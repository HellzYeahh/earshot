# Build Earshot and install it on the connected phone (USB or wireless debugging).
# Usage:  .\install.ps1                 (Play flavour, debug build: dev.earshot, the default)
#         .\install.ps1 -Foss           (GitHub flavour, debug build: dev.earshot.foss)
#         .\install.ps1 -Release        (minified release build; needs keystore.properties, see
#                                        docs/release-signing.md, or it comes out unsigned)
param([switch]$Release, [switch]$Foss)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$flavor = if ($Foss) { "foss" } else { "play" }
$type   = if ($Release) { "release" } else { "debug" }
$task   = ":app:assemble" + $flavor.Substring(0, 1).ToUpper() + $flavor.Substring(1) + $type.Substring(0, 1).ToUpper() + $type.Substring(1)
$apk    = "app\build\outputs\apk\$flavor\$type\app-$flavor-$type.apk"
$appId  = if ($Foss) { "dev.earshot.foss" } else { "dev.earshot" }

Write-Host "Building ($task)..." -ForegroundColor Cyan
& .\gw.cmd $task --console=plain -q
if ($LASTEXITCODE -ne 0) { throw "Build failed" }
if (-not (Test-Path $apk)) { throw "No signed APK at $apk (release builds need keystore.properties)" }

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
adb -s $serial shell am start -n "$appId/dev.earshot.MainActivity" | Out-Null
Write-Host "Done. Close the Bose app before connecting (only one app can use the headphones' control channel)." -ForegroundColor Green
Write-Host "Live log:  adb -s $serial logcat -v time Earshot/Bt:V Earshot/Protocol:V Earshot/Codec:V Earshot/Tuning:V AndroidRuntime:E *:S"
