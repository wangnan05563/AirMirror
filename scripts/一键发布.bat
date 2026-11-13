@echo off
setlocal enabledelayedexpansion
rem ============================================================
rem  AirMirror 一键发布（构建打包 + GitHub Release，无需手动输入）
rem ------------------------------------------------------------
rem  本文件是「启动器」：真正的流水线在 scripts/release.ps1
rem  （构建 -> 校验产物 -> 自动生成 tag -> 推送 -> 发 GitHub Release）。
rem  复用既有带 rollback / 凭据安全设计的 PowerShell 实现，不在批处理里重写。
rem
rem  编码：GBK（无 BOM）。cmd.exe 在中文 Windows 默认 GBK 代码页下读取批处理
rem  最稳；UTF-8（含 BOM）会被按 GBK 误读，3 字节汉字错位触发 > < & 把行拆断。
rem
rem  v2 优化（T05716）：
rem   1) 无需输入 tag：release.ps1 自动用 build\patch.txt 生成 tag，全程无交互。
rem   2) 自动从仓库根 .env 载入 GH_TOKEN（若存在且环境未设置），用于 GitHub REST
rem      发布；不硬编码、不回显任何 Token。
rem   3) 不自动 git commit（沿用 release.ps1「干净工作树」安全约束）。
rem   4) 命令行参数原样透传（如 -DryRun / -SkipBuild / -Tag xxx）。
rem
rem  用法：
rem    双击 一键发布.bat                （自动打包 + 自动 tag + 发布）
rem    一键发布.bat -DryRun             （安全验证，不发布）
rem
rem  前置：git、PowerShell、Android SDK（构建需要）；.env 里的 GH_TOKEN（发布需要）。
rem ============================================================

set "ROOT=%~dp0.."
set "RELEASE_PS=%~dp0release.ps1"
set "ENV_FILE=%ROOT%\.env"

rem --- 0. 从 .env 载入 GH_TOKEN / GITHUB_TOKEN（环境已设置则优先）---
if not defined GH_TOKEN (
    for /f "tokens=1,* delims==" %%a in ('findstr /i "^GH_TOKEN=" "%ENV_FILE%" 2^>nul') do set "GH_TOKEN=%%b"
)
if not defined GITHUB_TOKEN (
    for /f "tokens=1,* delims==" %%a in ('findstr /i "^GITHUB_TOKEN=" "%ENV_FILE%" 2^>nul') do set "GITHUB_TOKEN=%%b"
)
if defined GH_TOKEN set "GH_TOKEN=!GH_TOKEN:"=!"
if defined GITHUB_TOKEN set "GITHUB_TOKEN=!GITHUB_TOKEN:"=!"
if defined GH_TOKEN (echo [OK]   已从 .env 载入 GitHub Token) else (
    if defined GITHUB_TOKEN (echo [OK]   已从 .env 载入 GitHub Token) else (
        echo [WARN] 环境未设置 GH_TOKEN/GITHUB_TOKEN，且 .env 未找到；发布会失败。
    )
)

rem --- 1. 环境检测 ---
where git >nul 2>nul
if errorlevel 1 (
    echo [FAIL] 未检测到 git。请安装 Git for Windows 并加入 PATH。
    goto :fail
)
for /f "tokens=*" %%v in ('git --version 2^>nul') do echo [OK]   %%v

where powershell >nul 2>nul
if errorlevel 1 (
    echo [FAIL] 未检测到 PowerShell，无法调用 release.ps1。
    goto :fail
)
echo [OK]   PowerShell 可用

where gh >nul 2>nul
if not errorlevel 1 (
    echo [OK]   gh CLI 可用（优先用于发布）
) else (
    echo [OK]   将以 .env 的 GH_TOKEN 走 GitHub REST 发布。
)

set "EXTRA=%*"

echo.
echo [INFO] 调用：powershell -NoProfile -ExecutionPolicy Bypass -File "%RELEASE_PS%" !EXTRA!
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%RELEASE_PS%" !EXTRA!
set "CODE=%ERRORLEVEL%"

if not "%CODE%"=="0" (
    echo [FAIL] 发布流程退出码 %CODE%。详见上方 PowerShell 输出。
    goto :fail
)

echo.
echo [DONE] 发布完成。Release 链接见上方 PowerShell 输出。
if not defined NO_PAUSE pause
exit /b 0

:fail
if not defined NO_PAUSE pause
exit /b 1
