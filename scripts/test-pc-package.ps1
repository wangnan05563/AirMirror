#requires -Version 5.1
<#
.SYNOPSIS
    Verify the PC package end-to-end on the current Windows machine.

.DESCRIPTION
    Checks ZIP layout, silent install into a temp directory, bundled-runtime self-check,
    firewall/check dry-run, and uninstall without touching the user's real config,
    shortcuts or HKCU uninstall entry.
#>
param(
    [string]$Zip = '',
    [switch]$KeepTemp
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $root 'release\release'

if ([string]::IsNullOrWhiteSpace($Zip)) {
    $latest = Get-ChildItem -LiteralPath $outDir -Filter 'AirMirror-PC-*-win-x64.zip' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $latest) { throw "No PC ZIP found in $outDir" }
    $Zip = $latest.FullName
}
$Zip = [System.IO.Path]::GetFullPath($Zip)
if (-not (Test-Path -LiteralPath $Zip)) { throw "ZIP not found: $Zip" }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($Zip)
$required = @(
    'AirMirror-PC.bat',
    'install.ps1',
    'app/lib/pc-client.jar',
    'app/runtime/bin/java.exe',
    'app/runtime/bin/javaw.exe',
    'scripts/fix-firewall.ps1',
    'scripts/remove-firewall.ps1',
    'scripts/uninstall.ps1',
    'README.txt',
    'VERSION',
    'BUILD-INFO.json'
)
try {
    $names = @($archive.Entries | ForEach-Object { $_.FullName })
    $missing = $required | Where-Object { $names -notcontains $_ }
    if ($missing.Count -gt 0) { throw "ZIP missing entries: $($missing -join ', ')" }
} finally {
    $archive.Dispose()
}
Write-Host "[OK] ZIP layout: $Zip" -ForegroundColor Green

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$tempRoot = Join-Path $root "build\pc-package-test-$stamp"
New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null
$packageRoot = Join-Path $tempRoot 'package'
$installDir = Join-Path $tempRoot 'installed'
Expand-Archive -LiteralPath $Zip -DestinationPath $packageRoot -Force

$installWatch = [Diagnostics.Stopwatch]::StartNew()
$installOutput = & (Join-Path $packageRoot 'install.ps1') -Silent -InstallDir $installDir -NoShortcuts -NoRegistry *>&1
$installWatch.Stop()
$installExit = $LASTEXITCODE
if ($installExit -ne 0 -or ($installOutput -join "`n") -notmatch 'INSTALL_OK') {
    throw "Silent install failed (exit=$installExit): $($installOutput -join ' ')"
}
Write-Host '[OK] silent install' -ForegroundColor Green

$java = Join-Path $installDir 'app\runtime\bin\java.exe'
$classpath = Join-Path $installDir 'app\lib\*'
$selfCheck = & $java -cp $classpath io.github.wangnan05563.airmirror.pc.Main --self-check 2>&1
if ($LASTEXITCODE -ne 0 -or ($selfCheck -join "`n") -notmatch 'AIRMIRROR_PC_SELF_CHECK_OK') {
    throw "Bundled runtime self-check failed: $($selfCheck -join ' ')"
}
Write-Host "[OK] bundled runtime: $($selfCheck | Select-Object -First 1)" -ForegroundColor Green

$fix = Join-Path $installDir 'scripts\fix-firewall.ps1'
$check = & $fix -Check 2>&1
if (($check -join "`n") -notmatch 'RULES=\d+') { throw "firewall -Check did not return RULES: $($check -join ' ')" }
$dry = & $fix -DryRun 2>&1
if (($dry -join "`n") -notmatch 'WOULD_CREATE') { throw "firewall -DryRun did not return planned rules: $($dry -join ' ')" }
$removeDry = & (Join-Path $installDir 'scripts\remove-firewall.ps1') -DryRun 2>&1
if (($removeDry -join "`n") -notmatch 'WOULD_REMOVE') { throw "remove-firewall -DryRun did not return planned names: $($removeDry -join ' ')" }
Write-Host "[OK] firewall scripts: $($check -join ' | ')" -ForegroundColor Green

$uninstall = Join-Path $installDir 'scripts\uninstall.ps1'
$uninstallWatch = [Diagnostics.Stopwatch]::StartNew()
$uninstallOutput = & $uninstall -Silent -InstallDir $installDir -KeepConfig -KeepFirewall -NoRegistry *>&1
$uninstallWatch.Stop()
$uninstallExit = $LASTEXITCODE
if ($uninstallExit -ne 0 -or ($uninstallOutput -join "`n") -notmatch 'UNINSTALL_OK') {
    throw "Uninstall failed (exit=$uninstallExit): $($uninstallOutput -join ' ')"
}
Start-Sleep -Milliseconds 800
if (Test-Path -LiteralPath $installDir) {
    throw "Uninstall left program directory: $installDir"
}
Write-Host '[OK] uninstall removed program directory (config kept by design)' -ForegroundColor Green

$result = [ordered]@{
    zip          = $Zip
    zipBytes     = (Get-Item -LiteralPath $Zip).Length
    zipSha256    = (Get-FileHash -LiteralPath $Zip -Algorithm SHA256).Hash.ToLowerInvariant()
    layout       = 'ok'
    install      = 'ok'
    installMs    = $installWatch.ElapsedMilliseconds
    selfCheck    = ($selfCheck | Select-Object -First 1)
    firewallCheck = ($check -join ' | ')
    uninstall    = 'ok'
    uninstallMs  = $uninstallWatch.ElapsedMilliseconds
    configKept   = $true
    osCaption    = (Get-CimInstance Win32_OperatingSystem).Caption
    osVersion    = (Get-CimInstance Win32_OperatingSystem).Version
    testedAt     = (Get-Date).ToString('o')
}
$resultPath = Join-Path $root 'build\pc-package-test-result.json'
$result | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $resultPath -Encoding UTF8

if (-not $KeepTemp) {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}
Write-Host "[OK] result: $resultPath" -ForegroundColor Green
$result | ConvertTo-Json -Depth 4
