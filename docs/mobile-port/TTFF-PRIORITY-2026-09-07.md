# TTFF-first product decision and playback-denial follow-up

Latest result: the [TV metadata fix](PLAYER-METADATA-2026-09-07.md) supersedes
the all-routes-denied captures below. Existing-account TV requests now return
accepted playback metadata, but initial media ranges still return 403 before
any frame. No successful YouTube TTFF or sustained acceptance is claimed for
that candidate. The earlier credential-only diagnosis was premature.

## Product policy

The owner explicitly prioritizes **fast visible startup and stable ongoing
playback over CPU, memory, and bandwidth**. This supersedes the conservative
1000 ms startup decision in [the earlier performance round](PERFORMANCE-2026-09-07.md).
It does not mean shrinking the forward buffer or accepting arbitrary stalls.

## Implemented

- **500 ms initial/seek readiness gate**, down from 1000 ms. The recovery gate
  stays **1500 ms**; all four forward-buffer presets and the 120 s keyframe back
  buffer are unchanged. Explicit quality choices and normal ABR remain intact.
  The 250 ms candidate remains debug-only, without enough evidence to promote it.
- **Immediate new-video picture reveal** after the existing new-stream READY +
  texture-frame guard. The old 120 ms thumbnail fade no longer delays that
  reveal. Mini-player/error fades and the 300 ms opening morph remain unchanged.
  This does not expose a stale/frozen frame to manufacture a TTFF improvement.
- **Related/queue UI rendering yields to startup.** Models still update
  immediately, but repeated adapter/card submissions coalesce until the same
  playback-ready release, audio/error path, or six-second fallback. New-video
  clears and lifecycle cancellation remain immediate.
- **Native transport and media-cache index warm on a worker during app launch.**
  The existing synchronized singletons are reused, after app network settings
  are configured. No extra media/API fetch, decoder, Surface, Activity, or
  playback session is created. This intentionally spends idle resources sooner.
  Pixel logs observed 157 ms of transport initialization and 104 ms of cache
  initialization on that worker; those are work durations, **not** an isolated
  measured end-to-end TTFF saving.

The previous round's redundant-seek/artwork/metadata and other lifetime fixes
remain in place. Eager session setup stays enabled; deferring it previously
cost about one second of first-play latency. Dynamic scheduling stays disabled.

## Real Pixel test without server playback requests

YouTube continued rejecting NewTube playback, while the owner confirmed that
the same video plays in the official YouTube app on the same Pixel/connection.
To validate the media policy independently, a **debug-only, non-exported**
fixture Activity now uses the actual `Media3PlayerInitializer`, hardware decoder,
and SurfaceView with a generated 54 s, 640×360/30 fps H.264 + AAC test pattern.
Audio is decoded but muted. No fixture media bytes come from a server.

Media3 has different local-file buffering defaults. The fixture therefore has
a logical HTTPS MediaItem, but a resolver **unconditionally maps every read to
the bundled APK asset**, before the data source opens it. The existing shaper
paces those reads at 1500 kbit/s so the real network-start gate is exercised.
This tests loading/decoder readiness, not HTTP handshakes, YouTube extraction,
ABR transitions, watch-page rendering, or production end-to-end TTFF.

Interleaved same-APK ABBA on Pixel 9:

| Gate / arm | First decoded frame | Playback READY | Switched-source READY | 45 s progress |
|---|---:|---:|---:|---:|
| 1000 ms / A | 571 ms | 1122 ms | 1100 ms | 45080 ms |
| 500 ms / A | 371 ms | 687 ms | 702 ms | 45112 ms |
| 500 ms / B | 311 ms | 699 ms | 689 ms | 45125 ms |
| 1000 ms / B | 362 ms | 1060 ms | 1087 ms | 45114 ms |

Median playback READY improved **1091 → 693 ms (398 ms, ~36%)**. Switched-source
READY improved **1093.5 → 695.5 ms (398 ms)**. Every arm passed 45 s sustained
progress, pause/resume, seek, and a source switch with zero unplanned buffering
or media errors. The smaller initial cushion did not cause a stall in these
conditions; this is not a universal weak-network stability guarantee.

