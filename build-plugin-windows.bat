@echo off
setlocal EnableExtensions DisableDelayedExpansion
chcp 65001 >nul
title LUMQERA - Build LumqeraCore RC11
cd /d "%~dp0"
set "ROOT=%CD%"
set "PROJECT=%ROOT%\server\LumqeraCore"
set "LOG=%ROOT%\lumqera-build.log"
set "JDK_HOME="
set "GRADLE_CMD="
set "GRADLE_VERSION=9.1.0"

> "%LOG%" echo LUMQERA RC11 build diagnostics
>> "%LOG%" echo Project: %PROJECT%
echo [LUMQERA] Build LumqeraCore 1.0.0-RC11
echo [INFO] Log: "%LOG%"
if not exist "%PROJECT%\build.gradle.kts" goto no_source
if not exist "%PROJECT%\src\main\resources\plugin.yml" goto no_source

rem Look for a *real JDK home*, not Oracle's javapath shim directory.
if defined JDK25_HOME call :check_home "%JDK25_HOME%"
if not defined JDK_HOME if defined JAVA_HOME call :check_home "%JAVA_HOME%"
if defined ProgramFiles for /d %%D in ("%ProgramFiles%\Java\jdk-25*") do if not defined JDK_HOME call :check_home "%%~fD"
if defined ProgramFiles for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-25*") do if not defined JDK_HOME call :check_home "%%~fD"
if defined ProgramFiles for /d %%D in ("%ProgramFiles%\Microsoft\jdk-25*") do if not defined JDK_HOME call :check_home "%%~fD"
if defined ProgramFiles for /d %%D in ("%ProgramFiles%\BellSoft\LibericaJDK-25*") do if not defined JDK_HOME call :check_home "%%~fD"
if not defined JDK_HOME for /f "delims=" %%J in ('where javac 2^>nul') do if not defined JDK_HOME for %%K in ("%%~dpJ..") do call :check_home "%%~fK"
if not defined JDK_HOME goto no_jdk

set "JAVA_HOME=%JDK_HOME%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo [OK] JDK 25: "%JAVA_HOME%"
>> "%LOG%" echo JAVA_HOME=%JAVA_HOME%
"%JAVA_HOME%\bin\java.exe" -version >> "%LOG%" 2>&1
"%JAVA_HOME%\bin\javac.exe" -version >> "%LOG%" 2>&1

rem Use project wrapper if complete, system Gradle, or cached private copy.
if exist "%PROJECT%\gradlew.bat" if exist "%PROJECT%\gradle\wrapper\gradle-wrapper.jar" set "GRADLE_CMD=%PROJECT%\gradlew.bat"
if not defined GRADLE_CMD for /f "delims=" %%G in ('where gradle.bat 2^>nul') do if not defined GRADLE_CMD set "GRADLE_CMD=%%G"
if not defined GRADLE_CMD if exist "%LOCALAPPDATA%\LUMQERA\gradle-%GRADLE_VERSION%\bin\gradle.bat" set "GRADLE_CMD=%LOCALAPPDATA%\LUMQERA\gradle-%GRADLE_VERSION%\bin\gradle.bat"
if not defined GRADLE_CMD goto offer_gradle

goto do_build

:offer_gradle
echo.
echo [MISSING] Gradle 9.1.0 or newer is required to build with Java 25.
echo [INFO] Gradle can be downloaded from official services.gradle.org into LocalAppData.
echo [INFO] No administrator rights or changes to PATH are required.
choice /C YN /N /M "Download and install Gradle 9.1.0 now? [Y/N] "
if errorlevel 2 goto no_gradle
if errorlevel 1 goto download_gradle
goto no_gradle

