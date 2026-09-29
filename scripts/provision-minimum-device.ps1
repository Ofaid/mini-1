<#
.SYNOPSIS
    Provisions one supported Minimum radio from APK installation through reboot acceptance.

.DESCRIPTION
    This is the operator-facing one-shot workflow for known T99, T56 and RYKS hardware. It selects one
    Release artifact before opening ADB, then selects one authorized target and verifies its hardware
    model. Source-only development APKs are signature-checked without claiming Release trust. It
    installs the selected APK without clearing app data, runs the guarded model preparation, waits for the
    Device ID profile to become available from the portal, waits for Ready, reboots the radio, and
    waits for Ready again.

    The script opens the Minimum portal after displaying the six-character Device ID. Create that
    Device Profile once; no bearer token has to be copied to the radio. Unknown hardware is reported
    and rejected before any APK installation or provisioning change.

    Connect only one unit of a given model while using the reboot acceptance step. ADB transport IDs
    can change across reboot, so the script must be able to identify exactly one returning unit.
#>

[CmdletBinding()]
param(
    [string]$Serial = "",
    [int]$TransportId = 0,
    [ValidateRange(0, 65535)][int]$AdbPort = 0,
    [string]$ApkPath = "",
    [switch]$BuildApk,
    [Alias("DeviceId")]
    [string]$DeviceProfile = "",
    [string]$PortalUrl = "https://minimum.vra.or.th/",
    [switch]$SkipOpenPortal,
    [switch]$NonInteractive,
    [switch]$SkipZello,
    [switch]$SkipMinimumHome,
    [switch]$SkipLabWifi,
    [switch]$SkipLocation,
    [switch]$DisableDataRoaming,
    [switch]$RequestNetworkLocationConsent,
    [switch]$RefreshLabWifi,
    [string]$LabWifiSsid = "..@EmergencyTU",
    [string]$LabWifiCredentialPath = "",
    [ValidateRange(30, 900)][int]$ReadyTimeoutSeconds = 180,
    [ValidateRange(30, 900)][int]$BootTimeoutSeconds = 180,
    [switch]$SkipReboot,
    [Parameter(DontShow = $true)][switch]$LibraryOnly
)

$ErrorActionPreference = "Stop"
$GuidedMode = $PSBoundParameters.Count -eq 0
$MinimumPackage = "se.lublin.mumla"
$MinimumActivity = "se.lublin.mumla/.radio.RadioShellActivity"
$ProvisionReceiver = "se.lublin.mumla/.radio.RadioProvisionReceiver"
$IdentityReportAction = "se.lublin.mumla.action.PROVISION_REPORT_IDENTITY"
$ProvisionStatusAction = "se.lublin.mumla.action.PROVISION_REPORT_STATUS"
$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$BundledApkPath = Join-Path $RepositoryRoot "minimum-foss.apk"
$SourceBuildApkPath = Join-Path $RepositoryRoot "app\build\outputs\apk\foss\debug\mumla-foss-debug.apk"
$SourceBuildAvailable = Test-Path -LiteralPath (Join-Path $RepositoryRoot "gradlew.bat") -PathType Leaf
$DefaultApkPath = if (Test-Path -LiteralPath $BundledApkPath -PathType Leaf) {
    $BundledApkPath
} else {
    $SourceBuildApkPath
}
$adbPath = ""
$serverArgs = @()
$script:targetArgs = @()
$script:targetLabel = ""
$script:targetRecord = $null
$script:CellularReadinessWarning = $false

if ($DeviceProfile -and (($DeviceProfile -cnotmatch '^[A-Z0-9]{6}$') -or
        ($DeviceProfile -notmatch '[A-Z]') -or ($DeviceProfile -notmatch '\d'))) {
    throw "DeviceProfile must be six uppercase A-Z/0-9 characters with a letter and digit."
}
if ($SkipLocation -and $RequestNetworkLocationConsent) {
    throw "-SkipLocation and -RequestNetworkLocationConsent cannot be used together."
}

function Get-ListeningAdbPorts {
    $ports = @()
    try {
        $ports = @(Get-NetTCPConnection -State Listen -ErrorAction Stop |
            Where-Object { $_.LocalPort -in @(5037, 5041) } |
            Select-Object -ExpandProperty LocalPort -Unique)
    } catch {
        $netstat = & netstat.exe -ano -p TCP 2>$null
        foreach ($line in $netstat) {
            if ($line -match '^\s*TCP\s+\S+:(5037|5041)\s+\S+\s+LISTENING\s+') {
                $ports += [int]$Matches[1]
            }
        }
    }
    return @($ports | Sort-Object -Unique)
}

function Get-AuthorizedDeviceCount {
    param([Parameter(Mandatory)][int]$Port)
    $lines = & $adbPath -P $Port devices 2>$null
    if ($LASTEXITCODE -ne 0) {
        return 0
    }
    return @($lines | Where-Object { $_ -match '^[^\s]+\s+device(?:\s|$)' }).Count
}

