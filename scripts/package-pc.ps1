#requires -Version 5.1
<#
.SYNOPSIS
    Build the AirMirror PC portable ZIP and installer payload.

.DESCRIPTION
    Produces release\release\AirMirror-PC-<version>-win-x64.zip with:
      AirMirror-PC.bat, app\lib, app\runtime (jlink), scripts\install/uninstall/firewall,
      ffmpeg\ (optional), README.txt, VERSION and BUILD-INFO.json.
    The package is unsigned by design (REQ-004); no EXE wrapper is fabricated.
#>
param(
    [string]$VersionPatch = '',
    [switch]$SkipBuild,
    [switch]$Online,
    [switch]$SkipRuntime,
    [string]$FfmpegDir = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $PSScriptRoot 'android-tools.config.json'
$buildRoot = Join-Path $root 'release\build\pc-package'
$outDir = Join-Path $root 'release\release'

function Fail([string]$message) {
    Write-Host "[FAIL] $message" -ForegroundColor Red
    exit 1
}

function Get-JavaMajor([string]$javaExe) {
    try {
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $record = (& $javaExe -version 2>&1 | Select-Object -First 1)
        $ErrorActionPreference = $previous
        $text = if ($record -is [System.Management.Automation.ErrorRecord]) { $record.Exception.Message } else { "$record" }
        if ($text -match 'version "(\d+)(?:\.(\d+))?') {
            $major = [int]$Matches[1]
            if ($major -eq 1 -and $Matches[2]) { $major = [int]$Matches[2] }
            return $major
        }
    } catch { }
    return 0
}

function Get-JavaHome {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    if (Test-Path -LiteralPath $configPath) {
        $config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $min = [int]$config.build.minJavaMajor
        $max = [int]$config.build.maxJavaMajor
        foreach ($candidate in $config.build.javaHomeCandidates) {
            if (-not $candidate) { continue }
            $expanded = [Environment]::ExpandEnvironmentVariables($candidate)
            if ($expanded) { $candidates += $expanded }
        }
    } else {
        $min = 17
        $max = 22
    }
    foreach ($candidate in $candidates) {
        if (-not $candidate -or -not (Test-Path -LiteralPath (Join-Path $candidate 'bin\java.exe'))) { continue }
        $major = Get-JavaMajor (Join-Path $candidate 'bin\java.exe')
        if ($major -ge $min -and $major -le $max) { return $candidate }
    }
    return ''
}

function Get-Version {
    if ($VersionPatch) {
        if ($VersionPatch -notmatch '^\d{8}-\d{3}$') { Fail "-VersionPatch must be YYYYMMDD-NNN: $VersionPatch" }
        return "0.1.0-m0.$VersionPatch"
    }
    $patchFile = Join-Path $root '.build-state\patch.txt'
    if (Test-Path -LiteralPath $patchFile) {
        $patch = (Get-Content -LiteralPath $patchFile -Raw).Trim()
        if ($patch -match '^\d{8}-\d{3}$') { return "0.1.0-m0.$patch" }
    }
    return '0.1.0-m0.dev'
}

$version = Get-Version
$javaHome = Get-JavaHome
if (-not $javaHome) { Fail 'No JDK found. Set JAVA_HOME or use scripts\android-tools.config.json.' }
$env:JAVA_HOME = $javaHome
Write-Host "[INFO] version: $version" -ForegroundColor Cyan
Write-Host "[INFO] JAVA_HOME: $javaHome" -ForegroundColor Cyan

$gradlew = Join-Path $root 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradlew)) { Fail "gradlew.bat not found: $gradlew" }

if (-not $SkipBuild) {
    $gradleArgs = @(':pc-client:installDist', '--console=plain')
    if (-not $Online) { $gradleArgs += '--offline' }
    Push-Location $root
    try {
        & $gradlew @gradleArgs
        if ($LASTEXITCODE -ne 0) { Fail "Gradle :pc-client:installDist failed (exit $LASTEXITCODE)." }
    } finally {
        Pop-Location
    }
} else {
    Write-Host '[INFO] -SkipBuild: reuse installDist output' -ForegroundColor Yellow
}

$installDist = Join-Path $root 'release\build\pc-client\install\pc-client'
$libSource = Join-Path $installDist 'lib'
if (-not (Test-Path -LiteralPath $libSource)) { Fail "installDist lib missing: $libSource" }

if (Test-Path -LiteralPath $buildRoot) { Remove-Item -LiteralPath $buildRoot -Recurse -Force }
$stage = Join-Path $buildRoot 'AirMirror-PC'
New-Item -ItemType Directory -Path (Join-Path $stage 'app\lib') -Force | Out-Null
Copy-Item -Path (Join-Path $libSource '*') -Destination (Join-Path $stage 'app\lib') -Force
Copy-Item -LiteralPath (Join-Path $installDist 'bin\pc-client') -Destination (Join-Path $stage 'app\pc-client') -Force
Copy-Item -LiteralPath (Join-Path $installDist 'bin\pc-client.bat') -Destination (Join-Path $stage 'app\pc-client.bat') -Force

if ($FfmpegDir) {
    if (-not (Test-Path -LiteralPath $FfmpegDir)) { Fail "FfmpegDir not found: $FfmpegDir" }
    Copy-Item -LiteralPath $FfmpegDir -Destination (Join-Path $stage 'ffmpeg') -Recurse -Force
} else {
    New-Item -ItemType Directory -Path (Join-Path $stage 'ffmpeg') -Force | Out-Null
}

