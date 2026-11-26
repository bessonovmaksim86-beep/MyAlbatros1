@echo off
setlocal
cd /d "%~dp0"

REM Label print agent launcher.
REM This file is intentionally ASCII-only with CRLF line endings:
REM cmd.exe misparses UTF-8 Cyrillic text and LF-only line breaks.
REM Russian messages come from the Java agent, not from this script.

set "JAVA_EXE="

REM 1. Java in PATH.
where java >nul 2>nul
if not errorlevel 1 set "JAVA_EXE=java"

REM 2. Java installed but not in PATH: check standard folders.
if not defined JAVA_EXE for /d %%D in (
    "C:\Program Files\Java\jdk*"
    "C:\Program Files\Java\jre*"
    "C:\Program Files\Eclipse Adoptium\jdk*"
    "C:\Program Files\Eclipse Adoptium\jre*"
    "C:\Program Files\Microsoft\jdk*"
    "C:\Program Files\Amazon Corretto\jdk*"
    "C:\Program Files\BellSoft\LibericaJDK*"
) do if not defined JAVA_EXE if exist "%%~D\bin\java.exe" set "JAVA_EXE=%%~D\bin\java.exe"

if not defined JAVA_EXE goto nojava

REM UTF-8 console: the agent prints Russian text, cp866 would garble it.
chcp 65001 >nul

echo ========================================
echo  Label print agent
echo  Address: 127.0.0.1:7788
echo  Close this window to stop the agent.
echo ========================================
echo  Java: %JAVA_EXE%
echo.

"%JAVA_EXE%" -Dfile.encoding=UTF-8 -cp "%~dp0." PrintAgent

echo.
echo Agent stopped. Press any key to close.
pause >nul
exit /b 0

:nojava
echo.
echo ERROR: Java not found.
echo Install Java 17 or newer, then run this file again.
echo Download: https://adoptium.net/temurin/releases/?version=17
echo.
echo If Java is already installed, add its bin folder to PATH
echo or write the full path to java.exe in this file.
echo.
pause
exit /b 1
