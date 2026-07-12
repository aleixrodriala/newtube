# NewTube — Status

**v1.3.0 (versionCode 10300), main @ `c685fd6` (pot-enforcement round), all
three repos pushed (2026-07-12).** Phone-only: the TV flavors, vendored ExoPlayer fork, and
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

## Open — needs a real device (Pixel 9)
Round 2 (2026-07-12 evening) closed most of this list (see Works): live DVR
scrub-back + LIVE-chip + soak, background-audio FGS, PiP→search. Still open:
- ABR down-switch under genuinely constrained bandwidth. Radio-based
  constraining (`cmd phone set-allowed-network-types-for-users`) is OFF THE
  TABLE while the dev Mac tethers through the phone: a 35 s GSM-only flip
  wedged cellular data for ~12 min (DNS dead, radio still attached; Android
  data-stall recovery eventually rebuilt the PDN — HANDOFF §9). Needs a
  throttled Wi-Fi AP, or a day the phone isn't the Mac's uplink.
- WEB_EMBED /player RTT varies 0.3–2.1s on LTE (cold TTFF 3.8s worst case vs
  ANDROID_VR's 3.4s — acceptable since ANDROID_VR streams die at 60s on
  enforcing networks, but worth optimizing; TV+serviceIntegrityDimensions is
  the candidate).
- Deferred loop experiments: post-rebuffer resume gate 2.5→1.5 s,
  pinned-quality fallback rescue.

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
