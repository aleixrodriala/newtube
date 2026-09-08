#!/usr/bin/env bash
set -euo pipefail
# Synthetic, muted by instrumentation. No downloaded or copyrighted media.
# Run from the repository root. -n refuses to overwrite any existing fixture.
mkdir -p smarttubetv/src/debug/assets/sabr
ffmpeg -nostdin -n -hide_banner -loglevel error \
  -f lavfi -i testsrc2=size=320x180:rate=24 -t 12 -an \
  -c:v libx264 -threads 2 -preset veryfast -b:v 120k -g 48 -keyint_min 48 -sc_threshold 0 \
  -pix_fmt yuv420p -movflags empty_moov+default_base_moof+frag_keyframe \
  smarttubetv/src/debug/assets/sabr/avc.mp4
ffmpeg -nostdin -n -hide_banner -loglevel error \
  -f lavfi -i testsrc2=size=160x90:rate=24 -t 12 -an \
  -c:v libx264 -threads 2 -preset veryfast -b:v 55k -g 48 -keyint_min 48 -sc_threshold 0 \
  -pix_fmt yuv420p -movflags empty_moov+default_base_moof+frag_keyframe \
  smarttubetv/src/debug/assets/sabr/avc-low.mp4
ffmpeg -nostdin -n -hide_banner -loglevel error \
  -f lavfi -i sine=frequency=440:sample_rate=48000 -t 12 -vn \
  -c:a aac -threads 2 -b:a 32k -frag_duration 2000000 \
  -movflags empty_moov+default_base_moof \
  smarttubetv/src/debug/assets/sabr/aac.mp4
ffmpeg -nostdin -n -hide_banner -loglevel error \
  -f lavfi -i testsrc2=size=320x180:rate=24 -t 12 -an \
  -c:v libvpx-vp9 -threads 2 -deadline realtime -cpu-used 8 -b:v 100k -g 48 \
  -cluster_time_limit 2000 -cluster_size_limit 0 \
  smarttubetv/src/debug/assets/sabr/vp9.webm
ffmpeg -nostdin -n -hide_banner -loglevel error \
  -f lavfi -i sine=frequency=440:sample_rate=48000 -t 12 -vn \
  -c:a libopus -threads 2 -b:a 24k -cluster_time_limit 2000 -cluster_size_limit 0 \
  smarttubetv/src/debug/assets/sabr/opus.webm
