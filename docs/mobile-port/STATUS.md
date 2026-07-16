# NewTube — Status

**v1.3.0 (versionCode 10300), main = loop-round-3 merge (resume-gate 1500 +
pin-rescue + ABR verification + stuck-state fixes, 2026-07-13); submodules
unchanged since the 2026-07-12 push.** Phone-only: the TV flavors, vendored ExoPlayer fork, and
leanback modules were deleted ("slice 2"); the engine is androidx.media3
1.10.1 on embedded Cronet (H2/QUIC). Toolchain: AGP 8.9.3 / Gradle 8.11.1 /
Kotlin 2.1.20 / compileSdk 36 / targetSdk 35 / minSdk 24.

Build a phone APK:
```
ANDROID_HOME=<sdk> ./gradlew :smarttubetv:assembleStmobileDebug
# -> smarttubetv/build/outputs/apk/stmobile/debug/NewTube_<ver>_universal.apk
```

## Works (emulator-verified; see HANDOFF.md for evidence per claim)
Everything in the original feature set (grid Home + bottom nav, search with
suggestions/voice, channel pages, full watch page with comments/live chat/
SponsorBlock/DeArrow, background playback + notification, PiP, mini-player,
settings, device-code OAuth multi-account) PLUS, from the 2026-07-11/12 rounds:
- **Live playback + DVR on media3**: live routes to the DASH manifest URL
  through a ported `LiveDashManifestParser` (zero-base + growing DVR window);
  timebar/scrub/LIVE-chip/double-tap all verified against a real stream.
- **Seek/network hardening**: fail-fast 403/416 retries, 4 s media read
  timeouts, auto-reload capped at 3 (anti-abuse), persisted+seeded bandwidth
  estimate (no post-restart quality ladder walk), off-main DASH source builds.
- **Open-latency wins**: prefetch-at-tap + preload of the next video's info
  (80 s window) + pre-built next MediaSource → autoplay advance first-frame
  ~350 ms (vs ~2.6 s cold); replay-from-cache ~1.5 s.
- **Request hygiene**: phone /player failover ring 13→8 clients (TV clients
  gated out), single-pass failover, 30 s negative-result cache (gated video
  re-open = 0 network calls), 8 s bound on /player+/next, signed-out
  get_add_to_playlist gated off, log floods killed (JsonPath, OkHttp BODY,
  OkHttp profiler).
- **Video-buffer setting is live** (Low/Med/High/Highest → real LoadControl
  presets, RAM-capped; one-shot pref alignment protects existing installs);
  OOM recovery actually lowers the buffer now.
- Opaque system bars restored on Android 15+ (edge-to-edge opt-out; dies at
  targetSdk 36 — insets work required before bumping).
- **Pot-enforced networks fixed (first real-device round, Pixel 9 on Telefónica
  LTE/5G, 2026-07-12)**: carrier CGNAT IPs make googlevideo demand PO-token
  integrity — pot-less VOD streams died at exactly 60s of served media
  (Source error + reload cascade), live segments 403'd instantly. Fix: BotGuard
  warmup at app start + WEB_EMBED-first again for VOD (warm pot mints ~10ms, so
  the old ~2.7s penalty that justified ANDROID_VR-first is gone) + live walks
  on to a dash-manifest client with `/pot/` on the manifest URL. Verified: VOD
  250s+ soak clean, live DVR window (2h) renders with all segments 200, LTE
  mid-chunk SocketTimeout recovers via retry with no user-visible error.
  Full post-mortem: HANDOFF §8.
- **Second Pixel-9 round (2026-07-12 evening, HANDOFF §9)**: live DVR
  interactions on real 5G — 67-min scrub-back lands exactly (BUFFERING→READY
  3.3 s), LIVE-chip jump to edge (2.3 s), 18+ min soak with ~2 s manifest
  refreshes all 200 `pot=y`, zero errors. Background audio-only: proper
  media-playback FGS + notification, zero
  ForegroundServiceStartNotAllowedException, audio keeps advancing. PiP→search
  routing correct: pinned player task collapses back into the main task when a
  new video opens from search — single task, no duplicate player, no double
  audio; search-result tap→first-frame 1.9 s.