function Select-AdbServerPort {
    if ($AdbPort -gt 0) {
        Write-Host "ADB port: $AdbPort (selected by advanced command-line option)."
        return $AdbPort
    }

    $listening = @(Get-ListeningAdbPorts)
    if ($listening.Count -eq 0) {
        Write-Host "ADB port: 5037 (standard port; the ADB server will start automatically)."
        return 5037
    }

    $withDevices = foreach ($port in $listening) {
        $count = Get-AuthorizedDeviceCount -Port $port
        if ($count -gt 0) {
            [pscustomobject]@{ Port = $port; Count = $count }
        }
    }
    $withDevices = @($withDevices)
    if ($withDevices.Count -eq 1) {
        Write-Host "ADB port: $($withDevices[0].Port) (auto-detected $($withDevices[0].Count) authorized device(s))."
        return $withDevices[0].Port
    }
    if ($listening.Count -eq 1) {
        Write-Host "ADB port: $($listening[0]) (existing ADB server; connect and authorize the device if it is not listed yet)."
        return $listening[0]
    }
    if ($NonInteractive) {
        throw "More than one ADB server is active. Pass -AdbPort 5037 or -AdbPort 5041."
    }

    Write-Host ""
    Write-Host "More than one ADB server is active. Choose the server that owns the device:"
    Write-Host "  [1] Port 5037 - Android standard (recommended for a new workstation)"
    Write-Host "  [2] Port 5041 - Existing Minimum lab setup"
    while ($true) {
        $choice = (Read-Host "Select 1 or 2").Trim()
        if ($choice -eq "1") { return 5037 }
        if ($choice -eq "2") { return 5041 }
        Write-Host "Please enter 1 or 2."
    }
}

function Show-GuidedSetupMenu {
    param([Parameter(Mandatory)][string]$Profile)

    Write-Host ""
    Write-Host "Recommended setup will:"
    if ($SourceBuildAvailable) {
        Write-Host "  - build and install the latest Minimum test APK"
    } elseif ($Profile -eq "RYKS") {
        $script:SkipLocation = $true
        Write-Host "Location tracking is not enabled for the RYKS profile."
    } else {
        Write-Host "  - install the signed Minimum APK included in this Release bundle"
    }
    Write-Host "  - configure lab Wi-Fi and managed Location"
    Write-Host "  - remove Zello for Android user 0"
    Write-Host "  - open the Portal to register the displayed Device ID"
    Write-Host "  - verify Ready, reboot, and verify Ready again"
    Write-Host ""
    while ($true) {
        $mode = (Read-Host "Press Enter to start, C for custom choices, or Q to quit").Trim()
        if (-not $mode -or $mode -ieq "C" -or $mode -ieq "Q") { break }
        Write-Host "Please press Enter, C or Q."
    }
    if ($mode -ieq "Q") {
        Write-Host "Cancelled. No APK was installed and no provisioning change was made."
        exit 0
    }
    $script:BuildApk = $SourceBuildAvailable
    if (-not $mode) {
        return
    }

    if ($SourceBuildAvailable) {
        $answer = (Read-Host "Build the latest APK? [Y/n]").Trim()
        if ($answer -ieq "N") { $script:BuildApk = $false }
    } else {
        Write-Host "Source build tools are not included; the bundled signed APK will be used."
    }

    $answer = (Read-Host "Configure/verify lab Wi-Fi? [Y/n]").Trim()
    if ($answer -ieq "N") { $script:SkipLabWifi = $true }

    if ($Profile -eq "T56") {
        Write-Host "Location: [1] GPS only (recommended)  [2] GPS + network consent  [3] Skip"
        while ($true) {
            $answer = (Read-Host "Select 1, 2 or 3 [1]").Trim()
            if (-not $answer -or $answer -eq "1") { break }
            if ($answer -eq "2") {
                $script:RequestNetworkLocationConsent = $true
                break
            }
            if ($answer -eq "3") {
                $script:SkipLocation = $true
                break
            }
            Write-Host "Please enter 1, 2 or 3."
        }
    } else {
        $answer = (Read-Host "Configure managed Location? [Y/n]").Trim()
        if ($answer -ieq "N") { $script:SkipLocation = $true }
    }

    $answer = (Read-Host "Remove Zello for Android user 0? [Y/n]").Trim()
    if ($answer -ieq "N") { $script:SkipZello = $true }

    $answer = (Read-Host "Reboot and verify unattended startup? [Y/n]").Trim()
    if ($answer -ieq "N") { $script:SkipReboot = $true }
}

function Get-ConnectedDevices {
    $lines = & $adbPath @serverArgs devices -l
    if ($LASTEXITCODE -ne 0) {
        throw "Could not query ADB on port $AdbPort."
    }
    $records = foreach ($line in $lines) {
        if ($line -match '^([^\s]+)\s+device(?:\s|$)') {
            $deviceSerial = $Matches[1]
            $transport = 0
            if ($line -match '\btransport_id:(\d+)\b') {
                $transport = [int]$Matches[1]
            }
            [pscustomobject]@{
                Serial = $deviceSerial
                TransportId = $transport
                Line = $line
            }
        }
    }
    return @($records)
}

function Get-RecordAdbArguments {
    param([Parameter(Mandatory)]$Record)
    if ($Record.TransportId -gt 0) {
        return $serverArgs + @("-t", "$($Record.TransportId)")
    }
    return $serverArgs + @("-s", $Record.Serial)
}

function Invoke-AdbForRecord {
    param(
        [Parameter(Mandatory)]$Record,
        [Parameter(Mandatory)][string[]]$Arguments
    )
    $recordArgs = Get-RecordAdbArguments -Record $Record
    $commandArgs = $recordArgs + $Arguments
    $output = & $adbPath @commandArgs
    if ($LASTEXITCODE -ne 0) {
        throw "ADB command failed for connected device '$($Record.Serial)' with exit code $LASTEXITCODE."
    }
    return $output
}

