@echo off
chcp 65001 >nul
title LUMQERA - Synchronize Website Ranking
cd /d "%~dp0"
set "SOURCE=server\paper\plugins\LumqeraCore\export\public-stats.json"
set "TARGET=website\data\public-stats.json"
if not exist "%SOURCE%" (
  echo [ERROR] No game export found: %SOURCE%
  echo Start Paper with LumqeraCore, join the server, and run /lumqera export.
  pause
  exit /b 1
)
copy /y "%SOURCE%" "%TARGET%" >nul
if errorlevel 1 (
  echo [ERROR] Unable to copy export.
  pause
  exit /b 1
)
echo [OK] Website rating updated: %TARGET%
pause
