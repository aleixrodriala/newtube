#!/usr/bin/env bash
set -euo pipefail
# Optional benchmark input, not shipping assets. Synthetic 720p AVC/AAC, 60 seconds,
# aligned two-second video fragments. -n refuses to overwrite existing output.
cd "$(dirname "$0")/.."
comparison_assets=smarttubetv/build/generated/sabrComparisonAssets/sabr-comparison
mkdir -p "$comparison_assets"
ffmpeg -nostdin -n -hide_banner -loglevel error \
  -f lavfi -i testsrc2=size=1280x720:rate=24 -t 60 -an \
  -c:v libx264 -threads 2 -preset veryfast -b:v 2000k -maxrate 2000k -bufsize 4000k \
  -g 48 -keyint_min 48 -sc_threshold 0 -pix_fmt yuv420p -video_track_timescale 12288 \
  -movflags empty_moov+default_base_moof+frag_keyframe \
  "$comparison_assets/avc.mp4"
ffmpeg -nostdin -n -hide_banner -loglevel error \
  -f lavfi -i sine=frequency=440:sample_rate=48000 -t 60 -vn \
  -c:a aac -threads 2 -b:a 96k -frag_duration 2000000 \
  -movflags empty_moov+default_base_moof \
  "$comparison_assets/aac.mp4"
