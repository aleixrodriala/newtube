# Pixel startup ABR: constrained-network and direct-cellular observations

## Scope and immutable builds

Pixel 9, Android 17/API 37, USB serial `4A120DLAQ0049N`, app `io.github.aleixrodriala.arc` 1.7.0. User data was retained with `adb install -r`; no account, service-client, identity, route/quarantine or token settings were changed. Concurrent external installations were observed before testing, so the user paused the other session and we copied/hash-verified the latest stable installed APK before proceeding. The older `88c4…` candidate was **not** used as this baseline.

- Installed baseline: `46fff860c2f45c006c7cbc2af44ae7321e4197838898455ff414833191d885eb`, copied unchanged from the device after the final external install at 23:13:14.
- Startup-ABR candidate: `11626d61c30f2ad616d1b26a6fc7463b7650e1a584cac20074a2d7e40b97482a`, verified after installation. `startup_abr` was unset/default-on, and `next_media_preload` remained unset/off.
- Private evidence: `/tmp/newtube-pixel-startup-abr-20260907-hFM2tk/`, including the baseline APK, each run's sanitized playback log/results and `original-device-state.json`.

These are bounded debug-build observations, not release Macrobenchmark results or a randomized causal estimate. The candidate naturally persists measured bandwidth; subsequent runs intentionally retained this normal state. No seed/preferences reset was used to simulate independence.

## Matched workload and measurement meanings

Each run cold-force-stopped the app, then opened ordinary video `Fo89b8zAIE4` at `t=1s` using `tools/playback-benchmark.py --mode soak`. App media-cache reads/writes were bypassed with `debug.arc.media_cache=off`; the startup gate stayed 500ms. The weak profile had a 45s requested window; the 1.5Mbit profile had 30s. All live arms had one initial media failure and the app's existing normal recovery, then playback. No final denial or exhausted recovery occurred; none was retried by the operator.

`frame` below is first decoded-frame time from the initial app tap/open origin, including the initial recovery. `READY` is the corresponding first successful player READY event. It is a stronger playback gate than first decode alone, but is **not** a validated overlay/picture-visible timestamp: the still was removed during the initial error, so no same-episode visible-picture milestone exists. `prepare → …` isolates the successful recovery episode after media information was obtained.

The loopback whole-app HTTP CONNECT shaper used USB `adb reverse`, global proxy `127.0.0.1:18380`, shared upload/download buckets, and one added delay at CONNECT establishment. The named delay is **not** per-packet RTT. App API, thumbnails and media share the limit. Other device background applications also used the global proxy, so their contention is an uncontrolled source of variance. UID byte counters below isolate this app. The host is the Internet exit, QUIC is unavailable through CONNECT, and these shaped runs are **not direct LTE/5G measurements**. Wi-Fi/data remained enabled for the shaped runs.

## Results

| Profile / run | Frame | READY | Successful prepare → frame | Successful prepare → READY | Initial video |
|---|---:|---:|---:|---:|---|
| 700/300 kbit/s + 180ms CONNECT, baseline | 5,855ms | 5,948ms | 2,798ms | 2,891ms | 1080p / itag248 |
| Same profile, candidate A | 4,672ms | 5,559ms | 1,681ms | 2,568ms | 360p / itag243 |
| Same profile, candidate B | 4,001ms | 5,554ms | 886ms | 2,439ms | 240p / itag242 |
| 1500/500 kbit/s + 120ms CONNECT, baseline | 4,226ms | 4,301ms | 1,277ms | 1,352ms | 1080p / itag248 |
| Same profile, candidate | 3,446ms | 3,604ms | 408ms | 566ms | 240p / itag242 |

At 700 kbit/s, READY improved by 389–394ms, less than the 1.18–1.85s decoded-frame improvement because audio/buffer readiness followed decoding by 887–1,553ms. Successful prepare→READY improved 323–452ms. At 1.5Mbit/s, READY improved 697ms and prepare→READY improved 786ms. Metadata/recovery timing does not explain these gains: successful prepare occurred around 2.95–3.12s from the original tap across these arms. The repeated weak-link READY values are encouraging, but one baseline and small candidate sample cannot establish a general distributional improvement.

No post-READY BUFFERING state was recorded. All arms used Opus itag251 (~170,509bit/s); VP9 video bitrates were 2,289,375 at 1080p, 494,908 at 360p, 208,220 at 240p and 100,867 at 144p.

| Profile / run | Actual capture window | App UID RX | App UID TX | Main-process median PSS |
|---|---:|---:|---:|---:|
| Baseline 700 | 46.44s | 3,589,431B | 104,990B | 338.8MiB |
| Candidate 700 A | 46.39s | 3,639,857B | 115,701B | 345.0MiB |
| Baseline 1500 | 31.48s | 5,439,244B | 100,442B | 464.0MiB |
| Candidate 1500 | 31.51s | 5,426,643B | 96,047B | 447.1MiB |

Candidate 700 B has valid startup timings and approximately 31s of uninterrupted post-READY progress, but the operator accidentally paused at EventLogger time 36.39s and resumed at 46.76s while the 47.98s capture was finishing. Its full-window bytes/CPU are excluded from matched soak comparisons. This was an operator pause, not a network buffering event.

## Seed provenance and adaptation

