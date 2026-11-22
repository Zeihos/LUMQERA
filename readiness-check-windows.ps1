# LUMQERA RC13 - local readiness report (no network calls or writes).
$ErrorActionPreference = 'Continue'
$root = $PSScriptRoot
$problems = [System.Collections.Generic.List[string]]::new()
function Check([bool]$condition, [string]$message, [string]$help) {
    if ($condition) { Write-Host "[OK] $message" -ForegroundColor Green }
    else {
        Write-Host "[NOT READY] $message" -ForegroundColor Yellow
        $script:problems.Add($help)
    }
}
Write-Host 'LUMQERA 1.0.0-RC13 - Paper readiness' -ForegroundColor Cyan
$javaPaths = [System.Collections.Generic.List[string]]::new()
if ($env:JDK25_HOME) { $javaPaths.Add((Join-Path $env:JDK25_HOME 'bin\java.exe')) }
if ($env:JAVA_HOME) { $javaPaths.Add((Join-Path $env:JAVA_HOME 'bin\java.exe')) }
$pathJava = Get-Command java -ErrorAction SilentlyContinue
if ($pathJava) { $javaPaths.Add($pathJava.Source) }
$jdk25 = $false
foreach ($executable in $javaPaths) {
    try {
        if ($executable -ne 'java' -and -not (Test-Path -LiteralPath $executable -PathType Leaf)) { continue }
        $settings = (& $executable -XshowSettings:properties -version 2>&1 | Out-String)
        if ($settings -match '(?m)java\.specification\.version\s*=\s*25\s*$') { $jdk25 = $true; break }
    } catch { continue }
}
Check $jdk25 'Java 25 runtime available' 'Install JDK 25 or set JDK25_HOME to its installation directory.'
$jar = Join-Path $root 'server\paper\paper.jar'
$plugin = Join-Path $root 'server\paper\plugins\LumqeraCore.jar'
Check (Test-Path -LiteralPath $jar -PathType Leaf) 'Paper server JAR present' 'Download Paper 26.3 and save it as server\paper\paper.jar.'
Check (Test-Path -LiteralPath $plugin -PathType Leaf) 'LumqeraCore deployed' 'Build and deploy the plugin while Paper is stopped.'
$eula = Join-Path $root 'server\paper\eula.txt'
$accepted = $false
if (Test-Path -LiteralPath $eula -PathType Leaf) {
    $accepted = [bool](Select-String -LiteralPath $eula -Pattern '^\s*eula=true\s*$' -Quiet)
}
Check $accepted 'Minecraft EULA manually accepted' 'Read the Minecraft EULA and set eula=true only if you agree.'
$props = Join-Path $root 'server\paper\server.properties'
if (Test-Path -LiteralPath $props -PathType Leaf) {
    $lines = Get-Content -LiteralPath $props
    Check ([bool]($lines -match '^online-mode=true\s*$')) 'Online authentication enabled' 'Use online-mode=true in server.properties.'
    Check ([bool]($lines -match '^white-list=true\s*$')) 'Private test whitelist enabled' 'Use white-list=true during private testing.'
} else {
    Check $false 'server.properties exists' 'Start Paper once to generate server.properties, then check security settings.'
}
# Gradle is only needed to compile the plugin, not to run Paper.
$gradle = Get-Command gradle -ErrorAction SilentlyContinue
$cached = if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA 'LUMQERA\gradle-9.1.0\bin\gradle.bat' } else { '' }
if ($gradle -or ($cached -and (Test-Path -LiteralPath $cached -PathType Leaf))) {
    Write-Host '[INFO] Gradle available for local plugin builds.'
} else {
    Write-Host '[INFO] Gradle not installed; build-plugin-windows.bat can download it if needed.'
}
if ($problems.Count -gt 0) {
    Write-Host "Not ready: $($problems.Count) requirement(s)" -ForegroundColor Yellow
    foreach ($message in $problems) { Write-Host " - $message" }
    exit 1
}
Write-Host 'Basic readiness checks passed. Game mechanics still require a Paper server test.' -ForegroundColor Green
exit 0
