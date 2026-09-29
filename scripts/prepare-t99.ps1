<#[
.SYNOPSIS
    Prepares a supported Minimum radio device for radio-client use.

.DESCRIPTION
    This is the shared provisioning implementation used by the T99 and T56 wrappers. It reports
    the different serial identities,
    removes Zello for user 0, launches Minimum once so its app Device ID can be created, verifies
    that identity, provisions the lab Wi-Fi through a temporary helper that is removed immediately,
    installs a Minimum recovery shortcut into the OEM Launcher3 workspace, verifies that the system
    HOME no longer displays a chooser, and opens the one-icon-per-page radio dashboard. It
    intentionally does not attempt to rewrite the USB/ADB serial:
    the T99 exposes that value through a root-owned USB gadget node and this non-root device
    cannot safely change it.

    Use -TransportId when several identical devices expose the same ADB serial.
#>

[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$Serial = "12344321",
    [int]$TransportId = 0,
    [int]$AdbPort = 5041,
    [switch]$Force,
    [switch]$SkipZello,
    [switch]$SkipMinimumHome,
    [switch]$SkipLabWifi,
    [switch]$SkipLocation,
    [switch]$DisableDataRoaming,
    [switch]$RequestNetworkLocationConsent,
    [switch]$RefreshLabWifi,
    [switch]$ReportOnly,
    [Alias("DeviceId")]
    [string]$DeviceProfile = "",
    [string]$RadioConfigPath = "",
    [string]$LabWifiSsid = "..@EmergencyTU",
    [System.Management.Automation.PSCredential]$LabWifiCredential,
    [string]$LabWifiCredentialPath = "",
    [string]$TargetName = "T99",
    [string]$ExpectedManufacturer = "Youdotech",
    [string]$ExpectedModel = "QM011",
    [string]$LocationProviders = ""
)

$ErrorActionPreference = "Stop"
$script:CellularReadinessWarning = $false
$PackageName = "com.loudtalks"
$MinimumPackage = "se.lublin.mumla"
$MinimumActivity = "se.lublin.mumla/.radio.RadioShellActivity"
$MinimumHomeActivity = "se.lublin.mumla/.radio.MinimumHomeActivity"
$MinimumHomeComponent = "se.lublin.mumla/se.lublin.mumla.radio.MinimumHomeActivity"
$MinimumRadioComponent = "se.lublin.mumla/se.lublin.mumla.radio.RadioShellActivity"
$LegacyT56HardwareKeyService = "se.lublin.mumla/se.lublin.mumla.radio.RadioHardwareKeyAccessibilityService"
$ShortcutProvisionReceiver = "se.lublin.mumla/.radio.RadioProvisionReceiver"
$ShortcutProvisionAction = "se.lublin.mumla.action.PROVISION_LAUNCHER_SHORTCUT"
$DeviceProfileProvisionAction = "se.lublin.mumla.action.PROVISION_DEVICE_PROFILE"
$IdentityReportAction = "se.lublin.mumla.action.PROVISION_REPORT_IDENTITY"
$RadioConfigProvisionAction = "se.lublin.mumla.action.PROVISION_RADIO_CONFIG"
$WifiHelperPackage = "dev.minimum.wifiprovisioner"
$WifiHelperReceiver = "dev.minimum.wifiprovisioner/.WifiProvisionReceiver"
$WifiHelperImportAction = "dev.minimum.wifiprovisioner.action.IMPORT_REQUEST"
$WifiHelperStatusAction = "dev.minimum.wifiprovisioner.action.STATUS"
$WifiHelperRequestPathExtra = "requestPath"
$WifiHelperOperationIdExtra = "operationId"
if (-not $LabWifiCredentialPath) {
    $LabWifiCredentialPath = Join-Path $PSScriptRoot (".secrets\{0}-lab-wifi.credential.xml" -f $TargetName.ToLowerInvariant())
}

$adbCommand = Get-Command adb -ErrorAction Stop
$adbPath = $adbCommand.Source
$serverArgs = @("-P", "$AdbPort")
$deviceLines = & $adbPath @serverArgs devices -l
if ($LASTEXITCODE -ne 0) {
    throw "Could not query the ADB server on port $AdbPort"
}

if ($TransportId -gt 0) {
    $deviceLine = $deviceLines | Where-Object {
        $_ -match "\btransport_id:$TransportId\b\s*$" -or $_ -match "\btransport_id:$TransportId\b\s"
    }
    if (-not $deviceLine) {
        throw "No authorized device with transport id $TransportId is connected on port $AdbPort"
    }
    $targetArgs = $serverArgs + @("-t", "$TransportId")
    $targetLabel = "transport id $TransportId"
} else {
    $serialPattern = "^$([regex]::Escape($Serial))\s+device\s+"
    $matchingLines = @($deviceLines | Where-Object { $_ -match $serialPattern })
    if ($matchingLines.Count -eq 0) {
        throw "Authorized ADB device '$Serial' is not connected on port $AdbPort"
    }
    if ($matchingLines.Count -gt 1) {
        throw "ADB serial '$Serial' is duplicated. Re-run with -TransportId from 'adb devices -l'."
    }
    $targetArgs = $serverArgs + @("-s", $Serial)
    $targetLabel = "ADB serial $Serial"
}

