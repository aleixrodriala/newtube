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

## Works (added 2026-07-28 — VISIONOS, a token-free /player client)
Came out of an explicit hunt for routes we had not tried (different keys, older
deprecated systems). One real find, plus several dead ends now closed with
evidence so nobody re-opens them.

- **`VISIONOS` (clientName `VISIONOS`, cver `1.02`, InnerTube id `101`) is the
  only client left that requires neither a PO token nor a JS player.** In
  yt-dlp's current `_INNERTUBE_CLIENTS` its entry declares no `GVS_PO_TOKEN_POLICY`,
  no `PLAYER_PO_TOKEN_POLICY` and `REQUIRE_JS_PLAYER: False` — every other client
  in that table declares at least one. It now leads their defaults:
  `_DEFAULT_CLIENTS = ('visionos', 'android_vr', 'web')`.
- **Verified live, not taken from the docs.** Straight `/player` from a Mac on
  2026-07-28: `VISIONOS status=OK adaptive=32 cipher=0 pot_in_url=0` while
  `ANDROID_VR` and `TV_DOWNGRADED` both answered `LOGIN_REQUIRED "Sign in to
  confirm you're not a bot"` **in the same second, same IP**. Byte ranges off
  the returned URLs served `HTTP 206` at init, mid (~14 MB in) and ~95 %.
- **Request shape doesn't matter.** Our template always emits `clientScreen`
  and our UA sniffer would add `browserName`/`browserVersion` (the visionOS UA
  is a Safari string). Probed all three shapes — yt-dlp-exact, `+clientScreen`,
  `+clientScreen +browser` — all `status=OK`, all `media_http=206`. So no
  special-casing was needed in `AppClient`.
- **On-device A/B (Pixel 9, counterbalanced ABBA, one apk, forced client via
  `setprop debug.arc.player_client`).** VISIONOS won attempt 1 on every open:
  `status=OK playable=y`, **more** usable adaptive formats than VR (32 vs 28 on
  `aqz-KE-bpKQ`; 23 vs 22 on `Oa_Wpi-KWrg`), first frame 1.92–2.02 s vs VR's
  1.86 s warm. Both heads are healthy on this network, so this run proves
  parity + format count, NOT the bot-check advantage — that was only observable
  from the challenged Mac IP.
- **No 60 s cliff.** Forced-VISIONOS playback ran **~2 min continuous on
  cellular** (`net=cell:197`), ABR climbing to itag 303 (1080p60 VP9), last
  chunk at `pos=116633` / byte 32.4 MB of 168 MB — **zero 403s**. That is the
  exact deep-range case that kills the full-fat `TV` client.
- Wiring: `CLIENTS.VISIONOS` + `CLIENT_NAME_IDS["VISIONOS"]="101"`, an
  `AppClient` entry, and `PREFERRED_FIRST_CLIENT = AppClient.VISIONOS`.
  `VIDEO_INFO_TYPE_LIST` is untouched (upstream churns it).

**Two hazards this surfaced, both fixed:**
- `AppClient.VISIONOS` is appended at the **END** of the enum on purpose. The
  winning client is persisted **by ordinal** (`getData().setVideoInfoType`), so a
  mid-enum insert would silently re-point every value saved by an older build.
- The fast head is now **off-ring**, and `Helpers.getNextValue` answers with
  element 0 for a value it can't find — so `buildVisitOrder`'s lap never met its
  `type != beginType` stop condition and **span forever**. Fixed by anchoring the
  lap at element 0 and visiting that anchor explicitly. Covered by
  `offRingFastHeadWalksTheWholeRingExactlyOnce` (which hangs rather than fails if
  it regresses — that is the signal). Head preservation was also re-keyed from
  the `PREFERRED_FIRST_CLIENT` constant to `beginType`, otherwise a restored
  previous-session winner would have been demoted behind the Web family, turning
  the first cold start after upgrade into a pot-minting WEB open.