- **Original-audio default fixed (2026-07-12 late, main `cce4344` + MSC
  `b2d09bc6`)**: multi-language videos defaulted to an auto-dub (pt-br on a
  Spanish video, device-verified). Two causes: the generated MPD wrote the
  display string into `lang` and stamped Role=main on every set; and
  findTrack treated an itag hit as exact although audio itags repeat per
  language variant, so a persisted "en-us (original)" 251 pinned the first
  251 in the manifest = the dub. Now: MPD carries clean lang + label +
  Role (only original = main, dubs = dub), and audio id matches require
  language agreement with an original-preferring second tier + prefer-original
  fallback. Verified on the repro video (es-us original selected, dubs
  role=dub), single-language VOD, and live.
- **Background audio-only stops downloading video (2026-07-12 late night)**:
  true background audio (no PiP, no mini-player) now disables the VIDEO track
  type entirely — no download, no decode; re-enabled on foreground return /
  engine restart re-applies while backgrounded. Pixel-9 verified: audio-only
  chunk stream after screen-off, instant video return on wake. PiP and the
  Browse mini-player keep video. HANDOFF §10.
- **Offline recovery UX fixed (2026-07-12 late night)**: connectivity-class
  errors show a friendly title instead of the raw exception; when the reload
  cap trips offline, an edge-triggered connectivity listener fires exactly ONE
  automatic reload when the network validates again; play tap in the dead
  state is a manual retry (cap resets). Emulator-verified end to end, incl.
  the no-hot-loop guard when the cap trips while the network is up. HANDOFF §10.
- **Third Pixel-9 round — deferred loop items (2026-07-13, real 5G)**. New
  debug-only in-app harness (`DebugMediaShaper`, leaf DataSource wrapper) makes
  bandwidth experiments possible at last: `debug.arc.throttle_kbps` token-bucket
  shaping, `debug.arc.poison_itag` synthetic 403s, `debug.arc.rebuffer_gate_ms`
  gate override; all debug-gated, release path untouched.
  - **Post-rebuffer resume gate 2500→1500 ms: SHIPPED.** 5-pair interleaved
    starve/refill A/B (pinned 1080p vp9 248, 800→2400 kbps): 1500 won all 5
    pairs, median stall 3.21 s → 1.71 s (−1.09 s; matches the theoretical
    refill time of the removed 1000 ms of media, so it generalizes).
  - **Pinned-quality fallback rescue: VERIFIED.** Poisoned pinned itag →
    `rescue pin->auto` fires on the 403 source error → reload on Auto → media3
    track-exclusion skips the failing rendition → playing again in ~7 s (720p).
    One-shot per videoId held; persisted pin untouched (next launch re-pins).
  - **ABR down-switch under constrained bandwidth: VERIFIED** (long-blocked
    item). Under sustained 800 kbps against a 2.77 Mbps track the selector
    lands on 240p (id=242, then 243 as the estimate refines) and playback
    reaches READY ~12–15 s after collapse. Caveat: on this carrier the
    collapse path goes through a genuine googlevideo 403 first (mechanism
    CORRECTED next day — see the 403-mechanism bullet below; the original
    "CGNAT gives the new flow a different exit IP" explanation was refuted
    by measurement), so the observed recovery is 403 → auto-reload → ABR
    re-select → READY, twice reproduced.
    Up-switch after recovery not yet observed on-device (test window ended).
