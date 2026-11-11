# AirMirror build entry (Windows / PowerShell 5+).
# Detects each dependency one by one, stops on the first failure, then runs
# unit tests + assemble. Android SDK path comes from ANDROID_HOME/ANDROID_SDK_ROOT
# or local.properties (sdk.dir), matching how Gradle itself resolves it.

param(
    [switch]$Release,
    [switch]$Online,
    [switch]$SkipTests,
    [switch]$Patch,
    [string]$KeystoreFile = '',
    [string]$KeystorePassword = '',
    [string]$KeyAlias = '',
    [string]$KeyPassword = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$variant = if ($Release) { 'release' } else { 'debug' }

function Stop-WithError([string]$message) {
    Write-Host "[FAIL] $message" -ForegroundColor Red
    exit 1
}

function Write-Check([string]$name, [string]$detail) {
    Write-Host ("[ OK ] {0}: {1}" -f $name, $detail) -ForegroundColor Green
}

# Android has no package.json: keep the daily-sequence patch suffix in versionName.
# The sequence state lives under build/ (git-ignored), so plain builds stay reproducible.
function New-PatchVersion {
    $buildDir = Join-Path $root 'build'
    if (-not (Test-Path $buildDir)) { New-Item -ItemType Directory -Path $buildDir | Out-Null }
    $seqFile = Join-Path $buildDir 'patch-sequence.json'
    $today = Get-Date -Format 'yyyyMMdd'
    $seq = 0
    if (Test-Path $seqFile) {
        $state = Get-Content $seqFile -Raw | ConvertFrom-Json
        if ("$($state.date)" -eq $today) { $seq = [int]$state.seq }
    }
    $seq++
    @{ date = $today; seq = $seq } | ConvertTo-Json -Compress | Set-Content -Path $seqFile -Encoding UTF8
    $patchNumber = '{0}-{1:D3}' -f $today, $seq
    Set-Content -Path (Join-Path $buildDir 'patch.txt') -Value $patchNumber -Encoding UTF8
    return $patchNumber
}

Write-Host "AirMirror build ($variant)"
Write-Host "root: $root"

# 1. JDK: AGP 8.7 needs 17+. We require JAVA_HOME instead of guessing,
#    so a broken PATH cannot silently pick an old JRE.
if (-not $env:JAVA_HOME) {
    Stop-WithError "JAVA_HOME is not set. Install JDK 17+ and set JAVA_HOME."
}
$javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path $javaExe)) {
    Stop-WithError "JAVA_HOME points to '$env:JAVA_HOME' but bin\java.exe is missing."
}
# java writes -version to stderr; under $ErrorActionPreference='Stop' PowerShell 5
# would turn that into a terminating NativeCommandError, so relax it for this call.
$previousEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$javaRecord = (& $javaExe -version 2>&1 | Select-Object -First 1)
$ErrorActionPreference = $previousEap
# PowerShell wraps native stderr in an ErrorRecord whose ToString() is noisy;
# keep only the actual version line for parsing and display.
$javaText = if ($javaRecord -is [System.Management.Automation.ErrorRecord]) {
    $javaRecord.Exception.Message
} else {
    "$javaRecord"
}
$javaText = $javaText.Trim()
if ($javaText -notmatch 'version "(\d+)') {
    Stop-WithError "Cannot parse java version: $javaText"
}
$javaMajor = [int]$Matches[1]
# Range comes from the same config the .bat launcher reads, so a direct build.ps1
# call and a double-click build enforce one identical gate (no hardcoded bounds).
$configPath = Join-Path $PSScriptRoot 'android-tools.config.json'
if (-not (Test-Path $configPath)) {
    Stop-WithError "JDK range config not found: $configPath"
}
$buildConfig = (Get-Content $configPath -Raw -Encoding UTF8 | ConvertFrom-Json).build
$minMajor = [int]$buildConfig.minJavaMajor
$maxMajor = [int]$buildConfig.maxJavaMajor
if ($javaMajor -lt $minMajor -or $javaMajor -gt $maxMajor) {
    Stop-WithError ("JDK major {0} is outside the supported range {1}-{2} ({3}). Gradle/AGP will not run on this JDK. Point JAVA_HOME at a JDK in range, or run scripts\build_launcher.ps1 (the .bat build launcher) which auto-selects a supported JDK from android-tools.config.json." -f $javaMajor, $minMajor, $maxMajor, $javaText)
}
Write-Check 'JDK' "$javaText (supported $minMajor-$maxMajor)"

