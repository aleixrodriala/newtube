# SmartTube → Mobile (touch) Port — Architecture

> Goal: fork SmartTube (Android-TV / Leanback YouTube client) into a **touch-first
> phone/tablet app**. **Keep the backend, player engine, and account-login system
> unchanged. Replace only the Leanback/D-pad UI with touch screens.**

This document is the ground-truth map produced by a 7-agent codebase analysis.
It is the reference for every implementation wave. Class/file paths are relative
to the repo root.

---

## 1. The one-sentence reason this is feasible

SmartTube is a near-textbook **MVP** app: all logic, data, networking, and the
ExoPlayer engine live in the **`common`** and **`MediaServiceCore`** modules with
**zero `androidx.leanback` imports in the presenters**. The Leanback UI in
`smarttubetv/` only *implements plain View interfaces*. So we reuse the
presenters + backend + player and write new touch Views.

---

## 2. Module map: keep vs reuse vs rebuild

| Module | Role | Verdict | Action |
|---|---|---|---|
| `MediaServiceCore/youtubeapi`, `/mediaserviceinterfaces` | InnerTube backend, account login, stream/sig decode | **reuse-as-is** | Depend on it unchanged |
| `SharedModules/*` (`sharedutils`, `j2v8`, `appupdatechecker2`, `commons-io`) | utils, V8 JS engine (sig/nsig), updater | **reuse-as-is** | Unchanged |
| `exoplayer-amzn-2.10.6/*` | Player engine (DASH/HLS/SABR) | **reuse-as-is** | Unchanged (only `extension-leanback` is TV-tied; not needed) |
| `common/.../app/presenters/**` | All MVP presenters | **reuse-with-tweaks** | Reuse; only `ViewManager` is reworked |
| `common/.../app/models/playback/**`, `common/.../exoplayer/**` | 11 playback controllers + ExoPlayer wrapper | **reuse-as-is** | Unchanged |
| `common/.../prefs/**` (`*Data` singletons) | All settings/state | **reuse-as-is** | Unchanged |
| `common/.../app/views/ViewManager.java` | Navigation (Activity-per-screen, hand-rolled back-stack) | **rework** | New mobile router (see §5) |
| `doubletapplayerview` | YouTube-style double-tap seek | **reuse-as-is** | Already touch-native; keep |
| `slidableactivity`, `filepicker-lib`, `chatkit` | swipe-dismiss, file picker, chat list UI | **reuse-with-tweaks** | Generic Android, mostly reusable |
| `smarttubetv/.../ui/**` | Leanback Activities/Fragments/Cards/overlay | **tv-coupled-rewrite** | **Rebuild as touch** |
| `leanback-1.0.0`, `fragment-1.1.0` | vendored/patched Leanback & Fragment libs | **drop eventually** | Remove once no touch code imports them |
| `leanbackassistant` | Android-TV home-screen channels | **drop on mobile** | Exclude from mobile flavor (its manifest forces `leanback` feature) |

---

## 3. The MVP seam — Presenter ↔ View interface

Every screen = a **singleton Presenter** (`XxxPresenter.instance(context)`) in
`common/.../app/presenters/` + a plain **View interface** in
`common/.../app/views/`. A screen attaches via:

```java
XxxPresenter p = XxxPresenter.instance(context);
p.setView(this);            // 'this' implements XxxView
p.onViewInitialized();      // also onViewResumed/Paused/Destroyed in lifecycle
```

`BasePresenter` holds the view as a `WeakReference` and verifies liveness by
walking Fragment/Activity (generic, **not** Leanback) — works with any
Fragment/Activity, including ones hosting Compose.

**View interfaces to implement for touch** (all in `common/.../app/views/`):

| View | Presenter | Mobile screen |
|---|---|---|
| `SplashView` | `SplashPresenter` | launch/deep-link handler (often no UI) |
| `BrowseView` | `BrowsePresenter` (~1240 lines) | Home shell: bottom-nav/drawer + section content |
| `SearchView` | `SearchPresenter` | search bar + suggestions + results grid |
| `ChannelView` | `ChannelPresenter` | channel page (rows) |
| `ChannelUploadsView` | `ChannelUploadsPresenter` | channel uploads grid |
| `PlaybackView` (extends `PlayerManager`) | `PlaybackPresenter` | touch player (see §6) |
| `AppDialogView` | `AppDialogPresenter` | **one** settings/menu renderer (see §4) |
| `SignInView` | `YTSignInPresenter` | device-code login (see §7) |
| `AddDeviceView` | `AddDevicePresenter` | companion-device pairing |
| `WebBrowserView` | `WebBrowserPresenter` | in-app WebView (already touch-native) |
| `DetailsView` | `DetailsPresenter` | (dead on TV — design fresh if wanted) |
| `AppUpdateView` | (updater) | update prompt |

