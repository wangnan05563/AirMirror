# AirMirror push launcher: resolves adb, picks the newest APK for the configured
# variant, installs it and starts the receiver page. All machine-specific values
# live in android-tools.config.json.

param(
    [switch]$Help,
    [switch]$Clean,
    [switch]$DryRun,
    [Parameter(Position = 0)][string]$Serial = ''
)

$ErrorActionPreference = 'Stop'
$configPath = Join-Path $PSScriptRoot 'android-tools.config.json'
$root = Split-Path -Parent $PSScriptRoot

function Stop-WithError([string]$message) {
    Write-Host "[FAIL] $message" -ForegroundColor Red
    exit 1
}

# adb writes informational/diagnostic lines to stderr (e.g. "* daemon not running;
# starting now at tcp:5037"). With $ErrorActionPreference = 'Stop', PowerShell 5.1
# turns any native stderr into a terminating NativeCommandError and aborts. This
# wrapper temporarily relaxes the preference for the adb call only, while leaving
# $LASTEXITCODE intact so callers can still gate on it.
function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)] [string[]]$Arguments)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $adb @Arguments 2>&1 }
    finally { $ErrorActionPreference = $prev }
}

if ($Help) {
    Write-Host 'Push test launcher: installs the debug APK and launches the receiver page.'
    Write-Host 'Usage: <launcher>.bat [serial] [-Clean] [-DryRun]'
    Write-Host '  serial   device serial when more than one device is attached'
    Write-Host '  -Clean   wipe app data first (cold first-run check)'
    Write-Host '  -DryRun  resolve adb, devices and APK without installing'
    exit 0
}

if (-not (Test-Path $configPath)) { Stop-WithError "config not found: $configPath" }
$config = Get-Content $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
$push = $config.push

function Resolve-Adb {
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    foreach ($candidate in $push.adbCandidates) {
        if (-not $candidate) { continue }
        $expanded = [System.Environment]::ExpandEnvironmentVariables($candidate)
        if ($expanded -and (Test-Path $expanded)) { return $expanded }
    }
    $props = Join-Path $root 'local.properties'
    if (Test-Path $props) {
        $line = Get-Content $props | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($line) {
            $sdk = $line.Substring(8).Replace('\:', ':').Replace('\\', '\')
            $adb = Join-Path $sdk 'platform-tools\adb.exe'
            if (Test-Path $adb) { return $adb }
        }
    }
    return $null
}

$adb = Resolve-Adb
if (-not $adb) { Stop-WithError 'adb not found. Set ANDROID_HOME/ANDROID_SDK_ROOT or add platform-tools to PATH.' }
Write-Host "[INFO] adb: $adb"
Invoke-Adb start-server | Out-Null

$devices = @()
$lines = Invoke-Adb devices
foreach ($line in ($lines | Select-Object -Skip 1)) {
    if ($line -match '^(\S+)\s+device\s*$') { $devices += $Matches[1] }
}
if ($devices.Count -eq 0) { Stop-WithError 'no device connected. Start an emulator or plug a phone with USB debugging on.' }
if (-not $Serial) {
    if ($devices.Count -gt 1) {
        Stop-WithError ("more than one device attached; pass a serial: " + ($devices -join ', '))
    }
    $Serial = $devices[0]
}
if ($devices -notcontains $Serial) {
    Stop-WithError "serial $Serial is not attached. Online: $($devices -join ', ')"
}
Write-Host "[INFO] target device: $Serial"

# Artifacts live in release\<variant>\; the Gradle output dir is only a fallback.
$apkDir = Join-Path $root ("release\" + $push.variant)
if (-not (Test-Path $apkDir)) {
    $apkDir = Join-Path $root ("app\build\outputs\apk\" + $push.variant)
}
$apk = $null
foreach ($candidate in $push.apkCandidates) {
    $path = Join-Path $apkDir ($candidate.Replace('{variant}', $push.variant))
    if (Test-Path $path) { $apk = $path; break }
}
if (-not $apk -and (Test-Path $apkDir)) {
    $apk = (Get-ChildItem $apkDir -Filter *.apk | Select-Object -First 1).FullName
}
if (-not $apk) {
    Stop-WithError "no APK under $apkDir. Run the build launcher first."
}
Write-Host "[INFO] apk: $apk"

if ($DryRun) {
    Write-Host '[DONE] dry run: adb, device and APK resolved; nothing installed' -ForegroundColor Green
    exit 0
}

Invoke-Adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) { Stop-WithError 'install failed.' }

if ($Clean) {
    Write-Host '[INFO] clearing app data for a cold first-run check'
    Invoke-Adb -s $Serial shell pm clear $push.package | Out-Null
}

Invoke-Adb -s $Serial shell am start -f $push.startFlags -n ($push.package + '/' + $push.activity)
if ($LASTEXITCODE -ne 0) { Stop-WithError 'launch failed.' }
Write-Host "[DONE] installed and launched on $Serial" -ForegroundColor Green
exit 0
