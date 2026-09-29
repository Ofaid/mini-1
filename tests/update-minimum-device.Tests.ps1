$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "..\scripts\update-minimum-device.ps1") -LibraryOnly

$script:Passed = 0
$script:Failed = 0

function Assert-Equal {
    param($Expected, $Actual, [string]$Name)
    if (($Expected -is [array]) -or ($Actual -is [array])) {
        if ((@($Expected) -join "|") -cne (@($Actual) -join "|")) { throw "$Name expected '$(@($Expected) -join '|')' but got '$(@($Actual) -join '|')'." }
    } elseif ($Expected -cne $Actual) { throw "$Name expected '$Expected' but got '$Actual'." }
}

function Assert-True { param([bool]$Value, [string]$Name); if (-not $Value) { throw "$Name expected true." } }

function Assert-ThrowsCode {
    param([scriptblock]$Action, [string]$Code, [string]$Name)
    try { & $Action; throw "$Name did not throw." } catch {
        if ($_.Exception.Message -notmatch "^\[$([regex]::Escape($Code))\]") { throw "$Name threw unexpected error: $($_.Exception.Message)" }
    }
}

function Test-Case {
    param([string]$Name, [scriptblock]$Action)
    try { & $Action; $script:Passed++; Write-Host "PASS $Name" } catch { $script:Failed++; Write-Host "FAIL $Name - $($_.Exception.Message)" }
}

Test-Case "supported model source of truth" {
    Assert-Equal "T56" (Get-DeviceProfile "UNIPRO" "ZX") "T56"
    Assert-Equal "T99" (Get-DeviceProfile "Youdotech" "QM011") "T99"
    Assert-Equal "RYKS" (Get-DeviceProfile "ELINK" "ym_258") "RYKS"
    Assert-Equal "" (Get-DeviceProfile "Other" "ZX") "unknown"
}

Test-Case "version comparison and downgrade gate primitive" {
    Assert-Equal -1 (Compare-VersionCode 10 11) "upgrade"
    Assert-Equal 0 (Compare-VersionCode 11 11) "same"
    Assert-Equal 1 (Compare-VersionCode 12 11) "downgrade"
}

Test-Case "device transcript selection" {
    $one = Convert-AdbDeviceLines @("List of devices attached", "abc device product:x transport_id:7")
    Assert-Equal 1 @($one).Count "one count"
    Assert-Equal 7 $one[0].TransportId "transport"
    Assert-Equal "abc" (Select-TargetRecord $one).Serial "selected"
    $multiple = Convert-AdbDeviceLines @("a device transport_id:1", "b device transport_id:2")
    Assert-ThrowsCode { Select-TargetRecord $multiple } "TARGET_COUNT" "multiple"
    Assert-Equal "b" (Select-TargetRecord $multiple -RequestedTransportId 2).Serial "explicit transport"
    $bad = Convert-AdbDeviceLines @("a unauthorized transport_id:1")
    Assert-ThrowsCode { Select-TargetRecord $bad } "TARGET_NOT_AUTHORIZED" "unauthorized"
}

Test-Case "duplicate serial safely refused" {
    $records = Convert-AdbDeviceLines @("same device transport_id:1", "same device transport_id:2")
    Assert-ThrowsCode { Select-TargetRecord $records -RequestedSerial "same" } "SERIAL_AMBIGUOUS" "duplicate serial"
}

Test-Case "reboot transport change chooses unique same profile" {
    $records = @(
        [pscustomobject]@{ Serial="new"; State="device"; TransportId=9; Manufacturer="UNIPRO"; Model="ZX" },
        [pscustomobject]@{ Serial="other"; State="device"; TransportId=10; Manufacturer="Other"; Model="Other" }
    )
    Assert-Equal $null (Find-ReturningCandidate $records "UNIPRO" "ZX" "old") "model-only refused"
    $records[0] | Add-Member DeviceId "A1B2C3"
    Assert-Equal "new" (Find-ReturningCandidate $records "UNIPRO" "ZX" "old" "A1B2C3").Serial "identity-correlated returning"
    $ambiguous = @($records[0], [pscustomobject]@{ Serial="new2"; State="device"; TransportId=11; Manufacturer="UNIPRO"; Model="ZX" })
    Assert-Equal $null (Find-ReturningCandidate $ambiguous "UNIPRO" "ZX" "old") "ambiguous return"
}

Test-Case "package and preservation snapshot transcripts" {
    $package = Parse-PackageState "Packages:`n  versionCode=3070300 minSdk=21 targetSdk=36`n  versionName=3.7.3-minimum.1-debug"
    Assert-Equal ([long]3070300) $package.VersionCode "package code"
    Assert-Equal "3.7.3-minimum.1-debug" $package.VersionName "package name"
    $status = Parse-ProvisioningStatus ('Broadcast completed: result=0, data="deviceId=A1B2C3;activeDeviceId=A1B2C3;' +
        'configVersion=14;pending=false;lastSuccessMs=123;selectedChannel=ops;' +
        'activeConfigSha256=' + ('A' * 64) + ';safeSettingsSha256=' + ('B' * 64) + '"')
    Assert-Equal "A1B2C3" $status.DeviceId "device id"
    Assert-Equal 14 $status.ConfigVersion "config"
    Assert-True (-not $status.Pending) "pending false"
    Assert-Equal "ops" $status.SelectedChannel "selected channel"
    Assert-Equal ("A" * 64) $status.ActiveConfigSha256 "LKG digest"
    Assert-Equal ("B" * 64) $status.SafeSettingsSha256 "safe settings digest"
    $same = $status.PSObject.Copy()
    Assert-PreservedState $status $same "after update"
    $changed = $status.PSObject.Copy()
    $changed.SelectedChannel = "other"
    Assert-ThrowsCode { Assert-PreservedState $status $changed "after update" } "STATE_PRESERVATION_FAILED" "channel mutation"
}