**Known limitation — this is inert while signed in.** A signed-in open begins at
`AUTHENTICATED_HEAD` (`TV_DOWNGRADED`), never at `PREFERRED_FIRST_CLIENT`;
verified on device with the forced client cleared (`player-ring
authenticated-first=TV_DOWNGRADED`, VISIONOS absent). Since VISIONOS is off-ring
it does not appear in an authenticated walk at all. So today it helps
**signed-out** opens, and authenticated recovery only when the cursor is null.
Making it count for signed-in users means leading the *anonymous partition*
(authenticated recovery / auth-head-exhausted) with it instead of `WEB_EMBED` —
strictly cheaper, since it mints no token. Not done; see Open.

**Dead ends, closed with evidence:**
- `get_video_info` — **HTTP 410 Gone**, both `el=detailpage` and the `eurl`
  embed variant. Gone, not merely unreliable.
- **InnerTube API keys are not a lever.** The long-published `AIzaSy…qcW8` key
  and a fabricated garbage key produced byte-identical responses; the key is no
  longer validated. Bot-checking is what gates you.
- **VISIONOS does not rescue the feed.** `/next` and `/search` answered fine;
  `/browse` returned **HTTP 400**. Same SAPISID wall as the rejected home-feed
  work — unchanged.
- yt-dlp deleted `tv_embedded` and `ios_downgraded` as broken in Jan 2026. We
  still carry `TV_EMBED` in `TV_FALLBACK_CLIENTS`; harmless, the phone gate
  skips that tail.

## Works (added 2026-07-27 — playlist queue card in the watch page)
- **"Playing from X" card with an `i / N` position and a collapsible list**,
  above Up next. Collapsed by default; the header toggles it and rotates the
  chevron; the list is a `MaxHeightRecyclerView` capped at 50% of screen height
  so a 200-item playlist can't swallow the page.
- **Which row is the queue is decided by content, not by ordering**
  (`findQueueGroupId`): the row that CONTAINS the playing video is the queue - a
  playlist row always does, an algorithmic related row never does. That leans on
  no row order, no group title we don't own, and no playlist-id plumbing (which
  differs between a section playlist, a /next playlist and the local queue).
- **`PlaylistInfo` is trusted only when the queue is NOT the section group.** A
  feed-opened video has both a section group AND a Mix `PlaylistInfo`; naming the
  feed rows after the Mix is how this first read as a bug ("Playing from <mix>"
  over unrelated feed videos).
- **Position is matched by videoId, never `List.indexOf`.** `Video.equals` is a
  composite hash (playlistId, sectionId, channelGroupId, mediaItem, ...), so the
  playing Video and its own queue row - which arrived in a different group -
  almost never compare equal. `indexOf` returned -1, so the scroll-to-current on
  expand silently no-opped on exactly the long playlists it exists for. Both the
  scroll and the subtitle fallback now go through `indexOfQueueVideo(videoId)`.
- Pixel 9 verified end to end on a signed-in feed open: card reads
  `Playing from Recomendados` / `1 / 5`; collapsed by default (list absent from
  the hierarchy); header tap expands to a 734 px list; exactly one `Now playing`
  badge, on the playing row; tapping queue row 2 advances playback, moves the
  badge to row 2, reverts row 1 to its duration and updates the subtitle to
  `2 / 5`; header tap collapses again. No crashes.

## Works (added 2026-07-27 — carrier soak of the signed-in TV route)
Ran on the Pixel 9 over **roaming LTE (AndorraTelecom, `drei.at`, metered,
`net=vpn:183` split-tunnel Tailscale — default route is cellular)**, release
build, signed in. This closes the "never explicitly soaked" item above.
- **No 60s pot cliff on signed-in TV_DOWNGRADED.** A TV_DOWNGRADED `auth=y`
  stream (`2Szdo6fRc5c`, first frame +5041 ms) played **~3.5 min continuous**
  with zero 403s, zero reloads, zero recovery events — sampled every ~16 s off
  the player's own clock. Two further TV_DOWNGRADED opens (18:37, 18:48) also
  returned `status=OK playable=y auth=y`. The premise behind the open item —
  authenticated non-attested URLs dying at 60 s on an enforcing carrier — did
  NOT reproduce.
