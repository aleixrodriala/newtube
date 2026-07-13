#!/usr/bin/env bash
set -euo pipefail

video_url=${1:-https://www.youtube.com/watch?v=p7YpVl35pac}
client=${PLAYER_CLIENT:-android_vr}
format_ids=${FORMAT_IDS:-251,248}
app_ua='Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36'

for tool in yt-dlp jq curl; do
  command -v "$tool" >/dev/null || {
    echo "Missing required tool: $tool" >&2
    exit 2
  }
done

echo "Minting one fresh $client response for $video_url"
json=$(yt-dlp -J --no-warnings --no-playlist \
  --extractor-args "youtube:player_client=$client" "$video_url")

probe() {
  local itag=$1 label=$2 url=$3 range=$4
  local result
  result=$(curl -sS --max-time 20 --max-filesize 4194304 -o /dev/null \
    -A "$app_ua" -H "Range: bytes=$range" \
    -w 'code=%{http_code} bytes=%{size_download} ip=%{remote_ip} http=%{http_version}' \
    "$url" 2>&1) || true
  printf 'itag=%-4s range=%-22s variant=%-12s %s\n' "$itag" "$range" "$label" "$result"
}

IFS=',' read -r -a ids <<< "$format_ids"
for itag in "${ids[@]}"; do
  url=$(jq -r --arg id "$itag" '.formats[] | select(.format_id == $id) | .url' <<< "$json" | head -1)
  clen=$(jq -r --arg id "$itag" '.formats[] | select(.format_id == $id) | (.filesize // .filesize_approx // 0)' <<< "$json" | head -1)
  if [[ -z "$url" || "$url" == null || "$clen" == 0 ]]; then
    echo "itag=$itag unavailable for client=$client"
    continue
  fi

  # Match media3's two meaningful request classes: a tiny initialization range and a bounded
  # content range well inside the resource. The latter is deterministic for repeatable A/B runs.
  deep_start=$((clen * 18 / 100))
  deep_end=$((deep_start + 262143))
  (( deep_end >= clen )) && deep_end=$((clen - 1))

  without_pot=$(sed -E 's/([?&])pot=[^&]*&/\1/; s/([?&])pot=[^&]*$//' <<< "$url")
  bogus_pot="${without_pot}&pot=invalid-cross-platform-token"

  echo "client=$client itag=$itag clen=$clen source_pot=$([[ "$url" == *'pot='* ]] && echo y || echo n)"
  for range in "0-2047" "$deep_start-$deep_end"; do
    probe "$itag" original "$url" "$range"
    probe "$itag" no-pot "$without_pot" "$range"
    probe "$itag" bogus-pot "$bogus_pot" "$range"
  done
done
