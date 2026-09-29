[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $root "scripts\manage-cellular.ps1"
$source = Get-Content -LiteralPath $scriptPath -Raw
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile(
        $scriptPath, [ref]$tokens, [ref]$errors)
if ($errors.Count -gt 0) { throw "Cellular script parse error: $($errors[0].Message)" }

foreach ($name in @("Convert-PreferredNetworkMode", "Convert-ServiceState", "Convert-SignalStrength")) {
    $function = $ast.Find({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -ceq $name
    }, $true)
    if ($null -eq $function) { throw "Missing cellular parser '$name'." }
    Invoke-Expression $function.Extent.Text
}

$automatic = Convert-PreferredNetworkMode "22"
if (-not $automatic.Lte -or -not $automatic.Fallback -or $automatic.Name -notmatch 'automatic') {
    throw "Verified T56 mode 22 must be LTE capable with legacy fallback."
}
$lteOnly = Convert-PreferredNetworkMode "11"
if ($lteOnly.Fallback) { throw "LTE-only must never be treated as a safe fallback mode." }
$unknown = Convert-PreferredNetworkMode "999"
if ($unknown.Lte -or $unknown.Fallback) { throw "Unknown modes must fail closed." }

$service = Convert-ServiceState "0 0 home Carrier Carrier 00000 LTE LTE CSS not supported"
if (-not $service.InService -or $service.DataRat -ne "LTE" -or $service.VoiceRat -ne "LTE") {
    throw "Sanitized API-22 service-state parsing failed."
}
$signal = Convert-SignalStrength "99 0 -120 -160 -120 -1 -1 26 -98 -19 -54 2147483647 2147483647 gsm|lte"
if ($signal -ne "LTE RSRP -98 dBm (Android telephony registry)") {
    throw "LTE RSRP parsing failed: $signal"
}
if ((Convert-SignalStrength "99 0") -ne "unavailable") {
    throw "Unknown GSM ASU must remain unavailable."
}

foreach ($required in @(
        'CELLULAR COST WARNING',
        '[switch]$DisableDataRoaming',
        'if ((Get-GlobalSetting "data_roaming") -ne $desiredRoaming)',
        'API-22 Settings.Global writes do not prove the modem accepted a preferred mode',
        'if ((Get-GlobalSetting "mobile_data") -ne "1")',
        '$originalMode.Lte -and $originalMode.Fallback',
        'subscriber identifiers suppressed')) {
    if (-not $source.Contains($required)) { throw "Missing cellular safety contract: $required" }
}
if ($source -match '(?i)(imsi|iccid|imei|line1number|subscriberid)') {
    throw "Cellular script must not query or print subscriber/device identifiers."
}
if ($source -match 'content://telephony/carriers/preferapn"\)') {
    throw "Cellular script must never request a full preferred-APN row."
}
if ($source -notmatch '"--projection", "_id"') {
    throw "Cellular script must restrict APN inspection to the non-secret row identifier."
}
if ($source -match 'ExpectedManufacturer|ExpectedModel') {
    throw "Cellular mutation identity must not be caller-overridable."
}

Write-Host "All managed-cellular parser and policy checks passed."
