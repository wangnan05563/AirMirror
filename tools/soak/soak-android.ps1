#requires -Version 7.0
<#
.SYNOPSIS
    Android 端 30 分钟 soak 采样器：每 5 分钟采 PSS/CPU/温度，持续抓 logcat，结束后拉取 perf JSONL 并汇总。

.DESCRIPTION
    只做测量，不启动投屏、不改设备设置。开跑前需由人工在接收端开始一次真实投屏，
    并保持屏幕常亮、网络不变。脚本按 SLO v2.0 的 5 分钟粒度采样，输出 samples.csv、
    preflight.txt、logcat.txt、perf.jsonl 和 summary.json/md。

    注意：本机模拟器或非代表机只能做工具冒烟，不能替代 A/B/C 级 30 分钟验收。

.PARAMETER Serial
    adb 设备序列号；只有一台设备时可省略。

.PARAMETER Package
    接收端包名，默认 io.github.wangnan05563.airmirror。

.PARAMETER DurationMinutes
    采样总时长，默认 30 分钟。

.PARAMETER IntervalSeconds
    采样间隔，默认 300 秒（SLO 要求的 5 分钟）。

.PARAMETER OutDir
    证据输出目录；默认 docs/assets/soak-<timestamp>。

.PARAMETER Adb
    adb 可执行文件路径，默认 adb。

.PARAMETER SkipPerfPull
    跳过 run-as 拉取 perf JSONL（release 包不可 run-as 时使用）。

.PARAMETER NoLogcat
    不启动 logcat 后台抓取。

.PARAMETER DryRun
    只做设备/包预检并写 preflight.txt，不采样。