if (-not $SkipRuntime) {
    $jlink = Join-Path $javaHome 'bin\jlink.exe'
    if (-not (Test-Path -LiteralPath $jlink)) { Fail "jlink not found: $jlink (install a full JDK, not a JRE)." }
    $runtime = Join-Path $stage 'app\runtime'
    $modules = 'java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.security.jgss,java.sql,java.xml,java.instrument,jdk.crypto.ec,jdk.unsupported,jdk.zipfs,jdk.management'
    & $jlink --add-modules $modules --strip-debug --no-man-pages --no-header-files --compress=2 --output $runtime
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath (Join-Path $runtime 'bin\javaw.exe'))) {
        Fail "jlink failed to create runtime (exit $LASTEXITCODE)."
    }
} else {
    Write-Host '[WARN] -SkipRuntime: package expects Java 17+ on PATH' -ForegroundColor Yellow
}

$scriptsOut = Join-Path $stage 'scripts'
New-Item -ItemType Directory -Path $scriptsOut -Force | Out-Null
foreach ($name in @('install.ps1', 'uninstall.ps1', 'fix-firewall.ps1', 'remove-firewall.ps1')) {
    $source = Join-Path $PSScriptRoot $name
    if (-not (Test-Path -LiteralPath $source)) { Fail "Missing installer script: $source" }
    Copy-Item -LiteralPath $source -Destination (Join-Path $scriptsOut $name) -Force
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'install.ps1') -Destination (Join-Path $stage 'install.ps1') -Force

$launcher = @'
@echo off
setlocal
set "ROOT=%~dp0"
set "JAVA=%ROOT%app\runtime\bin\javaw.exe"
if not exist "%JAVA%" set "JAVA=javaw.exe"
if not exist "%ROOT%app\lib\pc-client.jar" (
  echo [ERROR] AirMirror PC payload is incomplete.
  pause
  exit /b 1
)
pushd "%ROOT%"
start "" "%JAVA%" -cp "%ROOT%app\lib\*" io.github.wangnan05563.airmirror.pc.Main %*
popd
endlocal
'@
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText((Join-Path $stage 'AirMirror-PC.bat'), $launcher, (New-Object System.Text.ASCIIEncoding))

$readme = @"
AirMirror PC v${version}（Windows x64，未签名）

一、直接使用（便携）
  1. 解压整个目录，不要只复制 AirMirror-PC.bat。
  2. 双击 AirMirror-PC.bat。
  3. 首次启动进入四步向导：网络 → 防火墙 → 发现设备 → 投屏验证。

二、安装到当前用户
  PowerShell -ExecutionPolicy Bypass -File .\install.ps1
  静默安装：
  PowerShell -ExecutionPolicy Bypass -File .\install.ps1 -Silent
  默认安装到 `$env:LOCALAPPDATA\Programs\AirMirror-PC`，不需要管理员权限。

三、防火墙
  设备发现/投屏需要专用网络入站规则：
  scripts\fix-firewall.ps1（管理员 PowerShell，幂等）
  scripts\remove-firewall.ps1（卸载时调用）

四、卸载
  安装版：scripts\uninstall.ps1，或 Windows“应用和功能”里的 AirMirror PC。
  默认保留 `$env:APPDATA\AirMirror 配置；要彻底删除加 -RemoveConfig。

五、未签名说明
  本包没有代码签名 EXE。SmartScreen 提示时选择“更多信息 → 仍要运行”。
  不伪造签名，也不改写系统安全策略。

六、系统要求
  Windows 10/11 x64；包内已带精简 JRE，无需另装 Java。
  同网发现依赖 mDNS/UDP 5353；路由器 AP 隔离时请改用手动 IP/投屏码。

VERSION: $version
"@
[System.IO.File]::WriteAllText((Join-Path $stage 'README.txt'), $readme, $utf8NoBom)
[System.IO.File]::WriteAllText((Join-Path $stage 'VERSION'), "$version`r`n", $utf8NoBom)

$commit = ''
try { $commit = (& git -C $root rev-parse HEAD).Trim() } catch { }
$info = [ordered]@{
    product     = 'AirMirror PC'
    version     = $version
    commit      = $commit
    builtAt     = (Get-Date).ToString('o')
    runtime     = if ($SkipRuntime) { 'system-java' } else { 'bundled-jlink' }
    signed      = $false
    architecture = 'win-x64'
}
$info | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $stage 'BUILD-INFO.json') -Encoding UTF8

if (-not (Test-Path -LiteralPath $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }
$zipName = "AirMirror-PC-$version-win-x64.zip"
$zipPath = Join-Path $outDir $zipName
if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $zipPath -CompressionLevel Optimal
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $zipPath)) { Fail 'Compress-Archive failed.' }

$item = Get-Item -LiteralPath $zipPath
$hash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Host ''
Write-Host "PC package: $($item.FullName)" -ForegroundColor Green
Write-Host ("  {0:N0} B  SHA-256 {1}" -f $item.Length, $hash) -ForegroundColor Cyan
Write-Host "  stage: $stage"
[pscustomobject]@{
    version = $version
    zip     = $item.FullName
    bytes   = $item.Length
    sha256  = $hash
    stage   = $stage
} | ConvertTo-Json -Depth 4
