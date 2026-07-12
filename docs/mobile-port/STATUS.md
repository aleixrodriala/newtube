# NewTube — Status

**v1.3.0 (versionCode 10300), main @ `37ef7d0`, all three repos pushed, tree
clean (2026-07-12).** Phone-only: the TV flavors, vendored ExoPlayer fork, and
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

## Open — needs a real device (Pixel 9)
- Live DVR soak on real network; ABR down-switch under genuinely constrained
  bandwidth (emulator throttle stalls connections instead of shaping).
- Background-audio engine-restart FGS path (Android 12+), POST_NOTIFICATIONS
  flow, PiP→search task-routing re-check.
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
- Carry-overs: occasional first-frame black in mini player; video decoder
  still runs during background audio.

## Open — tech debt
- Proper edge-to-edge insets before targetSdk 36.
- Unstripped native libs (needs NDK 21); `newtube.json` update manifest for
  the in-app updater.
- Parked (documented in HANDOFF): googlevideo range-query leaf wrapper, SABR,
  media3 DefaultPreloadManager.

## Pending decision
`NewTube_1.3.0_universal.apk` is built and unpublished — GitHub Releases +
download page (see `docs/gtm/`) are the ready channels; publishing is Aleix's
call.
