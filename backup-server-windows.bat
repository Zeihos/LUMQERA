@echo off
chcp 65001 >nul
title LUMQERA - Backup offline server
cd /d "%~dp0"
where powershell >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Windows PowerShell is required.
  pause
  exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0backup-server-windows.ps1"
if errorlevel 1 (
  echo [ERROR] Backup was not completed.
  pause
  exit /b 1
)
echo [OK] Backup command completed.
pause
