<# :
@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -Command "$Root = '%~dp0'; & ([scriptblock]::Create((Get-Content -LiteralPath '%~f0' -Raw)))"
set "RESULT=%ERRORLEVEL%"
if not "%RESULT%"=="0" (
    echo.
    echo Build failed. Read the error above.
    pause
)
exit /b %RESULT%
#>
$ErrorActionPreference = 'Stop'

$WorkspaceParent = Split-Path -Parent $Root.TrimEnd('\', '/')

# Destination for compiled release jars
$LocalModsDir = Join-Path $Root 'mods'
$GlobalModsDir = Join-Path $WorkspaceParent 'mods'

$OriginalJavaHome = $env:JAVA_HOME
$OriginalPath = $env:Path

function Use-JavaHome([string]$VariableName, [string]$Label) {
    $candidate = [Environment]::GetEnvironmentVariable($VariableName)

    if ([string]::IsNullOrWhiteSpace($candidate)) {
        if ($VariableName -eq 'JAVA21_HOME' -and (Test-Path 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe')) {
            $candidate = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
        } elseif ($VariableName -eq 'JAVA17_HOME' -and (Test-Path 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\java.exe')) {
            $candidate = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($candidate)) {
        if (-not (Test-Path (Join-Path $candidate 'bin\java.exe'))) {
            throw "$VariableName points to an invalid JDK directory: $candidate"
        }

        $env:JAVA_HOME = $candidate
        $env:Path = "$(Join-Path $candidate 'bin');$OriginalPath"
        Write-Host "Using $Label from ${VariableName}: $candidate"
        return
    }

    if (-not [string]::IsNullOrWhiteSpace($OriginalJavaHome)) {
        $env:JAVA_HOME = $OriginalJavaHome
        $env:Path = "$(Join-Path $OriginalJavaHome 'bin');$OriginalPath"
        Write-Host "${VariableName} is not set; using current JAVA_HOME for ${Label}: $OriginalJavaHome" -ForegroundColor Yellow
        return
    }

    $env:JAVA_HOME = $null
    $env:Path = $OriginalPath
    Write-Host "${VariableName} and JAVA_HOME are not set; Gradle will use java.exe from PATH for ${Label}." -ForegroundColor Yellow
}

function Invoke-Build(
    [string]$DisplayName,
    [string]$Folder,
    [string]$JavaVariable,
    [string]$JavaLabel
) {
    $dir = Join-Path $Root $Folder
    if (-not (Test-Path (Join-Path $dir 'build.gradle'))) {
        Write-Host "Skipping $DisplayName (build.gradle not found in $Folder)" -ForegroundColor DarkYellow
        return $false
    }

    Write-Host ""
    Write-Host "====================================================" -ForegroundColor Cyan
    Write-Host "Building $DisplayName" -ForegroundColor Cyan
    Write-Host "====================================================" -ForegroundColor Cyan

    Use-JavaHome $JavaVariable $JavaLabel

    Push-Location $dir
    try {
        & .\gradlew.bat clean build --stacktrace
        if ($LASTEXITCODE -ne 0) {
            throw "$DisplayName build failed with exit code $LASTEXITCODE"
        }
        return $true
    }
    finally {
        Pop-Location
    }
}

function Get-LatestReleaseJar(
    [string]$Folder,
    [string]$NamePattern,
    [string]$DisplayName
) {
    if (-not (Test-Path $Folder)) {
        return $null
    }

    $jar = Get-ChildItem -Path $Folder -Filter $NamePattern -File |
        Where-Object {
            $_.Name -notmatch '(-sources|-dev|-javadoc|-slim|-plain)\.jar$'
        } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    return $jar
}

function Copy-ReleaseJar(
    [System.IO.FileInfo]$Jar,
    [string]$DisplayName
) {
    if ($null -eq $Jar) { return }

    # Copy to local mods folder
    $destLocal = Join-Path $LocalModsDir $Jar.Name
    Copy-Item -Path $Jar.FullName -Destination $destLocal -Force
    Write-Host "Copied $DisplayName -> $destLocal" -ForegroundColor Green

    # Copy to global workspace mods folder if available
    if (Test-Path $GlobalModsDir) {
        $destGlobal = Join-Path $GlobalModsDir $Jar.Name
        Copy-Item -Path $Jar.FullName -Destination $destGlobal -Force
        Write-Host "Copied $DisplayName -> $destGlobal" -ForegroundColor Green
    }
}

New-Item -ItemType Directory -Path $LocalModsDir -Force | Out-Null
if (Test-Path $WorkspaceParent) {
    New-Item -ItemType Directory -Path $GlobalModsDir -Force | Out-Null
}

$successCount = 0

try {
    # 1. NeoForge 1.21.1
    if (Invoke-Build 'NeoForge 1.21.1' 'neoforge-1.21.1' 'JAVA21_HOME' 'JDK 21') {
        $jar = Get-LatestReleaseJar (Join-Path $Root 'neoforge-1.21.1\build\libs') 'simplemap-1.21.1-neoforge-*.jar' 'NeoForge 1.21.1'
        if ($null -eq $jar) {
            $jar = Get-LatestReleaseJar $LocalModsDir 'simplemap-1.21.1-neoforge-*.jar' 'NeoForge 1.21.1'
        }
        Copy-ReleaseJar $jar 'NeoForge 1.21.1'
        $successCount++
    }

    # 2. Fabric 1.21.1
    if (Invoke-Build 'Fabric 1.21.1' 'fabric-1.21.1' 'JAVA21_HOME' 'JDK 21') {
        $jar = Get-LatestReleaseJar (Join-Path $Root 'fabric-1.21.1\build\libs') 'simplemap-*.jar' 'Fabric 1.21.1'
        Copy-ReleaseJar $jar 'Fabric 1.21.1'
        $successCount++
    }

    # 3. Forge 1.20.1 (When ready)
    if (Invoke-Build 'Forge 1.20.1' 'forge-1.20.1' 'JAVA17_HOME' 'JDK 17') {
        $jar = Get-LatestReleaseJar (Join-Path $Root 'forge-1.20.1\build\libs') 'simplemap-*.jar' 'Forge 1.20.1'
        Copy-ReleaseJar $jar 'Forge 1.20.1'
        $successCount++
    }

    # 4. Fabric 1.20.1 (When ready)
    if (Invoke-Build 'Fabric 1.20.1' 'fabric-1.20.1' 'JAVA17_HOME' 'JDK 17') {
        $jar = Get-LatestReleaseJar (Join-Path $Root 'fabric-1.20.1\build\libs') 'simplemap-*.jar' 'Fabric 1.20.1'
        Copy-ReleaseJar $jar 'Fabric 1.20.1'
        $successCount++
    }

    Write-Host ""
    Write-Host "----------------------------------------------------" -ForegroundColor Green
    Write-Host "Completed: $successCount project(s) built successfully!" -ForegroundColor Green
    Write-Host "Release destination: $LocalModsDir" -ForegroundColor Cyan
    Write-Host "----------------------------------------------------" -ForegroundColor Green
}
finally {
    $env:JAVA_HOME = $OriginalJavaHome
    $env:Path = $OriginalPath
}
