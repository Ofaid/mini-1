@echo off
setlocal
title Minimum One-Shot Updater
echo Starting Minimum device update...
echo.
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\update-minimum-device.ps1"
set "MINIMUM_UPDATE_EXIT=%ERRORLEVEL%"
echo.
if "%MINIMUM_UPDATE_EXIT%"=="0" (
    echo Update window finished.
) else (
    echo UPDATE FAILED with exit code %MINIMUM_UPDATE_EXIT%.
    echo Read the sanitized error above, correct it, then run this updater again.
)
echo.
pause
exit /b %MINIMUM_UPDATE_EXIT%
