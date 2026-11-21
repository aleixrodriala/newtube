#!/usr/bin/env bash
# NewTube for Android Auto - installer (macOS / Linux). Same steps as install.ps1:
# Android Auto refuses car apps recorded as installed by adb or a file manager, so the APK is
# handed to the phone's system installer with Google Play named as the installer.
#   ./install.sh [path-or-https-URL-of-the-APK]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
packages="io.github.aleixrodriala.arc.auto io.github.aleixrodriala.arc"
fail() { echo; echo "ERROR: $*" >&2; exit 1; }

# Guard against installing an outdated APK over the Android Auto build: the filename carries the
# version (NewTube_<version>_<abi>.apk, see CopyRenamedApks in smarttubetv/build.gradle), and the
# expected one is RELEASE_VERSION below. Bump it at every release together with versionName.
RELEASE_VERSION="1.17.1"

# Where to fetch the release APK from when none is found next to the script (overridable).
APK_URL="${NEWTUBE_AUTO_APK_URL:-https://github.com/ruipereira1/newtube/releases/download/android-auto-v${RELEASE_VERSION}/NewTube_${RELEASE_VERSION}_arm64-v8a.apk}"

apk="${1:-}"
if [ -z "$apk" ]; then
  apk="$(ls -t "$here"/NewTube*.apk 2>/dev/null | head -1 || true)"
  # Nothing next to the script: download the release APK into this folder automatically,
  # so Install.bat / ./install.sh work out of the box in a fresh extracted folder.
  if [ -z "$apk" ]; then
    echo "No NewTube*.apk next to this script. Downloading $APK_URL ..."
    if curl -fL --retry 3 -o "$here/NewTube_${RELEASE_VERSION}_arm64-v8a.apk.tmp" "$APK_URL"; then
      mv "$here/NewTube_${RELEASE_VERSION}_arm64-v8a.apk.tmp" "$here/NewTube_${RELEASE_VERSION}_arm64-v8a.apk"
      apk="$here/NewTube_${RELEASE_VERSION}_arm64-v8a.apk"
    else
      rm -f "$here/NewTube_${RELEASE_VERSION}_arm64-v8a.apk.tmp"
      fail "No NewTube*.apk next to this script and the automatic download failed. Download it into this folder manually (or pass a path/URL as argument): $APK_URL"
    fi
  fi
fi
case "$apk" in http://*|https://*)
  echo "Downloading $apk"; curl -fL -o /tmp/NewTube-Auto-download.apk "$apk"; apk=/tmp/NewTube-Auto-download.apk;;
esac
[ -f "$apk" ] || fail "APK not found: $apk"
echo "APK: $apk"

case "$(basename "$apk")" in
  *"$RELEASE_VERSION"*) echo "APK version: $RELEASE_VERSION (matches this release).";;
  *) fail "This APK doesn't look like version $RELEASE_VERSION ($(basename "$apk")). The Android Auto release is NewTube_${RELEASE_VERSION}_*.apk - download the newest one into this folder and run this again.";
     ;;
esac

adb="$(command -v adb || true)"
if [ -z "$adb" ]; then
  adb="$here/platform-tools/adb"
  if [ ! -x "$adb" ]; then
    os=linux; [ "$(uname)" = Darwin ] && os=darwin
    echo "Downloading Android platform-tools (adb) from Google..."
    curl -fL -o /tmp/platform-tools.zip "https://dl.google.com/android/repository/platform-tools-latest-$os.zip"
    unzip -qo /tmp/platform-tools.zip -d "$here"
  fi
fi

"$adb" start-server >/dev/null 2>&1
devices() { "$adb" devices | awk -F'\t' '$2=="device"{print $1}'; }
serial="$(devices | head -1)"
if [ -z "$serial" ]; then
  svc="$("$adb" mdns services 2>/dev/null | awk '/_adb-tls-connect/{print $NF; exit}')"
  [ -n "$svc" ] && "$adb" connect "$svc" >/dev/null 2>&1 || true
  serial="$(devices | head -1)"
fi
if [ -z "$serial" ]; then
  echo "Waiting for the phone. On the phone: Settings > Developer options > USB debugging ON,"
  echo "plug it in with a cable and tap 'Allow' on the prompt."
  for _ in $(seq 60); do sleep 3; serial="$(devices | head -1)"; [ -n "$serial" ] && break; done
fi
[ -n "$serial" ] || fail "No phone found."
echo "Phone: $serial"
p() { "$adb" -s "$serial" "$@"; }
state() { for k in $packages; do p shell dumpsys package "$k" | grep -E 'lastUpdateTime|initiatingPackageName' | tr -d ' \r' || true; done; }
before="$(state)"

echo "Copying the APK to the phone..."
p push "$apk" /sdcard/Download/NewTube-Auto.apk >/dev/null
p shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/NewTube-Auto.apk >/dev/null
id=""
for _ in $(seq 10); do
  sleep 1
  id="$(p shell "content query --uri content://media/external/file --projection _id --where \"_data LIKE '%/Download/NewTube-Auto.apk'\"" \
        | sed -n 's/.*_id=\([0-9]*\).*/\1/p' | tail -1)"
  [ -n "$id" ] && break
done
[ -n "$id" ] || fail "The phone did not index the APK. Try again."

p shell input keyevent KEYCODE_WAKEUP >/dev/null
p shell am start -a android.intent.action.INSTALL_PACKAGE -d "content://media/external/file/$id" \
  -t application/vnd.android.package-archive \
  --es android.intent.extra.INSTALLER_PACKAGE_NAME com.android.vending --grant-read-uri-permission >/dev/null
echo
echo "On the phone (unlock it if needed): tap Install / Update."
echo "If Google Play Protect warns about the app: More details > Install anyway."

for _ in $(seq 100); do
  sleep 3
  now="$(state)"
  if [ "$now" != "$before" ]; then
    echo "$now" | grep -q packageinstaller || fail "Installed, but not through the system installer: Android Auto may refuse it. Run this again."
    p shell rm -f /sdcard/Download/NewTube-Auto.apk >/dev/null
    echo; echo "Done. Android Auto will list NewTube; reconnect the car."; exit 0
  fi
done
fail "The install was not confirmed on the phone in 5 minutes. Run this again."
