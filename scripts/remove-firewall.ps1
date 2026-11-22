#requires -Version 5.1
<#
.SYNOPSIS
    Remove AirMirror PC firewall rules by fixed DisplayName.

.DESCRIPTION
    Called by uninstall.ps1 before the program directory is deleted.
    Missing rules are not an error; no-admin returns exit 5 and lets uninstall continue.
#>
param(
    [switch]$Silent,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$names = @(
    'AirMirror PC (TCP 41523)',
    'AirMirror PC (UDP 41524-41525)'
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

if ($DryRun) {
    $names | ForEach-Object { Write-Output "WOULD_REMOVE name=$_" }
    exit 0
}

if (-not (Test-Admin)) {
    Write-Output 'ADMIN=false'
    Write-Output 'DETAIL=需要管理员权限才能删除防火墙规则'
    exit 5
}

$removed = 0
foreach ($name in $names) {
    try {
        $rules = @(Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue)
        foreach ($rule in $rules) {
            Remove-NetFirewallRule -Name $rule.Name -ErrorAction Stop
            $removed++
        }
        if (-not $Silent) { Write-Host "[OK] 已清理 $name" -ForegroundColor Green }
    } catch {
        Write-Output "DETAIL=${name}: $($_.Exception.Message)"
    }
}

Write-Output "REMOVED=$removed"
exit 0