- **Found instead: the full-fat `TV` client 403s at position 0** on this
  network. Chain was `TV_DOWNGRADED attempt=1 parsed=null` → fall to `TV` →
  `playable=y auth=y` → the googlevideo URLs reject immediately:
  `error +11920 InvalidResponseCodeException(http=403) pos=0`. So it is not a
  60 s cliff, it is an instant reject of that client's media URLs.
- **The ring recovers correctly, and that path is now proven on carrier.**
  `quarantine-auth-route client=TV cooldownMs=600000` → `authenticated-recovery
  first=WEB_EMBED` → `recovery-action remint-reload` → WEB_EMBED (`pot=y
  auth=n`) → `first-frame +11943`. Cost is ~12 s to first frame on that one
  open; every later open in the 10-min cooldown goes straight to WEB_EMBED, and
  after it expires TV_DOWNGRADED is used again and works.
- **`parsed=null` root-caused and FIXED (same night).** It was never a parse or
  response-shape failure: `player-context 18:29:33.273` → `parsed=null
  18:29:40.275` is **exactly 7.000 s**, and
  `VideoInfoService.CLIENT_ATTEMPT_TIMEOUT_MS = 7_000`. The head request simply
  overran the per-attempt budget and was cancelled (`getVideoInfoWithTimeout`
  returns null on deadline).
  That budget was written for a *speculative* client — `PREFERRED_FIRST_CLIENT
  = ANDROID_VR`, "often hangs?" — where failing over early costs a second and
  gains a second. But for a signed-in open `beginType = authBegin`, so the head
  is `TV_DOWNGRADED`, and the timeout was never revisited when
  `AUTHENTICATED_HEAD` was introduced in the antibot round. It applied to every
  non-web-pot client, head included.
  The cost is asymmetric: one slow COLD request (DNS + TLS, no warm connection,
  roaming link) → fall to `TV` → media 403 at `pos=0` → ~12 s to first frame on
  that open (the recovery reload is served anonymously by WEB_EMBED) and a
  10-minute quarantine of `TV`.
  **CORRECTION (an earlier draft of this section, the memory note and the commit
  messages for `cbcbd4e6`/`02c001a` all overstated this as a 10-minute
  quarantine of the ENTIRE authenticated route, with every open in the window
  served anonymously — that is wrong).** The quarantine is PER CLIENT
  (`mAuthRouteForbiddenUntilMs.put(failedClient, ...)`), and the constant's own
  javadoc says only when EVERY client in `AUTHENTICATED_HEAD` is quarantined
  does the walk give up on the account. The logs confirm it: the very next opens
  (18:29:53, 18:37, 18:48) all read `authenticated-first=TV_DOWNGRADED
  demoted=[TV]` and returned `auth=y`. Exactly ONE open was served anonymously —
  the immediate recovery reload — not ten minutes' worth.
  Fix: `AUTH_HEAD_ATTEMPT_TIMEOUT_MS = 15_000` applied via
  `attemptTimeoutMsFor(client)` — the head gets a cold-start budget, every other
  fast client keeps the short speculative one, and 15 s still fails over before
  OkHttp's own 20 s read/connect timeout. Regression test
  `VideoInfoVisitOrderTest.authenticatedHeadGetsAColdStartBudget`.
  Observed head latencies for calibration: 0.9 / 2.4 / 2.6 s warm, >7 s cold.
  **Honest limits of the verification.** 5 cold opens after the fix: 5/5
  `TV_DOWNGRADED attempt=1 status=OK auth=y`, no `parsed=null`, no timeout, no
  quarantine, no 403; head latency 3.40 s on the genuinely cold first open then
  0.64–0.78 s. So: no regression, and the head is winning. But **nothing in that
  run exceeded 7 s, so the extra headroom was never exercised** — the fix is a
  targeted hypothesis, not an observed save. And the original request was
  cancelled AT 7.000 s, so its true latency is unknown; 15 s may or may not have
  covered it. What the change rests on is the cost asymmetry, not a measured
  duration.
  **Tradeoff accepted:** if the head ever hangs for real, the user now waits up
  to 15 s instead of 7 s before failover. Judged worth it because the 7 s
  failover was not cheap either (it landed on `TV` → 403 → ~12 s + quarantine),
  and 15 s still beats OkHttp's 20 s.
