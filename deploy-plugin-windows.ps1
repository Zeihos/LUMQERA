# LUMQERA RC13 - checked, recoverable deployment for a stopped Paper server.
# Run via deploy-plugin-windows.bat, or supply Source/ServerDir for automation.
param(
    [string]$Source,
    [string]$ServerDir,
    [switch]$NonInteractive
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Source)) {
    $Source = Join-Path $PSScriptRoot 'server\LumqeraCore\build\libs\LumqeraCore-1.0.0-RC13.jar'
}
if ([string]::IsNullOrWhiteSpace($ServerDir)) {
    $ServerDir = Join-Path $PSScriptRoot 'server\paper'
}
$pending = $null
try {
    if (-not (Test-Path -LiteralPath $Source -PathType Leaf)) {
        throw "Built JAR not found: $Source. Run build-plugin-windows.bat first."
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $jar = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Source).Path)
    try {
        $entry = $jar.GetEntry('plugin.yml')
        if ($null -eq $entry) { throw 'JAR has no plugin.yml; this is not a valid Paper plugin.' }
        $reader = New-Object System.IO.StreamReader($entry.Open(), [System.Text.Encoding]::UTF8)
        try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if ($manifest -notmatch '(?m)^name:\s*LumqeraCore\s*$') {
            throw 'JAR has the wrong plugin name.'
        }
        if ($manifest -notmatch '(?m)^version:\s*.*1\.0\.0-RC13.*$') {
            throw 'JAR is not LumqeraCore 1.0.0-RC13; refusing to install an unexpected version.'
        }
    } finally { $jar.Dispose() }

    if (-not $NonInteractive) {
        Write-Host 'STOP the Paper server completely before deployment.' -ForegroundColor Yellow
        $answer = Read-Host 'Is the server fully stopped? Type YES to proceed'
        if ($answer -cne 'YES') { throw 'Cancelled; the existing plugin was not changed.' }
    }

    $plugins = Join-Path $ServerDir 'plugins'
    if (-not (Test-Path -LiteralPath $plugins -PathType Container)) {
        New-Item -ItemType Directory -Path $plugins -Force | Out-Null
    }
    $destination = Join-Path $plugins 'LumqeraCore.jar'
    $pending = Join-Path $plugins ('.lumqera-' + [guid]::NewGuid().ToString('N') + '.jar')
    Copy-Item -LiteralPath $Source -Destination $pending -ErrorAction Stop
    if ((Get-FileHash -LiteralPath $Source -Algorithm SHA256).Hash -ne
        (Get-FileHash -LiteralPath $pending -Algorithm SHA256).Hash) {
        throw 'JAR SHA-256 mismatch after copying; keeping the old plugin.'
    }

    if (Test-Path -LiteralPath $destination -PathType Leaf) {
        $stamp = Get-Date -Format 'yyyyMMdd-HHmmssfff'
        $backup = Join-Path $plugins ("LumqeraCore-$stamp-$PID.jar.bak")
        Copy-Item -LiteralPath $destination -Destination $backup -ErrorAction Stop
        Write-Host "[BACKUP] $backup" -ForegroundColor Cyan
    }
    Move-Item -LiteralPath $pending -Destination $destination -Force -ErrorAction Stop
    $pending = $null
    if ((Get-FileHash -LiteralPath $Source -Algorithm SHA256).Hash -ne
        (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash) {
        throw "Installed JAR checksum mismatch. Check the backup in $plugins."
    }
    Write-Host "[OK] Installed $destination" -ForegroundColor Green
} catch {
    Write-Error $_.Exception.Message
    exit 1
} finally {
    if ($pending -and (Test-Path -LiteralPath $pending -PathType Leaf)) {
        Remove-Item -LiteralPath $pending -Force -ErrorAction SilentlyContinue
    }
}
