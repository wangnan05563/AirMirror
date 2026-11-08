# Byte-level and smoke checks for the launcher scripts.
# Byte rules: launchers (.bat) must be pure ASCII, CRLF, no BOM.
# Logic scripts (.ps1) must be pure ASCII, CRLF, no BOM.
# Smoke rule: every launcher exits 0 when invoked with the configured help arg.

param(
    [switch]$SkipSmoke
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $PSScriptRoot 'android-tools.config.json'
if (-not (Test-Path $configPath)) { Write-Host "[FAIL] config not found: $configPath"; exit 1 }
$config = Get-Content $configPath -Raw -Encoding UTF8 | ConvertFrom-Json

$failures = 0
function Report([bool]$ok, [string]$name, [string]$detail) {
    if ($ok) {
        Write-Host ("[ OK ] {0}: {1}" -f $name, $detail) -ForegroundColor Green
    } else {
        Write-Host ("[FAIL] {0}: {1}" -f $name, $detail) -ForegroundColor Red
        $script:failures++
    }
}

function Test-Bytes([string]$rel) {
    $path = Join-Path $root $rel
    if (-not (Test-Path $path)) { Report $false $rel 'file missing'; return }
    $bytes = [System.IO.File]::ReadAllBytes($path)
    $hasBom = ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)
    $nonAscii = 0
    $loneLf = 0
    $crlf = 0
    for ($i = 0; $i -lt $bytes.Length; $i++) {
        if ($bytes[$i] -gt 0x7F) { $nonAscii++ }
        if ($bytes[$i] -eq 10) {
            if ($i -gt 0 -and $bytes[$i - 1] -eq 13) { $crlf++ } else { $loneLf++ }
        }
    }
    $ok = (-not $hasBom) -and ($nonAscii -eq 0) -and ($loneLf -eq 0) -and ($crlf -gt 0)
    $detail = "bytes=$($bytes.Length) bom=$hasBom nonAscii=$nonAscii loneLf=$loneLf crlf=$crlf"
    Report $ok $rel $detail
}

foreach ($rel in $config.launchers) { Test-Bytes $rel }
foreach ($rel in $config.logicScripts) { Test-Bytes $rel }

if (-not $SkipSmoke) {
    foreach ($rel in $config.launchers) {
        $path = Join-Path $root $rel
        if (-not (Test-Path $path)) { continue }
        $arguments = @('/c', ('"{0}" {1}' -f $path, $config.smokeArgs))
        $process = Start-Process -FilePath $env:ComSpec -ArgumentList $arguments -NoNewWindow -Wait -PassThru
        Report ($process.ExitCode -eq 0) "$rel (smoke $($config.smokeArgs))" "exit=$($process.ExitCode)"
    }
}

if ($failures -gt 0) {
    Write-Host "[FAIL] $failures check(s) failed" -ForegroundColor Red
    exit 1
}
Write-Host '[DONE] launcher checks passed' -ForegroundColor Green
exit 0
