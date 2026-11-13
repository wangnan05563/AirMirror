@echo off
setlocal enabledelayedexpansion
rem Push the newest debug APK to one connected device and launch the receiver.
rem Usage: push-apk.cmd [serial] [clean]
rem   serial  device serial when more than one device is attached
rem   clean   wipe app data first (useful to re-check the first-run onboarding)

set "ROOT=%~dp0.."
set "PKG=io.github.wangnan05563.airmirror"
set "ACT=.ui.ReceiverActivity"
set "SERIAL="
set "CLEAN="

for %%a in (%*) do (
    if /i "%%~a"=="clean" (
        set "CLEAN=clean"
    ) else (
        set "SERIAL=%%~a"
    )
)

rem --- locate adb: PATH first, then ANDROID_HOME, then local.properties ---
set "ADB=adb"
where adb >nul 2>nul
if errorlevel 1 (
    if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
    if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platform-tools\adb.exe" set "ADB=%ANDROID_SDK_ROOT%\platform-tools\adb.exe"
    if "!ADB!"=="adb" if exist "%ROOT%\local.properties" (
        for /f "tokens=1,* delims==" %%a in ('findstr /b "sdk.dir" "%ROOT%\local.properties"') do set "SDKP=%%b"
        if defined SDKP (
            rem java properties escape: E\:\\path\\to\\sdk -> E:\path\to\sdk
            set "SDKP=!SDKP:\:=:!"
            set "SDKP=!SDKP:\\=\!"
            if exist "!SDKP!\platform-tools\adb.exe" set "ADB=!SDKP!\platform-tools\adb.exe"
        )
    )
)
if not "!ADB!"=="adb" if not exist "!ADB!" (
    echo [FAIL] adb path not found: !ADB!
    goto :fail
)
if "!ADB!"=="adb" (
    where adb >nul 2>nul
    if errorlevel 1 (
        echo [FAIL] adb not found. Set ANDROID_HOME or add platform-tools to PATH.
        goto :fail
    )
)

echo [INFO] adb: !ADB!
"!ADB!" start-server >nul 2>nul

rem --- collect online devices ---
set /a COUNT=0
for /f "skip=1 tokens=1,2" %%a in ('"!ADB!" devices 2^>nul') do (
    if "%%b"=="device" (
        set /a COUNT+=1
        set "DEV!COUNT!=%%a"
    )
)
if %COUNT%==0 (
    echo [FAIL] no device connected. Start an emulator or plug a phone with USB debugging on.
    goto :fail
)
if %COUNT%==1 set "SERIAL=!DEV1!"
if %COUNT% GTR 1 (
    if not defined SERIAL (
        echo [FAIL] more than one device attached; pass a serial:
        for /l %%i in (1,1,%COUNT%) do echo    !DEV%%i!
        goto :fail
    )
)
echo [INFO] target device: !SERIAL!

rem --- pick APK: universal first, then per-ABI, then any ---
set "APKDIR=%ROOT%\app\build\outputs\apk\debug"
set "APK=%APKDIR%\AirMirror-universal-debug.apk"
if not exist "!APK!" set "APK=%APKDIR%\AirMirror-arm64-v8a-debug.apk"
if not exist "!APK!" set "APK=%APKDIR%\AirMirror-armeabi-v7a-debug.apk"
if not exist "!APK!" (
    for %%f in ("%APKDIR%\*.apk") do set "APK=%%~ff"
)
if not exist "!APK!" (
    echo [FAIL] no APK under %APKDIR%. Run scripts\build.ps1 first.
    goto :fail
)
echo [INFO] apk: !APK!

rem --- install ---
"!ADB!" -s !SERIAL! install -r "!APK!"
if errorlevel 1 (
    echo [FAIL] install failed.
    goto :fail
)

if defined CLEAN (
    echo [INFO] clearing app data for a cold first-run check
    "!ADB!" -s !SERIAL! shell pm clear %PKG% >nul
)

rem --- launch the receiver page (same flags the boot receiver uses) ---
"!ADB!" -s !SERIAL! shell am start -f 0x10020000 -n %PKG%/%ACT%
if errorlevel 1 (
    echo [FAIL] launch failed.
    goto :fail
)
echo [DONE] installed and launched on !SERIAL!
if not defined NO_PAUSE pause
exit /b 0

:fail
if not defined NO_PAUSE pause
exit /b 1