function Get-RecordProperty {
    param(
        [Parameter(Mandatory)]$Record,
        [Parameter(Mandatory)][string]$Name
    )
    return ((Invoke-AdbForRecord -Record $Record -Arguments @("shell", "getprop", $Name)) -join "").Trim()
}

function Add-RecordHardwareIdentity {
    param([Parameter(Mandatory)]$Record)
    $manufacturer = Get-RecordProperty -Record $Record -Name "ro.product.manufacturer"
    $model = Get-RecordProperty -Record $Record -Name "ro.product.model"
    $profile = if ($manufacturer -ieq "Youdotech" -and $model -ieq "QM011") {
        "T99"
    } elseif ($manufacturer -ieq "UNIPRO" -and $model -ieq "ZX") {
        "T56"
    } elseif ($manufacturer -ieq "ELINK" -and $model -ieq "ym_258") {
        "RYKS"
    } else {
        ""
    }
    $Record | Add-Member -NotePropertyName Manufacturer -NotePropertyValue $manufacturer -Force
    $Record | Add-Member -NotePropertyName Model -NotePropertyValue $model -Force
    $Record | Add-Member -NotePropertyName Profile -NotePropertyValue $profile -Force
    return $Record
}

function Select-InitialTarget {
    $devices = @(Get-ConnectedDevices)
    while ($devices.Count -eq 0 -and -not $NonInteractive) {
        Write-Host ""
        Write-Host "No authorized Android device was found on ADB port $AdbPort."
        Write-Host "1. Connect USB and unlock the radio."
        Write-Host "2. Enable USB debugging."
        Write-Host "3. Accept the 'Allow USB debugging' message on the radio."
        $choice = (Read-Host "Press Enter to check again, P to change ADB port, or Q to quit").Trim()
        if ($choice -ieq "Q") {
            Write-Host "Cancelled. No device change was made."
            exit 0
        }
        if ($choice -ieq "P") {
            Write-Host "  [1] Port 5037 - Android standard"
            Write-Host "  [2] Port 5041 - Minimum lab setup"
            $portChoice = (Read-Host "Select 1 or 2").Trim()
            if ($portChoice -eq "1") { $script:AdbPort = 5037 }
            if ($portChoice -eq "2") { $script:AdbPort = 5041 }
            $script:serverArgs = @("-P", "$AdbPort")
        }
        $devices = @(Get-ConnectedDevices)
    }
    if ($devices.Count -eq 0) {
        throw "No authorized Android device was found on ADB port $AdbPort."
    }
    if ($TransportId -gt 0) {
        $matches = @($devices | Where-Object { $_.TransportId -eq $TransportId })
        if ($matches.Count -ne 1) {
            throw "Authorized ADB transport id $TransportId was not found on port $AdbPort."
        }
        return Add-RecordHardwareIdentity -Record $matches[0]
    }
    if ($Serial) {
        $pattern = "^$([regex]::Escape($Serial))$"
        $matches = @($devices | Where-Object { $_.Serial -match $pattern })
        if ($matches.Count -ne 1) {
            throw "Expected exactly one authorized device with serial '$Serial'; use -TransportId when duplicated."
        }
        return Add-RecordHardwareIdentity -Record $matches[0]
    }

    $identifiedDevices = @($devices | ForEach-Object { Add-RecordHardwareIdentity -Record $_ })
    if ($identifiedDevices.Count -eq 1) {
        return $identifiedDevices[0]
    }
    if ($NonInteractive) {
        throw "More than one authorized device is connected; pass -Serial or -TransportId."
    }

    Write-Host ""
    Write-Host "More than one Android device is connected. Select the radio to provision:"
    for ($index = 0; $index -lt $identifiedDevices.Count; $index++) {
        $device = $identifiedDevices[$index]
        $support = if ($device.Profile) { $device.Profile } else { "unsupported - inventory only" }
        Write-Host ("  [{0}] {1}/{2} - {3}" -f ($index + 1),
            $device.Manufacturer, $device.Model, $support)
    }
    while ($true) {
        $choice = (Read-Host "Enter device number or Q to quit").Trim()
        if ($choice -ieq "Q") {
            Write-Host "Cancelled. No device change was made."
            exit 0
        }
        $number = 0
        if ([int]::TryParse($choice, [ref]$number) -and
                $number -ge 1 -and $number -le $identifiedDevices.Count) {
            return $identifiedDevices[$number - 1]
        }
        Write-Host "Please enter a number shown in the list."
    }
}

function Set-Target {
    param([Parameter(Mandatory)]$Record)
    $script:targetRecord = $Record
    $script:targetArgs = Get-RecordAdbArguments -Record $Record
    $script:targetLabel = if ($Record.TransportId -gt 0) {
        "transport id $($Record.TransportId)"
    } else {
        "ADB serial $($Record.Serial)"
    }
}

function Invoke-TargetAdb {
    param([Parameter(Mandatory)][string[]]$Arguments)
    $commandArgs = $script:targetArgs + $Arguments
    $output = & $adbPath @commandArgs
    if ($LASTEXITCODE -ne 0) {
        throw "ADB command failed for $script:targetLabel with exit code $LASTEXITCODE."
    }
    return $output
}

function Get-MinimumDeviceId {
    $output = Invoke-TargetAdb -Arguments @(
        "shell", "am", "broadcast", "-W",
        "-a", $IdentityReportAction,
        "-n", $ProvisionReceiver
    )
    $match = [regex]::Match(($output -join "`n"), 'data="?([A-Z0-9]{6})"?')
    if (-not $match.Success) {
        throw "Minimum did not return a six-character Device ID."
    }
    return $match.Groups[1].Value
}

