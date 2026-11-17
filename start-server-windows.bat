@echo off
setlocal EnableExtensions
chcp 65001 >nul
title LUMQERA - Paper Minecraft Server
cd /d "%~dp0server\paper"
if errorlevel 1 goto no_paper
if not exist "paper.jar" goto no_paper

rem Allow an explicit JDK 25 installation; otherwise resolve Java from PATH.
set "JAVA_CMD=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
if defined JDK25_HOME if exist "%JDK25_HOME%\bin\java.exe" set "JAVA_CMD=%JDK25_HOME%\bin\java.exe"

rem -version alone may refer to Java 8 even if HotSpot prints 25.xxx.
rem Check the JDK's actual java.specification.version instead.
"%JAVA_CMD%" -XshowSettings:properties -version 2>&1 | findstr /R /C:"java.specification.version = 25$" >nul
if errorlevel 1 (
  echo [ERROR] Java 25 is required but could not be verified.
  echo [HINT] Set JDK25_HOME to the root of your Java 25 installation.
  "%JAVA_CMD%" -version 2>&1
  pause
  exit /b 1
)
echo [OK] Java 25 detected.
echo [INFO] Stop Paper with the "stop" console command before replacing plugins.
echo [INFO] On first launch, read the Minecraft EULA and accept it manually if you agree.
if not defined LUMQERA_XMS set "LUMQERA_XMS=2G"
if not defined LUMQERA_XMX set "LUMQERA_XMX=4G"
"%JAVA_CMD%" -Xms%LUMQERA_XMS% -Xmx%LUMQERA_XMX% -jar "paper.jar" --nogui
set "EXIT_CODE=%ERRORLEVEL%"
echo [INFO] Paper exited with code %EXIT_CODE%.
pause
exit /b %EXIT_CODE%

:no_paper
echo [ERROR] Paper server not found: server\paper\paper.jar
echo [HINT] Download Paper 26.3 and name the file paper.jar.
pause
exit /b 1
