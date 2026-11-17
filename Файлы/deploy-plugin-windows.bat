@echo off
setlocal EnableExtensions
chcp 65001 >nul
title LUMQERA - Deploy LumqeraCore
cd /d "%~dp0"
echo [IMPORTANT] Stop the Paper server completely before deploying.
set "SOURCE=server\LumqeraCore\build\libs\LumqeraCore-1.0.0-RC6.jar"
set "DEST=server\paper\plugins"
if not exist "%SOURCE%" (
  echo [ERROR] Built plugin JAR not found: %SOURCE%
  echo Run build-plugin-windows.bat first, with JDK 25 and Gradle 9.x.
  pause
  exit /b 1
)
if not exist "%DEST%" mkdir "%DEST%"
if errorlevel 1 (
 echo [ERROR] Cannot create Paper plugins directory.
 pause
 exit /b 1
)
if exist "%DEST%\LumqeraCore.jar" (
  copy /y "%DEST%\LumqeraCore.jar" "%DEST%\LumqeraCore.jar.bak" >nul
  if errorlevel 1 (
   echo [ERROR] Could not back up the old plugin JAR.
   pause
   exit /b 1
  )
)
copy /y "%SOURCE%" "%DEST%\LumqeraCore.jar" >nul
if errorlevel 1 (
 echo [ERROR] Copy failed. Check that the Paper server is stopped.
 pause
 exit /b 1
)
echo [OK] Plugin deployed to %DEST%\LumqeraCore.jar
echo Run readiness-check-windows.ps1 before launching Paper.
pause
