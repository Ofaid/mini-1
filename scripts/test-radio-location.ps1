<#[
.SYNOPSIS
    Runs a temporary, redacted GPS/network-location acceptance probe on one radio.
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Serial,
    [int]$AdbPort = 5041,
    [ValidateRange(10, 900)]
    [int]$DurationSeconds = 120,
    [switch]$ShowCoordinates
)

$ErrorActionPreference = "Stop"
$adb = (Get-Command adb -ErrorAction Stop).Source
$probeRoot = Join-Path $PSScriptRoot "..\tools\location-probe"
$apk = Join-Path $probeRoot "app\build\outputs\apk\debug\app-debug.apk"
$gradle = Join-Path $PSScriptRoot "..\gradlew.bat"
$package = "dev.minimum.locationprobe"

& $gradle -p $probeRoot :app:assembleDebug --no-daemon
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $apk)) {
    throw "Location probe build failed."
}

try {
    & $adb -P $AdbPort -s $Serial install -r $apk | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Location probe installation failed." }
    & $adb -P $AdbPort -s $Serial shell am start `
        -n "$package/.LocationProbeActivity" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Location probe launch failed." }
    Write-Host "Collecting location evidence for $DurationSeconds seconds..."
    Start-Sleep -Seconds $DurationSeconds

    $broadcast = (& $adb -P $AdbPort -s $Serial shell am broadcast `
        -a "$package.REPORT" -n "$package/.LocationProbeReceiver") -join ""
    $match = [regex]::Match($broadcast, 'data="(\{.*\})"')
    if (-not $match.Success) { throw "Location probe returned no report." }
    $report = $match.Groups[1].Value.Replace('\"', '"') | ConvertFrom-Json
    $result = [ordered]@{
        elapsedMs = $report.elapsedMs
        gpsEnabled = $report.gpsEnabled
        networkEnabled = $report.networkEnabled
        gpsFix = $report.gpsFix
        gpsAccuracyM = $report.gpsAccuracyM
        gpsAgeMs = $report.gpsAgeMs
        networkFix = $report.networkFix
        networkAccuracyM = $report.networkAccuracyM
        networkAgeMs = $report.networkAgeMs
        satellites = $report.satellites
        almanac = $report.almanac
        ephemeris = $report.ephemeris
        usedInFix = $report.usedInFix
        maxSnr = $report.maxSnr
        error = $report.error
    }
    if ($ShowCoordinates) {
        $result.gpsLatitude = $report.gpsLatitude
        $result.gpsLongitude = $report.gpsLongitude
        $result.networkLatitude = $report.networkLatitude
        $result.networkLongitude = $report.networkLongitude
    }
    [pscustomobject]$result
} finally {
    & $adb -P $AdbPort -s $Serial uninstall $package 1>$null 2>$null
    & $adb -P $AdbPort -s $Serial shell am start `
        -n se.lublin.mumla/.radio.RadioShellActivity 1>$null 2>$null
}
