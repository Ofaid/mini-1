<#[
.SYNOPSIS
    Applies and verifies the managed T56 cellular-readiness policy.

.DESCRIPTION
    The command is deliberately model- and firmware-gated. It enables Data Roaming by default,
    keeps an LTE-capable automatic mode with legacy fallback, enables mobile data, and prints a
    sanitized PASS/WARN/FAIL report. It never prints subscriber/APN identity fields and never
    selects LTE-only mode. Use -DisableDataRoaming for carrier policies that prohibit roaming.
#>

[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$Serial = "",
    [int]$TransportId = 0,
    [int]$AdbPort = 5037,
    [switch]$DisableDataRoaming,
    [switch]$VerifyOnly,
    [ValidateRange(5, 180)][int]$TimeoutSeconds = 45
)

$ErrorActionPreference = "Stop"
$adbPath = (Get-Command adb -ErrorAction Stop).Source
$serverArgs = @("-P", "$AdbPort")

function Convert-PreferredNetworkMode {
    param([Parameter(Mandatory)][string]$Value)
    $modes = @{
        "0" = @{ Name = "WCDMA/GSM automatic"; Lte = $false; Fallback = $true }
        "1" = @{ Name = "GSM only"; Lte = $false; Fallback = $false }
        "2" = @{ Name = "WCDMA only"; Lte = $false; Fallback = $false }
        "3" = @{ Name = "GSM/WCDMA automatic"; Lte = $false; Fallback = $true }
        "7" = @{ Name = "CDMA/EVDO/GSM/WCDMA automatic"; Lte = $false; Fallback = $true }
        "8" = @{ Name = "LTE/CDMA/EVDO automatic"; Lte = $true; Fallback = $true }
        "9" = @{ Name = "LTE/GSM/WCDMA automatic"; Lte = $true; Fallback = $true }
        "10" = @{ Name = "LTE/CDMA/EVDO/GSM/WCDMA automatic"; Lte = $true; Fallback = $true }
        "11" = @{ Name = "LTE only"; Lte = $true; Fallback = $false }
        "12" = @{ Name = "LTE/WCDMA automatic"; Lte = $true; Fallback = $true }
        # Verified on UNIPRO/ZX build T56 / API 22. Do not copy this constant to another OEM.
        "22" = @{ Name = "LTE/TDSCDMA/CDMA/EVDO/GSM/WCDMA automatic"; Lte = $true; Fallback = $true }
    }
    $key = $Value.Trim()
    if (-not $modes.ContainsKey($key)) {
        return [pscustomobject]@{ Value = $key; Name = "unknown"; Lte = $false; Fallback = $false }
    }
    return [pscustomobject]@{
        Value = $key
        Name = $modes[$key].Name
        Lte = $modes[$key].Lte
        Fallback = $modes[$key].Fallback
    }
}

function Convert-ServiceState {
    param([string]$Text)
    $result = [ordered]@{ InService = $false; Roaming = "unknown"; VoiceRat = "unknown"; DataRat = "unknown" }
    if (-not $Text) { return [pscustomobject]$result }
    $match = [regex]::Match($Text, '^\s*(\d+)\s+(\d+)\s+(home|roaming|unknown)\s+', 'IgnoreCase')
    if ($match.Success) {
        $result.InService = $match.Groups[1].Value -eq "0"
        $result.Roaming = $match.Groups[3].Value.ToLowerInvariant()
    }
    $rat = [regex]::Match($Text, '\s([A-Z0-9_-]+)\s+([A-Z0-9_-]+)\s+CSS\s', 'IgnoreCase')
    if ($rat.Success) {
        $result.VoiceRat = $rat.Groups[1].Value.ToUpperInvariant()
        $result.DataRat = $rat.Groups[2].Value.ToUpperInvariant()
    }
    return [pscustomobject]$result
}

function Convert-SignalStrength {
    param([string]$Text)
    if (-not $Text) { return "unavailable" }
    $numbers = @([regex]::Matches($Text, '-?\d+') | ForEach-Object { [long]$_.Value })
    # AOSP API-22 layout: LTE RSRP is item 9 (zero-based 8). Accept only physical RSRP range.
    if ($numbers.Count -gt 8 -and $numbers[8] -ge -140 -and $numbers[8] -le -40) {
        return "LTE RSRP $($numbers[8]) dBm (Android telephony registry)"
    }
    $gsmAsu = if ($numbers.Count -gt 0) { $numbers[0] } else { 99 }
    if ($gsmAsu -ge 0 -and $gsmAsu -le 31) {
        return "GSM $(-113 + (2 * $gsmAsu)) dBm (ASU conversion)"
    }
    return "unavailable"
}