function Invoke-TargetAdb {
    param([Parameter(Mandatory)][string[]]$Arguments)
    $commandArgs = $targetArgs + $Arguments
    & $adbPath @commandArgs
    if ($LASTEXITCODE -ne 0) {
        throw "ADB command failed for $targetLabel with exit code $LASTEXITCODE"
    }
}

function Get-TargetProperty {
    param([Parameter(Mandatory)][string]$Name)
    $value = Invoke-TargetAdb -Arguments @("shell", "getprop", $Name)
    return ($value -join "").Trim()
}

function Get-TargetFile {
    param([Parameter(Mandatory)][string]$Path)
    $value = Invoke-TargetAdb -Arguments @("shell", "cat", $Path)
    return ($value -join "`n")
}

function Get-MinimumDeviceId {
    $result = Invoke-TargetAdb -Arguments @(
        "shell", "am", "broadcast",
        "-a", $IdentityReportAction,
        "-n", $ShortcutProvisionReceiver
    )
    $match = [regex]::Match(
            ($result -join "`n"),
            'data="?([A-Z0-9]{6})"?')
    if ($match.Success) {
        return $match.Groups[1].Value
    }
    return ""
}

function Test-MinimumHomeFocused {
    $windowState = Invoke-TargetAdb -Arguments @("shell", "dumpsys", "window", "windows")
    return ($windowState -join "`n").Contains($MinimumHomeComponent)
}

function Test-HomeChooserFocused {
    $windowState = Invoke-TargetAdb -Arguments @("shell", "dumpsys", "window", "windows")
    return ($windowState -join "`n").Contains("android/com.android.internal.app.ResolverActivity")
}

function Test-MinimumRadioFocused {
    $windowState = Invoke-TargetAdb -Arguments @("shell", "dumpsys", "window", "windows")
    return ($windowState -join "`n").Contains($MinimumRadioComponent)
}

function Test-LabWifiConnected {
    param([Parameter(Mandatory)][string]$Ssid)
    $connectivity = Invoke-TargetAdb -Arguments @("shell", "dumpsys", "connectivity")
    $escapedSsid = [regex]::Escape($Ssid)
    return (($connectivity -join "`n") -match
            "(?s)type:\s*WIFI.*?state:\s*CONNECTED/CONNECTED.*?extra:\s*`"$escapedSsid`"")
}

function Get-LocationProvisioningState {
    $mode = ((Invoke-TargetAdb -Arguments @(
        "shell", "settings", "get", "secure", "location_mode"
    )) -join "").Trim()
    $providers = ((Invoke-TargetAdb -Arguments @(
        "shell", "settings", "get", "secure", "location_providers_allowed"
    )) -join "").Trim()
    return [pscustomobject]@{
        Mode = $mode
        Providers = $providers
        GpsEnabled = $providers.Split(',') -contains "gps"
        NetworkEnabled = $providers.Split(',') -contains "network"
    }
}

function Get-GoogleLocationServicesConsentState {
    $result = Invoke-TargetAdb -Arguments @(
        "shell", "content", "query", "--uri",
        "content://com.google.settings/partner/use_location_for_services"
    )
    $match = [regex]::Match(
            ($result -join "`n"),
            'name=use_location_for_services,\s*value=(-?\d+)')
    if (-not $match.Success) {
        return -1
    }
    return [int]$match.Groups[1].Value
}

function Test-GoogleNetworkLocationConsentVisible {
    $windowState = Invoke-TargetAdb -Arguments @("shell", "dumpsys", "window", "windows")
    return ($windowState -join "`n").Contains(
            "com.google.android.location.network.ConfirmAlertActivity")
}

function Set-ManagedLocationState {
    param(
        [Parameter(Mandatory)][string]$Providers,
        [Parameter(Mandatory)][ValidateSet("1", "3")][string]$Mode
    )

    Invoke-TargetAdb -Arguments @(
        "shell", "settings", "put", "secure", "location_providers_allowed", $Providers
    ) | Out-Null
    Invoke-TargetAdb -Arguments @(
        "shell", "settings", "put", "secure", "location_mode", $Mode
    ) | Out-Null
    Invoke-TargetAdb -Arguments @(
        "shell", "am", "broadcast", "-a", "android.location.PROVIDERS_CHANGED"
    ) | Out-Null
}

function Enable-ManagedLocation {
    $desiredProviders = if ($LocationProviders) {
        $LocationProviders
    } elseif ($TargetName -eq "T56") {
        "gps"
    } else {
        "gps,network"
    }
    $desiredMode = if ($desiredProviders.Split(',') -contains "network") { "3" } else { "1" }
    Set-ManagedLocationState -Providers $desiredProviders -Mode $desiredMode
    Start-Sleep -Milliseconds 750

    $state = Get-LocationProvisioningState
    if ($state.Mode -ne $desiredMode -or -not $state.GpsEnabled) {
        throw "Managed Location did not persist (mode=$($state.Mode), providers=$($state.Providers))."
    }
    if ($desiredMode -eq "3") {
        Write-Host "Android Location enabled in high-accuracy GPS/network mode."
    } else {
        Write-Host "Android Location enabled in device-only GPS mode."
    }
}