- Still latent, NOT fixed by the above: **the full-fat `TV` client's media URLs
  really do 403 at `pos=0` on this network.** The timeout fix removes the usual
  way we *reach* `TV`; it does not make `TV` work. If the head fails for a real
  reason, the same 403 → quarantine → WEB_EMBED cascade still runs (correctly).
- Method note: **`dumpsys media_session` is useless for progress here** — the
  session posts no periodic updates, so `position`/`updated` stay frozen
  between transitions and a healthy stream looks identical to a hung one. Read
  `mobile_player_position` off the player instead (center tap reveals controls;
  the tap does not toggle play/pause). Also: a slow horizontal drag near the
  seekbar can land on the watch content and OPEN A DIFFERENT VIDEO rather than
  seek — check the video id in NetPath before reading a "seek" result.

## Works (added 2026-07-27 — watch page on the FIRST open of a session)
- **Eager watch-page fetch now covers the cold open** (`setEagerColdOpenEnabled`,
  SuggestionsController). The eager /next introduced earlier only ran when
  `mMediaItemService != null`, i.e. only once `onInit()` had run — which
  excluded exactly the open that needs it most: the FIRST player open of a
  session (deep link, notification, or just the first feed tap), where
  `openVideo()` calls `onNewVideo` and only THEN starts the playback
  Activity. The fetch there waited for `onVideoLoaded`. Measured on a Pixel 9
  over roaming LTE, 6 counterbalanced pairs, one apk (`debug.arc.eager_cold`):
  fetch start **+40ms vs +2973ms**, watch page ready **paired median −2625ms,
  A faster in 6/6 pairs**; first frame did not regress (paired −831ms, 4/5).
- **Park/replay** is what makes it safe: the metadata can land before there is
  anything to paint into, and every delivery point (`syncCurrentVideo`,
  `appendSuggestions`, `onWatchMetadata`) silently no-ops on a missing player,
  so the document is parked and replayed from `onInit()` — the Activity
  inflates its whole watch UI *before* `setView`/`onViewInitialized`, so that
  is a legal moment to paint. Verified with a temporary 6s delay on the player
  Activity launch: `suggest parked +2793` → `suggest replay +6087` (at onInit,
  54ms before the `open` milestone) → title + related cards painted.
- The liveness test is `isPlayerAlive()` (new, BasePlayerController), NOT
  `getPlayer() != null`: `PlaybackPresenter.getPlayer()` deliberately keeps
  returning a view whose Activity is finishing/destroyed, and painting into
  one is the same silent drop. This also covers "player Activity was backed
  out of, process still warm, open another video" — verified `view=n` there.
- Failure path verified for free (the test link's VPN DNS was dropping the
  first request per open): eager /next fails → `mEagerDelivered` stays false →
  `onVideoLoaded` refetches the classic way → page still paints.
- NetPath gained `suggest fetch/parked/replay/ready` (mobile gate only).

## Works (added 2026-07-18 — feed-load round, tier 2)
All six approved tier-2 items shipped (Pixel 9 WiFi-verified same day, cold
start × 2 + subs + TTL switches + pull-to-refresh + playback soak):
- **Disk-backed FeedCache snapshot**: sections persist to
  `files/feed_snapshots/<sectionId>.snap` on Browse onStop (top 40 videos,
  `Helpers.mergeList` of `Video.toString`, atomic tmp+rename) and restore on
  the process's first in-memory miss — verified cold start paints 40 Home
  cards at ~0.6s, BEFORE the first /browse even leaves ("Restored 40 videos
  from disk"). Display-only until the refetch replaces it (a deserialized
  Video has no live VideoGroup → no page key; the refetch ALWAYS follows
  because the browse TTL map is in-memory and empty on a fresh process).
  Account switch wipes memory + disk via the existing FeedCache.clear()
  listener — whatever is on disk always belongs to the selected account.
