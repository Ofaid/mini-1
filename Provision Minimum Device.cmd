@echo off
setlocal
title Minimum One-Shot Provisioning
echo Starting Minimum device setup...
echo.
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\provision-minimum-device.ps1"
set "MINIMUM_PROVISION_EXIT=%ERRORLEVEL%"
echo.
if "%MINIMUM_PROVISION_EXIT%"=="0" (
    echo Setup window finished.
) else (
    echo SETUP FAILED with exit code %MINIMUM_PROVISION_EXIT%.
    echo Read the error above, correct it, then double-click this file again.
)
echo.
pause
exit /b %MINIMUM_PROVISION_EXIT%