function Request-T56NetworkLocationConsent {
    if ($TargetName -ne "T56") {
        throw "-RequestNetworkLocationConsent is supported only by the T56 provisioning flow."
    }

    $accepted = $false
    try {
        Set-ManagedLocationState -Providers "gps" -Mode "1"
        Invoke-TargetAdb -Arguments @("shell", "am", "force-stop", $MinimumPackage) | Out-Null
        Invoke-TargetAdb -Arguments @(
            "shell", "am", "start",
            "-a", "com.google.android.gsf.action.SET_USE_LOCATION_FOR_SERVICES",
            "--ez", "disable", "true"
        ) | Out-Null

        $resetDeadline = (Get-Date).AddSeconds(10)
        while ((Get-Date) -lt $resetDeadline -and
                (Get-GoogleLocationServicesConsentState) -ne 0) {
            Start-Sleep -Milliseconds 250
        }
        if ((Get-GoogleLocationServicesConsentState) -ne 0) {
            throw "Google location-services consent could not be reset through its system activity."
        }

        Invoke-TargetAdb -Arguments @(
            "shell", "am", "start",
            "-a", "com.google.android.gsf.GOOGLE_LOCATION_SETTINGS"
        ) | Out-Null

        Write-Host "T56 Google location-services consent opened."
        Write-Host "Accept the consent dialog on the device within 120 seconds."
        Write-Host "Minimum will remain stopped until the consent decision is complete."

        $consentDeadline = (Get-Date).AddSeconds(120)
        while ((Get-Date) -lt $consentDeadline -and
                (Get-GoogleLocationServicesConsentState) -ne 1) {
            Start-Sleep -Seconds 1
        }
        if ((Get-GoogleLocationServicesConsentState) -ne 1) {
            throw "T56 Google location-services consent was not accepted within 120 seconds."
        }

        Invoke-TargetAdb -Arguments @(
            "shell", "am", "broadcast",
            "-a", "com.android.settings.location.MODE_CHANGING",
            "--ei", "CURRENT_MODE", "1",
            "--ei", "NEW_MODE", "3"
        ) | Out-Null
        Set-ManagedLocationState -Providers "gps,network" -Mode "3"

        $networkDeadline = (Get-Date).AddSeconds(120)
        $networkConsentPrompted = $false
        $stableAcceptedSamples = 0
        while ((Get-Date) -lt $networkDeadline) {
            Start-Sleep -Seconds 1
            if (Test-GoogleNetworkLocationConsentVisible) {
                if (-not $networkConsentPrompted) {
                    Write-Host "Google network-location consent is also waiting on the T56 display."
                    $networkConsentPrompted = $true
                }
                $stableAcceptedSamples = 0
                continue
            }
            $state = Get-LocationProvisioningState
            if ($state.Mode -eq "3" -and $state.GpsEnabled -and $state.NetworkEnabled) {
                $stableAcceptedSamples++
                if ($stableAcceptedSamples -ge 3) {
                    $accepted = $true
                    Write-Host "Android Location enabled in high-accuracy GPS/network mode after operator consent."
                    return
                }
            } else {
                $stableAcceptedSamples = 0
            }
        }
        throw "T56 high-accuracy Location did not stabilize after operator consent."
    } finally {
        if (-not $accepted) {
            Set-ManagedLocationState -Providers "gps" -Mode "1"
            Write-Warning "T56 Location restored to device-only GPS mode."
        }
    }
}

function Get-LabWifiCredential {
    if ($LabWifiCredential) {
        return $LabWifiCredential
    }
    if (Test-Path -LiteralPath $LabWifiCredentialPath) {
        $stored = Import-Clixml -LiteralPath $LabWifiCredentialPath
        if ($stored -isnot [System.Management.Automation.PSCredential]) {
            throw "Lab Wi-Fi credential file is not a Windows PSCredential: $LabWifiCredentialPath"
        }
        return $stored
    }
    throw "Lab Wi-Fi is not connected and no credential was supplied. Pass -LabWifiCredential or create the ignored DPAPI credential at $LabWifiCredentialPath."
}

function Convert-WifiHelperStatusMarker {
    param(
        [Parameter(Mandatory)][AllowEmptyCollection()][AllowEmptyString()][string[]]$Output,
        [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{32}$')][string]$ExpectedOperationId
    )

    $escapedOperationId = [regex]::Escape($ExpectedOperationId)
    $markerPattern = '(?<state>IMPORTED|SUCCESS):' + $escapedOperationId +
            '|ERROR:' + $escapedOperationId + ':(?<error>[a-z0-9-]{1,64})'
    foreach ($line in $Output) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        $text = ([string]$line).Trim()
        $match = [regex]::Match(
                $text,
                '^Broadcast completed:\s+result=-1,\s+data="?(?<marker>(?:' +
                    $markerPattern + '))"?$')
        if (-not $match.Success) {
            continue
        }
        $marker = $match.Groups['marker'].Value
        if ($marker.StartsWith('ERROR:', [System.StringComparison]::Ordinal)) {
            return [pscustomobject]@{ State = 'ERROR'; Error = $match.Groups['error'].Value }
        }
        $state = $marker.Substring(0, $marker.IndexOf(':'))
        return [pscustomobject]@{ State = $state; Error = $null }
    }
    return $null
}

function Convert-WifiHelperBroadcastOutput {
    param([Parameter(Mandatory)][AllowEmptyCollection()][AllowEmptyString()][object[]]$Output)

    return @($Output | ForEach-Object {
        if ($_ -is [System.Management.Automation.ErrorRecord]) {
            $_.ToString()
        } else {
            [string]$_
        }
    })
}