#>
[CmdletBinding()]
param(
    [string]$Serial = '',
    [string]$Package = 'io.github.wangnan05563.airmirror',
    [ValidateRange(1, 180)]
    [int]$DurationMinutes = 30,
    [ValidateRange(10, 600)]
    [int]$IntervalSeconds = 300,
    [string]$OutDir = '',
    [string]$Adb = 'adb',
    [string]$Label = 'unlabeled',
    [switch]$SkipPerfPull,
    [switch]$NoLogcat,
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:Adb = $Adb
$script:Serial = $Serial
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $repoRoot ('docs\assets\soak-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
$OutDir = [IO.Path]::GetFullPath($OutDir)
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $OutDir 'snapshots') | Out-Null

function Write-SoakLog([string]$Message) {
    $line = '[{0}] {1}' -f (Get-Date -Format 'HH:mm:ss'), $Message
    Write-Output $line
    Add-Content -LiteralPath (Join-Path $OutDir 'soak.log') -Value $line -Encoding utf8
}

function Invoke-AdbText {
    param(
        [Parameter(Mandatory = $true)][string[]]$AdbArgs,
        [switch]$AllowFailure
    )
    $prefix = @()
    if (-not [string]::IsNullOrWhiteSpace($script:Serial)) {
        $prefix = @('-s', $script:Serial)
    }
    $raw = & $script:Adb @prefix @AdbArgs 2>&1
    $code = $LASTEXITCODE
    $text = (($raw | ForEach-Object { "$_" }) -join "`n").TrimEnd()
    if ($code -ne 0 -and -not $AllowFailure) {
        throw "adb 失败（exit $code）：$($AdbArgs -join ' ')`n$text"
    }
    return $text
}

function Get-FirstNumber {
    param([string]$Text, [string]$Pattern)
    if ([string]::IsNullOrWhiteSpace($Text)) { return $null }
    $m = [regex]::Match($Text, $Pattern, [Text.RegularExpressions.RegexOptions]::IgnoreCase)
    if (-not $m.Success) { return $null }
    $value = $m.Groups[1].Value -replace ',', ''
    return [double]$value
}

function Get-PerfFiles {
    $listing = Invoke-AdbText -AdbArgs @('shell', "run-as $Package ls files/perf") -AllowFailure
    if ($LASTEXITCODE -ne 0 -or $listing -match 'No such file|Permission denied|unknown user|not debuggable') {
        return @()
    }
    return @($listing -split "`n" | ForEach-Object { $_.Trim() } | Where-Object {
        $_ -match '^perf[^/]*\.jsonl$'
    })
}

# ---- 设备选择与预检 ----
$devicesText = (& $script:Adb devices -l 2>&1 | Out-String)
$deviceLines = @($devicesText -split "`n" | Where-Object { $_ -match '^\S+\s+(device|unauthorized|offline|bootloader)' })
if ([string]::IsNullOrWhiteSpace($script:Serial)) {
    $online = @($deviceLines | Where-Object { $_ -match '^\S+\s+device' })
    if ($online.Count -eq 0) { throw "没有在线 adb 设备：`n$devicesText" }
    if ($online.Count -gt 1) { throw "有多台在线设备，请用 -Serial 指定：`n$($online -join "`n")" }
    $script:Serial = ($online[0] -split '\s+')[0]
}
$selectedLine = @($deviceLines | Where-Object { $_ -match ('^' + [regex]::Escape($script:Serial) + '\s+') })
if ($selectedLine.Count -eq 0) { throw "设备 $($script:Serial) 不在 adb devices 列表中。`n$devicesText" }
if ($selectedLine[0] -notmatch '^\S+\s+device') { throw "设备 $($script:Serial) 状态不是 device：$($selectedLine[0])" }

Write-SoakLog "设备 $($script:Serial)，包 $Package，计划 $DurationMinutes 分钟 / 每 $IntervalSeconds 秒采样"

$props = [ordered]@{}
foreach ($key in @('ro.product.manufacturer', 'ro.product.model', 'ro.build.version.release', 'ro.build.version.sdk', 'ro.product.cpu.abi')) {
    $props[$key] = (Invoke-AdbText -AdbArgs @('shell', 'getprop', $key) -AllowFailure).Trim()
}
$wmSize = (Invoke-AdbText -AdbArgs @('shell', 'wm', 'size') -AllowFailure).Trim()
$wmDensity = (Invoke-AdbText -AdbArgs @('shell', 'wm', 'density') -AllowFailure).Trim()
$memInfo = Invoke-AdbText -AdbArgs @('shell', 'cat', '/proc/meminfo') -AllowFailure
$memTotal = Get-FirstNumber -Text $memInfo -Pattern 'MemTotal:\s*(\d+)\s*kB'
$packagePath = Invoke-AdbText -AdbArgs @('shell', 'pm', 'path', $Package) -AllowFailure
if (-not $packagePath -or $packagePath -match 'package not found|Error') {
    throw "设备上没有安装 $Package；请先安装被测包。"
}
$pidNow = (Invoke-AdbText -AdbArgs @('shell', 'pidof', $Package) -AllowFailure).Trim()
$runAsCheck = Invoke-AdbText -AdbArgs @('shell', "run-as $Package id") -AllowFailure

$preflight = @"
时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
设备：$($script:Serial)
厂商：$($props['ro.product.manufacturer'])
型号：$($props['ro.product.model'])
Android：$($props['ro.build.version.release']) / API $($props['ro.build.version.sdk'])
ABI：$($props['ro.product.cpu.abi'])
分辨率：$wmSize
密度：$wmDensity
MemTotal：$memTotal kB
包名：$Package
APK 路径：$packagePath
当前 PID：$(if ($pidNow) { $pidNow } else { '未运行' })
run-as：$(if ($runAsCheck -match 'uid=') { $runAsCheck } else { '不可用（release 包或非 debuggable）' })
备注：本脚本只采样，不启动投屏；开跑前请人工开始一次真实投屏。
"@
Set-Content -LiteralPath (Join-Path $OutDir 'preflight.txt') -Value $preflight -Encoding utf8
Write-SoakLog "预检完成：$($props['ro.product.model']) / API $($props['ro.build.version.sdk']) / $wmSize"

if ($DryRun) {
    Write-SoakLog 'DryRun：预检通过，不采样。'
    return
}

# ---- logcat ----
$logcatProcess = $null
if (-not $NoLogcat) {
    Invoke-AdbText -AdbArgs @('logcat', '-c') -AllowFailure | Out-Null
    $logcatArgs = @()
    if (-not [string]::IsNullOrWhiteSpace($script:Serial)) {
        $logcatArgs += @('-s', $script:Serial)
    }
    $logcatArgs += @('logcat', '-v', 'threadtime')
    $logcatProcess = Start-Process -FilePath $script:Adb -ArgumentList $logcatArgs `
        -RedirectStandardOutput (Join-Path $OutDir 'logcat.txt') `
        -RedirectStandardError (Join-Path $OutDir 'logcat.err.txt') `
        -WindowStyle Hidden -PassThru
    Write-SoakLog "logcat 已启动（pid $($logcatProcess.Id)）"
}

$samples = [System.Collections.Generic.List[object]]::new()
$sampleCount = [Math]::Floor(($DurationMinutes * 60) / $IntervalSeconds) + 1
$startedAt = Get-Date
$csvPath = Join-Path $OutDir 'samples.csv'

try {
    for ($i = 0; $i -lt $sampleCount; $i++) {
        $now = Get-Date
        $elapsed = [int]($now - $startedAt).TotalSeconds
        $pidText = (Invoke-AdbText -AdbArgs @('shell', 'pidof', $Package) -AllowFailure).Trim()
        $mem = Invoke-AdbText -AdbArgs @('shell', 'dumpsys', 'meminfo', $Package) -AllowFailure
        $cpu = Invoke-AdbText -AdbArgs @('shell', 'dumpsys', 'cpuinfo') -AllowFailure
        $battery = Invoke-AdbText -AdbArgs @('shell', 'dumpsys', 'battery') -AllowFailure

        Set-Content -LiteralPath (Join-Path $OutDir ('snapshots\sample-{0:D2}.txt' -f $i)) `
            -Value ("# {0}`n## meminfo`n{1}`n## cpuinfo`n{2}`n## battery`n{3}" -f $now.ToString('o'), $mem, $cpu, $battery) -Encoding utf8

        $pss = Get-FirstNumber -Text $mem -Pattern '(?m)^\s*TOTAL PSS:\s*([\d,]+)'
        $java = Get-FirstNumber -Text $mem -Pattern '(?m)^\s*Java Heap:\s*([\d,]+)'
        $native = Get-FirstNumber -Text $mem -Pattern '(?m)^\s*Native Heap:\s*([\d,]+)'
        $graphics = Get-FirstNumber -Text $mem -Pattern '(?m)^\s*Graphics:\s*([\d,]+)'

        $cpuLine = @($cpu -split "`n" | Where-Object { $_ -match [regex]::Escape($Package) } | Select-Object -First 1)
        $cpuPct = $null
        if ($cpuLine.Count -gt 0 -and $cpuLine[0] -match '([\d.]+)%') { $cpuPct = [double]$Matches[1] }

        $tempRaw = Get-FirstNumber -Text $battery -Pattern '(?m)^\s*temperature:\s*(\d+)'
        $tempC = if ($null -ne $tempRaw) { [Math]::Round($tempRaw / 10.0, 1) } else { $null }

        $samples.Add([pscustomobject]@{
            sample = $i
            timestamp = $now.ToString('o')
            elapsed_s = $elapsed
            pid = $pidText
            pss_total_kb = $pss
            java_heap_kb = $java
            native_heap_kb = $native
            graphics_kb = $graphics
            cpu_pct = $cpuPct
            battery_temp_c = $tempC
        })
        $summaryLine = '样本 {0}/{1}  t={2}s  PSS={3}  CPU={4}  Temp={5}' -f `
            $i, ($sampleCount - 1), $elapsed, $pss, $cpuPct, $tempC
        Write-SoakLog $summaryLine

        if ($i -lt ($sampleCount - 1)) {
            $nextAt = $startedAt.AddSeconds(($i + 1) * $IntervalSeconds)
            $remainMs = [int](($nextAt - (Get-Date)).TotalMilliseconds)
            if ($remainMs -gt 0) { Start-Sleep -Milliseconds $remainMs }
        }
    }
}
finally {
    if ($samples.Count -gt 0) {
        $samples | Export-Csv -LiteralPath $csvPath -NoTypeInformation -Encoding utf8
        Write-SoakLog "采样写入 $csvPath（$($samples.Count) 行）"
    }
    if ($null -ne $logcatProcess -and -not $logcatProcess.HasExited) {
        Stop-Process -Id $logcatProcess.Id -Force -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 300
        Write-SoakLog 'logcat 已停止'
    }
}

# ---- perf JSONL ----
$perfPath = Join-Path $OutDir 'perf.jsonl'
$perfNote = Join-Path $OutDir 'perf-pull.txt'
if ($SkipPerfPull) {
    Set-Content -LiteralPath $perfNote -Value 'SkipPerfPull：未拉取 perf JSONL。' -Encoding utf8
}
else {
    $perfFiles = @(Get-PerfFiles)
    if ($perfFiles.Count -eq 0) {
        Set-Content -LiteralPath $perfNote -Value '未找到 files/perf/*.jsonl，或 run-as 不可用。' -Encoding utf8
        Write-SoakLog 'perf 拉取：无文件或 run-as 不可用'
    }
    else {
        $builder = [System.Text.StringBuilder]::new()
        foreach ($name in $perfFiles) {
            $chunk = Invoke-AdbText -AdbArgs @('exec-out', "run-as $Package cat files/perf/$name") -AllowFailure
            [void]$builder.AppendLine($chunk)
            Write-SoakLog "perf 拉取：$name"
        }
        Set-Content -LiteralPath $perfPath -Value $builder.ToString().TrimEnd() -Encoding utf8
        Write-SoakLog "perf 写入 $perfPath（$($perfFiles.Count) 个文件）"
    }
}

# ---- 汇总 ----
$summaryPath = Join-Path $OutDir 'summary.json'
$summaryMdPath = Join-Path $OutDir 'summary.md'
$python = Get-Command python -ErrorAction SilentlyContinue
if ($null -ne $python) {
    $args = @((Join-Path $PSScriptRoot 'summarize_soak.py'), '--samples', $csvPath, '--out', $summaryPath, '--markdown', $summaryMdPath, '--label', $Label)
    if (Test-Path -LiteralPath $perfPath) {
        $args += @('--perf', $perfPath)
    }
    & $python.Source @args
    if ($LASTEXITCODE -ne 0) { throw "summarize_soak.py 失败（exit $LASTEXITCODE）" }
    Write-SoakLog "汇总写入 $summaryPath / $summaryMdPath"
}
else {
    Write-SoakLog '未找到 python，跳过 summary；samples.csv 与 perf.jsonl 仍可直接分析。'
}

Write-SoakLog "完成。证据目录：$OutDir"
