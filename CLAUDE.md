# NewTube — Claude Code context

NewTube is a **phone-only fork of SmartTube** ("SmartTube for phones"): package
`io.github.aleixrodriala.arc` (brand-neutral so a rename never touches the
package; "arc" = the arch-"n" launcher mark), single product flavor `stmobile`,
touch UI in
`smarttubetv/src/stmobile/`, playback on **androidx.media3 1.10.1** (Cronet media
path). The TV flavors and the vendored ExoPlayer fork were deleted in the
phone-only port; pre-port history lives at the `tv-legacy` tag and the
`upstream/master` remote (that's where the original `LiveDashManifestParser`,
vendored ExoPlayer, and TV UI can still be read with `git show`).

Current state, open backlog, and deep context: **`docs/mobile-port/STATUS.md`**
and **`docs/mobile-port/HANDOFF.md`** (read HANDOFF before touching the player
or network stack). `docs/mobile-port/{ARCHITECTURE,ROADMAP}.md` predate the
phone-only slice — treat as historical. GTM/launch assets: `docs/gtm/`.

## Repos & commit order
- Main: `origin` = github.com/aleixrodriala/newtube, branch `main`.
- Submodules `MediaServiceCore` and `SharedModules` are **our forks**:
  `origin` = aleixrodriala/<name> (HTTPS — SSH keys are not set up on this
  Mac), `upstream` = yuliskov/<name>, branch `master`. Commit INSIDE the
  submodule first, push `origin master`, then commit the pointer bump in main.
- Upstream (yuliskov) fixes are merged into the **submodule forks only**; the
  main repo never merges upstream (TV code is gone). Submodules have repo-local
  `core.autocrlf=false` — keep it; several upstream build files are CRLF.

## Build
```
ANDROID_HOME=<sdk> ./gradlew :smarttubetv:assembleStmobileDebug   # verification builds
ANDROID_HOME=<sdk> ./gradlew :smarttubetv:assembleStmobileRelease # distribution
# -> smarttubetv/build/outputs/apk/stmobile/<type>/NewTube_<ver>_<abi>.apk
```
Use DEBUG builds for on-device verification — the NetPath per-chunk/cronet/
request logging is debug-gated. `minifyEnabled` is off (the live-DASH parser
and pref plumbing rely on reflection over media3 internals — do not enable R8
without auditing `LiveDashManifestParser` + `Helpers.setField` call sites).

## Hard-won rules (violating these has already cost debugging days)
- **logcat dumps contain NUL bytes** — plain grep silently returns nothing;
  ALWAYS `grep -a`.
- **Always pin the adb serial** (`adb -s <serial>`) — an unpinned install once
  replaced the app on the wrong device mid-test.
- Emulator seekbar: reveal controls with a center tap; only SLOW ~800–1200 ms
  `input swipe` drags register as seeks; fast swipes do nothing; sub-300 ms
  swipes on cards register as clicks.
- **Never edit `VIDEO_INFO_TYPE_LIST`** (MediaServiceCore VideoInfoService) —
  upstream churns it constantly; phone behavior is controlled through static
  gates set from `MobileMainApplication` (`setSkipTvFallbackClients`, etc.).
- The second `/player` call on playable videos is the **deferred WEB
  subtitle-enrichment fetch** (auto-translate lists), fires post-playback —
  it is NOT redundant; do not dedupe it.
- `GOOGLEVIDEO_RANGE_QUERY` (Media3SourceFactory) **stays false** — see
  HANDOFF for the 416/cache-poisoning post-mortem before ever revisiting.
- Edge-to-edge is **already enforced** (targetSdk 37): the
  `windowOptOutEdgeToEdgeEnforcement` attr in `styles_mobile.xml` still works on
  Android ≤15 and is ignored from Android 16 — verified by the `pfl=` window flags
  of one build on API 35 vs API 37 (`dumpsys window windows`, look for
  `EDGE_TO_EDGE_ENFORCED`). So on modern devices `setDecorFitsSystemWindows` /
  `setStatusBarColor` / `setNavigationBarColor` are **no-ops**, and what keeps
  content off the bars is `MobileActivity.installContentInsets()` plus the
  `shouldInsetContentFor*` overrides. A new screen either inherits that or
  applies the insets itself — **an overlay that paints its own scrim must opt
  out** (`MobileAppDialogActivity`), or the dim stops at the status bar.
- Single-slot caches self-evict: two shipped bugs came from a newer write
  evicting the entry the feature depended on (negative format-info cache;
  MediaSource stash cleared by its own open's reset). When adding "remember
  one thing" logic, walk the eviction timeline first.
- **A bottom sheet's surface is a THEME setting, not a `setBackground` call.**
  Material re-applies its own `MaterialShapeDrawable` to `design_bottom_sheet` on
  every layout, so code that paints that frame is silently overwritten (four files
  carried a workaround that never worked). Style `bottomSheetDialogTheme` ->
  `bottomSheetStyle` instead, and keep `elevationOverlayEnabled=false` on the theme
  overlay — the 16dp sheet elevation blends 14.75% white into `colorSurface`, which
  turned #1E1E1E into #3F3F3F.
- **Any googlevideo fetch outside the player must copy the player's two habits**
  (downloads learned both the hard way, 2026-09-11): use `MediaHttpClient` (the
  shared OkHttp client's InnerTube interceptors get a 403), and treat a media 403
  as routine — the first /player client's links are often refused; recover like
  `ErrorFixerController` (`markCurrentPlaybackRouteForbidden` + `applyNoPlaybackFix`
  + re-resolve the same itags), never by retrying the same link.
- **`getResources().getDisplayMetrics()` is NOT this device's metrics.**
  `MotherActivity.initDpi()` (private, called from its `onCreate` — not overridable)
  swaps in one process-wide cached instance: density is derived from a 1920px TV
  reference (2.525 on a Pixel 9, not 2.625), and width/height are frozen at whatever
  orientation the FIRST activity saw. Anything sizing itself off `heightPixels` must
  read the live display instead (`MobileSheets.expandTo`); `UI scale` multiplies that
  same density, which is why that settings knob is still live.
