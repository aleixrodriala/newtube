# Pixel playback performance round — 2026-09-07

Historical round: the owner's later TTFF/stability-first decision supersedes the
1000 ms recommendation below. See [the follow-up](TTFF-PRIORITY-2026-09-07.md)
for the promoted 500 ms gate, local Pixel stability checks, and denial handling.

## Outcome

Implemented and installed targeted waste/lifetime fixes. The controlled matrix
does **not** demonstrate a broad CPU, memory, or decoded-TTFF improvement. Keep
the production 1000 ms start gate, 1500 ms rebuffer gate, eager startup setup,
and Media3's default fixed scheduling. No quality or forward/back-buffer
settings were reduced to manufacture a performance win.

The last two attempted soaks were blocked **before media preparation** by
YouTube's explicit `LOGIN_REQUIRED` / bot-check response. They are failed
playback tests, not clean low-CPU playback. This also prevented final normal-
settings sustained validation and longer validation of the 500 ms experiment.
No client, credential, visitor-source, or token policy was changed in this round.

## Changes retained

- Explicit timestamp opens no longer seek first to saved history and immediately
  seek again to the requested timestamp. A captured baseline restored 259957 ms
  then sought to 1000 ms nine milliseconds later; the candidate seeks once.
  Normal history, live behavior, speed, volume, pitch, and play-state restoration
  remain covered by tests.
- Notification artwork uses a fitted 480 px CDN image, shares in-flight loads
  across repeated notification updates, and rejects obsolete completions using
  both request generation and the currently selected video. Media-session event
  updates are batched. Retained art is cleared on service release.
- Watch metadata is gated separately for **each** selected video, binds when the
  new picture replaces the loading still, and consumes the pending document.
  Audio-only/error paths and an independent six-second deadline prevent a
  missing frame from stranding the header. Stale video documents are rejected.
- The loading-still fallback now honors the same cache-only restriction as its
  primary request. Previously that fallback could start a network download.
- Hidden/stopped playback controls stop progress/autohide callbacks and rearm
  on return when controls are visible.
- Speculative session warmup uses cancellable timers instead of two sleeping
  worker threads. Actual fetch work creates a worker only after claiming its
  gate; playback and memory pressure cancel pending speculation.
- Persisted extractor-cache and HTTP/2 configuration now precede eager startup
  setup, closing an initialization race without changing setup semantics.
- Stable media cache keys memoize the last 64 signed URIs across range requests.
  The on-disk key format, format/version dimensions, live sequence distinction,
  and generic explicit-key fallback remain unchanged.

## Controlled matrix

Pixel 9, Android app `io.github.aleixrodriala.arc`, debug arm64 build, Media3
1.10.1, cellular 5G, thermal status 0 at checks. Only the Pixel was targeted.
Three fixed video IDs: `Fo89b8zAIE4`, `u_vnA6nlDvs`, `yi0PiY1i3XU`.
Both arms requested **t=1s** to avoid different saved-history positions. Media
disk-cache reads/writes were bypassed with the existing debug flag; account,
application data, extractor caches, and media cache contents were not cleared.

Each arm: three force-stopped direct opens; three force-stopped app-launch then
immediate video opens; six reused-activity switches; one ~two-minute sustained
run. All 26 controlled phases reached a first frame with zero player/media-load
errors. A brief BUFFERING transition near a SponsorBlock skip and isolated
dropped-frame reports mean this is **not** a claim of universally stall-free
playback. Initial selections were 1080p VP9 (itag 248, 24 fps) and Opus 251,
with the hardware `c2.exynos.vp9.decoder`.

| Metric | Baseline | Candidate experiment |
|---|---:|---:|
| Cold direct decoded TTFF, median of 3 | 2052 ms | 2172 ms |
| Cold app launch → immediate open, app TTFF, median of 3 | 1384 ms | 1391 ms |
| Video switch decoded TTFF, median of 6 | 418 ms | 446 ms |
| Host launch → frame, cold direct median | 3194 ms | 3347 ms |
| Host launch → frame, immediate-open median | 4862 ms | 5126 ms |
| Host launch → frame, switch median | 1562 ms | 1342 ms |
| Sustained main-process CPU, after first 30 s | 59.10% of one core | 60.11% of one core |
| Sustained sparse-sample median process PSS | 460.3 MiB | 473.2 MiB |
| Sustained received bytes / actual observation window | 38.25 MB / 127.55 s | 40.52 MB / 132.14 s |
| Six switches' received bytes / summed windows | 91.42 MB / 116.8 s | 85.34 MB / 113.3 s |

The candidate matrix enabled dynamic scheduling as an experiment; the installed
final defaults do not. These are sequential live-network runs, not enough
repetitions for statistical significance. Different observation windows, ABR,
radio/server variability, and cache warming prevent interpreting the byte
totals as a clean bandwidth-saving percentage. The source fixes remove specific
redundant work, but no whole-app percentage saving is established here.

## Same-build tuning experiments

### Dynamic scheduling: leave off