Earlier live-service ABBA evidence also showed a ~618 ms improvement in dense
resume's still-release time under media-only shaping. That was a **fade-start**
milestone, not fully unobscured presentation. The current harness distinguishes
`legacy-fade-start` from the new `picture-visible` overlay-GONE UI milestone;
neither is a compositor presentation timestamp. Do not add savings across the
different experiments or claim a newly measured YouTube first-frame percentile.

## Bot-check investigation and safe recovery

**The fresh server rejection remains unresolved.** In both this round's initial
availability check and final normal-settings check, the app received fresh
`LOGIN_REQUIRED`/explicit bot-check responses before media preparation. Account
requests succeeded and the account-bearing playback requests reported server
authentication. There was no 401/expired-token evidence. This does not support
blindly clearing credentials or claiming that repeating the existing sign-in
flow will repair playback.

Google's [documented device authorization flow](https://developers.google.com/youtube/v3/guides/auth/devices)
describes access to the YouTube Data API. Acceptance there does not establish
that this app's separate playback path will accept the same credential; that
distinction is an inference consistent with the captured responses. A supported
playback/authentication integration or a service-side change may be needed.
No credential extraction, identity/profile changes, challenge workaround, or
client-order changes were made in this round.

Two app/test-tool defects were fixed:

1. **Play/Pause after a pre-media denial now retries.** Previously only the
   media-error capped state owned Play-to-retry, so this denial left the control
   ineffective. A per-video gate now enters the normal format-fetch path once
   per explicit in-flight retry. Existing negative-cache/cooldown rules remain
   in force; there is no automatic retry, media-error route switching, or auth
   invalidation. New video, release, playable response, and transport failure
   clear the gate.
2. **The benchmark stops on unavailable playback.** It persists the failed
   phase and exits 2. A final bot denial stops immediately rather than waiting
   out the soak or force-stopping into the next cold phase, which would discard
   process-local cooldown state. Intermediate responses do not end a phase
   while the normal app flow is still resolving it.

Pixel verification: a Play command after the service's normal probe interval
logged `pre-media-retry user=y`, made an ordinary request, and was still denied.
A following explicit tap inside the negative-cache window returned the denial
in **7 ms with no new playback HTTP requests**. This validates the control and
request suppression, not successful video playback. The final availability
benchmark exited 2 after 9.73 s with `bot_check_denied=true` and no first frame.
Further live playback retries were stopped.

### Existing SmartTube/yt-dlp integration audit

The follow-up uses the existing implementation and credentials only. NewTube
does execute bundled yt-dlp/EJS-derived JavaScript through its Kotlin/J2V8
provider; it does **not** invoke the host yt-dlp downloader or inherit its
session implementation. Player requests and account authorization remain in
NewTube's own services. The installed host CLI reports 2026.08.19; its YouTube
extractor files match the later local clone revision inspected in this audit.
Neither the CLI's presence nor shared processing code establishes another
currently working playback session.

The preserved final-availability capture contains nine eligible attempts:
two account-bearing responses report `srvAuth=y` but `UNPLAYABLE`, and seven
anonymous responses report explicit `LOGIN_REQUIRED` bot checks. The earlier
successful route was attempted too. All parsed format counts are zero and
DASH/HLS/SABR URLs are absent. The final trip reports `ringExhausted=y`.
This is not the historical early-abort defect or just a cached denial.

`player-result` is logged immediately after response parsing, before format
fixups or media-URL processing. `VideoInfoServiceBase.transformFormats()` skips
unplayable results. Thus the captured failure precedes processing of this
video's media URLs by the yt-dlp-derived solver; there is no demonstrated
usable result that switching to that solver would recover. This does not
establish the upstream reason for the refusal.

Account restoration checks the selected account's credential ownership and
lifetime, and the captured fresh requests were authenticated server-side.
A separate source-level account-switch format-cache ownership gap was found,
but cannot explain these fresh-process responses and was not changed as part
of the denial investigation. No credentials were read, exported, cleared or
replaced during this audit. The Pixel APK hash was reverified against the
installed build before further changes.

