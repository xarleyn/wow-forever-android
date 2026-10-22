@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0fetch_components.ps1" %*
exit /b %errorlevel%