Test-Case "debug to release signer mismatch is refused before install" {
    $debugSigner = "168F42ED412DA80ADAF27BED0984DBEE191168E9DF04F08AFA240A3F9DE45972"
    $releaseSigner = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    Assert-ThrowsCode { Assert-SignerCompatibility @($debugSigner) $releaseSigner } "SIGNER_MISMATCH" "debug release mismatch"
}

Test-Case "matching signer accepted" {
    $signer = "168F42ED412DA80ADAF27BED0984DBEE191168E9DF04F08AFA240A3F9DE45972"
    Assert-SignerCompatibility @($signer) $signer
    Assert-ThrowsCode { Assert-SignerCompatibility @($signer, $signer) $signer } "SIGNER_MISMATCH" "duplicate signer refused"
}

Test-Case "returning target switches to its correlated ADB port" {
    $old = $script:ServerArguments
    try {
        $target = [pscustomobject]@{ Serial="same"; State="device"; TransportId=7; AdbPort=5041 }
        Set-TargetServerArguments $target
        Assert-Equal @("-P", "5041") $script:ServerArguments "returning ADB port"
    } finally { $script:ServerArguments = $old }
}

Test-Case "apksigner output parser requires verified signer digest" {
    $digest = "168F42ED412DA80ADAF27BED0984DBEE191168E9DF04F08AFA240A3F9DE45972"
    Assert-ThrowsCode { Parse-ApkSignerOutput "Number of signers: 1`nSigner #1 certificate SHA-256 digest: $digest" } "APK_SIGNATURE_INVALID" "text digest without certificate refused"
    Assert-ThrowsCode { Parse-ApkSignerOutput "DOES NOT VERIFY" } "APK_SIGNATURE_INVALID" "missing signer digest"
}

