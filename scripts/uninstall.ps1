#requires -Version 5.1
<#
.SYNOPSIS
    AirMirror PC 卸载脚本：停止进程 → 删防火墙规则 → 删快捷方式/卸载项 → 删程序目录。

.DESCRIPTION
    默认保留 %APPDATA%\AirMirror 与 %APPDATA%\AirMirrorPC（设备标识/最近设备）；
    -RemoveConfig 才彻底删除。程序目录、快捷方式、HKCU 卸载项与防火墙规则默认清理。
#>
param(
    [switch]$Silent,
    [switch]$RemoveConfig,
    [switch]$KeepConfig,
    [switch]$KeepFirewall,
    [switch]$NoRegistry,
    [string]$InstallDir = '',
    [switch]$Help
)

$ErrorActionPreference = 'Continue'

function Info([string]$message) { Write-Host "[INFO] $message" }
function Warn([string]$message) { Write-Host "[WARN] $message" -ForegroundColor Yellow }

function Get-DefaultInstallDir {
    if ($PSScriptRoot -and (Test-Path -LiteralPath (Join-Path (Split-Path -Parent $PSScriptRoot) 'app\lib\pc-client.jar'))) {
        return (Split-Path -Parent $PSScriptRoot)
    }
    $localAppData = [Environment]::GetFolderPath('LocalApplicationData')
    if (-not $localAppData) { $localAppData = $env:LOCALAPPDATA }
    return (Join-Path $localAppData 'Programs\AirMirror-PC')
}

if ($Help) {
    Write-Host 'AirMirror PC uninstaller'
    Write-Host '  uninstall.ps1 [-Silent] [-RemoveConfig] [-KeepConfig] [-KeepFirewall] [-NoRegistry] [-InstallDir <path>]'
    exit 0
}

if ([string]::IsNullOrWhiteSpace($InstallDir)) { $InstallDir = Get-DefaultInstallDir }
$InstallDir = [System.IO.Path]::GetFullPath($InstallDir).TrimEnd('\')

if (-not $Silent -and -not $RemoveConfig -and -not $KeepConfig) {
    $answer = Read-Host '是否删除 AirMirror 配置（最近设备/向导状态）？默认保留 [y/N]'
    if ($answer -and $answer.Trim().ToLowerInvariant().StartsWith('y')) { $RemoveConfig = $true }
}

$escaped = [regex]::Escape($InstallDir)
try {
    Get-CimInstance Win32_Process -ErrorAction Stop |
        Where-Object {
            $_.Name -in @('java.exe', 'javaw.exe') -and
            $_.CommandLine -and
            ($_.CommandLine -match $escaped)
        } |
        ForEach-Object {
            try {
                Stop-Process -Id $_.ProcessId -Force -ErrorAction Stop
                Info "已停止进程 $($_.ProcessId)"
            } catch {
                Warn "停止进程 $($_.ProcessId) 失败：$($_.Exception.Message)"
            }
        }
} catch {
    Warn "枚举进程失败：$($_.Exception.Message)"
}

if (-not $KeepFirewall) {
    $removeScript = Join-Path $InstallDir 'scripts\remove-firewall.ps1'
    if (Test-Path -LiteralPath $removeScript) {
        & $removeScript -Silent
        if ($LASTEXITCODE -eq 5) {
            Warn '没有管理员权限，防火墙规则未删除；请以管理员运行 scripts\remove-firewall.ps1。'
        } elseif ($LASTEXITCODE -ne 0) {
            Warn "防火墙规则清理失败（exit=$LASTEXITCODE），不阻断卸载。"
        } else {
            Info '防火墙规则已清理'
        }
    } else {
        Warn '未找到 remove-firewall.ps1，跳过防火墙清理。'
    }
}

foreach ($shortcut in @(
    (Join-Path ([Environment]::GetFolderPath('Programs')) 'AirMirror PC.lnk'),
    (Join-Path ([Environment]::GetFolderPath('Desktop')) 'AirMirror PC.lnk')
)) {
    if (-not [string]::IsNullOrWhiteSpace($shortcut) -and (Test-Path -LiteralPath $shortcut)) {
        Remove-Item -LiteralPath $shortcut -Force -ErrorAction SilentlyContinue
        Info "已删除快捷方式：$shortcut"
    }
}

if (-not $NoRegistry) {
    $key = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\AirMirror PC'
    if (Test-Path -LiteralPath $key) {
        Remove-Item -LiteralPath $key -Recurse -Force -ErrorAction SilentlyContinue
        Info '已删除 HKCU 卸载项'
    }
}

if ($RemoveConfig) {
    foreach ($dir in @(
        (Join-Path ([Environment]::GetFolderPath('ApplicationData')) 'AirMirror'),
        (Join-Path ([Environment]::GetFolderPath('ApplicationData')) 'AirMirrorPC')
    )) {
        if (-not [string]::IsNullOrWhiteSpace($dir) -and (Test-Path -LiteralPath $dir)) {
            Remove-Item -LiteralPath $dir -Recurse -Force -ErrorAction SilentlyContinue
            Info "已删除配置：$dir"
        }
    }
    Info 'CONFIG_REMOVED=true'
} else {
    Info 'CONFIG_KEPT=true'
}

Set-Location $env:TEMP
$removed = $false
$cmdExe = Join-Path $env:SystemRoot 'System32\cmd.exe'
for ($i = 0; $i -lt 3; $i++) {
    & $cmdExe /c "rmdir /s /q `"$InstallDir`"" 2>$null | Out-Null
    if (-not (Test-Path -LiteralPath $InstallDir)) {
        $removed = $true
        break
    }
    Start-Sleep -Milliseconds 500
}
if (-not $removed) {
    # 脚本自身在程序目录里时，Windows 可能短暂锁住文件；交给 cmd 在进程退出后删除。
    $cmdFile = Join-Path $env:TEMP ("airmirror-uninstall-{0}.cmd" -f $PID)
    $cmd = "@echo off`r`ntimeout /t 2 /nobreak >nul`r`nrmdir /s /q `"$InstallDir`"`r`ndel /f /q `"%~f0`"`r`n"
    Set-Content -LiteralPath $cmdFile -Value $cmd -Encoding ASCII
    Start-Process -FilePath (Join-Path $env:SystemRoot 'System32\cmd.exe') -ArgumentList '/c', $cmdFile -WindowStyle Hidden
    Warn '程序目录将在 2 秒后删除（文件被占用）。'
} else {
    Info '程序目录已删除'
}

Write-Host 'UNINSTALL_OK'
