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
- **SABR**: not ported; dispatch prefers DASH whenever present.
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
