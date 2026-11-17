@echo off
setlocal EnableExtensions DisableDelayedExpansion
chcp 65001 >nul
title LUMQERA - Build LumqeraCore RC6

rem This file is self-contained: it needs no PowerShell helpers.
cd /d "%~dp0"
set "ROOT=%CD%"
set "PROJECT=%ROOT%\server\LumqeraCore"
set "LOG=%ROOT%\lumqera-build.log"
set "JDK_BIN="
set "GRADLE_CMD="

> "%LOG%" echo LUMQERA RC6 build diagnostics
>> "%LOG%" echo Project: %PROJECT%
echo [LUMQERA] Building LumqeraCore 1.0.0-RC6
echo [INFO] Log: "%LOG%"

if not exist "%PROJECT%\build.gradle.kts" goto no_source
if not exist "%PROJECT%\src\main\resources\plugin.yml" goto no_source

rem JDK25_HOME is optional; JAVA_HOME or Windows installed JDKs also work.
if defined JDK25_HOME call :check_jdk "%JDK25_HOME%\bin"
if not defined JDK_BIN if defined JAVA_HOME call :check_jdk "%JAVA_HOME%\bin"
for /f "delims=" %%J in ('where javac 2^>nul') do if not defined JDK_BIN call :check_jdk "%%~dpJ"
if defined ProgramFiles for /d %%D in ("%ProgramFiles%\Java\jdk-25*") do if not defined JDK_BIN call :check_jdk "%%~fD\bin"
if defined ProgramFiles for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-25*") do if not defined JDK_BIN call :check_jdk "%%~fD\bin"
if defined ProgramFiles for /d %%D in ("%ProgramFiles%\Microsoft\jdk-25*") do if not defined JDK_BIN call :check_jdk "%%~fD\bin"
if defined ProgramFiles for /d %%D in ("%ProgramFiles%\BellSoft\LibericaJDK-25*") do if not defined JDK_BIN call :check_jdk "%%~fD\bin"
if not defined JDK_BIN goto no_jdk

for %%J in ("%JDK_BIN%\..") do set "JAVA_HOME=%%~fJ"
set "PATH=%JDK_BIN%;%PATH%"
echo [OK] JDK 25: "%JAVA_HOME%"
"%JDK_BIN%\java.exe" -version
"%JDK_BIN%\javac.exe" -version
>> "%LOG%" echo JAVA_HOME=%JAVA_HOME%

rem Prefer a complete Gradle wrapper when present; otherwise use installed Gradle.
if exist "%PROJECT%\gradlew.bat" set "GRADLE_CMD=%PROJECT%\gradlew.bat"
if not defined GRADLE_CMD if exist "%ROOT%\gradlew.bat" set "GRADLE_CMD=%ROOT%\gradlew.bat"
if not defined GRADLE_CMD for /f "delims=" %%G in ('where gradle.bat 2^>nul') do if not defined GRADLE_CMD set "GRADLE_CMD=%%G"
if not defined GRADLE_CMD for /f "delims=" %%G in ('where gradle.cmd 2^>nul') do if not defined GRADLE_CMD set "GRADLE_CMD=%%G"
if not defined GRADLE_CMD goto no_gradle

pushd "%PROJECT%"
if errorlevel 1 goto no_source

echo [INFO] Checking Gradle...
call "%GRADLE_CMD%" --version >> "%LOG%" 2>&1
if errorlevel 1 goto gradle_failed

echo [INFO] Compiling. The first build may download Paper API dependencies.
echo [INFO] Build details will be saved to "%LOG%".
call "%GRADLE_CMD%" clean build --no-daemon --console=plain --stacktrace >> "%LOG%" 2>&1
set "BUILD_EXIT=%ERRORLEVEL%"
if not "%BUILD_EXIT%"=="0" goto build_failed

set "OUTPUT=%PROJECT%\build\libs\LumqeraCore-1.0.0-RC6.jar"
if not exist "%OUTPUT%" goto jar_missing
popd
echo.
echo [SUCCESS] Plugin built: "%OUTPUT%"
echo [NEXT] Stop Paper and run deploy-plugin-windows.bat.
echo [INFO] Log: "%LOG%"
goto success

:no_source
echo [ERROR] Plugin sources not found at "%PROJECT%".
echo [HINT] Place this BAT in the LUMQERA project ROOT, next to the server folder.
goto failure
:no_jdk
echo [ERROR] JDK 25 with javac.exe was not found.
echo [HINT] Set JDK25_HOME to your JDK 25 installation folder, or fix JAVA_HOME.
echo [HINT] Check: javac -version
>> "%LOG%" echo ERROR: JDK 25 not found
goto failure
:no_gradle
echo [ERROR] Gradle is not installed/on PATH, and no gradlew.bat was found.
echo [HINT] Install Gradle 9.x, then reopen the terminal and run: gradle --version
>> "%LOG%" echo ERROR: Gradle not found
goto failure
:gradle_failed
echo [ERROR] Gradle could not start. Showing log:
type "%LOG%"
goto failure
:build_failed
echo [ERROR] Gradle build failed (exit code %BUILD_EXIT%). Showing log:
type "%LOG%"
goto failure
:jar_missing
echo [ERROR] Gradle finished but the expected JAR is missing.
echo [CHECK] "%PROJECT%\build\libs"
if exist "%PROJECT%\build\libs" dir /b "%PROJECT%\build\libs"
type "%LOG%"
goto failure
:failure
echo.
echo [NEXT] Send lumqera-build.log if the error persists.
echo [LOG] "%LOG%"
pause
exit /b 1
:success
echo.
pause
exit /b 0

:check_jdk
set "CANDIDATE_BIN=%~1"
if not exist "%CANDIDATE_BIN%\java.exe" exit /b 1
if not exist "%CANDIDATE_BIN%\javac.exe" exit /b 1
"%CANDIDATE_BIN%\javac.exe" -version 2>&1 | findstr /b /c:"javac 25" >nul
if errorlevel 1 exit /b 1
set "JDK_BIN=%CANDIDATE_BIN%"
exit /b 0
