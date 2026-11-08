#!/usr/bin/env pwsh
# Runs the in-app smoke test on every connected Android device/emulator.
#
#   npm run verify:android
#   powershell -File scripts/verify-android.ps1 -Devices 32e93a6d
#
# Requirements: debug build installed on each device, node in PATH.
param(
    [string[]]$Devices = @()
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$package = 'com.myapp.testionic'

if (-not $Devices) {
    $Devices = @(adb devices | Select-String "`tdevice$" | ForEach-Object { ($_ -split "`t")[0] })
}
if ($Devices.Count -eq 0) { throw 'No device/emulator connected.' }

$port = 9300
$summary = @()

foreach ($device in $Devices) {
    $port++
    Write-Host "[device] $device (devtools port $port)" -ForegroundColor Cyan

    adb -s $device shell am force-stop $package | Out-Null
    adb -s $device shell am start -n "$package/.MainActivity" | Out-Null
    Start-Sleep -Seconds 4

    # Use cmd /c so adb's stderr does not become a PowerShell terminating error
    $appPid = (cmd /c "adb -s $device shell pidof $package 2>nul" | Out-String).Trim()
    if (-not $appPid) {
        $summary += [pscustomobject]@{ Device = $device; Result = 'FAIL'; Detail = 'app not running' }
        continue
    }

    $null = cmd /c "adb -s $device forward --remove tcp:$port 2>nul"
    $null = cmd /c "adb -s $device forward tcp:$port localabstract:webview_devtools_remote_$appPid 2>nul"
    Start-Sleep -Milliseconds 800

    Push-Location $root
    try {
        $raw = & node scripts/webview-eval.mjs --port $port --file scripts/smoke-test.js 2>&1 | Out-String
        $exit = $LASTEXITCODE
    } finally {
        Pop-Location
    }

    if ($exit -ne 0) {
        Write-Host $raw -ForegroundColor Red
        $summary += [pscustomobject]@{ Device = $device; Result = 'FAIL'; Detail = 'cdp eval failed' }
        continue
    }

    $obj = $raw | ConvertFrom-Json
    $model = 'unknown'
    if ($obj.out.deviceInfo -match '"model":\s*"([^"]+)"') { $model = $Matches[1] }

    if ($obj.ok) {
        $summary += [pscustomobject]@{ Device = $device; Result = 'PASS'; Detail = "platform=$($obj.out.platform) model=$model" }
    } else {
        $summary += [pscustomobject]@{ Device = $device; Result = 'FAIL'; Detail = "failed: $($obj.failures -join ', ')" }
        Write-Host $raw -ForegroundColor Yellow
    }
}

Write-Host ''
$summary | Format-Table -AutoSize

if ($summary.Where({ $_.Result -ne 'PASS' }).Count -gt 0) { exit 1 }
Write-Host 'All devices passed.' -ForegroundColor Green