function Get-MinimumProvisioningStatus {
    $output = Invoke-TargetAdb -Arguments @(
        "shell", "am", "broadcast", "-W",
        "-a", $ProvisionStatusAction,
        "-n", $ProvisionReceiver
    )
    $text = $output -join "`n"
    $match = [regex]::Match($text,
        'data="?deviceId=([A-Z0-9]{6});activeDeviceId=([A-Z0-9*]{1,6});configVersion=(-?\d+);pending=(true|false);lastSuccessMs=(\d+)"?')
    if (-not $match.Success) {
        return $null
    }
    return [pscustomobject]@{
        DeviceId = $match.Groups[1].Value
        ActiveDeviceId = $match.Groups[2].Value
        ConfigVersion = [int]$match.Groups[3].Value
        Pending = $match.Groups[4].Value -eq "true"
        LastSuccessMs = [long]$match.Groups[5].Value
    }
}

function Build-MinimumApk {
    $buildRoot = $RepositoryRoot
    $temporaryJunction = ""
    try {
        if ($RepositoryRoot -match '\s') {
            $temporaryJunction = Join-Path ([IO.Path]::GetTempPath()) (
                "minimum-build-{0}" -f [guid]::NewGuid().ToString("N"))
            New-Item -ItemType Junction -Path $temporaryJunction `
                -Target $RepositoryRoot -ErrorAction Stop | Out-Null
            $buildRoot = $temporaryJunction
            Write-Host "Using a temporary path without spaces for the Android NDK build."
        }
        $gradleWrapper = Join-Path $buildRoot "gradlew.bat"
        if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
            throw "Gradle wrapper is missing: $gradleWrapper"
        }
        Write-Host "Building the FOSS debug APK from the current source..."
        Push-Location $buildRoot
        try {
            & $gradleWrapper :app:assembleFossDebug --no-daemon
            if ($LASTEXITCODE -ne 0) {
                throw "FOSS debug APK build failed with exit code $LASTEXITCODE."
            }
        } finally {
            Pop-Location
        }
    } finally {
        if ($temporaryJunction -and (Test-Path -LiteralPath $temporaryJunction)) {
            $junction = Get-Item -LiteralPath $temporaryJunction -Force
            if ($junction.LinkType -ne "Junction") {
                throw "Refusing to remove unexpected temporary build path: $temporaryJunction"
            }
            $junctionTarget = (Resolve-Path -LiteralPath @($junction.Target)[0]).Path
            if ($junctionTarget -ne $RepositoryRoot) {
                throw "Refusing to remove temporary junction with unexpected target: $junctionTarget"
            }
            [IO.Directory]::Delete($temporaryJunction)
        }
    }
}

function Test-TargetLabWifiConnected {
    $connectivity = (Invoke-TargetAdb -Arguments @("shell", "dumpsys", "connectivity")) -join "`n"
    $escapedSsid = [regex]::Escape($LabWifiSsid)
    return $connectivity -match
        "(?s)type:\s*WIFI.*?state:\s*CONNECTED/CONNECTED.*?extra:\s*`"$escapedSsid`""
}

function Ensure-LabWifiCredential {
    param([Parameter(Mandatory)][string]$Profile)
    if ($SkipLabWifi -or (Test-TargetLabWifiConnected)) {
        return
    }
    if (-not $LabWifiCredentialPath) {
        $credentialName = "{0}-lab-wifi.credential.xml" -f $Profile.ToLowerInvariant()
        $script:LabWifiCredentialPath = Join-Path $PSScriptRoot ".secrets\$credentialName"
    }
    if (Test-Path -LiteralPath $LabWifiCredentialPath) {
        return
    }
    if ($NonInteractive) {
        throw "Lab Wi-Fi is not connected. Create the DPAPI credential or pass -SkipLabWifi."
    }

    Write-Host "Lab Wi-Fi '$LabWifiSsid' is not connected. Windows will request its password."
    $credential = Get-Credential -UserName $LabWifiSsid `
        -Message "Enter the Minimum lab Wi-Fi password"
    if (-not $credential) {
        throw "Lab Wi-Fi credential entry was cancelled."
    }
    $credentialDirectory = Split-Path -Parent $LabWifiCredentialPath
    New-Item -ItemType Directory -Path $credentialDirectory -Force | Out-Null
    $credential | Export-Clixml -LiteralPath $LabWifiCredentialPath
    $credential = $null
    Write-Host "Lab Wi-Fi credential saved with Windows DPAPI for this account."
}

function Invoke-ModelPreparation {
    param([Parameter(Mandatory)][string]$Profile)
    $prepareScript = switch ($Profile) {
        "T56" { Join-Path $PSScriptRoot "prepare-t56.ps1" }
        "RYKS" { Join-Path $PSScriptRoot "prepare-ryks.ps1" }
        default { Join-Path $PSScriptRoot "prepare-t99.ps1" }
    }
    $arguments = @(
        "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $prepareScript,
        "-AdbPort", "$AdbPort", "-Force", "-LabWifiSsid", $LabWifiSsid
    )
    if ($script:targetRecord.TransportId -gt 0) {
        $arguments += @("-TransportId", "$($script:targetRecord.TransportId)")
    } else {
        $arguments += @("-Serial", $script:targetRecord.Serial)
    }
    if ($DeviceProfile) { $arguments += @("-DeviceProfile", $DeviceProfile) }
    if ($SkipZello) { $arguments += "-SkipZello" }
    if ($SkipMinimumHome) { $arguments += "-SkipMinimumHome" }
    if ($SkipLabWifi) { $arguments += "-SkipLabWifi" }
    if ($SkipLocation) { $arguments += "-SkipLocation" }
    if ($DisableDataRoaming) { $arguments += "-DisableDataRoaming" }
    if ($RequestNetworkLocationConsent) { $arguments += "-RequestNetworkLocationConsent" }
    if ($RefreshLabWifi) { $arguments += "-RefreshLabWifi" }
    if ($LabWifiCredentialPath) {
        $arguments += @("-LabWifiCredentialPath", $LabWifiCredentialPath)
    }

    Write-Host "Running guarded $Profile preparation..."
    & powershell.exe @arguments
    if ($LASTEXITCODE -eq 2 -and $Profile -eq "T56") {
        $script:CellularReadinessWarning = $true
        Write-Warning "T56 preparation completed with a documented cellular-readiness warning."
    } elseif ($LASTEXITCODE -ne 0) {
        throw "$Profile preparation failed with exit code $LASTEXITCODE."
    }
}

function Ensure-DisplayAwake {
    $powerState = (Invoke-TargetAdb -Arguments @("shell", "dumpsys", "power")) -join "`n"
    if ($powerState -notmatch 'Display Power: state=OFF' -and
            $powerState -notmatch 'mWakefulness=(Asleep|Dozing)') {
        return
    }
    Invoke-TargetAdb -Arguments @("shell", "input", "keyevent", "224") | Out-Null
    Start-Sleep -Seconds 1
    $powerState = (Invoke-TargetAdb -Arguments @("shell", "dumpsys", "power")) -join "`n"
    if ($powerState -match 'Display Power: state=OFF' -or
            $powerState -match 'mWakefulness=(Asleep|Dozing)') {
        Invoke-TargetAdb -Arguments @("shell", "input", "keyevent", "26") | Out-Null
        Start-Sleep -Seconds 2
    }
}

function Wait-MinimumReady {
    param(
        [Parameter(Mandatory)][string]$Phase,
        [Parameter(Mandatory)][string]$ExpectedDeviceId,
        [Parameter(Mandatory)][int]$TimeoutSeconds
    )
    $remoteUi = "/sdcard/minimum-provision-ready-$PID.xml"
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        Ensure-DisplayAwake
        try {
            Invoke-TargetAdb -Arguments @("shell", "uiautomator", "dump", $remoteUi) | Out-Null
            $ui = (Invoke-TargetAdb -Arguments @("shell", "cat", $remoteUi)) -join "`n"
            if ($ui -match 'content-desc="minimum-state-ready"') {
                $status = Get-MinimumProvisioningStatus
                if ($status -and $status.DeviceId -eq $ExpectedDeviceId -and
                        $status.ActiveDeviceId -eq $ExpectedDeviceId -and
                        -not $status.Pending -and $status.ConfigVersion -gt 0 -and
                        $status.LastSuccessMs -gt 0) {
                    Write-Host "Checkpoint: Minimum reached Ready $Phase with managed config v$($status.ConfigVersion)."
                    return
                }
            }
        } finally {
            $cleanupArgs = $script:targetArgs + @("shell", "rm", "-f", $remoteUi)
            & $adbPath @cleanupArgs 1>$null 2>$null
        }
        Start-Sleep -Seconds 5
    }
    throw "Minimum did not reach Ready $Phase within $TimeoutSeconds seconds. Check network, Portal registration and device config."
}

function Wait-ForReturningTarget {
    param(
        [Parameter(Mandatory)][string]$Manufacturer,
        [Parameter(Mandatory)][string]$Model,
        [Parameter(Mandatory)][string]$OriginalSerial,
        [Parameter(Mandatory)][int]$TimeoutSeconds
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $devices = @(Get-ConnectedDevices)
        $serialMatches = @($devices | Where-Object { $_.Serial -eq $OriginalSerial })
        if ($serialMatches.Count -eq 1) {
            $candidate = Add-RecordHardwareIdentity -Record $serialMatches[0]
            if ($candidate.Manufacturer -ieq $Manufacturer -and $candidate.Model -ieq $Model) {
                return $candidate
            }
        }
        $modelMatches = foreach ($device in $devices) {
            $candidate = Add-RecordHardwareIdentity -Record $device
            if ($candidate.Manufacturer -ieq $Manufacturer -and $candidate.Model -ieq $Model) {
                $candidate
            }
        }
        $modelMatches = @($modelMatches)
        if ($modelMatches.Count -eq 1) {
            return $modelMatches[0]
        }
    }
    throw "Could not identify exactly one returning $Manufacturer/$Model after reboot. Connect only one unit of this model and rerun Ready verification."
}

function Wait-AndroidBootCompleted {
    param([Parameter(Mandatory)][int]$TimeoutSeconds)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $completed = ((Invoke-TargetAdb -Arguments @(
            "shell", "getprop", "sys.boot_completed")) -join "").Trim()
        if ($completed -eq "1") {
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "Android did not finish booting within $TimeoutSeconds seconds."
}

function Test-ReleaseBundleLayout {
    param([Parameter(Mandatory)][string]$Root)

    foreach ($relative in @("RELEASE-MANIFEST.json", "VERSION.txt", "minimum-foss.apk")) {
        if (Test-Path -LiteralPath (Join-Path $Root $relative)) {
            return $true
        }
    }
    return $false
}

function Test-SameCanonicalPath {
    param([Parameter(Mandatory)][string]$Left, [Parameter(Mandatory)][string]$Right)

    $leftFull = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $Left).Path)
    $rightFull = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $Right).Path)
    return $leftFull.Equals($rightFull, [StringComparison]::OrdinalIgnoreCase)
}

