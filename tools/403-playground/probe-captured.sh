#!/usr/bin/env bash
set -euo pipefail

for tool in adb curl sed; do
  command -v "$tool" >/dev/null || {
    echo "Missing required tool: $tool" >&2
    exit 2
  }
done

log=$(adb logcat -d -v brief -s NetPath:D)
url=$(sed -n 's/^.*load\[E-url\] //p' <<< "$log" | tail -1)
if [[ -z "$url" ]]; then
  echo 'No captured HTTP-error URL. Reproduce on a debug build and look for load[E-url].' >&2
  exit 1
fi

itag=$(sed -E 's/.*[?&]itag=([^&]+).*/\1/' <<< "$url")
error_line=$(grep "load\[E\].*itag=$itag" <<< "$log" | tail -1 || true)
request=$(sed -nE 's/.* req=([0-9]+)\+([0-9]+).*/\1 \2/p' <<< "$error_line")
if [[ -z "$request" ]]; then
  echo "Could not recover req=position+length for itag=$itag" >&2
  exit 1
fi
read -r start length <<< "$request"
end=$((start + length - 1))

without_pot=$(sed -E 's/([?&])pot=[^&]*&/\1/; s/([?&])pot=[^&]*$//' <<< "$url")
query_url="${without_pot}&rn=403001&range=$start-$end"
app_ua='Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36'

probe() {
  local label=$1 target=$2 use_header=$3 result
  local -a args=(-sS --max-time 20 --max-filesize 4194304 -o /dev/null -A "$app_ua")
  [[ "$use_header" == y ]] && args+=(-H "Range: bytes=$start-$end")
  result=$(curl "${args[@]}" \
    -w 'code=%{http_code} bytes=%{size_download} ip=%{remote_ip} http=%{http_version}' \
    "$target" 2>&1) || true
  printf 'itag=%s range=%s-%s variant=%-14s %s\n' "$itag" "$start" "$end" "$label" "$result"
}

echo 'The host must share the URL-minting device public egress; otherwise ip= binding dominates.'
probe original "$url" y
probe no-pot "$without_pot" y
probe query-no-pot "$query_url" n
