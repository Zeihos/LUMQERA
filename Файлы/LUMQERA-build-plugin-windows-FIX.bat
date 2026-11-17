@echo off
setlocal EnableExtensions
chcp 65001 >nul
title LUMQERA - Build LumqeraCore RC6
cd /d "%~dp0"
set "LOG=%~dp0lumqera-build.log"
echo [LUMQERA] Build LumqeraCore 1.0.0-RC6
echo [INFO] Build log: "%LOG%"
echo [INFO] Checking Java 25 and Gradle...
if not exist "%~dp0find-java25-windows.ps1" goto missing_helper
if not exist "%~dp0server\LumqeraCore\build.gradle.kts" goto missing_source
set "JAVA25_EXE="
for /f "usebackq delims=" %%J in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0find-java25-windows.ps1"`) do if not defined JAVA25_EXE set "JAVA25_EXE=%%J"
if not defined JAVA25_EXE goto missing_jdk
for %%J in ("%JAVA25_EXE%") do for %%H in ("%%~dpJ..") do set "JAVA_HOME=%%~fH"
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo [OK] JDK: "%JAVA25_EXE%"
"%JAVA25_EXE%" -version
where gradle >nul 2>nul
if errorlevel 1 goto missing_gradle
cd /d "%~dp0server\LumqeraCore"
if errorlevel 1 goto missing_source
> "%LOG%" echo LUMQERA RC6 plugin build
>> "%LOG%" echo Java: %JAVA25_EXE%
call gradle --version >> "%LOG%" 2>&1
if errorlevel 1 goto gradle_failed

echo [INFO] Building plugin; the first run may download dependencies.
echo [INFO] Output will be printed here after Gradle finishes.
rem IMPORTANT: gradle on Windows is a .bat file. Use CALL so we return here.
call gradle clean build --no-daemon --stacktrace --console=plain >> "%LOG%" 2>&1
set "BUILD_EXIT=%ERRORLEVEL%"
type "%LOG%"
if not "%BUILD_EXIT%"=="0" goto build_failed
if not exist "build\libs\LumqeraCore-1.0.0-RC6.jar" goto jar_missing
echo.
echo [SUCCESS] LumqeraCore JAR has been built:
echo "%CD%\build\libs\LumqeraCore-1.0.0-RC6.jar"
echo [NEXT] Stop Paper, then run deploy-plugin-windows.bat
cd /d "%~dp0"
goto finish_ok

:missing_helper
echo [ERROR] find-java25-windows.ps1 is missing. Extract the full ZIP archive.
goto finish_error
:missing_source
echo [ERROR] server\LumqeraCore\build.gradle.kts is missing. Extract the full ZIP archive.
goto finish_error
:missing_jdk
echo [ERROR] JDK 25 was not detected. Ensure javac.exe is installed with Java 25.
echo [HINT] Check JAVA_HOME and your PATH.
goto finish_error
:missing_gradle
echo [ERROR] Gradle is not on PATH. Install Gradle 9.x and reopen the terminal.
echo [DOC] https://docs.gradle.org/current/userguide/installation.html
echo [CHECK] In cmd.exe: gradle --version
goto finish_error
:gradle_failed
echo [ERROR] Gradle could not start. Output:
type "%LOG%"
goto finish_error
:build_failed
echo.
echo [ERROR] Gradle build failed, exit code: %BUILD_EXIT%.
echo [LOG] "%LOG%"
goto finish_error
:jar_missing
echo [ERROR] Gradle reported success but expected JAR was not found.
echo [INFO] Check: server\LumqeraCore\build\libs\
echo [LOG] "%LOG%"
goto finish_error
:finish_error
echo.
echo [NEXT] Copy the build error from this window or send lumqera-build.log.
pause
exit /b 1
:finish_ok
echo.
pause
exit /b 0
