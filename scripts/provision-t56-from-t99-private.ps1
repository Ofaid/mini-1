<#[
.SYNOPSIS
    Clones the already-installed private T99 schema-3 config to a T56 without printing secrets.

.DESCRIPTION
    This is an operator-only bridge for the two lab radios. It reads T99's app-private active
    config through run-as, changes only deviceId and the two requested usernames, provisions the
    result through the guarded T56 flow, and removes the temporary host file in a finally block.
    It never writes credentials to the repository or emits the config contents.
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$SourceSerial,
    [Parameter(Mandatory)]
    [string]$TargetSerial,
    [string]$TargetDeviceProfile = "P1L4A0",
    [int]$AdbPort = 5041,
    [switch]$EnableAprsTracking,
    [Security.SecureString]$AprsPasscode,
    [string]$AprsSourceCallsign = "E25FGL",
    [string]$AprsHost = "ametx.com",
    [int]$AprsPort = 8888
)

$ErrorActionPreference = "Stop"
$adb = (Get-Command adb -ErrorAction Stop).Source
$temporaryConfig = Join-Path ([System.IO.Path]::GetTempPath()) "minimum-radio-t56-private.json"

try {
    $raw = (& $adb -P $AdbPort -s $SourceSerial exec-out run-as se.lublin.mumla cat `
        files/radio-config/active-config.json | Out-String)
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($raw)) {
        throw "Unable to read the existing private T99 active config."
    }
    $config = $raw | ConvertFrom-Json
    if ($config.schemaVersion -ne 3) {
        throw "The source config is not schema 3."
    }
    if ($config.configVersion -lt 1) {
        throw "The source config has no valid config version."
    }
    # Any private clone edits must advance the version so Last Known Good rejects no-op rewrites.
    $config.configVersion = [int]$config.configVersion + 1
    $config.deviceId = $TargetDeviceProfile
    $config.connections.'tse-public-main'.username = "E25FGL-T56"
    $config.connections.e2hub.username = "E25FGL-56"
    if ($EnableAprsTracking) {
        if ($null -eq $AprsPasscode) {
            throw "AprsPasscode is required when APRS tracking is enabled."
        }
        $credentialPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($AprsPasscode)
        try {
            $plainPasscode = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($credentialPointer)
            if ($plainPasscode -notmatch '^[0-9]{1,5}$') {
                throw "The APRS passcode format is invalid."
            }
            $config | Add-Member -Force -NotePropertyName tracking -NotePropertyValue ([pscustomobject]@{
                enabled = $true
                pttTriggered = $true
                aprs = [pscustomobject]@{
                    enabled = $true
                    sourceCallsign = $AprsSourceCallsign
                    passcode = $plainPasscode
                    host = $AprsHost
                    port = $AprsPort
                }
            })
        } finally {
            if ($credentialPointer -ne [IntPtr]::Zero) {
                [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($credentialPointer)
            }
            $plainPasscode = $null
        }
    }
    $config | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $temporaryConfig -Encoding UTF8

    & (Join-Path $PSScriptRoot "prepare-t56.ps1") `
        -Serial $TargetSerial -AdbPort $AdbPort -RadioConfigPath $temporaryConfig `
        -DeviceProfile $TargetDeviceProfile -SkipZello -SkipLabWifi -SkipMinimumHome
    if ($LASTEXITCODE -ne 0) {
        throw "T56 private config provisioning failed with exit code $LASTEXITCODE."
    }
    Write-Host "T56 private schema-3 config installed; credentials were not displayed."
} finally {
    if (Test-Path -LiteralPath $temporaryConfig) {
        Remove-Item -LiteralPath $temporaryConfig -Force
    }
}