Test-Case "apksigner process rejects command-stream injection characters" {
    $oldResolver = (Get-Item Function:\Resolve-ApkSigner).ScriptBlock
    try {
        Set-Item Function:\Resolve-ApkSigner { "C:\safe\apksigner.bat" }
        Assert-ThrowsCode { Get-ApkSignerDigests 'C:\release\bad%PATH%.apk' } "APK_SIGNATURE_INVALID" "cmd expansion refused"
        Set-Item Function:\Resolve-ApkSigner { "C:\bad`"tool\apksigner.bat" }
        Assert-ThrowsCode { Get-ApkSignerDigests 'C:\release\minimum.apk' } "APK_SIGNATURE_INVALID" "quote refused"
        Set-Item Function:\Resolve-ApkSigner { "C:\safe\apksigner.bat" }
        Assert-ThrowsCode { Get-ApkSignerDigests "C:\release\minimum.apk`nextra" } "APK_SIGNATURE_INVALID" "newline refused"
    } finally {
        Set-Item Function:\Resolve-ApkSigner $oldResolver
    }
}

Test-Case "apksigner timeout has bounded cleanup" {
    $root = Join-Path ([IO.Path]::GetTempPath()) ("minimum-apksigner-timeout-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $root | Out-Null
    try {
        $fakeApk = Join-Path $root "fixture.apk"
        Set-Content -LiteralPath $fakeApk -Value "fixture" -NoNewline
        if ($env:ComSpec) {
            $fakeSigner = Join-Path $root "apksigner.bat"
            Set-Content -LiteralPath $fakeSigner -Value "@ping -n 6 127.0.0.1 >nul" -Encoding ASCII
        } else {
            $fakeSigner = Join-Path $root "apksigner"
            Set-Content -LiteralPath $fakeSigner -Value "#!/bin/sh`nsleep 5" -Encoding ASCII
            & chmod +x $fakeSigner
        }
        $timer = [Diagnostics.Stopwatch]::StartNew()
        Assert-ThrowsCode { Invoke-ApkSignerProcess $fakeSigner $fakeApk 100 100 } "APK_SIGNATURE_INVALID" "timeout refused"
        $timer.Stop()
        Assert-True ($timer.ElapsedMilliseconds -lt 3000) "timeout cleanup remained bounded"
    } finally {
        if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
    }
}

Test-Case "migration dispatch is exact, versioned, and T56-only" {
    $migration = [pscustomobject]@{ id="CELLULAR_POLICY_V1_T56"; fromVersionCodeMax=3070300; toVersionCode=3070301; profiles=@("T56"); rebootRequired=$true; irreversible=$false }
    Assert-Equal 1 @(Get-RequiredMigrations @($migration) 3070300 3070301 "T56").Count "T56 migration"
    Assert-Equal 0 @(Get-RequiredMigrations @($migration) 3070300 3070301 "T99").Count "T99 skip"
    Assert-Equal 0 @(Get-RequiredMigrations @($migration) 3070301 3070301 "T56").Count "already migrated"
    $unknown = [pscustomobject]@{ id="UNKNOWN_POLICY"; fromVersionCodeMax=3070300; toVersionCode=3070301; profiles=@("T56"); rebootRequired=$true; irreversible=$false }
    Assert-ThrowsCode { Get-RequiredMigrations @($unknown) 3070300 3070301 "T56" } "MIGRATION_NOT_IMPLEMENTED" "unapproved migration"
    Assert-ThrowsCode { Get-RequiredMigrations @($migration) 3070300 3070302 "T56" } "MIGRATION_CONTRACT" "wrong target"
}

Test-Case "unsafe relative bundle paths are refused" {
    Assert-ThrowsCode { Assert-SafeRelativePath "../minimum-foss.apk" } "BUNDLE_PATH_UNSAFE" "parent traversal"
    Assert-ThrowsCode { Assert-SafeRelativePath "scripts\update-minimum-device.ps1" } "BUNDLE_PATH_UNSAFE" "backslash"
    Assert-ThrowsCode { Assert-SafeRelativePath "/minimum-foss.apk" } "BUNDLE_PATH_UNSAFE" "rooted path"
}

Test-Case "idempotent outcome and summary" {
    Assert-Equal "ALREADY_OK" (New-MigrationResult "APK_VERSION" "ALREADY_OK").Outcome "already ok"
    $results = @(
        [pscustomobject]@{ Profile="T56"; DeviceId="A1B2C3"; Result="PASS"; Detail="ALREADY_OK" },
        [pscustomobject]@{ Profile="T99"; DeviceId="D4E5F6"; Result="FAIL"; Detail="signer mismatch" }
    )
    $summary = Format-SessionSummary $results "3.7.4"
    Assert-True ($summary -match 'Totals: 1 PASS, 0 WARN, 1 FAIL') "summary totals"
    Assert-True ($summary -notmatch 'serial') "summary privacy"
}

Test-Case "secret and identifier redaction" {
    $safe = ConvertTo-SafeMessage "serial=usb123 token=ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ123456 password=hunter2 Bearer abc.def.ghi"
    Assert-True ($safe -notmatch 'usb123|ABCDEFGHIJKLMNOPQRSTUVWXYZ|hunter2|abc\.def') "redacted values"
    Assert-True ($safe -match 'serial=<redacted>') "redaction marker"
}

Test-Case "bundle allowlist and checksum reject tampering" {
    $root = Join-Path ([IO.Path]::GetTempPath()) ("minimum-updater-test-{0}" -f [guid]::NewGuid().ToString("N"))
    try {
        New-Item -ItemType Directory -Path $root | Out-Null
        New-Item -ItemType Directory -Path (Join-Path $root "scripts") | Out-Null
        New-Item -ItemType Directory -Path (Join-Path $root "assets") | Out-Null
        $approved = @(
            "Provision Minimum Device.cmd", "README.txt", "UPDATER-README.md", "Update Minimum Device.cmd",
            "VERSION.txt", "CELLULAR-README.md", "assets/t99-wifi-provisioner.apk", "minimum-foss.apk", "minimum-foss.apk.sha256",
            "scripts/manage-cellular.ps1",
            "scripts/prepare-ryks.ps1", "scripts/prepare-t56.ps1", "scripts/prepare-t99.ps1",
            "scripts/provision-minimum-device.ps1", "scripts/update-minimum-device.ps1"
        )
        foreach ($relative in $approved) {
            Set-Content -LiteralPath (Join-Path $root $relative.Replace('/', '\')) -Value "fixture-$relative" -NoNewline -Encoding ASCII
        }
        Set-Content -LiteralPath (Join-Path $root "VERSION.txt") -Value "3.7.3-minimum.2" -NoNewline -Encoding ASCII
        Set-Content -LiteralPath (Join-Path $root "minimum-foss.apk") -Value "fixture" -NoNewline -Encoding ASCII
        $apkHash = Get-FileSha256 (Join-Path $root "minimum-foss.apk")
        Set-Content -LiteralPath (Join-Path $root "minimum-foss.apk.sha256") -Value "$apkHash  minimum-foss.apk" -NoNewline -Encoding ASCII
        $files = $approved | ForEach-Object {
            [ordered]@{ path=$_; sha256=Get-FileSha256 (Join-Path $root $_) }
        }
        $manifest = [ordered]@{
            schemaVersion=1; releaseTag="3.7.3-minimum.2"; applicationId="se.lublin.mumla"; versionCode=3070301
            versionName="3.7.3-minimum.2"; apkFile="minimum-foss.apk"; apkSha256=$apkHash
            signerSha256=("A" * 64); rebootRequired=$false
            migrations=@([ordered]@{ id="CELLULAR_POLICY_V1_T56"; fromVersionCodeMax=3070300; toVersionCode=3070301; profiles=@("T56"); rebootRequired=$true; irreversible=$false })
            files=$files
        }
        $manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $root "RELEASE-MANIFEST.json") -Encoding UTF8
        $bundle = Read-ReleaseBundle $root
        Assert-Equal "3.7.3-minimum.2" $bundle.Manifest.releaseTag "valid bundle"
        Add-Content -LiteralPath (Join-Path $root "minimum-foss.apk") -Value "tamper"
        Assert-ThrowsCode { Read-ReleaseBundle $root } "BUNDLE_CHECKSUM" "tampered file"
        Set-Content -LiteralPath (Join-Path $root "extra.txt") -Value "extra"
        Assert-ThrowsCode { Read-ReleaseBundle $root } "BUNDLE_ALLOWLIST" "extra file"
    } finally {
        if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
    }
}

$realApkPath = if ($env:MINIMUM_TEST_SIGNED_APK) {
    $env:MINIMUM_TEST_SIGNED_APK
} else {
    Join-Path $PSScriptRoot "..\app\build\outputs\apk\foss\debug\mumla-foss-debug.apk"
}
if (Test-Path -LiteralPath $realApkPath -PathType Leaf) {
    Test-Case "real built APK identity and raw apksigner process parsing" {
        $identity = Get-ApkManifestIdentity -ApkPath $realApkPath
        if ($env:MINIMUM_TEST_SIGNED_APK) {
            Assert-True (-not [string]::IsNullOrWhiteSpace($env:MINIMUM_TEST_EXPECTED_APPLICATION_ID)) "release expected package supplied"
            Assert-True (-not [string]::IsNullOrWhiteSpace($env:MINIMUM_TEST_EXPECTED_VERSION_CODE)) "release expected version code supplied"
            Assert-True (-not [string]::IsNullOrWhiteSpace($env:MINIMUM_TEST_EXPECTED_VERSION_NAME)) "release expected version name supplied"
            Assert-Equal $env:MINIMUM_TEST_EXPECTED_APPLICATION_ID $identity.ApplicationId "release APK package"
            Assert-Equal ([long]$env:MINIMUM_TEST_EXPECTED_VERSION_CODE) $identity.VersionCode "release APK version code"
            Assert-Equal $env:MINIMUM_TEST_EXPECTED_VERSION_NAME $identity.VersionName "release APK version name"
        } else {
            Assert-Equal "se.lublin.mumla" $identity.ApplicationId "real APK package"
            Assert-Equal ([long]3070301) $identity.VersionCode "real APK version code"
            Assert-True ($identity.VersionName -match '-debug$') "real APK debug version"
        }
        $probe = Invoke-ApkSignerProcess -ApkSigner (Resolve-ApkSigner) -ApkPath $realApkPath
        Assert-Equal 0 $probe.ExitCode "real apksigner exit"
        $probeText = (($probe.Stdout, $probe.Stderr) -join "`n")
        $signers = @(Parse-ApkSignerOutput $probeText)
        Assert-True ($signers.Count -ge 1) "real APK signer count"
        Assert-True (@($signers | Where-Object { $_ -notmatch '^[0-9A-F]{64}$' }).Count -eq 0) "real APK signer format"
        $pem = [regex]::Match($probeText, '(?s)-----BEGIN CERTIFICATE-----\s*.*?\s*-----END CERTIFICATE-----')
        Assert-True $pem.Success "real signer PEM present"
        Assert-ThrowsCode { Parse-ApkSignerOutput ($probeText + "`n" + $pem.Value) } "APK_SIGNATURE_INVALID" "duplicate PEM refused"
        $invalidPemText = $probeText.Remove($pem.Index, $pem.Length).Insert($pem.Index,
            "-----BEGIN CERTIFICATE-----`nNOT-BASE64`n-----END CERTIFICATE-----")
        Assert-ThrowsCode { Parse-ApkSignerOutput $invalidPemText } "APK_SIGNATURE_INVALID" "invalid PEM refused"
        $wrongCount = [regex]::Replace($probeText, '(?im)^Number of signers:\s*[0-9]+\s*$', 'Number of signers: 99', 1)
        Assert-ThrowsCode { Parse-ApkSignerOutput $wrongCount } "APK_SIGNATURE_INVALID" "signer count mismatch refused"
        Assert-ThrowsCode { Parse-ApkSignerOutput ($probeText + "`nNumber of signers: $($signers.Count)") } "APK_SIGNATURE_INVALID" "duplicate signer count refused"
        $fakeDigest = if ($signers[0] -ceq ('A' * 64)) { 'B' * 64 } else { 'A' * 64 }
        Assert-ThrowsCode { Parse-ApkSignerOutput ($probeText + "`nSigner #99 certificate SHA-256 digest: $fakeDigest") } "APK_SIGNATURE_INVALID" "fake textual digest refused"
    }
}

function New-UpdaterStatus {
    param([string]$Level = "EXTENDED", [string]$DeviceId = "A1B2C3", [int]$ConfigVersion = 14,
        [bool]$Pending = $false, [long]$LastSuccessMs = 123)
    [pscustomobject]@{
        SnapshotLevel = $Level; DeviceId = $DeviceId; ActiveDeviceId = $DeviceId
        ConfigVersion = $ConfigVersion; Pending = $Pending; LastSuccessMs = $LastSuccessMs
        SelectedChannel = if ($Level -ceq "EXTENDED") { "ops" } else { "UNAVAILABLE_LEGACY" }
        ActiveConfigSha256 = if ($Level -ceq "EXTENDED") { "A" * 64 } else { "UNAVAILABLE_LEGACY" }
        SafeSettingsSha256 = if ($Level -ceq "EXTENDED") { "B" * 64 } else { "UNAVAILABLE_LEGACY" }
    }
}

function New-UpdaterBundle {
    [pscustomobject]@{
        ApkPath = "fixture.apk"
        Manifest = [pscustomobject]@{
            versionName = "3.7.3-minimum.2"; versionCode = 3070301; signerSha256 = "A" * 64
            rebootRequired = $false
            migrations = @([pscustomobject]@{
                id = "CELLULAR_POLICY_V1_T56"; fromVersionCodeMax = 3070300
                toVersionCode = 3070301; profiles = @("T56"); rebootRequired = $true; irreversible = $false
            })
        }
    }
}

function Set-UpdaterScenarioMocks {
    param([hashtable]$Scenario)
    $global:UpdaterScenario = $Scenario
    Set-Item Function:\Get-AdbRecords { @([pscustomobject]@{ Serial="usb"; State="device"; TransportId=7 }) }
    Set-Item Function:\Add-HardwareIdentity {
        param($Target)
        if (-not $script:CurrentTarget -or $script:CurrentTarget.TransportId -ne $Target.TransportId) {
            throw "target transport was not pinned before hardware inventory"
        }
        [pscustomobject]@{ Serial=$Target.Serial; State="device"; TransportId=7; Manufacturer=$global:UpdaterScenario.Manufacturer; Model=$global:UpdaterScenario.Model; Profile=$global:UpdaterScenario.Profile }
    }
    Set-Item Function:\Get-BatteryState { [pscustomobject]@{ Level=90; Powered=$true } }
    Set-Item Function:\Get-InstalledPackageState {
        $global:UpdaterScenario.PackageReads++
        $code = if ($global:UpdaterScenario.Installed -and -not $global:UpdaterScenario.PostVersionMismatch) { 3070301 } else { $global:UpdaterScenario.InstalledCode }
        $name = if ($code -eq 3070301) { "3.7.3-minimum.2" } else { "3.7.3-minimum.1" }
        [pscustomobject]@{ VersionCode=[long]$code; VersionName=$name; BaseApkPath="/data/app/base.apk" }
    }
    Set-Item Function:\Get-ProvisioningStatus {
        $global:UpdaterScenario.Transcript.Add("STATUS")
        return $global:UpdaterScenario.Before
    }
    Set-Item Function:\Get-LegacyExistingIdentityViaRunAs {
        $global:UpdaterScenario.Transcript.Add("RUN_AS_IDENTITY")
        if (-not $global:UpdaterScenario.LegacyProbeAvailable) {
            Throw-UpdateError "LEGACY_NONCREATING_PROBE_UNAVAILABLE" "mocked unavailable probe"
        }
        return $global:UpdaterScenario.Before.DeviceId
    }
    Set-Item Function:\Get-LegacyReadyUiEvidence {
        $global:UpdaterScenario.Transcript.Add("READY_UI_PROBE")
        return Parse-LegacyReadyUiEvidence -WindowDump $global:UpdaterScenario.WindowDump -UiXml $global:UpdaterScenario.UiXml
    }
    Set-Item Function:\Get-Identity {
        param([switch]$Legacy)
        $global:UpdaterScenario.IdentityCalls++
        $global:UpdaterScenario.Transcript.Add($(if ($Legacy) { "IDENTITY_LEGACY" } else { "IDENTITY_EXISTING" }))
        return $global:UpdaterScenario.Before.DeviceId
    }
    Set-Item Function:\Get-ReadyState { $true }
    Set-Item Function:\Get-InstalledSignerDigests { param([string]$RemoteApkPath); @($global:UpdaterScenario.InstalledSigners) }
    Set-Item Function:\Invoke-RequiredMigration {
        param($Migration, [switch]$VerifyOnly)
        $global:UpdaterScenario.MigrationCalls++
        New-MigrationResult -Id $Migration.id -Outcome $(if ($VerifyOnly) { "ALREADY_OK" } else { "APPLIED" })
    }
    Set-Item Function:\Ensure-RyksInstallPolicy {
        if ($global:UpdaterScenario.Profile -ceq "RYKS") { $global:UpdaterScenario.RyksCalls++; New-MigrationResult -Id "RYKS_INSTALL_POLICY" -Outcome "APPLIED" }
    }
    Set-Item Function:\Install-InPlace {
        param([string]$ApkPath, [switch]$Downgrade)
        $global:UpdaterScenario.InstallCalls++; $global:UpdaterScenario.Installed = $true
    }
    Set-Item Function:\Invoke-TargetAdb {
        param([string[]]$Arguments, [switch]$AllowFailure)
        if ((@($Arguments) -join " ") -match 'am start') { $global:UpdaterScenario.StartCalls++ }
        [pscustomobject]@{ ExitCode=0; Output="Success" }
    }
    Set-Item Function:\Wait-MinimumReady { param([string]$ExpectedDeviceId, [int]$TimeoutSeconds); return $global:UpdaterScenario.After }
    Set-Item Function:\Wait-ReturningTarget {
        param($OriginalTarget, [string]$ExpectedDeviceId, [int]$TimeoutSeconds)
        if ($global:UpdaterScenario.RebootFailure) { $script:CurrentTarget = $null; Throw-UpdateError "REBOOT_TARGET_AMBIGUOUS" "mocked timeout" }
        $OriginalTarget | Add-Member CorrelatedDeviceId $ExpectedDeviceId -Force
        return $OriginalTarget
    }
    Set-Item Function:\Wait-BootCompleted { param([int]$TimeoutSeconds) }
}

function New-UpdaterScenario {
    param([string]$Profile = "T99", [string]$Level = "EXTENDED")
    $hardware = switch ($Profile) {
        "T56" { @("UNIPRO", "ZX") }
        "T99" { @("Youdotech", "QM011") }
        "RYKS" { @("ELINK", "ym_258") }
    }
    @{
        Profile=$Profile; Manufacturer=$hardware[0]; Model=$hardware[1]; InstalledCode=3070300
        Before=(New-UpdaterStatus -Level $Level); After=(New-UpdaterStatus -Level "EXTENDED")
        InstalledSigners=@("A" * 64); Installed=$false; PostVersionMismatch=$false; RebootFailure=$false; LegacyProbeAvailable=$true
        WindowDump="mCurrentFocus=Window{42 u0 se.lublin.mumla/.radio.RadioShellActivity}"
        UiXml='<hierarchy><node package="se.lublin.mumla" content-desc="minimum-state-ready"/><node package="se.lublin.mumla" content-desc="Channel E21AS"/></hierarchy>'
        InstallCalls=0; IdentityCalls=0; MigrationCalls=0; RyksCalls=0; StartCalls=0; PackageReads=0
        Transcript=[Collections.Generic.List[string]]::new()
    }
}

$ReportOnly = $false
$NonInteractive = $true
$ConfirmNotTransmitting = $true
$AllowDowngrade = $false
$FullRebootAcceptance = $false
$Serial = ""
$TransportId = 0
$ReadyTimeoutSeconds = 30
$BootTimeoutSeconds = 30
$WhatIfPreference = $false

Test-Case "legacy 3070300 to 3070301 state-machine bootstraps expanded evidence" {
    $scenario = New-UpdaterScenario -Profile "T56" -Level "LEGACY"
    Set-UpdaterScenarioMocks $scenario
    $result = Invoke-OneUpdate -Bundle (New-UpdaterBundle) -SessionId "LEGACYOK"
    Assert-Equal "PASS" $result.Result "result ($($result.ErrorCategory): $($result.Detail))"
    Assert-Equal "BOOTSTRAPPED_POST_UPDATE" $result.PreservationEvidence "bootstrap evidence"
    Assert-Equal "LEGACY_RUN_AS_ID" $result.LegacyProofMode "proof mode"
    Assert-Equal @("RUN_AS_IDENTITY", "STATUS", "IDENTITY_LEGACY") @($scenario.Transcript) "noncreating proof before receiver transcript"
    Assert-Equal 1 $scenario.InstallCalls "install count"
    Assert-Equal 2 $scenario.MigrationCalls "apply plus post-reboot verify"
}

Test-Case "legacy unprovisioned state is rejected without identity or install" {
    $scenario = New-UpdaterScenario -Level "LEGACY"
    $scenario.LegacyProbeAvailable = $false
    $scenario.UiXml = '<hierarchy><node package="se.lublin.mumla" content-desc="minimum-state-offline"/></hierarchy>'
    Set-UpdaterScenarioMocks $scenario
    $result = Invoke-OneUpdate -Bundle (New-UpdaterBundle) -SessionId "LEGACYNO"
    Assert-Equal "LEGACY_NONCREATING_PROBE_UNAVAILABLE" $result.ErrorCategory "category"
    Assert-Equal 0 $scenario.IdentityCalls "identity calls"
    Assert-Equal 0 $scenario.InstallCalls "install count"
    Assert-Equal @("RUN_AS_IDENTITY", "READY_UI_PROBE") @($scenario.Transcript) "no receiver transcript"
}

Test-Case "legacy signer mismatch is reached preinstall without mutation" {
    $scenario = New-UpdaterScenario -Level "LEGACY"
    $scenario.InstalledSigners = @("B" * 64)
    Set-UpdaterScenarioMocks $scenario
    $result = Invoke-OneUpdate -Bundle (New-UpdaterBundle) -SessionId "SIGNERNO"
    Assert-Equal "SIGNER_MISMATCH" $result.ErrorCategory "category"
    Assert-Equal 0 $scenario.IdentityCalls "no receiver identity before signer refusal"
    Assert-Equal 0 $scenario.InstallCalls "install count"
    Assert-Equal @() @($scenario.Transcript) "no receiver or legacy probe needed after signer refusal"
}

Test-Case "legacy ReportOnly without run-as proof invokes no receiver" {
    $scenario = New-UpdaterScenario -Level "LEGACY"; $scenario.LegacyProbeAvailable = $false
    $scenario.UiXml = '<hierarchy><node package="se.lublin.mumla" content-desc="minimum-state-offline"/></hierarchy>'
    Set-UpdaterScenarioMocks $scenario
    $ReportOnly = $true
    try { $result = Invoke-OneUpdate -Bundle (New-UpdaterBundle) -SessionId "LEGACYREPORT" } finally { $ReportOnly = $false }
    Assert-Equal "LEGACY_NONCREATING_PROBE_UNAVAILABLE" $result.ErrorCategory "category"
    Assert-Equal @("RUN_AS_IDENTITY", "READY_UI_PROBE") @($scenario.Transcript) "noncreating probes only"
    Assert-Equal 0 $scenario.IdentityCalls "receiver identity calls"
    Assert-Equal 0 $scenario.InstallCalls "install count"
}

Test-Case "legacy Ready UI fallback permits receiver only after focused package proof" {
    $scenario = New-UpdaterScenario -Profile "T56" -Level "LEGACY"; $scenario.LegacyProbeAvailable = $false
    Set-UpdaterScenarioMocks $scenario
    $ReportOnly = $true
    try { $result = Invoke-OneUpdate -Bundle (New-UpdaterBundle) -SessionId "LEGACYUI" } finally { $ReportOnly = $false }
    Assert-Equal "WARN" $result.Result "legacy report result"
    Assert-Equal "LEGACY_READY_UI" $result.LegacyProofMode "proof mode"
    Assert-Equal "E21AS" $result.LegacySelectedChannelBefore "channel baseline"
    Assert-Equal @("RUN_AS_IDENTITY", "READY_UI_PROBE", "STATUS", "IDENTITY_LEGACY") @($scenario.Transcript) "proof-before-receiver transcript"
    Assert-Equal 0 $scenario.InstallCalls "install count"
}

Test-Case "legacy Ready UI fallback rejects wrong package before receiver" {
    $scenario = New-UpdaterScenario -Level "LEGACY"; $scenario.LegacyProbeAvailable = $false
    $scenario.UiXml = '<hierarchy><node package="other.app" content-desc="minimum-state-ready"/><node package="other.app" content-desc="Channel E21AS"/></hierarchy>'
    Set-UpdaterScenarioMocks $scenario
    $result = Invoke-OneUpdate -Bundle (New-UpdaterBundle) -SessionId "WRONGPKG"
    Assert-Equal "LEGACY_NONCREATING_PROBE_UNAVAILABLE" $result.ErrorCategory "category"
    Assert-Equal @("RUN_AS_IDENTITY", "READY_UI_PROBE") @($scenario.Transcript) "no receiver transcript"
    Assert-Equal 0 $scenario.IdentityCalls "identity receiver calls"
}

Test-Case "legacy Ready UI fallback rejects not-Ready screen before receiver" {
    $scenario = New-UpdaterScenario -Level "LEGACY"; $scenario.LegacyProbeAvailable = $false
    $scenario.UiXml = '<hierarchy><node package="se.lublin.mumla" content-desc="minimum-state-connecting"/></hierarchy>'
    Set-UpdaterScenarioMocks $scenario
    $result = Invoke-OneUpdate -Bundle (New-UpdaterBundle) -SessionId "NOTREADY"
    Assert-Equal "LEGACY_NONCREATING_PROBE_UNAVAILABLE" $result.ErrorCategory "category"
    Assert-Equal @("RUN_AS_IDENTITY", "READY_UI_PROBE") @($scenario.Transcript) "no receiver transcript"
    Assert-Equal 0 $scenario.IdentityCalls "identity receiver calls"
}

Test-Case "legacy Ready UI fallback rejects unfocused app before receiver" {
    $scenario = New-UpdaterScenario -Level "LEGACY"; $scenario.LegacyProbeAvailable = $false
    $scenario.WindowDump = "mCurrentFocus=Window{42 u0 com.android.settings/.Settings}"
    Set-UpdaterScenarioMocks $scenario
    $result = Invoke-OneUpdate -Bundle (New-UpdaterBundle) -SessionId "UNFOCUSED"
    Assert-Equal "LEGACY_NONCREATING_PROBE_UNAVAILABLE" $result.ErrorCategory "category"
    Assert-Equal @("RUN_AS_IDENTITY", "READY_UI_PROBE") @($scenario.Transcript) "no receiver transcript"
    Assert-Equal 0 $scenario.IdentityCalls "identity receiver calls"
}

Test-Case "ReportOnly uses existing identity and never installs" {
    $scenario = New-UpdaterScenario
    $scenario.InstalledCode = 3070301; $scenario.Installed = $true
    Set-UpdaterScenarioMocks $scenario
    $ReportOnly = $true
    try { $result = Invoke-OneUpdate -Bundle (New-UpdaterBundle) -SessionId "REPORT" } finally { $ReportOnly = $false }
    Assert-Equal "PASS" $result.Result "result"
    Assert-Equal 0 $scenario.InstallCalls "install count"
    Assert-Equal @("STATUS", "IDENTITY_EXISTING") @($scenario.Transcript) "read-only transcript"
}

Test-Case "post-install failure relaunches and reports verified recovery" {
    $scenario = New-UpdaterScenario
    $scenario.PostVersionMismatch = $true
    Set-UpdaterScenarioMocks $scenario
    $result = Invoke-OneUpdate -Bundle (New-UpdaterBundle) -SessionId "RECOVER"
    Assert-Equal "POST_VERSION_MISMATCH" $result.ErrorCategory "category"
    Assert-True ($result.Detail -match 'RECOVERY_VERIFIED') "recovery evidence"
    Assert-Equal 1 $scenario.InstallCalls "install count"
    Assert-Equal 1 $scenario.StartCalls "recovery relaunch"
}

Test-Case "T99 and RYKS route only their approved state-machine paths" {
    $t99 = New-UpdaterScenario -Profile "T99"; Set-UpdaterScenarioMocks $t99
    $t99Result = Invoke-OneUpdate -Bundle (New-UpdaterBundle) -SessionId "T99"
    Assert-Equal "PASS" $t99Result.Result "T99 result"; Assert-Equal 0 $t99.MigrationCalls "T99 cellular skip"; Assert-Equal 0 $t99.RyksCalls "T99 RYKS skip"
    $ryks = New-UpdaterScenario -Profile "RYKS"; Set-UpdaterScenarioMocks $ryks
    $ryksResult = Invoke-OneUpdate -Bundle (New-UpdaterBundle) -SessionId "RYKS"
    Assert-Equal "PASS" $ryksResult.Result "RYKS result"; Assert-Equal 0 $ryks.MigrationCalls "RYKS cellular skip"; Assert-Equal 1 $ryks.RyksCalls "RYKS policy"
}

Test-Case "reboot timeout clears correlation and forbids wrong-target recovery" {
    $scenario = New-UpdaterScenario -Profile "T56" -Level "LEGACY"
    $scenario.RebootFailure = $true
    Set-UpdaterScenarioMocks $scenario
    $result = Invoke-OneUpdate -Bundle (New-UpdaterBundle) -SessionId "REBOOTNO"
    Assert-Equal "REBOOT_TARGET_AMBIGUOUS" $result.ErrorCategory "category"
    Assert-Equal $null $script:CurrentTarget "cleared target"
    Assert-Equal 1 $scenario.StartCalls "only pre-reboot launch"
    Assert-True ($result.Detail -notmatch 'RECOVERY_') "no wrong-target recovery"
}

Test-Case "partial sequential session continues after a failed device" {
    $first = New-UpdaterScenario; $first.InstalledSigners = @("B" * 64); Set-UpdaterScenarioMocks $first
    $failed = Invoke-OneUpdate -Bundle (New-UpdaterBundle) -SessionId "BATCH"
    $second = New-UpdaterScenario -Profile "RYKS"; Set-UpdaterScenarioMocks $second
    $passed = Invoke-OneUpdate -Bundle (New-UpdaterBundle) -SessionId "BATCH"
    $summary = Format-SessionSummary @($failed, $passed) "3.7.3-minimum.2"
    Assert-Equal "FAIL" $failed.Result "first result"; Assert-Equal "PASS" $passed.Result "second result"
    Assert-True ($summary -match 'Totals: 1 PASS, 0 WARN, 1 FAIL') "partial session summary"
}

Test-Case "Resolve-ApkSigner tolerates unset Linux-style SDK environment" {
    $oldLocal = $env:LOCALAPPDATA; $oldHome = $env:ANDROID_HOME; $oldRoot = $env:ANDROID_SDK_ROOT
    try {
        $env:LOCALAPPDATA = $null; $env:ANDROID_HOME = $null; $env:ANDROID_SDK_ROOT = $null
        try { $resolved = Resolve-ApkSigner; Assert-True ([bool]$resolved) "resolved signer" }
        catch { if ($_.Exception.Message -notmatch '^\[APKSIGNER_MISSING\]') { throw } }
    } finally { $env:LOCALAPPDATA = $oldLocal; $env:ANDROID_HOME = $oldHome; $env:ANDROID_SDK_ROOT = $oldRoot }
}

Test-Case "SDK discovery accepts extensionless Linux apksigner" {
    $root = Join-Path ([IO.Path]::GetTempPath()) ("minimum-sdk-test-{0}" -f [guid]::NewGuid().ToString("N"))
    try {
        $directory = Join-Path $root "build-tools\99.0.0"
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
        $expected = Join-Path $directory "apksigner"
        Set-Content -LiteralPath $expected -Value "#!/bin/sh" -NoNewline -Encoding ASCII
        Assert-Equal $expected (Find-ApkSignerInSdkRoots @($root)) "Linux apksigner path"
    } finally {
        if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
    }
}

Write-Host "Updater tests: $script:Passed passed, $script:Failed failed"
if ($script:Failed -gt 0) { exit 1 }
