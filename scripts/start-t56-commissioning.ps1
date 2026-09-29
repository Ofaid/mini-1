<#
.SYNOPSIS
    Opens read-only T56 hardware-key commissioning monitors on the PC.

.DESCRIPTION
    The Android window polls InputReader's recent queue and screen state. The Linux window
    streams kernel input events directly. No key is injected and no PTT command is sent.
#>

[CmdletBinding()]
param(
    [string]$Serial = "",
    [int]$AdbPort = 5041,
    [ValidateSet("Launch", "Android", "Linux")]
    [string]$Mode = "Launch"
)

$ErrorActionPreference = "Stop"
$adb = (Get-Command adb -ErrorAction Stop).Source

function Invoke-TargetAdb {
    param([Parameter(Mandatory)][string[]]$Arguments)
    & $adb -P $AdbPort -s $Serial @Arguments
}

function Resolve-T56Serial {
    if ($Serial) {
        return $Serial
    }

    $matches = @(& $adb -P $AdbPort devices -l | ForEach-Object {
        if ($_ -match '^([^\s]+)\s+device\s+') {
            $candidate = $Matches[1]
            $manufacturer = ((& $adb -P $AdbPort -s $candidate shell getprop ro.product.manufacturer) -join "").Trim()
            $model = ((& $adb -P $AdbPort -s $candidate shell getprop ro.product.model) -join "").Trim()
            if ($manufacturer -ieq "UNIPRO" -and $model -ieq "ZX") {
                $candidate
            }
        }
    })
    if ($matches.Count -ne 1) {
        throw "Expected exactly one authorized UNIPRO/ZX T56; pass -Serial explicitly."
    }
    return $matches[0]
}

$Serial = Resolve-T56Serial
$state = (& $adb -P $AdbPort -s $Serial get-state 2>&1) -join ""
if ($LASTEXITCODE -ne 0 -or $state.Trim() -ne "device") {
    throw "T56 $Serial is not available through ADB port $AdbPort."
}

$manufacturer = ((Invoke-TargetAdb -Arguments @("shell", "getprop", "ro.product.manufacturer")) -join "").Trim()
$model = ((Invoke-TargetAdb -Arguments @("shell", "getprop", "ro.product.model")) -join "").Trim()
if ($manufacturer -ine "UNIPRO" -or $model -ine "ZX") {
    throw "Target $Serial is $manufacturer/$model, not the expected UNIPRO/ZX T56."
}

if ($Mode -eq "Launch") {
    $scriptPath = $MyInvocation.MyCommand.Path
    $common = @(
        "-NoLogo",
        "-NoExit",
        "-ExecutionPolicy", "Bypass",
        "-File", $scriptPath,
        "-Serial", $Serial,
        "-AdbPort", $AdbPort
    )
    Start-Process -FilePath "powershell.exe" -ArgumentList ($common + @("-Mode", "Android"))
    Start-Process -FilePath "powershell.exe" -ArgumentList ($common + @("-Mode", "Linux"))
    Write-Host "Opened read-only Android and Linux commissioning monitors for T56."
    Write-Host "Close either window or press Ctrl+C in it to stop that monitor."
    exit 0
}

if ($Mode -eq "Linux") {
    $Host.UI.RawUI.WindowTitle = "T56 Commissioning - Linux physical events"
    Write-Host "T56 LINUX PHYSICAL EVENTS (read-only)" -ForegroundColor Cyan
    Write-Host "Press the two side keys, screen-power, then PTT with the screen off."
    Write-Host "EV_KEY value: 1=DOWN, 0=UP, 2=REPEAT. Press Ctrl+C to stop."
    Write-Host ""
    & $adb -P $AdbPort -s $Serial shell getevent -lt
    exit $LASTEXITCODE
}

$Host.UI.RawUI.WindowTitle = "T56 Commissioning - Android key mapping"
while ($true) {
    $inputDump = @(Invoke-TargetAdb -Arguments @("shell", "dumpsys", "input"))
    $windowDump = @(Invoke-TargetAdb -Arguments @("shell", "dumpsys", "window"))
    $screenLine = $windowDump | Select-String -Pattern 'mScreenOnEarly=|mScreenOnFully=' | Select-Object -First 1
    $screen = if ($screenLine -and $screenLine.Line -match 'mScreenOnFully=(true|false)') {
        if ($Matches[1] -eq 'true') { 'ON' } else { 'OFF' }
    } else {
        'UNKNOWN'
    }
    $events = @($inputDump | Select-String -Pattern 'KeyEvent\(' | ForEach-Object { $_.Line.Trim() } | Select-Object -Last 12)

    Clear-Host
    Write-Host "T56 ANDROID KEY MAPPING (read-only)" -ForegroundColor Cyan
    Write-Host "Screen: $screen    Updated: $(Get-Date -Format 'HH:mm:ss.fff')"
    Write-Host "keyCode is Android-level; scanCode is the Linux key code."
    Write-Host "action 0=DOWN, 1=UP. RecentQueue is retained by Android briefly."
    Write-Host "Press Ctrl+C to stop."
    Write-Host ""
    if ($events.Count -eq 0) {
        Write-Host "No recent Android KeyEvent is visible." -ForegroundColor DarkYellow
    } else {
        $events | ForEach-Object { Write-Host $_ }
    }
    Start-Sleep -Milliseconds 350
}
