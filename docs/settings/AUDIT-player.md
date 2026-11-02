# Player-related settings: what each row does on the phone

Audit for GitHub issue #2 ("settings are way too convoluted"). Repo state: `main` @ 04869135 (v1.14.1 + site), 2026-10-02. Research only; no source changed.

Scope: every row the phone shows on **Settings > Player** (`PlayerSettingsPresenter.show()`, including the phone-only "Experimental" row injected by `MobileBrowseActivity.openSettings`) and on **Settings > Subtitles / SponsorBlock / DeArrow**. Rows hidden behind `!PhoneUi.isEnabled()` are left out: Setup player buttons, Loop Shorts, DeArrow's thumbnail source picker. So is SponsorBlock's "Exclude this channel" button, which is gated on the player being the top view, and that never happens on the phone.

## How to read this

**Verdicts.**
- **LIVE:** a phone code path reads the getter and changes something the user can notice.
- **DEAD:** nothing the phone runs reads it, or the only reader is a no-op stub.
- **PARTIAL:** some of what the row promises works on the phone and some doesn't; the row says which.
- **KEYBOARD-ONLY:** read only on a hardware-key path.
- **UNCLEAR:** couldn't be settled from the code.

**Path shorthand used in the tables.**
- `common/…/` = `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/`
- `mobile/…/` = `smarttubetv/src/stmobile/java/com/newtube/mobile/`
- `ctrl/` = `common/…/app/models/playback/controllers/`
- `MSC/…/` = `MediaServiceCore/youtubeapi/src/main/java/com/liskovsoft/youtubeapi/`
- `SM/…/` = `SharedModules/sharedutils/src/main/java/com/liskovsoft/sharedutils/`
- Class abbreviations:
  - PD = PlayerData, PTD = PlayerTweaksData, SD = SearchData, GD = GeneralData
  - PSP = PlayerSettingsPresenter
  - VLC = VideoLoaderController, EFC = ErrorFixerController
- "idx N" = the setting's slot in its class's single persisted pref string.

**Facts every verdict relies on.** Each was checked in the code:
1. **Nothing calls `PlaybackPresenter.onKeyDown`** (`common/…/app/presenters/PlaybackPresenter.java:389`). The phone's `MobilePlaybackActivity.dispatchKeyEvent` only routes volume keys to casting, and `MotherActivity.onKeyDown` only handles MEDIA_STOP. So every reader inside a controller's `onKeyDown` (OK key, number keys, D-pad seeking) is **DEAD, even with a hardware keyboard**. That is why no row came out KEYBOARD-ONLY.
2. **The phone drives the shared controllers** through `PlaybackPresenter`:
   - `MobilePlaybackActivity` calls `setView`/`onViewInitialized` (:507-508) and `onEngineInitialized` (:1294).
   - It also calls `onControlsShown` (:3373/:3399), `onPrevious/NextClicked`, `onButtonClicked`/`onButtonLongClicked` (gear-sheet actions only) and `onFinish`.
   - `Media3PlayerController` fires `onSourceChanged`, `onVideoLoaded`, `onPlay`, `onPlayEnd`, `onBuffering`, `onSpeedChanged` and `onEngineError`.
   - Controller code under those callbacks runs on the phone; code under `onKeyDown` or TV-only button ids does not.
3. **These phone `PlaybackView` methods are empty stubs** (MobilePlaybackActivity.java:5640-5660, 5757, 5822-5830, 5883, 5899, 8012-8036): `setZoomPercents`, `setAspectRatio`, `setRotationAngle`, `setVideoFlipEnabled`, `setVideoGravity`, `setSeekPreviewTitle`, `setNextTitle`, `loadStoryboard`, `updateEndingTime`, `focusSuggestedItem`, `resetSuggestedPosition`, `showSuggestions`. A setting that only reaches one of them is DEAD.
4. **TV readers that were deleted** (still readable at the `tv-legacy` tag): `PlaybackFragment` (pixel ratio, clock, quality info, double-tap seconds) and `PlaybackTransportRowPresenter` (seek confirmation, remaining/ending time, seek increment). The vendored ExoPlayer-2 renderers (audio delay, tunneling, vsync, Amlogic/Sony/Amazon fixes, SW decoder) and `TrackSelectorManager` (audio language, alt presets, unsafe audio) were deleted too.
5. **The TV HQ dialog (`HQDialogController`, `R.id.lb_control_high_quality`) is never opened on the phone.** Its in-player copies of Video buffer, Audio language, Audio delay, Volume and Sleep timer can't be reached.
6. **Labels come from `common/src/main/res/values/strings.xml`.** The stmobile flavor overrides only a handful of unrelated strings (sharing, pinning, subscribing).
7. **How the phone draws a settings page.** `DialogRowAdapter` shows every category inline: a header row, then every option. So Player is one long scroll, with no sub-screens except the "Video speed" button. Sixteen auto-hide radios, 21 sleep-timer radios, 61 zoom radios and every locale in Audio language all sit on that one page, which explains most of issue #2 by itself. Checkbox summaries (`_desc` strings) render as a second line.
8. **Dead-reader check.** Every DEAD getter was grepped repo-wide with `git grep --recurse-submodules` (MediaServiceCore and SharedModules included), searching for both calls and `::` method references. No reader was found outside its prefs class and the settings presenters, unless the row's notes name one.

The "Gear sheet" referred to below is the player's ⚙ sheet:
- Quality, Audio track (only on multi-language videos), Captions, Speed, PiP.
- More: Repeat, Shuffle, Zoom, Background, Add to playlist, Download, Queue, Stats.

---

## Settings > Player

> Slice notes (Playback mode to Volume):

Paths: `common/…/` = `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/`, `mobile/…/` = `smarttubetv/src/stmobile/java/com/newtube/mobile/`. Prefs: `PlayerData` = `common/…/prefs/PlayerData.java` (one blob, `restoreState()` L852), `PlayerTweaksData` = `common/…/prefs/PlayerTweaksData.java`. Factories: `AppDialogUtil` = `common/…/utils/AppDialogUtil.java`. Labels come from `common/src/main/res/values/strings.xml`; stmobile overrides none of them.

Phone wiring these verdicts rely on:
- `MobilePlaybackActivity.onCreate` calls `PlaybackPresenter.setView(this)` and `onViewInitialized()` (mobile/…/ui/playback/MobilePlaybackActivity.java:507-508), which runs every controller's `onInit`.
- `Media3PlayerController` fires `onSourceChanged` (:536), `onSpeedChanged` (:1073), `onVideoLoaded` (:1189), `onPlay` (:1258), `onPlayEnd` (:1262) and `onBuffering` (:1265). `SuggestionsController` fires `onMetadata`.
- `PlaybackPresenter.onKeyDown` is never called.
- The TV "HQ dialog" (`HQDialogController`, `R.id.lb_control_high_quality`) is never dispatched on the phone; only a comment mentions it (MobilePlaybackActivity.java:3616). So its copies of the Buffer, Audio language, Audio delay and Volume pickers cannot be reached.

### Playback mode

| Row | Getter/Setter | Default | Phone reader (file:line) | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Playback mode (radio): "Play videos continuously" / "Repeat current video" / "Shuffle any playlist" / "Play only playlist videos continuously" / "Play the playlist or channel videos in reverse order" / "Pause playback after each video (except queue)" / "Stop playback after one video (except queue)" | `PlayerData#getPlaybackMode` (:283) / `#setPlaybackMode` (:277) | `PLAYBACK_MODE_ALL` ("Play videos continuously") | common/…/app/models/playback/controllers/VideoLoaderController.java:286-290 `onPlayEnd` → `getPlaybackMode()` (:1009, which layers `QueuePlaybackMode` on top) → `applyPlaybackMode` (:818). `onPlayEnd` is fired by mobile/…/player/Media3PlayerController.java:1262. Also read by `preloadNextVideoIfNeeded` (:1063, prefetches the next video only for ALL/SHUFFLE/LIST) and by the gear Shuffle label, MobilePlaybackActivity.java:3911 | LIVE | Sets what happens when a video ends: play the next one, repeat, shuffle, follow only the playlist, play it in reverse, pause, or close the player. | The setter also clears any queue-scoped shuffle (`QueuePlaybackMode.clear()`). **Also in the player:** gear > More > Repeat opens this same radio, and More > Shuffle toggles SHUFFLE/ALL. Both write the same app-wide pref; there is no per-video override. On the phone "Stop playback" closes the player (`finishReally`), because `isSuggestionsShown()` always returns false. Option labels are long and TV-worded. |

### Video presets

| Row | Getter/Setter | Default | Phone reader (file:line) | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Video presets (radio): "Disabled" + presets such as "1080p    60fps    vp9", highest first (`AppDataSourceManager.getVideoPresets()`; VP9/AV1 rows the device cannot decode are hidden unless Developer options > "Unlock all formats" is on) | `PlayerData#getFormat(TYPE_VIDEO)` (:381) / `#setFormat` (:404), via `AppDialogUtil.setFormat` | No preset: `getDefaultVideoFormat()`, which is `VIDEO_FHD_VP9_60` (a 1080p60 VP9 ceiling) on the phone because `MobileMainApplication` calls `PlayerData.setDefaultVideoFormatMax1080(true)`; `VIDEO_HD_AVC_30` on devices without 1080p VP9. The radio shows "Disabled" | common/…/controllers/VideoStateController.java:252 `onSourceChanged` → `restoreFormats` → `restoreVideoFormat` :410 `getPlayer().setFormat(...)` → MobilePlaybackActivity.java:7901 `setFormat` → Media3PlayerController.java:950 `selectFormat` → mobile/…/player/Media3TrackAdapter.java:204 `applyVideoTarget`. A preset counts as "Auto", so it sets the max video size, max frame rate and preferred codec. Also seeded in Media3PlayerController.java:823 `applyPersistedFormats` | LIVE | Caps the automatic quality at the chosen resolution and frame rate, and prefers its codec; "Disabled" goes back to the built-in 1080p cap. | onSelect clears Developer > "Force legacy codecs" (`setLegacyCodecsForced(false)`). **Also in the player:** gear > Quality. A preset counts as "Auto" there, so a rung picked in the gear is only a per-session override (`setTempVideoFormat`). Picking "Auto" in the gear calls `setFormat(getDefaultVideoFormat())`, which **silently replaces the preset chosen here** with the 1080p default. Labels are raw spec strings ("720p     30fps    av01+hdr"). |

### Network engine

| Row | Getter/Setter | Default | Phone reader (file:line) | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Network engine (radio): "Default" ("Built-in network engine…") / "Cronet" ("Cronet is the Chromium network stack…") / "OkHttp" ("This network engine is the slowest one…") | `PlayerTweaksData#getPlayerDataSource` (:292) / `#setPlayerDataSource` (:296) | `PLAYER_DATA_SOURCE_CRONET` (`Utils.skipCronet()` is always false) | None. `mobile/…/player/Media3SourceFactory.java:396-453` always uses Cronet when the embedded engine loads and falls back to OkHttp by its own per-network verdicts; it never reads the pref. The only other reader, common/…/controllers/ErrorFixerController.java:365, runs on an OutOfMemoryError and only rewrites this same pref (`enableFasterDataSource`). The comment at :385 says it outright: "a pref the media3/Cronet stack never reads" | DEAD | Nothing on the phone. The media transport is chosen automatically: Cronet first, OkHttp as fallback. | No side effects. Incidental (from the codex review): with "OkHttp" picked, ErrorFixer's OutOfMemoryError recovery spends its first step flipping this pref (`enableFasterDataSource`) instead of lowering the buffer. That is not a transport change. The descriptions promise behavior that does not exist; "Default" is the old ExoPlayer built-in HTTP stack, which is gone. General settings has a second writer of the same pref (GeneralSettingsPresenter.java:700, outside this slice). |

### Video buffer

| Row | Getter/Setter | Default | Phone reader (file:line) | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Video buffer (radio): "Low" / "Medium" / "High" / "Highest" | `PlayerData#getVideoBufferType` (:509) / `#setVideoBufferTypeByUser` (:501) | Parse default `BUFFER_MEDIUM`. The phone promotes an untouched MEDIUM to **HIGH** once (`Media3PlayerInitializer.alignBufferDefaultOnce`, mobile/…/player/Media3PlayerInitializer.java:114-139), so the real default is High | Media3PlayerInitializer.java:203 `resolveBufferPreset` ← `createPlayerLoadControl` (:325) ← `createPlayer` ← MobilePlaybackActivity.java:1182 `createPlayerObjects` (activity creation and engine restart) | LIVE | Sets how far ahead the player buffers: Low 20-30 s / 48 MB, Medium 50 s / 96 MB, High 50-75 s / 192 MB, Highest 50-120 s / 288 MB (all capped by RAM). | onSelect persists at once and writes the `buffer_user_chosen` marker, so the one-time alignment never overrides the pick. It applies the next time the player engine is created, not to a player that is already open. ErrorFixer drops HIGH/HIGHEST to MEDIUM on an OutOfMemoryError (ErrorFixerController.java:368). Not in the gear sheet. |

### Video zoom