- **SessionWarmup deferred to first feed paint**: the throwaway BBB format
  fetch (JS parse + /player) used to fire at +1.2s and race the launch
  /browse chain; now MobileBrowseActivity kicks it after the first FRESH
  content paint (verified ordering in logcat), with a 15s launch fallback
  (offline / deep-link paths) and `init()` restoring the persisted warm flag
  early (the first-run player hint reads it before any feed paints).
- **Brotli for InnerTube JSON (phone-gated)**: `DefaultHeaders.brotliEnabled`
  + request-time Accept-Encoding resolution in RetrofitOkHttpHelper; decode
  side (UnzippingInterceptor) was wired all along. Verified "brotli active:
  first br response /youtubei/v1/visitor_id" + feeds/watch-page/playback all
  parse fine. Upstream's four `br` reverts were TV-box RAM + ByeByeDPI
  concerns — neither applies to phones; TV keeps gzip-only (gate off).
- **www.youtube.com preconnect at app start**: background HEAD to
  /generate_204 through OkHttpManager (whose pool the InnerTube client
  SHARES via newBuilder) — verified 204 in ~190ms before the first API call.
  OkHttpManager.instance()/getClient() made synchronized in SharedModules
  (the preconnect thread racing the first API call could otherwise build two
  clients with separate pools, silently voiding the warmup).
- **Boot double-load guard**: onViewInitialized used to select the boot
  section twice (refreshSections tail + its own tail) → dispose+resubscribe
  of the same in-flight observable. Phone gate in BrowsePresenter
  .onSectionFocused skips a same-section refocus while its load is running —
  verified "Section Inicio load already in flight — skipping refocus
  reload" on both cold starts. onAccountChanged disposes in-flight loads
  first (gate-tied) so the guard can never pin a stale account's fetch.
- **Row-pad continuations gated off** (`setRowPadContinuationsDisabled`):
  the MIN_ROW_GROUP_SIZE=5 eager fills exist for TV shelf rows; the phone
  flattens rows into one grid. NOTE the audit mis-attributed Home's
  continuation storm to this — the real driver is YouTubeContentService
  .emitGroupsPartial's while-loop draining EVERY home section-list
  continuation (~6 × ~35KB, growing ctoken bodies). That drain was
  DELIBERATELY KEPT: it runs after first paint (page 1 emits before
  continuation 2 fires, verified), and it is what fills the phone grid's
  whole scroll depth, which the 5-min TTL then serves for free. Capping it
  would shorten Home's scroll depth for a post-paint-only saving. A proper
  fix would be a lazy scroll-driven section-list continuation (new plumbing:
  BrowsePresenter has no notion of a section-list key) — future item.
- Net cold-start on WiFi: launch → painted cards ~0.6s (disk snapshot) with
  the fresh replace landing ~1.4s later; zero auth requests (tier-1 token
  restore, verified again at age 41 min); subs still 1 request; TTL switches
  still zero; pull-to-refresh bypass intact; 95s playback soak clean.

## Works (added 2026-07-18 — feed-load round, tier 1)
Root cause was measured 2026-07-16 on LTE ROAMING (~800ms RTT amplifies every
serial round trip): cold→Home first cards 5.2s = token refresh 1.9s →
accounts_list 0.8s → /browse 2.1s, ALL serial; Subs first visit 4.6s of blank
skeleton = 5 serial /browse (continueIfNeededTV pre-combining >60 items for
the LIVE-first sort before ANY emission); every Home re-focus refetched
~350KB with no TTL. 55-agent audit: 16 confirmed / 0 rejected findings.
Fixes (Pixel 9 WiFi-verified 2026-07-18):
- **Subs pre-combine gated off on phone** (`BrowseServiceGates
  .setSkipContinuationPreCombine`, set in MobileMainApplication):
  continueIfNeededTV returns page 1 as overrideItems/overrideKey → ONE
  /browse then paint (395ms on WiFi; was 5 serial requests). Page 1 keeps its
  live-first stable sort (MediaGroupImpl sorts the override window); deeper
  pages arrive via normal scroll pagination. TV default unchanged.
