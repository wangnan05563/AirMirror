@echo off
setlocal
rem Double-click launcher for installing the debug APK and launching the receiver.
rem Logic lives in push_apk.ps1; defaults come from android-tools.config.json.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0push_apk.ps1" %*
set "CODE=%ERRORLEVEL%"
if not "%CODE%"=="0" if not defined NO_PAUSE pause
exit /b %CODE%