# 2. Android SDK: same precedence Gradle uses (env, then local.properties).
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) {
    $props = Join-Path $root 'local.properties'
    if (Test-Path $props) {
        $line = Get-Content $props | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($line) {
            # java.util.Properties escapes ':' and '\' inside paths
            $sdk = $line.Substring(8).Replace('\:', ':').Replace('\\', '\')
        }
    }
}
if (-not $sdk -or -not (Test-Path $sdk)) {
    Stop-WithError "Android SDK not found. Set ANDROID_HOME or sdk.dir in local.properties."
}
if (-not (Test-Path (Join-Path $sdk 'platforms'))) {
    Stop-WithError "Android SDK at '$sdk' has no platforms/ directory."
}
Write-Check 'Android SDK' $sdk

# 3. Optional: adb is not needed to build, but report it because the
#    push script depends on it and a missing platform-tools is worth seeing here.
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (Test-Path $adb) {
    Write-Check 'adb' $adb
} else {
    Write-Host "[WARN] adb not found under $sdk\platform-tools (push-apk.cmd will not work)" -ForegroundColor Yellow
}

# 4. Gradle wrapper
$gradlew = Join-Path $root 'gradlew.bat'
if (-not (Test-Path $gradlew)) {
    Stop-WithError "gradlew.bat is missing at $root."
}
Write-Check 'gradlew' $gradlew

# 5. Build + tests. Offline by default because CI-less local builds already
#    have the dependency cache; pass -Online after changing dependencies.
$gradleArgs = @()
if ($Patch) {
    $patchNumber = New-PatchVersion
    Write-Host "[INFO] patch version: $patchNumber" -ForegroundColor Cyan
    $gradleArgs += "-PversionPatch=$patchNumber"
}
if (-not $Online) { $gradleArgs += '--offline' }
if (-not $SkipTests) { $gradleArgs += $(if ($Release) { 'testReleaseUnitTest' } else { 'testDebugUnitTest' }) }
$gradleArgs += $(if ($Release) { 'assembleRelease' } else { 'assembleDebug' })
$gradleArgs += '--console=plain'

# Optional release signing. Passwords only ever travel as command-line arguments
# (never written to the repo); the echoed command below redacts them.
$signingRequested = $KeystoreFile -or $KeystorePassword -or $KeyAlias -or $KeyPassword
if ($signingRequested) {
    if (-not $Release) {
        Stop-WithError 'Signing only applies to -Release builds.'
    }
    if (-not $KeystoreFile -or -not $KeystorePassword -or -not $KeyAlias) {
        Stop-WithError 'Signing needs -KeystoreFile, -KeystorePassword and -KeyAlias together.'
    }
    if (-not (Test-Path $KeystoreFile)) {
        Stop-WithError "keystore not found: $KeystoreFile"
    }
    $gradleArgs += "-PkeystoreFile=$((Resolve-Path $KeystoreFile).Path)"
    $gradleArgs += "-PkeystorePassword=$KeystorePassword"
    $gradleArgs += "-PkeyAlias=$KeyAlias"
    if ($KeyPassword) { $gradleArgs += "-PkeyPassword=$KeyPassword" }
    Write-Host "[INFO] release signing: $KeystoreFile (alias $KeyAlias)" -ForegroundColor Cyan
}

$shownArgs = $gradleArgs | ForEach-Object { $_ -replace '^-P(keystorePassword|keyPassword)=.*', '-P$1=***' }
Write-Host "running: gradlew.bat $($shownArgs -join ' ')"
Push-Location $root
try {
    # PowerShell 5.1 turns native stderr into a terminating NativeCommandError under
    # $ErrorActionPreference='Stop'. Gradle/JDK legitimately warn on stderr (e.g. the
    # JDK 24+ restricted-method notice), which must not abort a build that exits 0.
    $previousEap = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $gradlew @gradleArgs
        $gradleExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousEap
    }
    if ($gradleExit -ne 0) {
        Stop-WithError "Gradle failed (exit $gradleExit)."
    }
} finally {
    Pop-Location
}

# 6. Collect the produced packages into release\<variant>\ - the single artifact root.
#    release\ is disposable: delete it and the next build recreates the whole set.
#    Cleaning runs after Gradle succeeds, so a failed build never destroys the last good set.
$apkDir = Join-Path $root ("app\build\outputs\apk\" + $variant)
if (-not (Test-Path $apkDir)) {
    Stop-WithError "No APK directory at $apkDir."
}
$apks = Get-ChildItem $apkDir -Filter *.apk
if (-not $apks) {
    Stop-WithError "No APK produced in $apkDir."
}
$releaseDir = Join-Path $root ("release\" + $variant)
if (Test-Path $releaseDir) {
    Remove-Item $releaseDir -Recurse -Force
}
New-Item -ItemType Directory -Path $releaseDir | Out-Null
foreach ($apk in $apks) {
    Copy-Item $apk.FullName (Join-Path $releaseDir $apk.Name) -Force
}
$apks = @(Get-ChildItem $releaseDir -Filter *.apk | Sort-Object Name)
if ($apks.Count -eq 0) {
    Stop-WithError "APK copy into $releaseDir failed."
}
Write-Host "APK (release dir):"
foreach ($apk in $apks) {
    Write-Host ("  {0}  {1:N0} B" -f $apk.FullName, $apk.Length) -ForegroundColor Cyan
}
Write-Host "[DONE] build $variant OK" -ForegroundColor Green