A 25-second `simpleperf stat --per-thread` sample measured the playback thread
at 4007.86 ms CPU / 6755 context switches on the baseline versus 4074.96 ms /
5805 with dynamic scheduling. Fewer wakeups did **not** produce a CPU reduction
in the sustained matrix. Keep both experimental scheduler/renderer flags off.
The pinned [Media3 renderer source](https://raw.githubusercontent.com/androidx/media/1.10.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultRenderersFactory.java)
and [player source](https://raw.githubusercontent.com/androidx/media/1.10.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/ExoPlayer.java)
define these disabled defaults in the version used by this app.

### Eager startup setup: leave on

ABBA immediate-after-launch tests: eager on 1520/1359 ms decoded TTFF; deferred
2366/2596 ms. The medians differ by **1042 ms against deferral**. Early sparse
PSS samples were lower when deferred, but these are not peak-memory measures.
Normal native playback also obtains its existing visitor through this demand
initialization path, so delaying it shifts work into the selected-video path.

New direct warmup logs show pending speculative format fetches were cancelled
before starting in these early-open tests. Earlier notes attributing the whole
cold-start allocation spike specifically to `SessionWarmup` were too strong:
they established timing correlation, not which startup component allocated it.

### 500 ms start gate: promising for resume, not promoted

ABBA tests used a **media-data-source-only 1500 kbit/s shaper**, not an OS-wide
link limit. At t=1s, 500 ms and 1000 ms gates had essentially the same first
frame/visible-picture latency. Four additional 25-second tests at t=60s gave:

| Start gate | Decoded TTFF | Loading still lifted |
|---|---|---|
| 1000 ms, A | 3385 ms | 4484 ms |
| 500 ms, A | 3385 ms | 3854 ms |
| 500 ms, B | 3264 ms | 3781 ms |
| 1000 ms, B | 3269 ms | 4386 ms |

The visible-start medians improved by 617.5 ms (~14%) without a decoded-TTFF
gain. All four short runs had only initial BUFFERING, no player/load errors,
and the same initial quality followed by ABR downshifts under shaping. A lower
gate provides 0.5 seconds less initial cushion. The attempted 180-second 500 ms
soak was denied by the server before playback; therefore its sustained safety
is **unvalidated**, and the production default remains 1000 ms.

## PiP and final device state

The floating video reported during the first exploratory run belonged to
`app.revanced.android.youtube`, not a second NewTube player. An incorrectly
quoted ad-hoc adb URL let Android resolve the link in that other app. The user
dismissed its PiP. That contaminated exploratory run was discarded.

The reusable harness quotes Android's second shell, verifies the activity's
package, and refuses to start while any pinned task is present. Regression
tests cover the URL ampersand retaining the package restriction.

Later, NewTube's own HOME → PiP → open another video flow passed: one pinned
NewTube activity expanded into the selected video, decoded first frame 621 ms,
one playback activity, and no remaining pinned task. Screenshots confirmed the
selected video's header and picture. No duplicate-player PiP bug was reproduced.

Final normal-settings check on Charlie Puth also received the server bot-check
before media preparation. Its screenshot confirms the displayed reason, the
correct header, and no PiP. Both blocked soaks are excluded from successful
performance numbers. Further sustained and rapid-card-tap playback validation
requires normal server playback availability; repeated denial retries were
stopped. All five properties changed during this round were reset to inactive
`none`: media cache, media shaping, start gate, dynamic scheduling, eager setup.
The normal-settings process logged a 1000 ms start gate and dynamic scheduling
off. Quality, user buffer preference (MEDIUM: 50 s forward / 96 MB target), and
120 s back buffer remain unchanged.

## Validation and reproduction

Debug APK build passed. **76 Android unit/Robolectric tests and 10 offline
benchmark tests passed**. These cover warmup ordering/gates, source generations,
network failures, cache-key dimensions, explicit-position restoration, launch
policy, artwork races, metadata invalidation, and benchmark parsing. Activity-
level stop/start metadata deadline and progress-queue integration tests remain
a coverage gap; helper tests do not substitute for those lifecycle scenarios.

```sh
./gradlew :smarttubetv:assembleStmobileDebug \
  :smarttubetv:testStmobileDebugUnitTest \
  --tests 'com.newtube.mobile.player.*' \
  --tests 'com.newtube.mobile.SessionWarmupGateTest' \
  --tests 'com.newtube.mobile.ui.playback.*' \
  :common:testDebugUnitTest --tests '*VideoStateControllerPositionTest'
python3 -m unittest discover -s tools -p 'test_playback_benchmark.py' -v
python3 tools/playback-benchmark.py --serial <PIXEL_SERIAL> \
  --output <NEW_PRIVATE_DIRECTORY> --mode matrix --duration 180
```

The harness records decoded TTFF, separately records visible-picture latency
when available, preserves the original time origin across recovery, and marks
`no_first_frame` / `no_new_open` rather than treating zero engine errors as
successful playback. Host launch-to-frame includes adb and log-receipt delay;
the immediate-launch case also includes launcher/focus checking. CPU is the
main process's `/proc` CPU time as a percentage of **one** core. UID network
counters include API/artwork traffic. PSS is sparse-sampled main-process PSS,
not peak memory, isolated decoder process memory, or an energy measurement.
Expensive samples stay off the first-frame path except unresolved opens at 8 s.

Private evidence directory: `/tmp/newtube-perf-20260907-BYcmn2` (not committed).
Use `baseline-controlled`, `candidate-controlled`, `warmup-*`, `gate-*`, and
`final-normal`, plus thread-stat files, build logs, and PiP screenshots. Ignore
the earlier exploratory `baseline`, `baseline-matrix`, and `baseline-fixed`
runs: quoting, saved-history, or initial sampler issues made them unsuitable
for comparison. Raw signed URLs are sanitized in benchmark logs.

Baseline APK SHA-256:
`7e57221a999c6719d16890d9aea5a3bda96ba03516948ce5afe9a684e8347702`.
Controlled candidate APK SHA-256:
`6ac888b68351c8024edc05b125116ca8047f31adb0e3ced4583b1c05d5d76883`.
Later startup experiment controls/logging were built and installed after that
candidate; their default setup behavior is unchanged. All source changes are
uncommitted on `main`; submodules were not changed.

Final rebuilt APK SHA-256 (installed with data preserved and verified on-device):
`671bdddd172980ba33406b62ce6715defdc96334e266ef469e059682870ede80`.
Handoff leaves NewTube's browse activity foreground with no pinned task.
