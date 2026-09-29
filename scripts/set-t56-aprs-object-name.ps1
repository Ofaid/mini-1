<#
.SYNOPSIS
    Updates only the APRS Object name in a T56 app-private active config.

.DESCRIPTION
    Uses Minimum's android.permission.DUMP-protected provisioning receiver. The private config and
    credentials never leave the device. The app validates the label and advances configVersion only
    when the normalized Object name changes.
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Za-z0-9](?:[A-Za-z0-9 _-]{0,7}[A-Za-z0-9_-])?$')]
    [string]$ObjectName,
    [string]$Serial = "",
    [int]$AdbPort = 5041,
    [switch]$StartRadioShell
)

$ErrorActionPreference = "Stop"
$adb = (Get-Command adb -ErrorAction Stop).Source

if (-not $Serial) {
    $candidates = foreach ($line in (& $adb -P $AdbPort devices -l)) {
        if ($line -match '^([^\s]+)\s+device\s+') {
            $candidate = $Matches[1]
            $manufacturer = ((& $adb -P $AdbPort -s $candidate shell getprop `
                ro.product.manufacturer) -join "").Trim()
            $model = ((& $adb -P $AdbPort -s $candidate shell getprop `
                ro.product.model) -join "").Trim()
            if ($manufacturer -ieq "UNIPRO" -and $model -ieq "ZX") {
                $candidate
            }
        }
    }
    $candidateList = @($candidates)
    if ($candidateList.Count -ne 1) {
        throw "Expected exactly one authorized UNIPRO/ZX T56; pass -Serial explicitly."
    }
    $Serial = $candidateList[0]
}

$manufacturer = ((& $adb -P $AdbPort -s $Serial shell getprop `
    ro.product.manufacturer) -join "").Trim()
$model = ((& $adb -P $AdbPort -s $Serial shell getprop ro.product.model) -join "").Trim()
if ($manufacturer -ine "UNIPRO" -or $model -ine "ZX") {
    throw "Target is not an authorized UNIPRO/ZX T56."
}

$component = "se.lublin.mumla/.radio.RadioProvisionReceiver"
$result = (& $adb -P $AdbPort -s $Serial shell am broadcast -W -n $component `
    -a se.lublin.mumla.action.PROVISION_APRS_OBJECT_NAME `
    --es objectName $ObjectName) -join "`n"
if ($LASTEXITCODE -ne 0 -or $result -notmatch 'result=-1' -or $result -notmatch 'data="updated"') {
    throw "Minimum rejected the APRS Object name update."
}

if ($StartRadioShell) {
    & $adb -P $AdbPort -s $Serial shell am start -n `
        se.lublin.mumla/.radio.RadioShellActivity | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "APRS Object name was updated, but RadioShell could not be started."
    }
}

Write-Host "T56 APRS Object name updated without exporting private configuration."
