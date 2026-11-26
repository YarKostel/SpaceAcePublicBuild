@echo off
setlocal
cd /d "%~dp0"
set "LOG=%~dp0build-log.txt"
echo === JDK search === > "%LOG%"

rem --- JDK 21: ручной путь (если задан), затем стандартные места ---
set "JDK="
if exist "%~dp0jdk-path.txt" set /p JDK=<"%~dp0jdk-path.txt"
if defined JDK goto jdkcheck

for /d %%R in ("C:\Program Files\Java" "C:\Program Files\Eclipse Adoptium" "C:\Program Files\Microsoft" "C:\Program Files\Zulu" "C:\Program Files\Amazon Corretto" "C:\Program Files\BellSoft" "C:\Program Files\OpenJDK" "%USERPROFILE%\.jdks" "%USERPROFILE%\scoop\apps" "C:\#MyData") do for /d %%D in ("%%~R\*21*") do (
  echo candidate: %%~D >> "%LOG%"
  if exist "%%~D\bin\java.exe" set "JDK=%%~D"
)
if defined JDK goto jdkcheck
for /d %%D in ("C:\Program Files\Android\Android Studio*" "%LOCALAPPDATA%\Programs\Android Studio*" "C:\#MyData\Android Studio*") do (
  echo candidate: %%~D\jbr >> "%LOG%"
  if exist "%%~D\jbr\bin\java.exe" set "JDK=%%~D\jbr"
)
if defined JDK goto jdkcheck

echo JDK 21 NOT FOUND >> "%LOG%"
echo --- where java --- >> "%LOG%"
where java >> "%LOG%" 2>&1
echo --- registry --- >> "%LOG%"
reg query "HKLM\SOFTWARE\JavaSoft\JDK" /s /v JavaHome >> "%LOG%" 2>&1
reg query "HKLM\SOFTWARE\Eclipse Adoptium\JDK" /s /v Path >> "%LOG%" 2>&1
dir /b "C:\Program Files\Java" >> "%LOG%" 2>&1
echo EXIT=NOJDK >> "%LOG%"
echo DONE >> "%LOG%"
goto :eof

:jdkcheck
set "JAVA_HOME=%JDK%"
set "PATH=%JDK%\bin;%PATH%"

rem --- Android SDK -> local.properties ---
set "SDK=%ANDROID_HOME%"
if not defined SDK set "SDK=%LOCALAPPDATA%\Android\Sdk"
if exist local.properties goto sdkdone
if not exist "%SDK%\platforms" goto sdkdone
set "SDKF=%SDK:\=/%"
echo sdk.dir=%SDKF% > local.properties
:sdkdone

echo JAVA_HOME=%JAVA_HOME% >> "%LOG%"
echo SDK=%SDK% >> "%LOG%"
java -version >> "%LOG%" 2>&1
call gradlew.bat --stop >> "%LOG%" 2>&1
call gradlew.bat core:test desktop:assemble android:assembleDebug --continue --stacktrace --console=plain >> "%LOG%" 2>&1
echo EXIT=%ERRORLEVEL% >> "%LOG%"
echo DONE >> "%LOG%"
