<#[
.SYNOPSIS
    Compatibility entry point for the old Zello-only script.

.DESCRIPTION
    New provisioning should use prepare-t99.ps1. This wrapper preserves the old filename while
    applying the same complete T99 preparation workflow.
#>

[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$Serial = "12344321",
    [int]$TransportId = 0,
    [int]$AdbPort = 5041,
    [switch]$Force,
    [switch]$SkipZello,
    [switch]$SkipMinimumHome,
    [switch]$ReportOnly
)

$arguments = @{
    Serial = $Serial
    TransportId = $TransportId
    AdbPort = $AdbPort
    Force = $Force
    SkipZello = $SkipZello
    SkipMinimumHome = $SkipMinimumHome
    ReportOnly = $ReportOnly
}
if ($WhatIfPreference) {
    $arguments.WhatIf = $true
}

& (Join-Path $PSScriptRoot "prepare-t99.ps1") @arguments
exit $LASTEXITCODE
