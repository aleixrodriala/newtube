# NewTube — Claude Code context

NewTube is a **phone-only fork of SmartTube** ("SmartTube for phones"): package
`com.newtube.app`, single product flavor `stmobile`, touch UI in
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
- Submodules `MediaServiceCore` and `SharedModules` are **our forks**
  (remote `fork` = aleixrodriala/<name>, branch `master`). Commit INSIDE the
  submodule first, push `fork master`, then commit the pointer bump in main.
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
- `android:windowOptOutEdgeToEdgeEnforcement` keeps the opaque system bars but
  **dies at targetSdk 36** — proper per-screen insets are REQUIRED before any
  targetSdk bump.
- Single-slot caches self-evict: two shipped bugs came from a newer write
  evicting the entry the feature depended on (negative format-info cache;
  MediaSource stash cleared by its own open's reset). When adding "remember
  one thing" logic, walk the eviction timeline first.