function Invoke-WifiHelperBroadcast {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $savedErrorActionPreference = $ErrorActionPreference
    try {
        # Native adb diagnostics must be captured alongside stdout so a valid ordered result can
        # still be found without allowing a permission/native error to masquerade as one.
        $ErrorActionPreference = "Continue"
        $combinedOutput = @(& $adbPath @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedErrorActionPreference
    }
    [pscustomobject]@{
        ExitCode = $exitCode
        Output = @(Convert-WifiHelperBroadcastOutput -Output $combinedOutput)
    }
}

function Assert-WifiRemoteRequestAbsent {
    param([Parameter(Mandatory)][string]$RemotePath)

    # The path is generated locally from a random operation ID and is never credential data.
    # Keep the device-side probe output to one of two exact, non-secret words.
    $probeCommand = "if [ -e '$RemotePath' ]; then echo PRESENT; else echo ABSENT; fi"
    $probeOutput = @(& $adbPath @($targetArgs + @(
        "shell", $probeCommand
    )) 2>$null)
    if ($LASTEXITCODE -ne 0) {
        throw "remote request existence probe failed"
    }
    $probeText = (($probeOutput | ForEach-Object { [string]$_ }) -join "`n").Trim()
    if ($probeText -ceq "ABSENT") {
        return
    }
    if ($probeText -ceq "PRESENT") {
        throw "remote request remains present"
    }
    throw "remote request existence probe returned unexpected output"
}

function Assert-WifiHelperUninstalled {
    $uninstallOutput = @(& $adbPath @($targetArgs + @(
        "uninstall", $WifiHelperPackage
    )) 2>$null)
    if ($LASTEXITCODE -ne 0 -or
            ($uninstallOutput -join "`n") -notmatch '(?im)^\s*Success\s*$') {
        throw "helper uninstall was not acknowledged"
    }

    # pm list packages is expected to exit successfully even when no package matches. Only the
    # exact package line is meaningful; diagnostics are discarded and never echoed.
    $packageListOutput = @(& $adbPath @($targetArgs + @(
        "shell", "pm", "list", "packages", $WifiHelperPackage
    )) 2>$null)
    if ($LASTEXITCODE -ne 0) {
        throw "helper package query failed"
    }
    $packageLines = @($packageListOutput | ForEach-Object { ([string]$_).Trim() })
    if ($packageLines -contains "package:$WifiHelperPackage") {
        throw "helper package remains installed"
    }
}

function Invoke-LabWifiProvisioning {
    param(
        [Parameter(Mandatory)][string]$Ssid,
        [Parameter(Mandatory)][System.Management.Automation.PSCredential]$Credential
    )

    $bundledHelperApk = Join-Path $PSScriptRoot "..\assets\t99-wifi-provisioner.apk"
    if (Test-Path -LiteralPath $bundledHelperApk -PathType Leaf) {
        $helperApk = $bundledHelperApk
        Write-Host "Using the Wi-Fi provisioner included in the Release bundle."
    } else {
        $helperRoot = Join-Path $PSScriptRoot "..\tools\t99-wifi-provisioner"
        $gradleWrapper = Join-Path $PSScriptRoot "..\gradlew.bat"
        $helperApk = Join-Path $helperRoot "app\build\outputs\apk\debug\app-debug.apk"
        if (-not (Test-Path -LiteralPath $gradleWrapper)) {
            throw "Neither the bundled Wi-Fi provisioner nor the Gradle wrapper is available."
        }

        & $gradleWrapper -p $helperRoot :app:assembleDebug
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $helperApk)) {
            throw "Temporary Wi-Fi provisioner build failed."
        }
    }

    $temporaryDirectory = Join-Path ([IO.Path]::GetTempPath()) (
            "minimum-wifi-" + [guid]::NewGuid().ToString("N"))
    $requestPath = Join-Path $temporaryDirectory "request.json"
    $operationId = [guid]::NewGuid().ToString("N")
    $remoteRequest = "/data/local/tmp/minimum-wifi-$operationId.json"
    $helperInstalled = $false
    $primaryError = $null
    $cleanupError = $null
    New-Item -ItemType Directory -Path $temporaryDirectory | Out-Null
    try {
        $plainPassword = $Credential.GetNetworkCredential().Password
        $requestJson = [ordered]@{ ssid = $Ssid; psk = $plainPassword } |
            ConvertTo-Json -Compress
        [IO.File]::WriteAllText(
                $requestPath,
                $requestJson,
                (New-Object Text.UTF8Encoding($false)))
        $plainPassword = $null
        $requestJson = $null

        Invoke-TargetAdb -Arguments @("install", "-r", $helperApk) | Out-Null
        $helperInstalled = $true
        Invoke-TargetAdb -Arguments @("push", $requestPath, $remoteRequest) | Out-Null
        $importArgs = $targetArgs + @(
            "shell", "am", "broadcast", "-n", $WifiHelperReceiver,
            "-a", $WifiHelperImportAction,
            "--es", $WifiHelperRequestPathExtra, $remoteRequest,
            "--es", $WifiHelperOperationIdExtra, $operationId
        )
        $importCall = Invoke-WifiHelperBroadcast -Arguments $importArgs
        if ($importCall.ExitCode -ne 0) {
            throw "Temporary Wi-Fi provisioner could not accept the request."
        }
        # Android 5.1 does not reliably preserve ordered-broadcast result data. Import output is
        # therefore discarded. A nonce-bound STATUS marker from receiver-private, fsynced state
        # is the only proof that the request was validated and copied.
        $importCall = $null
        $importProof = $null
        $importDeadline = (Get-Date).AddSeconds(5)
        while ((Get-Date) -lt $importDeadline -and $null -eq $importProof) {
            $statusArgs = $targetArgs + @(
                "shell", "am", "broadcast", "-n", $WifiHelperReceiver,
                "-a", $WifiHelperStatusAction,
                "--es", $WifiHelperOperationIdExtra, $operationId
            )
            $statusCall = Invoke-WifiHelperBroadcast -Arguments $statusArgs
            if ($statusCall.ExitCode -eq 0) {
                $importProof = Convert-WifiHelperStatusMarker `
                        -Output $statusCall.Output -ExpectedOperationId $operationId
                if ($null -ne $importProof -and $importProof.State -eq 'ERROR') {
                    throw "Lab Wi-Fi provisioning failed: $($importProof.Error)"
                }
            }
            if ($null -eq $importProof) {
                Start-Sleep -Milliseconds 100
            }
        }
        if ($null -eq $importProof) {
            throw "Temporary Wi-Fi provisioner did not prove a private request import."
        }

        # Only the shell UID can remove its /data/local/tmp entry. Removal occurs after the
        # receiver-private import proof and is immediately verified without exposing content.
        & $adbPath @($targetArgs + @("shell", "rm", "-f", $remoteRequest)) 1>$null 2>$null
        if ($LASTEXITCODE -ne 0) {
            throw "remote request deletion failed"
        }
        Assert-WifiRemoteRequestAbsent -RemotePath $remoteRequest

        $result = if ($importProof.State -eq 'SUCCESS') { $importProof } else { $null }
        $deadline = (Get-Date).AddSeconds(25)
        while ((Get-Date) -lt $deadline -and $null -eq $result) {
            Start-Sleep -Milliseconds 500
            $statusArgs = $targetArgs + @(
                "shell", "am", "broadcast", "-n", $WifiHelperReceiver,
                "-a", $WifiHelperStatusAction,
                "--es", $WifiHelperOperationIdExtra, $operationId
            )
            $statusCall = Invoke-WifiHelperBroadcast -Arguments $statusArgs
            if ($statusCall.ExitCode -eq 0) {
                $status = Convert-WifiHelperStatusMarker `
                        -Output $statusCall.Output -ExpectedOperationId $operationId
                if ($null -ne $status -and $status.State -eq 'ERROR') {
                    throw "Lab Wi-Fi provisioning failed: $($status.Error)"
                }
                if ($null -ne $status -and $status.State -eq 'SUCCESS') {
                    $result = $status
                }
            }
        }
        if ($null -eq $result) {
            throw "Temporary Wi-Fi provisioner did not return a result."
        }
        Write-Host "Lab Wi-Fi profile saved for SSID '$Ssid'; credential value was not displayed."
    } catch {
        $primaryError = $_
    } finally {
        $remoteCleanupError = $null
        try {
            & $adbPath @($targetArgs + @("shell", "rm", "-f", $remoteRequest)) 1>$null 2>$null
            if ($LASTEXITCODE -ne 0) {
                throw "remote request deletion failed"
            }
        } catch {
            $remoteCleanupError = "remote request deletion failed"
        }
        try {
            Assert-WifiRemoteRequestAbsent -RemotePath $remoteRequest
        } catch {
            if ($remoteCleanupError) {
                $remoteCleanupError = "$remoteCleanupError; remote request absence probe failed"
            } else {
                $remoteCleanupError = "remote request absence probe failed"
            }
        }
        if ($remoteCleanupError) {
            $cleanupError = $remoteCleanupError
        }

        if ($helperInstalled) {
            try {
                Assert-WifiHelperUninstalled
            } catch {
                if ($cleanupError) {
                    $cleanupError = "$cleanupError; helper uninstall verification failed"
                } else {
                    $cleanupError = "helper uninstall verification failed"
                }
            }
        }

        try {
            if (Test-Path -LiteralPath $temporaryDirectory) {
                Remove-Item -LiteralPath $temporaryDirectory -Recurse -Force
            }
            if (Test-Path -LiteralPath $temporaryDirectory) {
                throw "local request cleanup failed"
            }
        } catch {
            if ($cleanupError) {
                $cleanupError = "$cleanupError; local request cleanup failed"
            } else {
                $cleanupError = "local request cleanup failed"
            }
        }
    }

    if ($primaryError) {
        if ($cleanupError) {
            throw "$($primaryError.Exception.Message) Cleanup also failed: $cleanupError."
        }
        throw $primaryError.Exception.Message
    }
    if ($cleanupError) {
        throw "Temporary Wi-Fi provisioner cleanup failed: $cleanupError."
    }

    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline -and -not (Test-LabWifiConnected -Ssid $Ssid)) {
        Start-Sleep -Seconds 1
    }
    if (Test-LabWifiConnected -Ssid $Ssid) {
        Write-Host "Lab Wi-Fi connected and available for automatic reconnection: '$Ssid'."
    } else {
        Write-Warning "Lab Wi-Fi profile is saved but '$Ssid' was not reachable within 30 seconds."
    }
}