**Input contract** the touch list/grid adapters call back into
(`VideoGroupPresenter`/`SectionPresenter`): `onVideoItemClicked` (= tap),
`onVideoItemLongClicked` (= long-press), `onScrollEnd` (= paginate),
`onSectionFocused` (= section/tab selected).

**Core data models** the Views bind to (`common/.../app/models/data/`):
`Video` (971-line domain entity), `VideoGroup` (page/section of videos with
`ACTION_REPLACE/SYNC/REMOVE/CONTINUE`), `BrowseSection` (sidebar/tab entry with
`TYPE_GRID/TYPE_ROW/TYPE_MULTI_GRID/TYPE_SHORTS_GRID/TYPE_SETTINGS_GRID/TYPE_ERROR`),
`SettingsItem`/`SettingsGroup`.

---

## 4. Highest-leverage seam: AppDialog (settings + every context menu)

`AppDialogPresenter` builds a `List<OptionCategory>` (each holding
`OptionItem`/`UiOptionItem`: radio/checkbox/single-action, with embedded onClick
lambdas) and shows it via the single `AppDialogView.show(...)`. **All ~15
settings presenters AND every "…" context menu** (`VideoMenuPresenter`,
`SectionMenuPresenter`, channel/uploads menus) funnel through this. → **Build ONE
Material bottom-sheet/list renderer of `OptionCategory/OptionItem` and ~30
screens/menus light up at once.** Use `AppDialogFragment.onPreferenceDisplayDialog()`
as the authoritative list of item "kinds" to support (single-select,
multi-select, sub-screen, password-gated, chat, comments).

---

## 5. Navigation seam: `ViewManager` (the one real adaptation)

`ViewManager` is Activity-per-screen with a hand-rolled back-stack
(`register(viewClass, activityClass, parentActivityClass)`, `startView(Class)`,
`startParentView`, `getTopView`, `getTopPresenter`). Wiring lives **only** in
`smarttubetv/.../ui/main/MainApplication.setupViewManager()`.

Two options (decision pending — see ROADMAP):
- **(A) Keep Activity-per-screen, repoint `setupViewManager()`** to new touch
  Activities. Reuses `ViewManager` nearly verbatim → presenters need *zero*
  changes. Fastest, lowest risk.
- **(B) Single-Activity + Jetpack Navigation**, reimplementing `ViewManager`'s
  API over a NavController. Cleaner; enables YouTube-style minimize-to-mini-player
  while browsing; larger change touching every screen's lifecycle.

Either way, the presenter-facing API (`register/startView/...`) stays identical.
`MotherActivity` (`common/.../misc/`) is the shared base Activity — a mobile base
Activity should preserve the `instanceof MotherActivity` contract used by
`ViewManager.properlyFinishTheApp()`.

---

## 6. Player seam

- **Reuse unchanged:** `PlaybackPresenter` (owns 11 controllers:
  VideoState, Suggestions, VideoLoader, ErrorFixer, PlayerUI, Remote,
  SponsorBlock, AutoFrameRate, HQDialog, Chat, Comments), the whole
  `common/.../exoplayer/**` (`ExoPlayerController`, `ExoMediaSourceFactory`,
  `TrackSelectorManager`, `ExoPlayerInitializer`, `SubtitleManager`,
  `DebugInfoManager`, `VideoZoomManager`), and `PlayerData`/`PlayerTweaksData`.
- **The contract:** `PlaybackView extends PlayerManager (= PlayerEngine + PlayerUI)`.
  No ExoPlayer types leak through the interface.
- **Template for the mobile player:**
  `smarttubetv/.../ui/widgets/embedplayer/EmbedPlayerView.java` — it already
  implements `PlaybackView` on a plain `com.google.android.exoplayer2.ui.PlayerView`
  with **no Leanback** in its hierarchy. Build `MobilePlaybackFragment` the same way.
- **Rebuild:** `PlaybackFragment` (~1650 lines), `VideoPlayerGlue` + the ~25
  `Action` subclasses, the Leanback transport row, storyboard `ThumbsBar`/
  `StoryboardSeekDataProvider`. Keep the `R.id.action_*` integer ids as the
  `onButtonClicked(int,int)` vocabulary so controllers need no changes — only the
  *caller* changes (a touch IconButton instead of `VideoPlayerGlue.dispatchAction`).