One existing behavior contradicted the owner's requirement to keep current
sessions: `noteAnonymousChallenge()` called `rotateWebVisitor()` after repeated
denials. That call is now removed. The existing threshold, cooldown and route
ordering are unchanged; the diagnostic now says `sessionPreserved=y`. Normal
session expiry/account lifecycle behavior is not modified. This correction is
not a claim that preserving the visitor resolves the upstream refusal.

Validation of this follow-up: five offline tests exercise the real denial
accounting while spying on token/visitor mutations; seven more use the actual
JSON-path converter with synthetic playable and denied responses. All twelve
pass, as do the thirteen benchmark-tool tests. Debug and release builds pass.
These parser fixtures validate
known response shapes, not the unseen raw body of every live response.

The rebuilt debug APK was installed without clearing data and its SHA-256
verified on the Pixel: `3f5f81c95b7d82a1eee99276879124a182ef19bda0d79b9e1aa8ab88ae1bd9fb`.
A single normal-settings playback check again received all nine denial
responses and logged `sessionPreserved=y`. The benchmark exited 2 after 9.51 s
with no first frame. The screen still displayed the explicit bot-check reason;
no pinned task was present. No subsequent playback requests were initiated by
the test. Real-video end-to-end acceptance remains blocked, not passed.

Follow-up artifacts: `/tmp/newtube-existing-session-20260907-WKwsic`, containing
the build/test log, sanitized availability capture/results and denial screenshot.
The narrow runtime patch and new tests are uncommitted in `MediaServiceCore`;
no submodule branch was changed and no commits or pushes were made.

## TTFF-priority round validation and artifacts (before the follow-up above)

**110 focused Android unit/Robolectric tests + 13 offline harness tests pass.**
The Android count is 93 app tests plus 17 common-controller tests, including
actual Media3 load-control decisions, instant still removal, deferred rendering,
one-shot infrastructure warming, and real denial/Play/Pause callbacks.
The four physical-Pixel ABBA runs above passed the instrumentation test. Review
then strengthened its source-switch assertion to require continued video-frame
production as well as position advancement; a fifth run at the production
500 ms default passed that version too.

Debug and release APK builds passed, including release lint checks. The release
APK contains no `ttff-fixture` asset. The updated debug APK remains installed;
the test-only companion package was removed after verification. All test
properties were restored to inactive values, and the phone was left on its
launcher with no pinned task. Account data was preserved.

```sh
./gradlew :smarttubetv:assembleStmobileDebug \
  :smarttubetv:assembleStmobileDebugAndroidTest \
  :smarttubetv:testStmobileDebugUnitTest \
  --tests 'com.newtube.mobile.player.*' \
  --tests 'com.newtube.mobile.ui.playback.*' \
  --tests 'com.newtube.mobile.SessionWarmupGateTest'
./gradlew :common:testDebugUnitTest \
  --tests '*PreMediaRetryGateTest' --tests '*VideoLoaderControllerRetryTest' \
  --tests '*VideoStateControllerPositionTest'
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools \
  -p 'test_playback_benchmark.py' -v
adb -s <PIXEL_SERIAL> shell am instrument -w -r \
  -e class com.newtube.mobile.player.TtffFixturePlaybackTest \
  io.github.aleixrodriala.arc.test/androidx.test.runner.AndroidJUnitRunner
```

The instrumentation test also checks continued frame production after switching,
not only advancing audio position. The fixture's reproduction command is in
`smarttubetv/src/debug/assets/README.md`; debug assets/activity are not release
features. Do not benchmark other connected devices implicitly.

Private evidence: `/tmp/newtube-ttff-priority-20260907-hsSv9w`: `fixture-*.txt`,
`availability`, `final-availability`, `explicit-retry*.log`,
`infrastructure-observed.log`, local-fixture screenshot, and build/test logs.
No account/cache contents were cleared or exported.

Historical TTFF-priority debug APK SHA-256 (the follow-up above supersedes it):
`db9b6f2a5daa22df3a28512518fb9c46470467a82d46a6d62389f14bb4c3718a`.
Root changes remain uncommitted on `main`. Submodules were unchanged during
this earlier round; the existing-session follow-up above now also changes
`MediaServiceCore`.
