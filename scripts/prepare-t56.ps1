<#[
.SYNOPSIS
    Prepares a detected UNIPRO/ZX T56 for Minimum radio-client use.

.DESCRIPTION
    T56 uses the same app-private config, microphone, Zello, Launcher3 and HOME safety flow as
    T99, but target selection is model-verified and the shared implementation never receives T99's
    serial default. T56's captured vendor DTT_PTT key is configured by the APK as keyCode 261;
    this script does not rewrite any USB/Android serial or guess unverified key mappings.
#>

[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$Serial = "",
    [int]$TransportId = 0,
    [int]$AdbPort = 5037,
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
    [string]$LabWifiCredentialPath = ""
)

$ErrorActionPreference = "Stop"
$adb = (Get-Command adb -ErrorAction Stop).Source

if ($TransportId -eq 0 -and -not $Serial) {
    $devices = & $adb -P $AdbPort devices -l
    $candidates = foreach ($line in $devices) {
        if ($line -match '^([^\s]+)\s+device\s+') {
            $candidateSerial = $Matches[1]
            $manufacturer = (& $adb -P $AdbPort -s $candidateSerial shell getprop ro.product.manufacturer) -join ""
            $model = (& $adb -P $AdbPort -s $candidateSerial shell getprop ro.product.model) -join ""
            if ($manufacturer.Trim() -ieq "UNIPRO" -and $model.Trim() -ieq "ZX") {
                $candidateSerial
            }
        }
    }
    $candidateList = @($candidates)
    if ($candidateList.Count -ne 1) {
        throw "Expected exactly one authorized UNIPRO/ZX T56; pass -Serial or -TransportId explicitly."
    }
    $Serial = $candidateList[0]
}

$shared = Join-Path $PSScriptRoot "prepare-t99.ps1"
$forward = @{
    Serial = $Serial
    TransportId = $TransportId
    AdbPort = $AdbPort
    Force = $Force
    SkipZello = $SkipZello
    SkipMinimumHome = $SkipMinimumHome
    SkipLabWifi = $SkipLabWifi
    SkipLocation = $SkipLocation
    DisableDataRoaming = $DisableDataRoaming
    RequestNetworkLocationConsent = $RequestNetworkLocationConsent
    RefreshLabWifi = $RefreshLabWifi
    ReportOnly = $ReportOnly
    DeviceProfile = $DeviceProfile
    RadioConfigPath = $RadioConfigPath
    LabWifiSsid = $LabWifiSsid
    LabWifiCredential = $LabWifiCredential
    LabWifiCredentialPath = $LabWifiCredentialPath
    TargetName = "T56"
    ExpectedManufacturer = "UNIPRO"
    ExpectedModel = "ZX"
}
if ($WhatIfPreference) {
    $forward.WhatIf = $true
}

& $shared @forward
exit $LASTEXITCODE
