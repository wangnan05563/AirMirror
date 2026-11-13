@echo off
setlocal enabledelayedexpansion
rem ============================================================
rem  AirMirror 一键发布（构建打包 + GitHub Release 全流程）
rem ------------------------------------------------------------
rem  本文件是「启动器」：真正的流水线在 scripts/release.ps1
rem  （构建 -> 校验产物 -> 打 tag -> 推送 -> 发 GitHub Release）。
rem  这样避免在 .bat 里重写一遍 GitHub 发布逻辑，复用既有的、
rem  带 rollback / 凭据安全设计的 PowerShell 实现。
rem
rem  编码：GBK（无 BOM）。cmd.exe 在中文 Windows 默认 GBK 代码页下读取
rem  批处理最稳；UTF-8（含 BOM）会被按 GBK 误读，3 字节汉字错位，触发
rem  > < & 等符号把批处理行拆断，故本文件用 GBK（需求里写的 UTF-8 BOM
rem  在 cmd 里实际不可用，已按可运行性改为 GBK）。
rem
rem  设计要点（相对通用需求模板的偏差，已对齐项目既有 release.ps1）：
rem   1) 不自动 git commit。release.ps1 要求「干净工作树」才发布，
rem      保证 tag 指向的 HEAD 能重建出同一个 APK；自动 commit 会把
rem      未提交/误改代码打进包里，故此处仅打 tag + 推送 + 发版。
rem   2) 仅在「版本号（tag）」这一步交互；其余全自动。
rem   3) 不硬编码任何 GitHub Token：优先 gh（gh auth login），
rem      其次 GITHUB_TOKEN / GH_TOKEN 环境变量（release.ps1 走 REST）。
rem   4) 命令行其余参数原样透传给 release.ps1，例如：
rem        -DryRun            只打印计划、不写入（推荐先跑一次验证）
rem        -SkipBuild         复用已有产物、不重新构建
rem        -KeystoreFile/...  传签名参数以产出可安装（signed）包
rem
rem  用法：
rem    双击 一键发布.bat                          （交互输入 tag；留空=自动生成）
rem    一键发布.bat -DryRun                       （安全验证流程，不发布）
rem    一键发布.bat -Tag v0.1.0-m0.20261113-001
rem
rem  前置：git、PowerShell、Android SDK（构建需要）；
rem        gh 或 GITHUB_TOKEN（发布需要）。
rem ============================================================

set "ROOT=%~dp0.."
set "RELEASE_PS=%~dp0release.ps1"
set "CONFIG=%~dp0release.version.txt"
set "TAG="
set "EXTRA=%*"

echo ============================================================
echo   AirMirror 一键发布
echo ============================================================

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
    echo [WARN] 未检测到 gh；release.ps1 会尝试用 GITHUB_TOKEN 走 REST 发布。
)

rem --- 2. 版本号（tag）：命令行 -Tag > 配置文件 > 交互输入 ---
echo %EXTRA% | findstr /i "\-Tag" >nul 2>nul
if not errorlevel 1 (
    echo [OK]   命令行已带 -Tag，跳过交互。
    goto :run
)

if exist "%CONFIG%" (
    set /p TAG=<"%CONFIG%"
    set "TAG=!TAG: =!"
    if defined TAG if not "!TAG!"=="" (
        echo [OK]   从配置文件读到 tag：!TAG!
        goto :run
    )
)

echo.
echo 请输入发布 tag（格式 v0.1.0-m0.YYYYMMDD-NNN，留空则用 build\patch.txt 自动生成）：
set /p TAG="tag> "
set "TAG=!TAG: =!"

:run
set "ARGS="
if defined TAG if not "!TAG!"=="" set "ARGS=-Tag !TAG!"
if defined EXTRA set "ARGS=!ARGS! !EXTRA!"

echo.
echo [INFO] 调用：powershell -NoProfile -ExecutionPolicy Bypass -File "%RELEASE_PS%" !ARGS!
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%RELEASE_PS%" !ARGS!
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