$manufacturer = Get-TargetProperty -Name ro.product.manufacturer
$model = Get-TargetProperty -Name ro.product.model
Write-Host "Target: $TargetName / $targetLabel"
Write-Host "Manufacturer/model: $manufacturer / $model"
if ($ExpectedManufacturer -and $manufacturer.Trim() -ine $ExpectedManufacturer) {
    throw "Target manufacturer '$manufacturer' does not match expected '$ExpectedManufacturer'."
}
if ($ExpectedModel -and $model.Trim() -ine $ExpectedModel) {
    throw "Target model '$model' does not match expected '$ExpectedModel'."
}
if ($SkipLocation -and $RequestNetworkLocationConsent) {
    throw "-SkipLocation and -RequestNetworkLocationConsent cannot be used together."
}
if ($RequestNetworkLocationConsent -and $TargetName -ne "T56") {
    throw "-RequestNetworkLocationConsent is supported only by the T56 provisioning flow."
}
if ($DisableDataRoaming -and $TargetName -ne "T56") {
    throw "-DisableDataRoaming is supported only by the T56 managed-cellular flow."
}

if ($TargetName -eq "T56") {
    $cellularScript = Join-Path $PSScriptRoot "manage-cellular.ps1"
    $cellularArgs = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $cellularScript,
        "-AdbPort", "$AdbPort")
    if ($TransportId -gt 0) {
        $cellularArgs += @("-TransportId", "$TransportId")
    } else {
        $cellularArgs += @("-Serial", $Serial)
    }
    if ($DisableDataRoaming) { $cellularArgs += "-DisableDataRoaming" }
    if ($ReportOnly -or $WhatIfPreference) { $cellularArgs += "-VerifyOnly" }
    Write-Host "Applying the guarded T56 cellular-readiness policy before final connectivity checks..."
    & powershell.exe @cellularArgs
    $cellularExit = $LASTEXITCODE
    if ($cellularExit -eq 2) {
        $script:CellularReadinessWarning = $true
        Write-Warning "T56 cellular policy verified with a readiness warning; provisioning continues with documented fallback."
    } elseif ($cellularExit -ne 0) {
        throw "T56 cellular readiness failed with exit code $cellularExit."
    }
}
$adbSerial = (& $adbPath @targetArgs get-serialno) -join ""
$systemSerial = Get-TargetProperty -Name ro.serialno
$bootSerial = Get-TargetProperty -Name ro.boot.serialno
$usbSerial = (Get-TargetFile -Path /sys/class/android_usb/android0/iSerial).Trim()
Write-Host "ADB serial: $adbSerial"
Write-Host "Android ro.serialno: $systemSerial"
Write-Host "Android ro.boot.serialno: $bootSerial"
Write-Host "USB gadget iSerial: $usbSerial"
Write-Host "Serial rewrite: NOT ATTEMPTED (requires root/firmware-level provisioning)"

