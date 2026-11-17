# LUMQERA RC6 - local readiness report, no network calls and no modifications.
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$problems = [System.Collections.Generic.List[string]]::new()
function Check([bool]$condition, [string]$message, [string]$help) {
    if ($condition) { Write-Host "[OK] $message" -ForegroundColor Green }
    else {
        Write-Host "[NOT READY] $message" -ForegroundColor Yellow
        $script:problems.Add($help)
    }
}
Write-Host "LUMQERA v1.0.0-RC6 - environment readiness" -ForegroundColor Cyan
$javaCmd = Get-Command java -ErrorAction SilentlyContinue
$javaLine = ''
if ($javaCmd) {
    $javaText = & java -version 2>&1 | Out-String
    $javaLine = $javaText
}
Check ($javaLine -match 'version\s+"25(?:[.\+\-"]|$)') 'Java 25 runtime' 'Install JDK 25 and verify java -version.'
Check ([bool](Get-Command javac -ErrorAction SilentlyContinue)) 'Java compiler (JDK)' 'Add JDK 25 bin folder to PATH.'
Check ([bool](Get-Command gradle -ErrorAction SilentlyContinue)) 'Gradle on PATH' 'Install Gradle 9.x or run build through a configured IDE.'
$paper = Join-Path $root 'server\paper\paper.jar'
$plugin = Join-Path $root 'server\paper\plugins\LumqeraCore.jar'
Check (Test-Path $paper -PathType Leaf) 'Paper server JAR present' 'Download official Paper 26.3 and place it as server\paper\paper.jar.'
Check (Test-Path $plugin -PathType Leaf) 'LumqeraCore deployed' 'Run build-plugin-windows.bat and deploy-plugin-windows.bat.'
$eula = Join-Path $root 'server\paper\eula.txt'
$accepted = $false
if (Test-Path $eula -PathType Leaf) {
    $accepted = [bool](Select-String -Path $eula -Pattern '^\s*eula=true\s*$' -Quiet)
}
Check $accepted 'Minecraft EULA manually accepted' 'Read the EULA, start Paper once, then set eula=true yourself in eula.txt only if you agree.'
$props = Join-Path $root 'server\paper\server.properties'
if (Test-Path $props -PathType Leaf) {
    $contents = Get-Content $props
    Check ([bool]($contents -match '^online-mode=true\s*$')) 'Online authentication enabled' 'Set online-mode=true in server.properties.'
    Check ([bool]($contents -match '^white-list=true\s*$')) 'Closed testing whitelist enabled' 'Set white-list=true for private testing.'
} else {
    Check $false 'server.properties generated' 'Start Paper once to generate server.properties, then enable online-mode and whitelist.'
}
Write-Host ''
if ($problems.Count) {
    Write-Host "Not ready: $($problems.Count) requirement(s) outstanding" -ForegroundColor Yellow
    foreach ($message in $problems) { Write-Host " - $message" }
    exit 1
}
Write-Host 'Basic environment checks passed. This is NOT proof the plugin runs correctly.' -ForegroundColor Green
exit 0