function Get-VerifiedReleaseProvisioningArtifact {
    param(
        [Parameter(Mandatory)][string]$Root,
        [string]$RequestedApkPath = ""
    )

    $manifestPath = Join-Path $Root "RELEASE-MANIFEST.json"
    $bundledApk = Join-Path $Root "minimum-foss.apk"
    $updaterPath = Join-Path $Root "scripts\update-minimum-device.ps1"
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf) -or
            -not (Test-Path -LiteralPath $bundledApk -PathType Leaf)) {
        throw "[BUNDLE_INCOMPLETE] Release bundle markers are present, but the manifest or bundled APK is missing. No ADB command or device change was attempted."
    }
    if (-not (Test-Path -LiteralPath $updaterPath -PathType Leaf)) {
        throw "[BUNDLE_VERIFIER_MISSING] The bundle verifier is missing. Authenticate the outer ZIP with its separately published checksum before trusting any extracted verifier. No ADB command or device change was attempted."
    }
    if ($RequestedApkPath) {
        if (-not (Test-Path -LiteralPath $RequestedApkPath -PathType Leaf) -or
                -not (Test-SameCanonicalPath -Left $RequestedApkPath -Right $bundledApk)) {
            throw "[APK_PATH_OUTSIDE_BUNDLE] Release provisioning accepts only the APK bound by the extracted Release manifest. No ADB command or device change was attempted."
        }
    }

    # Invoke the updater as a library only inside a child scope. Dot-sourcing it in this script's
    # scope would overwrite provisioning parameters such as Serial, TransportId and AdbPort.
    return & {
        param($VerifierPath, $BundleRoot)
        . $VerifierPath -LibraryOnly

        $bundle = Read-ReleaseBundle -Root $BundleRoot
        $identity = Get-ApkManifestIdentity -ApkPath $bundle.ApkPath
        if ($identity.ApplicationId -cne [string]$bundle.Manifest.applicationId -or
                $identity.VersionCode -ne [long]$bundle.Manifest.versionCode -or
                $identity.VersionName -cne [string]$bundle.Manifest.versionName) {
            Throw-UpdateError "APK_IDENTITY_BINDING" "The APK package/version does not match the exact Release manifest."
        }
        $targetSigners = @(Get-ApkSignerDigests -ApkPath $bundle.ApkPath)
        $reviewedSigner = ([string]$bundle.Manifest.signerSha256).ToUpperInvariant()
        if ($targetSigners.Count -ne 1 -or $targetSigners[0] -cne $reviewedSigner) {
            Throw-UpdateError "APK_SIGNER_BINDING" "The APK must contain exactly the one reviewed signer from the Release manifest; missing or extra signers are refused."
        }
        $apkSha256 = (Get-FileHash -LiteralPath $bundle.ApkPath -Algorithm SHA256).Hash.ToUpperInvariant()
        return [pscustomobject]@{
            ApkPath = $bundle.ApkPath
            ApplicationId = $identity.ApplicationId
            VersionCode = $identity.VersionCode
            VersionName = $identity.VersionName
            ApkSha256 = $apkSha256
            SignerSha256 = $targetSigners[0]
            Trust = "RELEASE_MANIFEST_AND_SIGNER_VERIFIED"
        }
    } $updaterPath $Root
}

