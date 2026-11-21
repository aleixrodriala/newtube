@echo off
rem NewTube for Android Auto: double-click to install or update (see install.ps1).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*
pause
