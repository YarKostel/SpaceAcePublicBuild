# Debug launch: everything Gradle and the game print goes to the screen and to logs\run-<date-time>.log.
# The log is written line by line (flushed), so it survives even if the window is closed.
# -SkipTests: tell Gradle to skip every test task (run_wout_tests.bat).
param([switch]$SkipTests)
$root = $PSScriptRoot
$gradleArgs = 'desktop:run --console=plain --stacktrace'
if ($SkipTests) { $gradleArgs += ' -x test' }

$jdkFile = Join-Path $root 'jdk-path.txt'
if (-not (Test-Path $jdkFile)) {
    Write-Host 'jdk-path.txt not found. Put the JDK 21 folder path into it, e.g. C:\#MyData\jdk-21.0.12.1'
    exit 1
}
$jdk = (Get-Content $jdkFile -TotalCount 1).Trim()
if (-not (Test-Path (Join-Path $jdk 'bin\java.exe'))) {
    Write-Host "JDK not found: $jdk"
    exit 1
}
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"
# UTF-8 output from the JVM, so Russian error messages are readable in the log.
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8'
[Console]::OutputEncoding = New-Object Text.UTF8Encoding $false

$logs = Join-Path $root 'logs'
New-Item -ItemType Directory -Force $logs | Out-Null
$log = Join-Path $logs ('run-{0:yyyy-MM-dd_HH-mm-ss}.log' -f (Get-Date))
$writer = New-Object IO.StreamWriter($log, $false, (New-Object Text.UTF8Encoding $false))
$writer.AutoFlush = $true

function Write-Log([string]$line) {
    Write-Host $line
    $writer.WriteLine($line)
}

try {
    Write-Log "SpaceAce debug run $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
    Write-Log "OS: $([Environment]::OSVersion.VersionString)"
    Write-Log "JDK: $jdk"
    Write-Log "Gradle: $gradleArgs"
    & cmd /c "`"$jdk\bin\java.exe`" -version 2>&1" | ForEach-Object { Write-Log "$_" }
    Write-Log '--- game output ---'
    # cmd merges stderr into stdout before PowerShell sees it: plain lines, no NativeCommandError wrapping.
    & cmd /c "`"$root\gradlew.bat`" $gradleArgs 2>&1" | ForEach-Object { Write-Log "$_" }
    Write-Log "--- exit code: $LASTEXITCODE ---"
} finally {
    $writer.Close()
}
Write-Host ''
Write-Host "Log saved: $log"