function Get-VerifiedDevelopmentProvisioningArtifact {
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$DevelopmentApkPath
    )

    $updaterPath = Join-Path $Root "scripts\update-minimum-device.ps1"
    if (-not (Test-Path -LiteralPath $updaterPath -PathType Leaf)) {
        throw "[DEVELOPMENT_VERIFIER_MISSING] The APK verifier is missing. No installation was attempted."
    }
    if (-not (Test-Path -LiteralPath $DevelopmentApkPath -PathType Leaf)) {
        throw "[DEVELOPMENT_APK_MISSING] The requested development APK is missing. No installation was attempted."
    }

    return & {
        param($VerifierPath, $CandidatePath, $ExpectedApplicationId)
        . $VerifierPath -LibraryOnly

        $identity = Get-ApkManifestIdentity -ApkPath $CandidatePath
        if ($identity.ApplicationId -cne $ExpectedApplicationId) {
            Throw-UpdateError "DEVELOPMENT_APK_IDENTITY" "The development APK package is not Minimum."
        }
        $signers = @(Get-ApkSignerDigests -ApkPath $CandidatePath)
        if ($signers.Count -ne 1) {
            Throw-UpdateError "DEVELOPMENT_APK_SIGNATURE" "The development APK must have exactly one cryptographically verified signer."
        }
        $resolvedCandidate = (Resolve-Path -LiteralPath $CandidatePath).Path
        return [pscustomobject]@{
            ApkPath = $resolvedCandidate
            ApplicationId = $identity.ApplicationId
            VersionCode = $identity.VersionCode
            VersionName = $identity.VersionName
            ApkSha256 = (Get-FileHash -LiteralPath $resolvedCandidate -Algorithm SHA256).Hash.ToUpperInvariant()
            SignerSha256 = $signers[0]
            Trust = "DEVELOPMENT_SIGNATURE_VALID_NOT_RELEASE_BOUND"
        }
    } $updaterPath $DevelopmentApkPath $MinimumPackage
}

