#requires -Version 5.1
<#
.SYNOPSIS
    AirMirror 一键发布：构建 → 校验产物 → 打 tag → 发 GitHub Release。

.DESCRIPTION
    PRD Q-6 已裁「只发 GitHub Release」，所以本脚本不需要任何应用商店资质或 versionCode 分段。
    所有写操作（git tag / push / release create）都在前置检查全部通过后才发生：
    凭据缺失或产物不全时当场失败，不留下「本地打了 tag、远端没有 Release」的半成品状态。

    凭据只从环境读取，不接受命令行明文口令：
      - 有 GitHub CLI（gh）且已 `gh auth login`，或设置 GH_TOKEN / GITHUB_TOKEN 供 gh 使用；或
      - 没有 gh、只有 GITHUB_TOKEN 时，走 scripts/publish-release.py 的 REST 通道
        （远端 tag 与 Release 都由 GitHub API 创建，不依赖 git push 凭据）。

.PARAMETER DryRun
    只打印将要执行的命令，不做任何写入。用于在没有凭据的机器上验证流程。

.PARAMETER Tag
    显式指定 tag（如 v0.1.0-m0.20261106-001）。指定 tag 时构建会使用 tag 尾部的
    YYYYMMDD-NNN 作为 versionPatch，保证 APK versionName 与 tag 一致。缺省时按
    .build-state/patch.txt 生成。

.PARAMETER SkipBuild
    复用已有产物，不重新构建。

