@echo off
setlocal enabledelayedexpansion
set "REPO_ROOT=%~dp0.."
cd /d "%REPO_ROOT%"

title Simple Map - Fabric 1.20.1 Client (Debug & Telemetry Mode)

echo =====================================================================
echo          SIMPLE MAP - FABRIC 1.20.1 DEBUG & TELEMETRY LAUNCHER
echo =====================================================================
echo.
echo [1] KHOI DONG CHE DO DEBUG & TELEMETRY:
echo   - JVM Flag: -Dsimplemap.debug=true duoc kich hoat.
echo   - Bo ghi thong so (MapDebugRecorder) hoat dong san sang.
echo.
echo [2] CAC PHIM NONG TRONG GAME (KHI DANG CHOI HOAC DANG MO FULL MAP 'M'):
echo   - [ F8  ] : BAT / TAT HUD OVERLAY (hien thi truc tiep thong so map:
echo              FPS, frame time, Heap/GC, Page states, FBO status len man hinh).
echo   - [ F9  ] : BAT / TAT GHI METRICS (mac dinh: tu dong BAT khi vao world).
echo   - [ F10 ] : *** DANH DAU SU CO (MARK ISSUE) ***:
echo              - Bam F10 ngay khi thay PAN MAP BI GIAT/LAG!
echo              - Bam F10 khi lia den VUNG MAP BI DEN / KHONG LOAD CAVE!
echo              - Khi bam F10, mod se ghi Burst Mode (250ms/mau lien tuc trong 20s)
echo                va danh dau moc USER_MARKER de phan tich nguyen nhan chinh xac.
echo.
echo [3] VI TRI LUU LOG METRICS:
echo   - Thu muc: fabric-1.20.1\run\simplemap-debug\
echo   - Chuan gom: metrics.csv (thong so dinh luong) va events.jsonl (su kien/loi)
echo =====================================================================
echo.

:: Detect JDK 17
if defined JAVA17_HOME (
    if exist "%JAVA17_HOME%\bin\java.exe" (
        set "JAVA_HOME=%JAVA17_HOME%"
        goto :JAVA_DETECTED
    )
)

if exist "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\java.exe" (
    set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
    goto :JAVA_DETECTED
)

if exist "C:\Program Files\Java\jdk-17\bin\java.exe" (
    set "JAVA_HOME=C:\Program Files\Java\jdk-17"
    goto :JAVA_DETECTED
)

if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        goto :JAVA_DETECTED
    )
)

:JAVA_DETECTED
if defined JAVA_HOME (
    echo [Environment] Su dung JDK 17 tai: "%JAVA_HOME%"
    set "PATH=%JAVA_HOME%\bin;%PATH%"
) else (
    echo [Warning] Khong tim thay JAVA17_HOME co dinh, su dung Java mac dinh tren PATH.
)

:: Enable debug property environment variable
set "SIMPLEMAP_DEBUG=true"

echo [Launcher] Dang khoi chay Fabric 1.20.1 Client voi che do Debug...
echo ---------------------------------------------------------------------

cd /d "%REPO_ROOT%\fabric-1.20.1"
call gradlew.bat runClient -Psimplemap.debug=true "-Dsimplemap.debug=true"
set "EXITCODE=%ERRORLEVEL%"
cd /d "%REPO_ROOT%"

echo.
echo =====================================================================
if "%EXITCODE%"=="0" (
    echo [Launcher] Game da dong binh thuong.
) else (
    echo [Launcher] Game ket thuc voi ma loi: %EXITCODE%
)
echo =====================================================================

set "LATEST_LOG_TXT=%REPO_ROOT%\fabric-1.20.1\run\simplemap-debug\latest.txt"
if exist "%LATEST_LOG_TXT%" (
    set /p LATEST_DIR=<"%LATEST_LOG_TXT%"
    echo.
    echo [Telemetry Output] Thu muc ket qua phan tich session gan nhat:
    echo   !LATEST_DIR!
    echo.
    echo   Cac tep da ghi:
    if exist "!LATEST_DIR!\metrics.csv" (
        echo   [x] !LATEST_DIR!\metrics.csv
    )
    if exist "!LATEST_DIR!\events.jsonl" (
        echo   [x] !LATEST_DIR!\events.jsonl
    )
    echo.
    set /p "OPEN_DIR=Ban co muon mo thu muc debug vua tao khong? (Y/N): "
    if /i "!OPEN_DIR!"=="Y" (
        start "" "!LATEST_DIR!"
    )
) else (
    echo [Telemetry Output] Chua tim thay thu muc debug moi (co the do chua vao World).
)

echo.
pause
exit /b %EXITCODE%