function Confirm-ProvisioningArtifactUnchanged {
    param(
        [Parameter(Mandatory)]$ExpectedArtifact,
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][bool]$ReleaseBundleMode
    )

    # Check the immutable value captured by the initial, pre-ADB verification before loading any
    # bundle code again. A replacement APK therefore fails even if another extracted bundle file
    # was also changed after the operator's initial checksum validation.
    if (-not (Test-Path -LiteralPath $ExpectedArtifact.ApkPath -PathType Leaf)) {
        throw "[APK_CHANGED_AFTER_VERIFICATION] The verified APK disappeared before installation. No installation was attempted."
    }
    $currentSha256 = (Get-FileHash -LiteralPath $ExpectedArtifact.ApkPath -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($currentSha256 -cne [string]$ExpectedArtifact.ApkSha256) {
        throw "[APK_CHANGED_AFTER_VERIFICATION] The verified APK changed before installation. No installation was attempted."
    }

    $currentArtifact = if ($ReleaseBundleMode) {
        Get-VerifiedReleaseProvisioningArtifact -Root $Root `
            -RequestedApkPath $ExpectedArtifact.ApkPath
    } else {
        Get-VerifiedDevelopmentProvisioningArtifact -Root $Root `
            -DevelopmentApkPath $ExpectedArtifact.ApkPath
    }

    foreach ($property in @(
        "ApkPath", "ApplicationId", "VersionCode", "VersionName",
        "ApkSha256", "SignerSha256", "Trust")) {
        if ([string]$currentArtifact.$property -cne [string]$ExpectedArtifact.$property) {
            throw "[APK_BINDING_CHANGED_AFTER_VERIFICATION] The verified APK $property binding changed before installation. No installation was attempted."
        }
    }
    return $currentArtifact
}

if ($LibraryOnly) { return }

$releaseBundleMode = Test-ReleaseBundleLayout -Root $RepositoryRoot
$verifiedArtifact = $null
if ($releaseBundleMode) {
    if ($BuildApk) {
        throw "[RELEASE_BUNDLE_BUILD_REFUSED] A Release bundle cannot replace its manifest-bound APK with a local build. No ADB command or device change was attempted."
    }
    $verifiedArtifact = Get-VerifiedReleaseProvisioningArtifact -Root $RepositoryRoot `
        -RequestedApkPath $ApkPath
    $resolvedApkPath = $verifiedArtifact.ApkPath
    Write-Host ("Release artifact verified before ADB: {0}, versionCode {1}." -f `
        $verifiedArtifact.VersionName, $verifiedArtifact.VersionCode)
}

try {
    $adbPath = (Get-Command adb -ErrorAction Stop).Source
} catch {
    throw "ADB was not found. Install Android Platform Tools or add adb.exe to PATH, then double-click the launcher again."
}

$Host.UI.RawUI.WindowTitle = "Minimum One-Shot Provisioning"
if ($GuidedMode) {
    Write-Host "============================================================"
    Write-Host " Minimum radio - one-shot setup"
    Write-Host " No command-line parameters are required in guided mode."
    Write-Host "============================================================"
}
$AdbPort = Select-AdbServerPort
$serverArgs = @("-P", "$AdbPort")
$target = Select-InitialTarget
Set-Target -Record $target
Write-Host "Target: $($target.Manufacturer)/$($target.Model) via $script:targetLabel"
if (-not $target.Profile) {
    $apiLevel = Get-RecordProperty -Record $target -Name "ro.build.version.sdk"
    $buildId = Get-RecordProperty -Record $target -Name "ro.build.display.id"
    Write-Host "Unsupported hardware inventory: manufacturer='$($target.Manufacturer)' model='$($target.Model)' API='$apiLevel' build='$buildId'."
    throw "Unknown hardware is not provisioned automatically. Complete physical key/PTT commissioning and add a guarded model profile first."
}
if ($RequestNetworkLocationConsent -and $target.Profile -ne "T56") {
    throw "-RequestNetworkLocationConsent is supported only for T56."
}
if ($GuidedMode) {
    Show-GuidedSetupMenu -Profile $target.Profile
}

if (-not $releaseBundleMode) {
    if (-not $ApkPath) { $ApkPath = $DefaultApkPath }
    if ($BuildApk -or -not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
        Build-MinimumApk
    }
    $verifiedArtifact = Get-VerifiedDevelopmentProvisioningArtifact -Root $RepositoryRoot `
        -DevelopmentApkPath $ApkPath
    $resolvedApkPath = $verifiedArtifact.ApkPath
    Write-Warning ("DEVELOPMENT APK: package and signature are valid, but no Release manifest or reviewed Release signer trust is claimed ({0}, versionCode {1})." -f `
        $verifiedArtifact.VersionName, $verifiedArtifact.VersionCode)
}
if ($target.Profile -eq "RYKS") {
    # ELINK's PackageManager accepts third-party APKs only when this documented build-policy
    # property is 1. The property is absent on factory ym_258 images and resets on reboot.
    $installPolicy = Get-RecordProperty -Record $target -Name "ro.build.install"
    if ($installPolicy -ne "1") {
        Write-Host "Enabling the RYKS OEM APK-install policy for this boot..."
        Invoke-TargetAdb -Arguments @("shell", "setprop", "ro.build.install", "1") | Out-Null
        $installPolicy = Get-RecordProperty -Record $target -Name "ro.build.install"
        if ($installPolicy -ne "1") {
            throw "RYKS firmware did not enable ro.build.install=1; APK installation remains blocked."
        }
    }
}

