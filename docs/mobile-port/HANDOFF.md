# NewTube — Deep handoff (engine, network, verification)

Written 2026-07-12 after the live-DVR round + a 4-iteration improvement loop.
Audience: a future working session (any device) picking this codebase up cold.
Companion: `STATUS.md` (current state + backlog), root `CLAUDE.md` (hard rules).

## 1. Repo topology

- `origin` = github.com/aleixrodriala/newtube (`main`).
- Submodules `MediaServiceCore`, `SharedModules` = our forks; remote name
  `fork`, branch `master`. Commit inside the submodule → push `fork master` →
  commit the pointer bump in main. Upstream (yuliskov) fixes for YouTube
  breakage are merged into these forks; the main repo never merges upstream.
- `tv-legacy` tag + `upstream/master` remote = pre-port history: the vendored
  `exoplayer-amzn-2.10.6` fork, the TV UI, and the ORIGINAL
  `LiveDashManifestParser` (`git show tv-legacy:common/src/main/java/com/liskovsoft/smartyoutubetv2/common/exoplayer/LiveDashManifestParser.java`).
- `minifyEnabled` is off. Reflection over media3 internals (live parser, via
  `Helpers.setField/getField`) and pref plumbing depend on that.

## 2. Mobile player stack (smarttubetv/src/stmobile/.../player/)

| File | Role / non-obvious behavior |
|---|---|
| `Media3PlayerController` | Mirrors legacy ExoPlayerController's surface so activity delegation is unchanged. DASH source builds run OFF-MAIN on `SOURCE_BUILD_EXECUTOR` guarded by `mOpenGeneration` (stale builds dropped). Holds the one-slot pre-built-next-source stash: `prebuildNextSource`/`takeStashedSource` (consume-once); `resetPlayerState()` drops only MISMATCHED stash entries — an unconditional clear makes the feature self-evicting because `loadVideo` resets before the open the stash was built for. Persists the bandwidth estimate on `release()`. |
| `Media3PlayerInitializer` | LoadControl presets driven by Settings→Player→Video buffer: LOW 30s/20s/min(48MB,cap/4), MEDIUM 50s/50s/min(96MB,cap/2), HIGH 75s/50s/min(192MB,cap)=pre-knob values, HIGHEST 120s/50s/min(288MB,RAM/12); cap=RAM/18. Read fresh in `createPlayer()` (engine restart applies it). One-shot pref alignment MEDIUM→HIGH (`newtube_player`/`buffer_default_aligned`): the persisted parse default was MEDIUM while the engine always ran baked HIGH — wiring without alignment silently downgrades every existing install. `setPrioritizeTimeOverSizeThresholds(true)`. |
| `Media3SourceFactory` | Custom process-wide bandwidth meter seeded from prefs (`newtube_network`/`bw_estimate_bps`, clamped 100k–50M) — kills the post-restart ABR ladder walk. `FailFastLoadErrorPolicy`: 403/416 fail after 2 tries (walks the cause chain), backoff capped 1 s, 6 retries otherwise. `GOOGLEVIDEO_RANGE_QUERY=false` — parked experiment, see §5. Live URL manifests get `.setManifestParser(new LiveDashManifestParser())` — NOT static, per-source state. Generated-MPD path: static-forcing keyed on `formatInfo.isLive()` (past live streams mark type=dynamic per-format but must be normalized); genuinely-live last resort rides a `data:` URI (media3 `checkArgument(!manifest.dynamic)` forbids side-loading dynamic manifests). |
| `LiveDashManifestParser` | THE reason live DVR works (media3 port of the legacy app-level parser; the vendored ExoPlayer fork's DASH code was stock — this parser was always the fix). First parse: zero-base (`Period.startMs=0`, SegmentList `presentationTimeOffset=0`, `startNumber=0` via reflection). Refreshes: append only new tail segments to the RETAINED manifest (window grows monotonically) and copy the fresh `publishTimeMs` forward — media3 1.10.1 has a publishTime staleness check (2.10.6 didn't) that otherwise loops `DashManifestStaleException`. Why needed: YouTube live manifests carry `presentationTimeOffset` ≈ stream age (e.g. 3.2e9 timescale units) + absolute `startNumber` (sq); stock media3 window math reads that as duration = −(stream age) → timebar duration 0 → DefaultTimeBar rejects all touches, LIVE chip inert. Zero-basing without accumulation breaks refresh continuity — both halves are required. |
| `Media3PlayerCache` | 512 MB SimpleCache, sq-aware cache keys (`id+itag+lmt+xtags`) so live/OTF segments can't collide. |
| `Media3TrackAdapter`, `Media3SubtitleManager`, `Media3DebugInfoManager` | Presets→constraints/native ABR; explicit picks→TrackSelectionOverride. |

Common-layer seams: `VideoLoaderController` dispatch (live → dashManifestUrl,
then HLS, then generated-MPD last resort; `wouldOpenPlainDash()` gates the
prebuild), `preloadNextVideoIfNeeded` on tick (80 s window),
`ErrorFixerController` auto-reload cap = 3 consecutive fixes (uncapped looping
reloaded every ~2 s and provoked server-side "video unavailable" anti-abuse via
BotGuard/PO-token regeneration), `PlayerEngine.prebuildNextSource` default
no-op seam, `NetPath` (common/.../misc) logging.

## 3. Network stack facts

- **/player client ring** (MediaServiceCore `VideoInfoService`): 13 clients
  upstream; phone skips TV_LEGACY/TV_DOWNGRADED/TV_EMBED/TV_SIMPLY via the
  `setSkipTvFallbackClients(true)` static gate set in `MobileMainApplication`
  (NEVER edit `VIDEO_INFO_TYPE_LIST` — upstream churns it). Happy path = 1
  call (winner remembered); failover = single pass (old code walked twice).
- **Caches**: single-slot positive format-info cache (validity = cipher+poToken
  fresh + `containsMedia()`ed) + SEPARATE single-slot negative cache (30 s TTL,
  unplayable verdicts; keyed on the REQUESTED videoId because unplayable
  responses may lack videoDetails). The negative slot exists because the
  positive slot gets overwritten by the auto-skip target ~5 s after every
  gated failure.
- The second /player on playable videos is the deferred WEB
  subtitle-enrichment call (auto-translate language list), post-playback,
  at most once per process once the language cache warms. NOT redundant.
- **Timeouts**: shared OkHttp clients 20 s; /youtubei/v1/player + /next bounded
  to 8 s connect/read via an interceptor in `RetrofitOkHttpHelper` (one client
  serves all youtubeapi Retrofit instances; auth is per-request headers).
- **Cronet**: media path uses the embedded Cronet engine (H2/QUIC); QUIC server
  configs persist across restarts (`setStoragePath` + 1 MB DISK_NO_HTTP cache →
  0-RTT). Debug builds log one `NetPath: cronet <proto> <status> ttfb= total=
  rx= reused= <url64>` line per request. Measured healthy: ~99% h3 with
  ~99% connection reuse on googlevideo.
- **Log hygiene**: OkHttp debug logging is BASIC (BODY dumped full JSON bodies
  and poisoned URL forensics with phantom request templates); the OkHttp
  profiler is OFF by default — flipping it required BOTH
  `OkHttpCommons.enableProfiler` AND the no-arg `OkHttpManager.instance()`
  overload that overwrote it.
- Signed-out `get_add_to_playlist` is gated off in `YouTubeMediaItemService`
  (was 16×401 per watch page).

## 4. NetPath log guide (tag `NetPath`)

