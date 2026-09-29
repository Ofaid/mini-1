<#
.SYNOPSIS
    Securely updates one already-provisioned Minimum radio in place.

.DESCRIPTION
    Validates the extracted Release bundle and signed APK, selects one supported radio, verifies
    the installed package/signer/version and managed identity, performs an in-place update, runs
    only approved version/model-gated migrations, and verifies same-ID Ready. Reports never persist
    Android/USB serials, subscriber identifiers, credentials, certificate fingerprints or logs.
#>

[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$Serial = "",
    [int]$TransportId = 0,
    [ValidateRange(0, 65535)][int]$AdbPort = 0,
    [string]$BundleRoot = "",
    [string]$ReportDirectory = "",
    [switch]$UpdateSession,
    [switch]$ReportOnly,
    [switch]$AllowDowngrade,
    [switch]$FullRebootAcceptance,
    [switch]$ConfirmNotTransmitting,
    [switch]$NonInteractive,
    [ValidateRange(30, 900)][int]$ReadyTimeoutSeconds = 180,
    [ValidateRange(30, 900)][int]$BootTimeoutSeconds = 180,
    [Parameter(DontShow = $true)][switch]$LibraryOnly
)

$ErrorActionPreference = "Stop"
$MinimumPackage = "se.lublin.mumla"
$MinimumActivity = "se.lublin.mumla/.radio.RadioShellActivity"
$ProvisionReceiver = "se.lublin.mumla/.radio.RadioProvisionReceiver"
$ExistingIdentityReportAction = "se.lublin.mumla.action.PROVISION_REPORT_EXISTING_IDENTITY"
$LegacyIdentityReportAction = "se.lublin.mumla.action.PROVISION_REPORT_IDENTITY"
$ProvisionStatusAction = "se.lublin.mumla.action.PROVISION_REPORT_STATUS"
$script:AdbExecutable = ""
$script:ServerArguments = @()
$script:CurrentTarget = $null

function Get-DeviceProfile {
    param([string]$Manufacturer, [string]$Model)
    if ($Manufacturer -ieq "UNIPRO" -and $Model -ieq "ZX") { return "T56" }
    if ($Manufacturer -ieq "Youdotech" -and $Model -ieq "QM011") { return "T99" }
    if ($Manufacturer -ieq "ELINK" -and $Model -ieq "ym_258") { return "RYKS" }
    return ""
}

function ConvertTo-SafeMessage {
    param([AllowNull()][string]$Text)
    if (-not $Text) { return "" }
    $safe = $Text
    $safe = [regex]::Replace($safe, '(?i)\b(serial|imei|imsi|iccid|phone|token|password|secret)\s*[=:]\s*[^\s;,]+', '$1=<redacted>')
    $safe = [regex]::Replace($safe, '(?i)\b(?:[0-9a-f]{2}:){31}[0-9a-f]{2}\b', '<redacted-fingerprint>')
    $safe = [regex]::Replace($safe, '(?i)\b(?:gh[pousr]_[A-Za-z0-9]{20,}|Bearer\s+[A-Za-z0-9._~-]+)\b', '<redacted-token>')
    return $safe
}

function Get-ErrorCategory {
    param([string]$Message)
    $match = [regex]::Match($Message, '^\[([A-Z0-9_]+)\]\s*')
    if ($match.Success) { return $match.Groups[1].Value }
    return "UNEXPECTED_FAILURE"
}

function Throw-UpdateError {
    param([Parameter(Mandatory)][string]$Code, [Parameter(Mandatory)][string]$Message)
    throw "[$Code] $Message"
}

