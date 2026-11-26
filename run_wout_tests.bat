@echo off
rem Build and launch the desktop game without running any tests (Gradle -x test).
rem Output goes to the screen and to logs\run-<date-time>.log, like run-debug.bat.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-debug.ps1" -SkipTests
pause