$packagePath = & $adbPath @($targetArgs + @("shell", "pm", "path", $PackageName)) 2>$null
if ($LASTEXITCODE -ne 0) {
    # `pm path` returns a non-zero status when an already-prepared radio no longer has Zello.
    # Treat that expected absence as an empty result so preparation remains idempotent.
    $packagePath = @()
}
if (-not $SkipZello -and $packagePath) {
    Write-Host "Zello system path: $($packagePath -join ' ')"
}

$minimumInstalled = Invoke-TargetAdb -Arguments @("shell", "pm", "list", "packages", $MinimumPackage)
if (-not $minimumInstalled) {
    throw "Minimum ($MinimumPackage) is not installed; install the APK before provisioning."
}

if ($TargetName -eq "T56") {
    $enabledServices = ((Invoke-TargetAdb -Arguments @(
        "shell", "settings", "get", "secure", "enabled_accessibility_services"
    )) -join "").Trim()
    $serviceList = @($enabledServices -split ":" | Where-Object {
        $_ -and $_ -ne "null"
    })
    $legacyHardwareKeyServiceEnabled = $serviceList -contains $LegacyT56HardwareKeyService
    if ($ReportOnly) {
        Write-Host "Legacy T56 accessibility key service present=$legacyHardwareKeyServiceEnabled (report-only)."
    } elseif ($legacyHardwareKeyServiceEnabled -and
            $PSCmdlet.ShouldProcess($targetLabel, "remove obsolete Minimum accessibility key service")) {
        if (-not $WhatIfPreference) {
            $remainingServices = @($serviceList | Where-Object {
                $_ -ne $LegacyT56HardwareKeyService
            })
            if ($remainingServices.Count -gt 0) {
                Invoke-TargetAdb -Arguments @(
                    "shell", "settings", "put", "secure", "enabled_accessibility_services",
                    ($remainingServices -join ":")
                ) | Out-Null
            } else {
                Invoke-TargetAdb -Arguments @(
                    "shell", "settings", "delete", "secure", "enabled_accessibility_services"
                ) | Out-Null
                Invoke-TargetAdb -Arguments @(
                    "shell", "settings", "put", "secure", "accessibility_enabled", "0"
                ) | Out-Null
            }
            Write-Host "Obsolete Minimum accessibility key service removed; T56 uses OEM PTT broadcasts."
        }
    }
}