function Get-FileSha256 {
    param([Parameter(Mandatory)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()
}

function Test-Sha256Value {
    param([string]$Expected, [string]$Actual)
    return $Expected -match '^[0-9A-Fa-f]{64}$' -and $Actual -match '^[0-9A-Fa-f]{64}$' -and
        $Expected.ToUpperInvariant() -ceq $Actual.ToUpperInvariant()
}

function Read-UInt16LittleEndian {
    param([byte[]]$Bytes, [int]$Offset)
    return [BitConverter]::ToUInt16($Bytes, $Offset)
}

function Read-UInt32LittleEndian {
    param([byte[]]$Bytes, [int]$Offset)
    return [BitConverter]::ToUInt32($Bytes, $Offset)
}

function Read-AxmlLength8 {
    param([byte[]]$Bytes, [ref]$Offset)
    $value = [int]$Bytes[$Offset.Value]
    $Offset.Value++
    if (($value -band 0x80) -ne 0) {
        $value = (($value -band 0x7f) -shl 8) -bor [int]$Bytes[$Offset.Value]
        $Offset.Value++
    }
    return $value
}

function Read-AxmlLength16 {
    param([byte[]]$Bytes, [ref]$Offset)
    $value = [int](Read-UInt16LittleEndian -Bytes $Bytes -Offset $Offset.Value)
    $Offset.Value += 2
    if (($value -band 0x8000) -ne 0) {
        $second = [int](Read-UInt16LittleEndian -Bytes $Bytes -Offset $Offset.Value)
        $Offset.Value += 2
        $value = (($value -band 0x7fff) -shl 16) -bor $second
    }
    return $value
}

function Read-AxmlStringPool {
    param([byte[]]$Bytes, [int]$ChunkOffset)
    $headerSize = [int](Read-UInt16LittleEndian -Bytes $Bytes -Offset ($ChunkOffset + 2))
    $chunkSize = [int](Read-UInt32LittleEndian -Bytes $Bytes -Offset ($ChunkOffset + 4))
    $count = [int](Read-UInt32LittleEndian -Bytes $Bytes -Offset ($ChunkOffset + 8))
    $flags = [int](Read-UInt32LittleEndian -Bytes $Bytes -Offset ($ChunkOffset + 16))
    $stringsStart = [int](Read-UInt32LittleEndian -Bytes $Bytes -Offset ($ChunkOffset + 20))
    if ($headerSize -lt 28 -or $chunkSize -lt $headerSize -or $count -lt 1 -or $count -gt 100000) {
        Throw-UpdateError "APK_IDENTITY_INVALID" "The APK binary manifest has an invalid string pool."
    }
    $utf8 = ($flags -band 0x100) -ne 0
    $values = New-Object System.Collections.Generic.List[string]
    for ($index = 0; $index -lt $count; $index++) {
        $relative = [int](Read-UInt32LittleEndian -Bytes $Bytes -Offset ($ChunkOffset + $headerSize + 4 * $index))
        $cursor = $ChunkOffset + $stringsStart + $relative
        if ($cursor -lt 0 -or $cursor -ge $Bytes.Length) {
            Throw-UpdateError "APK_IDENTITY_INVALID" "The APK binary manifest contains an invalid string offset."
        }
        if ($utf8) {
            [void](Read-AxmlLength8 -Bytes $Bytes -Offset ([ref]$cursor))
            $byteLength = Read-AxmlLength8 -Bytes $Bytes -Offset ([ref]$cursor)
            if ($cursor + $byteLength -gt $Bytes.Length) { Throw-UpdateError "APK_IDENTITY_INVALID" "The APK manifest string is truncated." }
            $values.Add([Text.Encoding]::UTF8.GetString($Bytes, $cursor, $byteLength))
        } else {
            $charLength = Read-AxmlLength16 -Bytes $Bytes -Offset ([ref]$cursor)
            $byteLength = $charLength * 2
            if ($cursor + $byteLength -gt $Bytes.Length) { Throw-UpdateError "APK_IDENTITY_INVALID" "The APK manifest string is truncated." }
            $values.Add([Text.Encoding]::Unicode.GetString($Bytes, $cursor, $byteLength))
        }
    }
    return $values.ToArray()
}

function Get-AxmlString {
    param([string[]]$Pool, [uint32]$Index)
    if ($Index -eq [uint32]::MaxValue) { return $null }
    if ($Index -ge $Pool.Count) { Throw-UpdateError "APK_IDENTITY_INVALID" "The APK manifest references an invalid string." }
    return $Pool[[int]$Index]
}

function Get-ApkManifestIdentity {
    param([Parameter(Mandatory)][string]$ApkPath)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($ApkPath)
    try {
        $entry = $archive.GetEntry("AndroidManifest.xml")
        if (-not $entry) { Throw-UpdateError "APK_IDENTITY_INVALID" "The APK has no AndroidManifest.xml." }
        $stream = $entry.Open()
        $memory = New-Object IO.MemoryStream
        try { $stream.CopyTo($memory); $bytes = $memory.ToArray() } finally { $stream.Dispose(); $memory.Dispose() }
    } finally { $archive.Dispose() }
    if ($bytes.Length -lt 16 -or (Read-UInt16LittleEndian -Bytes $bytes -Offset 0) -ne 3) {
        Throw-UpdateError "APK_IDENTITY_INVALID" "AndroidManifest.xml is not a valid binary XML document."
    }
    $declaredSize = [int](Read-UInt32LittleEndian -Bytes $bytes -Offset 4)
    if ($declaredSize -gt $bytes.Length -or $declaredSize -lt 8) { Throw-UpdateError "APK_IDENTITY_INVALID" "The APK manifest size is invalid." }
    $pool = $null
    $offset = [int](Read-UInt16LittleEndian -Bytes $bytes -Offset 2)
    while ($offset + 8 -le $declaredSize) {
        $type = [int](Read-UInt16LittleEndian -Bytes $bytes -Offset $offset)
        $header = [int](Read-UInt16LittleEndian -Bytes $bytes -Offset ($offset + 2))
        $size = [int](Read-UInt32LittleEndian -Bytes $bytes -Offset ($offset + 4))
        if ($header -lt 8 -or $size -lt $header -or $offset + $size -gt $declaredSize) {
            Throw-UpdateError "APK_IDENTITY_INVALID" "The APK manifest contains an invalid chunk."
        }
        if ($type -eq 1) { $pool = @(Read-AxmlStringPool -Bytes $bytes -ChunkOffset $offset) }
        if ($type -eq 0x0102 -and $pool) {
            $elementName = Get-AxmlString -Pool $pool -Index (Read-UInt32LittleEndian -Bytes $bytes -Offset ($offset + 20))
            if ($elementName -ceq "manifest") {
                $attributeStart = [int](Read-UInt16LittleEndian -Bytes $bytes -Offset ($offset + 24))
                $attributeSize = [int](Read-UInt16LittleEndian -Bytes $bytes -Offset ($offset + 26))
                $attributeCount = [int](Read-UInt16LittleEndian -Bytes $bytes -Offset ($offset + 28))
                if ($attributeSize -lt 20 -or $attributeCount -gt 256) { Throw-UpdateError "APK_IDENTITY_INVALID" "The APK manifest attributes are invalid." }
                $values = @{}
                for ($index = 0; $index -lt $attributeCount; $index++) {
                    $attributeOffset = $offset + 16 + $attributeStart + ($index * $attributeSize)
                    if ($attributeOffset + 20 -gt $offset + $size) { Throw-UpdateError "APK_IDENTITY_INVALID" "The APK manifest attribute is truncated." }
                    $name = Get-AxmlString -Pool $pool -Index (Read-UInt32LittleEndian -Bytes $bytes -Offset ($attributeOffset + 4))
                    $rawIndex = Read-UInt32LittleEndian -Bytes $bytes -Offset ($attributeOffset + 8)
                    $dataType = [int]$bytes[$attributeOffset + 15]
                    $data = Read-UInt32LittleEndian -Bytes $bytes -Offset ($attributeOffset + 16)
                    if ($rawIndex -ne [uint32]::MaxValue) { $value = Get-AxmlString -Pool $pool -Index $rawIndex }
                    elseif ($dataType -eq 3) { $value = Get-AxmlString -Pool $pool -Index $data }
                    elseif ($dataType -in @(0x10, 0x11)) { $value = [string]$data }
                    else { continue }
                    $values[$name] = $value
                }
                if (-not $values.ContainsKey("package") -or -not $values.ContainsKey("versionCode") -or -not $values.ContainsKey("versionName")) {
                    Throw-UpdateError "APK_IDENTITY_INVALID" "The APK manifest identity fields are incomplete."
                }
                return [pscustomobject]@{ ApplicationId = $values["package"]; VersionCode = [long]$values["versionCode"]; VersionName = $values["versionName"] }
            }
        }
        $offset += $size
    }
    Throw-UpdateError "APK_IDENTITY_INVALID" "The APK manifest element could not be verified."
}

function Assert-SafeRelativePath {
    param([Parameter(Mandatory)][string]$Path)
    if (-not $Path -or $Path -match '\\' -or $Path.StartsWith('/') -or
            $Path -match '(^|/)\.\.?(/|$)' -or $Path -match '(^|/)\.(?:git|secrets)(/|$)' -or
            [IO.Path]::IsPathRooted($Path)) {
        Throw-UpdateError "BUNDLE_PATH_UNSAFE" "Release manifest contains an unsafe bundle path."
    }
}

function Read-ReleaseBundle {
    param([Parameter(Mandatory)][string]$Root)
    $resolvedRoot = (Resolve-Path -LiteralPath $Root).Path
    $manifestPath = Join-Path $resolvedRoot "RELEASE-MANIFEST.json"
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        Throw-UpdateError "MANIFEST_MISSING" "RELEASE-MANIFEST.json is missing. Use a complete reviewed Release ZIP."
    }
    try {
        $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch {
        Throw-UpdateError "MANIFEST_INVALID" "The release manifest is not valid JSON."
    }
    $required = @("schemaVersion", "releaseTag", "applicationId", "versionCode", "versionName",
        "apkFile", "apkSha256", "signerSha256", "rebootRequired", "migrations", "files")
    $names = @($manifest.PSObject.Properties.Name)
    if (@($required | Where-Object { $_ -notin $names }).Count -gt 0 -or
            @($names | Where-Object { $_ -notin $required }).Count -gt 0) {
        Throw-UpdateError "MANIFEST_SCHEMA" "The release manifest schema does not match the reviewed updater contract."
    }
    if ([int]$manifest.schemaVersion -ne 1 -or
            [string]$manifest.applicationId -cne "se.lublin.mumla" -or
            [string]$manifest.releaseTag -cne [string]$manifest.versionName -or
            [string]$manifest.releaseTag -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(?:[-.][0-9A-Za-z.-]+)?$' -or
            [long]$manifest.versionCode -le 0 -or
            [string]$manifest.apkFile -cne "minimum-foss.apk" -or
            [string]$manifest.apkSha256 -notmatch '^[0-9A-Fa-f]{64}$' -or
            [string]$manifest.signerSha256 -notmatch '^[0-9A-Fa-f]{64}$') {
        Throw-UpdateError "MANIFEST_IDENTITY" "The release manifest has an invalid package, version, APK or signer identity."
    }
    $versionText = (Get-Content -LiteralPath (Join-Path $resolvedRoot "VERSION.txt") -Raw).Trim()
    if ($versionText -cne [string]$manifest.releaseTag) {
        Throw-UpdateError "VERSION_BINDING" "VERSION.txt does not match the exact release tag in the manifest."
    }
    $listed = @{}
    foreach ($entry in @($manifest.files)) {
        $entryNames = @($entry.PSObject.Properties.Name)
        if ($entryNames.Count -ne 2 -or "path" -notin $entryNames -or "sha256" -notin $entryNames) {
            Throw-UpdateError "MANIFEST_FILES" "A release-manifest file entry has an unexpected shape."
        }
        $relative = [string]$entry.path
        Assert-SafeRelativePath -Path $relative
        if ($listed.ContainsKey($relative)) {
            Throw-UpdateError "MANIFEST_FILES" "The release manifest contains a duplicate file path."
        }
        if ([string]$entry.sha256 -notmatch '^[0-9A-Fa-f]{64}$') {
            Throw-UpdateError "MANIFEST_FILES" "A release-manifest file checksum is invalid."
        }
        $listed[$relative] = ([string]$entry.sha256).ToUpperInvariant()
    }
    $approvedFiles = @(
        "Provision Minimum Device.cmd",
        "README.txt",
        "UPDATER-README.md",
        "Update Minimum Device.cmd",
        "VERSION.txt",
        "assets/t99-wifi-provisioner.apk",
        "minimum-foss.apk",
        "minimum-foss.apk.sha256",
        "CELLULAR-README.md",
        "scripts/manage-cellular.ps1",
        "scripts/prepare-ryks.ps1",
        "scripts/prepare-t56.ps1",
        "scripts/prepare-t99.ps1",
        "scripts/provision-minimum-device.ps1",
        "scripts/update-minimum-device.ps1"
    ) | Sort-Object
    $manifestFiles = @($listed.Keys | Sort-Object)
    if (($manifestFiles -join "`n") -cne ($approvedFiles -join "`n")) {
        Throw-UpdateError "BUNDLE_ALLOWLIST" "Release manifest files differ from the updater's exact reviewed allowlist."
    }
    $specialEntry = Get-ChildItem -LiteralPath $resolvedRoot -Recurse -Force | Where-Object {
        $_.Attributes -band [IO.FileAttributes]::ReparsePoint
    } | Select-Object -First 1
    if ($specialEntry) {
        Throw-UpdateError "BUNDLE_SPECIAL_FILE" "The extracted bundle contains a link or reparse point."
    }
    $actual = @(Get-ChildItem -LiteralPath $resolvedRoot -Recurse -Force -File | ForEach-Object {
        $_.FullName.Substring($resolvedRoot.Length).TrimStart('\', '/').Replace('\', '/')
    } | Where-Object { $_ -cne "RELEASE-MANIFEST.json" } | Sort-Object)
    $expected = $manifestFiles
    if (($actual -join "`n") -cne ($expected -join "`n")) {
        Throw-UpdateError "BUNDLE_ALLOWLIST" "Extracted bundle files differ from the exact release manifest allowlist."
    }
    foreach ($relative in $expected) {
        $path = Join-Path $resolvedRoot $relative.Replace('/', '\')
        $item = Get-Item -LiteralPath $path -Force
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            Throw-UpdateError "BUNDLE_SPECIAL_FILE" "The extracted bundle contains a link or reparse-point file."
        }
        $actualHash = Get-FileSha256 -Path $path
        if (-not (Test-Sha256Value -Expected $listed[$relative] -Actual $actualHash)) {
            Throw-UpdateError "BUNDLE_CHECKSUM" "A bundled file does not match the exact release manifest checksum: $relative"
        }
    }
    $apkPath = Join-Path $resolvedRoot ([string]$manifest.apkFile)
    $apkHash = Get-FileSha256 -Path $apkPath
    if (-not (Test-Sha256Value -Expected ([string]$manifest.apkSha256) -Actual $apkHash)) {
        Throw-UpdateError "APK_CHECKSUM" "minimum-foss.apk does not match the manifest checksum."
    }
    $checksumLine = (Get-Content -LiteralPath (Join-Path $resolvedRoot "minimum-foss.apk.sha256") -Raw).Trim()
    $checksumMatch = [regex]::Match($checksumLine, '^([0-9A-Fa-f]{64})\s+\*?minimum-foss\.apk$')
    if (-not $checksumMatch.Success -or
            -not (Test-Sha256Value -Expected $checksumMatch.Groups[1].Value -Actual $apkHash)) {
        Throw-UpdateError "APK_CHECKSUM_FILE" "minimum-foss.apk.sha256 is not an exact checksum binding for minimum-foss.apk."
    }
    return [pscustomobject]@{ Root = $resolvedRoot; Manifest = $manifest; ApkPath = $apkPath }
}

function Resolve-ApkSigner {
    $command = Get-Command apksigner, apksigner.bat -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command) { return $command.Source }
    $sdkRoots = @(@($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT) | Where-Object { $_ })
    if ($env:LOCALAPPDATA) {
        $sdkRoots += Join-Path $env:LOCALAPPDATA "Android\Sdk"
    }
    $candidate = Find-ApkSignerInSdkRoots -SdkRoots $sdkRoots
    if ($candidate) { return $candidate }
    Throw-UpdateError "APKSIGNER_MISSING" "Android Build Tools apksigner is required to cryptographically verify the APK. Install Android Platform/Build Tools and rerun; no installation was attempted."
}

function Find-ApkSignerInSdkRoots {
    param([string[]]$SdkRoots)
    foreach ($sdkRoot in $sdkRoots) {
        if (-not $sdkRoot) { continue }
        $buildTools = Join-Path $sdkRoot "build-tools"
        if (-not (Test-Path -LiteralPath $buildTools -PathType Container)) { continue }
        $candidate = Get-ChildItem -LiteralPath $buildTools -Directory | Sort-Object Name -Descending |
            ForEach-Object {
                Join-Path $_.FullName "apksigner"
                Join-Path $_.FullName "apksigner.bat"
            } |
            Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
        if ($candidate) { return $candidate }
    }
    return $null
}

function Parse-ApkSignerOutput {
    param([string]$Text)
    # Process capture returns raw streams; accept only anchored apksigner structural
    # lines after removing terminal controls. PEM certificates are authoritative.
    $normalized = [regex]::Replace($Text, '\x1B\[[0-?]*[ -/]*[@-~]', '')
    $textDigests = @([regex]::Matches($normalized,
        '(?im)^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-f]{64})\s*$') |
        ForEach-Object { $_.Groups[1].Value.ToUpperInvariant() })
    $pemBlocks = @([regex]::Matches($normalized,
        '(?s)-----BEGIN CERTIFICATE-----\s*(.*?)\s*-----END CERTIFICATE-----'))
    $pemDigests = @($pemBlocks | ForEach-Object {
        try {
            $der = [Convert]::FromBase64String(([regex]::Replace($_.Groups[1].Value, '\s', '')))
            $certificate = New-Object Security.Cryptography.X509Certificates.X509Certificate2 -ArgumentList @(,$der)
            $sha256 = [Security.Cryptography.SHA256]::Create()
            try { ([BitConverter]::ToString($sha256.ComputeHash($certificate.RawData))).Replace('-', '') }
            finally { $sha256.Dispose(); $certificate.Dispose() }
        } catch {
            Throw-UpdateError "APK_SIGNATURE_INVALID" "apksigner returned an invalid signer certificate."
        }
    })
    $countLines = @([regex]::Matches($normalized, '(?im)^Number of signers:\s*([0-9]+)\s*$'))
    if ($countLines.Count -ne 1 -or $pemDigests.Count -eq 0 -or
            [int]$countLines[0].Groups[1].Value -ne $pemDigests.Count -or
            @($pemDigests | Select-Object -Unique).Count -ne $pemDigests.Count) {
        Throw-UpdateError "APK_SIGNATURE_INVALID" "apksigner did not report an exact verified signer certificate set."
    }
    if ($textDigests.Count -gt 0) {
        $textSet = @($textDigests | Sort-Object)
        $pemSet = @($pemDigests | Sort-Object)
        if ($textDigests.Count -ne $pemDigests.Count -or
                @($textDigests | Select-Object -Unique).Count -ne $textDigests.Count -or
                (Compare-Object $textSet $pemSet)) {
            Throw-UpdateError "APK_SIGNATURE_INVALID" "apksigner textual and certificate signer sets disagree."
        }
    }
    return $pemDigests
}

function Stop-ApkSignerProcess {
    param([Parameter(Mandatory)]$Process)
    try {
        $killTree = $Process.GetType().GetMethod("Kill", [type[]]@([bool]))
        if ($killTree) { $killTree.Invoke($Process, @($true)) | Out-Null }
        elseif ($env:OS -ceq "Windows_NT") {
            # .NET Framework (Windows PowerShell 5.1) lacks Kill(Boolean).
            # taskkill's numeric PID argument avoids shell parsing and terminates
            # cmd.exe plus any batch-launched Java descendants holding our pipes.
            $stop = New-Object System.Diagnostics.ProcessStartInfo
            $stop.FileName = Join-Path $env:SystemRoot "System32\taskkill.exe"
            $stop.Arguments = "/PID $($Process.Id) /T /F"
            $stop.UseShellExecute = $false
            $stop.CreateNoWindow = $true
            $killer = [Diagnostics.Process]::Start($stop)
            if ($killer) {
                $killer.WaitForExit(2000) | Out-Null
                $killer.Dispose()
            }
        } else { $Process.Kill() }
    } catch { }
}

function Invoke-ApkSignerProcess {
    param(
        [Parameter(Mandatory)][string]$ApkSigner,
        [Parameter(Mandatory)][string]$ApkPath,
        [int]$TimeoutMilliseconds = 30000,
        [int]$DrainTimeoutMilliseconds = 2000
    )
    # Do not use PowerShell's native-command stream redirection here. On Linux,
    # pwsh can wrap output from the extensionless apksigner launcher as error
    # records, losing the certificate lines when those records are stringified.
    # Process captures the launcher's raw stdout/stderr on every supported host.
    if ($ApkPath.IndexOfAny(@([char]0, [char]10, [char]13, [char]34)) -ge 0 -or
            $ApkSigner.IndexOfAny(@([char]0, [char]10, [char]13, [char]34)) -ge 0) {
        Throw-UpdateError "APK_SIGNATURE_INVALID" "Unsafe character in the APK or apksigner path."
    }
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    if ($ApkSigner.EndsWith(".bat", [StringComparison]::OrdinalIgnoreCase)) {
        # cmd expands percent variables even inside quotes. Refuse percent rather
        # than allow either path to be rewritten before the reviewed tool runs.
        if ($ApkPath.Contains('%') -or $ApkSigner.Contains('%')) {
            Throw-UpdateError "APK_SIGNATURE_INVALID" "Unsafe character in the Windows APK or apksigner path."
        }
        if (-not $env:ComSpec) {
            Throw-UpdateError "APKSIGNER_MISSING" "The Windows command processor required for apksigner.bat is unavailable."
        }
        $start.FileName = $env:ComSpec
        $start.Arguments = "/d /s /v:off /c `"`"$ApkSigner`" verify --verbose --print-certs --print-certs-pem `"$ApkPath`"`""
    } else {
        $start.FileName = $ApkSigner
        if ($start.PSObject.Properties["ArgumentList"]) {
            # .NET Core exposes a true argv collection. Use it for the Unix
            # extensionless launcher so paths are never reparsed as one string.
            $start.ArgumentList.Add("verify")
            $start.ArgumentList.Add("--verbose")
            $start.ArgumentList.Add("--print-certs")
            $start.ArgumentList.Add("--print-certs-pem")
            $start.ArgumentList.Add($ApkPath)
        } else {
            # Windows PowerShell 5.1 has no ArgumentList; extensionless launchers
            # are unusual there, but retain safe quote-delimited compatibility.
            $start.Arguments = "verify --verbose --print-certs --print-certs-pem `"$ApkPath`""
        }
    }
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $start
    try {
        if (-not $process.Start()) {
            Throw-UpdateError "APK_SIGNATURE_INVALID" "apksigner could not be started."
        }
        # Drain both pipes concurrently so a noisy rejected file cannot fill one
        # pipe and deadlock while the other is read. Signature verification is
        # local and bounded; a hung tool is killed and refused after 30 seconds.
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutMilliseconds)) {
            Stop-ApkSignerProcess $process
            Throw-UpdateError "APK_SIGNATURE_INVALID" "apksigner timed out; no installation was attempted."
        }
        $tasks = [Threading.Tasks.Task[]]@($stdoutTask, $stderrTask)
        if (-not [Threading.Tasks.Task]::WaitAll($tasks, $DrainTimeoutMilliseconds)) {
            Stop-ApkSignerProcess $process
            Throw-UpdateError "APK_SIGNATURE_INVALID" "apksigner output pipes did not close; no installation was attempted."
        }
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        $exitCode = $process.ExitCode
    } catch {
        if ($_.Exception.Message -match '^\[APK_SIGNATURE_INVALID\]') { throw }
        Throw-UpdateError "APK_SIGNATURE_INVALID" "apksigner could not be executed; no installation was attempted."
    } finally {
        $process.Dispose()
    }
    return [pscustomobject]@{ ExitCode=$exitCode; Stdout=$stdout; Stderr=$stderr }
}

function Get-ApkSignerDigests {
    param([Parameter(Mandatory)][string]$ApkPath)
    $apksigner = Resolve-ApkSigner
    $result = Invoke-ApkSignerProcess -ApkSigner $apksigner -ApkPath $ApkPath
    $exitCode = $result.ExitCode
    $stdout = $result.Stdout
    $stderr = $result.Stderr
    $text = (($stdout, $stderr) -join "`n").Trim()
    if ($exitCode -ne 0) {
        Throw-UpdateError "APK_SIGNATURE_INVALID" "apksigner rejected the APK signature; no installation was attempted."
    }
    return @(Parse-ApkSignerOutput -Text $text)
}

function Assert-SignerCompatibility {
    param([string[]]$InstalledDigests, [Parameter(Mandatory)][string]$TargetDigest)
    $installed = @($InstalledDigests)
    if ($installed.Count -ne 1 -or $installed[0] -cne $TargetDigest.ToUpperInvariant()) {
        Throw-UpdateError "SIGNER_MISMATCH" "Installed Minimum and the Release APK use different signing certificates. No uninstall or data clear was attempted. A debug-to-release switch requires an explicitly reviewed manual recovery."
    }
}

function Compare-VersionCode {
    param([long]$Installed, [long]$Target)
    if ($Installed -lt $Target) { return -1 }
    if ($Installed -gt $Target) { return 1 }
    return 0
}

function Convert-AdbDeviceLines {
    param([string[]]$Lines)
    $records = foreach ($line in $Lines) {
        if ($line -match '^([^\s]+)\s+(device|unauthorized|offline|recovery)(?:\s|$)') {
            $recordSerial = $Matches[1]
            $recordState = $Matches[2]
            $transport = 0
            if ($line -match '\btransport_id:(\d+)\b') { $transport = [int]$Matches[1] }
            [pscustomobject]@{ Serial = $recordSerial; State = $recordState; TransportId = $transport }
        }
    }
    return @($records)
}

function Select-TargetRecord {
    param([object[]]$Records, [string]$RequestedSerial = "", [int]$RequestedTransportId = 0)
    $authorized = @($Records | Where-Object { $_.State -eq "device" })
    if ($RequestedTransportId -gt 0) {
        $matches = @($authorized | Where-Object { $_.TransportId -eq $RequestedTransportId })
        if ($matches.Count -ne 1) { Throw-UpdateError "TARGET_NOT_FOUND" "The selected authorized ADB transport was not found exactly once." }
        return $matches[0]
    }
    if ($RequestedSerial) {
        $matches = @($authorized | Where-Object { $_.Serial -ceq $RequestedSerial })
        if ($matches.Count -ne 1) { Throw-UpdateError "SERIAL_AMBIGUOUS" "The selected ADB serial was not found exactly once; use -TransportId for a duplicate serial." }
        return $matches[0]
    }
    if ($authorized.Count -ne 1) {
        if ($authorized.Count -eq 0 -and @($Records).Count -gt 0) {
            Throw-UpdateError "TARGET_NOT_AUTHORIZED" "No device is in the authorized normal Android state. Unlock it and authorize USB debugging."
        }
        Throw-UpdateError "TARGET_COUNT" "Connect exactly one authorized radio, or use -Serial/-TransportId explicitly."
    }
    return $authorized[0]
}

function Find-ReturningCandidate {
    param([object[]]$Records, [string]$Manufacturer, [string]$Model, [string]$OriginalSerial,
        [string]$ExpectedDeviceId = "")
    $sameSerial = @($Records | Where-Object {
        $_.State -eq "device" -and $_.Serial -ceq $OriginalSerial -and
        $_.Manufacturer -ieq $Manufacturer -and $_.Model -ieq $Model
    })
    if ($sameSerial.Count -eq 1) { return $sameSerial[0] }
    $sameModel = @($Records | Where-Object {
        $_.State -eq "device" -and $_.Manufacturer -ieq $Manufacturer -and $_.Model -ieq $Model
    })
    if ($ExpectedDeviceId) {
        $identityMatches = @($sameModel | Where-Object {
            $_.PSObject.Properties.Name -contains "DeviceId" -and $_.DeviceId -ceq $ExpectedDeviceId
        })
        if ($identityMatches.Count -eq 1) { return $identityMatches[0] }
    }
    return $null
}

function Parse-PackageState {
    param([string]$Text)
    $code = [regex]::Match($Text, '(?m)^\s*versionCode=(\d+)\b')
    $name = [regex]::Match($Text, '(?m)^\s*versionName=([^\r\n]+)$')
    if (-not $code.Success -or -not $name.Success) { return $null }
    return [pscustomobject]@{ VersionCode = [long]$code.Groups[1].Value; VersionName = $name.Groups[1].Value.Trim() }
}

function Parse-ProvisioningStatus {
    param([string]$Text)
    $corePattern = 'data="?deviceId=([A-Z0-9]{6});activeDeviceId=([A-Z0-9*]{1,6});configVersion=(-?\d+);pending=(true|false);lastSuccessMs=(\d+)'
    $core = [regex]::Match($Text, $corePattern)
    if (-not $core.Success) { return $null }
    $extended = [regex]::Match($Text, $corePattern + ';selectedChannel=([a-zA-Z0-9._-]{0,64});activeConfigSha256=([0-9A-F]{64});safeSettingsSha256=([0-9A-F]{64})"?')
    return [pscustomobject]@{
        SnapshotLevel = if ($extended.Success) { "EXTENDED" } else { "LEGACY" }
        DeviceId = $core.Groups[1].Value
        ActiveDeviceId = $core.Groups[2].Value
        ConfigVersion = [int]$core.Groups[3].Value
        Pending = $core.Groups[4].Value -eq "true"
        LastSuccessMs = [long]$core.Groups[5].Value
        SelectedChannel = if ($extended.Success) { $extended.Groups[6].Value } else { "UNAVAILABLE_LEGACY" }
        ActiveConfigSha256 = if ($extended.Success) { $extended.Groups[7].Value } else { "UNAVAILABLE_LEGACY" }
        SafeSettingsSha256 = if ($extended.Success) { $extended.Groups[8].Value } else { "UNAVAILABLE_LEGACY" }
    }
}

function Assert-LegacyBridgeEligible {
    param($Status, [long]$InstalledVersionCode, [long]$TargetVersionCode)
    if (-not $Status -or $Status.SnapshotLevel -cne "LEGACY" -or
            $Status.ActiveDeviceId -ceq "*" -or $Status.ActiveDeviceId -notmatch '^[A-Z0-9]{6}$' -or
            $Status.Pending -or $Status.ConfigVersion -le 0 -or $Status.LastSuccessMs -le 0) {
        Throw-UpdateError "LEGACY_NOT_PROVISIONED" "Legacy Minimum did not prove an existing active identity and Last Known Good configuration; the identity action was not called."
    }
    if ($InstalledVersionCode -gt 3070300 -or $TargetVersionCode -ne 3070301) {
        Throw-UpdateError "LEGACY_BRIDGE_UNSUPPORTED" "The limited legacy preservation bridge is approved only for an installed build at or below 3070300 updating to 3070301."
    }
}

function Assert-PreservedState {
    param([Parameter(Mandatory)]$Before, [Parameter(Mandatory)]$After, [string]$Phase)
    if ($After.DeviceId -cne $Before.DeviceId -or
            $After.ActiveDeviceId -cne $Before.ActiveDeviceId -or
            $After.Pending -or $After.ConfigVersion -lt $Before.ConfigVersion -or
            $After.LastSuccessMs -le 0) {
        Throw-UpdateError "STATE_PRESERVATION_FAILED" "Identity or Last Known Good configuration regressed $Phase."
    }
    if ($Before.SnapshotLevel -ceq "LEGACY") {
        if ($After.SnapshotLevel -cne "EXTENDED") {
            Throw-UpdateError "STATE_PRESERVATION_FAILED" "The updated app did not provide the required expanded preservation report $Phase."
        }
        return "BOOTSTRAPPED_POST_UPDATE"
    }
    if ($After.SnapshotLevel -cne "EXTENDED" -or
            $After.SelectedChannel -cne $Before.SelectedChannel -or
            $After.ActiveConfigSha256 -cne $Before.ActiveConfigSha256 -or
            $After.SafeSettingsSha256 -cne $Before.SafeSettingsSha256) {
        Throw-UpdateError "STATE_PRESERVATION_FAILED" "Identity, selected channel, safe device settings or Last Known Good configuration changed $Phase."
    }
    return "PRESERVED_EXTENDED"
}

function Get-RequiredMigrations {
    param([object[]]$ManifestMigrations, [long]$InstalledVersionCode, [long]$TargetVersionCode, [string]$Profile)
    $required = @()
    foreach ($migration in @($ManifestMigrations)) {
        $properties = @($migration.PSObject.Properties.Name)
        $migrationFields = @("id", "fromVersionCodeMax", "toVersionCode", "profiles", "rebootRequired", "irreversible")
        if (@($migrationFields | Where-Object { $_ -notin $properties }).Count -gt 0 -or
                @($properties | Where-Object { $_ -notin $migrationFields }).Count -gt 0 -or
                [string]$migration.id -notmatch '^[A-Z0-9][A-Z0-9_]{0,63}$' -or
                @($migration.profiles | Where-Object { $_ -notin @("T56", "T99", "RYKS") }).Count -gt 0) {
            Throw-UpdateError "MIGRATION_CONTRACT" "A release migration entry does not match the reviewed contract."
        }
        if ([string]$migration.id -cne "CELLULAR_POLICY_V1_T56" -or
                [long]$migration.fromVersionCodeMax -ne 3070300 -or
                [long]$migration.toVersionCode -ne 3070301 -or
                @($migration.profiles).Count -ne 1 -or
                [string]$migration.profiles[0] -cne "T56" -or
                $migration.rebootRequired -isnot [bool] -or -not [bool]$migration.rebootRequired -or
                $migration.irreversible -isnot [bool] -or [bool]$migration.irreversible) {
            Throw-UpdateError "MIGRATION_NOT_IMPLEMENTED" "Release requests a migration that this reviewed updater does not implement exactly."
        }
        if ($TargetVersionCode -ne [long]$migration.toVersionCode) {
            Throw-UpdateError "MIGRATION_CONTRACT" "The cellular migration is not bound to the exact target versionCode."
        }
        if ($Profile -in @($migration.profiles) -and
                $InstalledVersionCode -le [long]$migration.fromVersionCodeMax) {
            $required += $migration
        }
    }
    return @($required)
}

function New-MigrationResult {
    param(
        [string]$Id,
        [ValidateSet("APPLIED", "ALREADY_OK", "SKIPPED", "FAILED")][string]$Outcome,
        [string]$Detail = ""
    )
    return [pscustomobject]@{ Id = $Id; Outcome = $Outcome; Detail = $Detail }
}

function Invoke-CellularPolicyMigration {
    param([switch]$VerifyOnly)
    if ($script:CurrentTarget.Profile -ne "T56") {
        Throw-UpdateError "MIGRATION_PROFILE" "The T56 cellular policy was routed to a different hardware profile."
    }
    $cellularScript = Join-Path $PSScriptRoot "manage-cellular.ps1"
    if (-not (Test-Path -LiteralPath $cellularScript -PathType Leaf)) {
        Throw-UpdateError "MIGRATION_SCRIPT_MISSING" "The reviewed T56 cellular migration script is missing from the bundle."
    }
    $arguments = @("-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $cellularScript,
        "-AdbPort", "$AdbPort")
    if ($script:CurrentTarget.TransportId -gt 0) {
        $arguments += @("-TransportId", "$($script:CurrentTarget.TransportId)")
    } else {
        $arguments += @("-Serial", $script:CurrentTarget.Serial)
    }
    if ($VerifyOnly) { $arguments += "-VerifyOnly" }
    $output = @(& powershell.exe @arguments 2>&1)
    $exitCode = $LASTEXITCODE
    $safeLines = @($output | ForEach-Object { ConvertTo-SafeMessage -Text ([string]$_) })
    $text = ($safeLines -join "`n").Trim()
    if ($text) { Write-Host $text }
    if ($exitCode -notin @(0, 2)) {
        Throw-UpdateError "CELLULAR_POLICY_V1_T56" "The guarded T56 cellular migration failed verification."
    }
    $marker = [regex]::Match($text, '(?m)^MIGRATION_OUTCOME:\s*(APPLIED|ALREADY_OK)\s*$')
    if (-not $marker.Success) {
        Throw-UpdateError "MIGRATION_RESULT_INVALID" "The T56 cellular migration did not return its reviewed outcome marker."
    }
    $detail = if ($exitCode -eq 2) { "READINESS_WARN" } elseif ($VerifyOnly) { "POST_REBOOT_VERIFIED" } else { "VERIFIED" }
    return New-MigrationResult -Id "CELLULAR_POLICY_V1_T56" -Outcome $marker.Groups[1].Value -Detail $detail
}

function Invoke-RequiredMigration {
    param([Parameter(Mandatory)]$Migration, [switch]$VerifyOnly)
    switch -CaseSensitive ([string]$Migration.id) {
        "CELLULAR_POLICY_V1_T56" { return Invoke-CellularPolicyMigration -VerifyOnly:$VerifyOnly }
        default { Throw-UpdateError "MIGRATION_NOT_IMPLEMENTED" "The migration has no reviewed execution handler." }
    }
}

function Format-SessionSummary {
    param([object[]]$Results, [string]$TargetVersion)
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("Minimum update session")
    $lines.Add("Target version: $TargetVersion")
    foreach ($group in @($Results | Group-Object { if ($_.Profile) { $_.Profile } else { "UNKNOWN" } } | Sort-Object Name)) {
        $lines.Add("")
        $lines.Add("[$($group.Name)]")
        foreach ($result in @($group.Group)) {
            $deviceId = if ($result.DeviceId) { $result.DeviceId } else { "------" }
            $lines.Add(("{0}  {1}  {2}" -f $deviceId, $result.Result, $result.Detail))
        }
    }
    $pass = @($Results | Where-Object { $_.Result -eq "PASS" }).Count
    $warn = @($Results | Where-Object { $_.Result -eq "WARN" }).Count
    $fail = @($Results | Where-Object { $_.Result -eq "FAIL" }).Count
    $lines.Add("")
    $lines.Add("Totals: $pass PASS, $warn WARN, $fail FAIL")
    return $lines -join "`r`n"
}

function Invoke-AdbRaw {
    param([string[]]$Arguments)
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = @(& $script:AdbExecutable @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    $text = (($output | ForEach-Object { if ($_ -is [Management.Automation.ErrorRecord]) { $_.ToString() } else { [string]$_ } }) -join "`n").Trim()
    return [pscustomobject]@{ ExitCode = $exitCode; Output = $text }
}

function Get-ListeningAdbPorts {
    $ports = @()
    try {
        $ports = @(Get-NetTCPConnection -State Listen -ErrorAction Stop |
            Where-Object { $_.LocalPort -in @(5037, 5041) } | Select-Object -ExpandProperty LocalPort -Unique)
    } catch {
        foreach ($line in @(& netstat.exe -ano -p TCP 2>$null)) {
            if ($line -match '^\s*TCP\s+\S+:(5037|5041)\s+\S+\s+LISTENING\s+') { $ports += [int]$Matches[1] }
        }
    }
    return @($ports | Sort-Object -Unique)
}

function Get-AdbRecords {
    $result = Invoke-AdbRaw -Arguments ($script:ServerArguments + @("devices", "-l"))
    if ($result.ExitCode -ne 0) { Throw-UpdateError "ADB_QUERY" "Could not query the selected ADB server." }
    return @(Convert-AdbDeviceLines -Lines ($result.Output -split "`r?`n"))
}

function Get-ReturningAdbRecords {
    # Reviewed T56 firmware can return on the alternate local ADB server after reboot.
    $records = @()
    foreach ($port in @(5037, 5041)) {
        $result = Invoke-AdbRaw -Arguments @("-P", "$port", "devices", "-l")
        if ($result.ExitCode -ne 0) { continue }
        foreach ($record in @(Convert-AdbDeviceLines -Lines ($result.Output -split "`r?`n"))) {
            $record | Add-Member AdbPort $port -Force
            $records += $record
        }
    }
    return @($records)
}

function Set-TargetServerArguments {
    param([Parameter(Mandatory)]$Target)
    if ($Target.PSObject.Properties.Name -contains "AdbPort" -and $Target.AdbPort -gt 0) {
        $script:ServerArguments = @("-P", "$($Target.AdbPort)")
    }
}

function Select-AdbPort {
    if ($AdbPort -gt 0) { return $AdbPort }
    $listening = @(Get-ListeningAdbPorts)
    if ($listening.Count -eq 0) { return 5037 }
    $active = @()
    foreach ($port in $listening) {
        $probe = Invoke-AdbRaw -Arguments @("-P", "$port", "devices")
        if ($probe.ExitCode -eq 0 -and $probe.Output -match '(?m)^[^\s]+\s+(device|unauthorized|offline|recovery)(?:\s|$)') { $active += $port }
    }
    if ($active.Count -eq 1) { return $active[0] }
    if ($listening.Count -eq 1) { return $listening[0] }
    Throw-UpdateError "ADB_PORT_AMBIGUOUS" "Both supported ADB servers are active; pass -AdbPort 5037 or -AdbPort 5041."
}

function Get-TargetArguments {
    param([Parameter(Mandatory)]$Target)
    if ($Target.TransportId -gt 0) { return $script:ServerArguments + @("-t", "$($Target.TransportId)") }
    return $script:ServerArguments + @("-s", $Target.Serial)
}

function Invoke-TargetAdb {
    param([string[]]$Arguments, [switch]$AllowFailure)
    $result = Invoke-AdbRaw -Arguments ((Get-TargetArguments -Target $script:CurrentTarget) + $Arguments)
    if (-not $AllowFailure -and $result.ExitCode -ne 0) { Throw-UpdateError "ADB_COMMAND" "An ADB command failed for the selected target." }
    return $result
}

function Get-TargetProperty {
    param([string]$Name)
    return (Invoke-TargetAdb -Arguments @("shell", "getprop", $Name)).Output.Trim()
}

function Add-HardwareIdentity {
    param([Parameter(Mandatory)]$Target)
    $manufacturer = Get-TargetProperty -Name "ro.product.manufacturer"
    $model = Get-TargetProperty -Name "ro.product.model"
    $Target | Add-Member Manufacturer $manufacturer -Force
    $Target | Add-Member Model $model -Force
    $Target | Add-Member Profile (Get-DeviceProfile -Manufacturer $manufacturer -Model $model) -Force
    return $Target
}

function Get-Identity {
    param([switch]$Legacy)
    $action = if ($Legacy) { $LegacyIdentityReportAction } else { $ExistingIdentityReportAction }
    $result = Invoke-TargetAdb -Arguments @("shell", "am", "broadcast", "-W", "-a", $action, "-n", $ProvisionReceiver)
    $match = [regex]::Match($result.Output, 'data="?([A-Z0-9]{6})"?')
    if (-not $match.Success) { Throw-UpdateError "IDENTITY_UNREADABLE" "Minimum did not return its existing six-character Device ID." }
    return $match.Groups[1].Value
}

function Get-ProvisioningStatus {
    $result = Invoke-TargetAdb -Arguments @("shell", "am", "broadcast", "-W", "-a", $ProvisionStatusAction, "-n", $ProvisionReceiver)
    return Parse-ProvisioningStatus -Text $result.Output
}

function Get-InstalledPackageState {
    $pathResult = Invoke-TargetAdb -Arguments @("shell", "pm", "path", $MinimumPackage) -AllowFailure
    if ($pathResult.ExitCode -ne 0 -or $pathResult.Output -notmatch '(?m)^package:') {
        Throw-UpdateError "PACKAGE_NOT_INSTALLED" "Minimum is not installed; use Provision Minimum Device instead."
    }
    $dump = Invoke-TargetAdb -Arguments @("shell", "dumpsys", "package", $MinimumPackage)
    $state = Parse-PackageState -Text $dump.Output
    if (-not $state) { Throw-UpdateError "PACKAGE_VERSION_UNREADABLE" "The installed Minimum version could not be verified." }
    $base = @($pathResult.Output -split "`r?`n" | Where-Object { $_ -match '^package:.*/base\.apk$' } | Select-Object -First 1)
    if ($base.Count -ne 1) { Throw-UpdateError "PACKAGE_PATH_UNREADABLE" "The installed Minimum base APK path could not be verified." }
    $state | Add-Member BaseApkPath ($base[0].Substring(8)) -Force
    return $state
}

function Get-InstalledSignerDigests {
    param([string]$RemoteApkPath)
    $temporary = Join-Path ([IO.Path]::GetTempPath()) ("minimum-installed-{0}.apk" -f [guid]::NewGuid().ToString("N"))
    try {
        $pull = Invoke-TargetAdb -Arguments @("pull", $RemoteApkPath, $temporary) -AllowFailure
        if ($pull.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $temporary -PathType Leaf)) {
            Throw-UpdateError "INSTALLED_SIGNER_UNREADABLE" "The installed APK signer could not be read safely."
        }
        return @(Get-ApkSignerDigests -ApkPath $temporary)
    } finally {
        if (Test-Path -LiteralPath $temporary -PathType Leaf) { Remove-Item -LiteralPath $temporary -Force }
    }
}

function Get-BatteryState {
    $dump = (Invoke-TargetAdb -Arguments @("shell", "dumpsys", "battery")).Output
    $level = [regex]::Match($dump, '(?m)^\s*level:\s*(\d+)\s*$')
    $powered = $dump -match '(?m)^\s*(?:AC|USB|Wireless) powered:\s*true\s*$'
    if (-not $level.Success) { Throw-UpdateError "BATTERY_UNREADABLE" "Battery state could not be verified." }
    return [pscustomobject]@{ Level = [int]$level.Groups[1].Value; Powered = $powered }
}

function Get-ReadyState {
    $remote = "/sdcard/minimum-update-ready-$PID.xml"
    try {
        $dump = Invoke-TargetAdb -Arguments @("shell", "uiautomator", "dump", $remote) -AllowFailure
        if ($dump.ExitCode -ne 0) { return $false }
        $read = Invoke-TargetAdb -Arguments @("shell", "cat", $remote) -AllowFailure
        return $read.ExitCode -eq 0 -and $read.Output -match 'content-desc="minimum-state-ready"'
    } finally {
        Invoke-TargetAdb -Arguments @("shell", "rm", "-f", $remote) -AllowFailure | Out-Null
    }
}

function Wait-MinimumReady {
    param([string]$ExpectedDeviceId, [int]$TimeoutSeconds)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Get-ReadyState) {
            $status = Get-ProvisioningStatus
            if ($status -and $status.DeviceId -ceq $ExpectedDeviceId -and
                    $status.ActiveDeviceId -ceq $ExpectedDeviceId -and -not $status.Pending -and
                    $status.ConfigVersion -gt 0 -and $status.LastSuccessMs -gt 0) { return $status }
        }
        Start-Sleep -Seconds 5
    }
    Throw-UpdateError "READY_TIMEOUT" "Minimum did not reach same-ID Ready within the bounded timeout."
}

function Install-InPlace {
    param([string]$ApkPath, [switch]$Downgrade)
    $arguments = @("install", "-r")
    if ($Downgrade) { $arguments += "-d" }
    $arguments += $ApkPath
    $result = Invoke-TargetAdb -Arguments $arguments -AllowFailure
    if ($result.ExitCode -ne 0 -or $result.Output -notmatch '(?im)^Success\s*$') {
        if ($result.Output -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
            Throw-UpdateError "SIGNER_MISMATCH" "Android rejected the in-place update because the APK signers differ. No uninstall or data clear was attempted."
        }
        if ($result.Output -match 'INSTALL_FAILED_INSUFFICIENT_STORAGE') {
            Throw-UpdateError "INSUFFICIENT_STORAGE" "Android rejected the update because storage is insufficient; no app data was cleared."
        }
        Throw-UpdateError "INSTALL_FAILED" "The in-place APK update failed; the existing app data was not cleared."
    }
}

function Ensure-RyksInstallPolicy {
    if ($script:CurrentTarget.Profile -ne "RYKS") { return New-MigrationResult -Id "RYKS_INSTALL_POLICY" -Outcome "SKIPPED" }
    if ((Get-TargetProperty -Name "ro.build.install") -eq "1") { return New-MigrationResult -Id "RYKS_INSTALL_POLICY" -Outcome "ALREADY_OK" }
    Invoke-TargetAdb -Arguments @("shell", "setprop", "ro.build.install", "1") | Out-Null
    if ((Get-TargetProperty -Name "ro.build.install") -ne "1") {
        Throw-UpdateError "RYKS_INSTALL_POLICY" "RYKS firmware did not enable its model-gated APK install policy for this boot."
    }
    return New-MigrationResult -Id "RYKS_INSTALL_POLICY" -Outcome "APPLIED"
}

function Wait-ReturningTarget {
    param($OriginalTarget, [string]$ExpectedDeviceId, [int]$TimeoutSeconds)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    try {
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Seconds 2
            $candidates = @()
            foreach ($record in @(Get-ReturningAdbRecords | Where-Object { $_.State -eq "device" })) {
                $script:CurrentTarget = $record
                Set-TargetServerArguments -Target $record
                try { $candidates += Add-HardwareIdentity -Target $record } catch { }
            }
            # A serial or model match is only a candidate. Recovery commands are permitted only
            # after the installed app reports the expected existing Device ID on that candidate.
            foreach ($record in @($candidates | Where-Object {
                $_.Manufacturer -ieq $OriginalTarget.Manufacturer -and $_.Model -ieq $OriginalTarget.Model
            })) {
                $script:CurrentTarget = $record
                Set-TargetServerArguments -Target $record
                try {
                    $identity = Get-Identity
                    $record | Add-Member DeviceId $identity -Force
                    if ($identity -ceq $ExpectedDeviceId) {
                        $record | Add-Member CorrelatedDeviceId $identity -Force
                    }
                } catch { }
            }
            $candidate = Find-ReturningCandidate -Records $candidates -Manufacturer $OriginalTarget.Manufacturer `
                -Model $OriginalTarget.Model -OriginalSerial "" -ExpectedDeviceId $ExpectedDeviceId
            if ($candidate) {
                Set-TargetServerArguments -Target $candidate
                $script:CurrentTarget = $candidate
                return $candidate
            }
        }
        Throw-UpdateError "REBOOT_TARGET_AMBIGUOUS" "The same supported profile and Device ID could not be correlated uniquely after reboot."
    } catch {
        $script:CurrentTarget = $null
        throw
    }
}

function Get-LegacyExistingIdentityViaRunAs {
    # PreferenceManager's default file and the public identity key are stable in 3070300. The
    # remote shell uses only built-ins and emits only the public value, never the surrounding XML.
    $preferenceFile = "shared_prefs/$($MinimumPackage)_preferences.xml"
    $probeScript = 'while IFS= read -r line; do case "$line" in *''<string name="radio_device_id">''*) value=${line#*>}; value=${value%%<*}; printf ''%s\n'' "$value"; exit 0;; esac; done < "$1"; exit 3'
    $probe = Invoke-TargetAdb -Arguments @("shell", "run-as", $MinimumPackage, "sh", "-c", $probeScript, "minimum-probe", $preferenceFile) -AllowFailure
    if ($probe.ExitCode -ne 0) {
        Throw-UpdateError "LEGACY_NONCREATING_PROBE_UNAVAILABLE" "Legacy Minimum does not expose a non-creating identity probe on this signing/build channel; no receiver was called and no update was attempted."
    }
    $identity = $probe.Output.Trim()
    if ($identity -notmatch '^(?=.*[A-Z])(?=.*[0-9])[A-Z0-9]{6}$') {
        Throw-UpdateError "LEGACY_NONCREATING_PROBE_UNAVAILABLE" "No valid existing legacy identity was found by the non-creating app-private probe; no receiver was called and no update was attempted."
    }
    return $identity
}

function Parse-LegacyReadyUiEvidence {
    param([string]$WindowDump, [string]$UiXml)
    $focusedShell = $WindowDump -match '(?m)^\s*mCurrentFocus=.*\bse\.lublin\.mumla/(?:\.radio\.RadioShellActivity|se\.lublin\.mumla\.radio\.RadioShellActivity)\b'
    if (-not $focusedShell) {
        Throw-UpdateError "LEGACY_NONCREATING_PROBE_UNAVAILABLE" "Legacy Minimum was not already focused on RadioShell. Wake/unlock the radio and open the existing app manually, then retry; the updater did not start it or call a receiver."
    }
    $ready = $false
    $selectedChannel = ""
    foreach ($match in [regex]::Matches($UiXml, '(?is)<node\b[^>]*>')) {
        $node = $match.Value
        if ($node -notmatch '(?:^|\s)package="se\.lublin\.mumla"(?:\s|/?>)') { continue }
        if ($node -match '(?:^|\s)content-desc="minimum-state-ready"(?:\s|/?>)') { $ready = $true }
        $channel = [regex]::Match($node, '(?:^|\s)content-desc="Channel ([A-Za-z0-9._-]{1,64})"(?:\s|/?>)')
        if ($channel.Success) { $selectedChannel = $channel.Groups[1].Value }
    }
    if (-not $ready) {
        Throw-UpdateError "LEGACY_NONCREATING_PROBE_UNAVAILABLE" "The already-focused legacy app did not expose package-bound Ready evidence. Open the existing Ready screen manually and retry; no receiver was called."
    }
    return [pscustomobject]@{ Mode = "LEGACY_READY_UI"; Identity = ""; SelectedChannel = $selectedChannel }
}

function Get-LegacyReadyUiEvidence {
    $window = Invoke-TargetAdb -Arguments @("shell", "dumpsys", "window", "windows") -AllowFailure
    if ($window.ExitCode -ne 0) {
        Throw-UpdateError "LEGACY_NONCREATING_PROBE_UNAVAILABLE" "The focused legacy app could not be verified. Wake/unlock it and open the existing app manually; no receiver was called."
    }
    $remote = "/data/local/tmp/minimum-legacy-ready-$([guid]::NewGuid().ToString('N')).xml"
    try {
        $dump = Invoke-TargetAdb -Arguments @("shell", "uiautomator", "dump", $remote) -AllowFailure
        if ($dump.ExitCode -ne 0) {
            Throw-UpdateError "LEGACY_NONCREATING_PROBE_UNAVAILABLE" "A fresh legacy Ready UI snapshot could not be obtained; no receiver was called."
        }
        $ui = Invoke-TargetAdb -Arguments @("shell", "cat", $remote) -AllowFailure
        if ($ui.ExitCode -ne 0) {
            Throw-UpdateError "LEGACY_NONCREATING_PROBE_UNAVAILABLE" "The fresh legacy Ready UI snapshot could not be read; no receiver was called."
        }
        return Parse-LegacyReadyUiEvidence -WindowDump $window.Output -UiXml $ui.Output
    } finally {
        Invoke-TargetAdb -Arguments @("shell", "rm", "-f", $remote) -AllowFailure | Out-Null
    }
}

function Get-LegacyNonCreatingEvidence {
    try {
        $identity = Get-LegacyExistingIdentityViaRunAs
        return [pscustomobject]@{ Mode = "LEGACY_RUN_AS_ID"; Identity = $identity; SelectedChannel = "" }
    } catch {
        if ((Get-ErrorCategory -Message $_.Exception.Message) -cne "LEGACY_NONCREATING_PROBE_UNAVAILABLE") { throw }
    }
    return Get-LegacyReadyUiEvidence
}

function Wait-BootCompleted {
    param([int]$TimeoutSeconds)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ((Get-TargetProperty -Name "sys.boot_completed") -eq "1") { return }
        Start-Sleep -Seconds 2
    }
    Throw-UpdateError "BOOT_TIMEOUT" "Android did not finish booting within the bounded timeout."
}

function Write-SanitizedReports {
    param([Parameter(Mandatory)]$Result, [string]$Directory)
    if (-not $Directory) {
        $base = if ($env:LOCALAPPDATA) { $env:LOCALAPPDATA } else { [IO.Path]::GetTempPath() }
        $Directory = Join-Path $base "Minimum\UpdateReports"
    }
    New-Item -ItemType Directory -Path $Directory -Force | Out-Null
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $baseName = "minimum-update-$($Result.SessionId)-$stamp"
    $jsonPath = Join-Path $Directory "$baseName.json"
    $textPath = Join-Path $Directory "$baseName.txt"
    $Result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $jsonPath -Encoding UTF8
    $migrationSummary = if (@($Result.Migrations).Count -eq 0) {
        "none"
    } else {
        (@($Result.Migrations) | ForEach-Object {
            $suffix = if ($_.Detail) { "/$($_.Detail)" } else { "" }
            "$($_.Id)=$($_.Outcome)$suffix"
        }) -join "; "
    }
    @(
        "Minimum update report",
        "Session: $($Result.SessionId)",
        "Profile: $($Result.Profile)",
        "Device ID: $($Result.DeviceId)",
        "Previous version: $($Result.PreviousVersion)",
        "Target version: $($Result.TargetVersion)",
        "Artifact verification: $($Result.ArtifactVerification)",
        "Migrations: $migrationSummary",
        "Preservation evidence: $($Result.PreservationEvidence)",
        "Legacy proof mode: $($Result.LegacyProofMode)",
        "Legacy selected channel baseline: $($Result.LegacySelectedChannelBefore)",
        "Pre-update Ready: $($Result.PreReady)",
        "Post-update Ready: $($Result.PostReady)",
        "Reboot acceptance: $($Result.RebootAcceptance)",
        "Rollback assessment: $($Result.RollbackAssessment)",
        "Result: $($Result.Result)",
        "Error category: $($Result.ErrorCategory)",
        "Detail: $($Result.Detail)",
        "",
        "When requesting help, attach this .txt and matching .json report. They intentionally exclude hardware serials and secrets."
    ) | Set-Content -LiteralPath $textPath -Encoding UTF8
    Write-Host "Sanitized report: $textPath"
}

function Write-SessionSummaryReport {
    param([string]$Summary, [string]$SessionId, [string]$Directory)
    if (-not $Directory) {
        $base = if ($env:LOCALAPPDATA) { $env:LOCALAPPDATA } else { [IO.Path]::GetTempPath() }
        $Directory = Join-Path $base "Minimum\UpdateReports"
    }
    New-Item -ItemType Directory -Path $Directory -Force | Out-Null
    $path = Join-Path $Directory ("minimum-update-session-{0}.txt" -f $SessionId)
    $Summary | Set-Content -LiteralPath $path -Encoding UTF8
    Write-Host "Sanitized session summary: $path"
}

function Invoke-OneUpdate {
    param($Bundle, [string]$SessionId, [hashtable]$CompletedDeviceIds = @{})
    $result = [ordered]@{
        SessionId = $SessionId; Profile = ""; DeviceId = ""; PreviousVersion = "unknown"
        TargetVersion = [string]$Bundle.Manifest.versionName; ArtifactVerification = "VERIFIED"
        Migrations = @(); PreReady = $false; PostReady = $false; RebootAcceptance = "NOT_REQUIRED"
        RollbackAssessment = "NOT_AUTOMATED; old APK is not included and no data migration is declared"
        PreservationEvidence = "NOT_CAPTURED"; LegacyProofMode = "NOT_APPLICABLE"
        LegacySelectedChannelBefore = ""
        ConfigVersionBefore = -1; ConfigVersionAfter = -1; Result = "FAIL"
        ErrorCategory = ""; Detail = ""
    }
    $mutationStarted = $false
    $before = $null
    $deviceId = ""
    try {
        $records = @(Get-AdbRecords)
        $target = Select-TargetRecord -Records $records -RequestedSerial $Serial -RequestedTransportId $TransportId
        # Hardware inventory uses target-scoped ADB commands, so pin the selected transport before
        # reading manufacturer/model. This must happen before Add-HardwareIdentity calls getprop.
        $script:CurrentTarget = $target
        $script:CurrentTarget = Add-HardwareIdentity -Target $target
        if (-not $script:CurrentTarget.Profile) {
            Throw-UpdateError "UNSUPPORTED_HARDWARE" "Unknown hardware was inventory-checked and rejected before mutation."
        }
        $result.Profile = $script:CurrentTarget.Profile
        Write-Host "Target: $($script:CurrentTarget.Manufacturer)/$($script:CurrentTarget.Model) ($($result.Profile))"
        $battery = Get-BatteryState
        if ($battery.Level -lt 20 -and -not $battery.Powered) {
            Throw-UpdateError "POWER_TOO_LOW" "Battery is below 20 percent and external power was not detected."
        }
        $installed = Get-InstalledPackageState
        $result.PreviousVersion = $installed.VersionName
        $comparison = Compare-VersionCode -Installed $installed.VersionCode -Target ([long]$Bundle.Manifest.versionCode)
        if ($comparison -gt 0 -and -not $AllowDowngrade) {
            Throw-UpdateError "DOWNGRADE_REFUSED" "Installed Minimum is newer than this bundle. Use a newer reviewed bundle; downgrade is refused by default."
        }
        $requiredMigrations = @(Get-RequiredMigrations -ManifestMigrations @($Bundle.Manifest.migrations) `
            -InstalledVersionCode $installed.VersionCode -TargetVersionCode ([long]$Bundle.Manifest.versionCode) `
            -Profile $script:CurrentTarget.Profile)
        # Signer compatibility and the non-creating legacy probe occur before any receiver action.
        # This lets unsupported/non-debuggable legacy channels fail with zero app-state mutation.
        $installedSigner = @(Get-InstalledSignerDigests -RemoteApkPath $installed.BaseApkPath)
        Assert-SignerCompatibility -InstalledDigests $installedSigner -TargetDigest ([string]$Bundle.Manifest.signerSha256).ToUpperInvariant()
        $legacyEvidence = $null
        if ($installed.VersionCode -le 3070300) {
            $legacyEvidence = Get-LegacyNonCreatingEvidence
            $result.LegacyProofMode = $legacyEvidence.Mode
            $result.LegacySelectedChannelBefore = $legacyEvidence.SelectedChannel
        }
        # Status is deliberately queried before either identity action. A legacy identity action
        # may call getOrCreate, so it is permitted only after the app-private run-as probe has
        # already proved a persisted identity without invoking application code.
        $before = Get-ProvisioningStatus
        if (-not $before) {
            Throw-UpdateError "CONFIG_UNVERIFIED" "Minimum did not return a recognized provisioning status."
        }
        if ($before.SnapshotLevel -ceq "LEGACY") {
            Assert-LegacyBridgeEligible -Status $before -InstalledVersionCode $installed.VersionCode `
                -TargetVersionCode ([long]$Bundle.Manifest.versionCode)
            $deviceId = Get-Identity -Legacy
            $privateIdMismatch = $legacyEvidence.Mode -ceq "LEGACY_RUN_AS_ID" -and
                $deviceId -cne $legacyEvidence.Identity
            if (-not $legacyEvidence -or $privateIdMismatch -or $deviceId -cne $before.ActiveDeviceId -or
                    $before.DeviceId -cne $deviceId) {
                Throw-UpdateError "LEGACY_IDENTITY_MISMATCH" "Legacy receiver identity/configuration did not match the non-creating app-private identity proof."
            }
            $result.PreservationEvidence = "LEGACY_LIMITED_BASELINE"
        } else {
            $deviceId = Get-Identity
            if ($before.DeviceId -cne $deviceId -or $before.ActiveDeviceId -cne $deviceId -or
                    $before.Pending -or $before.ConfigVersion -le 0 -or $before.LastSuccessMs -le 0) {
                Throw-UpdateError "CONFIG_UNVERIFIED" "Existing identity, active configuration or Last Known Good state could not be verified."
            }
            $result.PreservationEvidence = "EXTENDED_BASELINE"
        }
        $script:CurrentTarget | Add-Member CorrelatedDeviceId $deviceId -Force
        $result.DeviceId = $deviceId
        if ($CompletedDeviceIds.ContainsKey($deviceId)) {
            if ($NonInteractive) { Throw-UpdateError "SESSION_DUPLICATE" "This Device ID was already completed in the current session." }
            $answer = (Read-Host "Device ID $deviceId was already completed in this session. Type RECHECK to verify it again").Trim()
            if ($answer -cne "RECHECK") { Throw-UpdateError "SESSION_DUPLICATE" "Operator declined to recheck an already-completed Device ID." }
        }
        $result.ConfigVersionBefore = $before.ConfigVersion
        $result.PreReady = [bool](Get-ReadyState)
        if (-not $ReportOnly -and -not $WhatIfPreference -and -not $ConfirmNotTransmitting) {
            if ($NonInteractive) {
                Throw-UpdateError "TX_CONFIRMATION_REQUIRED" "Non-interactive mutation requires -ConfirmNotTransmitting."
            }
            $answer = (Read-Host "Confirm this radio is not transmitting, then type UPDATE").Trim()
            if ($answer -cne "UPDATE") { Throw-UpdateError "OPERATOR_CANCELLED" "Operator did not confirm the non-transmitting update boundary." }
        }
        if ($ReportOnly -or $WhatIfPreference) {
            $result.Result = if ($before.SnapshotLevel -ceq "LEGACY") { "WARN" } else { "PASS" }
            $result.Detail = if ($before.SnapshotLevel -ceq "LEGACY") {
                "REPORT_ONLY; compatible, no mutation performed; legacy preservation baseline is limited"
            } else { "REPORT_ONLY; compatible, no mutation performed" }
            $result.PostReady = $result.PreReady
            return [pscustomobject]$result
        }
        foreach ($migration in $requiredMigrations) {
            $mutationStarted = $true
            $result.Migrations += Invoke-RequiredMigration -Migration $migration
        }
        if ($comparison -eq 0) {
            $result.RollbackAssessment = "NOT_NEEDED"
            $result.Migrations += New-MigrationResult -Id "APK_VERSION" -Outcome "ALREADY_OK"
        } else {
            if ($comparison -gt 0) {
                Write-Warning "EXPLICIT DOWNGRADE: Android will receive install -r -d. Signer and identity checks remain enforced; rollback is not automated."
            }
            $result.Migrations += Ensure-RyksInstallPolicy
            Write-Host "Installing verified Minimum $($Bundle.Manifest.versionName) in place..."
            $mutationStarted = $true
            Install-InPlace -ApkPath $Bundle.ApkPath -Downgrade:($comparison -gt 0)
            $result.Migrations += New-MigrationResult -Id "APK_VERSION" -Outcome "APPLIED"
        }
        $postPackage = Get-InstalledPackageState
        if ($postPackage.VersionCode -ne [long]$Bundle.Manifest.versionCode -or
                $postPackage.VersionName -cne [string]$Bundle.Manifest.versionName) {
            Throw-UpdateError "POST_VERSION_MISMATCH" "Installed package identity/version does not match the exact release manifest."
        }
        Invoke-TargetAdb -Arguments @("shell", "am", "start", "-n", $MinimumActivity) | Out-Null
        $after = Wait-MinimumReady -ExpectedDeviceId $deviceId -TimeoutSeconds $ReadyTimeoutSeconds
        $result.PostReady = $true
        $result.ConfigVersionAfter = $after.ConfigVersion
        $result.PreservationEvidence = Assert-PreservedState -Before $before -After $after -Phase "after the in-place update"
        $needsReboot = [bool]$Bundle.Manifest.rebootRequired -or $FullRebootAcceptance -or
            @($requiredMigrations | Where-Object { [bool]$_.rebootRequired }).Count -gt 0
        if ($needsReboot) {
            $original = $script:CurrentTarget
            Invoke-TargetAdb -Arguments @("reboot") | Out-Null
            $script:CurrentTarget = Wait-ReturningTarget -OriginalTarget $original -ExpectedDeviceId $deviceId -TimeoutSeconds $BootTimeoutSeconds
            Wait-BootCompleted -TimeoutSeconds $BootTimeoutSeconds
            $afterReboot = Wait-MinimumReady -ExpectedDeviceId $deviceId -TimeoutSeconds $ReadyTimeoutSeconds
            $rebootEvidence = Assert-PreservedState -Before $before -After $afterReboot -Phase "after reboot"
            if ($result.PreservationEvidence -cne "BOOTSTRAPPED_POST_UPDATE") {
                $result.PreservationEvidence = $rebootEvidence
            }
            foreach ($migration in @($requiredMigrations | Where-Object { [bool]$_.rebootRequired })) {
                $result.Migrations += Invoke-RequiredMigration -Migration $migration -VerifyOnly
            }
            $result.RebootAcceptance = "READY_SAME_ID"
        }
        $hasMigrationWarning = @($result.Migrations | Where-Object { $_.Detail -ceq "READINESS_WARN" }).Count -gt 0
        $result.Result = if ($hasMigrationWarning) { "WARN" } else { "PASS" }
        $result.Detail = if ($comparison -eq 0) {
            "ALREADY_OK; same-ID Ready verified"
        } elseif ($hasMigrationWarning) {
            "UPDATED; same-ID Ready verified; cellular policy persisted with documented readiness warning"
        } else {
            "UPDATED; same-ID Ready verified"
        }
        return [pscustomobject]$result
    } catch {
        $message = ConvertTo-SafeMessage -Text $_.Exception.Message
        $result.ErrorCategory = Get-ErrorCategory -Message $message
        $result.Detail = [regex]::Replace($message, '^\[[A-Z0-9_]+\]\s*', '')
        if ($mutationStarted -and $deviceId -and $before -and $script:CurrentTarget -and
                $script:CurrentTarget.PSObject.Properties.Name -contains "CorrelatedDeviceId" -and
                $script:CurrentTarget.CorrelatedDeviceId -ceq $deviceId) {
            try {
                $recoveryPackage = Get-InstalledPackageState
                Invoke-TargetAdb -Arguments @("shell", "am", "start", "-n", $MinimumActivity) | Out-Null
                $recovered = Wait-MinimumReady -ExpectedDeviceId $deviceId -TimeoutSeconds ([Math]::Min($ReadyTimeoutSeconds, 90))
                $result.PreservationEvidence = Assert-PreservedState -Before $before -After $recovered -Phase "during failure recovery"
                $result.PostReady = $true
                $result.ConfigVersionAfter = $recovered.ConfigVersion
                $result.Detail += "; RECOVERY_VERIFIED: installed $($recoveryPackage.VersionName) returned to same-ID Ready with preserved state"
            } catch {
                $recoveryMessage = ConvertTo-SafeMessage -Text $_.Exception.Message
                $result.Detail += "; RECOVERY_UNVERIFIED: $([regex]::Replace($recoveryMessage, '^\[[A-Z0-9_]+\]\s*', ''))"
            }
        }
        return [pscustomobject]$result
    }
}

if ($LibraryOnly) { return }

$Host.UI.RawUI.WindowTitle = "Minimum One-Shot Updater"
$sessionId = ([guid]::NewGuid().ToString("N").Substring(0, 12)).ToUpperInvariant()
try {
    if (-not $BundleRoot) { $BundleRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path }
    $bundle = Read-ReleaseBundle -Root $BundleRoot
    $apkIdentity = Get-ApkManifestIdentity -ApkPath $bundle.ApkPath
    if ($apkIdentity.ApplicationId -cne [string]$bundle.Manifest.applicationId -or
            $apkIdentity.VersionCode -ne [long]$bundle.Manifest.versionCode -or
            $apkIdentity.VersionName -cne [string]$bundle.Manifest.versionName) {
        Throw-UpdateError "APK_IDENTITY_BINDING" "The APK package/version does not match the exact release manifest."
    }
    $targetSigners = @(Get-ApkSignerDigests -ApkPath $bundle.ApkPath)
    $reviewedSigner = ([string]$bundle.Manifest.signerSha256).ToUpperInvariant()
    if ($targetSigners.Count -ne 1 -or $targetSigners[0] -cne $reviewedSigner) {
        Throw-UpdateError "APK_SIGNER_BINDING" "The APK must contain exactly the one reviewed signer from the release manifest; missing or extra signers are refused."
    }
    try { $script:AdbExecutable = (Get-Command adb -ErrorAction Stop).Source } catch {
        Throw-UpdateError "ADB_MISSING" "ADB was not found. Install Android Platform Tools or add adb.exe to PATH."
    }
    $AdbPort = Select-AdbPort
    $script:ServerArguments = @("-P", "$AdbPort")
} catch {
    $safe = ConvertTo-SafeMessage -Text $_.Exception.Message
    $preflight = [pscustomobject][ordered]@{
        SessionId = $sessionId; Profile = ""; DeviceId = ""; PreviousVersion = "unknown"
        TargetVersion = "unknown"; ArtifactVerification = "FAILED"; Migrations = @()
        PreReady = $false; PostReady = $false; RebootAcceptance = "NOT_RUN"
        RollbackAssessment = "NO_MUTATION"; PreservationEvidence = "NOT_CAPTURED"
        LegacyProofMode = "NOT_CAPTURED"; LegacySelectedChannelBefore = ""
        ConfigVersionBefore = -1; ConfigVersionAfter = -1
        Result = "FAIL"; ErrorCategory = Get-ErrorCategory -Message $safe
        Detail = [regex]::Replace($safe, '^\[[A-Z0-9_]+\]\s*', '')
    }
    Write-SanitizedReports -Result $preflight -Directory $ReportDirectory
    Write-Error "FAIL: $($preflight.ErrorCategory) - $($preflight.Detail)" -ErrorAction Continue
    exit 1
}
$results = New-Object System.Collections.Generic.List[object]
$completedIds = @{}
do {
    $one = Invoke-OneUpdate -Bundle $bundle -SessionId $sessionId -CompletedDeviceIds $completedIds
    if ($one.DeviceId -and $completedIds.ContainsKey($one.DeviceId) -and -not $NonInteractive) {
        Write-Warning "Device ID $($one.DeviceId) was already processed in this session. This run was retained as a recheck."
    }
    if ($one.DeviceId) { $completedIds[$one.DeviceId] = $true }
    $results.Add($one)
    Write-SanitizedReports -Result $one -Directory $ReportDirectory
    Write-Host ("{0}: {1} / {2} - {3}" -f $one.Result, $one.Profile, $one.DeviceId, $one.Detail)
    if (-not $UpdateSession) { break }
    if ($NonInteractive) { break }
    Write-Host "Disconnect the completed radio. The updater will not accept another until no authorized device remains."
    while (@(Get-AdbRecords | Where-Object { $_.State -eq "device" }).Count -gt 0) { Start-Sleep -Seconds 2 }
    $choice = (Read-Host "Connect the next radio and press Enter, or type Q to finish").Trim()
    if ($choice -ieq "Q") { break }
} while ($true)

# Windows PowerShell 5.1 can throw "Argument types do not match" when array-subexpressing a
# generic List[object]. ToArray preserves the completed sequential results without binder coercion.
$summary = Format-SessionSummary -Results $results.ToArray() -TargetVersion ([string]$bundle.Manifest.versionName)
Write-Host ""
Write-Host $summary
Write-SessionSummaryReport -Summary $summary -SessionId $sessionId -Directory $ReportDirectory
if (@($results | Where-Object { $_.Result -eq "FAIL" }).Count -gt 0) { exit 1 }
if (@($results | Where-Object { $_.Result -eq "WARN" }).Count -gt 0) { exit 2 }
exit 0
