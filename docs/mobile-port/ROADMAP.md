# SmartTube Mobile Port — Roadmap

Sequenced waves to reach **full feature parity** on touch phones/tablets while
reusing SmartTube's `common` presenters + `MediaServiceCore` backend + ExoPlayer
engine + account login. See `ARCHITECTURE.md` for the seams each wave attaches to.

Execution model: each wave is driven by a **Workflow** — Opus orchestrates,
Sonnet 5 implements independent subtasks in parallel (worktree isolation when
writing files concurrently), then build → verify on device → adversarial review.

Legend: 🔒 = hard dependency on a prior wave.

---

## Wave 0 — Build & launch on a phone (toolchain + manifest)
*Outcome: an installable `stmobile` debug APK that boots on a phone without crashing.*
- Install a Linux Android SDK in WSL; set `ANDROID_HOME` + `local.properties`.
- Patch `smarttubetv` manifest: `leanback` → `required=false`, `touchscreen` →
  `required=true`, drop `LEANBACK_LAUNCHER` (keep `LAUNCHER`+`DEFAULT`).
- Patch/neutralize `leanbackassistant` manifest leanback requirement (or exclude
  the dependency for the mobile flavor).
- Add `stmobile` product flavor (`smarttubetv`, `common`, `MediaServiceCore/youtubeapi`).
- Establish loop: `./gradlew assembleStmobileDebug` → `adb install` → launch.
  (At this point it may still boot the Leanback UI — that's fine; we have a build.)

## Wave 1 — Mobile foundation 🔒W0
*Outcome: app shell + the universal dialog renderer that unlocks ~30 settings/menus.*
- Mobile base `Activity` (preserve `instanceof MotherActivity` contract).
- Router: implement the chosen navigation model (A: keep Activity-per-screen &
  repoint `setupViewManager()`; B: single-Activity + Jetpack Navigation) behind
  `ViewManager`'s existing `register/startView/...` API.
- **`AppDialogView` touch renderer** (Material bottom sheets / list screens) over
  `OptionCategory/OptionItem` — covers all settings + every context menu.
- Material theme, app icon, splash screen.

## Wave 2 — Browse / Home shell 🔒W1
*Outcome: scrollable Home + section navigation, infinite scroll, touch cards.*
- `BrowseView` touch impl: bottom-nav (phone) / nav-rail or drawer (tablet) from
  `BrowseSection` list; content area renders grid / nested-rows / shorts per
  `BrowseSection.type`.
- RecyclerView adapters for Video / Shorts / Channel cards — port
  `ComplexImageCardView` Glide loading, badges (LIVE/NEW/SHORTS), watch-progress;
  drop focus-scale, add ripple/elevation. Reuse `GridFragmentHelper` span math.
- Pagination via `onScrollEnd`; long-press → context menu (via Wave 1 renderer).

## Wave 3 — Search, Channel, Channel Uploads 🔒W2
- `SearchView`: search field + tag suggestions + results grid; voice via
  `SpeechRecognizer`; filter dialog via `AppDialogPresenter`.
- `ChannelView` (rows) + `ChannelUploadsView` (grid), in-channel search.

## Wave 4 — Touch player 🔒W1 (biggest single wave)
*Outcome: full-screen touch playback with controls, scrubber, gestures, related.*
- `MobilePlaybackFragment implements PlaybackView` (template: `EmbedPlayerView`),
  wrapping `ExoPlayerController` via `ExoPlayerInitializer`.
- Touch controls overlay: tap-to-toggle, play/pause, drag scrubber with
  storyboard preview (`StoryboardManager`) + SponsorBlock segment ranges
  (`SeekBarSegment`), prev/next, fullscreen/rotate, overflow sheet for the long
  tail of `R.id.action_*` buttons (keep the int-id `onButtonClicked` vocabulary).
- Reuse `doubletapplayerview` double-tap seek; subtitles (`SubtitleManager`);
  speed/quality/audio-track via `AppDialogPresenter`; repeat/shuffle modes.
- Related-videos panel, background playback, Picture-in-Picture. Drop AFR.

## Wave 5 — Account login (mobile) 🔒W1
- `SignInView` touch screen: code + tappable link + **client-side QR** (zxing);
  progress/expiry. `AccountSelection` + Settings→Accounts (add/switch/remove,
  password-lock, select-on-boot). Boot sequence parity with `SplashPresenter`.
- Unlocks signed-in feeds for Wave 6 (subscriptions, history, playlists, likes).

## Wave 6 — Feature-parity sweep 🔒W2,W4,W5
*Outcome: "all features SmartTube has."*
- Sections: Subscriptions, History, Playlists, Watch Later, Liked, My Videos,
  Trending, Music, Gaming, News, Live, Shorts, Kids, Notifications, Playback
  Queue, Blocked Channels.
- SponsorBlock (settings + skip), DeArrow (title/thumbnail), Return YouTube
  Dislike, Comments panel, Live chat panel (chatkit).
- Channel groups / pinning; like/dislike/subscribe/playlist actions.
- All ~15 settings categories (mostly free via the Wave 1 renderer): General,
  Player, Player Tweaks (codecs AV1/VP9/AVC, 8K, buffers), Main UI, Subtitles,
  Language/region, Search, Account, About, UI scale.
- Proxy/censorship bypass, backup/restore (filepicker + GDrive), in-app updates,
  deep links / share-intent / `vnd.youtube` handling.

## Wave 7 — Mobile-native polish 🔒W4,W6
- Minimize-to-floating mini-player while browsing (esp. if single-Activity),
  swipe-to-dismiss, orientation/fullscreen handling.
- Tablet two-pane / NavigationRail layouts.
- Media-session + notification/lock-screen controls (port `BackboneQueueNavigator`).
- Dark/light theming, empty/error states.

## Wave 8 — Hardening 🔒all
- Multi-window/foldable, singleton liveness under Fragment recreation.
- Performance (scroll, image cache, player startup), crash/error handling.
- End-to-end manual verification on a real device across every feature.

---

### Cross-cutting: keep the TV build alive
The `stmobile` flavor means `stbeta/ststable/stfdroid` (TV) keep building
unchanged throughout — we add touch source sets/screens alongside, and only
remove `leanback-1.0.0`/`fragment-1.1.0`/`leanbackassistant` from the *mobile*
flavor once nothing touch-side imports them.
