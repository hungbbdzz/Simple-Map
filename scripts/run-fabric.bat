@echo off
setlocal
set "REPO_ROOT=%~dp0.."
cd /d "%REPO_ROOT%"

set "CHOICE=%~1"
if not "%CHOICE%"=="" goto :HANDLE_CHOICE

:MENU
cls
echo ====================================================
echo         Simple Map - Fabric Dev Client
echo ====================================================
echo   1) Fabric 1.21.1 (Java 21) [Normal]
echo   2) Fabric 1.20.1 (Java 17) [Normal]
echo   3) Fabric 1.21.1 (Java 21) [Debug & Telemetry]
echo   4) Fabric 1.20.1 (Java 17) [Debug & Telemetry]
echo   0) Exit
echo ----------------------------------------------------
set /p "CHOICE=Select version to run [1-4]: "

:HANDLE_CHOICE
if "%CHOICE%"=="1" goto :RUN_1211
if "%CHOICE%"=="1.21.1" goto :RUN_1211
if "%CHOICE%"=="1211" goto :RUN_1211

if "%CHOICE%"=="2" goto :RUN_1201
if "%CHOICE%"=="1.20.1" goto :RUN_1201
if "%CHOICE%"=="1201" goto :RUN_1201

if "%CHOICE%"=="3" goto :RUN_DEBUG_1211
if /i "%CHOICE%"=="debug" goto :RUN_DEBUG_1211
if "%CHOICE%"=="d" goto :RUN_DEBUG_1211

if "%CHOICE%"=="4" goto :RUN_DEBUG_1201

if "%CHOICE%"=="0" exit /b 0

echo Invalid selection: "%CHOICE%"
timeout /t 2 >nul
goto :MENU

:RUN_DEBUG_1211
call "%~dp0run-fabric-debug.bat"
exit /b %ERRORLEVEL%

:RUN_DEBUG_1201
call "%~dp0run-fabric-1.20.1-debug.bat"
exit /b %ERRORLEVEL%

:RUN_1211
if defined JAVA21_HOME set "JAVA_HOME=%JAVA21_HOME%"
set "TARGET_DIR=fabric-1.21.1"
goto :START_CLIENT

:RUN_1201
if defined JAVA17_HOME set "JAVA_HOME=%JAVA17_HOME%"
set "TARGET_DIR=fabric-1.20.1"
goto :START_CLIENT

:START_CLIENT
echo [Simple Map] Launching %TARGET_DIR% Fabric client...
cd /d "%REPO_ROOT%\%TARGET_DIR%"
call gradlew.bat runClient
set "EXITCODE=%ERRORLEVEL%"
cd /d "%REPO_ROOT%"
if not "%EXITCODE%"=="0" (
    echo.
    echo Fabric client run failed with code %EXITCODE%.
    pause
)
exit /b %EXITCODE%