Always-on milestones (per videoId, +ms since tap):
`tap <id>` → `open <id> +N` → `info <id> +N dash= hls= sabr= live=` →
`warm <host> +N` (preconnect) → `prepare <id> +N type=` → `first-frame <id> +N`
(once per open) → `error ...`.
`prepare type=` values: `dash-mpd` (generated, VOD), `dash-mpd-stash`
(pre-built next source adopted), `dash-url` (live manifest URL), `hls`,
`dash-mpd-live` (data-URI last resort, expected unreachable), `url-list`.
Debug-only extras: `load[S|C|X|E]` per media chunk (stock media3 EventLogger
emits NO loadStarted/Completed — we register our own listener in
`MobilePlaybackActivity` under `BuildConfig.DEBUG`), `cronet ...` per request,
`player-ring <CLIENT> attempt=N playable=` (failover walks only),
`prepare-stash hit/miss <id>`, `buffer=<TYPE> max= min= bytes=` (player
creation), `dash-url-full <url>` (full live manifest URL for curl'ing).

Reference numbers from the emulator (debug build, 2 GB AVD): cold open
first-frame ~1.8–2.6 s; replay from cache ~1.5 s; autoplay advance with stash
~350 ms; far-seek resume ≤2 s at unchanged quality (seeded meter); age-gated
verdict ~1.1 s (8-client walk).

## 5. Parked experiments — read before re-attempting

- **`GOOGLEVIDEO_RANGE_QUERY` (leaf-wrapper range rewrite)**: mirroring
  `range=`/`rn=` into the query WHILE KEEPING the Range header broke all
  playback: googlevideo prioritizes the query and answers 416 or 200 with an
  offset body; media3's CronetDataSource treats 200 as "range ignored" and
  re-skips position bytes → extractor garbage (Matroska varint crash,
  negative-skip AIOOBE) AND TeeDataSource poisons the SimpleCache. A correct
  implementation must be a NewPipe-style leaf DataSource wrapper: open at
  position=0, DROP the Range header, put `range=`/`rn=` in the URL —
  `ResolvingDataSource` CANNOT suppress the header. Needs a real device to
  evaluate any benefit. Flag stays false.
- **QUIC A/B (disable QUIC)**: dropped — transport measured healthy (§3); the
  itag-248 SocketTimeout stalls are emulator-NAT artifacts that recover.
- **Post-seek quality floor / ABR tuning**: dropped — the seeded bandwidth
  meter already eliminated the ladder walk.
- **SABR (updated 2026-09-08)**: optional native Media3 VOD source is now implemented,
  default off, used only when accepted TV metadata actually provides it. The
  current TV 5.x route remained DASH-only in Wi-Fi/LTE trials; real SABR delivery
  and speed gains remain unvalidated. No metadata-client switch is part of this
  option. The TV 7.x diagnostic initially skipped normal URL preparation; that
  test defect is now fixed. Standalone real audio POSTs using the complete
  metadata pipeline still return 403 with empty bodies on Wi-Fi (47 ms) and
  cellular (218 ms), with fresh unexpired URLs and stable validated networks.
  Media delivery remains unresolved; defer player changes until it works.
  Controlled fixtures do not replace that missing real-stream A/B.
  See [implementation and comparisons](SABR-MEDIA3-2026-09-08.md).
- **media3 DefaultPreloadManager**: structural, deferred; the one-slot stash
  captures most of the win for autoplay advance.

## 6. Verification methodology (how every round above was verified)

Pattern: orchestrator session + disposable agents. Implementer agents own the
tree (no git writes — orchestrator commits after verification); emulator verify
agents are screenshot-driven and decide KEEP/REVERT against explicit metrics
measured from logcat (A/B on the same boot when attribution matters: baseline
numbers first, install candidate build, re-measure the SAME videos/actions).

Emulator ground rules (this dev machine = WSL2 + Windows-host emulator;
adapt paths per device, keep the rules):
- ALWAYS pin `-s <serial>`; screenshots via `screencap` → pull → view.
- `logcat -G 16M` + `logcat -c` per measurement phase; `grep -a` always.
- Seeks: center-tap to reveal controls, slow 800–1200 ms drags only.
- `INSTALL_FAILED_INSUFFICIENT_STORAGE` appears when free space is near
  Android's ~500 MB low-storage threshold: `pm trim-caches` barely helps —
  full uninstall+reinstall of the app is the reliable fix (app data loss OK on
  test emulators). `pm uninstall-system-updates com.google.android.youtube`
  frees ~200 MB.
- `adb emu network speed X` STALLS connections (zero bytes) rather than
  shaping bandwidth — it starves the ABR estimator and even InnerTube calls.
  Do NOT use it to "verify" ABR or rebuffer behavior; that work needs a real
  device.
- Age-gated test video used across rounds: `qkO6iBwcoe4` (search "rammstein
  pussy official", the [FIXED AUDIO] re-upload). Reliable 24/7 live stream:
  Lofi Girl `X4VbdwhkE10` ("lofi hip hop radio - beats to relax/study to").
- API-36 AVD flake: PiP + activity-OPEN transitions can wedge (activity
  created, never rendered) on all code paths — verify PiP flows on a real
  device; use `dumpsys` task structure as truth.

## 7. Recent history (all pushed)

Main repo `main`:
`1aaac64` opaque system bars (edge-to-edge opt-out) · `fbb6006` v1.2.1
seek/network hardening + NetPath · `bbfe649` v1.2.2 live playback + DVR +
open-latency/network batch · `bc46d87` iter1 /player ring trim ·
`34245d9` iter2 bumps (unplayable reuse, 8 s bound, profiler off) ·
`00eb4f7` iter3 pre-built next MediaSource + negative-slot bump ·
`f27cdbc` iter4 Video-buffer knob + OOM recovery · `37ef7d0` v1.3.0 bump.
MediaServiceCore `master`: `1c2d87b7` playlist-gate + JsonPath flood ·
`83056437` ring trim · `d889d93c` unplayable reuse + timeout bound ·
`504db87d` negative-cache own slot.
SharedModules `master`: `81b1027` cronet observability + QUIC persistence +
BODY→BASIC · `ff7c620` profiler off.

Loop verdicts and the measured evidence behind each claim in STATUS.md live in
the per-iteration commit messages above — each one carries its A/B numbers.

## 8. PO-token enforcement (first real-device round, 2026-07-12)

Pixel 9 (serial 4A120DLAQ0049N) on Telefónica LTE/5G. Carrier CGNAT IPs make
googlevideo enforce PO-token integrity; the emulator's residential network
never did, so none of this was visible before.

**The rules, as measured on-device:**
- VOD streams whose /player flow minted no pot (ANDROID_VR, TV, IOS — every
  "fast" client) serve EXACTLY ~60s of media per stream, then 403 every chunk.
  User-visible: play 60s → freeze → Source error → auto-reload (ring walk) →
  repeat at 120s, 180s… until WEB_EMBED wins. Exactly consumed the 3-reload
  ErrorFixer cap.
- Client-side pot attachment CANNOT rescue non-web clients. Tried and 403'd
  with `pot=y` on the wire: web-visitor streaming pot, app-visitor-bound
  streaming pot (`getAppClientStreamingPot`, minted against the same
  visitorData the /player call used). What matters is the MINTING FLOW: only
  URLs from an attested (serviceIntegrityDimensions) /player request survive;
  Android-family clients would need DroidGuard, unavailable to us.
- Live has NO grace window (segments 403 instantly) and WEB_EMBED live
  responses are HLS-only (no dashManifestUrl → no DVR parser). Pot on the HLS
  manifest URL (path form `/pot/<gvs-pot>`) propagates into playlist+segment
  URLs but they still 403. The working recipe: walk on to a dash-manifest
  client (`setPreferDashManifestForLive`) — ANDROID_VR's DASH live manifest +
  `/pot/` yields all-200 segments and the LiveDashManifestParser DVR window.
  (Unknown whether the /pot/ is strictly needed there; it's idempotent — keep.)
- The fix stack (MediaServiceCore `2d80b1d7`, main-repo Application flip):
  BotGuard warmup at app start (~1.1s, off open path) → WEB_EMBED-first for
  VOD (`setPreferNoPotClient(false)`; warm content-pot mints ~10ms per video,
  killing the ~2.7s penalty that motivated ANDROID_VR-first) → live walks to a
  dash client. Non-embeddable videos still fall through to fast clients and
  will cascade on enforcing networks — known gap, candidates: jump straight to
  WEB family, or TV+serviceIntegrityDimensions.
- WEB_EMBED /player RTT on LTE varied 0.3–2.1s across opens (vs ~0.4s
  ANDROID_VR). Cold TTFF ~3.8s worst case. Optimization candidate, not a
  regression that matters (the alternative dies at 60s).

**Debugging pitfalls that cost real time tonight (all reusable):**
- `adb install -r` can print "Success" while installing a DIFFERENT package —
  the appId was renamed mid-session (`301a936`, com.newtube.app →
  io.github.aleixrodriala.arc) and every install/launch/dumpsys kept targeting
  the old id, silently testing a stale build twice. ALWAYS verify
  `dumpsys package <id> | grep lastUpdateTime` (and md5 of `pm path` base.apk
  vs the local file) after installing; `monkey -p <old-id>` happily launches
  the stale app.
- Android 17 sideloads run through `com.google.android.verifier`
  (VerificationCheck logcat lines) — adds seconds and log noise; verdicts
  matter (`Result: Pass`).
- The long-running `adb logcat > file` stream can silently stall (file stops
  growing, process alive). Check the tail timestamp before trusting "no new
  errors"; restart the capture.
- Before ANY `input tap/swipe`, check `dumpsys window | grep mCurrentFocus` —
  the user may have taken the phone (a swipe meant for the seekbar landed in
  WhatsApp tonight).
- Data survives package renames/signature changes via `run-as` (debug builds):
  tar app data out, uninstall, reinstall, tar back in — but RENAME
  `shared_prefs/<old-appId>_preferences.xml` to the new appId or the default
  prefs silently reset. OAuth lives in `files/global_prefs/
  media_service_account_data` (survived 3 reinstalls tonight).
- Per-chunk NetPath lines are `load[S|C|X|E]` with literal brackets — grep
  needs `load\[[SCXE]\]`, not `load[SCXE]` (a character class that matches
  nothing; cost an hour of "the listener is broken" tonight).

## 9. Second Pixel-9 round (2026-07-12 evening) — DVR/FGS/PiP verified, network-wedge post-mortem

Same build as §8 (1.3.0 + pot fixes, installed 21:33). Serial `4A120DLAQ0049N`,
package `io.github.aleixrodriala.arc`.

**Live DVR (X4VbdwhkE10, ~2h16m window, real 5G): PASS.** 18+ min continuous
soak — DASH manifest refresh every ~2 s, all HTTP 200 with `/pot/`, zero
`load[E]`. Slow ~1 s seekbar drag 67 min back: seek lands exactly (state
BUFFERING mediaPos=4240.8 → READY +3.3 s), segment fetches at the target all
200. LIVE chip: position 4309 s → 8307 s (edge), READY +2.3 s. The emulator
seek rules hold on device: reveal controls first; only slow drags register.

**Background audio-only ("Solo audio (pulsando HOME)"): PASS.**
`MobilePlaybackService` isForeground=true type=MEDIA_PLAYBACK, transport
notification on `newtube_playback_channel` with MediaSession token, ZERO
ForegroundServiceStartNotAllowedException across the whole session, audio
position advances for minutes in background. Note: no engine re-init happens
at HOME — the FGS is already up during foreground playback, so the
Android 12+ background-FGS-start restriction never triggers.
BUG (worse than the old carry-over): the VIDEO stream (itag 303, 1080p60
VP9) keeps downloading in audio-only mode, ~5 min buffered ahead on
cellular. Fix direction: deselect the video track/renderer in audio mode,
not just detach the surface.

**PiP → search routing: PASS.** Launcher relaunch while backgrounded put the
player into a pinned task over the browse task (two tasks). Search opened
in-task; opening a result collapsed the pinned task — the SAME
ActivityRecord moved back into the main task (Browse→Search→Playback), no
duplicate player, no double audio. Search-result tap→first-frame 1.9 s.

**Network-wedge post-mortem (READ BEFORE TOUCHING THE RADIO):** attempting
the ABR test via `cmd phone set-allowed-network-types-for-users -s 0
1000000000000011` (GSM-only) for ~35 s wedged cellular data for ~12 min:
the radio stayed attached (dumpsys showed LTE/NR_NSA throughout) but every
NEW DNS lookup failed (UnknownHostException); established flows kept
working. Android's data-stall detection eventually tore down and rebuilt
the PDN (new validated network at +12 min). The dev Mac tethers THROUGH the
phone (USB ncm0 local net, NAT to rmnet1), so the wedge also hit the Mac.
Rules: (1) never flip allowed-network-types while the phone is the Mac's
uplink; (2) restore mask is `11001111101111111111`; (3) if wedged, wait for
data-stall recovery or toggle airplane mode.
App-side findings from the outage (both on the UX backlog in STATUS):
ErrorFixer's reload cap (3) worked as designed and stopped cleanly; but
after connectivity returned the app never retried, the raw
UnknownHostException string sat in the player title, and play was a no-op —
the video had to be re-opened manually.

**Misc:** WEB_EMBED DASH picked `pt-br (dubbed-auto)` audio as [main] on a
Spanish VOD — track-selection original-language check needed. One
search-result thumbnail rendered blank gray. The in-player "Play in
background" dialog was restored to Desactivado (device owner's original)
after the tests.

## 10. Background-audio + offline-recovery fixes (2026-07-12 late night)

**Audio-only background no longer downloads/decodes video.** True background
audio (activity `onStop` with `!mIsInPip && !mSuppressAutoPip &&
!isFinishing()` — home without PiP, screen off, another screen on top) now
disables the whole VIDEO track type via
`Media3TrackAdapter.setVideoTrackDisabled(true)`
(`setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, …)` composed onto CURRENT selector
parameters — never a snapshot, so quality/audio/subtitle writes and the flag
can't clobber each other). Re-enabled at the top of `onResume` before the
texture reattach; `createPlayerObjects` re-applies the flag after an engine
restart (fresh selector) while backgrounded. PiP and the Browse mini-player
keep video (both render live frames). Pixel-9 verified: screen off →
`videoDisabled` in 3 s, then pure itag-251 audio chunks; a lift-to-wake blip
(activity resumed behind keyguard for 2 s) correctly re-enabled/re-disabled —
that's designed lifecycle behavior, costs ~1–2 MB per genuine wake event.
KNOWN GAPS (minor, by design): (1) minimize into the Browse mini-player and
THEN home out — the player activity is already stopped, so video stays
enabled (needs a Browse-host hook); (2) screen-on-at-keyguard streams video
while the lockscreen shows (activity is resumed behind keyguard).

**Offline recovery is no longer a dead end.** ErrorFixerController changes:
- Connectivity-class errors (cause-chain walk: UnknownHost/SocketTimeout/
  Connect/SocketException + "Unable to connect to"/ERR_INTERNET_DISCONNECTED/
  ERR_NAME_NOT_RESOLVED/ERR_CONNECTION_*/ERR_TIMED_OUT/"Exception in
  CronetUrlRequest" etc.) now put a friendly title in the player
  (`msg_player_no_connection_retry`) instead of the raw exception — both on
  the pre-cap path and the capped dead state. Non-connectivity errors keep
  the raw string (honest + diagnosable). Note: a swallowed connectivity
  failure that surfaces as "fromNullable result is null" (seen behind a dead
  proxy) stays raw — the service layer lost the cause, and mislabeling a
  server break as "no connection" would be worse.
- When the cap trips on a connectivity error, a `registerDefaultNetworkCallback`
  (APPLICATION context) arms ONE automatic reload for when the network
  validates. STRICTLY EDGE-TRIGGERED — do not "simplify" this: the callback
  replays the current network state at registration, so a level-triggered
  version insta-fires when the cap trips on a slow-but-alive link and loops
  cap→arm→fire→cap forever against googlevideo (the exact anti-abuse loop the
  cap exists to stop). The detector seeds `seenDisconnected` from the active
  network's VALIDATED bit at arm time and only fires on a
  disconnected→validated transition. Disarm on: fire, user open, engine
  release, finish.
- Play tap in the dead state = manual retry (resets the cap window, reloads).
  Both `onPlayClicked` and `onPauseClicked` are handled — the retained
  `playWhenReady` can make the first tap dispatch pause.
Emulator-verified (pixel9_audit): mid-playback outage → cap → friendly title;
play-tap while offline reloads and re-caps (bounded); network restore → exactly
ONE auto-reload, first frame +0.4–1.2 s, real title restored (both the engine
error path and the offline-open/format-fetch path); cap-trip with the network
UP (dead-proxy trick) → zero auto-reloads over 50 s (no hot loop). Established
Cronet connections survive a global-proxy change — to force failures on a
validated network you must open a NEW video.

## 11. Tunnel-shaped outage recovery (2026-08-01, Pixel 9)

The §10 recovery only fires on a PROVEN `disconnected -> validated` edge. Real
mobile outages usually produce no such edge: in a tunnel/lift/metro, or across
a Wi-Fi->cellular handover, the link stops delivering while Android still
reports the default network connected and `NET_CAPABILITY_VALIDATED` (§9
measured ~12 min for data-stall detection to invalidate a wedged LTE network).
The player, by contrast, gives up in **seconds** — `isFatalTransportError`
(Media3SourceFactory) does not retry `UnknownHost`/`ERR_INTERNET_DISCONNECTED`
at all, so the buffer drains, 4 error cycles burn, and the cap trips long
before Android notices. `armConnectivityRetry` then seeds `seenDisconnected=n`
and sits inert forever. User-visible: video stops in the tunnel and never
resumes; only reopening it works.

**What changed (ErrorFixerController):**
- A **timer** now retries alongside the edge listener, on an escalating budget
  `AUTO_RETRY_BACKOFF_MS = {5s, 15s, 45s, 120s, 300s}` (~8 min of outage), then
  stops until a user action, a proven connectivity edge, or recovered playback.
  The budget — not the edge — is now the anti-hammer guard, so the edge path
  keeps its original strictly-edge-triggered seeding (a slow-but-alive link
  must not hot-loop; see §10).
- A connectivity EDGE is strong evidence, so it retries immediately and REFILLS
  the budget (reviving an exhausted one). The timer is weak evidence (our own
  failures) and only spends it.
- `onTickle` declares genuine recovery — playing past `mLastErrorPositionMs +
  SAME_POSITION_WINDOW_MS` — and refills the budget. `onPlay` cannot: after a
  reload most of the replayed span comes from the disk cache, so READY+playing
  proves nothing about the chunk that died (same reason `mSamePositionErrorCount`
  exists).
- **Trap, cost an iteration:** `retryNow` must tag its reload as ours
  (`mAutoReloadPending` + `mAutoFixVideoId`) or `onNewVideo` reads it as a fresh
  user-initiated open and calls `clearErrorCapped()`, resetting the budget to 0.
  Measured before the fix: every retry logged `attempt=0` and the app re-attempted
  forever on a fixed ~16 s period — worse than the bug being fixed.
- **Transport controls reach the retry** (MobilePlaybackService): the media
  session `onPlay/onPause` and the notification's `QueueForwardingPlayer`
  `play()/pause()/prepare()` now call `PlaybackPresenter.onPlayClicked()`.
  Before, they only touched an IDLE player (`setPlayWhenReady` = no-op), so the
  notification button was DEAD in the error state — the phone-in-pocket case had
  no recovery at all. `prepare()` on IDLE is routed to the app reload instead of
  media3's `handlePlayButtonAction` re-preparing the dead source. Idempotent:
  `retryNow` clears `mErrorCapped` first, so double dispatch is a no-op.
- **Raw error toasts removed** (3 call sites): `MessageHelpers.showLongMessage`
  threw `Response code: 403` dumps and whole stack traces over the video for
  failures the next line was already fixing. The error surface is the player
  (title + overlay); capped titles are now localized (`getErrorTitle` /
  `unknown_source_error`) instead of raw exception text.
- **Persistent offline notice** (`PlaybackView.showPlaybackNotice`, default no-op;
  `mobile_player_notice` in the mobile layout). `setTitle` cannot carry this: the
  metadata bind of every recovery reload overwrites it, so with the toast gone an
  outage showed the real title and no explanation at all. The notice survives the
  retries and is cleared ONLY by real playback (`onPlay`), a user-initiated open,
  or `onFinish` — an outage then reads as one continuous state instead of a
  message blinking once per attempt. The text tracks what is actually happening:
  `msg_player_no_connection_short` ("retrying…") while the budget holds,
  `msg_player_no_connection_tap` once it is spent. Placement matters: anchored
  `bottom` in the video box, between the transport controls and the seek block —
  the first attempt put it in the centre loading stack and it printed straight
  through the play/prev/next icons. Keep it to ONE short line; the portrait video
  box is only ~190dp tall.

**Repro recipe (better than §10's dead proxy).** Cronet ignores the system HTTP
proxy on this device and reuses established connections, so the proxy trick no
longer breaks playback. Use strict private DNS instead — it kills the app's
name resolution while leaving adb-over-Wi-Fi untouched:
`settings put global private_dns_specifier blackhole.invalid` +
`private_dns_mode hostname`; restore with `private_dns_mode off` (and put the
original specifier back — it was `f2d9fc.dns.nextdns.io`). Note two things:
(1) already-established googlevideo connections keep serving, and a video whose
segments are in the 512 MB SimpleCache plays right through the outage — force a
failure by opening an UNCACHED video; (2) strict private DNS also fails Android's
own validation within ~seconds, so this repro produces `seedDisconnected=y` and
exercises BOTH triggers, not the pure no-edge case.

**Measured (Pixel 9, `4A120DLAQ0049N`, debug 1.6.1):** cap -> `in=5000
attempt=0` -> timer fires at +5.0 s -> `in=15000 attempt=1` -> +15.0 s ->
`in=45000 attempt=2` -> `in=120000 attempt=3`; DNS restored mid-outage ->
automatic resume with `first-frame +4047` at the exact position it died
(`pos=76948`), no user action; notification play button in the dead state ->
`recovery-retry-now user=y`. Log lines to grep: `recovery-auto-retry-scheduled`,
`recovery-auto-retry trigger=timer|network`, `recovery-auto-retry-exhausted`,
`recovery-recovered`, `recovery-retry-now user=`.

## 12. Bad-network round (2026-08-06, emulator `NewTube_Network_Test`)

Field report: on LTE with poor signal the app is slow and fragile. Five parallel
read-only audits were run (InnerTube/OkHttp path, media3 byte path, recovery
under flaky links, bandwidth contention, cold-start critical path). What got
built and what was measured is in STATUS "Works (added 2026-08-06)". This
section is the part a future session needs: the METHOD, and what is left.

**Rig, and its limits.** The emulator's own shaper is worse than documented:
`adb emu network speed` produced ZERO `api-http[C]` completions in 30s at 4000,
2000 AND 1000 kbps - it stalls connections, it does not shape them, at any rate.
`adb emu network delay` works but only on NEW connections: with `delay 500` the
first request (fresh connection) took +525ms while the next eight, multiplexed
over the same H2 connection, were unchanged. So it models handshake cost only.
That leaves the in-app `DebugMediaShaper` (`debug.arc.throttle_kbps`), which
shapes the MEDIA LEAF ONLY - so every bandwidth experiment this project has ever
run left the API and image traffic at full speed, and the cross-stack contention
that motivated this round has never been reproducible on the bench. **The single
highest-leverage next investment is a whole-app shaper** (a shared token bucket
across the Cronet leaf, OkHttp and Glide - one candidate is a debug-only default
`SSLSocketFactory`, which covers OkHttp and Glide's `HttpURLConnection` in one
place, with the existing media shaper for Cronet).

Other rig facts worth keeping:
- Per-app bytes: `dumpsys netstats detail --uid`, sum `rb=` for the app's uid
  (`pm list packages -U`). Buckets refresh lazily, so a mid-run sample reads 0 -
  compare whole runs, not phases.
- `am start -a android.intent.action.VIEW -d <watch url>` opens the REAL YouTube
  app on a Play-services AVD. Always pass `-p io.github.aleixrodriala.arc`.
- `pgrep -f <script>` inside a wrapper whose own command line contains that
  string matches ITSELF - a wait loop built on it never terminates. Cost ~15 min.
- Reproducing the long-buffering rescue: open throttled (`throttle_kbps 250`),
  let ABR settle, then drop to 60. Starting UNTHROTTLED does not work - the
  75s HIGH buffer absorbs the starvation and `BufferingDetector` never sees its
  20s of stalls.

**Still open from the audits (verified findings, not built).** Roughly by value:
- **Chunk cancellation is off.** media3 1.10.1's `AdaptiveTrackSelection` does
  not override `shouldCancelChunkLoad` (bytecode-checked: the base returns
  `false`), and `DefaultBandwidthMeter` only folds a sample in at
  `onTransferEnd`. So a segment requested at a stale-high bitrate runs to
  completion no matter how far the estimate collapses under it: a 5s 1080p
  segment (~1.7 MB) at 300 kbps commits the loader for ~45s. Biggest remaining
  lever on start time; needs a `shouldCancelChunkLoad` subclass plus a
  thrash guard.
- **The ABR seed is per-network-TYPE, not per-conditions.** A 20 Mbps estimate
  learned on good 4G is re-applied verbatim on a train, and
  `setResetOnNetworkTypeChange(true)` restores it on a Wi-Fi->cell handover,
  discarding the freshly-learned low value. Candidates: clamp the applied seed on
  cellular, persist a conservative statistic rather than the last value, persist
  periodically instead of only in `release()`.
- **Nothing in the app is metered-aware.** `isActiveNetworkMetered` /
  `getRestrictBackgroundStatus` appear nowhere; `NET_CAPABILITY_NOT_METERED` only
  in a log string. 75s of buffer-ahead (~25 MB of 1080p) is downloaded for a
  video abandoned after 10s, on cellular, with Data Saver on. Wants one
  `NetworkPolicy` helper the discretionary consumers route through.
- **Channel and playlist screens have NO error surface** - a failed load leaves a
  blank grid with no message, no retry, no pull-to-refresh (their presenters only
  `Log.e` + `showProgressBar(false)`; the mobile views implement only
  `showProgressBar`). This is the most likely source of a literal "nothing loads"
  report that the feed's FeedCache + 30s re-poll does NOT cover.
- **Feed error re-poll is a fixed 30s, forever, and runs while backgrounded**
  (`BrowsePresenter:1307`). Same shape at `VideoLoaderController:416` (upcoming
  live) and in `StreamReminderService` (one /player per reminder per minute).
  Reuse `ErrorFixerController`'s escalating budget; pause in `onViewPaused` -
  but add a resume/connectivity-edge retry FIRST, since the 30s loop is
  currently the feed's only recovery path.
- **The history ping drags a full /player.** `updateHistoryPosition` calls
  `getFormatInfo` first, and the mobile format cache TTL is measured from
  creation, not last access - so on any video longer than ~6 min every other 3-min
  ping re-fetches ~17 KB of /player for the video already playing.
- **Live re-fetches the whole /next every 60s** (`onTickle` ->
  `updateLiveDescription`), ~65 KB gzipped, and it keeps running in background
  audio and PiP - the 2026-07-16 live-chat lifecycle work missed this path.
- **Two third-party hosts on every watch open**, both on by default, both
  outside the tightened-timeout allowlist (so 20s connect/read): SponsorBlock and
  Return-YouTube-Dislike, ~3 RTT of fresh handshake each, neither cached.
- Signed-out Home costs 5 SERIAL `/browse` calls before anything paints
  (`BrowseService2.kt:42-57`) - the same shape tier-1 fixed for Subs.
- `visitorData` is resolved INSIDE an OkHttp interceptor and can perform a
  blocking `GET /tv` there (10h reuse window), in front of the first `/browse`
  AND the first `/player`, invisible to the `api-http[S]->[C]` span.
- Nothing cancels an API call at the HTTP level anywhere: `RxHelper` subscribes
  on a NON-interruptible `Schedulers.from(cachedThreadPool)`, and the only
  `Call.cancel()` in the tree is the cast sender. Disposal is cosmetic for
  network work; abandoned responses keep consuming the pipe. The switch-cancel
  fix above works around this at the ring level, not at the socket.
- Live opens fire 1-2 (up to 6) extra googlevideo GETs through OkHttp with an
  unconditional blind retry, using `@GET` where `@HEAD` was meant
  (`VideoInfoServiceBase:236-266`).
- Minor: live-chat reconnect loops with zero delay on the error branch; the
  updater retries 10x with no delay and buffers the whole APK in memory;
  `RssService` fans out 2 requests per channel with no concurrency cap;
  playlist opens can walk up to 20 `/next` continuations
  (`MAX_PLAYLIST_CONTINUATIONS`); search suggestions debounce at 200ms.

**Watch out for, from this round's changes:**
- `callTimeout(45s)` is on the SHARED client and rides every `newBuilder()`
  derivative. Bulk transfers must use `OkHttpManager.getStreamingClient()`; the
  two known ones (in-app APK download, cast proxy) are wired, but a new bulk
  caller would silently get cut at 45s.
- Web-pot `/player` attempts now run on the pool thread, so a timeout interrupts
  `PoTokenWebView`'s latch and forces a `forceRecreate=true` BotGuard rebuild.
  Grep for `Failed to obtain poToken, retrying` right after
  `player-ring attempt-timeout`; if that pairing shows up in the field, raise the
  budget or make the web-pot cancel non-interrupting.
- Removing `http.keepAlive=false` also restores keep-alive on media3's
  `DefaultHttpDataSource`, which is the Cronet FALLBACK leaf. Believed a benefit
  (range GETs to one host), but it is a real behavior change on
  Cronet-unavailable devices.
- The Glide hold/release must survive every exit path. It is released from
  `hideVideoStill` (above its visibility guard), on player error, on the
  comments/chat sheets, and by a 6s watchdog. Verified on the emulator: all
  related thumbnails render. A blank-forever related list means a path was missed.

---

## 13. The netshape rig (2026-08-06) — and what it proved wrong

Read this before doing any more bad-network work: it replaces the "we cannot
reproduce contention" caveat in §12.

### The rig
`tools/netshape.py` — an HTTP CONNECT proxy in WSL, ONE shared token bucket per
direction, live control, blackout toggle. Start it, point the emulator at it:
```
python3 tools/netshape.py --port 18080 --control 18081 --down-kbps 1200 --up-kbps 400 --rtt-ms 120 &
adb -s emulator-5554 shell settings put global http_proxy 10.0.2.2:18080
# ... work ...
adb -s emulator-5554 shell settings put global http_proxy :none
```
`/set?down=&up=&rtt=&blackout=`, `/stats`, `/timeline` (per-second bytes per
host), `/reset`. `bucket_host()` collapses the per-edge `rr7---sn-*.googlevideo.com`
names so the media row does not scatter.

Facts that make it work, each of which cost time to establish:
- **The emulator reaches WSL at `10.0.2.2:<port>`** — emulator → Windows
  loopback → WSL localhost forwarding. Verified with a throwaway listener before
  building anything; do that first if it ever stops working.
- **All four traffic classes honour the Android global proxy**: OkHttp (API),
  Cronet (media), Glide/HttpURLConnection (thumbnails), avatars. Cronet does
  disable QUIC and tunnel over CONNECT while a proxy is set — so QUIC-specific
  behaviour is NOT under test on this rig.
- **Blackout must STALL, not close.** A tunnel drops packets; it does not RST.
  Stalling is what reproduces the field bug; closing would produce instant clean
  errors and hide it.
- The emulator has no `curl` and no `wget`; use `toybox nc` for probes.
- `adb logcat -d | grep` can hang on the big buffer — redirect to a file first,
  then grep (and always `grep -a`).

### What the rig disproved
- **`DebugMediaShaper` was measuring the wrong thing.** It shapes only the media
  leaf, so it made media look like the bottleneck. Under a whole-app bucket the
  actual bottleneck at startup was *thumbnails*, taking more bytes than the video.
- **`NET_CAPABILITY_VALIDATED` is useless as an outage signal.** It stayed `y`
  through every second of a 150 s blackout. Any code that asks Android "is the
  network OK?" to decide whether to retry is asking the wrong question — ask
  whether the server answered instead. This killed one shipped fix from §12.
- **Bright Data cannot be used for this app at all** (`policy_20050`, KYC-gated):
  it refuses `www.youtube.com` and `*.googlevideo.com` on every zone. Do not
  spend time on proxy credentials for YouTube traffic.

### Watch out for, from this round's changes
- `TRANSPORT_DOWN_STREAK = 2` ends a ring walk on two consecutive no-response
  attempts. If a single client ever starts timing out routinely on a healthy
  link, this could end walks early — the `player-ring transport-down` line names
  the client, so check `lastClient=` before assuming the link was the problem.
- `hasServerVerdict()` treats a cause-less `IllegalStateException` as "no answer
  from YouTube" and therefore retriable. That rests on `RetrofitHelper` NOT
  raising for HTTP error statuses on the player path (`handleResponseErrors` is
  auth-transaction only). If that ever changes, genuine content errors would
  start getting the 5-step retry ladder.
- `fitThumbnail` only ever downgrades and only within
  `{mqdefault, hqdefault, sddefault}` — `hq720`/`maxresdefault` are not generated
  for every video, so widening into them 404s. The feed card is deliberately
  left at the API's rendition (it is full-width; 480px would be visibly soft).
  Only the related row and the loading still are fitted.
- The loading still now uses `onlyRetrieveFromCache(true)` with an `.error()`
  fallback. If Glide ever changes that failure to something `.error()` does not
  catch, the still goes blank rather than falling back — the video is unaffected.

### Still open (measured, not built)
- Recovery latency is dominated by the retry ladder's own spacing, not by the
  network. After a long outage the first retry can sit up to 300 s away. A
  cheap liveness probe (a 204 GET) while capped would let it resume within
  seconds of the link returning, instead of within minutes.
- `WEB_EMBED` costs a full 20 s attempt whenever it is reached. It is now
  reached far less often, but on a link that is slow rather than dead it is
  still the single most expensive entry in the ring.
- Everything in §12's "still open" list remains open; none of it was revisited.

## 14. Pixel pass and default-network callback recovery (2026-09-07)

See [`LIVE-PASS-2026-09-07.md`](LIVE-PASS-2026-09-07.md) for the baseline,
candidate checks, local HTTP failure reproduction, and 150-second emulator
outage result. A spontaneous Milo J media 403 remains reproducible and was
automatically recovered on the Pixel. No new client profiles were added.

`ErrorFixerController` now uses `DefaultNetworkRecoveryCallback`: Android's
default-network handover can omit `onLost(old)`, so the callback tracks the
replacement identity and waits for its validated capabilities. A replay of
the same healthy default at registration must remain quiet. Each registration
owns its posted retry and cancellation token; unregistering alone cannot stop
an in-flight callback from posting into a later capped episode. The 12 tests
cover these event sequences without changing the physical phone's radio.

The same-network silent-outage case still uses the bounded retry ladder. This
change does not add liveness polling or reduce its maximum 300-second spacing.

## 15. Startup transport comparison and fallback (2026-09-07)

See [`STARTUP-TRANSPORT-2026-09-07.md`](STARTUP-TRANSPORT-2026-09-07.md).
The old DefaultHttpDataSource fallback took about 8.2 seconds for every small
init range in a three-video Pixel sample. Stock OkHttp took 121–366 ms for the
same initialization ranges. Cronet remains primary with QUIC enabled; OkHttp
now serves the existing startup-timeout cooldown and missing-engine path.
Neither transport eliminated the existing initial media 403. The native
4.4-second delay seen earlier was not reproduced, so do not claim it is fixed.

MediaHttpClient shares the app pool for default-network eviction and retains
proxy routing/authentication, but uses stock TLS and excludes API interceptors,
cookies and origin authentication. Its whole-call deadline is zero for long
streams; read/write inactivity is four seconds, connection timeout eight.
Media3 owns ranges and headers; the existing cache remains above the transport.

MediaHostPreconnect now uses PreconnectGate: 60-second positive freshness,
five-second failure cooldown, current-network invalidation, two active jobs,
four remembered hosts, eight-second deadline and stale-completion protection.
This repair and Cronet metrics touch submodules currently on master. Do not
commit them or switch branches without resolving the main-only workflow.

Debug comparison properties are media_cache, media_transport and cronet_quic
under debug.arc. QUIC requires process restart; cache bypass preserves data.
Always clear comparison and one-shot timeout properties and restart before
handing the phone back. Negotiated protocol `unknown` is not proof of H2/H3.
OkHttp `result=ok` means transport completion; inspect HTTP status for 403.

## 16. Explicit playback denial and queued work (2026-09-07)

[`BOT-CHECK-2026-09-07.md`](BOT-CHECK-2026-09-07.md) records the preserved Pixel
screen and HTTP200/LOGIN_REQUIRED response for Rusowsky. No media preparation
occurred. The owner confirmed the video plays in the official YouTube app;
that does not establish the cause of NewTube's denial. The existing challenge
cooldown stays intact. No fingerprint/client-identity changes or external-player
flow were added.

The captured walk already covered `TV_DOWNGRADED`, `TV` and `VISIONOS`.
Both TV requests carried OAuth auth; all three body/header identity tuples match
the current upstream client declarations. There is no evidenced profile
constant to change. The eager `/next` request had started before the denial and
could still populate the failed watch page; the bot-check branch now disposes
that work and clears any delivered suggestions. This is request suppression and
UI cleanup, not a claim that the upstream playability result is fixed.

SessionWarmup now yields pending speculative jobs when the playback view
receives a video. Historical first_setup_done remains a hint, not an extractor
freshness guarantee: idle-browse warming is retained. Atomic claims prevent
duplicate feed/fallback scheduling and the post-delay check catches a real
selection during the delay. Already-running warmup is not forcibly interrupted.

SourceBuildGeneration skips stale queued source builders and checks delivery.
Its short publication lock coordinates with reset/release cleanup; keep lock
order generation then stash, and never hold the generation lock for XML work.
Completed matching source stashes must still survive the target video's reset.

The profile-audit debug APK was installed in place on the Pixel at 12:21:28 without
launching it or replaying the challenged video. Build plus 87 focused tests
passed; see the incident document for the exact command and private log path.

The owner then requested one controlled replay. At 12:27 the same Rusowsky open
again ended before media: two authenticated TV profiles returned `UNPLAYABLE`,
then `WEB_EMBED` returned the explicit Spanish bot check. Tap-to-info was
2.55 seconds. The eager suggestions cancellation ran, but the portrait UI hid
the reason because `setTitle` targets an overlay title suppressed in portrait.
The unplayable branch now also uses `showPlaybackNotice`; a playable result or
different video clears it. The rebuilt APK was installed at 12:35:32 with a
matching hash and inactive debug overrides. It was not launched or replayed
again. Evidence is under `replay-qa-20260907-122553`; raw logcat is private.

## 17. Walking past the bot check (2026-09-07, Pixel 9)

Historical observations follow. Section 23 supersedes this round's diagnosis:
the TV request was missing upstream timestamp normalization, and fixing it
restores accepted metadata with the existing account. The claims that only the
circuit breaker was ours, or that a different credential was necessary, were
premature. Preserve the measurements below, not those causal conclusions.

**One: authenticated TVHTML5 returned a reload-page verdict.** Every signed-in `TV` and
`TV_DOWNGRADED` request answers HTTP 200 / `UNPLAYABLE` with "Es necesario
volver a cargar la página." on every video, not just Rusowsky. yt-dlp tracks
the same breakage (issue #17389; its own tv client moved behind
`tv_downgraded` in commit 5d5b634). This server-side attribution was incorrect;
the later request-format fix in §23 changes the verdict to `OK`.

**Two: the anonymous partition is challenged on that network.** The bot check
arrives on the web-family clients, on a carrier CGNAT (`net=cell:333`), for
any video. It is a guest-session throttle, not a per-video verdict.

**Three, ours: the circuit breaker aborted the walk.** A challenge at attempt
3 of 10 raised the verdict immediately, so the ring never reached a client
that does not answer from the challenged web identity — and then suppressed
every video for 15 minutes. Two working clients sat unvisited behind a wall we
built ourselves.

### What changed

`BotCheckWalkState` holds the verdict instead of throwing it. A challenge is
recorded, and the walk continues while `hasUnchallengedClientAfter` finds a
later client that neither requires a web PO token nor is skipped. The verdict
is only raised if the ring genuinely ran out; `getActiveBotCheckResult` now
requires `mBotCheckRingExhausted` and lets one probe through per
`BOT_CHECK_PROBE_INTERVAL_MS` (60s), so a cleared challenge is noticed without
waiting out the window.

Two authenticated heads answering `UNPLAYABLE` with **zero** adaptive, regular,
DASH, HLS and SABR entries is a structural signal — `isAuthRouteReloadVerdict`
does not read the message text. Two such hits on *different* videoIds
(`AUTH_RELOAD_QUARANTINE_MIN_HITS`) quarantine the route; `BotCheckDetector.
isReloadPageVerdict` exists for the log line only, and routing must never
start depending on it. `VISIONOS` is injected ahead of the web-PO-token
clients in the signed-in fallback, so a quarantined open goes straight there.

Measured, five videos at shipping defaults: 0 bot checks, 0 load errors,
0 403s, 70 clean media loads. The quarantine arms on video 2
(`quarantine-auth-route reason=no-media-verdict quarantined=1/2` then `2/2`)
and time to first frame goes 2221 → 1060 → 822 ms as three `/player` round
trips collapse into one. `VISIONOS` served 140 media loads across two sessions
with zero errors.

### Two paths measured and rejected — do not re-litigate without new evidence

**A player PO token for `ANDROID_VR` does not prevent its 403.** yt-dlp marks
the client `not_required_with_player_token` on all three GVS protocols, so
attesting the request should make its media URLs stop needing a token. Both
arms reached first frame and both died on the same deep-range
`load[E-http] code=403` about nine seconds in: pot off at `req=854906+158684`,
pot on at `req=991541+181034`. That is the wall yt-dlp recorded on 2026-08-17
before dropping the client (commit dae52d8). Off behind
`debug.arc.player_pot`; the measured numbers are at the `setPlayerPotEnabled`
call site.

The correctness half of that work is **not** gated on the flag:
`PoTokenGate.getPoToken` used to feed both the `/player` body and three
media-URL call sites through one branch, so a Web-minted token could reach a
non-Web client's media URLs. `getPlayerRequestPoToken` is now the only site
allowed to attest a non-Web `/player` body, with the decision table extracted
to `PoTokenSelection` so it is testable without a WebView.

**The account cannot ride `WEB_EMBED`.** yt-dlp's default signed-in head
returns HTTP 400 `{"message":"Request contains an invalid argument.",
"reason":"badRequest"}` for us — three opens, three identical failures, before
any playability verdict. The same string already annotates `WEB_CREATOR` in
`AppClient`: upstream met this years ago. yt-dlp gets away with it because it
sends cookie-derived SAPISIDHASH; it removed OAuth support entirely. InnerTube
will not take a TV device-flow bearer on a web client. Off behind
`debug.arc.web_auth`.

To read that 400 at all, `RetrofitOkHttpHelper` had to decode error bodies:
the logging interceptor sits above the brotli decoder, so every failed
`/player` printed as mojibake. Decoded by reflection, since `okhttp-brotli` is
`implementation`-scoped in another module.

### The open thread

Every winning line now reads `auth=n`. Playback works and it is anonymous,
which silently costs age-restricted and members-only videos and server-side
watch history. P3 did not establish that a different credential was necessary;
that earlier inference is superseded by §23. `VideoInfo.isServerLoggedIn()`
(logged as `srvAuth=`) exists to tell
whether the server considered a request signed in; it reads `?` throughout
this round and is still unvalidated against a known-positive case.

**Sharpened by the LTE rounds of the same day (§18), and it matters.** Every
`TV` and `TV_DOWNGRADED` result across all five rounds reads `srvAuth=y` — the
server *did* consider those requests signed in, and answered "reload page"
anyway. So our credential was not being rejected or ignored on the TV clients.
The later metadata fix demonstrates a request-format problem instead; do not
use this round to justify new credential forms or session changes. `srvAuth` is
validated against a known-positive case now; the `?` is the non-TV clients,
which do not return `serviceTrackingParams` at all.

## 18. Two LTE soak rounds after the ring change (2026-09-07)

Wi-Fi off, LTE only (`net=cell:337`), five rounds on the Pixel 9. Two bugs, one
of them caused by §17, and one non-finding worth recording so it is not
rediscovered.

### The quarantine counted evidence it did not have

`isAuthRouteReloadVerdict` matches a structural shape — account-bearing client,
UNPLAYABLE, zero media of any kind. A video that is simply unavailable produces
that shape from *every* client, so the "two different videoIds" safeguard
separated nothing: two unavailable videos in a row is an ordinary afternoon.

Opening the lofi 24/7 stream (`jfKfPfyJRdk`), whose recording is not published,
walked all eleven clients and scored a quarantine hit on both authenticated
heads — with `reloadPage=n` printed on the same line. The failure is silent:
the account route is demoted and everything afterwards is served anonymously.

`AuthRouteWalkState` holds each observation until some other client serves the
same video. Something plays → the auth head is the outlier, count it. Nothing
plays → the video is the outlier, drop it. Verified on device: the lofi stream
logs `auth-route held` twice and counts nothing; two videos that do play
elsewhere still reach `1/2` then `2/2` and quarantine the route as before.

### The live dash search cost six round trips, not one

`sPreferDashManifestForLive` holds an HLS-only live result and walks on toward a
client with a dash manifest. Its comment says this costs one extra round trip,
and that was true while ANDROID_VR sat near the front. §17's quarantine
reordering pushed it to seventh, so it cost six.

Evidence gathered before changing anything, two 24/7 streams: every web-family
client answered `dash=n`, ANDROID_VR answered `dash=y` for both. The walk now
skips clients that cannot answer with a dash manifest (`isLiveDashCandidate`).
`5yx6BWlEVcY` reached ANDROID_VR at attempt 4 instead of 7; `4xDzrJKXOOY` at 2
instead of 5, first frame +2472ms → +1216ms. VOD unchanged (+723ms).

### Not a bug: the 25s LTE outage

Cutting mobile data for 25s mid-video looked at first like a 26-second freeze —
the network validated at 14:13:04 and the player did not report an error until
14:13:30. It is the opposite. Position advanced 68194ms over 71s of wall clock
(0.96x, the gap being the initial first frame) with zero buffering events: the
50s buffer covered the entire outage, and the error is the buffer finally
running dry. One `url-remint` reload, first frame 3s later, resuming at the same
position. Read the position delta before calling a quiet log a stall.

The one thing left on the table there: between the network returning and the
buffer running dry there were 27 seconds in which the failed chunk could have
been refetched, which would have made the outage invisible. That needs media3 to
retry a chunk after a fatal source error, which is not reachable from here, and
it is worth 3 seconds.

Also observed, and deliberately not changed: `applyNoPlaybackFix` on the
recovery path calls `switchNextFormat`, which for a VISIONOS/ANDROID_VR winner
resets the PO token cache — so a pure `ERR_INTERNET_DISCONNECTED` costs a
BotGuard re-mint (`visitorAgeMs=0` on the recovery walk). It is the same
"blame the client for the network" shape as the quarantine bug, but the
existing comment argues a network reattach may sit behind a new public IP that
the old identity no longer matches, so a fresh visitor may well be right. No
evidence either way; measured recovery was 3s. Left alone.

### Harness notes

`scratchpad/soak.py` streams logcat to a file instead of reading the ring
buffer at the end — a 3.5-minute debug round produced 7356 lines and rolled the
first three opens out of the buffer (`EventLogger` and `pixel-thermal` dominate;
NetPath was 404 of them, and both `EventLogger` and the per-chunk
`NetPathLoadListener` are `BuildConfig.DEBUG`-gated). Seek drags need the
seekbar's real bounds read from `uiautomator dump`; guessed coordinates produce
`position-discontinuity delta=0` and look like a seek that did nothing.

Airplane mode is the wrong outage lever on this device: it re-associates Wi-Fi
on the way back and hands the default network to it, silently ending an
LTE test. Use `svc data disable`/`enable`.

## 19. Where the memory goes, and how far the account gets (2026-09-07, Pixel 9, LTE)

Two questions closed by measurement rather than by reasoning: is the ~450 MB
PSS a leak, and what does anonymous playback actually cost.

### PSS is a working set, not a leak

PSS (Proportional Set Size) counts a process's private pages in full and its
shared pages divided by the number of sharers - roughly "what comes back if
this process dies". The 453 MB quoted in §18 was sampled mid-playback and was
never a resting figure.

Six videos, one sample while playing and one after BACK (`scratchpad/mem.py`,
run `mem-143620`):

| after video | 1 | 2 | 3 | 4 | 5 | 6 |
|---|---|---|---|---|---|---|
| playing (MB) | 426 | 515 | 499 | 522 | 482 | 508 |
| resting (MB) | 370 | 358 | 355 | 375 | 364 | 366 |

Resting is flat across six opens - no trend. Views pin at exactly 400 from the
first open and never grow; AppContexts oscillate 10-11; Activities stay at 2
(`MobileBrowseActivity` + `MobilePlaybackActivity`, both legitimately in the
back stack). Java heap allocation stays in the 14-40 MB band with no drift.

A forced `am send-trim-memory COMPLETE` drops the process to **256 MB**:
graphics 91 -> 6 MB, views 400 -> 177, contexts 11 -> 8, activities 2 -> 1. So
~110 MB of the resting figure is cache the app hands back the moment the system
asks. Nothing here is a leak.

The one monotonic series is native heap at rest: 60, 64, 67, 68, 71, 73 MB,
about +2.2 MB per video - and the trim returns it to 59 MB, i.e. it is
allocator/cache retention rather than growth. Worth re-measuring if a future
round ever shows it surviving a trim.

Playback peak is dominated by Graphics (200-245 MB of the ~500 MB): decoder
output buffers plus surfaces, released on BACK.

### A cold-start spike coincides with SessionWarmup: ~135 MB for ~2 s

Sampling meminfo once a second through a cold start isolates it to one sample:

```
t=+4s   native= 57 MB   PSS=344 MB
t=+5s   native=186 MB   PSS=471 MB    <- SessionWarmup 14:44:17.863 -> .722
t=+6s   native= 51 MB   PSS=332 MB
```

The window matches `SessionWarmup` exactly (`session warmup: start` ->
`session warmup: done`, 859 ms, containing the player-JS work and the three
warmup /player calls). Which component inside that window allocates the 135 MB
was NOT isolated - the JS parse is the likely candidate given the class comment
describes a multi-MB parse, but treat that as unconfirmed.

On this device (11.8 GB) it is invisible and self-correcting. It matters only
on a low-RAM phone, where a 471 MB peak at second five of every cold start -
before the user has touched anything - lands exactly when the LMK is most
willing to kill a young process. If NewTube ever targets 3-4 GB devices, gating
the warmup on `ActivityManager.isLowRamDevice()` is the cheap mitigation. Not
done: no low-RAM device to measure on, and guessing at the threshold is how you
ship a regression to the machines you cannot test.

### The account works everywhere except /player

Measured on a cold start: every `/browse` and `/account` call carries the
account (`api-http[S] ... auth=y`), and only `/player` lands anonymous. So the
blast radius of §17's open thread is narrower than "signed out":

- **Works:** home/subscriptions feeds, playlists, likes, subscribe, account list.
- **Broken:** age-restricted, members-only and private/unlisted playback;
  server-side watch history (the `cpn`/`ei` that `TrackingApi` pings with come
  out of the /player response, which is the anonymous one, so watch time
  credits the anonymous visitor); Premium entitlements if the account has them.

### What is left after ruling out the cheap fixes

Correction: the following credential-only interpretation was premature.
Matching client versions did not check timestamp formatting. Section 23 fixes
that request field and obtains accepted authenticated TV metadata without
changing the credential. The web-bearer HTTP 400 results below remain historical
observations, not proof that the TV account route is unusable.

Our `TV_DOWNGRADED` is `clientVersion = 5.20260707` - **byte-identical to
yt-dlp's `tv_downgraded`** (checked against its `INNERTUBE_CLIENTS` table).
That client version was already in place here and still answered "Es necesario
volver a cargar la página" with `srvAuth=y`. It ruled out a version mismatch,
not other request-format defects; the missing timestamp normalization in §23
was not checked in this earlier comparison.

yt-dlp's authed clients are `('web_embedded', 'tv_downgraded', 'web')`, and the
clients it marks `SUPPORTS_COOKIES` are exactly `web, web_safari, web_embedded,
web_music, web_creator, mweb, tv, tv_downgraded`. Its credential is
cookie-derived (`_make_sid_authorization`, ytdlp `_base.py`):

```
SAPISIDHASH <ts>_<sha1(f"{ts} {SAPISID} https://www.youtube.com")>
```

emitted three times over `SAPISID`, `__Secure-1PAPISID`, `__Secure-3PAPISID`
and space-joined. Ours is a TV device-flow OAuth bearer, which InnerTube takes
on the TV family and refuses on the web family with a flat HTTP 400.

**Historical web-bearer comparison; not a diagnosis of the TV verdict.**
`debug.arc.web_auth` was widened from a WEB_EMBED boolean to a client NAME, and
three arms were forced through `debug.arc.player_client` on one video, one
network, with the bearer as the only variable (`scratchpad/webauth.py`, run
`webauth-145115`):

| arm | client | `auth=` | HTTP | body |
|---|---|---|---|---|
| A | WEB_EMBED | y | **400** | hash `7b125bdfc2` |
| B | WEB | y | **400** | hash `7b125bdfc2` |
| C | WEB | n | **200** | 40 formats |

The same WEB client answers 200 without the bearer and 400 with it, and WEB and
WEB_EMBED fail with a byte-identical body. So the 400 follows the credential,
not the embed context, and no reordering of web clients can route around it.
For contrast the same bearer draws HTTP 200 from TV in the same run - TVHTML5's
refusal is a playability verdict, not a transport-level rejection.

These arms demonstrate that the two tested web requests rejected that bearer.
They do not rule out request-format bugs in the separate TV routes, nor prove
that importing cookies is necessary. Section 23 restores accepted TV metadata
using the existing credential.

Note the arms ran on Wi-Fi (`net=wifi:339`) rather than LTE. Acceptable here -
the question is whether InnerTube accepts a credential, which is not a
transport property - but it is why these numbers are not comparable to the LTE
timings in section 18.

The earlier suggestion to design a cookie-import flow is withdrawn. The owner
explicitly requires the existing sessions, without borrowing or rotation; the
TV timestamp fix demonstrates why the credential-only conclusion was premature.

## 20. Pixel performance and PiP follow-up (2026-09-07)

See [`PERFORMANCE-2026-09-07.md`](PERFORMANCE-2026-09-07.md) for the full matrix,
reproduction commands, private artifacts, and caveats. Targeted seek, artwork,
metadata, hidden-UI polling, warmup, initialization-order, and cache-key fixes
are installed; 76 Android tests plus 10 offline benchmark tests pass.

Both controlled 13-phase arms played successfully. Whole-process CPU/PSS and
decoded TTFF did not establish a broad gain. Keep dynamic scheduling off,
eager startup setup on, and the start gate at 1000 ms. The 500 ms gate saved
~618 ms of visible-picture delay in a shaped dense-resume ABBA, but its longer
soak and a final normal-settings check were denied upstream before media.
Do not count those failed opens as low-CPU or stall-free playback. All debug
overrides used in the round were restored; no auth/client policy changed.

The earlier allocation attribution in section 19 is only a timing correlation:
new diagnostics distinguish cancelled speculative format warming from eager
setup that can still allocate during a real open. Deferring eager setup loses
about a second of first-play latency on this Pixel.

The apparent second PiP belonged to ReVanced, opened by an incorrectly quoted
ad-hoc adb URL; the owner dismissed it. The harness now quotes and verifies the
target package, refuses existing PiP, and tests the quoting regression. The
later NewTube PiP → different video test left one activity and no pinned task.
Further rapid-card/lifecycle and normal sustained validation is pending server
playback availability, not a license to change credentials or client behavior.

## 21. TTFF-first policy and explicit denial retry (2026-09-07)

The owner prioritizes fast visible startup and stable playback over resource
use. This supersedes section 20's 1000 ms start decision: production now starts
at 500 ms, with recovery still 1500 ms and forward/back buffers unchanged.
Native transport/cache prewarming, deferred related rendering, and instant
post-READY new-video still removal accompany it. See
[`TTFF-PRIORITY-2026-09-07.md`](TTFF-PRIORITY-2026-09-07.md).

Four local-asset Pixel ABBA runs exercised the real decoder/network readiness
policy with 1500 kbit/s paced reads. Median READY 1091 → 693 ms, no unexpected
buffering in 45 s soaks or pause/seek/switch phases. These are not YouTube/API
TTFF results. 110 Android unit/Robolectric and 13 harness tests pass.

Fresh server bot-check responses continue despite successful account requests;
the owner confirms the same video works in official YouTube. Do not call this
solved. The pre-media denial's dead Play/Pause retry was fixed through the normal
format service, preserving cache/cooldown and avoiding error-driven client
switching. An actual retry still received denial; the next tap hit the negative
cache in 7 ms without playback HTTP. Benchmark matrices now stop on unavailable
playback instead of repeatedly cold-starting past process-local cooldown state.
No auth, identity, token, or client-order policy changed.

## 22. Existing-session follow-up (2026-09-07)

The owner explicitly wants the current SmartTube/yt-dlp-derived implementation
and existing sessions, without borrowing sessions or rotating identities.
The audit confirmed that bundled yt-dlp/EJS code executes in Kotlin/J2V8, but
the app's own service still owns player requests and authorization. Both the
prior and new Pixel capture attempt all nine eligible routes; the earlier
winner also answers an explicit denial. No usable result was demonstrated to
be discarded before the media-URL processor. Selected-account credential
ownership/expiry checks do not explain the fresh authenticated server denials.

One older behavior violated the session-preservation requirement:
`VideoInfoService.noteAnonymousChallenge()` called `rotateWebVisitor()` after
repeated denials. That call was removed, without changing cooldown, threshold,
client ordering or normal session expiry. Five real-method offline tests and
seven actual-converter parser tests pass. The normal Pixel replay logs
`sessionPreserved=y` but still receives no media; the benchmark exits 2 after
9.51 s. No further playback attempts were started. This is not a playback fix
or real-video end-to-end acceptance. Details, APK hash and artifacts are in
[`TTFF-PRIORITY-2026-09-07.md`](TTFF-PRIORITY-2026-09-07.md).

The new runtime change/tests are uncommitted in `MediaServiceCore` (still on
its original branch); do not treat the submodule as unchanged after this round.
No commits, pushes, credential exports or account resets were performed.

## 23. Existing TV request metadata repaired; media 403 remains (2026-09-07)

The upstream comparison found the missing TV-specific timestamp normalization
in `QueryBuilder`. The Pixel had a five-digit cached scalar; both TV request
routes sent it unchanged. Porting the upstream automatic five-to-eight-digit
formatting changes the same signed-in route from reload-page `UNPLAYABLE` to
`OK` with 22 usable adaptive formats. Account, visitor, client ordering and
non-TV requests are unchanged. This supersedes the credential-only/server-only
conclusions in sections 17 and 19.

One real Pixel availability check on the installed candidate reached media
preparation, then both initial ranges returned HTTP 403 with zero media bytes.
The app exhausted its existing four-error recovery cap without a first frame.
Do not report this failed run's resource usage as playback performance, or its
error-cleanup still removal as visible TTFF. Cold/immediate, warm-card,
mid-playback switch and sustained real-video acceptance remain outstanding on
this candidate.

Eight serialization tests plus eight nonsecret diagnostic tests were added;
all 138 focused Android tests and debug/release builds pass. The upstream clone
and local yt-dlp comparison found identical bundled solver code; no demonstrated
media-URL handoff defect or skipped usable non-SABR response explains the 403.
Cached-code consistency and transformation-output completeness remain evidence
gaps, not established causes or a reason to change identities. Details, precise
scope, current APK hash and artifacts:
[`PLAYER-METADATA-2026-09-07.md`](PLAYER-METADATA-2026-09-07.md).

## 24. Cache integrity repaired; current media 403 not resolved (2026-09-07)

Three offline fault-injection tests reproduced incoherent cache publication:
old contents could be returned under a new key/version after a failed write.
CacheService now stores one atomic envelope per section containing the full key,
code and metadata, under a shared load/store/clear lock. Unverifiable legacy entries
are ignored without deletion; IO failures preserve the previous complete entry.
Fifteen cache tests cover real partial-write rollback and interrupted recovery,
in addition to eight nonsecret transformation-diagnostic tests.

The installed debug candidate is
`5eb8e3dcbbb25c0f975311ed7f6ca5bd65fbaedb22ca13f4e5f374401b07cf41`.
One normal Pixel replay still returns initial media 403 and no first frame.
New cached code has the same SHA-256 as the old code. All 23 requested values
in each transformation group have present, changed outputs of matching list
length. These results close the missing-output/cache-corruption hypotheses for
this capture; they do not prove algorithmic correctness or server acceptance.
The benchmark stopped at non-connectivity recovery exhaustion; no further
playback requests were made by the test. No session/account/client-policy change.

All 161 focused Android + 27 offline harness tests and debug/release builds pass.
SABR support remains a concrete capability gap: the accepted TV response needs a
streaming-source/protocol module missing from this Media3 port. The `tv-legacy`
tag preserves SmartTube's implementation, but it is bound to old ExoPlayer APIs
and cannot be enabled by changing response classification or imports alone.
Supporting it is a substantial new integration, not a verified cure for these
403s. Exact evidence, private artifacts and integration scope:
[`MEDIA-CACHE-INTEGRITY-2026-09-07.md`](MEDIA-CACHE-INTEGRITY-2026-09-07.md).

## 25. Isolated SABR proof stopped at media HTTP 403 (2026-09-07)

User approved proof before a full production streaming-source port. Added
test-only retained SABR schemas, bounded UMP inspection, opted-in instrumentation
and an in-memory decoder helper. 218 app unit tests passed; the Pixel's local
fixture produced 30 decoded output buffers per track in 2 passing helper tests.
These are not real-video render/TTFF/soak evidence.

Headless instrumentation initially skipped normal SplashPresenter preferences
setup. The harness now initializes the same target-app preferences and waits for
existing account restore. The resulting single live probe confirmed TV raw `OK`,
server authentication and no bot flag (1044 ms), selected AAC 140 / AVC 136, then
received HTTP 403 on the first audio SABR POST (333 ms). It stopped immediately;
no video request, denial retries, session rotation or new credentials.

The URL was deliberately used as issued, not run through the normal parameter
processing path. Therefore this negative result is not proof of full SABR
incompatibility or the cause of the denial. No production SABR source was added;
the main installed APK remains `5eb8e3dcbbb25c0f975311ed7f6ca5bd65fbaedb22ca13f4e5f374401b07cf41`.
Current real-video cold/warm/switch/sustained acceptance remains open.
Evidence, exact bounds and limitations:
[`SABR-PROOF-2026-09-07.md`](SABR-PROOF-2026-09-07.md).

## 26. The timestamp hack is what kills the media URLs (2026-09-07, Pixel 9 + off-device)

Rusowsky (`Fo89b8zAIE4`) plays. It has been playing all along — every open just
failed once first, and that failure is what kept the phone anonymous.

**The open, before this round.** `TV_DOWNGRADED` answers `OK` with 22 formats and
`srvAuth=y`; its media URLs 403 on the very first byte range; the app quarantines
the route, reloads on `VISIONOS` and plays. Tap to first frame 5.15–5.48 s,
against 2.80 s when `VISIONOS` is forced (`debug.arc.player_client`).

**Reproduced off the device** — a laptop on a different IP, a fresh anonymous
visitor from `sw.js_data`, and the app's own bundled EJS solver
(`assets/nsigsolver`) run under node, so the app's exact transform is what
signs the URL. One video, one session, one minute:

| request | verdict | media |
|---|---|---|
| TVHTML5 5.x, real sts `20697` | `UNPLAYABLE` "the page needs to be reloaded" | — |
| TVHTML5 5.x, suffixed sts `20697001` | OK, 22 ciphered formats | **403** on the first byte |
| TVHTML5 7.x, suffixed sts | OK | SABR-only, no URLs |
| TVHTML5 7.20260901.15.00 (the version youtube.com/tv actually serves), real sts | `UNPLAYABLE` reload | — |
| any TVHTML5 with no `signatureTimestamp` at all | `UNPLAYABLE` reload | — |
| **TVHTML5_SIMPLY, real sts** | OK, 22 ciphered formats | **206** |
| TVHTML5_SIMPLY, suffixed sts | OK, the same 22 formats | **403** |

The last two rows are the finding. Same client, same session, same IP, only the
timestamp differing: the upstream `+001` suffix makes the server answer with
formats whose URLs are already dead. That is also what kills `TV_DOWNGRADED` —
except there the suffix is the only thing that gets an answer at all, so the
TVHTML5 family has no working configuration today, with or without the account.
This supersedes §23's "media 403 remains" as an open question: the metadata fix
was real, the URLs it unlocked never were.

**Our signature solver is correct.** TVHTML5_SIMPLY's ciphered URLs, deciphered
by our own solver, serve 206; stripping the transformed `n` from the same URL
returns 403. Both transforms are right, which closes the cached-code and
transformation-output gaps left open in §23 and §24. Every player JS variant
(`main`, `es6`, `tce`, `es6_tce`, `tv`, `tv_es6`, `phone`) yields byte-identical
output, so variant choice is not a variable either.

**What the bot check is.** `LOGIN_REQUIRED` / "Sign in to confirm you're not a
bot" is a property of the anonymous identity, not of the video: the same client
on the same IP is challenged with no `visitorData` and answers `OK` with a
freshly minted one. It is not purely identity-bound either — `ANDROID_VR` is
challenged on a brand-new visitor, and §17 recorded a `WEB_EMBED` challenge on
the very visitor `ANDROID_VR` then played from. Read it as a joint verdict on
(client, identity, IP). The phone meets it because the dead account route leaves
every playback anonymous on a carrier CGNAT.

### What changed

- **`QueryBuilder`: the `+001` suffix is scoped to the Cobalt `TVHTML5` client**
  (`AppClient.usesTvSignatureTimestamp`) instead of every enum whose name starts
  with `TV`. Upstream applies it to all of them; on TVHTML5_SIMPLY that is the
  difference between 206 and 403. Latent for now — see the caveat below.
- **A SABR-only answer from an account head is a no-media verdict**
  (`isAuthRouteSabrOnlyVerdict`). `TV` returns `formats=22+1 usableAdaptive=0
  sabr=y`, which this port cannot play (§24/§25), yet nothing quarantined it, so
  the walk paid for it on every open. It now feeds the same
  two-different-videos streak as the reload-page shape, with restricted videos
  excluded so a gated video is never read as evidence about the route.
- **The 403 quarantine survives a process restart** (`AuthRouteQuarantineStore`,
  phone only). Same 10-minute TTL and network keying, so the route is still
  re-probed when it expires — just not once per cold start.
- **A fresh bot challenge rotates the anonymous identity**
  (`setRotateVisitorOnAnonChallenge`, phone only): visitor cookie, cached app
  info, persisted app info and the Web PO-token session. **This deliberately
  reverses §22**, at the owner's request in this session, and is narrower than
  the behaviour removed there: the ACCOUNT credential is untouched, only the
  guest identity rotates, at most once per 15-minute cooldown per network.

**Measured after, Pixel 9 / LTE:** the walk converges in two videos (`TV`
quarantined `2/2` by the SABR verdict, `TV_DOWNGRADED` by the 403), then every
cold open restores `quarantined=2/2` from prefs and goes straight to `VISIONOS`
— one `/player`, no 403, no reload, tap to first frame 3.2–3.8 s, 27 clean media
loads on a sustained watch, zero bot checks. 139 focused youtubeapi + 218
smarttubetv + 48 common tests pass; debug build green.

### Caveats

- **TVHTML5_SIMPLY is NOT in the phone ring and must not be added yet.** Fixed
  timestamp and all, it serves the first ~300 KB and then 403s deep ranges
  (bytes 5,000,000+) — yt-dlp marks `tv_simply` GVS-PO-token-required and that
  matches. `VISIONOS` served the identical deep ranges 206 in the same run. The
  timestamp fix is correctness for the day a GVS PO token exists, not a route.
- **The rotation has not been exercised against a real challenge.** No bot check
  occurred in any run this round, so what is verified is the mechanism (unit
  tests: every cached copy of the old visitor is dropped) and the fact that a
  fresh visitor clears a challenge off-device — not that rotation fixes a
  challenge the phone is actually under. The `visitorRotated=` field on
  `player-ring anon-challenged` says which happened; read it before claiming it.
- Restoring authenticated playback still has no known path. TVHTML5 is dead at
  every timestamp we can send, `WEB_EMBED` refuses our OAuth bearer with HTTP 400
  (§19), and TVHTML5_SIMPLY does not support auth at all. What is left is a
  credential form we do not have (cookie-derived SAPISIDHASH) or SABR support.

## 27. SABR delivers; the 403 was the client gate (2026-09-08, Pixel 9 + off-device)

**The SABR source works on real YouTube.** §25's 403 was never a protocol,
parser or network problem: eligibility was gated on an authenticated **TVHTML5**
response, and TVHTML5 is the one client whose media is dead (§26). Pointing SABR
at a client that actually serves it makes it play.

Reproduced off-device — different network, different IP, anonymous identity, no
PO token, one video, one session — POSTing each response's own
`serverAbrStreamingUrl`: **VISIONOS, IOS, ANDROID_VR and ANDROID all return HTTP
200 with 133,605 bytes of UMP media** (demuxes to H.264 + AAC under `ffprobe`),
while **TVHTML5 7.x returns HTTP 403 with a zero-byte body**. yt-dlp marks the TV
family GVS-PO-token-required; this port cannot mint one. TVHTML5 5.x
(`TV_DOWNGRADED`) has no SABR endpoint at all.

Three defects had to be fixed together — the first alone only exposes the second:

1. `SabrVodCapability.isEligible` now gates on `AppClient.isSabrSupported`
   (VISIONOS/IOS/ANDROID/ANDROID_VR) instead of on the account. The TV family
   stays excluded, so its existing SABR-only quarantine verdict is unchanged.
2. **There is no "video only" request.** `enabledTrackTypesBitfield` is not a
   bitfield: `1` returns audio alone, and `0`, `2`, `3` all return audio AND
   video. The old `2` meant every video response carried an unrequested audio
   format and the parser killed the source with `unexpected_format`. A video
   request now names the companion audio and declares it fully buffered — the
   protocol's own suppression mechanism. Foreign formats are discarded, not fatal.
3. **A media-free response is the server pacing delivery, not an error.** Once
   ~30 s are buffered ahead of the playhead it answers with control parts and no
   media. Treating that as terminal is what stopped the first successful device
   run one second after its first frame. The source now backs off and only fails
   a stream that has nothing left to play.

**Measured, Pixel 9 / Wi-Fi, client pinned to VISIONOS for both arms, 6 opens
each in ABBA order, identical formats (itag 136 720p AVC + 140 AAC) in all 12
opens, zero rebuffers and zero dropped frames throughout:** first frame median
**DASH 267 ms vs SABR 333 ms** (+24.7 %), app UID bytes **DASH 4,590,625 vs SABR
4,099,509** (−10.7 %), CPU +4.8 %. SABR costs ~66 ms of startup and saves ~490 KB
per eight seconds of 720p.

Caveats: one video, one network, six opens per arm, no soak, no cellular, no ABR
or battery evidence; the UID byte counter includes concurrent loading. **The
client was pinned** — unpinned, this video was answered by `TV_DOWNGRADED`
(DASH, no SABR), so SABR only engages once the ring reaches VISIONOS. The
preference stays **off by default**.

Why it still matters despite being slower: `IOS`, `ANDROID` and `TVHTML5` already
return zero formats with URLs — they are SABR-only today. `VISIONOS` and
`ANDROID_VR` are the two that still hand out URLs. A working SABR source is what
keeps playback possible when VISIONOS follows them.

**Shipped as a second source in 1.8.1** (versionCode 10801, signed with the
NewTube release key, `releases/1.8.1/`). DASH stays the default and the toggle
stays off until the user turns it on; the SABR module's runtime classes are in
the release DEX and its proof/fixture code is not. This supersedes the 1.8.0
`sabr-experimental` candidate under `releases/experimental-sabr-2026-09-08/`,
which carries the broken client gate — do not install that one to test SABR.
Not published as a GitHub release: no release record, poster or announcement
copy exists for 1.8.1, only CHANGELOG entries.

Full detail, tables and reproduction:
[`SABR-MEDIA3-2026-09-08.md`](SABR-MEDIA3-2026-09-08.md).

## 28. SABR was never wired as a fallback (2026-09-08, Pixel 9, four Tiny Desk videos)

§27 got SABR *delivering*. It did not make it a **fallback**: the route that was
supposed to carry a response with no playable links could not be reached at all,
and the only way SABR ever ran was by displacing a DASH route that already
worked. Exactly backwards from what a fallback is for.

**The dead wire.** `VideoInfo.containsAdaptiveVideoInfo()` returns false when
`isAdaptiveFormatsBroken()` - i.e. when every adaptive format lacks a URL, which
is precisely a SABR-only answer (upstream even left `// TODO: remove when SABR
parser will be fixed` on it). That false propagates to
`YouTubeMediaItemFormatInfo.mContainsAdaptiveVideoFormats`, so
`containsSabrFormats()` is false, so `VideoLoaderController`'s
`player.openSabr(...)` branch never runs. Meanwhile `VideoInfo.isUnplayable()`
*already* consulted `SabrVodCapability.accepts()`. Enabling SABR therefore
flipped the response from "unplayable" to "playable" and then handed the player
nothing to open.

**Measured, four Tiny Desk videos, Pixel 9 on cell, 20 s per open:**

| arm | client | SABR | outcome |
|---|---|---|---|
| A | ring | off | 3/4 opens: TV_DOWNGRADED wins -> **media 403** -> reload -> VISIONOS -> plays |
| B | IOS (pinned) | off | link-less answer = unplayable -> **app skips to the next video**; 16 episodes, 0 frames |
| C | IOS (pinned) | on | `playable=y`, **no `prepare` line at all** - the player just sits there |
| D | ring | on | VISIONOS with working links -> SABR **preempts DASH** and plays |

Arm A is worth keeping in mind on its own: the media 403 of §26 is still there on
most first opens, and what rescues it is the existing client reload
(TV_DOWNGRADED -> quarantine -> VISIONOS), not SABR. TV_DOWNGRADED has no SABR
endpoint, so SABR cannot help that case at all.

**The fix** (three files, all small):

1. `YouTubeMediaItemFormatInfo.from()` publishes a link-less adaptive list as
   adaptive when `SabrVodCapability.accepts()` - so `containsSabrFormats()` is
   true and the SABR route is reachable. With the capability off, nothing moves.
2. `SabrSourcePreference` splits into two roles: **fallback** (carry a response
   with no links) and **preferred** (displace working DASH links; unchanged
   experiment). `openDash` preemption and the next-video prebuild key on
   *preferred* only. The fallback was built default-**ON** and turned **OFF**
   before release - see "Verdict" below; both roles are opt-in today.
3. A failing **fallback** SABR source hands the real cause to the shared fixer
   instead of `TerminalSourceException`, and `allowsAutomaticSourceRecovery()`
   stays true for it. A failing *preferred* source is still terminal - that one
   displaced something that worked.

**After the fix, same videos, same phone:**

| arm | client | outcome |
|---|---|---|
| E | ring, fallback on | VISIONOS -> `type=dash-mpd` -> first frame. No preemption, no 403, unchanged from A's happy path |
| F | IOS (pinned) | **route now reached**: `info dash=0 sabr=y` -> `prepare type=sabr-vod` on 4/4 - then fails `response_reload_required` |
| G | IOS, fallback off (= today's default) | identical to B - the switch turns it back off cleanly |

**Closed - it was never about the reload part (2026-09-08).** The reload is a
symptom; the cause is an **attestation wall at ~60 s**, and the request shape was
innocent all along.

The chase, in order. A new instrumentation test (`SabrReloadDiagnosticTest`,
androidTest) runs the app's OWN `SabrProtocol.request()` and media transport
against the endpoint the ordinary metadata pipeline issued, and reports a UMP
part census. Through it, an IOS request at **position 0** returns real media on
all four videos (64 KB audio / 188-517 KB video). So the body, the client info,
the config, the UA and the transport are all fine. What differed in real playback
was one line in the log: `position-discontinuity reason=seek from=0 to=1029273` -
the app resumes from watch history, and the failing loads were the ones issued at
that position.

Sweeping the start position (off-device, three videos, fresh anonymous session):

| start position | IOS / ANDROID_VR | VISIONOS |
|---|---|---|
| 0 s | media, `STREAM_PROTECTION_STATUS = ATTESTATION_PENDING` | media, status **OK** |
| 30 s | media, ATTESTATION_PENDING | media, OK |
| 60 s and beyond | **no media**, `ATTESTATION_REQUIRED max_retries=10` | media, OK |

The wall sits between **56.2 s and 60.0 s**, identically on all three videos, and
it is **positional, not a session quota**: a FRESH session asking for 60 s is
refused, while one session asking for 0 s six times in a row is served every
time. `ATTESTATION_REQUIRED` is the PO-token demand - which this port cannot
mint. `SabrProtocol` already maps it to `stream_protection`; the phone reached
`response_reload_required` instead because the server answers a *post-seek,
concurrent, previously-aborted* request with part 46 rather than the bare
protection status. Same wall, different phrasing, and reading the reload token
would not have helped: there is nothing to echo back that substitutes for
attestation.

**What this means for the fallback.** The fallback only fires on a response with
no links, and the clients that answer that way (IOS, ANDROID) are exactly the
walled ones - VISIONOS and ANDROID_VR still hand out URLs, so they route to
DASH. So the fallback is structurally capped at the first minute of any video: it
can never carry a full playback without a PO token. That is the measured reason
both switches ship **off**, and it is a stronger reason than the one recorded
above.

**What this means for the experiment.** VISIONOS SABR is unwalled and works
end-to-end. Verified on the Pixel 9 with `player_client=VISIONOS` and
`sabr_vod=1`: `prepare type=sabr-vod` -> `first-frame +2369` -> still `PLAYING`
at **2:34** with 3:00 buffered and zero `stream_protection` /
`response_reload_required` / `SabrException` lines across three and a half
minutes. So "Prefer SABR even when links work" is a real, working path today;
the fallback is the broken one. Reopening the default should start there, not
with the reload part.

**Verdict: shipped off (2026-09-08).** The fallback was written default-on and
flipped to default-off the same day, on the evidence above. It has never carried
a video that would not otherwise play:

- In normal use it is never selected. Seven unpinned opens (arms A and E) went
  VISIONOS -> `dash-mpd` -> first frame; the ring's leading client still hands
  out real URLs, so a link-less response never arrives.
- The 403 that *does* occur in the wild (arm A, TV_DOWNGRADED) is already
  handled by the existing quarantine + ring walk. TV_DOWNGRADED has no SABR
  endpoint, so SABR cannot address it even in principle.
- Forced onto the path (arm F) it prepares and then fails on every video.
- It *cannot* work: the clients that answer without links are precisely the ones
  walled at ~60 s without a PO token. See "Closed" below - this is the decisive
  reason, found after the switch was already flipped.
- The only arm where SABR delivered frames (D) is the opt-in experiment
  displacing a route that already worked. That proves the decoder, not the
  feature - though the decoder turns out to be genuinely good: see the VISIONOS
  result below.

And it is not free: accepting a link-less answer makes the ring *stop* at that
client instead of walking on to one that might still have URLs, so on a video
where VISIONOS fails and IOS is reached, default-on spends
`ErrorFixerController`'s retry budget (4 attempts, measured) on SABR before
anything else is tried. Non-zero cost against zero measured benefit - so both
switches are opt-in until the reload gap below is closed. Note this also means
the `containsAdaptiveVideoInfo()` change in point 1 is not a standalone bug fix:
with the capability off, upstream's classification is correct and the ring walks
on as it always did. It repairs a hole the capability itself opened.

`MediaItemFormatInfoImpl.kt` carries the same `containsSabrFormats()` logic but
is dead code here (`InnertubeService` is only referenced from a commented-out
line), so it was deliberately not touched.