| Row | Getter/Setter | Default | Phone reader (file:line) | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Video zoom: fit modes "Default" / "Fit width" / "Fit height" / "Fit either width or height" / "Stretch" | `PlayerData#getResizeMode` (:553) / `#setResizeMode`; onSelect also `setZoomPercents(-1)` | `RESIZE_MODE_DEFAULT` (fit) | common/…/controllers/PlayerUIController.java:104 `onInit` (runs from `onViewInitialized`, MobilePlaybackActivity.java:508) → MobilePlaybackActivity.java:7995 `setResizeMode` → media3 `PlayerView.setResizeMode`. The ints match media3's `AspectRatioFrameLayout` (PlayerConstants:19-25) | LIVE | Picks how the picture fills the video area: fit, fill width, fill height, crop to fill, or stretch. | Applies when the player screen is next created; an open or mini player keeps its current mode. **Also in the player:** pinch writes the same pair (fit ↔ "Fit either", MobilePlaybackActivity.java:1102-1110), and gear > More > Zoom opens `PlayerUIController.onVideoZoom`, which shows this radio plus Aspect and Rotate. Aspect and Rotate are no-ops on the phone (`setAspectRatio`/`setRotationAngle`, :8018-8026). The persistent value is shared with the player; there is no per-video override. "Fit either width or height" is actually a crop/zoom-to-fill. |
| Video zoom: "50%"…"95%", "96%"…"100%", "105%"…"300%" | `PlayerData#getZoomPercents` (:562) / `#setZoomPercents`; onSelect also `setResizeMode(DEFAULT)` | -1 (off) | PlayerUIController.java:105 `onInit` → MobilePlaybackActivity.java:8012 `setZoomPercents`, which is an **empty TODO** | DEAD | Nothing: percentage zoom is not implemented on the phone. | Picking a percentage silently resets the fit mode to "Default", so in practice it behaves like "Default". The same 61 dead rows appear in gear > More > Zoom. |

### Video speed (button → sub-dialog "Video speed")