$deviceLines = @(& $adbPath @serverArgs devices -l)
if ($TransportId -gt 0) {
    $match = @($deviceLines | Where-Object { $_ -match "\btransport_id:$TransportId\b" })
    if ($match.Count -ne 1) { throw "Expected one authorized device on ADB transport $TransportId." }
    $targetArgs = $serverArgs + @("-t", "$TransportId")
} elseif ($Serial) {
    $match = @($deviceLines | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+device\s+" })
    if ($match.Count -ne 1) { throw "Expected one authorized device with the specified ADB serial." }
    $targetArgs = $serverArgs + @("-s", $Serial)
} else {
    throw "Pin the target with -Serial or -TransportId. Automatic cellular mutation is refused."
}

function Invoke-TargetAdb {
    param([Parameter(Mandatory)][string[]]$Arguments, [switch]$AllowFailure)
    $output = @(& $adbPath @($targetArgs + $Arguments) 2>&1)
    $exit = $LASTEXITCODE
    if (-not $AllowFailure -and $exit -ne 0) { throw "Pinned-target ADB command failed (exit $exit)." }
    return ($output | ForEach-Object { $_.ToString() })
}

function Get-Property([string]$Name) {
    return ((Invoke-TargetAdb @("shell", "getprop", $Name)) -join "").Trim()
}
function Get-GlobalSetting([string]$Name) {
    return ((Invoke-TargetAdb @("shell", "settings", "get", "global", $Name)) -join "").Trim()
}
function Set-GlobalSetting([string]$Name, [string]$Value) {
    Invoke-TargetAdb @("shell", "settings", "put", "global", $Name, $Value) | Out-Null
    $actual = Get-GlobalSetting $Name
    if ($actual -ne $Value) { throw "Cellular setting '$Name' read back as '$actual', expected '$Value'." }
}
function Get-RegistryField([string]$Registry, [string]$Name) {
    $match = [regex]::Match($Registry, "(?m)^\s*$([regex]::Escape($Name))=(.*)$")
    if ($match.Success) { return $match.Groups[1].Value.Trim() }
    return ""
}

$manufacturer = Get-Property "ro.product.manufacturer"
$model = Get-Property "ro.product.model"
$api = Get-Property "ro.build.version.sdk"
$build = Get-Property "ro.build.display.id"
$baseband = Get-Property "gsm.version.baseband"
if ($manufacturer -ine "UNIPRO" -or $model -ine "ZX") {
    throw "Unsupported hardware '$manufacturer/$model'; cellular mutation is hard-gated to UNIPRO/ZX."
}
if ($api -ne "22" -or $build -ne "T56" -or $baseband -notlike "LANSUS1-L811*") {
    throw "Unverified T56 firmware (API=$api build=$build baseband=$baseband); refusing numeric network-mode mutation."
}

$originalRoaming = Get-GlobalSetting "data_roaming"
$originalMode = Convert-PreferredNetworkMode (Get-GlobalSetting "preferred_network_mode")
$originalMobileData = Get-GlobalSetting "mobile_data"
$desiredRoaming = if ($DisableDataRoaming) { "0" } else { "1" }
$settingsChanged = $false

Write-Host "CELLULAR COST WARNING: Data Roaming can incur carrier charges. Use -DisableDataRoaming to opt out."
Write-Host "Cellular target verified: UNIPRO/ZX, Android API 22, known T56 modem firmware (subscriber identifiers suppressed)."
Write-Host "Original policy: roaming=$originalRoaming; preferred=$($originalMode.Name); mobileData=$originalMobileData."

if (-not $VerifyOnly -and $PSCmdlet.ShouldProcess("pinned UNIPRO/ZX T56", "apply managed cellular policy")) {
    if (-not $WhatIfPreference) {
        if ((Get-GlobalSetting "data_roaming") -ne $desiredRoaming) {
            Set-GlobalSetting "data_roaming" $desiredRoaming
            $settingsChanged = $true
        }
        # Do not bounce an already-enabled data service: repeated provisioning must be inert.
        if ((Get-GlobalSetting "mobile_data") -ne "1") {
            Invoke-TargetAdb @("shell", "svc", "data", "enable") | Out-Null
            $settingsChanged = $true
        }
        if ((Get-GlobalSetting "mobile_data") -ne "1") {
            throw "Mobile data could not be verified enabled."
        }
        # API-22 Settings.Global writes do not prove the modem accepted a preferred mode. Preserve
        # the commissioned safe automatic mode; an unsafe/unknown mode is reported below instead
        # of claiming a database write changed the modem.
    }
}

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$registry = ""
$service = $null
do {
    $registry = (Invoke-TargetAdb @("shell", "dumpsys", "telephony.registry")) -join "`n"
    $service = Convert-ServiceState (Get-RegistryField $registry "mServiceState")
    if ($service.InService) { break }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $deadline)

$effectiveRoaming = Get-GlobalSetting "data_roaming"
$effectiveMode = Convert-PreferredNetworkMode (Get-GlobalSetting "preferred_network_mode")
$mobileData = Get-GlobalSetting "mobile_data"
$simState = Get-Property "gsm.sim.state"
$dataState = Get-RegistryField $registry "mDataConnectionState"
$dataPossible = Get-RegistryField $registry "mDataConnectionPossible"
$dataReason = Get-RegistryField $registry "mDataConnectionReason"
$signal = Convert-SignalStrength (Get-RegistryField $registry "mSignalStrength")
$connectivity = (Invoke-TargetAdb @("shell", "dumpsys", "connectivity")) -join "`n"
$cellularRoute = $connectivity -match '(?is)type:\s*MOBILE.*?state:\s*CONNECTED/CONNECTED'
$apnOutput = (Invoke-TargetAdb @("shell", "content", "query", "--uri",
    "content://telephony/carriers/preferapn", "--projection", "_id") -AllowFailure) -join "`n"
$apnStatus = if ($apnOutput -match '(?i)permission denial|securityexception') {
    "unverifiable (OEM provider denies shell access)"
} elseif ($apnOutput -match '(?m)^Row:') {
    "selected (identity and credentials suppressed)"
} else { "not selected or unavailable" }

$failures = @()
$warnings = @()
if ($simState -ne "READY") { $failures += "SIM is $simState" }
if ($effectiveRoaming -ne $desiredRoaming) { $failures += "Data Roaming readback mismatch" }
if (-not ($effectiveMode.Lte -and $effectiveMode.Fallback)) { $warnings += "preferred mode is not safe LTE automatic/fallback" }
if ($mobileData -ne "1") { $failures += "mobile data is disabled" }
if (-not $service.InService) { $failures += "cellular service did not register" }
if ($apnStatus -like 'not selected*') { $warnings += "selected APN unavailable; no APN fields were read" }
if ($apnStatus -like 'unverifiable*') { $warnings += $apnStatus }
if (-not ($originalMode.Lte -and $originalMode.Fallback)) {
    $warnings += "preferred mode is unsafe/unknown; API-22 modem mutation is not safely verifiable and was not attempted"
}
if (-not $cellularRoute) { $warnings += "no active cellular route (dataState=$dataState reason=$dataReason possible=$dataPossible)" }
if ($service.DataRat -notmatch 'LTE') { $warnings += "registered data RAT is $($service.DataRat), documented fallback accepted" }
if ($signal -eq "unavailable") { $warnings += "signal unavailable/invalid; no weak-value claim made" }

$outcome = if ($failures.Count) { "FAIL" } elseif ($warnings.Count) { "WARN" } else { "PASS" }
Write-Host "Effective policy: roaming=$effectiveRoaming; preferred=$($effectiveMode.Name); mobileData=$mobileData."
Write-Host "Cellular state: SIM=$simState; service=$(if($service.InService){'in-service'}else{'out-of-service'}); voice=$($service.VoiceRat); data=$($service.DataRat); roaming=$($service.Roaming); route=$cellularRoute."
Write-Host "APN: $apnStatus. Signal: $signal."
Write-Host "MIGRATION_OUTCOME: $(if ($settingsChanged) { 'APPLIED' } else { 'ALREADY_OK' })"
if ($warnings.Count) { Write-Warning ($warnings -join "; ") }
if ($failures.Count) { Write-Error ($failures -join "; ") -ErrorAction Continue }
Write-Host "$outcome`: managed cellular readiness."
if ($outcome -eq "FAIL") { exit 1 }
if ($outcome -eq "WARN") { exit 2 }
exit 0
