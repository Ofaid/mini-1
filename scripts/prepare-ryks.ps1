<#
.SYNOPSIS
    Prepares a detected ELINK/ym_258 RYKS for Minimum radio-client use.

.DESCRIPTION
    Selects exactly one authorized RYKS target, verifies its stable build identity, and delegates
    to Minimum's shared guarded provisioning flow. The APK owns the captured PTT and side-key
    mappings; this script does not rewrite Android keylayout files or device serials.
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
            if ($manufacturer.Trim() -ieq "ELINK" -and $model.Trim() -ieq "ym_258") {
                $candidateSerial
            }
        }
    }
    $candidateList = @($candidates)
    if ($candidateList.Count -ne 1) {
        throw "Expected exactly one authorized ELINK/ym_258 RYKS; pass -Serial or -TransportId explicitly."
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
    SkipLocation = $true
    ReportOnly = $ReportOnly
    DeviceProfile = $DeviceProfile
    RadioConfigPath = $RadioConfigPath
    LabWifiSsid = $LabWifiSsid
    LabWifiCredential = $LabWifiCredential
    LabWifiCredentialPath = $LabWifiCredentialPath
    TargetName = "RYKS"
    ExpectedManufacturer = "ELINK"
    ExpectedModel = "ym_258"
}
if ($WhatIfPreference) {
    $forward.WhatIf = $true
}

& $shared @forward
exit $LASTEXITCODE
