#!/usr/bin/env bash
set -euo pipefail

video_url=${1:-https://www.youtube.com/watch?v=p7YpVl35pac}
duration=${SOAK_SECONDS:-180}
package=io.github.aleixrodriala.arc
forced_client=${PLAYER_CLIENT:-}
fresh_app_info=${FRESH_APP_INFO:-0}
poison_once_itag=${POISON_ONCE_ITAG:-}
adb_serial=${ADB_SERIAL:?Set ADB_SERIAL to the exact emulator/device serial}
adb_cmd=(adb -s "$adb_serial")

command -v adb >/dev/null || { echo 'Missing required tool: adb' >&2; exit 2; }
"${adb_cmd[@]}" get-state >/dev/null

echo "Starting $duration-second device soak on $adb_serial: $video_url"
if [[ -n "$forced_client" ]]; then
  echo "Forcing /player client: $forced_client"
  "${adb_cmd[@]}" shell setprop debug.arc.player_client "$forced_client"
  # Android setprop rejects an empty value on some releases. The debug app hook
  # treats this sentinel as disabled on the next process start.
else
  "${adb_cmd[@]}" shell setprop debug.arc.player_client none
fi
"${adb_cmd[@]}" shell setprop debug.arc.fresh_app_info "$fresh_app_info"
# Do not let a stale persistent debug property contaminate a control run. The one-shot variant
# poisons one playback episode, then disarms when Media3 emits the terminal player error so the
# reloaded Web-client URL can proceed.
"${adb_cmd[@]}" shell setprop debug.arc.poison_itag none
if [[ -n "$poison_once_itag" ]]; then
  echo "Injecting one synthetic 403 for itag: $poison_once_itag"
  "${adb_cmd[@]}" shell setprop debug.arc.poison_once_itag "$poison_once_itag"
else
  "${adb_cmd[@]}" shell setprop debug.arc.poison_once_itag none
fi
cleanup() {
  "${adb_cmd[@]}" shell setprop debug.arc.player_client none >/dev/null
  "${adb_cmd[@]}" shell setprop debug.arc.fresh_app_info 0 >/dev/null
  "${adb_cmd[@]}" shell setprop debug.arc.poison_itag none >/dev/null
  "${adb_cmd[@]}" shell setprop debug.arc.poison_once_itag none >/dev/null
}
trap cleanup EXIT
"${adb_cmd[@]}" logcat -c
"${adb_cmd[@]}" shell am force-stop "$package"
"${adb_cmd[@]}" shell input keyevent KEYCODE_WAKEUP
"${adb_cmd[@]}" shell wm dismiss-keyguard
"${adb_cmd[@]}" shell am start -W -a android.intent.action.VIEW -d "$video_url" -p "$package" >/dev/null

deadline=$((SECONDS + duration))
while (( SECONDS < deadline )); do
  if "${adb_cmd[@]}" logcat -d -v brief -s NetPath:D | grep -a -q 'auto-reload cap hit'; then
    break
  fi
  sleep 5
done

"${adb_cmd[@]}" logcat -d -v time -s NetPath:D VideoInfoService:D | grep -a -E \
  'open |player-ring|winning client|info |prepare |first-frame|cronet .* (403|416) |load\[E\]|load\[E-http\]|error |auto-reload|shaper poison|one-shot' || true