- **Stuck-state fixes (2026-07-13, Pixel-9 fault-injection verified)** — three
  bugs adjacent to the error-reload path:
  - Watch page blanked by error-reloads: reloads re-enter with a bare Video
    (no title/author) and the re-fetch can die on a bad network. Now:
    same-video rebinds never overwrite the title/channel name with empty, and
    bindWatchMetadata (re)populates the title from the metadata document.
  - Same-URL external intent silently swallowed: Android dedupes a launch
    whose intent filterEquals a recents task's ROOT intent into a bare
    task-to-front (no callback; dead task records survive force-stop, so the
    first URL a task ever opened kept matching forever). External filters
    (watch links/shares/vnd.youtube) moved off the main-task Splash onto a new
    ephemeral-task IntentRouterActivity (taskAffinity ":router",
    excludeFromRecents, noHistory) — same-URL re-opens now route (verified:
    tap→open→prepare ~300ms where before there was silence). Launcher opens
    still use the main-task Splash (same-task animation preserved).
  - Error-reload's play() killed by an external AUDIO_FOCUS_LOSS ~200ms after
    re-prepare → recovery completed but sat paused. Now: a focus loss within
    5s of prepare() retries play() ONCE (media3's focus request suppresses the
    retry if the thief still holds focus — no fight loop). Live thief not
    re-observed (1-in-8 reloads); "focus-grace" NetPath line will identify it.
