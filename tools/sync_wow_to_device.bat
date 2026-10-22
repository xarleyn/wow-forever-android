@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0sync_wow_to_device.ps1" %*
exit /b %errorlevel%
