#requires -Version 5.1
<#
.SYNOPSIS
    AirMirror PC 安装脚本（无签名 EXE，纯 PowerShell + ZIP 载荷）。

.DESCRIPTION
    默认安装到当前用户目录，不请求管理员权限：
      %LOCALAPPDATA%\Programs\AirMirror-PC
    安装后创建开始菜单快捷方式；可选写 HKCU 卸载项与桌面快捷方式。
    -Silent 适合批量/无人值守；任何提示都不会出现。

.PARAMETER InstallDir
    指定安装目录；默认按当前用户 LocalAppData 计算。

.PARAMETER Silent
    静默安装：覆盖已有安装、不创建桌面快捷方式、不弹确认。

.PARAMETER NoShortcuts
    不创建开始菜单/桌面快捷方式（测试或绿色部署用）。

.PARAMETER NoRegistry
    不写 HKCU 卸载项（测试或便携部署用）。

.PARAMETER ConfigureFirewall
    安装完成后尝试创建专用网络防火墙规则；需要管理员权限，没有则只提示。

.PARAMETER DesktopShortcut
    额外创建桌面快捷方式；配合 -Silent 时生效。

.PARAMETER Force
    已存在安装时直接覆盖，不询问。
#>
param(
    [string]$InstallDir = '',
    [switch]$Silent,
    [switch]$NoShortcuts,
    [switch]$NoRegistry,
    [switch]$ConfigureFirewall,
    [switch]$DesktopShortcut,
    [switch]$NoVerify,
    [switch]$Force,
    [switch]$Help
)

$ErrorActionPreference = 'Stop'

function Fail([string]$message, [int]$code = 1) {
    Write-Host "[FAIL] $message" -ForegroundColor Red
    exit $code
}

function Resolve-FullPath([string]$path) {
    if ([string]::IsNullOrWhiteSpace($path)) { return '' }
    $full = [System.IO.Path]::GetFullPath($path)
    return $full.TrimEnd('\')
}

function Get-PackageRoot {
    if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'AirMirror-PC.bat')) { return $PSScriptRoot }
    $parent = Split-Path -Parent $PSScriptRoot
    if (Test-Path -LiteralPath (Join-Path $parent 'AirMirror-PC.bat')) { return $parent }
    return $PSScriptRoot
}

function Stop-InstalledProcesses([string]$dir) {
    $escaped = [regex]::Escape($dir)
    try {
        Get-CimInstance Win32_Process -ErrorAction Stop |
            Where-Object {
                $_.Name -in @('java.exe', 'javaw.exe') -and
                $_.CommandLine -and
                ($_.CommandLine -match $escaped)
            } |
            ForEach-Object {
                try { Stop-Process -Id $_.ProcessId -Force -ErrorAction Stop } catch { }
            }
    } catch {
        Write-Host "[WARN] 无法枚举/停止旧进程：$($_.Exception.Message)" -ForegroundColor Yellow
    }
}

if ($Help) {
    Write-Host 'AirMirror PC installer'
    Write-Host '  install.ps1 [-Silent] [-InstallDir <path>] [-NoShortcuts] [-NoRegistry]'
    Write-Host '              [-ConfigureFirewall] [-DesktopShortcut] [-NoVerify] [-Force]'
    exit 0
}

$packageRoot = Resolve-FullPath (Get-PackageRoot)
if (-not (Test-Path -LiteralPath (Join-Path $packageRoot 'AirMirror-PC.bat'))) {
    Fail "安装包不完整：缺少 $packageRoot\AirMirror-PC.bat"
}
if (-not (Test-Path -LiteralPath (Join-Path $packageRoot 'app\lib\pc-client.jar'))) {
    Fail "安装包不完整：缺少 app\lib\pc-client.jar"
}

if ([string]::IsNullOrWhiteSpace($InstallDir)) {
    $localAppData = [Environment]::GetFolderPath('LocalApplicationData')
    if ([string]::IsNullOrWhiteSpace($localAppData)) { $localAppData = $env:LOCALAPPDATA }
    if ([string]::IsNullOrWhiteSpace($localAppData)) { Fail '无法确定 LocalAppData；请显式传 -InstallDir。' }
    $InstallDir = Join-Path $localAppData 'Programs\AirMirror-PC'
}
$InstallDir = Resolve-FullPath $InstallDir
if ($InstallDir -eq $packageRoot) {
    Fail '安装目录不能等于解压目录；请把包解压到临时目录后再安装。'
}

$parent = Split-Path -Parent $InstallDir
if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }

$exists = Test-Path -LiteralPath $InstallDir
if ($exists -and -not $Silent -and -not $Force) {
    $answer = Read-Host "安装目录已存在：$InstallDir`n覆盖安装？[Y/n]"
    if ($answer -and $answer.Trim().ToLowerInvariant().StartsWith('n')) {
        Fail '用户取消安装。' 2
    }
}

Stop-InstalledProcesses $InstallDir
if ($exists) {
    try {
        Remove-Item -LiteralPath $InstallDir -Recurse -Force -ErrorAction Stop
    } catch {
        Fail "无法清理旧安装目录：$($_.Exception.Message)"
    }
}
New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null

