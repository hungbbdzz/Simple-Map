@echo off
setlocal
cd /d "%~dp0"

set "CHOICE=%~1"
if not "%CHOICE%"=="" goto :HANDLE_CHOICE

:MENU
cls
echo =====================================================================
echo                VELORISE SIMPLE MAP - CLIENT LAUNCHER
echo =====================================================================
echo   [NORMAL CLIENT RUN]
echo     1) Fabric 1.21.1   (Java 21)
echo     2) NeoForge 1.21.1 (Java 21)
echo     3) Forge 1.20.1    (Java 17)
echo     4) Fabric 1.20.1   (Java 17)
echo.
echo   [DEBUG & TELEMETRY CLIENT RUN]
echo     5) Fabric 1.21.1   (Debug)
echo     6) NeoForge 1.21.1 (Debug)
echo     7) Forge 1.20.1    (Debug)
echo     8) Fabric 1.20.1   (Debug)
echo.
echo     0) Exit
echo ---------------------------------------------------------------------
set /p "CHOICE=Select an option [1-8]: "

:HANDLE_CHOICE
if "%CHOICE%"=="1" ( call "%~dp0scripts\run-fabric.bat" 1 & exit /b )
if "%CHOICE%"=="2" ( call "%~dp0scripts\run-neoforge.bat" 1 & exit /b )
if "%CHOICE%"=="3" ( call "%~dp0scripts\run-forge-1.20.1.bat" 1 & exit /b )
if "%CHOICE%"=="4" ( call "%~dp0scripts\run-fabric.bat" 2 & exit /b )

if "%CHOICE%"=="5" ( call "%~dp0scripts\run-fabric-debug.bat" & exit /b )
if "%CHOICE%"=="6" ( call "%~dp0scripts\run-neoforge-debug.bat" & exit /b )
if "%CHOICE%"=="7" ( call "%~dp0scripts\run-forge-1.20.1-debug.bat" & exit /b )
if "%CHOICE%"=="8" ( call "%~dp0scripts\run-fabric-1.20.1-debug.bat" & exit /b )

if "%CHOICE%"=="0" exit /b 0

echo Invalid selection: "%CHOICE%"
timeout /t 2 >nul
goto :MENU