The baseline reported a stored Wi-Fi seed of 27,008,854bit/s and began at 1080p on both shaped links. Candidate A logged `seed=1000000 storedSeed=27008854 bootstrap=fresh`: the stale legacy value was not taken as current link capacity. It started at 360p, dropped to 144p about 3.7s after READY, and returned to 240p about 25s after READY. Measured persistence progressed from ~326k to ~723kbit/s. It did not stay at 1080p on the weak link.

Candidate B inherited a recent, actually measured 738,915bit/s Wi-Fi seed and started at 240p. The 1500-profile candidate then inherited 722,806bit/s from the preceding weak-link session. It measured ~1.535Mbit/s by READY+2.13s and upgraded to 480p by READY+8.92s. These are normal cross-run adaptations, not seed-isolated A/B arms. The stock steady-state ABR remains responsible for subsequent quality changes.

## Direct cellular sanity check

After the shaped tests, the proxy was disabled and Wi-Fi turned off over stable USB; the active default network was verified `CELLULAR`, `INTERNET`, `VALIDATED`. No preferred radio generation was changed. The base radio was LTE, with app bandwidth samples subsequently classified as 5G-NSA; do not label this forced or pure LTE.

The same candidate/video/cache/gate workload produced a frame at 3,555ms and READY at 3,642ms in a 48.02s capture. It began at 360p with effective 1Mbit/s versus a stale stored 26.7Mbit/s cellular seed, recorded a measured ~20.56Mbit/s 5G-NSA estimate at READY+356ms, and selected 1080p by READY+8.927s. No post-READY buffering or final denial occurred. App UID RX was 18,436,056B and TX 179,565B; median main-process PSS was 402.1MiB. There is no new matched baseline for this direct-cellular arm, so it is an adaptation/stability check, not a claimed cellular TTFF gain.

## State and next validation

After the constrained/direct-cellular testing, the owned shaper process was stopped, its single `tcp:18380` reverse mapping removed, and original Wi-Fi=1, mobile data=1, proxy=`:0`, media-cache property=`none`, startup-gate property=`none` were restored and read back. New startup/preload/fixture controls remained unset at that handoff. No app data was cleared. Subsequent offline/profile verification replaced the test APKs in place; the final normal debug installed and hash-verified for the closing checks is `42047861f47cb8e5e66c9e2ecd0d2f8e0733cc583877635f5821223c8dae463d`, not the `11626…` network-comparison candidate. The immutable baseline and measured candidate evidence remain available for exact attribution or rollback if requested.

The separate [preload report](NEXT-MEDIA-PRELOAD-2026-09-07.md) records hardware sample-handoff correctness; the [non-debuggable benchmark report](RELEASE-BENCHMARK-2026-09-08.md) records profile generation, compiled-artifact validation and the comparison's lack of a defensible profile speed gain. Those results are not live-network TTFF measurements. See [the release benchmark harness](../../macrobenchmark/README.md) for the reproducible offline workflow and its code-cache/compilation effects.

## Closing ordinary autoplay check and final restoration

On final debug `420478…`, one 111.51s ordinary, unshaped Wi-Fi capture opened the same video at 1,094s of its 1,169s duration. The existing auto-advancing **Shuffle mode (4)** and 1.0× speed were retained, with normal media caching and only `debug.arc.next_media_preload=1` temporarily enabled. No mode, queue, quality, service-client or route preference was changed.

The natural minute tick predicted `X1bx9_TL20A` and started a two-second speculative preload. Media requests returned 403 and the preloader stopped with `reason=error` after 115ms. There was **no ready event or matching hit**, so this is failed real-network preload acceptance, not an optimization success. The existing normal autoplay then advanced to that video, recovered once through the app's unchanged behavior, decoded a frame 936ms after its initial open and reached READY at 1,026ms. Approximately 30s more playback were captured without another post-READY buffering state or final denial on the next video. No operator retry, denial workaround or follow-up matrix was performed.

The original video's playback was not entirely uninterrupted: an audio underrun caused a 7ms BUFFERING interval about 1.7s after READY, approximately 51s **before** preload started. This cannot be attributed to preload, but must not be omitted from the live stability result. The capture's exact app-UID RX/TX totals were 29,781,252B / 233,362B. A private screenshot taken after pausing confirmed an actual video picture was visible; it does not provide a first-picture timestamp.

At final handoff, all 19 captured `debug.arc.*` controls matched their original readbacks exactly. Preload/startup-ABR/benchmark-fixture properties were empty, media-cache/start-buffer properties were `none`, Wi-Fi and both the global/active-subscription mobile-data switches were enabled, proxy was `:0`, and no ADB reverse mapping remained. The experimental engine was destroyed by a normal process restart, then the exported Splash entry returned to **MobileBrowseActivity** with no playback running. The final debug APK's full SHA-256 was verified again. The test packages and private evidence were retained; no app data was cleared.

Playback mode remained 4, speed 1.0, and the stored quality field's SHA-256 remained `76776a7037923e39e594c09d296d009c85ca9c4c9074411180f67b10807cb9d2`. Final evidence includes `final-autoplay-acceptance/`, `final-paused.png`, `preload-final-instrumentation.txt` and `final-device-state.json` in the private directory. Pixel ownership is released. Production media preloading remains off.
