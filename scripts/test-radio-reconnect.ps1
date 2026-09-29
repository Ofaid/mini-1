<#
.SYNOPSIS
    Exercises Minimum network-loss recovery on a connected T99/T56 without pressing PTT.

.DESCRIPTION
    Records Wi-Fi/mobile-data state, opens RadioShell, disables both transports for a bounded
    outage, restores the exact prior transport state in a finally block, and waits for the ready
    UI to return. It never clears app data, changes radio config, displays tokens or transmits.
#>

[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$Serial = "12344321",
    [int]$TransportId = 0,
    [int]$AdbPort = 5041,
    [ValidateRange(5, 300)][int]$OutageSeconds = 20,
    [ValidateRange(30, 900)][int]$RecoveryTimeoutSeconds = 180,
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$MinimumPackage = "se.lublin.mumla"
$MinimumActivity = "se.lublin.mumla/.radio.RadioShellActivity"
$adbPath = (Get-Command adb -ErrorAction Stop).Source
$serverArgs = @("-P", "$AdbPort")
$deviceLines = & $adbPath @serverArgs devices -l
if ($LASTEXITCODE -ne 0) { throw "Could not query ADB on port $AdbPort" }

if ($TransportId -gt 0) {
    $line = $deviceLines | Where-Object { $_ -match "\btransport_id:$TransportId\b" }
    if (-not $line) { throw "Authorized transport id $TransportId was not found" }
    $targetArgs = $serverArgs + @("-t", "$TransportId")
    $targetLabel = "transport id $TransportId"
} else {
    $pattern = "^$([regex]::Escape($Serial))\s+device\s+"
    $matches = @($deviceLines | Where-Object { $_ -match $pattern })
    if ($matches.Count -ne 1) {
        throw "Expected exactly one authorized device with serial '$Serial'; use -TransportId when duplicated"
    }
    $targetArgs = $serverArgs + @("-s", $Serial)
    $targetLabel = "ADB serial $Serial"
}

function Invoke-TargetAdb {
    param([Parameter(Mandatory)][string[]]$Arguments)
    $output = & $adbPath @targetArgs @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "ADB command failed for $targetLabel with exit code $LASTEXITCODE"
    }
    return $output
}

function Get-GlobalSetting {
    param([Parameter(Mandatory)][string]$Name)
    return ((Invoke-TargetAdb -Arguments @("shell", "settings", "get", "global", $Name)) -join "").Trim()
}

function Assert-NetworkStateValue {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Value
    )
    if ($Value -notin @("0", "1")) {
        throw "Cannot safely test: $Name returned '$Value' instead of 0 or 1"
    }
}

function Wait-NetworkState {
    param(
        [Parameter(Mandatory)][string]$ExpectedWifiState,
        [Parameter(Mandatory)][string]$ExpectedDataState,
        [ValidateRange(1, 60)][int]$TimeoutSeconds = 20
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $actualWifiState = Get-GlobalSetting -Name "wifi_on"
        $actualDataState = Get-GlobalSetting -Name "mobile_data"
        if ($actualWifiState -eq $ExpectedWifiState -and $actualDataState -eq $ExpectedDataState) {
            return
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    throw "Network restore verification failed: expected Wi-Fi=$ExpectedWifiState mobile-data=$ExpectedDataState; got Wi-Fi=$actualWifiState mobile-data=$actualDataState"
}

function Ensure-DisplayAwakeForInspection {
    $powerState = (Invoke-TargetAdb -Arguments @("shell", "dumpsys", "power")) -join "`n"
    if ($powerState -notmatch "Display Power: state=OFF" -and
            $powerState -notmatch "mWakefulness=(Asleep|Dozing)") {
        return
    }

    Invoke-TargetAdb -Arguments @("shell", "input", "keyevent", "224") | Out-Null
    Start-Sleep -Seconds 1
    $powerState = (Invoke-TargetAdb -Arguments @("shell", "dumpsys", "power")) -join "`n"
    if ($powerState -match "Display Power: state=OFF" -or
            $powerState -match "mWakefulness=(Asleep|Dozing)") {
        # The T99 firmware ignores KEYCODE_WAKEUP from shell, but honors KEYCODE_POWER while off.
        Invoke-TargetAdb -Arguments @("shell", "input", "keyevent", "26") | Out-Null
        Start-Sleep -Seconds 2
    }
}

function Restore-Network {
    param([string]$WifiState, [string]$DataState)
    if ($WifiState -eq "1") {
        Invoke-TargetAdb -Arguments @("shell", "svc", "wifi", "enable") | Out-Null
    } elseif ($WifiState -eq "0") {
        Invoke-TargetAdb -Arguments @("shell", "svc", "wifi", "disable") | Out-Null
    }
    if ($DataState -eq "1") {
        Invoke-TargetAdb -Arguments @("shell", "svc", "data", "enable") | Out-Null
    } elseif ($DataState -eq "0") {
        Invoke-TargetAdb -Arguments @("shell", "svc", "data", "disable") | Out-Null
    }
}

if (-not $Force -and -not $WhatIfPreference) {
    $answer = Read-Host "Type RECONNECT to interrupt this radio's network for $OutageSeconds seconds"
    if ($answer -cne "RECONNECT") { Write-Host "Cancelled."; exit 0 }
}

$wifiState = Get-GlobalSetting -Name "wifi_on"
$dataState = Get-GlobalSetting -Name "mobile_data"
Assert-NetworkStateValue -Name "wifi_on" -Value $wifiState
Assert-NetworkStateValue -Name "mobile_data" -Value $dataState
Write-Host "Target: $targetLabel"
Write-Host "Original network state: Wi-Fi=$wifiState mobile-data=$dataState"

try {
    if ($PSCmdlet.ShouldProcess($targetLabel, "exercise a bounded network outage and recovery")) {
        Invoke-TargetAdb -Arguments @("shell", "am", "start", "-n", $MinimumActivity) | Out-Null
        Start-Sleep -Seconds 2
        Invoke-TargetAdb -Arguments @("shell", "svc", "wifi", "disable") | Out-Null
        Invoke-TargetAdb -Arguments @("shell", "svc", "data", "disable") | Out-Null
        Write-Host "Network disabled; observing reconnect state for $OutageSeconds seconds."
        Start-Sleep -Seconds $OutageSeconds
    }
} finally {
    if (-not $WhatIfPreference) {
        Restore-Network -WifiState $wifiState -DataState $dataState
        Wait-NetworkState -ExpectedWifiState $wifiState -ExpectedDataState $dataState
        Write-Host "Original network state restored and verified."
    }
}

if ($WhatIfPreference) { exit 0 }

$deadline = (Get-Date).AddSeconds($RecoveryTimeoutSeconds)
$ready = $false
while ((Get-Date) -lt $deadline) {
    Ensure-DisplayAwakeForInspection
    Invoke-TargetAdb -Arguments @("shell", "uiautomator", "dump", "/sdcard/minimum-reconnect.xml") | Out-Null
    $ui = (Invoke-TargetAdb -Arguments @("shell", "cat", "/sdcard/minimum-reconnect.xml")) -join "`n"
    Invoke-TargetAdb -Arguments @("shell", "rm", "/sdcard/minimum-reconnect.xml") | Out-Null
    if ($ui -match 'content-desc="minimum-state-ready"') {
        $ready = $true
        break
    }
    Start-Sleep -Seconds 5
}

if (-not $ready) {
    throw "Minimum did not return to Ready within $RecoveryTimeoutSeconds seconds"
}
Write-Host "PASS: Minimum returned to Ready after the bounded network outage."
