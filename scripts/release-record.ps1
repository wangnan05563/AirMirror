#requires -Version 5.1
<#
.SYNOPSIS
    Generate a local, reproducible release record for AirMirror.

.DESCRIPTION
    Mirrors the release-note record written by MTask's one-click publish chain:
    a Markdown file plus a machine-readable JSON file are written before publishing.
    The Markdown body is also usable as the GitHub Release body.

    The record contains tag, repository, branch, commit, previous tag, artifact
    sizes/SHA-256 and the git log since the previous tag. It does not contact
    GitHub and does not require a token.
#>
param(
    [Parameter(Mandatory = $true)][string]$Root,
    [Parameter(Mandatory = $true)][string]$Tag,
    [Parameter(Mandatory = $true)][string]$Repo,
    [string]$Branch = '',
    [string]$Commit = '',
    [string[]]$Artifacts = @(),
    [string]$Notes = '',
    [string]$RecordDir = ''
)

$ErrorActionPreference = 'Stop'
$rootPath = [System.IO.Path]::GetFullPath($Root)
$git = 'git'

function Invoke-Git([string[]]$gitArgs) {
    $out = & $git -C $rootPath @gitArgs 2>$null
    return (($out | Out-String).Trim())
}

if ([string]::IsNullOrWhiteSpace($Branch)) {
    $Branch = Invoke-Git @('rev-parse', '--abbrev-ref', 'HEAD')
}
if ([string]::IsNullOrWhiteSpace($Commit)) {
    $Commit = Invoke-Git @('rev-parse', 'HEAD')
}
$previousTag = Invoke-Git @('describe', '--tags', '--abbrev=0')
if ($previousTag) {
    $changeLog = Invoke-Git @('log', "$previousTag..HEAD", '--pretty=format:- %s')
} else {
    $changeLog = Invoke-Git @('log', '-20', '--pretty=format:- %s')
}
if ([string]::IsNullOrWhiteSpace($changeLog)) {
    $changeLog = '- (没有新的提交)'
}

$safeTag = ($Tag -replace '[^A-Za-z0-9._-]', '_')
$recordDirPath = if ([string]::IsNullOrWhiteSpace($RecordDir)) {
    Join-Path $rootPath 'release\records'
} else {
    [System.IO.Path]::GetFullPath($RecordDir)
}
if (-not (Test-Path -LiteralPath $recordDirPath)) {
    New-Item -ItemType Directory -Path $recordDirPath -Force | Out-Null
}

$markdownPath = Join-Path $recordDirPath ($safeTag + '-release-notes.md')
$jsonPath = Join-Path $recordDirPath ($safeTag + '-release-record.json')

$artifactRecords = @()
foreach ($artifact in $Artifacts) {
    $full = [System.IO.Path]::GetFullPath($artifact)
    if (-not (Test-Path -LiteralPath $full)) {
        throw "artifact not found: $full"
    }
    $item = Get-Item -LiteralPath $full
    $artifactRecords += [ordered]@{
        name   = $item.Name
        path   = $full
        bytes  = [int64]$item.Length
        sha256 = (Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}

$body = $Notes.Trim()
if ([string]::IsNullOrWhiteSpace($body)) {
    $body = "## Changes`n`n$changeLog"
}

$createdAt = (Get-Date).ToString('o')
$markdown = @"
# AirMirror $Tag 发布记录

- 生成时间：$createdAt
- 仓库：$Repo
- 分支：$Branch
- 提交：$Commit
- 上一 tag：$(if ($previousTag) { $previousTag } else { '(无)' })
- 生成器：`scripts\release-record.ps1`（参考 MTask 一键发布的 release-notes 记录）

## 发布产物

$(($artifactRecords | ForEach-Object { '- `' + $_.name + '` — ' + $_.bytes + ' B — SHA-256 `' + $_.sha256 + '`' }) -join "`n")

$body
"@

$bytes = [System.Text.Encoding]::UTF8.GetBytes($markdown)
$utf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($markdownPath, $markdown, $utf8)

$record = [ordered]@{
    schemaVersion = 1
    tag           = $Tag
    repo          = $Repo
    branch        = $Branch
    commit        = $Commit
    previousTag   = $previousTag
    createdAt     = $createdAt
    notesFile     = $markdownPath
    artifacts     = $artifactRecords
}
$json = $record | ConvertTo-Json -Depth 6
[System.IO.File]::WriteAllText($jsonPath, $json, $utf8)

[pscustomobject]@{
    Markdown = $markdownPath
    Json     = $jsonPath
}
