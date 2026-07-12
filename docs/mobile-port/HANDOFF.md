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