Write-Host "[INFO] 安装目录：$InstallDir"
# jlink runtime 有数千个小文件，PowerShell Copy-Item 递归复制会到分钟级；
# robocopy /MT 是 Windows 自带的多线程复制，能把解压/安装压到 5 秒级。
$robocopy = Join-Path $env:SystemRoot 'System32\robocopy.exe'
if (-not (Test-Path -LiteralPath $robocopy)) { Fail '系统缺少 robocopy.exe。' }
& $robocopy $packageRoot $InstallDir /E /MT:8 /R:1 /W:1 /NFL /NDL /NJH /NJS /NC /NS /NP | Out-Null
if ($LASTEXITCODE -ge 8) {
    Fail "robocopy 复制失败（exit=$LASTEXITCODE）。"
}
if (-not (Test-Path -LiteralPath (Join-Path $InstallDir 'AirMirror-PC.bat'))) {
    Fail 'robocopy 未复制到 AirMirror-PC.bat。'
}
if (-not (Test-Path -LiteralPath (Join-Path $InstallDir 'app\runtime\bin\java.exe'))) {
    Write-Host '[WARN] 包内没有捆绑 JRE；运行依赖系统 Java 17+。' -ForegroundColor Yellow
}

if (-not $NoShortcuts) {
    $programs = [Environment]::GetFolderPath('Programs')
    if (-not [string]::IsNullOrWhiteSpace($programs)) {
        if (-not (Test-Path -LiteralPath $programs)) { New-Item -ItemType Directory -Path $programs -Force | Out-Null }
        $shell = New-Object -ComObject WScript.Shell
        $shortcut = $shell.CreateShortcut((Join-Path $programs 'AirMirror PC.lnk'))
        $shortcut.TargetPath = Join-Path $InstallDir 'AirMirror-PC.bat'
        $shortcut.WorkingDirectory = $InstallDir
        $shortcut.Description = 'AirMirror PC 投屏发送端'
        $icon = Join-Path $InstallDir 'app\runtime\bin\javaw.exe'
        if (Test-Path -LiteralPath $icon) { $shortcut.IconLocation = "$icon,0" }
        $shortcut.Save()
        Write-Host '[INFO] 已创建开始菜单快捷方式'
    }
}

if ($DesktopShortcut -and -not $NoShortcuts) {
    $desktop = [Environment]::GetFolderPath('Desktop')
    if (-not [string]::IsNullOrWhiteSpace($desktop)) {
        $shell = New-Object -ComObject WScript.Shell
        $shortcut = $shell.CreateShortcut((Join-Path $desktop 'AirMirror PC.lnk'))
        $shortcut.TargetPath = Join-Path $InstallDir 'AirMirror-PC.bat'
        $shortcut.WorkingDirectory = $InstallDir
        $shortcut.Description = 'AirMirror PC 投屏发送端'
        $icon = Join-Path $InstallDir 'app\runtime\bin\javaw.exe'
        if (Test-Path -LiteralPath $icon) { $shortcut.IconLocation = "$icon,0" }
        $shortcut.Save()
        Write-Host '[INFO] 已创建桌面快捷方式'
    }
}

if (-not $NoRegistry) {
    $key = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\AirMirror PC'
    New-Item -Path $key -Force | Out-Null
    $ps = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $uninstall = Join-Path $InstallDir 'scripts\uninstall.ps1'
    Set-ItemProperty -Path $key -Name DisplayName -Value 'AirMirror PC'
    Set-ItemProperty -Path $key -Name DisplayVersion -Value (Get-Content -LiteralPath (Join-Path $InstallDir 'VERSION') -Raw).Trim()
    Set-ItemProperty -Path $key -Name Publisher -Value 'AirMirror'
    Set-ItemProperty -Path $key -Name InstallLocation -Value $InstallDir
    Set-ItemProperty -Path $key -Name UninstallString -Value "`"$ps`" -NoProfile -ExecutionPolicy Bypass -File `"$uninstall`" -InstallDir `"$InstallDir`""
    Set-ItemProperty -Path $key -Name QuietUninstallString -Value "`"$ps`" -NoProfile -ExecutionPolicy Bypass -File `"$uninstall`" -Silent -InstallDir `"$InstallDir`""
    New-ItemProperty -Path $key -Name NoModify -PropertyType DWord -Value 1 -Force | Out-Null
    New-ItemProperty -Path $key -Name NoRepair -PropertyType DWord -Value 1 -Force | Out-Null
    $bytes = 0
    try {
        $bytes = (Get-ChildItem -LiteralPath $InstallDir -Recurse -File | Measure-Object Length -Sum).Sum
    } catch { }
    if ($bytes -gt 0) {
        New-ItemProperty -Path $key -Name EstimatedSize -PropertyType DWord -Value ([int]($bytes / 1KB)) -Force | Out-Null
    }
    Write-Host '[INFO] 已写入 HKCU 卸载项'
}

if ($ConfigureFirewall) {
    $fix = Join-Path $InstallDir 'scripts\fix-firewall.ps1'
    if (Test-Path -LiteralPath $fix) {
        & $fix -Silent
        if ($LASTEXITCODE -ne 0) {
            Write-Host '[WARN] 防火墙规则未创建（需要管理员权限）；首次向导会再次提示。' -ForegroundColor Yellow
        }
    }
}

if (-not $NoVerify) {
    $java = Join-Path $InstallDir 'app\runtime\bin\java.exe'
    if (Test-Path -LiteralPath $java) {
        $classpath = Join-Path $InstallDir 'app\lib\*'
        $output = & $java -cp $classpath io.github.wangnan05563.airmirror.pc.Main --self-check 2>&1
        if ($LASTEXITCODE -ne 0 -or ($output -join "`n") -notmatch 'AIRMIRROR_PC_SELF_CHECK_OK') {
            Fail "安装后自检失败：$($output -join ' ')"
        }
        Write-Host "[INFO] 自检通过：$($output | Select-Object -First 1)"
    } else {
        Write-Host '[WARN] 跳过自检：没有捆绑 JRE。' -ForegroundColor Yellow
    }
}

Write-Host "[OK] AirMirror PC 已安装：$InstallDir" -ForegroundColor Green
Write-Host 'INSTALL_OK'
