@echo off
setlocal
set "REPO_ROOT=%~dp0.."
cd /d "%REPO_ROOT%"

set "CHOICE=%~1"
if not "%CHOICE%"=="" goto :HANDLE_CHOICE

:MENU
cls
echo =====================================================================
echo         SIMPLE MAP - ALL LOADERS DEBUG & TELEMETRY LAUNCHER
echo =====================================================================
echo   1) Fabric 1.21.1   (Java 21) [Debug & Telemetry]
echo   2) NeoForge 1.21.1 (Java 21) [Debug & Telemetry]
echo   3) Forge 1.20.1    (Java 17) [Debug & Telemetry]
echo   4) Fabric 1.20.1   (Java 17) [Debug & Telemetry]
echo   0) Exit
echo ---------------------------------------------------------------------
echo   Phim nong trong game: F8 (HUD Overlay), F9 (Toggle Log), F10 (Mark Issue)
echo =====================================================================
set /p "CHOICE=Chon phien ban de khoi chay debug [1-4]: "

:HANDLE_CHOICE
if "%CHOICE%"=="1" goto :RUN_FABRIC_1211
if "%CHOICE%"=="1.21.1" goto :RUN_FABRIC_1211
if "%CHOICE%"=="fabric" goto :RUN_FABRIC_1211

if "%CHOICE%"=="2" goto :RUN_NEOFORGE_1211
if "%CHOICE%"=="neoforge" goto :RUN_NEOFORGE_1211
if "%CHOICE%"=="neo" goto :RUN_NEOFORGE_1211

if "%CHOICE%"=="3" goto :RUN_FORGE_1201
if "%CHOICE%"=="forge" goto :RUN_FORGE_1201
if "%CHOICE%"=="1.20.1" goto :RUN_FORGE_1201

if "%CHOICE%"=="4" goto :RUN_FABRIC_1201
if "%CHOICE%"=="fabric-1.20.1" goto :RUN_FABRIC_1201

if "%CHOICE%"=="0" exit /b 0

echo Lua chon khong hop le: "%CHOICE%"
timeout /t 2 >nul
goto :MENU

:RUN_FABRIC_1211
call "%~dp0run-fabric-debug.bat"
exit /b %ERRORLEVEL%

:RUN_NEOFORGE_1211
call "%~dp0run-neoforge-debug.bat"
exit /b %ERRORLEVEL%

:RUN_FORGE_1201
call "%~dp0run-forge-1.20.1-debug.bat"
exit /b %ERRORLEVEL%

:RUN_FABRIC_1201
call "%~dp0run-fabric-1.20.1-debug.bat"
exit /b %ERRORLEVEL%

