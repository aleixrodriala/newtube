# Pixel cellular startup-gate comparison — 2026-09-07

The production **500 ms startup gate is retained**. An interleaved same-APK
500/250/250/500 comparison found essentially identical startup times for the
successful playback episode: median **906 ms at 500 versus 904.5 ms at 250**.
The larger first-open delay occurred before successful media preparation.

## Device, build, and workload

- Pixel 9, Android 17, USB serial `4A120DLAQ0049N`.
- Installed 1.7.0 debug APK, updated 2026-09-07 19:25:57. SHA-256 was verified
  before and after this baseline:
  `2a2c5644e9d034153a285d698aed65c94686a3c30f90be51112ff6f1060726d3`.
  Later candidate builds are not represented by these measurements.
- Direct validated cellular network `cell:342`, metered, no HTTP proxy. Android
  reported LTE with `overrideNetwork=NR_NSA`; the status bar displayed 5G.
  No preferred mobile-network generation was changed.
- All app traffic used the ordinary cellular connection. These runs did not
  shape bandwidth, latency, API calls, artwork, or media traffic.
- `debug.arc.media_cache=off` bypassed media cache without deleting contents.
  Each cold process opened `Fo89b8zAIE4` at `t=1s`; the sole gate change was
  `debug.arc.start_buffer_ms=500` or `250`. Existing accounts, app preferences,
  transport selection, session identity, and playback routes were preserved.
- The actual player selected 1080p VP9/24 fps plus Opus audio and the existing
  MEDIUM 50-second forward-buffer preset. Each gate arm requested a 45-second
  phase through `tools/playback-benchmark.py --mode soak --start-seconds 1`.

## Results

All four opens encountered one initial HTTP 403 episode, followed by the app's
existing automatic recovery and successful playback. There was no final denial.
Each arm reached approximately 40.9 seconds of media position with no subsequent
buffering state or player error in the captured interval. A screenshot also
confirmed the normal watch page displaying video during the second 250 ms arm.

| Arm | Tap → decoded frame, including recovery | Successful open → decoded frame | Successful prepare → decoded frame | Successful prepare → READY |
| --- | ---: | ---: | ---: | ---: |
| 500 A | 3918 ms | 901 ms | 400 ms | 518 ms |
| 250 A | 3699 ms | 885 ms | 455 ms | 521 ms |
| 250 B | 3528 ms | 924 ms | 471 ms | 539 ms |
| 500 B | 3421 ms | 911 ms | 468 ms | 573 ms |

The small difference in overall medians is not attributable to the gate:
metadata work, normal recovery, and cold-process initialization changed across
the sequence. Only two samples per setting were collected. The first episode's
format-info milestone ranged from 2126 to 2616 ms after tap; the gate controls
readiness after media preparation and cannot remove that delay.

The loading still was removed on the initial error, before a decoded frame.
Consequently the harness correctly reported no valid `picture-visible`
measurement for these recovery opens. Decoded first frame and READY are
separate events, and neither is a measured compositor presentation timestamp.

Two additional 20-second cold opens used the 500 ms gate and the same cache
bypass to cover different videos:

| Video | Overall decoded TTFF | Successful episode | Result |
| --- | ---: | ---: | --- |
| `u_vnA6nlDvs` | 3585 ms | 915 ms | One initial error, ordinary recovery, then playback |
| `yi0PiY1i3XU` | 3571 ms | 939 ms | One initial error, ordinary recovery, then playback |

These are cold-process opens, not same-process video switches. They do not
establish a live-service rejection rate, a worst-case latency, or stability
under a constrained link. The four 45-second arms each received about 18.7 MB
at the app UID, including API/artwork traffic and forward-buffer downloads.

## Evidence and device handoff

Private sanitized evidence is under
`/tmp/newtube-pixel-ttff-baseline-20260907-ChOBBw`: the four `cellular-*-[ab]`
directories, `cellular-500-u-vna`, `cellular-500-yi0`, their `results.json` and
`playback.log`, and `cellular-250-b.png`. Earlier `normal-cold` and
`normal-unlocked` captures are excluded: the first ran while the phone was
locked, and the second lost wireless ADB when the owner attached USB.

At the exclusive device handoff to the network-test agent, both modified debug
properties had been restored to their original `none` values and playback was
explicitly paused. Original Wi-Fi/mobile-data settings were `1/1`; current
settings were `0/1` for the owner-authorized cellular tests. Global proxy remained
`:0`. The second agent owns final network restoration after its tests. This
baseline agent made no runtime source changes or installs.
