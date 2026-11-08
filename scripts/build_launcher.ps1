# AirMirror build launcher: resolves a supported JDK from config, then delegates
# to scripts/build.ps1. Keeps runtime paths out of the code (see android-tools.config.json).

param(
    [switch]$Help,
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
$configPath = Join-Path $PSScriptRoot 'android-tools.config.json'
$root = Split-Path -Parent $PSScriptRoot

function Stop-WithError([string]$message) {
    Write-Host "[FAIL] $message" -ForegroundColor Red
    exit 1
}

if ($Help) {
    Write-Host 'Android build launcher'
    Write-Host 'Usage: <launcher>.bat [-Release] [-Patch] [-Online] [-SkipTests]'
    Write-Host '       [-KeystoreFile <jks> -KeystorePassword <pwd> -KeyAlias <alias> [-KeyPassword <pwd>]]'
    Write-Host 'JDK candidates and version range come from scripts/android-tools.config.json.'
    exit 0
}

if (-not (Test-Path $configPath)) { Stop-WithError "config not found: $configPath" }
$config = Get-Content $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
$buildConfig = $config.build
$minMajor = [int]$buildConfig.minJavaMajor
$maxMajor = [int]$buildConfig.maxJavaMajor

function Get-JavaMajor([string]$javaExe) {
    $previousEap = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $record = (& $javaExe -version 2>&1 | Select-Object -First 1)
    } finally {
        $ErrorActionPreference = $previousEap
    }
    $text = if ($record -is [System.Management.Automation.ErrorRecord]) { $record.Exception.Message } else { "$record" }
    if ($text -match 'version "(\d+)(?:\.(\d+))?') {
        $major = [int]$Matches[1]
        if ($major -eq 1 -and $Matches[2]) { $major = [int]$Matches[2] }
        return $major
    }
    return 0
}

$javaHome = $null
$tried = @()
foreach ($candidate in $buildConfig.javaHomeCandidates) {
    if (-not $candidate) { continue }
    $expanded = [System.Environment]::ExpandEnvironmentVariables($candidate)
    if (-not $expanded) { continue }
    $javaExe = Join-Path $expanded 'bin\java.exe'
    if (-not (Test-Path $javaExe)) { continue }
    $major = Get-JavaMajor $javaExe
    $tried += "$expanded (major $major)"
    if ($major -ge $minMajor -and $major -le $maxMajor) {
        $javaHome = $expanded
        break
    }
}

if (-not $javaHome) {
    $detail = if ($tried) { $tried -join '; ' } else { 'no candidate path exists' }
    Stop-WithError "No JDK in range $minMajor-$maxMajor found. Tried: $detail. Set AIRMIRROR_JAVA_HOME or edit scripts/android-tools.config.json."
}
Write-Host "[INFO] JAVA_HOME for this build: $javaHome" -ForegroundColor Cyan
$env:JAVA_HOME = $javaHome

$buildScript = Join-Path $root $buildConfig.script
if (-not (Test-Path $buildScript)) { Stop-WithError "build script not found: $buildScript" }

$forward = @{}
foreach ($name in @('Release', 'Online', 'SkipTests', 'Patch')) {
    if ($PSBoundParameters.ContainsKey($name)) { $forward[$name] = $true }
}
foreach ($name in @('KeystoreFile', 'KeystorePassword', 'KeyAlias', 'KeyPassword')) {
    if ($PSBoundParameters.ContainsKey($name)) { $forward[$name] = $PSBoundParameters[$name] }
}

& $buildScript @forward
$exitCode = if ($null -ne $LASTEXITCODE) { $LASTEXITCODE } else { 0 }
if ($exitCode -ne 0) { exit $exitCode }
exit 0