| Row | Getter/Setter | Default | Phone reader (file:line) | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| "Video speed" (button on the Player page) | none | n/a | PlayerSettingsPresenter.java:203-211 | LIVE (navigation) | Opens a sub-page with the speed list, "Remember speed" and "Misc". | The sub-page is built when it opens, so toggling the long-list options below does not re-list the speeds until it is reopened. |
| Video speed (radio): 0.25…4.0. Short list 0.25-4 (16 values), "Long speed list" 0.25-4 with fine steps (26 values, the default), or "Extra long" 0.05-4 in 0.05 steps | `PlayerData#getSpeed()` / `#setSpeed(float)` (:598-603), because the factory gets `playbackController == null` (`AppDialogUtil.createSpeedListCategory`) | 1.0. At launch `mSpeed` is reset to 1.0 unless "Same on all videos" is on (`restoreState`) | VideoStateController.java:604 `restoreSpeedAndPositionIfNeeded` (on `onMetadata`/`onBuffering`) → `getPlayerData().getSpeed(item.channelId)` → `getPlayer().setSpeed` → MobilePlaybackActivity.java:7960 → Media3PlayerController.java:1065 | PARTIAL | Sets the starting speed for videos, but only when "Remember speed" is "Same on all videos" (or "None", until the app restarts). Under the default "Per each channel" the per-channel lookup overrides it and resets the global value (`PlayerData.getSpeed(String)` :606-618). | The checkmark shows whatever global value was last left behind, so it can look wrong. **Also in the player:** gear > Speed sets the current video's speed, and "More speeds" opens the same factory with the live player (a per-video choice that the "Remember speed" mode then persists). As a default row this mostly does nothing on a stock install. |
| Remember speed: "None" | `PlayerData#setAllSpeedEnabled(false)`, `#setSpeedPerVideoEnabled(false)`, `#setSpeedPerChannelEnabled(false)`; checked when `!isAllSpeedEnabled() && !isSpeedPerVideoEnabled()` | not the effective mode by default (see "Per each channel") | VideoStateController.java:600-606 (same chain as above) | LIVE | Supposed to start every video at 1x, but the last chosen speed actually carries over to later videos until the app process restarts (`isAllSpeedEnabled() \|\| item.channelId != null ? speed : 1.0f`, where `speed` is the in-memory `mSpeed` that `onSpeedChanged` keeps updating). | The checked-state test ignores per-channel, so on a default install **both "None" and "Per each channel" render checked** (`DialogRowAdapter` uses each item's own `isSelected()` until a tap). The label is misleading. |
| Remember speed: "Same on all videos" | `PlayerData#isAllSpeedEnabled` (:287) / `#setAllSpeedEnabled(true)` (clears the other two) | false | VideoStateController.java:605-606; also `restoreState` keeps `mSpeed` across launches only when this is true | LIVE | Every video starts at the last speed you picked, even after a restart. | Mutually exclusive with the others (the setter clears them). |
| Remember speed: "Per each video" | `PlayerData#isSpeedPerVideoEnabled` (:298) / `#setSpeedPerVideoEnabled(true)` | false | VideoStateController.java:605 (`state.speed` from `VideoStateService`); the gear speed pick also saves it, MobilePlaybackActivity.java:6711 | LIVE | Each video remembers its own speed with its watch position. | A video with no saved state falls back to the in-memory last speed, not 1x. |
| Remember speed: "Per each channel" | `PlayerData#isSpeedPerChannelEnabled` (:650) / `#setSpeedPerChannelEnabled(option.isSelected())` (:654) | **true** (blob index 56). This is the effective default | `PlayerData.getSpeed(channelId)` :609 via VideoStateController.java:604; `onSpeedChanged` → `setSpeed(channelId, speed)` :626 stores it per channel | LIVE | Each channel remembers its own speed; channels you never changed play at 1x. | Shown as a radio option, but the setter reads `isSelected()`, which is always true from a radio tap. |
| Misc: "Long speed list" (checkbox) | `PlayerTweaksData#isLongSpeedListEnabled` (:427) / `#setLongSpeedListEnabled` (:431, clears "Extra long") | **true** | `AppDialogUtil.createSpeedListCategory`, used by this sub-page and by the player's gear > Speed > "More speeds" (MobilePlaybackActivity.java:6695 `openPlayerOption(R.id.action_video_speed, true)` → `VideoStateController.onButtonLongClicked` :272 → `onSpeedLongClicked`) | LIVE | Uses the finer 26-step 0.25-4x list in the speed pickers. | Mutually exclusive with "Extra long". It does not change the gear sheet's own 8 presets (0.25-2x, hard-coded at MobilePlaybackActivity.java:6659). The Misc header is the generic "Misc" (`player_other`). |
| Misc: "Extra long speed list" (checkbox) | `PlayerTweaksData#isExtraLongSpeedListEnabled` (:437) / `#setExtraLongSpeedListEnabled` (:441, clears "Long") | false | same as above | LIVE | Uses an 80-step 0.05-4x list in the speed pickers. | Mutually exclusive with "Long"; both off gives the short 16-value list. |

### Audio language

| Row | Getter/Setter | Default | Phone reader (file:line) | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Audio language (radio): "Original", then recently picked languages, then every `Locale` display language A-Z | `PlayerData#getAudioLanguage` (:688) / `#setAudioLanguage` (:692, also pushes to `getLastAudioLanguages`) | The device's current language (`LocaleUtility.getCurrentLanguage`) | None. No code outside the dialog factory reads `getAudioLanguage()`; the reader was in the deleted ExoPlayer `TrackSelectorManager`. Media3 hard-codes "prefer the original track" (Media3PlayerController.java:799 `setPreferOriginalAudio(true)`), and the persisted audio track (`getFormat(TYPE_AUDIO)`) overrides it | DEAD | Nothing on the phone: dubbed videos start on the original track, or on the language last picked in the player's Audio track sheet. | No side effects. **The player's equivalent is** gear > Audio track (shown only on multi-language videos), which persists via `PlayerData.setFormat(audio)`. The default (the device language) is not what the phone does (it plays the original). |

### Audio delay

| Row | Getter/Setter | Default | Phone reader (file:line) | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Audio delay: "Enable" (checkbox) | `PlayerData#isAudioDelayEnabled` (:661) / `#setAudioDelayEnabled` | false | None: no reader outside AppDialogUtil and the unreachable HQDialogController (it lived in the vendored ExoPlayer audio renderer) | DEAD | Nothing on the phone. | onSelect does nothing while the delay is 0, so the box will not tick. |
| Audio delay: "Audio delay" (checkbox that opens a "Audio delay seconds" number dialog) | `PlayerData#getAudioDelayMs` (:670) / `#setAudioDelayMs` (+`setAudioDelayEnabled(delay != 0)`) | 0 ms | None (as above) | DEAD | Nothing on the phone. | **onSelect closes the whole Settings flow** (`AppDialogPresenter.closeDialog()` → `MobileAppDialogActivity.finish` ends every level) before showing the edit dialog. |

### Volume

| Row | Getter/Setter | Default | Phone reader (file:line) | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Volume (radio): "0%"…"100%" in steps of 5 | `PlayerData#getPlayerVolume` (:544) / `#setPlayerVolume` | 1.0 (100%) | VideoStateController.java:634 `restoreVolume` ← `restoreState` (:457) ← `onVideoLoaded` (:180, fired by Media3PlayerController.java:1189) → MobilePlaybackActivity.java:7985 `setVolume` → Media3PlayerController.java:1132 (`mUserVolume × loudness gain`) | LIVE | Plays every video at this fraction of full volume, on top of the phone's own volume and YouTube's loudness normalization. | Older 105-300% "boost" values are clamped to 100%. Applies from the next video loaded. Not in the gear sheet (hardware volume keys are the phone's control). The label "Volume" invites confusion with the system volume; "Player volume" would be clearer. |

> Slice notes (OK button behavior to Sleep timer):

Paths: `common/…/` = `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/`, `mobile/…/` = `smarttubetv/src/stmobile/java/com/newtube/mobile/`. Defaults come from `PlayerData.restoreState()` (`common/…/prefs/PlayerData.java:860-896`) / `PlayerTweaksData.restoreState()`. Labels come from `common/src/main/res/values/strings.xml` unless noted.

Facts used throughout:
- **Nothing calls `PlaybackPresenter.onKeyDown`** (`common/…/app/presenters/PlaybackPresenter.java:389`). So every controller `onKeyDown` branch is DEAD on the phone, even with a hardware keyboard.
- **Gone with the TV UI:** the old readers of seek-confirm, remaining/ending time, seek increment and pixel ratio were `tv/ui/mod/leanback/playerglue/tweaks/PlaybackTransportRowPresenter.java` and `tv/ui/playback/PlaybackFragment.java` (check with `git grep … tv-legacy`). Both files were deleted.
- **Phone time label:** always "position / duration" (`mobile/…/ui/playback/MobilePlaybackActivity.java:4066-4067`). `updateEndingTime()` is an empty stub at `:5899`.

### OK button behavior

| Row | Getter/Setter | Default | Phone reader (file:line) | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| OK button behavior: Only UI / UI and pause / Only pause / Toggle speed on/off (radio) | `PlayerData#getOKButtonBehavior` / `#setOKButtonBehavior` (`OK_ONLY_UI=0`, `OK_UI_AND_PAUSE=1`, `OK_ONLY_PAUSE=2`, `OK_TOGGLE_SPEED=3`) | 0 = Only UI (`PlayerData.java:860`) | Only reader: `common/…/app/models/playback/controllers/PlayerUIController.java:792` `handleConfirmKey`, which runs only from `PlayerUIController.onKeyDown:131-140` (no caller on the phone). The phone has no OK/D-pad-center route into the presenter. | DEAD | Nothing on the phone. It chose what the remote's OK/center key did while the TV player's controls were hidden. | Plain setter, no side effects. Not in the gear sheet. **Overlap:** `GeneralSettingsPresenter.java:370-371` writes the same pref as a checkbox (OK → toggle speed). That row belongs to the General audit and is DEAD for the same reason. |

### Auto-hide UI

| Row | Getter/Setter | Default | Phone reader (file:line) | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Auto-hide UI: Never, 1 sec … 15 sec (radio, 16 options) | `PlayerData#getUiHideTimeoutSec` / `#setUiHideTimeoutSec` (`AUTO_HIDE_NEVER=0`) | 3 sec (`PlayerData.java:861`) | `PlayerUIController.java:677-678` `enableUiAutoHideTimeout()`. Path: `MobilePlaybackActivity.showControlsInternal` → `mPresenter.onControlsShown(true)` (`MobilePlaybackActivity.java:3373`) → `PlaybackPresenter.onControlsShown:438` → `PlayerUIController.onControlsShown:121-127`. The timer is also armed by `onNewVideo:113` and `onViewResumed:330`. When it fires, `mUiAutoHideHandler:72-87` calls `getPlayer().showOverlay(false)`, which reaches `MobilePlaybackActivity.showOverlay:5414` → `hideControls()`. The phone also runs its **own fixed 3.5 s timer** (`AUTO_HIDE_MS`, `MobilePlaybackActivity.java:171`, `armAutoHide:3403`, `onAutoHideTick:3476`). That timer ignores the pref and is re-armed on every tap, while the shared timer is not. | PARTIAL | The setting can only make the controls hide **sooner**. The shared timer counts from when the controls appeared and hides them even if you tapped since (it waits while you hold the seek bar, while playback is paused, or while an AppDialog is open). "Never" and long values cannot keep the controls up: the phone's own 3.5 s timer hides them anyway. | Plain setter. Not in the gear sheet. Today's default (3 s) is the timer that actually hides the controls, so this row is not inert. A phone-friendly fix is either: the phone's `armAutoHide` uses the pref as its delay and the shared timer is ignored on the phone, or the row is removed and both timers are pinned. |

### Seek behavior

| Row | Getter/Setter | Default | Phone reader (file:line) | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Seek behavior: Regular / With confirmation (pause while seeking) / With confirmation (play while seeking) (radio) | `PlayerData#isSeekConfirmPauseEnabled` / `#setSeekConfirmPauseEnabled`, `#isSeekConfirmPlayEnabled` / `#setSeekConfirmPlayEnabled` | both false = Regular (`PlayerData.java:864`, `:895`) | None. A repo-wide grep finds no reader outside `PlayerData` and the presenter. It was read only by the deleted TV `PlaybackTransportRowPresenter.java:485/501` (D-pad left/right seek). | DEAD | Nothing on the phone. Seek-bar drags and double-tap seeks commit at once and never wait for a confirmation. | Each option writes **both** booleans (a two-pref radio). No other side effects. Not in the gear sheet. |

### Preview while seeking

| Row | Getter/Setter | Default | Phone reader (file:line) | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Preview while seeking: Disabled / Single frame / Carousel (slow, by keyframes) / Carousel (fast, not precise preview) (radio) | `PlayerData#getSeekPreviewMode` / `#setSeekPreviewMode` (`SEEK_PREVIEW_NONE=0` … `CAROUSEL_FAST=3`) | 1 = Single frame (`PlayerData.java:863`) | `PlayerUIController.onMetadata:375` → `getPlayer().loadStoryboard()`, which is an **empty stub** on the phone (`MobilePlaybackActivity.java:5883`, "TODO Wave N"). The phone also turns storyboard fetching off: `VideoInfoService.setSkipStoryboardEnrichment(true)` (`mobile/…/MobileMainApplication.java:658-663`, "the touch UI has no seek-preview thumbnails yet"). The only other read is `AppDialogUtil.java:1089`, a warning toast in the Seek interval row. | DEAD | Nothing on the phone. The seek bar never shows preview thumbnails, whatever this is set to. | Plain setter. Not in the gear sheet. The modes describe the TV's D-pad carousel and mean nothing on touch. If the phone ever builds a storyboard preview, a plain On/Off would fit better. |

### Seek interval (from `AppDialogUtil.appendSeekIntervalDialogItems(…, false)`, `common/…/utils/AppDialogUtil.java:1082-1100`)

| Row | Getter/Setter | Default | Phone reader (file:line) | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Seek interval: 1 / 2 / 3 / 5 / 7 / 10 / 15 / 20 / 30 / 60 sec (radio, 10 options) | `PlayerData#getSeekIncrementMs` / `#setSeekIncrementMs` | 10 000 ms = 10 sec (`PlayerData.java:896`) | None. A repo-wide grep finds the getter read only by the dialog's checked-state test (`AppDialogUtil.java:1096`). The phone's double-tap skip is **hard-coded at 10 s** in the layout (`smarttubetv/src/stmobile/res/layout/activity_mobile_playback.xml:74` `app:yt_seekSeconds="10"`), and nothing sets it in code. Keyboard arrows on the seek bar use `PlayerTimeBar.positionIncrement():978` (duration / key count), not this pref. | DEAD | Nothing on the phone. Double-tap always skips 10 s. | onSelect: `setSeekIncrementMs`. If Preview mode is "Carousel (slow)" it also shows a "not compatible" toast (`Utils.showNotCompatibleMessage`, `AppDialogUtil.java:1089-1091`). `closeOnSelect=false` here, so the dialog stays open. Not in the gear sheet. **Cheap to make LIVE:** the TV fed this exact pref to its double-tap overlay (`tv-legacy` `PlaybackFragment.java:575` `.seekSeconds(getSeekIncrementMs()/1000)`). The phone uses the same `YouTubeOverlay`, so wiring it would make this a real "double-tap skip" setting. |

### Show ending time in controls bar

| Row | Getter/Setter | Default | Phone reader (file:line) | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Show ending time in controls bar: Disabled / Show remaining time in controls bar / Show ending time in controls bar (radio) | `PlayerData#isRemainingTimeEnabled` / `#setRemainingTimeEnabled`, `#isEndingTimeEnabled` / `#setEndingTimeEnabled` | remaining = **true**, ending = false, so "Show remaining time" is checked (`PlayerData.java:866`, `:893`) | None. Neither getter is read anywhere outside `PlayerData` and the presenter. `PlayerUIController.onVideoLoaded:303` / `onSeekEnd:313` call `getPlayer().updateEndingTime()`, which is an empty stub on the phone (`MobilePlaybackActivity.java:5899`). The phone's label is always "position / duration" (`:4066-4067`). It was read only by the deleted TV `PlaybackTransportRowPresenter.java:770-774`. | DEAD | Nothing on the phone. The controls show elapsed and total time whatever is picked, and the default ("remaining time") is not what the phone shows. | Each option writes both booleans. **Confusing label:** the category title repeats the third option's text ("Show ending time in controls bar"). Not to be confused with `isGlobalEndingTimeEnabled` (Main UI presenter, another fork). Not in the gear sheet. |

### Pixel ratio

| Row | Getter/Setter | Default | Phone reader (file:line) | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Pixel ratio: 1:1 (16:9 display) / 1.11111:1 (16:10 display) / 1.3333:1 (4:3 display) / 0.75:1 (64:27 display) / 0.7442:1 (43:18 display) / 0.7407:1 (12:5 display) (radio, hard-coded English labels) | `PlayerTweaksData#getPixelRatio` / `#setPixelRatio` | 1.0 = 1:1 (`PlayerTweaksData.java:733`) | None. A repo-wide grep finds no reader outside `PlayerTweaksData` and the presenter. It does not even reach the no-op `setAspectRatio`: it was read only by the deleted TV `PlaybackFragment.java:552` `setPixelRatio(...)`. | DEAD | Nothing on the phone. It stretched the picture for non-square-pixel TV panels and projectors, which has no meaning on a phone screen. | Plain setter. Not in the gear sheet (the gear's More → Zoom row is a different pref, video zoom). The labels are hard-coded strings that are not translated (`PlayerSettingsPresenter.java:321-328`). |

### Sleep timer (from `AppDialogUtil.createSleepTimerCategory`, `common/…/utils/AppDialogUtil.java:969-984`)

| Row | Getter/Setter | Default | Phone reader (file:line) | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Sleep timer: Disabled, 0.5 hours, 1 hour, 1.5 hour, 2 hours … 10 hours (radio, 21 options in 0.5 h steps; `Helpers.range` includes both ends) | `PlayerData#getSleepTimerHours` / `#setSleepTimerHours` | 0 = Disabled (`PlayerData.java:885`) | `ctrl/VideoLoaderController.java:397-410` `checkSleepTimer()`. Path: `VideoLoaderController.onTickle:318-319` ← `PlaybackPresenter.onTickle:399` ← `TickleManager` (ticks once a minute). The presenter subscribes in `PlaybackPresenter.onEngineInitialized:326`, which the phone calls (`MobilePlaybackActivity.java:1294`). The start time `mSleepTimerStartMs` is reset only in `onInit:124`, `onEngineInitialized:165` and `onKeyDown:305`. The last one is DEAD on the phone. `onNewVideo` does **not** reset it, so autoplay, Next and related-video taps inside the open player keep the same count. The phone calls `onEngineInitialized` when the player screen creates its engine (`createPlayerObjects`, :1294) and on `reloadPlayback` (:7935, which ErrorFixer uses for recovery). | PARTIAL | Pauses playback once N hours have passed since the player was opened, counting across autoplayed videos. When it fires it pauses, sets the player title to "Sleep timer (N hours)", shows the controls and stops keeping the screen on (`Helpers.enableScreensaver`). | Plain setter. **What doesn't work on the phone:** the TV's "no remote key pressed for N hours" half. Touching the screen never resets the count, so the timer counts from when the player opened, not from when you last used the phone. The count also restarts silently whenever the engine is recreated or reloaded, for example by error recovery or by reopening the player. After it fires, `mSleepTimerStartMs` is not reset, so pressing play pauses again within a minute until the engine is recreated. **Not in the player:** the only in-player copy is in the TV HQ dialog (`HQDialogController.java:57/145`), which nothing on the phone opens. **Label bug:** `getQuantityString(R.plurals.hours, (int) hours, …)` truncates the number, so 1.5 shows as "1.5 hour". A phone user would expect a countdown in the player menu ("stop in 30 min / end of video"). The core behaviour, pause N hours after the player opened, does work. (Corrected after a codex review: an earlier draft said each new video restarted the count.) |

### Misc (checkbox list)

Path abbreviations: `common/…/` = `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/`, `ctrl/` = `common/…/app/models/playback/controllers/`, `mobile/…/` = `smarttubetv/src/stmobile/java/com/newtube/mobile/`. Prefs: PTD = `common/…/prefs/PlayerTweaksData`, PD = `PlayerData`, SD = `SearchData`, GD = `GeneralData`; "idx N" = slot in the pref blob's restore list. Header `R.string.player_other` = **"Misc"**; built by `PlayerSettingsPresenter.appendMiscCategory` (lines 368-517), an `appendCheckedCategory`. Every onSelect here is a plain set + persist: no restart flag, no dialog close, no other pref reset. PTD setters persist on a 10 s delay (`persistData` -> `postDelayed(…, 10_000)`); PD/SD/GD persist via their own `persistState`/`persistData`.

Established for this slice: `PlaybackPresenter.onKeyDown` has no caller, so a reader inside a controller's `onKeyDown` is DEAD even with a hardware keyboard. The phone's `setZoomPercents` / `setVideoGravity` / `setSeekPreviewTitle` / `focusSuggestedItem` / `showSuggestions` are no-ops (MobilePlaybackActivity.java:5640-5660, 5757, 5822, 8012-8036). The phone sends `onButtonLongClicked` only for `action_repeat` and `action_video_speed`, and `onButtonClicked` only for the overlay/More actions and like/dislike/subscribe. It never sends `action_chat` long-click, `action_search`, `action_channel` or a plain `action_video_speed` click.

| Row | Getter/Setter | Default | Phone reader (file:line) | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Horizontally scrolled Suggestions | PTD#isSuggestionsHorizontallyScrolled / #setSuggestionsHorizontallyScrolled | false (idx 56) | `ctrl/SuggestionsController.java:952` in `appendSuggestions` (:898), reached from `updateSuggestions` (:801), which runs when the phone loads a video's metadata. When on, every untitled /next group gets the same id (`"Suggestions".hashCode()`). The phone keys its Up next model by group id (`mobile/…/ui/playback/MobilePlaybackActivity.java:5545-5583`, a `LinkedHashMap` at :323): the APPEND path de-duplicates within a group, and `rebuildRelatedList` flattens groups in insertion order with no de-duplication across groups. | PARTIAL | There are no horizontal rows on the phone. The only effect: when YouTube returns more than one untitled suggestion group, "on" merges them into one de-duplicated block at the first group's position, and "off" lists them separately, so a video in both appears twice. That is rare, because continuations already keep their group's id (SuggestionsController:583). | None. Corrected from DEAD after a codex review. The label promises a TV layout that the phone doesn't have. |
| Don't resize video to fit dialog | PTD#isDontResizeVideoToFitDialogEnabled / #setDontResizeVideoToFitDialogEnabled | false (idx 55) | `common/…/app/models/playback/BasePlayerController.java:43` (`mFitVideoStart`) and `:70` (`mFitVideoFinish`), registered by `fitVideoIntoDialog()` (:383) as the AppDialog's onStart/onFinish. Phone triggers: More > Add to playlist (MobilePlaybackActivity:3731 -> `ctrl/PlayerUIController.java:253`), Speed sheet > More speeds (MobilePlaybackActivity:6695 -> `ctrl/VideoStateController.java:303`), the TV quality dialog (`ctrl/HQDialogController.java:47`). | PARTIAL | The video is never resized on the phone, because the zoom and gravity calls are no-ops (MobilePlaybackActivity:8012, :8033). The only effect: while unchecked, opening one of those reused dialogs from the player first hides the player controls (`showControls(false)`, BasePlayerController:51). | None. The label promises something the phone never does. Effectively a "hide controls behind sheets" toggle. |
| Audio focus (pause if other players detected) | PTD#isAudioFocusEnabled / #setAudioFocusEnabled | true (idx 54) | `mobile/…/player/Media3PlayerInitializer.java:496` `setupAudio` <- `createPlayer` (:315, called at :354/:380) <- `MobilePlaybackActivity.createPlayerObjects` (:1166/:1182, from onCreate :510 and `restartEngine` :7926) | LIVE | On: NewTube takes audio focus (`handleAudioFocus=true`), so it pauses or ducks when another app or a call plays sound. Off: it plays over other audio. | Read only when the player is built. A change applies the next time the player screen is created, not to the video already playing. |
| Auto volume adjustment | PTD#isPlayerAutoVolumeEnabled / #setPlayerAutoVolumeEnabled | true (idx 40) | `mobile/…/player/Media3PlayerController.java:1156` `updateLoudnessGain` <- `onAudioInputFormatChanged` listener (:157), on every audio format change | LIVE | Turns loud videos down to YouTube's normalized loudness, using each track's loudnessDb (`AudioLoudness.gain`, which only attenuates when db > 0). Quiet videos are not boosted. Off = raw track volume. | Applies at the next audio format change (new video, dub or quality switch). Ties to issue #15 (fixed in 95c38da4). |
| Audio time stretching (summary: "Keeps voice natural when changing speed. May significantly reduce performance on some devices.") | PTD#isAudioTimeStretchingEnabled / #setAudioTimeStretchingEnabled | true (idx 59) | `mobile/…/player/Media3PlayerController.java:1111` `withSpeed` <- `setSpeed` (:1068/:1070) and the press-and-hold 2x boost (:1090) | LIVE | On: changing speed keeps the voice's pitch. Off: pitch follows the speed (chipmunk or slow-motion voice). | Applies at the next speed change. Related to the gear sheet's Speed row (Speed sets the speed; this decides how it sounds). The "performance" warning is TV-era. |
| Use current section contents as a playlist | PTD#isSectionPlaylistEnabled / #setSectionPlaylistEnabled | `Utils.isEnoughRam()` (max heap > 350 MB; with `largeHeap=true` that is effectively true on phones) (idx 31) | `common/…/app/models/data/Video.java:986` `isSectionPlaylistEnabled(ctx)` <- `ctrl/SuggestionsController.java:1099` `appendSectionPlaylistIfNeeded` (called at :920 in `appendSuggestions`), :943 (drops /next's duplicate playlist row), :1186 | LIVE | When a video starts from a real playlist row (playlist page, Liked, Watch later), that row as loaded becomes the watch page's queue card and autoplay order. Off: the queue comes from YouTube's own /next playlist panel instead. Feed, search and history rows never count (NewTube's `isRealPlaylistSection`). | None. Subtle on the phone, because both paths show a queue card. Related to More > Queue, but that is a different control. |
| Switch to the next chapter by clicking on the notification | PTD#isChapterNotificationEnabled / #setChapterNotificationEnabled | false (idx 36) | `ctrl/SuggestionsController.java:1058` `startChapterNotificationServiceIfNeeded` <- `appendChaptersIfNeeded` (:1090, on metadata), `onControlsShown(false)` (:472; the phone calls `mPresenter.onControlsShown`, MobilePlaybackActivity:3399), `onSeekEnd` (:485). It shows `showChapterDialog` as an overlay AppDialog (rendered by `MobileAppDialogActivity`). | LIVE | At each chapter start, while the controls are hidden, a small sheet with the chapter's name pops up. Tapping it jumps to the next chapter. | None. A TV-style pop-up that has not been checked on a device. The phone already has chapter marks plus a Chapters list (#13). |
| Background play while searching/browsing a channel | SD#isTempBackgroundModeEnabled / #setTempBackgroundModeEnabled | false (SD idx 4) | Only `ctrl/PlayerUIController.java:935` `startTempBackgroundMode`, whose callers are `onSearchClicked` (:561, `action_search`) and `openChannel` (:1224, `action_channel`). The phone never dispatches either action (no `R.id.action_search` / `R.id.action_channel` in `mobile/`). Consumer `common/…/app/presenters/base/BasePresenter.java:128` is never armed. | DEAD | Nothing. On TV, opening search or a channel from the player sent the video to PiP and brought it back afterwards. The phone's mini player already keeps playing while you browse. | Duplicate: the same row also appears in Settings > Search (`SearchSettingsPresenter.java:100-102`, no phone guard). |
| Exit from the player: Double back | GD#getPlayerExitShortcut / #setPlayerExitShortcut (checked = `EXIT_DOUBLE_BACK`, unchecked = `EXIT_SINGLE_BACK`) | `EXIT_SINGLE_BACK` = unchecked (GD idx 58) | None. The only readers are the settings presenters. GeneralSettingsPresenter hides its own copy on the phone with the comment "read by nothing but the settings screens (phone Back is MobileActivity's)" (`GeneralSettingsPresenter.java:651-657`). | DEAD | Nothing. Phone Back minimizes or closes the player on its own logic. | Leftover duplicate of the row the phone already hides in General. |
| ~~Loop Shorts~~ | PTD#isLoopShortsEnabled | true (idx 44) | — | hidden on phone (`!PhoneUi.isEnabled()` at :409) | Not shown: the phone has no Shorts. | Not counted. |
| Place chat to the left | PTD#isChatPlacedLeft / #setChatPlacedLeft | false (idx 19) | Only `ctrl/ChatController.java:112/:120/:183`, inside `onButtonLongClicked(action_chat)`, which the phone never sends. The phone's live chat is `LiveChatSheet` (bottom sheet) fed through `setChatReceiver` (MobilePlaybackActivity:5904), with no side placement. | DEAD | Nothing. On TV it docked live chat to the left of the video. | None. |
| Place comments to the left | PTD#isCommentsPlacedLeft / #setCommentsPlacedLeft | false (idx 52) | `common/…/app/models/playback/BasePlayerController.java:66` passes it to `setVideoGravity`, which is a no-op on the phone (MobilePlaybackActivity:8033). `ctrl/ChatController.java:183` is reached only via the unsent `action_chat` long-click. Phone comments are `CommentsPanel` under the video. | DEAD | Nothing. On TV it put the comments panel left and pushed the video right. | None. |
| Disable suggestions | PTD#isSuggestionsDisabled / #setSuggestionsDisabled | false (idx 17) | `ctrl/SuggestionsController.java:931` in `appendSuggestions`: caps the rows to 0, or to 1 when the video has a playlist | LIVE | Empties the "Up next" related list under the video. A playlist's queue row survives, and chapters still show (they are appended separately). Autoplay still uses /next's next video (`nextMediaItem`). | Also reset to false when Child mode is turned off (`GeneralSettingsPresenter.java:761`). |
| Seek with number keys | PD#isNumberKeySeekEnabled / #setNumberKeySeekEnabled | true (PD idx 43) | Only `ctrl/PlayerUIController.java:825` `handleNumKeys` <- `onKeyDown` (:131) <- `PlaybackPresenter.onKeyDown` (:389), which has no caller | DEAD | Nothing. On TV, number keys 0-9 jumped to 0-90 % of the video. A phone hardware keyboard does not reach it either. | None. |
| Remember live stream position | PTD#isRememberPositionOfLiveVideosEnabled / #setRememberPositionOfLiveVideosEnabled | true (idx 46) | `ctrl/VideoStateController.java:351` in `resetPositionIfNeeded` (:333) <- `onNewVideo` (:69), which fires on every phone open | LIVE | Reopening a live stream that has a rewindable history (`isFullLive`) resumes where you left off instead of jumping to the live edge. | None. |
| Remember position of short videos (less than 5 min) | PTD#isRememberPositionOfShortVideosEnabled / #setRememberPositionOfShortVideosEnabled | false (idx 16) | `ctrl/VideoStateController.java:347` in `resetPositionIfNeeded` <- `onNewVideo` (:69) | LIVE | Off (default): videos shorter than 6 minutes always start from the beginning, like music. On: they resume like longer videos. | The label is wrong: the threshold is `MUSIC_VIDEO_MAX_DURATION_MS = 6 * 60 * 1000` (VideoStateController.java:23), not 5 min. |
| Revert old behavior of the speed button | PTD#isSpeedButtonOldBehaviorEnabled / #setSpeedButtonOldBehaviorEnabled | false (idx 23) | Only `ctrl/VideoStateController.java:295` `onSpeedClicked` <- `onButtonClicked(action_video_speed)` (:266). The phone sends only the long-click (MobilePlaybackActivity:6695), so the plain click never fires. | DEAD | Nothing. On TV it made the speed button open the speed list instead of toggling between 1x and the last speed. | The phone's Speed row opens its own sheet (`showSpeedSheet`). |
| Show clock in controls bar | PD#isClockEnabled / #setClockEnabled | true (PD idx 5) | `mobile/…/ui/playback/MobilePlaybackActivity.java` `updateClockVisibility` (the TV PlaybackFragment that read it was deleted) | LIVE | The time beside the title in fullscreen, hidden while the status bar shows its own. The phone row is Settings > Player > Fullscreen > Clock in fullscreen. | None. |
| Show quality info in the controls bar | PD#isQualityInfoEnabled / #setQualityInfoEnabled | true (PD idx 28) | None | DEAD | Nothing. The phone shows quality in the gear sheet's Quality row ("Auto (720p)") regardless of this setting. | None. |
| Add bitrate into the quality info | PTD#isQualityInfoBitrateEnabled / #setQualityInfoBitrateEnabled | false (idx 22) | None | DEAD | Nothing (depends on the dead row above). | None. |
| Sync focus between player button rows (summary: "This feature affects which player button will receive focus when navigating between player button rows") | PTD#isSyncRowButtonIndexEnabled / #setSyncRowButtonIndexEnabled | true (idx 41) | None | DEAD | Nothing. It is D-pad focus behaviour for the deleted Leanback player bar. | None. |
| Show player UI when switching to the next video | PTD#isPlayerUiOnNextEnabled / #setPlayerUiOnNextEnabled | false (idx 39) | `ctrl/VideoLoaderController.java:254` (`loadPrevious`) and `:272` (`loadNext`), reached from the phone's Prev/Next buttons (MobilePlaybackActivity:859/:867 -> `onPreviousClicked`/`onNextClicked`, :230/:240) and from autoplay `onPlayEnd` -> `loadNext` (:844/:858/:871/:879). Calls `showOverlay(true)`, which the phone implements (MobilePlaybackActivity:5414). | LIVE | When the next video starts (autoplay, or the Next and Previous buttons), the player controls appear instead of staying hidden. | None. Barely noticeable for Next/Prev taps, since the controls are already up. Visible on autoplay. |
| Enable player UI animations | PTD#isUIAnimationsEnabled / #setUIAnimationsEnabled | false (idx 34) | None | DEAD | Nothing. Phone motion is fixed (motion-polish work). | None. |
| Show likes/dislikes count | PTD#isLikesCounterEnabled / #setLikesCounterEnabled | true (idx 35) | `ctrl/SuggestionsController.java:1378` (`appendDislikes`) and `:1423` (`applyCachedDislikes`). Off nulls `video.likeCount` and `dislikeCount`, but the phone's bind only writes non-empty counts (MobilePlaybackActivity:6896) and refills the like count from metadata (:7057-7059). | PARTIAL | Off does not hide the like count on the phone. Its only real effect is skipping the Return YouTube Dislike fetch. | Bug: with RYD on and this off, the dislike number falls back to /next's estimate (:7061-7063), the fake number the RYD opt-in was meant to avoid. |
| Dislike counts (Return YouTube Dislike) | PTD#isReturnYouTubeDislikeEnabled / #setReturnYouTubeDislikeEnabled | false (idx 61) | `ctrl/SuggestionsController.java:1388`, `:1424` (fetch and cache) plus `mobile/…/ui/playback/MobilePlaybackActivity.java:7120` `showsDislikeCount()` (the dislike TextView's visibility, :6901, :6932, :7061) | LIVE | Shows a dislike number next to the thumb, fetched from the third-party Return YouTube Dislike service. Off: thumb only, as on YouTube. | Privacy-relevant: it sends the video ID to a third-party host. Applies at the next video bind. |
| Show button tooltips | PD#isTooltipsEnabled / #setTooltipsEnabled | true (PD idx 41) | None | DEAD | Nothing. These were TV player-bar hover tooltips. | None. |
| Long press this button for additional options | PTD#isButtonLongClickEnabled / #setButtonLongClickEnabled | true (idx 24) | None | DEAD | Nothing. | The label is a fragment of tooltip text and reads as an instruction, not a setting. |
| Show icon on the channel button | PTD#isRealChannelIconEnabled / #setRealChannelIconEnabled | true (idx 20) | `ctrl/PlayerUIController.java:380` in `onMetadata`, which calls `setChannelIcon` only when on. But the phone paints the avatar unconditionally in `bindWatchMetadata` (MobilePlaybackActivity:7054). | DEAD | Nothing: the channel avatar on the watch page shows either way. | Off doesn't hide anything. On can only make the avatar appear a moment earlier (the controller path at `onMetadata` runs before the phone's own metadata bind), which is not user-meaningful. Noted from the codex review. |
| Queue respects playback mode | PTD#isQueueRespectsPlaybackMode / #setQueueRespectsPlaybackMode | false (idx 60) | `ctrl/VideoLoaderController.java:857` (mode CLOSE) and `:870` (mode PAUSE) in `onPlayEnd`, at the end of every phone video | LIVE | When the playback mode is "close" or "pause after each video" and a queue ("Play next" list) has more items: off (default) keeps playing the queue anyway; on stops or closes as the mode says. | None. Interacts with Playback mode (Settings and More > Repeat). |

### Developer options (checkbox list)

Path shorthand: `common/…/` = `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/`, `mobile/…/` = `smarttubetv/src/stmobile/java/com/newtube/mobile/`, `MSC/…/` = `MediaServiceCore/youtubeapi/src/main/java/com/liskovsoft/youtubeapi/`, `SM/…/` = `SharedModules/sharedutils/src/main/java/com/liskovsoft/sharedutils/`. `PSP` = `common/…/app/presenters/settings/PlayerSettingsPresenter.java`, `PTD` = `common/…/prefs/PlayerTweaksData.java`, `VLC` = `common/…/app/models/playback/controllers/VideoLoaderController.java`, `EFC` = `…/controllers/ErrorFixerController.java`.

Source: `PSP.appendDeveloperCategory` (PSP:519-690), header `R.string.player_tweaks` = **"Developer options"**, built with `appendCheckedCategory`, so every row is a checkbox that writes as soon as it is tapped. Rows marked "restart toast" set `mRestartApp = true`. When the Player dialog closes, `mOnFinish` (PSP:36-41) then shows "Please restart the app to apply these settings".

Phone call chains used below:
- **Open chain:** `MobilePlaybackActivity:1294 mPresenter.onEngineInitialized()` → `VLC.onEngineInitialized:157` → `loadFormatInfo:463` → `processFormatInfo:503`. That picks the source route and calls the phone engine (`MobilePlaybackActivity:7775-7806` → `Media3PlayerController`).
- **Stall chain:** `Media3PlayerController:1265 onBuffering` → `EFC.onBuffering:170` → `BufferingDetector` (20 s of stalls in a 60 s window) → `EFC.onLongBuffering:135`.
- **Error chain:** `Media3PlayerController:257 onEngineError` → `EFC.onEngineError:116` → `runEngineErrorAction:321`.

| Row | Getter/Setter | Default | Phone reader (file:line) | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Disable playback notifications — *"Hides track change notifications. Could be useful on AOSP-based firmware."* | `PTD#isPlaybackNotificationsDisabled` / `#setPlaybackNotificationsDisabled` (tweaks blob idx 11) | off | none. The only call site is PSP:525. | DEAD | Nothing. The track-change toasts it hid lived in the deleted TV ExoPlayer controller. | none |
| Disable automatic network error fixing — *"You probably need to enable this option if you're using a VPN"* | `PTD#isNetworkErrorFixingDisabled` / `#setNetworkErrorFixingDisabled` (idx 51) | off | `EFC:156` in `onLongBuffering` (stall chain) gates `lowerVideoQuality()` (`EFC:1437`) | LIVE (narrow) | When on, playback no longer drops one quality step on its own after about 20 s of buffering within a minute. | The label over-claims: 403/route recovery, fresh-URL reminting and client rotation (`runEngineErrorAction`) ignore this flag. It turns off only the stall quality rescue. |
| Oculus Quest fix | `PTD#isOculusQuestFixEnabled` / `#setOculusQuestFixEnabled` (idx 47) | `Utils.isOculusQuest()`, so off on phones | `common/…/misc/MotherActivity.java:70` (read in `onCreate`) → `:87-88` `setRequestedOrientation(LANDSCAPE)` (not on API 26); `:356-357` `finish()` in `onBackPressed`. Every phone screen extends `MobileActivity` → `MotherActivity` (`MobileActivity.java:54` calls `super.onCreate`). | LIVE (harmful) | When on, every screen opens locked to landscape, and Back closes the screen outright wherever `Activity.onBackPressed` is reached. | Restart toast (applies to each screen created afterwards). A trap on a phone: the browse screens stay landscape-locked. The player frees its own orientation later (`MobilePlaybackActivity:3596`). |
| Prefer IPv4 DNS — *"Could fix situations when the app isn't working at all. Note. May cause hangs and crashes (especially on Android 8 devices or Dune HD)"* | `PTD#getPreferredDnsType` == `DNS_TYPE_IPV4` / `#setPreferredDnsType(IPV4 or SYSTEM)`, which delegates to `GlobalPreferences` key `preferred_dns_type` | **on** (`GlobalPreferences.getPreferredDnsType` defaults to `DNS_TYPE_IPV4`, `SM/…/prefs/GlobalPreferences.java:154`) | `SM/…/okhttp/OkHttpCommons.java:304-311` `setupBuilder` → `preferIPv4Dns` (`OkHttpDNSSelector IPV4_FIRST`, :370). Built once by `OkHttpManager.getClient():204`. The InnerTube Retrofit client derives from it (`MSC/…/../googlecommon/common/helpers/RetrofitOkHttpHelper.kt:89`). | PARTIAL | Resolves YouTube API hosts (/player, /browse, /next) IPv4-first. Video traffic is not affected: `mobile/…/player/MediaHttpClient.java:82` swaps in `Dns.SYSTEM` plus its own IPv4 heuristic, and Cronet ignores it. | Restart toast. The shared client freezes its DNS at first build, so a restart really is needed. Unchecking writes SYSTEM. `BrowsePresenter:1799-1804` forces IPV4 and restarts the app when a feed fails with "No address associated with hostname". Checked by default, but the label reads like an opt-in. |
| Prefer Google DNS — *(shows the IPv4 summary text: "Could fix situations when the app isn't working at all…")* | `PTD#getPreferredDnsType` == `DNS_TYPE_GOOGLE` / `#setPreferredDnsType(GOOGLE or SYSTEM)` | off | Same as above: `OkHttpCommons.java:312-315` → `forceGoogleDns` (`PublicDnsResolver.google()`, :385) | PARTIAL | Resolves YouTube API hosts through Google's public DNS instead of the phone's. Video downloads are unaffected (same reasons as above). | Restart toast. Writes the same int as the IPv4 row, so the two are mutually exclusive, but the other checkbox does not refresh until the dialog is reopened. Unchecking Google writes SYSTEM, which also turns off the IPv4 default. Wrong summary string (PSP:549 reuses `prefer_ipv4_desc`). |
| Audio sync fix — *"An alternative way to synchronize audio/video. It's considered obsolete, but in some cases, may help."* | `PTD#isAudioSyncFixEnabled` / `#setAudioSyncFixEnabled` (idx 8) | off | none (only PSP:559). `Media3PlayerInitializer` does not read it. | DEAD | Nothing. This was a TV ExoPlayer-2 audio-sink option. | none |
| Ambilight/Aspect ratio/Video scale/Screenshots fix — *"Fixes absent bias lighting. Fixes incorrect aspect ratio or video scale. Fixes blank screenshots. Affects performance!"* | `PTD#isTextureViewEnabled` / `#setTextureViewEnabled` (idx 5) | off | Its intended reader (surface type) is gone: the phone always renders on a code-managed TextureView (`smarttubetv/src/stmobile/res/layout/activity_mobile_playback.xml:51`, `MobilePlaybackActivity#setupVideoSurface`). The only reader is `common/…/controllers/SponsorBlockController.java:401` in `applyActions` (← `skipSegment:240` ← `onVideoLoaded:96`). | PARTIAL (side effect only) | Changes nothing about the picture. While on, SponsorBlock silently doesn't skip a segment when less than 10 s of it is left to skip. The check runs about 0-2 s into the segment, so in practice this means segments shorter than about 10 s. It was a workaround for a TV TextureView seek bug. | Selecting it also turns off "Tunneled video playback" (PSP:565-568), a dead pref. The pair is mutually exclusive. Its only real effect is harmful. |
| Unlock high bitrate 1080p vp9 formats 💎 (`TrackSelectorUtil.HIGH_BITRATE_MARK` diamond appended) | `PTD#isHighBitrateFormatsEnabled` / `#setHighBitrateFormatsEnabled`, which delegates to `MediaServiceData` bit `FORMATS_EXTENDED_HLS` | off (`MediaServiceData` formats default `DASH\|URL`, `MSC/…/service/internal/MediaServiceData.java:306`) | (a) `VLC:611` in `processFormatInfo` (open chain): DASH answers that `hasExtendedHlsFormats()` (HLS URL + 1080p top rung, not live) open as `player.openMerged(...)` → `Media3PlayerController.openMerged:462` (DASH+HLS merged source). `VLC:1135` excludes them from next-video prebuild. (b) `MSC/…/videoinfo/V2/VideoInfoService.java:4587` `shouldObtainExtendedFormats` ← `applyFixesIfNeeded` (`getVideoInfo:1703`) → `applyFixesAsync:4469` (the phone sets `setPreferNoPotClient(true)`, `MobileMainApplication:299`): an extra deferred iOS /player request for 1080p answers that lack HLS. (c) `EFC:432-433` turns it off on a 429/500 error. | LIVE (conditional) | Adds YouTube's high-bitrate HLS (iOS) formats to the stream mix when the /player answer offers them, at the cost of an extra background /player request. | The auto-off on 429/500 is silent. The label ("1080p vp9") does not describe what the phone does (merged DASH+HLS). Whether `hasExtendedHlsFormats()` is ever true on phone answers depends on the winning client returning an HLS URL for VOD (see notes). |
| Unlock high bitrate mp4a formats | `PTD#isUnsafeAudioFormatsEnabled` / `#setUnsafeAudioFormatsEnabled` (idx 42) | **on** | none (only PSP:578). `Media3TrackAdapter` does no "unsafe audio" filtering. | DEAD | Nothing. The audio filter it unlocked belonged to the TV ExoPlayer track selector. | Checked by default, so it looks active. |
| Force legacy codecs (720p) — *"Significantly improves performance on low-end devices. Maximum resolution is 720p."* | `PlayerData#isLegacyCodecsForced` / `#setLegacyCodecsForced` (PlayerData blob idx 24) | off | `VLC:917` `acceptAdaptiveFormats` returns false when the answer has URL formats, and `VLC:626` skips the HLS-VOD route, so `processFormatInfo` reaches `VLC:629-631` `player.openProgressive(...)` (open chain). | LIVE | Plays the old single-file MP4 stream instead of adaptive DASH/SABR/HLS whenever the answer carries one: no quality switching, low resolution (the label promises 720p; in practice YouTube now mostly serves 360p progressive). | Choosing any preset in Player → Video presets clears this flag silently (`common/…/utils/AppDialogUtil.java:359-361` `setFormat`). |
| Live stream fix (1080p) — *"Warn, this tweak disables the rewinding of streams. Significantly improves live stream performance on low-end devices. Maximum resolution is 1080p."* | `PTD#isHlsStreamsForced` / `#setHlsStreamsForced` (idx 10) | off | `VLC:601` live branch of `processFormatInfo` (open chain): picks `openHlsUrl` over the default `openDashUrl` when the live answer has HLS. Also `VLC:921/936/944` (`acceptAdaptiveFormats`, `acceptDashLive`). | LIVE | Plays live streams from YouTube's HLS manifest instead of the DASH manifest the phone uses by default. | The setter clears "Live stream fix (4K)" (PTD:236-237). The two rows are mutually exclusive, but the other checkbox only refreshes when the dialog is reopened. |
| Live stream fix (4K) — *"Warn, this tweak disables the rewinding of streams. Significantly improves live stream performance. Maximum resolution is 4K."* | `PTD#isDashUrlStreamsForced` / `#setDashUrlStreamsForced` (idx 28) | off | `VLC:932` in `acceptAdaptiveFormats`, which cannot change the outcome. Every live answer with a DASH or HLS URL already takes the earlier live branch (`VLC:593-605`, DASH URL by default), so `:932` is only evaluated when there is no DASH URL, and its condition then requires one. | DEAD | Nothing of its own: the phone already plays every live stream from the DASH manifest URL, which is what this tweak forced on the TV. | Checking it clears "Live stream fix (1080p)" (PTD:246-247). That is its only real effect, and it brings live back to the default DASH route. |
| Disable buffer on streams — *"Fix for situations when the stream too far behind. Note: the stream may start to lag."* | `PTD#isBufferOnStreamsDisabled` / `#setBufferOnStreamsDisabled` (idx 30) | off | `common/…/controllers/VideoStateController.java:724` `getLiveBuffer()` ← `restorePosition:529-531` (on video load), `restoreSpeedAndPositionIfNeeded:596` (live end), `onNextClicked:90-91` (phone Next button, `MobilePlaybackActivity:867`) | LIVE | Starts (and re-syncs) live streams at the live edge instead of 15 s behind it. | none |
| Unlock all video formats — *"On some devices the firmware incorrectly reports some formats as unsupported, even if they are. (e.g. 1080p smart TVs tend to report 4k as not supported)."* | `PTD#isAllFormatsUnlocked` / `#setAllFormatsUnlocked` (idx 27) | off | `common/…/utils/AppDialogUtil.java:327-331` `fromPresets` ← `createVideoPresetsCategory:307` (Player → Video presets list) | PARTIAL | Only shows the VP9/AV1 presets the phone says it cannot decode in the Video presets list. It does not make the player decode them: media3 track selection still follows the decoder's real capabilities. | none |
| Alt presets behavior (limit bandwidth) — *"The app will try to maintain bandwidth corresponding to selected preset instead of matching between resolution, fps and codec."* | `PTD#isAltPresetsEnabled` / `#setAltPresetsEnabled` (idx 15) | off | none (only PSP:612) | DEAD | Nothing. This was TV track-selector logic. | none |
| Amlogic 1080p@60fps fix — *"Frame drop fix on Amlogic-based devices."* | `PTD#isAmlogicFixEnabled` / `#setAmlogicFixEnabled` (idx 0) | off | none (only PSP:617) | DEAD | Nothing (a TV-chipset renderer workaround). | none |
| Tunneled video playback (Android 5+) — *"NOTE: pause may not work properly! Tunneled video playback promises benefits such as better audio/video synchronization (AV sync) and smoother playback. Required Android 5+"* | `PTD#isTunneledPlaybackEnabled` / `#setTunneledPlaybackEnabled` (idx 12) | off | none (only PSP:628). `Media3PlayerInitializer` never enables tunneling, and the TextureView surface could not tunnel anyway. | DEAD | Nothing. | Selecting it turns off the "Ambilight…" pref (PSP:623-626), which removes that pref's SponsorBlock side effect. That is the row's only observable effect. |
| Disable snap to vsync — *"This option disables aligning frames with the display's vertical sync signal…"* | `PTD#isSnappingToVsyncDisabled` / `#setSnappingToVsyncDisabled` (idx 2) | off | none (only PSP:633) | DEAD | Nothing. | none |
| Skip codec profile level check — *"Don't check codec support when starting a video. Could be helpful on buggy firmware."* | `PTD#isProfileLevelCheckSkipped` / `#setProfileLevelCheckSkipped` (idx 3) | off | none (only PSP:638) | DEAD | Nothing. | none |
| Force SW video decoder — *"Could play almost any video, but performance is very poor."* | `PTD#isSWDecoderForced` / `#setSWDecoderForced` (idx 4) | off | Only `EFC:469-470` (error chain, video renderer error), which *clears* the flag and keeps the engine restart. Nothing in `Media3PlayerInitializer` selects a software decoder (it uses `setEnableDecoderFallback(true)`, :333). | DEAD | Never forces software decoding. The only code that reads it switches it back off after a decoder error. Incidental (from the codex review): with the flag on, that video-renderer error restarts the engine instead of reloading the source. | none |
| Frame drop fix #1 — *"Fix lags on Sony TV and some other devices. Note: possible problems with audio synchronization."* | `PTD#isSonyFrameDropFixEnabled` / `#setSonyFrameDropFixEnabled` (idx 29) | off | none (only PSP:648) | DEAD | Nothing. | none |
| Frame drop fix #2 — *"Intended for Amazon Stick devices. May work on other devices too."* | `PTD#isAmazonFrameDropFixEnabled` / `#setAmazonFrameDropFixEnabled` (idx 1) | off | none (only PSP:653) | DEAD | Nothing. | none |
| Keep finished activities | `PTD#isKeepFinishedActivityEnabled` / `#setKeepFinishedActivityEnabled` (idx 9) | off | none (only PSP:657) | DEAD | Nothing (a TV ViewManager crash workaround for projectors). | none |
| Disable Channels service | `GlobalPreferences#isChannelsServiceEnabled` (negated) / `#setChannelsServiceEnabled(!checked)`, key `enable_channels_service` | unchecked (service "enabled" = true) | none. No other reader in common/, stmobile/, SharedModules/ or MSC. The leanback channels service is not in the phone manifest, and `Utils.updateChannels` is a NOP (`common/…/utils/Utils.java:986-987`). | DEAD | Nothing. This was the Android TV home-screen channels. | none |
| Hide Settings section (dangerous!) | `SidebarService#isSettingsSectionEnabled` (negated) / `#enableSettingsSection(!checked)` | unchecked (section enabled = true, `SidebarService.java:333`) | `common/…/app/presenters/BrowsePresenter.java:342` adds or omits the TV sidebar Settings section. The phone filters that section out of the nav and You lists (`mobile/…/ui/browse/MobileBrowseActivity.java:1056,1737`) and always appends its own Settings row (`:1088-1089`). | DEAD | Nothing on the phone: Settings stays reachable from You either way. | Restart toast. |
| Fix empty Subscriptions and Channels | `MediaServiceData#isLegacyUIEnabled` / `#setLegacyUIEnabled` (MSC data idx 25) | off | `MSC/…/common/models/impl/mediagroup/MediaGroupOptions.kt:37` → `clientTV:14` = `TV_LEGACY`. Used by `MSC/…/browse/v2/BrowseService2.kt:117` (Subscriptions, via `getSubscriptions:105`), `:140/:162` (channel uploads), `:246` (your playlists), `:311` (playlists/channels), `:510/:560/:666` (continuations), `:592/:607` (row sections). Not suggestions (`isBrowseSection` is false for `TYPE_SUGGESTIONS`). | LIVE | Loads Subscriptions, channel pages and playlists with YouTube's older TV interface instead of the current one. | Not a Player setting at all (browse feeds). Upstream notes the legacy Subscriptions feed comes back out of order. Phone parsing of TV_LEGACY responses was not verified (see notes). |

### Experimental (phone-only extra row, `mobile/…/ui/browse/MobileBrowseActivity.java:901-907`)

| Row | Getter/Setter | Default | Phone reader (file:line) | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Prefer SABR even when links work (experimental; next VOD). Checkbox under the category "Experimental" (`smarttubetv/src/stmobile/res/values/strings_mobile.xml`: `mobile_settings_experimental`, `sabr_vod_option`) | `SabrSourcePreference#isPreferred` / `#setPreferred` (`mobile/…/player/SabrSourcePreference.java`, SharedPreferences `newtube_playback_sources` key `sabr_vod`; debug/benchmark builds can override it with `debug.arc.sabr_vod`) | false (off) | `mobile/…/player/Media3PlayerController.java:292` `openDash`: if preferred and `SabrFormatAdapter.eligible`, it opens SABR instead of DASH. Path: `VideoLoaderController.java:614` `player.openDash(formatInfo)` → `MobilePlaybackActivity.openDash:7765` → `Media3PlayerController.openDash`. Also read at `:242-247` `openSabr` (whether a SABR failure is terminal), `:326` `prebuildNextSource` (skips the next-video DASH prebuild), and in `isEnabled()` → `SabrVodCapability.setEnabled` (MSC `VideoInfo.java:266`, `VideoInfoService.java:3063`, `YouTubeMediaItemFormatInfo.java:104/165`). It is initialized at startup in `MobileMainApplication.java:805`. | LIVE | Streams on-demand videos over YouTube's SABR protocol instead of the normal DASH links, from the next video on. Roughly 11% less data, about 66 ms slower start (class doc). | onSelect `setPreferred` writes the pref, re-runs `initialize()` (`SabrVodCapability.setEnabled`) and calls `YouTubeMediaItemService.instance().invalidateCache()` to drop cached /player answers. Takes effect on the next VOD, with no restart. Not in the gear sheet. The label is jargon ("SABR", "links work", "VOD"). It is the only row in its one-row category, injected on every `openSettings()` call. |

---

# Other player-related pages

> Slice notes (Subtitles, SponsorBlock, DeArrow): Path abbreviations in this part: `common/…/` = `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/`, `mobile/…/` = `smarttubetv/src/stmobile/java/com/newtube/mobile/`, `MSC/…/` = `MediaServiceCore/youtubeapi/src/main/java/com/liskovsoft/youtubeapi/`. Presenters: `presenters/settings/` = `common/…/app/presenters/settings/`; controllers = `common/…/app/models/playback/controllers/`. Labels resolved from `common/src/main/res/values/strings.xml` / `unlocalized-strings.xml` (the stmobile flavor overrides none of them).

## Subtitles

Page title "Subtitles" (Settings root, `AppDataSourceManager.java:70`). The same page also opens from the player: Captions sheet -> "Caption style & size" (`mobile/…/ui/playback/MobilePlaybackActivity.java:6631` -> `SubtitleSettingsPresenter.show()`; hidden on the cast captions sheet, :3211). Not on this page: subtitle language and "Unlock more subtitles" (commented out, `SubtitleSettingsPresenter.java:29-31`). Track choice is per video in the player's Captions sheet, not here.

| Row | Getter/Setter | Default | Phone reader (file:line) | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Enable subtitles only on the current channel (switch) | `PlayerData#isSubtitlesPerChannelEnabled()` / `#setSubtitlesPerChannelEnabled(boolean)` (`common/…/prefs/PlayerData.java:479/483`); per-channel list `enable/disableSubtitlesPerChannel` :461/:466 | ON (`PlayerData.java:914`, parse default `true`) | `controllers/VideoStateController.java:435` `restoreSubtitleFormat()` <- `onMetadata` :151 / `onSourceChanged` -> `restoreFormats` :252/:650 (dispatched by PlaybackPresenter, driven by `Media3PlayerController`); `mobile/…/ui/playback/MobilePlaybackActivity.java:6548` `applyCaptionFormat()` (CC button tap / Captions sheet pick) records the channel | LIVE | When on, captions you turn on come back only on videos from that same channel (other channels start with captions off); when off, the last caption language you picked follows you to every video. | Factory `AppDialogUtil.createSubtitleChannelOption` (`common/…/utils/AppDialogUtil.java:564`). No side effects. Label is confusing: it is a "remember captions per channel" switch, default ON, so first-time users see captions "forget" themselves across channels. |
| Subtitle style (radio, 7 options: White on transparent / White on semitransparent / White on black / Yellow on transparent / Yellow on semitransparent / Yellow on black background / System style (Android settings > Accessibility)) | `PlayerData#getSubtitleStyle()` / `#setSubtitleStyle(SubtitleStyle)` (`PlayerData.java:517/521`; list built at :829-839) | "White on semitransparent background" (index 1, `PlayerData.java:859/873`; existing installs migrated off the old yellow default once, `mobile/…/MobileMainApplication.java:225-237`) | `mobile/…/player/Media3SubtitleManager.java:107` `configureSubtitleView()` <- constructor :45 and `onDataChange` :50 (PlayerData change listener); manager built in `MobilePlaybackActivity.java:1292` `createPlayerObjects()` -> `createSubtitleManager()` :5859 | LIVE | Sets caption text colour and background box (or copies the Android accessibility caption style). | Factory `AppDialogUtil.createSubtitleStylesCategory` :558/:573. onSelect also calls `Utils.showPlayerControls(ctx,false)` (hides the player controls if a player exists, so the change is visible). Applies live via `PlayerData.persistState` -> `onDataChange`. |
| Subtitle scale (radio, 0.1x-2.0x in 0.1 steps, 20 options) | `PlayerData#getSubtitleScale()` / `#setSubtitleScale(float)` (`PlayerData.java:526/530`) | 1.0x (`PlayerData.java:898`) | `Media3SubtitleManager.java:165` `setTextSize()` <- `applyStyle` / `applySystemStyle` <- `configureSubtitleView` (same chain as above) | LIVE | Makes captions smaller or bigger (multiplier on media3's default size, which already scales with the video box). | Factory `AppDialogUtil.createSubtitleSizeCategory` :591. Same `showPlayerControls(false)` side effect. Also multiplies the System style's own font scale. |
| Subtitle bottom shift (radio, 0%-100% in 2.5% steps, 41 options) | `PlayerData#getSubtitlePosition()` / `#setSubtitlePosition(float)` (`PlayerData.java:535/539`) | 10% (`PlayerData.java:901`, 0.1) | `Media3SubtitleManager.java:117` `setBottomPaddingFraction()` <- `configureSubtitleView` | LIVE | Raises captions from the bottom edge of the video by a share of its height. | Factory `AppDialogUtil.createSubtitlePositionCategory` :608. Same `showPlayerControls(false)` side effect. Label is confusing ("bottom shift"; values near 100% push captions to or past the top). |

## SponsorBlock

Page title "SponsorBlock" (Settings root, `AppDataSourceManager.java:74`). Built by `presenters/settings/SponsorBlockSettingsPresenter.java:37-49`. All playback effects run in `controllers/SponsorBlockController.java`, reached on the phone via `mobile/…/player/Media3PlayerController.java:1189` -> `PlaybackPresenter.onVideoLoaded` (`common/…/app/presenters/PlaybackPresenter.java:395`) -> `SponsorBlockController.onVideoLoaded` :96, then a 1 s poll (`RxHelper.interval`, :209) -> `skipSegment` :225. The phone never dispatches `R.id.action_content_block` (no player button), so `onButtonClicked/onButtonLongClicked` :131/:155 are unreachable. Changes apply from the next video load (no in-place refresh when changed from Settings).

| Row | Getter/Setter | Default | Phone reader (file:line) | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Enable (switch) | `SponsorBlockData#isSponsorBlockEnabled()` / `#setSponsorBlockEnabled(boolean)` (`common/…/prefs/SponsorBlockData.java:191/195`) | ON (`SponsorBlockData.java:250`, `SDK_INT > 19`) | `SponsorBlockController.java:103` (`onVideoLoaded`) and :115 (`onMetadata`) | LIVE | Turns SponsorBlock segment fetching, skipping and seek-bar marks on or off. | onSelect also calls `stopExcludingChannel(channelId)` with the current player video's channel, but on the phone the page is never opened with the player as top view, so channelId is null: no-op. Label is terse ("Enable"). |
| Exclude this channel from SponsorBlock / Stop excluding this channel from SponsorBlock (button) | `SponsorBlockData#isChannelExcluded` / `#toggleExcludeChannel` (:150/:154) | none excluded | (would be) `SponsorBlockController.java:426` | NOT SHOWN | — | Only appended when `getViewManager().getTopView() == PlaybackView.class` (`SponsorBlockSettingsPresenter.java:167`). On the phone the page opens only from the Settings root (top = `AppDialogView`/`MobileAppDialogActivity`), and the TV long-press path is unreachable, so this row never renders. Exclusion is still possible from the card menu item "Exclude this channel from SponsorBlock" (`BaseMenuPresenter.java:567`, `MENU_ITEM_EXCLUDE_FROM_CONTENT_BLOCK`, off by default in `MainUIData.MENU_ITEM_DEFAULT`). Excluded rows not counted below. |
| Ignore short segments (radio: Disabled, 0.5 s-20 s in 0.5 s steps, 41 options) | `SponsorBlockData#getIgnoredDurationMs()` / `#setIgnoredDurationMs(long)` (:228/:232) | 5 s (`SponsorBlockData.java:259`) | `SponsorBlockController.java:402` `applyActions()` | LIVE | Doesn't skip a segment when less than this much of it is left to skip. | Factory `AppDialogUtil.createIgnoreShortSegmentsCategory` (`AppDialogUtil.java:948`). Also an extra hard 10 s floor when the Developer "TextureView" tweak is on (:401). |
| Check SponsorBlock server status (button) | — (opens `https://status.sponsor.ajay.app`) | — | `Utils.openLink` (`common/…/utils/Utils.java:538`) -> `WebBrowserView` -> `mobile/…/ui/webbrowser/MobileWebBrowserActivity` (mapping `MobileMainApplication.java:990`) | LIVE | Opens the SponsorBlock status page in the device browser. | Leaves the app. Same URL as DeArrow's status row. |
| About SponsorBlock (button) | — (opens `https://sponsor.ajay.app`) | — | same chain as above | LIVE | Opens the SponsorBlock website in the device browser. | Leaves the app. |

### Choose action (one button per segment category; each opens a radio sub-dialog)

Sub-dialog options (`SponsorBlockSettingsPresenter.java:87-104`), all stored via `SponsorBlockData#setAction(category, type)` (:180), read by `#getAction` (:170) / `#getActions` (:166) / `#getEnabledCategories` (:108) / `#isActionsEnabled` (:200):
- **Do nothing** (`ACTION_DO_NOTHING`): never skipped. The category is left out of the server request only when its colour marker is also unchecked (`SponsorBlockData.getEnabledCategories:108-119` adds every marker-checked category; read at `SponsorBlockController.java:171/180`). Every marker is checked by default, so by default a "Do nothing" category is still fetched and painted on the seek bar. (Corrected after a codex review.)
- **Only skip** (`ACTION_SKIP_ONLY`): silent jump (`simpleSkip` :263).
- **Skip with notification** (`ACTION_SKIP_WITH_TOAST`): jump plus a system toast "SponsorBlock: Skipping segment "X"…" (`messageSkip` :272, `MessageHelpers.showMessage`).
- **Show confirmation dialog** (`ACTION_SHOW_DIALOG`): `confirmSkip` :283 shows a one-button AppDialog ("Skip segment "X"?"), id 144, transparent/overlay, auto-closing when the segment ends (`AppDialogPresenter.setCloseTimeoutMs`); on the phone this renders as `MobileAppDialogActivity`'s bottom sheet over the player. Not shown if another dialog is open. Falls back to a silent skip in PiP or screen-off (:405).

Reader for all rows: `SponsorBlockController.java:361` (`findMatchedSegments`) and :396 (`applyActions`), fetch set :171/:180, poll gate :206.

| Row | Getter/Setter | Default | Phone reader (file:line) | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| ● Sponsor | `getAction/setAction("sponsor")` | Skip with notification (`SponsorBlockData.java:301`) | `SponsorBlockController.java:361/396` | LIVE | Chooses what happens when a paid sponsor segment starts. | Sub-dialog only stores the choice; no restart. Label = coloured dot + category name. |
| ● Intermission/intro animation | `…("intro")` | Skip with notification | same | LIVE | Same, for intros/intermissions. | |
| ● Endcards/credits | `…("outro")` | Skip with notification | same | LIVE | Same, for end cards and credits. | |
| ● Interaction reminder (subscribe) | `…("interaction")` | Skip with notification | same | LIVE | Same, for "like and subscribe" reminders. | |
| ● Unpaid/self promotion | `…("selfpromo")` | Skip with notification | same | LIVE | Same, for unpaid/self promotion. | |
| ● Non-music section of clip | `…("music_offtopic")` | Skip with notification | same | LIVE | Same, for non-music parts of music videos. | |
| ● Preview or recap of the video | `…("preview")` | Skip with notification | same | LIVE | Same, for previews/recaps. | |
| ● Interesting point (bookmark) | `…("poi_highlight")` | Skip with notification | same (fetched), but practically never acted on | DEAD | Nothing in practice: a highlight is a zero-length point, so the 1 s poll would have to land on its exact millisecond (`isPositionInsideSegment` accepts position == start, :259-264), and there is nothing to skip anyway. | Highlights from SponsorBlock have start == end (`MSC/…/service/data/YouTubeSponsorSegment.java:25-26`). There is no "jump to highlight" feature. Its only effects: any action other than "Do nothing" adds the category to the request and keeps the 1 s poll running (`isActionsEnabled`, :206). |
| ● Off-topic (filler) | `…("filler")` | Do nothing (`SponsorBlockData.java:298-299`) | same | LIVE | Same, for off-topic filler (off by default). | |

### Color markers on progress bar (checkbox list, 9 rows)

Getter/setter: `SponsorBlockData#isColorMarkerEnabled(category)` / `#enableColorMarker` / `#disableColorMarker` (:132/:122/:127), plus `#isColorMarkersEnabled()` (:136, any checked). Reader chain: `SponsorBlockController.java:203` (`startSponsorWatcher`) and :324 (`toSeekBarSegments`) -> `PlayerUI.setSeekBarSegments` -> `MobilePlaybackActivity.java:5888` -> `mobile/…/ui/playback/PlayerTimeBar.java:329` `setSegments`, drawn at :474-490. A checked marker also adds its category to the server request (`SponsorBlockData.getEnabledCategories:117`), so a marker shows even when its category's action is "Do nothing".

| Row | Getter/Setter | Default | Phone reader (file:line) | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| ● Sponsor | `isColorMarkerEnabled/enable/disableColorMarker("sponsor")` | checked (all categories, `SponsorBlockData.java:270`) | `SponsorBlockController.java:324` -> `PlayerTimeBar.java:474` | LIVE | Paints sponsor segments green on the seek bar. | No side effects. |
| ● Intermission/intro animation | `…("intro")` | checked | same | LIVE | Paints intros cyan. | |
| ● Endcards/credits | `…("outro")` | checked | same | LIVE | Paints end cards blue. | |
| ● Interaction reminder (subscribe) | `…("interaction")` | checked | same | LIVE | Paints interaction reminders magenta. | |
| ● Unpaid/self promotion | `…("selfpromo")` | checked | same | LIVE | Paints self promotion yellow. | |
| ● Non-music section of clip | `…("music_offtopic")` | checked | same | LIVE | Paints non-music sections orange. | |
| ● Preview or recap of the video | `…("preview")` | checked | same | LIVE | Paints previews light blue. | |
| ● Interesting point (bookmark) | `…("poi_highlight")` | checked | `PlayerTimeBar.java:478` skips it | DEAD | Nothing: the phone's seek bar drops zero-width segments (`if (end <= start) continue;`), and a highlight is a point. | The TV seek bar may have drawn a tick; the phone never does. |
| ● Off-topic (filler) | `…("filler")` | checked | same as Sponsor | LIVE | Paints filler violet. Filler's action is "Do nothing" by default, but the checked marker alone puts filler in the server request. | Corrected from PARTIAL after a codex review: `getEnabledCategories` includes marker-checked categories. |

### Misc (checkbox list)

| Row | Getter/Setter | Default | Phone reader (file:line) | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Paid content notification | `SponsorBlockData#isPaidContentNotificationEnabled()` / `#setPaidContentNotificationEnabled` (:219/:223) | OFF (`SponsorBlockData.java:258`) | `controllers/VideoLoaderController.java:540` `processFormatInfo()` (every /player answer) | LIVE | Shows a toast like YouTube's "Includes paid promotion" when the video declares it. | Independent of SponsorBlock's Enable switch (it reads YouTube's own /player `paidContentOverlay`, `MSC/…/innertube/utils/PlayerResultExtensions.kt:27`), so it is misfiled on this page. Whether every /player client the phone uses returns the overlay is untested. |
| Don't skip segments again | `SponsorBlockData#isDontSkipSegmentAgainEnabled()` / `#setDontSkipSegmentAgainEnabled` (:210/:214) | OFF (`SponsorBlockData.java:256`) | `SponsorBlockController.java:243` `skipSegment()` | LIVE | Skips each segment only the first time; if you seek back into it, it plays. | Label is confusing (reads like "never skip"). |
| Use alternative server (desc: "Enable this option if SponsorBlock refusing to work by different reasons.") | `SponsorBlockData#isAltServerEnabled()` / `#enableAltServer` (:237/:241) -> `GlobalPreferences#isContentBlockAltServerEnabled` (`SharedModules/sharedutils/…/prefs/GlobalPreferences.java:161`) | OFF | `MSC/…/block/SponsorBlockService.java:29/39-41/46` <- `YouTubeMediaItemService.getSponsorSegments` :830 <- `SponsorBlockController.java:180` | LIVE | Fetches segments from `api.sponsor.ajay.app` instead of `sponsor.ajay.app`. | Already-fetched segments are cached per video (`.cache()`, :181); takes effect on the next video. |

## DeArrow

Page title "DeArrow" (Settings root, `AppDataSourceManager.java:76`). Built by `presenters/settings/DeArrowSettingsPresenter.java:33-45`. Not shown on the phone: the "not-submitted thumbnails" source picker (`appendThumbQuality`, gated `!PhoneUi.isEnabled()` :39; same `MainUIData` setting lives under User interface) and the unused `appendDeArrowSwitch` (`DeArrowData#isDeArrowEnabled` has no reader anywhere). Reader chain for all three switches: `common/…/misc/BrowseProcessorManager.java:14-15` (DeArrowProcessor + UnlocalizedTitleProcessor), built by `BrowsePresenter.java:210` (process at :1044/:1275/:1357/:1435), `SuggestionsController.java:177/245` (process :585/:958, related list under the video), `ChannelPresenter.java:57`, `ChannelUploadsPresenter.java:53`, `SearchPresenter.java:51`; results fold into `Video.deArrowTitle` / `altCardImageUrl`, pushed back as `ACTION_SYNC` (`mobile/…/ui/browse/MobileBrowseActivity.java:2111-2113` -> `VideoCardAdapter.refreshItems`; same handling in the channel, uploads, search and player screens). Toggles affect lists loaded after the change (processed cards are flagged `deArrowProcessed`).

| Row | Getter/Setter | Default | Phone reader (file:line) | Verdict | What it does | Side effects / notes |
|---|---|---|---|---|---|---|
| Unlocalized video titles (switch) | `MainUIData#isUnlocalizedTitlesEnabled()` / `#setUnlocalizedTitlesEnabled` (`common/…/prefs/MainUIData.java:372/376`) | OFF (`MainUIData.java:473`) | `common/…/misc/UnlocalizedTitleProcessor.java:46` (flag), `process()` -> `MSC/…/service/YouTubeMediaItemService.java:936` `getUnlocalizedTitleObserve`; shown via `Video.getTitle()` (`common/…/app/models/data/Video.java:308`) at `mobile/…/ui/browse/VideoCardAdapter.java:226/249`, `mobile/…/ui/playback/RelatedVideoAdapter.java:178` | LIVE | Shows video titles in their original language instead of YouTube's auto-translated ones. | onSelect also sets `DeArrowData.setReplaceTitlesEnabled(false)` (mutually exclusive with Crowdsourced titles). One extra request per card. Exact duplicate of the "Unlocalized video titles" row in User interface -> Misc (`MainUISettingsPresenter.java:187-192`); not really a DeArrow feature. |
| Crowdsourced titles (switch) | `DeArrowData#isReplaceTitlesEnabled()` / `#setReplaceTitlesEnabled` (`common/…/prefs/DeArrowData.java:30/34`) | OFF (DataSaverBase boolean default) | `common/…/misc/DeArrowProcessor.java:44` (flag) / :58 (`video.deArrowTitle`), request `YouTubeMediaItemService.java:906` `getDeArrowDataObserve`; shown at `VideoCardAdapter.java:226/249`, `RelatedVideoAdapter.java:178` via `Video.getTitle()` | LIVE | Replaces clickbait titles on cards with DeArrow's community-written ones. | onSelect also sets `MainUIData.setUnlocalizedTitlesEnabled(false)`. Sends each listed video id to the third-party DeArrow API. |
| Crowdsourced thumbnails (switch) | `DeArrowData#isReplaceThumbnailsEnabled()` / `#setReplaceThumbnailsEnabled` (`DeArrowData.java:38/42`) | OFF | `DeArrowProcessor.java:45` / :61 (`video.altCardImageUrl`); shown via `VideoCardAdapter.java:348` -> `common/…/utils/ClickbaitRemover.java:98` (DeArrow image wins over the thumbnail-source setting) | LIVE | Replaces card thumbnails with DeArrow's community-picked frames. | Sends video ids to the DeArrow API. No other side effects. |
| Check DeArrow server status (button) | — (opens `https://status.sponsor.ajay.app`) | — | `Utils.openLink` (`Utils.java:538`) -> `MobileWebBrowserActivity` | LIVE | Opens the DeArrow/SponsorBlock status page in the device browser. | Leaves the app; identical URL to SponsorBlock's status row. |
| About DeArrow (button) | — (opens `https://dearrow.ajay.app`) | — | same | LIVE | Opens the DeArrow website in the device browser. | Leaves the app. |

---

# Summary

## Counts

How rows are counted:
- A radio category with a long run of numbers (speeds, seconds, percentages) counts as one row.
- Every checkbox and every switch counts as one row.
- Zoom splits into two rows: fit modes and percentages.
- The four "Remember speed" options count separately, because their verdicts differ.
- The "Video speed" navigation button is not counted.

| Page | Rows | LIVE | PARTIAL | DEAD | KEYBOARD-ONLY | UNCLEAR |
|---|---|---|---|---|---|---|
| Player: categories (Playback mode … Sleep timer, Experimental) | 26 | 12 | 3 | 11 | 0 | 0 |
| Player: Misc checkboxes | 28 | 11 | 3 | 14 | 0 | 0 |
| Player: Developer options checkboxes | 26 | 7 | 4 | 15 | 0 | 0 |
| **Player page total** | **80** | **30** | **10** | **40** | 0 | 0 |
| Subtitles | 4 | 4 | 0 | 0 | 0 | 0 |
| SponsorBlock | 25 | 23 | 0 | 2 | 0 | 0 |
| DeArrow | 5 | 5 | 0 | 0 | 0 | 0 |
| **All four pages** | **114** | **62** | **10** | **42** | **0** | **0** |

No row is KEYBOARD-ONLY. Even a hardware keyboard never reaches the controllers' key handlers, because nothing calls `PlaybackPresenter.onKeyDown`.

**Half the Player page (40 of 80 rows) does nothing on the phone, and 10 more only partly work.**
- Subtitles, SponsorBlock and DeArrow are almost entirely live.
- Several LIVE rows still hurt on a phone:
  - **Oculus Quest fix** locks every screen to landscape.
  - **"Ambilight…" fix** stops SponsorBlock skipping segments shorter than about 10 s.
  - **Force legacy codecs** gives low-resolution single-file video; picking any preset silently clears it.
  - **Fix empty Subscriptions and Channels** switches the feed client to the TV legacy one.

## DEAD rows (42)

**Player > categories (11)**
1. Network engine: the media3 stack picks Cronet and falls back to OkHttp on its own. ErrorFixer and MainApplication's OOM handler only rewrite the pref.
2. Video zoom 50%–300%: reaches the `setZoomPercents` stub. Picking one resets the fit mode to Default.
3. Audio language: no reader. Media3 hard-codes "prefer original"; the gear Audio track is the real control.
4. Audio delay > Enable
5. Audio delay > value: onSelect also closes the whole Settings flow.
6. OK button behavior: `onKeyDown` only.
7. Seek behavior (confirmation modes)
8. Preview while seeking: reaches the `loadStoryboard` stub, and the phone also skips storyboard enrichment.
9. Seek interval: the phone's double-tap is hard-coded at 10 s in `activity_mobile_playback.xml:74`.
10. Show ending time in controls bar (remaining/ending): the default "remaining" is not what the phone shows.
11. Pixel ratio

**Player > Misc (14)**
12. Background play while searching/browsing a channel (also on Settings > Search)
13. Exit from the player: Double back
14. Place chat to the left
15. Place comments to the left
16. Seek with number keys
17. Revert old behavior of the speed button
18. ~~Show clock in controls bar~~: live again, as the fullscreen clock (Settings > Player > Fullscreen).
19. Show quality info in the controls bar
20. Add bitrate into the quality info
21. Sync focus between player button rows
22. Enable player UI animations
23. Show button tooltips
24. Long press this button for additional options
25. Show icon on the channel button

**Player > Developer options (15)**
26. Disable playback notifications
27. Audio sync fix
28. Unlock high bitrate mp4a formats: checked by default, so it looks active.
29. Live stream fix (4K): the phone already plays live from the DASH URL. Ticking it only unticks the 1080p fix.
30. Alt presets behavior (limit bandwidth)
31. Amlogic 1080p@60fps fix
32. Tunneled video playback: ticking it only unticks "Ambilight…".
33. Disable snap to vsync
34. Skip codec profile level check
35. Force SW video decoder: its only reader switches it back off after a decoder error.
36. Frame drop fix #1
37. Frame drop fix #2
38. Keep finished activities
39. Disable Channels service
40. Hide Settings section (dangerous!)

**SponsorBlock (2)**
41. Choose action > Interesting point (bookmark)
42. Color markers > Interesting point (bookmark)

## PARTIAL rows (10)
- **Video speed list:** only applies under "Remember speed: Same on all videos" (or "None" until the next restart). It is overridden under the default "Per each channel".
- **Auto-hide UI:** can only hide the controls sooner than the phone's fixed 3.5 s timer; "Never" and longer values do nothing.
- **Sleep timer:** pauses N hours after the player opened, counting across autoplay. What the phone lacks is the "inactivity" half: touching the screen never resets the count. Engine reloads (error recovery) restart it silently, it re-pauses every minute after tripping, and "1.5 hour" is a plural bug.
- **Horizontally scrolled Suggestions:** there are no horizontal rows on the phone. Its only effect is merging several untitled /next suggestion groups into one de-duplicated block in Up next (rare).
- **Don't resize video to fit dialog:** never resizes anything; while unchecked it only hides the controls when reused dialogs open.
- **Show likes/dislikes count:** doesn't hide the like count. It only gates the Return YouTube Dislike fetch. Bug: with RYD on and this off, the dislike number falls back to YouTube's estimate.
- **Prefer IPv4 DNS and Prefer Google DNS (two rows):** affect YouTube API requests only, not video. IPv4 is on by default.
- **Ambilight/Aspect ratio/Video scale/Screenshots fix:** its only effect is that SponsorBlock doesn't skip a segment with less than 10 s left to skip.
- **Unlock all video formats:** only adds presets to the list.

## Rows that duplicate the player's gear sheet

| Settings row | Player control | Relationship |
|---|---|---|
| Playback mode | Gear > More > Repeat (the same radio), More > Shuffle | Same app-wide pref; there is no per-video override. |
| Video presets | Gear > Quality | Settings is the persistent ceiling; a gear rung is a per-session override. **Bug:** gear "Auto" calls `setFormat(getDefaultVideoFormat())` and silently replaces the preset picked in Settings with the 1080p default. |
| Video zoom (fit modes and the dead percentages) | Pinch, and Gear > More > Zoom (the same radio, plus dead Aspect/Rotate) | Same pref both ways. |
| Video speed (list) | Gear > Speed, and its "More speeds" (the same factory, with the live player) | The gear sets this video's speed. "Remember speed" decides what persists, so the Settings list is mostly inert. |
| Long / Extra long speed list | Shapes Gear > Speed > More speeds | Doesn't change the gear's own 8 presets. |
| Audio language (dead) | Gear > Audio track (multi-language videos only) | Different mechanism: the gear one is real and persists via `setFormat(audio)`. |
| Subtitles page (style, scale, position, per-channel) | Gear > Captions > "Caption style & size" opens this same page | The Captions sheet's track pick is the per-video choice and feeds the per-channel memory. |

No Misc, Developer, SponsorBlock or DeArrow row has an in-player control. Buffer, Audio delay, Volume and Sleep timer have no in-player control at all, because the TV HQ dialog that held them can't be reached.

**Duplicates across Settings pages:**
- Background play while searching: Player > Misc and Search.
- Exit from the player: Player > Misc; General hides its own copy on the phone.
- OK button behavior: General writes the same pref as a checkbox.
- Network engine: General has a second writer (`GeneralSettingsPresenter.java:700`).
- Unlocalized video titles: DeArrow and User interface > Misc.
- Fix empty Subscriptions and Channels: a browse-feed setting filed under Player > Developer.
- Paid content notification: a YouTube disclosure toast filed under SponsorBlock.

## Confusing labels

**TV concepts with no phone meaning:**
- OK button behavior
- Seek behavior (confirmation modes)
- Preview while seeking (carousel modes)
- Pixel ratio (hard-coded English strings)
- Sync focus between player button rows
- Show button tooltips
- "Long press this button for additional options": tooltip text, not a setting.
- "Show icon on the channel button": the phone has no channel button.
- Developer options: Amlogic / Oculus Quest / Frame drop fix #1, #2 / Keep finished activities / Channels service / "Hide Settings section (dangerous!)" / Tunneled / vsync
- "Ambilight/Aspect ratio/Video scale/Screenshots fix": it does none of those things.

**Labels that say the wrong thing:**
- "Network engine": its three descriptions describe engines the phone doesn't have.
- "Video presets": "Disabled" means Auto capped at 1080p, and the options are raw spec strings such as "720p     30fps    av01+hdr".
- "Fit either width or height": it crops to fill.
- "Remember speed: None": it remembers until the next restart. On a default install it shows checked together with "Per each channel", which is a radio option with checkbox code.
- "Show ending time in controls bar": the category title repeats one of its own options.
- "Auto-hide UI": implies "Never" works.
- "Sleep timer": it means "pause N hours after the player opened" (touch doesn't reset it), not a countdown, and it has the "1.5 hour" plural bug.
- "Remember position of short videos (less than 5 min)": the code uses 6 min.
- "Switch to the next chapter by clicking on the notification": the "notification" is an in-player pop-up.
- "Show likes/dislikes count": doesn't hide likes.
- "Disable automatic network error fixing": it only disables the stall quality step-down.
- "Prefer IPv4 DNS": on by default, API traffic only.
- "Prefer Google DNS": shows the IPv4 description, and unchecking it also clears the IPv4 default.
- "Unlock high bitrate 1080p vp9 formats 💎": on the phone this is DASH+HLS merging plus an extra /player call.
- "Force legacy codecs (720p)": usually 360p.
- "Live stream fix (1080p/4K)": the 4K one is already the phone's default.
- "Fix empty Subscriptions and Channels": not a player setting.

**Labels that are vague or jargon:**
- Generic "Misc" headers: the Player page and the speed sub-page both have one.
- "Volume": this is player gain, not the system volume.
- Playback-mode options: long TV-era sentences.
- "Use current section contents as a playlist"
- "Audio focus (pause if other players detected)"
- Experimental: "Prefer SABR even when links work (experimental; next VOD)"
- Subtitles: "Enable subtitles only on the current channel" (a per-channel caption memory, ON by default) and "Subtitle bottom shift"
- SponsorBlock: bare "Enable", "Don't skip segments again" (it means skip each segment once), and "Interesting point (bookmark)" offered as skippable.
- "Unlocalized video titles" sits on the DeArrow page.

## DEAD verdicts I'm less than sure about

- **Background play while searching:** rests on no phone code dispatching `R.id.action_search` / `R.id.action_channel` to `PlayerUIController`. A grep of `mobile/` finds none.
- **Show icon on the channel button:** dead only because `bindWatchMetadata` always paints the avatar (MobilePlaybackActivity.java:7054). With it on, the avatar can only appear a moment earlier.
- **Live stream fix (4K):** dead only because of branch order in `VLC.processFormatInfo` (the live-URL branch at :593 runs before `acceptAdaptiveFormats`). It comes alive if that branch changes.
- **Hide Settings section:** dead because the phone filters out the TV Settings section and always adds its own You row. Its one reachable effect is on a saved "boot to Settings" target, and the phone's Boot-to-section list doesn't offer Settings (`GeneralSettingsPresenter.java:311-318`).
- **Force SW video decoder:** dead because no media3 `MediaCodecSelector` reads it today. Its one reader (`EFC:469`) only decides engine restart versus reload after a video-renderer error, then clears the flag.
- **Network engine:** besides the settings screen, its only readers are error-recovery bookkeeping (ErrorFixer and MainApplication's OutOfMemoryError handlers). With OkHttp picked, the first OOM recovery flips the pref instead of lowering the buffer. It never selects the media3 transport.
- **Interesting point (bookmark) ×2:** both assume SponsorBlock highlights arrive with start == end. With that assumption, the action fires only if the 1 s poll lands on the exact millisecond, and the marker is never drawn. Both rows still add the category to the request. Confirm by logging the parsed segment or the `setSegments` input on a video with a highlight.
- **Seek interval and Preview while seeking:** certain they're dead today, but both are already wired through to the player and would be cheap to bring back. The TV fed `getSeekIncrementMs()/1000` to the same `YouTubeOverlay.seekSeconds`; `PlayerUIController.onMetadata` already calls `loadStoryboard`.

LIVE verdicts with a caveat, all read from the code and none tried on a device:
- **Unlock high bitrate formats:** the merged route needs an HLS URL on the winning /player client. Otherwise only the extra deferred iOS /player fetch remains.
- **Fix empty Subscriptions:** the phone's handling of `TV_LEGACY` feed responses is unverified.
- **Chapter notification pop-up:** not checked on a device.
- **SponsorBlock "Show confirmation dialog" action:** the bottom sheet over the player is not checked on a device.

## Cross-model check

`codex-agent --sol -e high` (gpt-6.1-sol; the transcript is `settings-ref/codex-review.md`) was asked to falsify every DEAD verdict, the PARTIAL claims and the three premises. It confirmed all three premises and 36 of the 43 rows drafted as DEAD. Its findings were reconciled as follows:
- **Changed:**
  - Horizontally scrolled Suggestions: DEAD → PARTIAL (it merges untitled groups in Up next).
  - SponsorBlock Color markers > Off-topic (filler): PARTIAL → LIVE (a checked marker alone puts the category in the request).
  - "Do nothing" description: a marker-checked category is still fetched and painted.
  - Sleep timer: the count runs from engine init, not from each new video.
  - Ambilight threshold: measured on the time left to skip, not on segment length.
- **Kept DEAD, with the incidental effects recorded in the notes:**
  - Network engine and Force SW decoder: error-recovery bookkeeping only.
  - Show icon on the channel button: can only paint the avatar earlier.
  - Hide Settings section: only affects a boot-to-Settings target the phone can't set.
  - Interesting point ×2: request and poll cost only.