.PARAMETER SkipTests
    传给构建脚本，跳过 JVM 单测（仅建议在已单独跑过测试时使用）。

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
    [switch]$SkipTests,
    [string]$Notes = '',
    [string]$KeystoreFile = '',
    [string]$KeystorePassword = '',
    [string]$KeyAlias = '',
    [string]$KeyPassword = ''
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
function Get-DotEnvValue([string]$key) {
    $envPath = Join-Path $root '.env'
    if (-not (Test-Path -LiteralPath $envPath)) { return '' }
    $line = Get-Content -LiteralPath $envPath -Encoding UTF8 |
        Where-Object { $_ -match ("^" + [regex]::Escape($key) + "=") } |
        Select-Object -First 1
    if (-not $line) { return '' }
    return $line.Substring($key.Length + 1).Trim()
}
function Get-AaptExe {
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
    if (-not $sdk) {
        $props = Join-Path $root 'local.properties'
        if (Test-Path -LiteralPath $props) {
            $line = Get-Content -LiteralPath $props |
                Where-Object { $_ -match '^sdk\.dir=' } |
                Select-Object -First 1
            if ($line) { $sdk = $line.Substring(8).Replace('\:', ':').Replace('\\', '\') }
        }
    }
    if (-not $sdk -or -not (Test-Path -LiteralPath $sdk)) { return '' }
    $buildTools = Join-Path $sdk 'build-tools'
    if (-not (Test-Path -LiteralPath $buildTools)) { return '' }
    $dirs = @(Get-ChildItem -LiteralPath $buildTools -Directory | Sort-Object Name -Descending)
    foreach ($dir in $dirs) {
        $aapt = Join-Path $dir.FullName 'aapt.exe'
        if (Test-Path -LiteralPath $aapt) { return $aapt }
    }
    return ''
}
function Get-ApkVersionName([string]$apkPath, [string]$aaptExe) {
    $out = & $aaptExe dump badging $apkPath 2>$null
    $text = ($out | Out-String)
    $match = [regex]::Match($text, "versionName='([^']+)'")
    if ($match.Success) { return $match.Groups[1].Value }
    return ''
}
function Get-PatchFromTag([string]$value) {
    if ($value -match '\.(\d{8}-\d{3})$') { return $Matches[1] }
    return ''
}

$git = Get-Command git -ErrorAction SilentlyContinue
$gh = Get-Command gh -ErrorAction SilentlyContinue
$python = Get-Command python -ErrorAction SilentlyContinue
$hasToken = -not [string]::IsNullOrWhiteSpace($env:GH_TOKEN) -or -not [string]::IsNullOrWhiteSpace($env:GITHUB_TOKEN)
$useRest = $hasToken -and -not $gh

Step '前置检查'
if (-not $git) { Fail '未找到 git。' }
Ok "git $((& git --version) -replace 'git version ', '')"

if (-not $SkipBuild) {
    # 走 build_launcher.ps1：它会按 android-tools.config.json 自动挑选 17-22 区间的 JDK，
    # 避免直接调用 build.ps1 时受机器默认 JAVA_HOME（可能是 25）影响而构建失败。
    $buildScript = Join-Path $root 'scripts\build_launcher.ps1'
    if (-not (Test-Path $buildScript)) { Fail "缺少构建脚本：$buildScript" }
    Ok "构建脚本存在（经 build_launcher.ps1 自动选 JDK）"
}

$signingRequested = $KeystoreFile -or $KeystorePassword -or $KeyAlias -or $KeyPassword
if ($signingRequested) {
    if ($SkipBuild) { Fail '-SkipBuild 与签名参数冲突：复用旧产物无法保证它是用这个 keystore 签的。' }
    if (-not $KeystoreFile -or -not $KeystorePassword -or -not $KeyAlias) {
        Fail '签名需要同时提供 -KeystoreFile、-KeystorePassword、-KeyAlias（可选 -KeyPassword）。'
    }
    if (-not (Test-Path $KeystoreFile)) { Fail "keystore 不存在：$KeystoreFile" }
    Ok "release 签名：$KeystoreFile（alias $KeyAlias）"
} elseif (-not $SkipBuild) {
    $envKeystore = Get-DotEnvValue 'AIRMIRROR_KEYSTORE'
    $envPassword = Get-DotEnvValue 'AIRMIRROR_KEYSTORE_PASSWORD'
    $envAlias = Get-DotEnvValue 'AIRMIRROR_KEY_ALIAS'
    if ($envKeystore -and $envPassword -and $envAlias) {
        Ok "构建会读取 .env 的 release 签名配置（alias $envAlias）"
    } else {
        Warn '未传签名参数且 .env 缺少签名三要素：将产出 unsigned 包，安装会被系统拒绝。'
    }
}

if ($gh) {
    Ok "gh $(((& gh --version) | Select-Object -First 1))"
    if ($hasToken) { Ok '凭据：环境变量 token（gh 会优先使用）' }
} elseif ($hasToken) {
    if (-not $python) {
        Fail '环境里有 GITHUB_TOKEN，但没有 python：无法走 REST 发布通道，请安装 Python 3 或改用 gh。'
    }
    Ok '凭据：GITHUB_TOKEN（无 gh，走 GitHub REST API）'
} else {
    Warn '未找到 gh，也没有 GH_TOKEN/GITHUB_TOKEN：DryRun 可用，真实发布会失败。'
}

$originUrl = ((& git -C $root remote get-url origin) -join '').Trim()
$repo = ''
if ($originUrl -match 'github\.com[:/]+([^/]+)/([^/]+?)(?:\.git)?/*$') {
    $repo = "$($Matches[1])/$($Matches[2])"
} elseif (-not $DryRun) {
    Fail "无法从 origin 解析 GitHub 仓库（需要 owner/name）：$originUrl"
}

$dirty = (& git -C $root status --porcelain) -join "`n"
if ($dirty.Trim().Length -gt 0) {
    # 发布必须能从 tag 重建出同一个包：脏工作区会把未提交代码打进 APK，而 tag 指向 HEAD，两者对不上。
    # DryRun 是只读验证，允许脏工作区。
    if ($DryRun) {
        Warn "工作树有未提交改动（DryRun 不写入，允许）：`n$dirty"
    } else {
        Fail "工作树有未提交改动，发布会打出无法从 tag 重建的包：`n$dirty`n请先提交或 stash 后再发布。"
    }
}
$branch = ((& git -C $root rev-parse --abbrev-ref HEAD) -join '').Trim()
if ($branch -ne 'main') { Warn "当前分支是 $branch，不是 main。" }

$tagPatch = Get-PatchFromTag $Tag
if (-not [string]::IsNullOrWhiteSpace($Tag)) {
    if (-not $tagPatch) {
        Fail "构建发布时 -Tag 必须带 YYYYMMDD-NNN 后缀，才能与 APK versionName 对齐：$Tag"
    }
    $existingEarly = (& git -C $root tag --list $Tag) -join ''
    if ($existingEarly.Trim().Length -gt 0) { Fail "tag $Tag 已存在；换一个 patch 号或用 -Tag 指定。" }
    Ok "tag = $Tag（versionPatch=$tagPatch）"
}

if (-not $DryRun -and -not $gh -and -not $hasToken) {
    Fail '缺少 GitHub 凭据：请安装 gh 并执行 gh auth login，或设置 GITHUB_TOKEN 后重试（-DryRun 可只验证流程）。'
}

Step '构建'
if ($SkipBuild) {
    Ok '按 -SkipBuild 跳过构建'
} elseif ($DryRun) {
    $plan = if ($tagPatch) {
        "将执行：scripts\build_launcher.ps1 -Release -VersionPatch $tagPatch"
    } else {
        '将执行：scripts\build_launcher.ps1 -Release -Patch'
    }
    $plan += '（自动选 17-22 JDK）'
    if ($SkipTests) { $plan += '，跳过单测' }
    if ($signingRequested) { $plan += '（含 release 签名参数）' }
    Ok $plan
} else {
    # 用哈希表展开（而非数组展开）：PowerShell 5.1 对 [switch] 参数的数组展开不会绑定，
    # 会把 '-Release -Patch' 整体塞进第一个 [string] 参数，导致构建误判为签名+debug。
    $buildParams = @{ Release = $true }
    if ($tagPatch) {
        $buildParams['VersionPatch'] = $tagPatch
    } else {
        $buildParams['Patch'] = $true
    }
    if ($SkipTests) { $buildParams['SkipTests'] = $true }
    if ($signingRequested) {
        $buildParams['KeystoreFile'] = $KeystoreFile
        $buildParams['KeystorePassword'] = $KeystorePassword
        $buildParams['KeyAlias'] = $KeyAlias
        if ($KeyPassword) { $buildParams['KeyPassword'] = $KeyPassword }
    }
    & (Join-Path $root 'scripts\build_launcher.ps1') @buildParams
    if ($LASTEXITCODE -ne 0) { Fail "构建失败（exit=$LASTEXITCODE）。" }
    Ok '构建完成'
}

Step '确定版本与产物'
$patchFile = Join-Path $root '.build-state\patch.txt'
if ([string]::IsNullOrWhiteSpace($Tag)) {
    if (-not (Test-Path $patchFile)) {
        Fail "缺少 .build-state\patch.txt：请先执行 scripts\build.ps1 -Release -Patch，或用 -Tag 指定 tag。"
    }
    $tagPatch = (Get-Content $patchFile -Raw).Trim()
    if ([string]::IsNullOrWhiteSpace($tagPatch)) { Fail '.build-state\patch.txt 为空。' }
    $Tag = "v0.1.0-m0.$tagPatch"
    Ok "tag = $Tag（versionPatch=$tagPatch）"
}
if ($Tag -notmatch '^v\d+\.\d+\.\d+' -or -not $tagPatch) {
    Fail "tag 不符合约定（应为 v<semver>.<YYYYMMDD-NNN>）：$Tag"
}
$expectedVersionName = $Tag.Substring(1)

# 优先取 build.ps1 汇总的 release\release\；Gradle 原始目录只作回退。
$apkDir = Join-Path $root 'release\release'
if (-not (Test-Path $apkDir)) {
    $apkDir = Join-Path $root 'release\build\app\outputs\apk\release'
}
$apks = @()
if (Test-Path $apkDir) {
    $apks = @(Get-ChildItem $apkDir -Filter '*.apk' | Sort-Object Name)
}
# 只取与本次 patch 匹配的包，避免 reuse 目录里历史包干扰版本/新鲜度校验。
if ($tagPatch) {
    $apks = @($apks | Where-Object { $_.Name -like "*$tagPatch*" })
}
if ($apks.Count -eq 0) {
    if ($DryRun) {
        Warn "产物目录暂无 APK（DryRun 允许）：$apkDir"
    } else {
        Fail "没有找到 release APK：$apkDir"
    }
} else {
    $aaptExe = Get-AaptExe
    if (-not $aaptExe) {
        if ($DryRun) {
            Warn '未找到 aapt，DryRun 跳过 APK versionName 校验。'
        } else {
            Fail '未找到 aapt（Android SDK build-tools），无法校验 APK versionName 与 tag 是否一致。'
        }
    }
    foreach ($apk in $apks) {
        Ok ("{0} ({1:N0} B)" -f $apk.Name, $apk.Length)
        if ($aaptExe) {
            $apkVersion = Get-ApkVersionName $apk.FullName $aaptExe
            if (-not $apkVersion) {
                Fail "无法读取 APK versionName：$($apk.Name)"
            }
            if ($apkVersion -ne $expectedVersionName) {
                Fail "APK versionName=$apkVersion 与 tag=$Tag（期望 $expectedVersionName）不一致；请重新构建或用匹配的 -Tag。"
            }
            Ok "versionName=$apkVersion"
        }
        if ($apk.Name -like '*unsigned*') {
            if ($signingRequested) {
                Fail "已传签名参数但产物仍是未签名包：$($apk.Name)（签名未生效，检查 keystore/alias/口令）。"
            }
            Warn "$($apk.Name) 未签名，安装会被系统拒绝；请按 README 传 -KeystoreFile 重新构建。"
        }
    }
    if ($SkipBuild) {
        $headEpochText = ((& git -C $root show -s --format=%ct HEAD) -join '').Trim()
        if ($headEpochText -match '^\d+$') {
            $headTime = [DateTimeOffset]::FromUnixTimeSeconds([int64]$headEpochText).UtcDateTime
            foreach ($apk in $apks) {
                if ($apk.LastWriteTimeUtc -lt $headTime) {
                    $apkStamp = $apk.LastWriteTimeUtc.ToString('u')
                    $headStamp = $headTime.ToString('u')
                    $msg = "复用产物早于 HEAD 提交（APK $apkStamp，HEAD $headStamp）：不是当前提交构建的包，不能发布。"
                    if ($DryRun) { Warn $msg } else { Fail $msg }
                }
            }
        }
    }
}

Step '检查 tag 是否已存在'
$existing = (& git -C $root tag --list $Tag) -join ''
if ($existing.Trim().Length -gt 0) { Fail "tag $Tag 已存在；换一个 patch 号或用 -Tag 指定。" }
Ok 'tag 未被占用'

$headSha = ((& git -C $root rev-parse HEAD) -join '').Trim()
$recordScript = Join-Path $root 'scripts\release-record.ps1'
if (-not (Test-Path $recordScript)) { Fail "缺少发布记录生成器：$recordScript" }
$apkPaths = @($apks | ForEach-Object { $_.FullName })

Step '发布计划'
Write-Host "   git tag $Tag"
if ($useRest) {
    Write-Host "   python scripts\publish-release.py --repo $repo --tag $Tag（REST 建远端 tag + Release，$($apks.Count) 个 APK）"
} else {
    Write-Host "   git push origin $Tag"
    Write-Host "   gh release create $Tag 「$($apks.Count) 个 APK」 --title `"AirMirror $Tag`" --notes 说明正文"
}

if ($DryRun) {
    Step 'DryRun 结束（未做任何写入）'
    exit 0
}

Step '生成发布记录'
$recordArgs = @{
    Root      = $root
    Tag       = $Tag
    Repo      = $repo
    Branch    = $branch
    Commit    = $headSha
    Artifacts = $apkPaths
    Notes     = $Notes
}
$record = & $recordScript @recordArgs
if (-not $record -or -not (Test-Path -LiteralPath ([string]$record.Markdown))) {
    Fail '发布记录生成失败：未得到 Markdown 记录文件。'
}
$recordMd = [string]$record.Markdown
$recordJson = [string]$record.Json
$Notes = Get-Content -LiteralPath $recordMd -Raw -Encoding UTF8
Ok "发布记录：$recordMd"
Ok "机器可读记录：$recordJson"

Step '打本地 tag'
& git -C $root tag $Tag
if ($LASTEXITCODE -ne 0) { Fail 'git tag 失败。' }

if ($useRest) {
    $headSha = ((& git -C $root rev-parse HEAD) -join '').Trim()
    Step '创建远端 tag 并发布 Release（GitHub REST API）'
    $publishScript = Join-Path $root 'scripts\publish-release.py'
    if (-not (Test-Path $publishScript)) { Fail "缺少发布器：$publishScript" }
    $publishArgs = @($publishScript, '--repo', $repo, '--tag', $Tag, '--sha', $headSha,
        '--title', "AirMirror $Tag", '--notes', $Notes) + @($apks | ForEach-Object { $_.FullName })
    & python @publishArgs
    if ($LASTEXITCODE -ne 0) {
        & git -C $root tag -d $Tag | Out-Null
        Fail "REST 发布失败（exit=$LASTEXITCODE），已回滚本地 tag；远端由发布器自行回滚。"
    }
    Ok 'Release 已发布'
} else {
    Step '推送 tag'
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
}

Step '完成'
Ok "Release：https://github.com/$repo/releases/tag/$Tag"
Ok "发布记录：$recordMd"
Ok "机器可读记录：$recordJson"
