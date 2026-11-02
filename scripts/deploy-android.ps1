#!/usr/bin/env pwsh
# Build and deploy the app to an Android device/emulator.
#
# Usage:
#   npm run android:deploy
#   powershell -File scripts/deploy-android.ps1 -NoBuild
#   powershell -File scripts/deploy-android.ps1 -Device emulator-5554
#
# Note: keep this file ASCII-only. Windows PowerShell 5.1 reads BOM-less .ps1
# as ANSI, and non-ASCII literals would be garbled.
param(
    [string]$Device = "",
    [switch]$NoBuild,
    [switch]$SkipWeb
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent

# Capacitor 8 Android project requires JDK 21. Prefer the JBR bundled with Android Studio.
$jbr = 'C:\Program Files\Android\Android Studio\jbr'
if (Test-Path $jbr) {
    $env:JAVA_HOME = $jbr
    $env:PATH = "$jbr\bin;$env:PATH"
} else {
    Write-Host "[warn] Android Studio JBR not found, using JAVA_HOME=$env:JAVA_HOME" -ForegroundColor Yellow
}

if (-not $Device) {
    $devices = @(adb devices | Select-String "`tdevice$" | ForEach-Object { ($_ -split "`t")[0] })
    if ($devices.Count -eq 0) { throw "No device/emulator connected. Start an emulator or plug in a phone." }
    $Device = if ($devices -contains 'emulator-5554') { 'emulator-5554' } else { $devices[0] }
}
Write-Host "[info] target device: $Device" -ForegroundColor Cyan

Push-Location $root
try {
    if (-not $SkipWeb) {
        Write-Host "[step] vite build" -ForegroundColor Cyan
        npm run build | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "web build failed" }
    }

    Write-Host "[step] cap sync android" -ForegroundColor Cyan
    npx cap sync android | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "cap sync failed" }

    $apk = Join-Path $root 'android\app\build\outputs\apk\debug\app-debug.apk'
    if (-not $NoBuild) {
        Write-Host "[step] gradlew assembleDebug" -ForegroundColor Cyan
        Push-Location (Join-Path $root 'android')
        try {
            & .\gradlew.bat assembleDebug --console=plain -q
            if ($LASTEXITCODE -ne 0) { throw "gradle build failed" }
        } finally { Pop-Location }
    }
    if (-not (Test-Path $apk)) { throw "APK not found: $apk" }

    Write-Host "[step] adb install" -ForegroundColor Cyan
    adb -s $Device install -r $apk
    if ($LASTEXITCODE -ne 0) {
        throw "install failed. On Xiaomi/Redmi: enable 'Install via USB' in Developer options and confirm on the phone."
    }

    Write-Host "[step] launch app" -ForegroundColor Cyan
    adb -s $Device shell am force-stop com.myapp.testionic
    adb -s $Device shell am start -n com.myapp.testionic/.MainActivity | Out-Null
    Write-Host "[done] deployed to $Device" -ForegroundColor Green
} finally {
    Pop-Location
}
