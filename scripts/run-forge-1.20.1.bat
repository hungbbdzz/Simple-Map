@echo off
setlocal
set "REPO_ROOT=%~dp0.."
cd /d "%REPO_ROOT%"

set "CHOICE=%~1"
if /i "%CHOICE%"=="debug" goto :RUN_DEBUG
if /i "%CHOICE%"=="-debug" goto :RUN_DEBUG
if /i "%CHOICE%"=="--debug" goto :RUN_DEBUG
if /i "%CHOICE%"=="d" goto :RUN_DEBUG
if /i "%CHOICE%"=="2" goto :RUN_DEBUG

if "%CHOICE%"=="1" goto :START_CLIENT

:MENU
echo ====================================================
echo         Simple Map - Forge 1.20.1 Client
echo ====================================================
echo   1) Run Client (Normal)
echo   2) Run Client (Debug & Telemetry Mode)
echo   0) Exit
echo ----------------------------------------------------
set /p "CHOICE=Select mode [1-2, default=1]: "
if "%CHOICE%"=="" set "CHOICE=1"

if "%CHOICE%"=="1" goto :START_CLIENT
if "%CHOICE%"=="2" goto :RUN_DEBUG
if "%CHOICE%"=="0" exit /b 0

:RUN_DEBUG
call "%~dp0run-forge-1.20.1-debug.bat"
exit /b %ERRORLEVEL%

:START_CLIENT
if defined JAVA17_HOME set "JAVA_HOME=%JAVA17_HOME%"

echo [Simple Map] Launching Forge 1.20.1 Client...
cd /d "%REPO_ROOT%\forge-1.20.1"
call gradlew.bat runClient
set "EXITCODE=%ERRORLEVEL%"
cd /d "%REPO_ROOT%"
if not "%EXITCODE%"=="0" (
    echo.
    echo Forge client run failed with code %EXITCODE%.
    pause
)
exit /b %EXITCODE%