$locationState = Get-LocationProvisioningState
if ($ReportOnly) {
    Write-Host "Location mode=$($locationState.Mode) providers=$($locationState.Providers) (report-only)."
} elseif ($SkipLocation) {
    Write-Host "Location provisioning skipped by request."
} elseif ($PSCmdlet.ShouldProcess($targetLabel, "enable managed Android Location")) {
    if (-not $WhatIfPreference) {
        if ($RequestNetworkLocationConsent) {
            Request-T56NetworkLocationConsent
        } else {
            Enable-ManagedLocation
        }
    }
}

$labWifiConnected = Test-LabWifiConnected -Ssid $LabWifiSsid
if ($labWifiConnected -and -not $RefreshLabWifi) {
    Write-Host "Lab Wi-Fi is connected: '$LabWifiSsid'."
} elseif ($ReportOnly) {
    Write-Host "Lab Wi-Fi connected=$labWifiConnected refresh=$RefreshLabWifi (report-only): '$LabWifiSsid'."
} elseif ($SkipLabWifi) {
    Write-Host "Lab Wi-Fi provisioning skipped by request."
} elseif ($PSCmdlet.ShouldProcess("$targetLabel / $LabWifiSsid", "save and enable lab Wi-Fi")) {
    if (-not $WhatIfPreference) {
        $credential = Get-LabWifiCredential
        Invoke-LabWifiProvisioning -Ssid $LabWifiSsid -Credential $credential
    }
}

if (-not $ReportOnly -and -not $WhatIfPreference) {
    # Managed radio provisioning should not leave a tiny-screen Android permission dialog for the
    # operator. This only grants a permission already declared by Minimum.
    $apiLevelOutput = @(Invoke-TargetAdb -Arguments @(
        "shell", "getprop", "ro.build.version.sdk"
    ))
    $apiLevelMatch = [regex]::Match(($apiLevelOutput -join " "), '\b\d+\b')
    if (-not $apiLevelMatch.Success) {
        throw "Could not determine the Android API level for microphone provisioning."
    }
    $androidApiLevel = [int]$apiLevelMatch.Value
    if ($androidApiLevel -ge 23) {
        Invoke-TargetAdb -Arguments @(
            "shell", "pm", "grant", $MinimumPackage, "android.permission.RECORD_AUDIO"
        ) | Out-Null
        Write-Host "Minimum microphone permission granted for managed PTT use."
    } else {
        Write-Host "Minimum microphone permission is install-time on Android API $androidApiLevel."
    }
}

if (-not $ReportOnly -and -not $SkipZello -and $packagePath) {
    if (-not $Force -and -not $WhatIfPreference) {
        $answer = Read-Host "Type PREPARE to remove Zello for user 0 and initialize Minimum"
        if ($answer -cne "PREPARE") {
            Write-Host "Cancelled. No device changes were made."
            exit 0
        }
    }
    if ($PSCmdlet.ShouldProcess("$targetLabel / $PackageName", "pm uninstall --user 0")) {
        Invoke-TargetAdb -Arguments @("shell", "pm", "uninstall", "--user", "0", $PackageName)
        $visiblePackage = Invoke-TargetAdb -Arguments @("shell", "pm", "list", "packages", $PackageName)
        if ($visiblePackage) {
            throw "Zello still appears in the user package list after uninstall"
        }
        Write-Host "Zello removed from user 0. The read-only system APK may remain."
    }
} elseif ($SkipZello) {
    Write-Host "Zello step skipped by request."
} elseif (-not $packagePath) {
    Write-Host "Zello is already absent from this device user."
}

if (-not $ReportOnly -and -not $WhatIfPreference) {
    Invoke-TargetAdb -Arguments @("shell", "am", "start", "-n", $MinimumActivity) | Out-Null
    Start-Sleep -Seconds 2
}

$deviceId = Get-MinimumDeviceId
if ($DeviceProfile) {
    if (($DeviceProfile -cnotmatch '^[A-Z0-9]{6}$') -or
            ($DeviceProfile -notmatch '[A-Z]') -or
            ($DeviceProfile -notmatch '\d')) {
        throw "DeviceProfile must be six uppercase A-Z/0-9 characters with a letter and digit."
    }
    if (-not $deviceId) {
        throw "Minimum Device ID is not initialized; launch Minimum once before assigning a profile."
    }
    if (($deviceId -cne $DeviceProfile) -and
            (-not $ReportOnly) -and
            $PSCmdlet.ShouldProcess(
                "$targetLabel / Minimum identity",
                "assign config profile/deviceId $DeviceProfile")) {
        Invoke-TargetAdb -Arguments @(
            "shell", "am", "force-stop", $MinimumPackage
        ) | Out-Null
        Invoke-TargetAdb -Arguments @(
            "shell", "am", "broadcast",
            "-a", $DeviceProfileProvisionAction,
            "-n", $ShortcutProvisionReceiver,
            "--es", "deviceProfile", $DeviceProfile
        ) | Out-Null
        $deviceId = Get-MinimumDeviceId
        if ($deviceId -cne $DeviceProfile) {
            throw "Minimum config profile/deviceId assignment did not persist."
        }
        Write-Host "Minimum config profile assigned: $DeviceProfile"
    } elseif ($ReportOnly -and $deviceId -cne $DeviceProfile) {
        Write-Host "Requested config profile: $DeviceProfile (report-only; not applied)"
    }
}
if ($deviceId) {
    if ($deviceId -notmatch '[A-Z]' -or $deviceId -notmatch '\d') {
        throw "Minimum Device ID '$deviceId' does not contain both a letter and a digit"
    }
    Write-Host "Minimum Device ID: $deviceId"
} else {
    Write-Warning "Minimum Device ID is not initialized yet; launch the app once with the normal user."
}

