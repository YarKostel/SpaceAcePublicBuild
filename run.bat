@echo off
setlocal
cd /d "%~dp0"

rem JDK 21 path is taken from jdk-path.txt (one line, no quotes).
set "JDK="
if exist "%~dp0jdk-path.txt" set /p JDK=<"%~dp0jdk-path.txt"
if not defined JDK (
  echo jdk-path.txt not found. Put the JDK 21 folder path into it, e.g. C:\#MyData\jdk-21.0.12.1
  pause
  exit /b 1
)
if not exist "%JDK%\bin\java.exe" (
  echo JDK not found: "%JDK%"
  pause
  exit /b 1
)

set "JAVA_HOME=%JDK%"
set "PATH=%JDK%\bin;%PATH%"

echo Starting SpaceAce...
rem Full path: cmd may be configured not to search the current folder (NoDefaultCurrentDirectoryInExePath).
call "%~dp0gradlew.bat" desktop:run --console=plain -q
if errorlevel 1 (
  echo.
  echo Game exited with an error. See the messages above.
  pause
)