- **Groundhog-loop fixes (2026-07-13 afternoon, Pixel-9 dogfood round)**. Live
  dogfooding caught the app in an INFINITE error-reload loop: a googlevideo
  edge served a deterministic 403 for one audio chunk's byte range (same media
  position, every fresh URL mint, ~20min episode, real 403s with req well
  inside clen) → each ~45s cycle replayed the same 41s of video forever.
  Five compounding defects fixed, each verified on-device (shaper poison +
  one organic 403 episode mid-verification):
  - `containsMedia()` was playback-state based, but a fatal error IDLEs the
    player BEFORE onPlayerError, so VideoStateController's error/seek/release
    position saves ALL silently no-oped → every reload resumed at a stale
    position (the 41s rewind). Now media-item based; reloads resume at the
    death position (verified to the decisecond: died 795.67 → resumed 795.67).
  - Same-position error cap: errors recurring at (±5s) the same media position
    count in a window that onPlay does NOT reset — post-reload READY comes
    from the disk cache and proves nothing (that false-healthy signal is what
    reset the plain consecutive cap every cycle). 4th same-position error →
    dead state (verified: surfaced in 7.7s where the old build looped 6min+).
  - Audio pin rescue (twin of round-3 video pin-rescue): a persisted audio
    language pin maps to ONE itag; when its URL persistently 403s every reload
    re-selected the dead rendition (manifest's other same-language codec never
    tried). Now: 403 SOURCE error with a pinned audio format → session-scoped
    fallback to the default preset via new PlayerData.setTempAudioFormat →
    selector freely picks the alternative codec (verified: pinned aac 140
    poisoned → reload picked opus 251 → played through).
  - Dead-state manual retry re-fetches: retryNow() now calls
    applyNoPlaybackFix() like the automatic 403 path — it used to reload into
    the still-actual format-info cache and replay exactly the URLs that just
    died (observed burning a full error cycle; also right for
    connectivity-restore, where a network reattach may sit behind a new
    public IP that no longer matches the URLs' ip= binding).
  - Ring memory (MediaServiceCore): error-reload /player walks probe the last
    WINNING client second instead of last — applyNoPlaybackFix starts the walk
    after the winner, and with 8/9 clients unplayable midday every reload
    burned 8 playable=n calls (~2.3s) re-finding TV at the ring's end.
  - Diagnostics: NetPath load[E] lines now append req=<pos>+<len> plus clen/
    lmt so a recurrence of the per-range 403 is diagnosable from logcat.
- **403 mechanism MEASURED (2026-07-13 pm) — the CGNAT exit-IP theory is
  REFUTED; do not re-propose transport fixes off it.** Measurements on the
  live network (Mac egresses through the Pixel 9's 5G): exit IP is STABLE
  across fresh connections — 30 samples / 5 min, same public IPv4
  (88.29.x.x) and IPv6 every time (phone is dual-stack: CGNAT'd v4
  internally, global v6). And in the same session's logs: 24 of 34 real
  googlevideo 403s hit REUSED (warm) connections; all 403s carried pot=y;
  206s and 403s interleave within the same second on the same host and
  connection, split purely by which format URL / byte range was asked for.
  Conclusion: the episodes are server-side per-format/per-range URL
  rejections (some googlevideo serving/anti-abuse heuristic), transient
  (healed ~20 min later), not client transport, flow churn, IP binding, or
  pot. Consequently the Cronet QUIC idle-timeout/keep-alive idea was
  evaluated and DROPPED — it would not have prevented any observed 403.
  The mitigation is the recovery stack above (format/client failover with
  exact-position resume, ~8 s per episode). Caveat: cross-network moves
  (WiFi↔5G) and PDN reattach were NOT tested and may still invalidate ip=
  bindings — that class is already handled by the invalidate-on-403 path.
  MECHANISM REFINED 2026-07-13 (later) — see the attested-web-first bullet:
  the "per-format/per-range rejection" is integrity/attestation enforcement of
  NON-ATTESTED client mints. The pm refutation (IP/transport/flow-churn ruled
  out) stands; "not pot" was too strong — the pot is present but NON-VALIDATING
  on non-web clients, which is the actual trigger.
- **403 root cause CONFIRMED + attested-web-first fallback (2026-07-13 pm,
  Pixel-9 + off-device replay).** Deep-dive on "why 403s at all, reliably
  avoid them". Off-device replay through the phone's own 5G egress: a pot-LESS
  ANDROID_VR URL (yt-dlp mint) is bulletproof — 500 range sweeps / 76 MB, 300
  identical-range replays, deep ranges to 99 % of a 1 GB file: ZERO 403s. So
  byte-volume, replay, deep-range, and exit-IP are all excluded as triggers
  (reconfirms the pm bullet). The differentiator is the mint: the APP attaches
  a NON-VALIDATING app-visitor pot (`pot=y`) to non-web client URLs, and on
  carrier/CGNAT (pot-enforcing) networks googlevideo integrity-enforces those
  — a non-attested client (ANDROID_VR/ANDROID_REEL/TV/IOS) serves ~60 s then
  403s forever; ONLY an attested WEB-family flow (WEB_EMBED/WEB/WEB_SAFARI/GEO/
  MWEB, BotGuard-attested `/player` body) mints URLs that survive. This is
  exactly the 2026-07-12 on-device finding recorded in `MobileMainApplication`
  (WEB_EMBED-first) — now confirmed by a MediaServiceCore ring/pot audit and
  the replay tests. Residual 403 storms = videos WEB_EMBED returns playable=n
  for (embed-disabled/geo/age): the ring then falls through the LIST order
  (VR → REEL → TV → …) and wins on a 403-prone non-attested client BEFORE the
  other attested web clients (WEB/WEB_SAFARI/GEO/MWEB sit later in the list).
  Fix: `VideoInfoService.setPreferAttestedWebFallback(true)` (mobile gate,
  VIDEO_INFO_TYPE_LIST untouched) stable-partitions the fallback tail so all
  attested clients are probed before any non-attested one — an embed-disabled
  video that plain WEB can serve now gets a SURVIVING attested URL on the first
  open instead of starting a storm on TV. Non-attested clients stay as the
  final fallback for auth-walled (TV) / SABR-only (VR) videos. Verified
  on-device (debug.arc.fail_clients force-fail hook, since removed): walk order
  is WEB_EMBED→WEB→WEB_SAFARI→GEO→MWEB→ANDROID_VR→…, and ring-memory
  (last-winner probed second) still bounds the reload walk at 2. Happy path
  unchanged (WEB_EMBED wins at attempt 1, 10/10 test opens clean). Caveat:
  helps only the subset of WEB_EMBED-fails a sibling WEB client can also serve;
  videos only TV/VR can serve still take a non-attested win (unchanged). Also
  shipped: `load[E-http]`/`load[E-url]` forensics (the 281-byte 403 body + full
  failing URL) so the next organic episode is curl-replayable.

## Works (added 2026-07-16 — network round: Tiny-Desk timings + 69-agent code audit)
All device-verified on the Pixel 9 (WiFi, signed in, wireless adb
`adb-4A120DLAQ0049N-JPKPvP._adb-tls-connect._tcp`):
- **Signed-in ring memory**: authenticated TV /player is currently SABR-only
  (playable=n, usableAdaptive=0) — measured 4/4 opens; the ring now learns it
  per-process (`player-ring learn tv-sabr-only=y`) and starts later signed-in
  opens at TV_DOWNGRADED (TV keeps one re-probe per process — normally the
  app-start session warmup — and a playable TV response clears the flag).
  Warm open tap→first-frame **1.70s → 1.08s**, one /player per open not two.
- **Cold-open V8 stall gone**: PlayerDataExtractor's restored-cache path now
  warms V8 on a background thread instead of inside the first /player's
  request path (it ran under AppServiceIntCached's player lock). Measured
  player-context→player-http gap 2.3s → 58ms; cold intent-open first-frame
  **4.63s → 2.78s**. The once-per-JS-rotation dummy-solve validation stays
  synchronous on purpose — firstValidExtractor's validate() contract needs it.
- **Storyboard enrichment gated off** (`setSkipStoryboardEnrichment`, mobile
  gate): the touch UI never renders seek previews (loadStoryboard is a stub),
  yet a broken storyboard on the winning client fired a deferred IOS /player
  per non-live open. Re-enable when seek-preview UI ships.
- **Live-chat poll lifecycle**: get_live_chat (~5s cadence) used to run until
  video change/destroy — with the sheet closed, in background audio, and in
  PiP (~700 req/h invisible). Now stops on sheet dismiss / background audio /
  PiP enter, revives on foreground return / PiP exit while the sheet is open.
- **Updater fixed**: pending-update APK re-downloaded IN FULL on every cold
  start >15min (freshness heuristic) — now a getPackageArchiveInfo integrity+
  version check, at most one download per advertised version. Manifest check
  60s → 12h (one-shot migration of the persisted legacy 60s pref); definitive
  answers (404 = nothing published, today's reality) stamp the throttle clock,
  connectivity failures don't. Verified: relaunch fires zero manifest GETs.
- **Dead-host placeholder removed**: Video.getBackgroundUrl no longer returns
  a via.placeholder.com URL (dead host — one failed TLS per watch open);
  callers render solid black on null.
- **ABR up-switch after collapse VERIFIED** (closes the round-3 open item):
  700 kbps shaper on a fresh open → clean 1080p→480p(+4.6s)→240p(+20s)
  down-switch, zero errors (no 403 involvement on WiFi); lift → chunk loads
  back at 1080p in ~2s, selector event +31s (buffered low-res plays out —
  media3's data-frugal default). Minor 480p↔240p flapping only when available
  bandwidth sits exactly at a rendition's bitrate; not worth tuning.
- Tiny Desk concerts (the "hard" test set) play clean on WiFi — zero 403s,
  zero reloads across Mumford/RAYE/Sting/Parcels; their historical difficulty
  is carrier-attestation dynamics (HANDOFF §8), not content.

## Open — network audit backlog (2026-07-16, verified findings not yet built)
From the 69-agent audit (21 confirmed after 2-lens adversarial verify; the
items above are done). Ordered roughly by value:
- Browse section switches refetch /browse every time (BrowsePresenter:494,
  no TTL cache; needs account-change invalidation + History/Subs freshness).
- SessionWarmup fires a throwaway Big Buck Bunny /player + googlevideo
  preconnect every launch (SessionWarmup:64; preconnect-skip is the safe
  half — full skip needs an nsig-extractor freshness probe).
- FailFastLoadErrorPolicy: treat Cronet net::ERR_NAME_NOT_RESOLVED /
  ERR_INTERNET_DISCONNECTED as fatal (currently 6 futile retries ~5s).
- Fixed 1000ms reload delay on the 403-remint path (VideoLoaderController:461;
  shorten via a call-site overload, NOT the shared reloadVideo default).
- ABR seed persists across network types (Media3SourceFactory:151; use the
  per-networkType setInitialBitrateEstimate overload).
- No metered cap on buffer-ahead (75s of 1080p prefetch on abandoned videos).
- Brotli for InnerTube JSON (decode path already wired; CAUTION: MSC fork
  history flip-flopped `br` 4×, last REVERTED — gate phone-only, verify on
  cellular).
- 10s connect timeout for non-open-path API calls (currently 20s).
- CronetManager.getEngine catches only UnsatisfiedLinkError → broaden to
  Throwable, keep null-fallback.
- Account avatars fetched with ALL caching disabled (GlideIconFetcher:45).
- UnlocalizedTitleProcessor unbounded flatMap (add maxConcurrency).
- CLOSE/PAUSE queue auto-advance misses the next-video prefetch (NOT
  REVERSE_LIST — it advances backwards, would warm the wrong video).
- DeArrow: per-card uncached GETs (batch = k-anonymity hashPrefix endpoint —
  bucket API, needs client-side filtering).
Rejected by verification (do NOT re-propose without new evidence): IPv4-first
DNS change, Glide→OkHttp loader swap, related-thumb downsizing, live manifest
cadence backoff (refresh is emsg-driven), proactive WiFi↔cell URL invalidate,
gating the /player fingerprint logging.

## Open — needs a real device (Pixel 9)
Round 2 (2026-07-12 evening) closed most of this list (see Works): live DVR
scrub-back + LIVE-chip + soak, background-audio FGS, PiP→search.
Round 3 (2026-07-13) closed the rest via the in-app debug shaper (see Works):
ABR down-switch, resume gate A/B, pin-rescue. The 2026-07-16 round closed the
ABR up-switch item (see Works). Radio-based constraining stays
OFF THE TABLE (HANDOFF §9 GSM-flip incident) — the shaper replaces it. Still
open:
- WEB_EMBED /player RTT varies 0.3–2.1s on LTE (cold TTFF 3.8s worst case vs
  ANDROID_VR's 3.4s — acceptable since ANDROID_VR streams die at 60s on
  enforcing networks, but worth optimizing; TV+serviceIntegrityDimensions is
  the candidate). Note 2026-07-16: signed-in flows now ride TV_DOWNGRADED
  (ring memory) — re-measure on carrier before optimizing.
- Signed-in TV_DOWNGRADED streams on a pot-ENFORCING carrier network: the
  2026-07-16 round was WiFi-only; confirm authenticated non-attested URLs
  don't hit the 60s cliff on Telefónica 5G (dogfooding hasn't shown it, but
  it was never explicitly soaked).

## Open — product/UX
- Playlist queue UI in player ("Playing from: X · i/N", collapsible) — top item.
- "Not interested"/"Don't recommend channel" feedback tokens (server moved
  them; MediaServiceCore dig needed).
- Channel rows in search suggestions; channel page header/sort polish.
- Age-gated videos: silent ~6 s stall then auto-skip — needs an error dialog.
- In-player "Video buffer" row (knob currently applies at next player open).
- UI sweep leftovers: CC dialog still TV-style, speed/CC pickers inconsistent
  with native quality sheet, PiP enter-animation flash.
- Carry-overs: occasional first-frame black in mini player. Two minor gaps
  left by the audio-only fix (HANDOFF §10): mini-player-then-home keeps video
  enabled (needs a Browse-host hook), and screen-on-at-keyguard streams video
  behind the lockscreen.

## Open — tech debt
- **16 KB page-size compliance**: Android 17 flags the debug build's native
  libs as unaligned (`libcronet.105.0.5195.68.so` LOAD segment, plus libj2v8,
  libconscrypt_jni, libglide-webp). Runs fine on 4 KB-page devices today, but
  16 KB-only devices are coming; needs updated .so dependencies or repack.
- Proper edge-to-edge insets before targetSdk 36.
- Unstripped native libs (needs NDK 21); `newtube.json` update manifest for
  the in-app updater.
- Parked (documented in HANDOFF): googlevideo range-query leaf wrapper, SABR,
  media3 DefaultPreloadManager.

## Pending decision
`NewTube_1.3.0_universal.apk` is built and unpublished — GitHub Releases +
download page (see `docs/gtm/`) are the ready channels; publishing is Aleix's
call.
