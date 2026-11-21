# NewTube for Android Auto - installer (Windows).
#
# Android Auto only lists car apps that it believes came from Google Play. A plain
# "adb install" (or tapping the APK in a file manager) is recorded as installed by the shell or
# by that app, and Android Auto refuses it: the car shows only the sound entry, never video.
# This script hands the APK to the phone's own system installer with Google Play named as the
# installer, which is the record Android Auto accepts. Use it for the first install and for
# every update.
#
# Usage: put this script next to the NewTube APK and run Install.bat (or this script).
#   .\install.ps1 [-Apk <path or https URL>]
param([string]$Apk = "")

$ErrorActionPreference = "Continue"   # adb writes progress to stderr
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$packages = @("io.github.aleixrodriala.arc.auto", "io.github.aleixrodriala.arc")

function Say($t) { Write-Host $t }
function Fail($t) { Write-Host ""; Write-Host "ERROR: $t" -ForegroundColor Red; exit 1 }

# 1. The APK: an argument, or the newest NewTube*.apk next to this script.
if (-not $Apk) {
    $found = Get-ChildItem -Path $here -Filter "NewTube*.apk" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $found) { Fail "No NewTube*.apk next to this script. Download it into this folder." }
    $Apk = $found.FullName
}
if ($Apk -match '^https?://') {
    $dest = Join-Path $env:TEMP "NewTube-Auto-download.apk"
    Say "Downloading $Apk"
    Invoke-WebRequest $Apk -OutFile $dest -ErrorAction Stop
    $Apk = $dest
}
if (-not (Test-Path $Apk)) { Fail "APK not found: $Apk" }
Say "APK: $Apk"

# Guard against installing an outdated APK over the Android Auto build: the filename carries the
# version (NewTube_<version>_<abi>.apk, see CopyRenamedApks in smarttubetv/build.gradle). Bump
# ReleaseVersion at every release together with versionName in smarttubetv/build.gradle.
$ReleaseVersion = "1.17.1"
if ((Split-Path -Leaf $Apk) -notlike "*$ReleaseVersion*") {
    Fail "This APK doesn't look like version $ReleaseVersion ($(Split-Path -Leaf $Apk)). The Android Auto release is NewTube_${ReleaseVersion}_*.apk - download the newest one into this folder and run this again."
}
Say "APK version: $ReleaseVersion (matches this release)."

# 2. adb: on PATH, or Google's platform-tools downloaded once next to this script.
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
    $adb = Join-Path $here "platform-tools\adb.exe"
    if (-not (Test-Path $adb)) {
        Say "Downloading Android platform-tools (adb) from Google..."
        $zip = Join-Path $env:TEMP "platform-tools.zip"
        Invoke-WebRequest "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -OutFile $zip -ErrorAction Stop
        Expand-Archive $zip -DestinationPath $here -Force
    }
}
function A { & $adb @args 2>$null }

# 3. The phone: USB first, then wireless debugging found on the network.
A start-server | Out-Null
function Devices { (A devices) | Select-String '\tdevice$' | ForEach-Object { ($_.Line -split '\t')[0] } }
$serial = Devices | Select-Object -First 1
if (-not $serial) {
    $svc = (A mdns services) | Select-String '_adb-tls-connect' | Select-Object -First 1
    if ($svc) { A connect (($svc.Line -split '\s+')[-1]) | Out-Null; $serial = Devices | Select-Object -First 1 }
}
if (-not $serial) {
    Say "Waiting for the phone. On the phone: Settings > Developer options > USB debugging ON,"
    Say "plug it in with a cable and tap 'Allow' on the prompt."
    for ($i = 0; $i -lt 60 -and -not $serial; $i++) { Start-Sleep 3; $serial = Devices | Select-Object -First 1 }
}
if (-not $serial) { Fail "No phone found." }
Say "Phone: $serial"
function P { A -s $serial @args }

function State {
    $out = @()
    foreach ($p in $packages) { $out += (P shell dumpsys package $p) | Select-String 'lastUpdateTime|initiatingPackageName' | ForEach-Object { "$p $($_.Line.Trim())" } }
    $out -join ' | '
}
$before = State

# 4. Copy the APK to Download and find its media-store id (the system installer reads it there).
Say "Copying the APK to the phone..."
P push $Apk /sdcard/Download/NewTube-Auto.apk | Out-Null
P shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/NewTube-Auto.apk | Out-Null
$q = Join-Path $env:TEMP "newtube-q.sh"
[IO.File]::WriteAllText($q, "content query --uri content://media/external/file --projection _id --where `"_data LIKE '%/Download/NewTube-Auto.apk'`"`n")
P push $q /data/local/tmp/newtube-q.sh | Out-Null
$id = $null
for ($i = 0; $i -lt 10 -and -not $id; $i++) {
    Start-Sleep 1
    $row = (P shell sh /data/local/tmp/newtube-q.sh) | Select-String '_id=(\d+)' | Select-Object -Last 1
    if ($row) { $id = $row.Matches[0].Groups[1].Value }
}
if (-not $id) { Fail "The phone did not index the APK. Try again." }

# 5. System installer, Google Play named as the installer.
P shell input keyevent KEYCODE_WAKEUP | Out-Null
P shell am start -a android.intent.action.INSTALL_PACKAGE -d "content://media/external/file/$id" `
    -t application/vnd.android.package-archive `
    --es android.intent.extra.INSTALLER_PACKAGE_NAME com.android.vending --grant-read-uri-permission | Out-Null
Say ""
Say "On the phone (unlock it if needed): tap Install / Update."
Say "If Google Play Protect warns about the app: More details > Install anyway."

for ($i = 0; $i -lt 100; $i++) {
    Start-Sleep 3
    $now = State
    if ($now -ne $before) {
        if ($now -match 'packageinstaller') {
            P shell rm /sdcard/Download/NewTube-Auto.apk /data/local/tmp/newtube-q.sh | Out-Null
            Say ""
            Write-Host "Done. Android Auto will list NewTube; reconnect the car." -ForegroundColor Green
            exit 0
        }
        Fail "Installed, but not through the system installer: Android Auto may refuse it. Run this again."
    }
}
Fail "The install was not confirmed on the phone in 5 minutes. Run this again."
