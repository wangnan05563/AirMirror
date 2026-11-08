@echo off
setlocal
rem Double-click launcher for the Android build.
rem Logic lives in build_launcher.ps1; defaults come from android-tools.config.json.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build_launcher.ps1" %*
set "CODE=%ERRORLEVEL%"
if not "%CODE%"=="0" if not defined NO_PAUSE pause
exit /b %CODE%
