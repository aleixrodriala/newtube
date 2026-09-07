# SmartTube network and playback comparison — 2026-09-07

Upstream refs were fetched and checked against GitHub's current commit history.
This is a selective comparison of the playback/network changes since the port's
last common upstream ancestry, not a merge of the TV application into the phone app.
No branch, commit, push, or submodule-pointer changes were made during this audit.

| Repository | Latest upstream master checked | Commit time |
| --- | --- | --- |
| [SmartTube](https://github.com/yuliskov/SmartTube/commit/f23438ba2f1ad25a965692d3aeed13d63e8f8ed0) | `f23438ba2f1ad25a965692d3aeed13d63e8f8ed0` | 2026-09-06 03:50:00 +03:00 |
| [MediaServiceCore](https://github.com/yuliskov/MediaServiceCore/commit/0b01a01730f256b3dde3cc9eb4d8ac90e48f3946) | `0b01a01730f256b3dde3cc9eb4d8ac90e48f3946` | 2026-09-06 03:48:45 +03:00 |
| [SharedModules](https://github.com/yuliskov/SharedModules/commit/86f032738e3a24f6ee85c7a4ccd9524b0d20b7aa) | `86f032738e3a24f6ee85c7a4ccd9524b0d20b7aa` | 2026-09-03 23:38:33 +03:00 |

Those MediaServiceCore and SharedModules hashes are also the gitlinks selected
by current upstream SmartTube. Local dependency HEADs before these edits were
`04d65fe25419431f8549c6b5bc35b4316fb49ee4` and
`29b83b5087ab3a83b30d73ade076dec5d07dda3d`, respectively. An upstream commit
missing from local ancestry does not mean its behavior is missing: this fork
already contains selective ports and independent changes.

## Missing fixes selected for this round

1. [QueryBuilder JSON validation, MediaServiceCore `4d128db8`](https://github.com/yuliskov/MediaServiceCore/commit/4d128db8db4ff3877dae657cb25abf1022ba7d20).
   Optional request chunks leave trailing commas before closing objects. Local
   `QueryBuilder.build()` still returned those invalid JSON bodies. Upstream
   removes every literal `,}`; the port removes only syntax commas outside
   quoted strings, including correct escaped-quote/backslash handling. Existing
   request values, client selection, and timestamp normalization stay intact.
   `QueryBuilderJsonTest` strictly parses the real generated bodies for every
   existing client in player and browse modes, checks optional values, preserves
   quoted comma/brace content, and refuses to silently repair an unfinished string.

2. [BufferingDetector duration accounting, SmartTube `48995334`](https://github.com/yuliskov/SmartTube/commit/489953341eb44b43d5fe23e359321d8554469133).
   Local stop notifications counted elapsed time even when no buffering interval
   was active and left the interval start set. Repeated play/pause notifications
   could therefore inflate the total; the resulting negative callback delay is
   ignored by this port's `Utils.postDelayed`, suppressing the intended rescue.
   The selective correction uses monotonic uptime, counts each active interval
   once, ignores duplicate starts, and clamps the delay to zero. Regression tests
   cover the actual detector and callback scheduling.

3. [Helpers integer overflow fallback, SharedModules `1845eaa7`](https://github.com/yuliskov/SharedModules/commit/1845eaa746eca32483d8ea27c5ae6529b2b44250).
   `isInteger` accepts numeric strings beyond the signed-int range, after which
   `Integer.parseInt` threw. Playback metadata uses this helper, including
   `YouTubeMediaFormat.create()` for `approxDurationMs`. The selected change
   returns the caller's default on overflow, retaining normal signed parsing.
   Five actual-helper tests cover boundaries, overflow, caller defaults, invalid
   input, and the array overload.

These fix serialization and recovery correctness. They are not, by themselves,
evidence of faster accepted YouTube playback.

## Relevant changes already covered or deliberately not imported

| Upstream change | Comparison and decision |
| --- | --- |
| Request timestamp null guard (`2ee29c4e`) | Already present as nullable `signatureTimestamp?.let`. The phone's existing Cobalt-only timestamp scope is more specific than upstream's TV-wide scope and is retained. |
| Remove 403 transport switching (`4bd8338`); stop opening controls on errors (`f23438b`) | Local source errors already reload without changing the unused TV transport preference or forcing controls open. The phone has its own bounded recovery, connectivity callbacks, notices, and Cronet-to-OkHttp startup fallback. |
| Custom-DNS startup engine switching (`d742d75`) | Changes legacy TV engine selection. Media3 uses its own transport factory and measured startup fallback, so the old preference switch would not change this player's transport. |
| DNS-default changes (`00fb0fc`, `11116d2`) | Net result matches local `DNS_TYPE_IPV4`. The latter commit's subject says IPv6, but its code explicitly selects IPv4; no DNS-default change is warranted by this diff. |
| [Bounded Rx worker/queue (`bc67a7e`, `86f0327`)](https://github.com/yuliskov/SharedModules/commit/86f032738e3a24f6ee85c7a4ccd9524b0d20b7aa) | Missing locally, but not imported verbatim. `CallerRunsPolicy` executes saturated work on the submitting thread; `RxHelper.setup()` schedules service work subscribed from UI paths. A busy queue could therefore put blocking service work on the main thread. A future bound needs a rejection/priority design that preserves startup latency. |
| [Alternate format source after an unplayable result (`0b01a017`)](https://github.com/yuliskov/MediaServiceCore/commit/0b01a01730f256b3dde3cc9eb4d8ac90e48f3946) | Adds a separate Innertube/watch-page format path after legacy denial. It does not shorten successful startup and has not been reconciled with this port's negative cache, cancellation, and terminal-denial rules. Left out. |
| SABR audio metadata and detection (`c6e1d7f7`, `e42a7a9b`, `b3b1cd71`, `29c40c2e`, `aab3db91`) | The production Media3 source for SABR is still absent. Broadening format classification cannot implement the missing streaming source; importing these alone would not create working playback. |
| SABR retry patch (`66cf8bb`) | Upstream removed it again in `893e08a`. It targets the deleted legacy ExoPlayer stack and is not a current upstream fix to copy. |
| Extractor warmup/variant work (`e946b1ee`, `e37774d7`) | Local code already warms the runtime and additionally memoizes the fetched player body during construction and isolates warmup from the first validation. No missing transform correction was established by this audit. |
| Playlist cache and history removal/re-add (`82fbbfbf`, `0b66e615`, `a11439ea`) | Separate playlist/history behavior, outside this startup/network implementation slice. No change made. |

## Verification and Pixel network pass

The coordinated Android verification passed 150 focused common/youtubeapi/app
tests, followed by all five new `HelpersParseIntTest` tests. The debug build passed.
Candidate APK SHA-256:
`fc20a2170bb2dbdabb41d8cbf0f059a7db8e15b1f669d9349d1a3d6cd6ed979f`.
It was installed in place on the Pixel at 22:06:55 and the device APK hash matched.

The second Pixel pass used `tools/netshape.py` through USB `adb reverse`. Host
listeners were restricted to `127.0.0.1:18280` (proxy) and `:18281` (control).
An unlimited normal-playback run observed 14,755 KiB of googlevideo traffic,
175 KiB of YouTube API traffic, and 65 KiB of artwork through the proxy, with
zero proxy errors. A screenshot confirmed visible video and rendered metadata.
These counters establish that the app's distinct traffic classes share the
bucket; they include encrypted transport overhead and are not exact per-phase
media payload totals. This rig exits through the Linux host and disables QUIC;
it is a shaped host-link test, not direct LTE/QUIC evidence.

An ABBA comparison used the same candidate, the same video (`Fo89b8zAIE4`),
500 ms startup gate, media-cache bypass without deletion, normal cold deep links
at `t=1s`, and 30-second phase windows. The shared profile was 1500 kbit/s down,
500 kbit/s up, and 120 ms added connection-establishment delay. The last value
does not add latency to every request on an existing HTTP/2 connection.

| Arm | Cold `/next` policy | Tap to decoded frame | Successful recovery episode | `/next` requests |
| --- | --- | ---: | ---: | ---: |
| A1 | Eager/default | 4085 ms | 1358 ms | 1 |
| B1 | Deferred (`eager_cold=0`) | 4111 ms | 1502 ms | 2 |
| B2 | Deferred (`eager_cold=0`) | 4003 ms | 1495 ms | 2 |
| A2 | Eager/default | 4256 ms | 1734 ms | 1 |

The median difference was 114 ms (2.7%), with overlapping results and opposite
directions in the two surrounding pairs. Both deferred arms issued a duplicate
`/next` during the existing initial-media-error recovery. Deferral is to
`onVideoLoaded`, which precedes the first frame; it does not remove metadata
traffic from the whole startup interval. Eager loading remains the default.

All four arms encountered one existing initial media 403 and recovered through
the app's normal flow. No terminal denials, exhausted recovery, or post-READY
buffering occurred during these four windows. Timing is explicitly to decoded
frames: the still is already removed by the first error, so the benchmark's
per-episode visibility counter is absent for the later recovery frame.

The first three-fix candidate check on direct cellular used a separate
browse-immediate launch: 3048 ms to decoded frame, one normal initial-403
recovery, and one 14 ms post-READY buffering event during 25 seconds. Because
the launch method differed, it is acceptance evidence rather than a paired
speed comparison with the first agent's cold-deep-link runs.

The weaker-link baseline on the same candidate used 700 kbit/s down, 300 kbit/s
up, and 180 ms connection-establishment delay for 45 seconds. Decoded TTFF was
7166 ms (4349 ms in the successful recovery episode), again with one initial
403 followed by successful normal recovery. There was no post-READY buffering
in the window. Selection moved from 1080p/itag 248 to 144p/278, then 240p/242;
app UID received 3,848,702 bytes. The slow first media transfer is now a larger
part of startup than in the 1500 kbit/s case.

Private sanitized logs, JSON results, per-host proxy counters/timelines, and
screenshots: `/tmp/newtube-pixel-network-20260907-os5xFV`.

## Parser candidate and outage follow-up

The subsequent candidate reuses already-parsed Gson nodes while mapping nested
JsonPath response objects. Seven regression tests verify mapped values and tree
reuse; the dense fixture uses one root text parse, compared with 76 in the legacy
implementation, and reuses 75 incoming query-result nodes. Final isolated checks
passed **175 tests in 26 suites, with zero skips, failures, or errors**. Debug and
release builds passed.

The immutable debug APK is
`/tmp/newtube-ttff-upstream-20260907-WL5QR2/parser-debug.apk`, SHA-256
`88c4e8a365b863b21a1f75956dc45d3fa67308376942e15328f21a250e95dbec`.
It was installed in place at 22:24:27 and its device hash matched. The release
artifact is `parser-release.apk` in the same private directory, SHA-256
`da00f45be74989c1c1533d54d1b7d2278ddf2173c1ccb9e81f1a0c5672e75486`.
Only that immutable debug APK was installed for the following comparisons.

| Shared profile | Three-fix baseline decoded TTFF | Parser candidate decoded TTFF | Interpretation |
| --- | --- | --- | --- |
| 1500/500 kbit/s, 120 ms connection delay | 4085, 4256 ms | 4227, 4039 ms | Median 4171 → 4133 ms: effectively unchanged in this sample. |
| 700/300 kbit/s, 180 ms connection delay | 7166 ms | 5283, 4621 ms | Lower in both candidate runs, but only one baseline and no reversed APK comparison. |

All these cold opens retained the 500 ms startup gate, eager `/next`, media-cache
bypass, existing sessions, and `t=1s`. Every one still encountered an initial
403 followed by the application's normal recovery. No terminal denial occurred.
The 1500 kbit/s candidate windows were 30 and 70 seconds and had no post-READY
buffering. Both 700 kbit/s windows were 45 seconds; the first had one 32 ms
post-READY buffering event and the second had none.

Decoded frame time is not always time to continuous playback. On the 700 kbit/s
profile, baseline READY was at 7222 ms; candidate READY was at 5365 and 6605 ms.
In the latter run the first frame preceded READY by 1984 ms. The weak-profile
candidate medians are therefore 4952 ms to a decoded frame and 5985 ms to READY.
Artwork/cache warmth, server/network variation, and concurrent casting work in
the shared workspace prevent attributing all of the APK timing difference to the
parser alone. The measured reduction in redundant parser work is independently
covered by the offline tests; the faster-link samples do not show a broad TTFF gain.

Following the second 1500 kbit/s candidate window, the same buffered playback
continued through a ten-second whole-app blackout, **22:30:12–22:30:22**. The
independent 45-second `blackout-followup.log` captured two expected media-open
timeouts at about eight seconds. The same audio and video load IDs completed
successfully at 22:30:26.907 and 22:30:27.868, approximately 4.9 and 5.9 seconds
after traffic resumed, and subsequent media loads continued. No player IDLE,
BUFFERING, or engine-reload event occurred in that follow-up. A screenshot after
the outage confirmed rendered video and metadata. This validates buffer absorption
and media-request resumption; it does not exercise the 20-second long-buffering
watchdog or establish recovery from a longer unbuffered outage.

The final direct-cellular cold-deep-link check, with Wi-Fi off and the proxy
removed, reached a decoded frame in **3985 ms** (909 ms in the successful recovery
episode). It retained the existing initial 403 and normal recovery, then played
through the 30-second window without post-READY buffering or terminal denial.
Media requests negotiated **HTTP/3** and returned successful 206 ranges. This is
acceptance evidence; it does not establish a faster direct-cellular cold start.

Final handback readback on USB Pixel `4A120DLAQ0049N`: Wi-Fi **1**, mobile data
**1**, global HTTP proxy **`:0`**, no remaining reverse mappings, and no listeners
on the test proxy/control ports. The temporary shaper process was terminated.
`debug.arc.media_cache` and `debug.arc.start_buffer_ms` were restored to their
original `none` values; `debug.arc.eager_cold` was restored to its original empty
value. All other `debug.arc` values matched the original readback. The app was
force-stopped and relaunched to the browse screen, with the phone unlocked and
no playback left running. Its installed APK was reverified as SHA-256
`88c4e8a365b863b21a1f75956dc45d3fa67308376942e15328f21a250e95dbec`,
last updated at 22:24:27. Account/session data and caches were not cleared, and
no forced client, identity, or token settings were changed.
