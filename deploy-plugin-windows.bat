@echo off
setlocal EnableExtensions
chcp 65001 >nul
title LUMQERA - Install LumqeraCore
cd /d "%~dp0"
if not exist "deploy-plugin-windows.ps1" (
  echo [ERROR] Missing deploy-plugin-windows.ps1. Use the full repository.
  pause
  exit /b 1
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy-plugin-windows.ps1"
set "DEPLOY_EXIT=%ERRORLEVEL%"
if not "%DEPLOY_EXIT%"=="0" (
  echo [ERROR] Plugin deployment was cancelled or failed.
) else (
  echo [OK] LumqeraCore installed. Start Paper and check /plugins.
)
pause
exit /b %DEPLOY_EXIT%
