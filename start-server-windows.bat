@echo off
setlocal EnableExtensions
chcp 65001 >nul
title LUMQERA - Paper Minecraft Server
cd /d "%~dp0server\paper"
if not exist "paper.jar" (
  echo [ERROR] Paper 26.3 JAR not found: "%CD%\paper.jar"
  echo Download the correct Paper version and name the file paper.jar.
  pause
  exit /b 1
)
set "JAVA_CMD=java"
if defined JDK25_HOME if exist "%JDK25_HOME%\bin\java.exe" set "JAVA_CMD=%JDK25_HOME%\bin\java.exe"
"%JAVA_CMD%" -version 2>&1
if errorlevel 1 (
  echo [ERROR] Java not found. Install Java 25 first.
  pause
  exit /b 1
)
echo.
echo [INFO] LUMQERA Paper server starting; Java 25 is required.
echo [INFO] You must accept the Minecraft EULA yourself before the first start.
"%JAVA_CMD%" -Xms2G -Xmx4G -jar "paper.jar" --nogui
set "EXIT_CODE=%ERRORLEVEL%"
echo [INFO] Paper exited with code %EXIT_CODE%.
pause
exit /b %EXIT_CODE%
