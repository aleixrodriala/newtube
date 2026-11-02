# Player swipe gestures (issue #12)

Built 2026-10-02 on `feat/gestures`. The player's one-finger swipes, what each one does, and
why. YouTube 21.18 numbers were measured on the reference emulator (stock app, `ytref2_api35`
clone of `ytref_api35`); the conventions for brightness, volume and seek swipes come from
reading the source of ReVanced's swipe controls, NewPipe, VLC, LibreTube, mpv-android and Just
Player (behaviour only: only mpv-android (MIT) and Just Player (Unlicense) are compatible with
this repo's licence, and no code was taken from any of them).

## What each swipe does

| Where | Swipe | Does | Code |
|---|---|---|---|
| Portrait video | down | minimize to the mini player (unchanged) | `onDismissDrag*` |
| Portrait video | up | fullscreen | `beginFullscreenSwipe(SWIPE_ENTER_FULLSCREEN)` |
| Fullscreen, left 3/8 | up / down | brightness | `SwipeLevels` |
| Fullscreen, right 3/8 | up / down | volume | `SwipeLevels` |
| Fullscreen, centre 1/4 | down | leave fullscreen | `beginFullscreenSwipe(SWIPE_EXIT_FULLSCREEN)` |
| Anywhere on the video | sideways | seek | `PlayerTimeBar.startSwipeScrub` |

- `PlayerContainerLayout` decides: a drag becomes a swipe past 2x touch slop and 1.5x along one
  axis, and only if the activity claims it (`onSwipeStart`). A declined drag stays with the views
  under the finger for the whole touch, so a drag never turns into another swipe half-way.
- Nothing starts during press-and-hold 2x, a seek bar drag, a minimize/open morph, the Back
  preview or PiP, nor from the status-bar band at the top or the home-gesture band at the bottom
  (sticky immersive delivers those edge swipes to the app too). Seeks don't start in the side
  bands where gesture navigation's Back begins. The seek bar's own touch band keeps its touches
  (it disallows interception), as YouTube's does.
- Switches: `PlayerGesturePrefs`, one each for brightness, volume and seek, all on by default
  (the old shared brightness/volume switch is still read as the default of both). With only one
  of brightness and volume available (its switch off, or a fixed-volume phone), the other works on
  both sides (`sideSwipe`). The fullscreen and minimize swipes are YouTube's own and always on.

## Fullscreen in and out

YouTube 21.18, measured:

- **Up into fullscreen:** commits on release ~70 dp from touch-down. Nothing moves during the
  drag; the rotation is the system's.
- **Down out of fullscreen:** commits ~150 dp. The video shrinks to 0.95 within ~40 dp and slides
  down at 0.84x the finger, stopping dead at 30% of the screen height.
- Speed never commits either one; neither vibrates.

Here, both stick and let go like the minimize drag (`MagneticDrag`: 75% follow, one click at
72 dp, a flick over 800 dp/s also goes). That is the app's drag language since the haptics round,
and it says when letting go will act. Leaving fullscreen looks like YouTube's (0.95, capped at
30%). Entering, YouTube shows nothing, so here the page slides up beneath the video, with the
same 30% cap. The commit is `toggleFullscreen()`, the button's own, and the pull comes off in
`onConfigurationChanged` (700 ms fallback when no rotation comes).

## Brightness and volume

- **Zones:** ReVanced's 3/8 | 1/4 | 3/8, which is what most people who swipe on YouTube learned.
  The centre quarter keeps YouTube's swipe down out of fullscreen. Fullscreen only, like
  ReVanced, NewPipe and LibreTube (portrait's vertical swipes are minimize and fullscreen).
- **Range:** the full range over 75% of the video's height (NewPipe 75%, VLC 80%, LibreTube
  70% of the short side; ReVanced's 100 dp is the outlier).
- **Brightness** is the window's (`screenBrightness`), never the phone's setting. It holds only
  while the player is fullscreen and in front, so portrait, PiP and every other screen keep the
  phone's own. It moves along the quick-settings slider's curve (AOSP `BrightnessUtils`), so half
  way up looks like half way up the system slider. A swipe starts from the phone's level (the
  setting, read through the same curve); with adaptive brightness on that is the last manual
  level, the closest public value. The level comes back on the next fullscreen for 4 hours (as
  NewPipe does: "a viewing block"), then the phone's own is the better guess.
- **Volume** is `STREAM_MUSIC` in the phone's own steps, flags 0, so no system panel (this pill
  replaces it). When the hearing-safety limit holds a step back, one retry with `FLAG_SHOW_UI`
  lets the system ask (VLC does the same).
- **Pill:** icon and a thin bar at the top centre, where the 2x and "Release to cancel" pills sit
  (ReVanced's default overlay is a top pill too); it lingers 700 ms after the finger lifts.
- **Haptics:** a tick per volume step and at both ends of the brightness range: the seek bar's
  "boundary" tick. None of the other apps vibrate here; this follows the app's own vocabulary.

## Seek

- No YouTube client has a plain sideways swipe to seek (YouTube's 2021 "slide to seek" needed a
  long press first, which is now 2x). The video players all have one: VLC (a curve in
  centimetres, seeks on release), mpv (150 s per screen width, live), Just Player (speed-based,
  live).
- Here it drives the seek bar's own scrub: the controls come up and step aside, the bar's pill
  shows the time and chapter, chapter lines tick, coming back to the start arms "Release to
  cancel", and the seek happens on release (no googlevideo fetch per move).
- The time follows the finger's travel, not the track: 100 ms per dp plus (dp / 100)^3 x 10 s
  (`PlayerTimeBar.swipeOffsetMs`). About a centimetre (72 dp) is ~11 s, a double tap; 250 dp is
  ~3 min; across a portrait screen ~10 min. VLC's shape, so a short swipe nudges and a long one
  crosses a film, the same on any length of video. A drag on the bar stays the coarse,
  direct one.