- **Access token persisted across process starts** (YouTubeSignInService
  AuthTokenCache pref: header + mint time + owning refresh token): cold start
  within the 60-min token lifetime restores the header from disk — zero
  /o/oauth2/token calls, verified "Restored persisted authorization header".
  Invalidated on account change/sign-out (invalidateCache clears disk too);
  revoked-early tokens are handled by a transport-level one-shot 401
  retry (RetrofitOkHttpHelper.retryOnceIfAuthRejected → refresh → replay).
- **accounts_list off the auth lock**: syncStorage (avatar/name/email sync,
  drawer cosmetics) used to run INSIDE synchronized updateAuthHeaders — the
  first feed's checkAuth blocked on its round trip. Now a named background
  thread ("AccountStorageSync"), once-per-process semantics kept
  (synchronized syncStorage). First browse no longer waits on it.
- **Per-section browse TTL (5 min)** in BrowsePresenter: a re-focused section
  fetched successfully within the TTL skips the refetch entirely — verified
  ZERO requests on Home↔Subs switches ("Section X is fresh — skipping
  refetch"), where each Home re-focus used to cost 1 browse + ~6 serial
  continuations (~350KB). Pull-to-refresh / refresh() force-bypass; History
  is exempt (just-watched must appear); invalidated on account change and on
  channel-sorting/playlists-style changes (backing observable swapped). New
  BrowseView.onSectionContentCurrent default method tells the phone view its
  painted snapshot is current (clears mAwaitingFreshContent so a later
  scroll-end APPEND extends instead of swap-replacing).
- **FeedCache now pins its snapshot's VideoGroups** (strong refs, replaced
  per put / dropped on clear): Video.group is upstream's WeakReference
  memory-leak fix, so after any GC a repainted snapshot answered
  getGroup()==null and scroll-end pagination died silently ("Can't continue
  group") — previously masked because every focus refetched. Verified:
  TTL-skipped subs grid paginates (2 continuation pages appended, list
  extended not replaced). Walk this timeline again if snapshot scope changes
  (CLAUDE.md single-slot-cache rule).
- Net effect measured on WiFi: cold start #2 launch→rows ~2.0s with zero
  auth requests; subs tap→cards 1 request; tab switches free. On the roaming
  profile this removes ~2.7s of the 5.2s cold chain and ~3.7s of the 4.6s
  subs wait per the 2026-07-16 request-level measurements.
Tier 2 shipped 2026-07-18 — see the tier-2 section above. Rejected by
verify (do not re-propose): switching feeds TV→WEB client (upstream: WEB
home breaks signed-in parity).

## Open — network audit backlog (2026-07-16, verified findings not yet built)
From the 69-agent audit (21 confirmed after 2-lens adversarial verify; the
items above are done). Ordered roughly by value.

**From the 2026-07-28 client hunt** (see the VISIONOS section above):
- **Lead the anonymous partition with VISIONOS instead of `WEB_EMBED`.** This is
  what makes the VISIONOS work pay off for a signed-in user: authenticated
  recovery and the auth-head-exhausted path both currently begin at `WEB_EMBED`,
  which mints a PO token first. VISIONOS mints nothing and was unchallenged on an
  IP that challenged everything else. Costs one extra round trip (~0.5 s) when it
  can't serve (made-for-kids), and it already falls through to `WEB_EMBED`.
  Touches load-bearing recovery ordering — the 2026-07-27 quarantine work — so
  soak it properly.
- **Player-token exemption for the android/iOS family.** `android`, `android_vr`
  and `ios` all carry `not_required_with_player_token=True`: send a PO token in
  the **/player request** and the returned stream URLs no longer need a GVS one.
  We already have the machinery (`PoTokenGate`, CONTENT/SESSION tokens, cloud +
  local factories) and only spend it on `WEB_EMBED`. This is upstream's sanctioned
  answer to their new note on `android_vr`: *"Since 2026.07, intermittent/selective
  POT enforcement has been observed for non-HLS formats."*
- **HLS as a resilience lane.** GVS POT policy for HLS is `required=False` across
  the android/VR family, and VISIONOS returns an `hlsManifestUrl` (`hls=y` in our
  own device logs). media3 speaks HLS. Worth knowing exists if the HTTPS/DASH lane
  gets potted; not worth building speculatively.
- **Premium exemption (needs one fact).** `WEB`/`MWEB` carry
  `not_required_for_premium=True` — a Premium account drops the web partition's
  pot requirement entirely. Free if the account has Premium, nothing if not.
- **Possible wrong constant:** `CLIENT_NAME_IDS` maps `TVHTML5_SIMPLY` to `"74"`,
  upstream says `75`. Low stakes (the phone gate skips TV_SIMPLY) but if it's
  wrong it is silently wrong.
- ~~Browse section switches refetch /browse every time~~ DONE 2026-07-18
  (per-section 5-min TTL, see the feed-load round above).
- ~~SessionWarmup fires a throwaway Big Buck Bunny /player + googlevideo
  preconnect every launch~~ DEFERRED to first feed paint 2026-07-18 (tier 2)
  so it never races the launch /browse chain; a FULL skip (needs an
  nsig-extractor freshness probe) remains open.
- ~~FailFastLoadErrorPolicy: treat Cronet net::ERR_NAME_NOT_RESOLVED /
  ERR_INTERNET_DISCONNECTED as fatal~~ ALREADY DONE (shipped in `4315f15`, this
  line was stale). `FailFastLoadErrorPolicy.isFatalTransportError` matches
  UnknownHostException, NoRouteToHostException and both Cronet `net::ERR_`
  strings, and `getRetryDelayMsFor` returns `C.TIME_UNSET` for them so the very
  first failure surfaces to the app-level reload. Covered by
  `FailFastLoadErrorPolicyTest.dnsAndDisconnectedErrorsAreFatalAtMediaLayer`.
- Fixed 1000ms reload delay on the 403-remint path (VideoLoaderController:461;
  shorten via a call-site overload, NOT the shared reloadVideo default).
- ~~ABR seed persists across network types~~ ALREADY DONE (this line was stale
  too): Media3SourceFactory keys the persisted EWMA per Android network type
  (`bw_estimate_bps_net_*`, `SEEDED_NETWORK_TYPES`) and feeds them through the
  per-networkType `setInitialBitrateEstimate` overload, with a one-time
  conservative migration off the old global key.
- No metered cap on buffer-ahead (75s of 1080p prefetch on abandoned videos).
- ~~Brotli for InnerTube JSON~~ DONE 2026-07-18 (tier 2, phone-gated;
  WiFi-verified — still worth a one-off sanity check on cellular/roaming).
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
- ~~Signed-in TV_DOWNGRADED streams on a pot-ENFORCING carrier network~~
  SOAKED 2026-07-27 (see Works below) — no 60s cliff. Closed.

## Open — product/UX
- ~~Playlist queue UI in player ("Playing from: X · i/N", collapsible)~~ DONE
  2026-07-27, Pixel 9 verified (see Works below).
- "Not interested"/"Don't recommend channel" feedback tokens (server moved
  them; MediaServiceCore dig needed).
- Channel rows in search suggestions; channel page header/sort polish.
- Age-gated videos: silent ~6 s stall then auto-skip — needs an error dialog.
- In-player "Video buffer" row (knob currently applies at next player open).
- UI sweep DONE: PiP enter-animation flash fixed (gear→PiP pre-strips the
  window to video-only BEFORE enterPictureInPictureMode, so the shrink never
  captures the squeezed watch page; refused-entry path restores the layout;
  rotation now re-pushes PiP params so the auto-enter sourceRectHint stays
  fresh). Quality + captions + speed are all native sheets.
- Captions rework DONE (post-1.5.0): CC tap toggles with YouTube-style
  snackbar + filled/outlined icon state; native captions sheet (long-press CC
  or gear→Subtitles) with flat track list + "Caption style & size" footer;
  default render = white-on-semi scrim, regular weight, fractional sizing
  (one-shot migration off the old yellow TV default in MobileMainApplication).
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
