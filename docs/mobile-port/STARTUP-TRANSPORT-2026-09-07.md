# Pixel startup transport comparison — 2026-09-07

Follow-up to [`LIVE-PASS-2026-09-07.md`](LIVE-PASS-2026-09-07.md). The earlier
pass found successful first media requests taking about 4.4 seconds. This
round adds connection timing, compares supported transports on the Pixel,
repairs stale preconnect suppression, and replaces the slow regular HTTP
fallback with the official Media3 OkHttp adapter. Cronet remains primary,
with QUIC enabled. Client identities, tokens and TLS fingerprint settings
were not changed.

## Measured comparison

The same three Tiny Desk videos were opened on the same validated cellular
network. A debug property bypassed local media cache without deleting it.
Format-info caching and resume positions were retained. Arms ran sequentially;
network drift, changing resume positions, process restarts and occasional PiP
transitions limit comparisons of small first-frame differences.

| Transport | Charlie Puth | Rusowsky | Milo J |
| --- | ---: | ---: | ---: |
| Native Cronet, QUIC enabled | 2.679 s overall / 1.690 s recovery | 1.641 s | 1.819 s |
| Existing DefaultHttpDataSource | 8.617 s | 9.167 s | 8.897 s |
| Native Cronet, QUIC disabled | 3.938 s overall / 1.867 s recovery | 1.484 s | 1.625 s |
| Standard OkHttp 5.4.0 | 4.041 s overall / 2.096 s recovery | 1.367 s | 2.036 s |

Charlie encountered one media 403 episode in each native arm and the OkHttp
arm, followed by existing automatic recovery. Its first-open totals include
that recovery; process startup also differed between arms. The regular HTTP
arm had no 403. These observations do not establish a transport-specific
rejection rate or demonstrate that TLS fingerprinting caused the 403.

The initialization requests provide stronger evidence than the overall times:

- All six successful regular HTTP initialization requests took **8185–8292 ms**,
  across three CDN hosts. They transferred only about 2–5 KB each; later
  chunks completed normally. Metadata was already cached for those opens.
- The OkHttp arm's eight successful initialization requests took **121–366 ms**,
  including both Charlie attempts. Its seven fresh connections succeeded over
  IPv6 in **56–128 ms**. All 103 logged calls negotiated HTTP/1.1 in this arm,
  despite the client supporting HTTP/2.
- Fresh native media connections took **262–311 ms** with QUIC enabled and
  **5–65 ms** with it disabled in these samples. The engine was
  `CronetUrlRequestContext`; both arms reported negotiated protocol `unknown`.
  QUIC-disabled must not be described as proven negotiated HTTP/2.

The repeated eight-second regular HTTP delay is near its connection timeout,
but its logs do not identify the stalled address or connection phase. OkHttp's
successful IPv6 connections do not prove the cause of the old delay. The
earlier 4.4-second native stall was not reproduced in this comparison, so this
round does not establish that it has been eliminated.

## Changes

`Media3SourceFactory` now uses `OkHttpDataSource` when Cronet is unavailable or
the existing zero-byte DASH initialization timeout causes source recovery.
The network-scoped two-minute Cronet cooldown is unchanged. Ordinary HTTP 403
responses do not activate this transport switch. Cronet remains the default;
the small QUIC-disabled timing improvement does not justify a global change.

`MediaHttpClient` uses standard OkHttp TLS and hostname verification, enables
its normal fast connection fallback, and shares the existing connection pool
so default-network handover eviction also covers media. It retains configured
proxy routing/authentication, while API interceptors, origin authentication
and cookies stay out of media requests. Connect timeout is eight seconds,
read/write inactivity limits are four seconds, and there is no whole-stream
deadline. Media3 still supplies request headers, byte ranges and bandwidth
meter events; the existing media cache sits above the selected transport.