if (-not $ReportOnly -and $RadioConfigPath -and -not $WhatIfPreference) {
    $resolvedConfigPath = (Resolve-Path -LiteralPath $RadioConfigPath).Path
    $configFile = Get-Item -LiteralPath $resolvedConfigPath
    if ($configFile.Length -gt 262144) {
        throw "Radio config exceeds the 262144-byte application limit."
    }
    $configObject = Get-Content -LiteralPath $resolvedConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($configObject.schemaVersion -ne 3 -or $configObject.configVersion -lt 1) {
        throw "Radio config has an unsupported schema/config version."
    }
    if ((-not $configObject.radio.defaultChannel) -or
            (-not $configObject.connections) -or
            (-not $configObject.channels) -or
            ($configObject.channels.Count -lt 1)) {
        throw "Radio config is missing radio/defaultChannel/connections/channels."
    }
    $defaultChannel = $configObject.channels |
            Where-Object { $_.id -ceq $configObject.radio.defaultChannel } |
            Select-Object -First 1
    if (-not $defaultChannel) {
        throw "Radio config defaultChannel does not identify a configured channel."
    }
    foreach ($channel in $configObject.channels) {
        if ((-not $channel.id) -or (-not $channel.connectionId) -or (-not $channel.path)) {
            throw "Radio config contains an incomplete channel."
        }
        $connection = $configObject.connections.PSObject.Properties[$channel.connectionId].Value
        if ((-not $connection) -or (-not $connection.host) -or
                (-not $connection.username) -or ($connection.port -lt 1) -or
                ($connection.port -gt 65535)) {
            throw "Radio channel '$($channel.id)' references an incomplete connection."
        }
    }
    if ($deviceId -and $configObject.deviceId -ne "*" -and $configObject.deviceId -ne $deviceId) {
        throw "Radio config deviceId does not match Minimum Device ID $deviceId."
    }

    $remoteTemporary = "/data/local/tmp/minimum-radio-config-$PID.json"
    Invoke-TargetAdb -Arguments @("push", $resolvedConfigPath, $remoteTemporary) | Out-Null
    try {
        Invoke-TargetAdb -Arguments @("shell", "chmod", "644", $remoteTemporary) | Out-Null
        $provisionResult = Invoke-TargetAdb -Arguments @(
            "shell", "am", "broadcast",
            "-a", $RadioConfigProvisionAction,
            "-n", $ShortcutProvisionReceiver,
            "--es", "configPath", $remoteTemporary
        )
        if (($provisionResult -join "`n") -notmatch
                '(?s)result=-1.*data="?installed"?') {
            throw "Minimum rejected the private radio config."
        }
    } finally {
        Invoke-TargetAdb -Arguments @("shell", "rm", "-f", $remoteTemporary) | Out-Null
    }
    Invoke-TargetAdb -Arguments @("shell", "am", "force-stop", $MinimumPackage) | Out-Null
    Write-Host "Private Last Known Good radio config installed; token values were not displayed."
}

if (-not $ReportOnly -and -not $SkipMinimumHome -and -not $WhatIfPreference) {
    Invoke-TargetAdb -Arguments @(
        "shell", "am", "broadcast",
        "-a", $ShortcutProvisionAction,
        "-n", $ShortcutProvisionReceiver
    ) | Out-Null
    Start-Sleep -Milliseconds 500
    Invoke-TargetAdb -Arguments @(
        "shell", "am", "start",
        "-a", "android.intent.action.MAIN",
        "-c", "android.intent.category.HOME"
    ) | Out-Null
    Start-Sleep -Milliseconds 500
    if (Test-HomeChooserFocused) {
        throw "Android still displayed the HOME chooser after Minimum stopped registering as HOME."
    }
    Write-Host "OEM HOME verified: no chooser is visible; Minimum shortcut was requested."

    $finalActivity = if ($RadioConfigPath) { $MinimumActivity } else { $MinimumHomeActivity }
    Invoke-TargetAdb -Arguments @("shell", "am", "start", "-n", $finalActivity) | Out-Null
    Start-Sleep -Milliseconds 500
    if ($RadioConfigPath -and -not (Test-MinimumRadioFocused)) {
        throw "Minimum radio client did not take focus after config provisioning."
    }
    if (-not $RadioConfigPath -and -not (Test-MinimumHomeFocused)) {
        throw "Minimum recovery dashboard did not take focus."
    }
    if ($RadioConfigPath) {
        Write-Host "Minimum radio client opened with the private Last Known Good config."
    } else {
        Write-Host "Minimum recovery dashboard opened: swipe between Minimum and Settings."
    }
} elseif ($SkipMinimumHome) {
    Write-Host "Minimum dashboard/shortcut step skipped by request."
}

Write-Host "Preparation report complete. USB/ADB serial remains '$adbSerial'; Minimum identity is the per-device ID."
if ($script:CellularReadinessWarning) { exit 2 }
