@echo off
rem Debug launch: same as run.bat, but all output also goes to logs\run-<date-time>.log
rem and the window stays open after the game exits, so a crash message is not lost.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-debug.ps1"
pause
