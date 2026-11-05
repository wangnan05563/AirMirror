#requires -Version 5.1
<#
.SYNOPSIS
    AirMirror 一键发布：构建 → 校验产物 → 打 tag → 发 GitHub Release。

.DESCRIPTION
    PRD Q-6 已裁「只发 GitHub Release」，所以本脚本不需要任何应用商店资质或 versionCode 分段。
    所有写操作（git tag / push / release create）都在前置检查全部通过后才发生：
    凭据缺失或产物不全时当场失败，不留下「本地打了 tag、远端没有 Release」的半成品状态。

    凭据只从环境读取，不接受命令行明文口令：
      - 需要 GitHub CLI（gh）且已 `gh auth login`；或设置 GH_TOKEN / GITHUB_TOKEN 供 gh 使用。

.PARAMETER DryRun
    只打印将要执行的命令，不做任何写入。用于在没有凭据的机器上验证流程。

.PARAMETER Tag
    显式指定 tag（如 v0.1.0-m0.20261106-001）。缺省时按 build/patch.txt 生成。

.PARAMETER SkipBuild
    复用已有产物，不重新构建。

.PARAMETER Notes
    Release 说明正文。缺省用本次 tag 与产物清单生成一段简短说明。

.EXAMPLE
    .\scripts\release.ps1 -DryRun

.EXAMPLE
    .\scripts\release.ps1 -Tag v0.1.0-m0.20261106-001
#>
param(
    [switch]$DryRun,
    [string]$Tag = '',
    [switch]$SkipBuild,
    [string]$Notes = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-StrictMode -Version 2.0

function Step([string]$text) { Write-Host ""; Write-Host "== $text" -ForegroundColor Cyan }
function Ok([string]$text) { Write-Host "   OK  $text" -ForegroundColor Green }
function Warn([string]$text) { Write-Host "   !!  $text" -ForegroundColor Yellow }
function Fail([string]$text) {
    Write-Host "   XX  $text" -ForegroundColor Red
    exit 1
}

$git = Get-Command git -ErrorAction SilentlyContinue
$gh = Get-Command gh -ErrorAction SilentlyContinue
$hasToken = -not [string]::IsNullOrWhiteSpace($env:GH_TOKEN) -or -not [string]::IsNullOrWhiteSpace($env:GITHUB_TOKEN)

Step '前置检查'
if (-not $git) { Fail '未找到 git。' }
Ok "git $((& git --version) -replace 'git version ', '')"

if (-not $SkipBuild) {
    $buildScript = Join-Path $root 'scripts\build.ps1'
    if (-not (Test-Path $buildScript)) { Fail "缺少构建脚本：$buildScript" }
    Ok "构建脚本存在"
}

if ($gh) {
    Ok "gh $(((& gh --version) | Select-Object -First 1))"
} elseif ($hasToken) {
    Warn '未找到 gh，但环境里有 GH_TOKEN/GITHUB_TOKEN；仍需安装 gh 才能创建 Release。'
} else {
    Warn '未找到 gh，也没有 GH_TOKEN/GITHUB_TOKEN：DryRun 可用，真实发布会失败。'
}

$dirty = (& git -C $root status --porcelain) -join "`n"
if ($dirty.Trim().Length -gt 0) {
    Warn "工作树有未提交改动，发布会把当前 HEAD 打成 tag：`n$dirty"
}
$branch = ((& git -C $root rev-parse --abbrev-ref HEAD) -join '').Trim()
if ($branch -ne 'main') { Warn "当前分支是 $branch，不是 main。" }

if (-not $DryRun -and -not $gh) {
    Fail '缺少 GitHub CLI：请先安装 gh 并执行 gh auth login，或改用 -DryRun 只验证流程。'
}

Step '构建'
if ($SkipBuild) {
    Ok '按 -SkipBuild 跳过构建'
} elseif ($DryRun) {
    Ok "将执行：scripts\build.ps1 -Release -Patch"
} else {
    & (Join-Path $root 'scripts\build.ps1') -Release -Patch
    if ($LASTEXITCODE -ne 0) { Fail "构建失败（exit=$LASTEXITCODE）。" }
    Ok '构建完成'
}

Step '确定版本与产物'
$patchFile = Join-Path $root 'build\patch.txt'
if ([string]::IsNullOrWhiteSpace($Tag)) {
    if (-not (Test-Path $patchFile)) {
        Fail "缺少 build\patch.txt：请先执行 scripts\build.ps1 -Release -Patch，或用 -Tag 指定 tag。"
    }
    $patch = (Get-Content $patchFile -Raw).Trim()
    if ([string]::IsNullOrWhiteSpace($patch)) { Fail 'build\patch.txt 为空。' }
    $Tag = "v0.1.0-m0.$patch"
}
if ($Tag -notmatch '^v\d+\.\d+\.\d+') { Fail "tag 不符合约定（应以 v 开头并带语义版本）：$Tag" }
Ok "tag = $Tag"

$apkDir = Join-Path $root 'app\build\outputs\apk\release'
$apks = @()
if (Test-Path $apkDir) {
    $apks = @(Get-ChildItem $apkDir -Filter '*.apk' | Sort-Object Name)
}
if ($apks.Count -eq 0) {
    if ($DryRun) {
        Warn "产物目录暂无 APK（DryRun 允许）：$apkDir"
    } else {
        Fail "没有找到 release APK：$apkDir"
    }
} else {
    foreach ($apk in $apks) {
        Ok ("{0} ({1:N0} B)" -f $apk.Name, $apk.Length)
        if ($apk.Name -like '*unsigned*') {
            Warn "$($apk.Name) 未签名，安装会被系统拒绝；请按 README 传 -PkeystoreFile 重新构建。"
        }
    }
}

Step '检查 tag 是否已存在'
$existing = (& git -C $root tag --list $Tag) -join ''
if ($existing.Trim().Length -gt 0) { Fail "tag $Tag 已存在；换一个 patch 号或用 -Tag 指定。" }
Ok 'tag 未被占用'

if ([string]::IsNullOrWhiteSpace($Notes)) {
    $Notes = "AirMirror $Tag`n`n包含本次构建的三种 ABI 安装包：armeabi-v7a、arm64-v8a、universal。"
}

Step '发布计划'
Write-Host "   git tag $Tag"
Write-Host "   git push origin $Tag"
Write-Host "   gh release create $Tag 「$($apks.Count) 个 APK」 --title `"AirMirror $Tag`" --notes 说明正文"

if ($DryRun) {
    Step 'DryRun 结束（未做任何写入）'
    exit 0
}

Step '打 tag 并推送'
& git -C $root tag $Tag
if ($LASTEXITCODE -ne 0) { Fail 'git tag 失败。' }
& git -C $root push origin $Tag
if ($LASTEXITCODE -ne 0) {
    & git -C $root tag -d $Tag | Out-Null
    Fail '推送 tag 失败，已回滚本地 tag。'
}
Ok 'tag 已推送'

Step '创建 GitHub Release'
$ghArgs = @('release', 'create', $Tag) + @($apks | ForEach-Object { $_.FullName }) +
    @('--title', "AirMirror $Tag", '--notes', $Notes)
& gh @ghArgs
if ($LASTEXITCODE -ne 0) { Fail "gh release create 失败（exit=$LASTEXITCODE）。tag 已推送，可修正后重试 gh。" }

Step '完成'
Ok "Release：https://github.com/wangnan05563/AirMirror/releases/tag/$Tag"
