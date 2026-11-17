# LUMQERA offline data backup. Does NOT stop the server or modify its state.
$ErrorActionPreference = 'Stop'
$server = Join-Path $PSScriptRoot 'server\paper'
$backups = Join-Path $PSScriptRoot 'backups'
if (-not (Test-Path -LiteralPath $server -PathType Container)) {
    Write-Error 'server\paper folder is missing.'
    exit 1
}
Write-Host 'STOP the Paper server before making a backup. Backup is not safe during active writes.' -ForegroundColor Yellow
$answer = Read-Host 'Is the server fully stopped? Type YES to proceed'
if ($answer -cne 'YES') {
    Write-Host 'Cancelled: no files were copied.'
    exit 1
}
# Paths are relative to server/paper; keep structure on restore.
$items = @('world', 'world_nether', 'world_the_end', 'plugins\LumqeraCore',
           'server.properties', 'bukkit.yml', 'spigot.yml', 'config',
           'whitelist.json', 'ops.json', 'banned-players.json', 'banned-ips.json')
$present = @($items | Where-Object { Test-Path -LiteralPath (Join-Path $server $_) })
if ($present.Count -eq 0) {
    Write-Error 'No game worlds, plugin data, or server config found. Nothing to backup.'
    exit 1
}
New-Item -ItemType Directory -Force -Path $backups | Out-Null
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$dest = Join-Path $backups "lumqera-backup-$timestamp.zip"
if (Test-Path -LiteralPath $dest) {
    Write-Error "Archive $dest already exists; not overwriting."
    exit 1
}
$temp = Join-Path ([IO.Path]::GetTempPath()) ([IO.Path]::GetRandomFileName())
try {
    New-Item -ItemType Directory -Path $temp | Out-Null
    foreach ($rel in $present) {
        $source = Join-Path $server $rel
        $target = Join-Path $temp $rel
        $parent = Split-Path -Parent $target
        if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
        Copy-Item -LiteralPath $source -Destination $target -Recurse -Force
    }
    Compress-Archive -Path (Join-Path $temp '*') -DestinationPath $dest -CompressionLevel Optimal
    if (-not (Test-Path -LiteralPath $dest) -or (Get-Item -LiteralPath $dest).Length -lt 100) {
        throw 'Backup archive is missing or unexpectedly small.'
    }
    Write-Host "Backup created: $dest" -ForegroundColor Green
    Write-Host 'Private file: contains server configuration and possibly player data. Do not publish.'
} catch {
    if (Test-Path -LiteralPath $dest) { Remove-Item -LiteralPath $dest -Force }
    Write-Error $_
    exit 1
} finally {
    if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
}
