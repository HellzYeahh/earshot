# Build Earshot and install it on the connected phone (USB or wireless debugging).
# Usage:  .\install.ps1            (debug build, default)
#         .\install.ps1 -Release   (minified build, signed with the debug key)
param([switch]$Release)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$task = if ($Release) { "assembleRelease" } else { "assembleDebug" }
$apk  = if ($Release) { "app\build\outputs\apk\release\app-release.apk" } else { "app\build\outputs\apk\debug\app-debug.apk" }

Write-Host "Building ($task)..." -ForegroundColor Cyan
& .\gw.cmd $task --console=plain -q
if ($LASTEXITCODE -ne 0) { throw "Build failed" }

$devices = adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" } | ForEach-Object { ($_ -split "\t")[0] }
$phones  = $devices | Where-Object { $_ -notmatch "^emulator-" }
if (-not $phones) {
    Write-Host "No phone found. Plug it in with USB debugging on (or pair Wireless debugging), then run again." -ForegroundColor Yellow
    Write-Host "Devices seen: $($devices -join ', ')"
    exit 1
}
$serial = $phones | Select-Object -First 1
Write-Host "Installing on $serial ..." -ForegroundColor Cyan
adb -s $serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw "Install failed" }

Write-Host "Launching..." -ForegroundColor Cyan
adb -s $serial shell am start -n dev.earshot/.MainActivity | Out-Null
Write-Host "Done. Close the Bose app before connecting (only one app can use the headphones' control channel)." -ForegroundColor Green
Write-Host "Live log:  adb -s $serial logcat -v time Earshot/Bt:V Earshot/Protocol:V Earshot/Codec:V Earshot/Tuning:V AndroidRuntime:E *:S"
