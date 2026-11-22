#requires -Version 5.1
<#
.SYNOPSIS
    Idempotently create AirMirror PC private-network firewall rules.

.DESCRIPTION
    Rules:
      AirMirror PC (TCP 41523)       inbound TCP 41523
      AirMirror PC (UDP 41524-41525) inbound UDP 41524-41525
    Only the Private profile is allowed; Public/Domain are untouched.
    -Check is read-only and is consumed by the first-run wizard.
    -DryRun validates names/parameters without creating anything.
#>
param(
    [switch]$Check,
    [switch]$DryRun,
    [switch]$Silent
)

$ErrorActionPreference = 'Stop'

$rules = @(
    [ordered]@{ Name = 'AirMirror PC (TCP 41523)'; Protocol = 'TCP'; Port = '41523' },
    [ordered]@{ Name = 'AirMirror PC (UDP 41524-41525)'; Protocol = 'UDP'; Port = '41524-41525' }
)

function Test-Admin {
    try {
        $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
        $principal = New-Object Security.Principal.WindowsPrincipal($identity)
        return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    } catch {
        return $false
    }
}

function Get-RuleCount([string]$name) {
    try {
        return @(Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue |
            Where-Object { $_.Enabled -eq 'True' }).Count
    } catch {
        return 0
    }
}

$admin = Test-Admin
if ($Check) {
    $count = 0
    foreach ($rule in $rules) { $count += Get-RuleCount $rule.Name }
    Write-Output "ADMIN=$($admin.ToString().ToLowerInvariant())"
    Write-Output "RULES=$count"
    if ($count -lt $rules.Count) {
        Write-Output 'DETAIL=缺少 AirMirror 专用网络入站规则'
    } else {
        Write-Output 'DETAIL=AirMirror 专用网络规则已存在'
    }
    exit 0
}

if ($DryRun) {
    Write-Output 'DRYRUN=true'
    foreach ($rule in $rules) {
        Write-Output ("WOULD_CREATE name={0} direction=Inbound protocol={1} localPort={2} profile=Private" -f $rule.Name, $rule.Protocol, $rule.Port)
    }
    exit 0
}

if (-not $admin) {
    Write-Output 'ADMIN=false'
    Write-Output 'DETAIL=需要管理员权限才能创建防火墙规则'
    exit 5
}

$failed = $false
foreach ($rule in $rules) {
    try {
        $existing = @(Get-NetFirewallRule -DisplayName $rule.Name -ErrorAction SilentlyContinue)
        if ($existing.Count -gt 0) {
            foreach ($e in $existing) { Remove-NetFirewallRule -Name $e.Name -ErrorAction Stop }
        }
        New-NetFirewallRule `
            -DisplayName $rule.Name `
            -Direction Inbound `
            -Protocol $rule.Protocol `
            -LocalPort $rule.Port `
            -Action Allow `
            -Profile Private `
            -Enabled True `
            -Description 'AirMirror PC local casting (Private network only)' | Out-Null
        if (-not $Silent) { Write-Host "[OK] $($rule.Name)" -ForegroundColor Green }
    } catch {
        $failed = $true
        Write-Output "DETAIL=$($rule.Name): $($_.Exception.Message)"
    }
}

$count = 0
foreach ($rule in $rules) { $count += Get-RuleCount $rule.Name }
Write-Output "ADMIN=true"
Write-Output "RULES=$count"
if ($failed -or $count -lt $rules.Count) { exit 1 }
Write-Output 'DETAIL=AirMirror 专用网络规则已创建'
exit 0