function Install-MinimumApk {
    param(
        [Parameter(Mandatory)]$ExpectedArtifact,
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][bool]$ReleaseBundleMode
    )

    # Target selection, operator prompts and model checks may take an arbitrary amount of time.
    # Re-run the complete identity/hash/signer/trust verification here, immediately before adb
    # receives the path, so an APK changed after preflight is never installed.
    $finalArtifact = Confirm-ProvisioningArtifactUnchanged -ExpectedArtifact $ExpectedArtifact `
        -Root $Root -ReleaseBundleMode $ReleaseBundleMode
    $Path = $finalArtifact.ApkPath

    # Capture only the exit/result needed to classify installation failures. In particular, do not
    # invoke `pm clear` or uninstall here: a failed signature upgrade must preserve app data.
    $installArgs = $script:targetArgs + @("install", "-r", $Path)
    # Windows PowerShell 5.1 can turn native stderr merged with 2>&1 into a
    # terminating ErrorRecord when EAP is Stop. Temporarily keep native output
    # non-terminating, then stringify ErrorRecord entries before classification.
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = @(& $adbPath @installArgs 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $resultText = (($output | ForEach-Object {
        if ($_ -is [System.Management.Automation.ErrorRecord]) {
            $_.ToString()
        } else {
            [string]$_
        }
    }) -join "`n").Trim()
    if ($exitCode -ne 0 -or $resultText -notmatch '(?im)^Success\s*$') {
        if ($resultText -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
            throw "APK installation failed: INSTALL_FAILED_UPDATE_INCOMPATIBLE. The installed app and this APK use different signing keys. No app data was cleared; preserve the existing identity/configuration and obtain explicit approval before uninstalling the old build."
        }
        throw "APK installation failed (adb exit code $exitCode). No app data was cleared; correct the installation issue and retry."
    }
}
Write-Host "Installing Minimum APK without clearing app data..."
Install-MinimumApk -ExpectedArtifact $verifiedArtifact -Root $RepositoryRoot `
    -ReleaseBundleMode $releaseBundleMode
$installed = Invoke-TargetAdb -Arguments @("shell", "pm", "path", $MinimumPackage)
if (-not $installed) {
    throw "Minimum package verification failed after APK installation."
}

Ensure-LabWifiCredential -Profile $target.Profile
Invoke-ModelPreparation -Profile $target.Profile
$deviceId = Get-MinimumDeviceId
Write-Host "Minimum Device ID: $deviceId"

if (-not $NonInteractive) {
    $portalModel = $target.Profile.ToLowerInvariant()
    Write-Host "Register Device ID $deviceId as model '$portalModel' in the Minimum Portal. No device token is required."
    if (-not $SkipOpenPortal) {
        try {
            Start-Process $PortalUrl
        } catch {
            Write-Warning "Could not open the Portal automatically. Open $PortalUrl manually."
        }
    }
}

Invoke-TargetAdb -Arguments @("shell", "am", "start", "-n", $MinimumActivity) | Out-Null
Wait-MinimumReady -Phase "before reboot" -ExpectedDeviceId $deviceId `
    -TimeoutSeconds $ReadyTimeoutSeconds

if ($SkipReboot) {
    Write-Host "INCOMPLETE: reboot and same-ID Ready verification were skipped; final acceptance was not completed."
    exit 2
}

$originalSerial = $target.Serial
$manufacturer = $target.Manufacturer
$model = $target.Model
Write-Host "Rebooting for unattended startup acceptance..."
Invoke-TargetAdb -Arguments @("reboot") | Out-Null
$returningTarget = Wait-ForReturningTarget -Manufacturer $manufacturer -Model $model `
    -OriginalSerial $originalSerial -TimeoutSeconds $BootTimeoutSeconds
Set-Target -Record $returningTarget
Wait-AndroidBootCompleted -TimeoutSeconds $BootTimeoutSeconds
if ($target.Profile -eq "T56") {
    $cellularScript = Join-Path $PSScriptRoot "manage-cellular.ps1"
    $cellularArguments = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
        $cellularScript, "-AdbPort", "$AdbPort")
    if ($returningTarget.TransportId -gt 0) {
        $cellularArguments += @("-TransportId", "$($returningTarget.TransportId)")
    } else {
        $cellularArguments += @("-Serial", $returningTarget.Serial)
    }
    if ($DisableDataRoaming) { $cellularArguments += "-DisableDataRoaming" }
    $cellularArguments += "-VerifyOnly"
    Write-Host "Verifying T56 cellular-policy persistence after reboot without rewriting settings..."
    & powershell.exe @cellularArguments
    $cellularExit = $LASTEXITCODE
    if ($cellularExit -eq 2) {
        $script:CellularReadinessWarning = $true
        Write-Warning "Post-reboot cellular verification remains WARN; no persistence PASS is claimed."
    } elseif ($cellularExit -ne 0) {
        throw "Post-reboot T56 cellular verification failed with exit code $cellularExit."
    }
}
Wait-MinimumReady -Phase "after reboot" -ExpectedDeviceId $deviceId `
    -TimeoutSeconds $ReadyTimeoutSeconds

if ($script:CellularReadinessWarning) {
    Write-Warning "WARN: $($target.Profile) Device ID $deviceId is provisioned and Ready, but cellular readiness is not fully accepted."
    exit 2
}
Write-Host "PASS: $($target.Profile) Device ID $deviceId is provisioned and Ready."
