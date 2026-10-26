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
echo         Simple Map - NeoForge 1.21.1 Client
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
call "%~dp0run-neoforge-debug.bat"
exit /b %ERRORLEVEL%

:START_CLIENT
if defined JAVA21_HOME set "JAVA_HOME=%JAVA21_HOME%"

echo [Simple Map] Launching NeoForge 1.21.1 Client...
cd /d "%REPO_ROOT%\neoforge-1.21.1"
call gradlew.bat runClient
set "EXITCODE=%ERRORLEVEL%"
cd /d "%REPO_ROOT%"
if not "%EXITCODE%"=="0" (
    echo.
    echo NeoForge client run failed with code %EXITCODE%.
    pause
)
exit /b %EXITCODE%