OkHttp documents its ordinary connection racing in its
[connection guide](https://raw.githubusercontent.com/square/okhttp/master/docs/features/connections.md).
The [Media3 network-stack guide](https://developer.android.com/media/media3/exoplayer/network-stacks)
documents the supported adapter. This experiment did not add custom TLS
profiles, client identities, tokens, proxies or alternate service endpoints.

`MediaHostPreconnect` previously remembered the last attempted host indefinitely,
even when the warm request failed. It now remembers successful warming for
60 seconds, retries failures after a five-second cooldown, and invalidates
old warming when the current default-network identity changes. At most two
warm requests and four remembered hosts are retained. Requests have an
eight-second deadline, a three-redirect limit and a 4 KiB body limit. Attempt
tokens prevent stale completion callbacks from refreshing newer work.

Debug-only controls allow reproducible comparisons without persistent app
preference changes:

- `debug.arc.media_cache=off`: bypass media cache; data remains intact.
- `debug.arc.media_transport=http|okhttp`: choose a comparison transport.
- `debug.arc.cronet_quic=off`: disable QUIC when constructing the engine;
  a process restart is required.

All controls are inert when unset/`none` and ignored in release builds.
Cronet logs now split DNS, connect, TLS and request-wait intervals; connect
includes TLS, so those durations must not be added. OkHttp diagnostics record
address family, connection duration, protocol and HTTP status without addresses,
signed queries, headers or exception messages. A transport completion labelled
`result=ok` alone does not imply an HTTP success status.

## Final Pixel recovery verification

The final 1.7.0 debug candidate was installed in place and its on-device APK
hash matched the local arm64 build. A normal warmup first allowed the existing
cold-start 403 recovery to settle. With cache bypassed and no transport
override, the existing debug hook then injected one zero-byte initialization
read timeout on a different Tiny Desk video.

The player logged the timeout at 11:19:00.418, selected the network-scoped
Cronet cooldown, created the OkHttp fallback at 11:19:00.969, and rendered a
frame at 11:19:01.788: **1.370 seconds after timeout detection**, including
the source reload. This measures recovery after detection; the synthetic
fault does not include waiting for a real network timeout to expire.

Fallback initialization returned HTTP 206. Fresh IPv6 connections took
65/68 ms, including TLS at 38/40 ms, and negotiated HTTP/1.1. A seek from
about 5:47 to 14:23 resumed in **0.665 seconds**. About 150 seconds of real
fallback playback, including 111 seconds after the seek, produced no further
player errors or media 403 responses. These are individual observations,
not worst-case latency or rejection-rate bounds.

All four comparison/fault properties were then cleared to inactive `none`
and the app restarted. Logs confirmed the native engine with QUIC enabled;
normal media caching was restored. Milo's usual initial media 403 recovered
automatically, rendering a frame 2.398 seconds after reload and continuing
for about 67 seconds. Charlie opened in 1.807 seconds, played for 15 seconds
and was explicitly paused at 11:23:55. No further media errors occurred in
those successful playback intervals. The phone's radios, proxy settings,
account and persisted playback preferences were not changed by this round.

## Local verification and artifacts

The final debug APK builds, and all **38 focused tests pass**: eight new
OkHttp tests, twelve preconnect gate tests, twelve default-network callback
tests, four load-policy tests and two local playback-failure tests.

```sh
./gradlew :smarttubetv:assembleStmobileDebug
./gradlew :smarttubetv:testStmobileDebugUnitTest \
  --tests 'com.newtube.mobile.player.*' \
  :common:testDebugUnitTest \
  --tests com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.DefaultNetworkRecoveryCallbackTest \
  :youtubeapi:testDebugUnitTest \
  --tests com.liskovsoft.youtubeapi.common.helpers.PreconnectGateTest
```

`MediaHttpClientTest` exercises the official adapter through localhost sockets:
exact 206 range bytes, correct handling of a range-ignored 200 response, and
terminal 403 handling without a transport-change callback. It also checks
stock trust/hostname configuration, shared pool/proxy settings, API-state
isolation (including a 401 challenge), and streaming timeout configuration.

Twelve preconnect gate tests cover expiry, failure cooldown, early/late
deduplication, network replacement, stale completion and bounded capacity.
An isolated control retaining permanent suppression fails its regression;
the repaired implementation passes.

Private evidence is under `/tmp/newtube-startup-20260907`; the preconnect
regression proof is under `/tmp/newtube-preconnect-proof`. Sanitized per-arm
logs, `transport-matrix.md` and `init-range-metrics.json` retain the comparison.
Raw device logs contain temporary signed URLs and must not be published.