- **Reuse touch-native bits:** `doubletapplayerview` (double-tap seek),
  `StoryboardManager` (sprite fetch/cache — render on a new touch scrubber),
  `SponsorBlockController`'s `SeekBarSegment` list (draw colored ranges on scrubber).
- **Drop on mobile:** `AutoFrameRateController`/AFR (no mobile OS equivalent);
  `BackgroundPlaybackService` is documented dead code (background play = activity
  lifecycle trick). PiP API works the same on phones.
- **Blocking dependency:** player settings sheets (quality/speed/subtitles) go
  through `AppDialogPresenter` → §4 must ship before/with the player.

---

## 7. Account-login seam (KEEP — only re-skin)

Device-code OAuth ("enter code at yt.be/activate", QR). **Reuse unchanged:**
`YTSignInPresenter`, `SignInService`/`YouTubeSignInService`,
`YouTubeAccountManager`, `AuthService` (V2), token storage. Only
`smarttubetv/.../ui/signin/SignInActivity`+`SignInFragment` (Leanback GuidedStep)
get rebuilt.

Mobile login screen recipe:
1. Implement `SignInView` → `showCode(userCode, signInUrl, fullSignInUrl)`, `close()`.
2. `YTSignInPresenter.instance(ctx).setView(this); ...onViewInitialized();`
3. Show the code big + copyable; `signInUrl` as a tappable link; generate the QR
   **client-side** (zxing) from `fullSignInUrl` instead of hitting `api.qrserver.com`.
4. Show progress (the poll runs up to ~10 min / 200×3s).
5. On success the presenter calls `getView().close()` then
   `AccountSelectionPresenter.instance(ctx).show(true)` — keep it.
6. Accounts management: build a touch Settings→Accounts screen calling
   `SignInService.getAccounts()/selectAccount()/removeAccount()` directly.

**Do NOT change** `model_name='ytlr::'` or the TV OAuth client id/secret in
`AppService`/`AuthApiHelper` — Google would reject/flag the client. Token blob is
stored as a plain file via `GlobalPreferences.set/getMediaServiceAccountData()`;
optionally back it with `EncryptedFile` without touching auth logic.

---

## 8. Build changes (see also docs in ROADMAP Wave 0)

- Versions centralized in `SharedModules/constants.gradle`: AGP 7.4.2, Gradle 7.5,
  Kotlin 1.8.10, JDK 17, compileSdk 34, minSdk 17.
- **Mandatory manifest fix** (`smarttubetv/src/main/AndroidManifest.xml`): make
  `android.software.leanback` `required="false"` (or remove); make
  `android.hardware.touchscreen` `required="true"` (or remove). Remove
  `LEANBACK_LAUNCHER` category, keep `LAUNCHER`+`DEFAULT`. **Also** patch
  `leanbackassistant/src/main/AndroidManifest.xml` (it re-adds the leanback
  requirement; `common` depends on `:leanbackassistant`) or drop that dependency
  for the mobile flavor.
- **New flavor** `stmobile` alongside `stbeta/ststable/stfdroid` in
  `smarttubetv/build.gradle` (+ mirror in `common`, `MediaServiceCore/youtubeapi`).
  Lets the TV build keep working untouched.
- Build: `./gradlew assembleStmobileDebug` → APK under
  `smarttubetv/build/outputs/apk/stmobile/debug/`.
- **Sandbox gap:** no Android SDK present (only Windows SDK via WSL). Wave 0 must
  install a Linux Android SDK + set `ANDROID_HOME`/`local.properties`.

---

## 9. Top risks (carry into every wave)

1. **`ViewManager` rework** is the only real architectural risk — keep its public
   API identical regardless of approach.
2. **`PlaybackFragment` rewrite** (~1650 lines) is the single biggest task —
   decompose into PlayerFragment + RelatedVideosPanel + LiveChatPanel; don't 1:1 port.
3. **nsig/signature + PO-token decode** (in MediaServiceCore) is an active arms
   race vs YouTube; it's a *shared* maintenance burden, not mobile-specific, but
   it's the thing most likely to break playback. Keep it in sync with upstream.
4. Process-wide **singleton presenters + mutable static caches** assume one
   Activity hierarchy; re-verify `WeakReference` liveness under aggressive mobile
   Fragment recreation / multi-window / foldables.
5. Long-press semantics: D-pad OK-long-press encodes secondary actions; needs a
   touch UX redesign (long-press vs overflow), not a code port.
6. `DetailsView`/`DetailsPresenter` are **dead code on TV** — design fresh if a
   pre-play watch page is wanted.