:download_gradle
echo [INFO] Downloading and verifying official Gradle 9.1.0 distribution.
echo [INFO] The download may be large. Output is appended to the build log.
rem The command downloads Gradle and its official SHA-256 checksum, verifies the archive,
rem then unzips it to LocalAppData. It does not download any other software.
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; $v='9.1.0'; $base=Join-Path $env:LOCALAPPDATA 'LUMQERA'; New-Item -ItemType Directory -Path $base -Force | Out-Null; $zip=Join-Path $base ('gradle-'+$v+'-bin.zip'); $sha=Join-Path $base ('gradle-'+$v+'-bin.zip.sha256'); $url='https://services.gradle.org/distributions/gradle-'+$v+'-bin.zip'; Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $zip; Invoke-WebRequest -UseBasicParsing -Uri ($url+'.sha256') -OutFile $sha; $expected=([regex]::Match((Get-Content -Raw $sha),'[a-fA-F0-9]{64}')).Value; if(-not $expected) { throw 'Official Gradle SHA256 is invalid' }; $actual=(Get-FileHash $zip -Algorithm SHA256).Hash; if($expected -ine $actual) { throw 'Gradle SHA256 mismatch' }; Expand-Archive -LiteralPath $zip -DestinationPath $base -Force; Remove-Item -LiteralPath $zip,$sha -Force; if(-not (Test-Path (Join-Path $base ('gradle-'+$v+'\bin\gradle.bat')))) { throw 'Gradle executable was not extracted' }" >> "%LOG%" 2>&1
if errorlevel 1 goto download_failed
set "GRADLE_CMD=%LOCALAPPDATA%\LUMQERA\gradle-%GRADLE_VERSION%\bin\gradle.bat"

:do_build
echo [INFO] Gradle executable: "%GRADLE_CMD%"
>> "%LOG%" echo Gradle executable: %GRADLE_CMD%
pushd "%PROJECT%"
if errorlevel 1 goto no_source
echo [INFO] Checking Gradle...
call "%GRADLE_CMD%" --version >> "%LOG%" 2>&1
if errorlevel 1 goto gradle_failed
echo [INFO] Assembling LumqeraCore JAR. Full tests run in GitHub Actions.
call "%GRADLE_CMD%" clean assemble --no-daemon --console=plain --stacktrace >> "%LOG%" 2>&1
set "BUILD_EXIT=%ERRORLEVEL%"
if not "%BUILD_EXIT%"=="0" goto build_failed
set "OUTPUT=%PROJECT%\build\libs\LumqeraCore-1.0.0-RC11.jar"
if not exist "%OUTPUT%" goto jar_missing
popd
echo.
echo [SUCCESS] JAR built: "%OUTPUT%"
echo [NEXT] Stop Paper first, then run deploy-plugin-windows.bat.
echo [INFO] Build log: "%LOG%"
goto success

:check_home
set "CANDIDATE_HOME=%~1"
if not exist "%CANDIDATE_HOME%\bin\java.exe" exit /b 1
if not exist "%CANDIDATE_HOME%\bin\javac.exe" exit /b 1
"%CANDIDATE_HOME%\bin\javac.exe" -version 2>&1 | findstr /R /C:"^javac 25" >nul
if errorlevel 1 exit /b 1
set "JDK_HOME=%CANDIDATE_HOME%"
exit /b 0

:no_source
echo [ERROR] Java plugin sources not found in "%PROJECT%".
echo [HINT] Place this BAT beside the 'server' folder in your extracted project.
goto failure
:no_jdk
echo [ERROR] Real JDK 25 installation directory not found.
echo [HINT] Set JDK25_HOME to the JDK 25 folder containing bin\javac.exe.
echo [HINT] Do NOT set it to Common Files\Oracle\Java (javapath shim).
>> "%LOG%" echo ERROR: JDK 25 root not found
goto failure
:no_gradle
echo [ERROR] Gradle is missing; installation was declined.
echo [HINT] Alternative: winget install Gradle.Gradle
>> "%LOG%" echo ERROR: Gradle not found
goto failure
:download_failed
echo [ERROR] Gradle download or checksum validation failed.
echo [HINT] Check internet access, antivirus, and "%LOG%".
echo [HINT] Alternative: winget install Gradle.Gradle
>> "%LOG%" echo ERROR: Gradle download failed
goto failure
:gradle_failed
echo [ERROR] Gradle failed to start. See "%LOG%".
goto failure
:build_failed
echo [ERROR] Gradle build failed with exit code %BUILD_EXIT%.
echo [HINT] The last log entries follow:
powershell.exe -NoProfile -Command "Get-Content -LiteralPath '%LOG%' -Tail 40"
goto failure
:jar_missing
echo [ERROR] Gradle succeeded but LumqeraCore-1.0.0-RC11.jar was not found.
echo [CHECK] "%PROJECT%\build\libs"
if exist "%PROJECT%\build\libs" dir /b "%PROJECT%\build\libs"
goto failure
:failure
echo.
echo [NEXT] Send "%LOG%" if the problem persists.
pause
exit /b 1
:success
echo.
pause
exit /b 0
