# Android Auto

NewTube on the car screen, in two entries that share one player (`com.newtube.mobile.auto`):

- **Car app** (Car App Library, navigation category): Subscriptions, Home, Continue watching,
  Playlists (Watch later included), History and Search as car lists, and a player screen. The
  navigation category is the only one that gets a drawing surface, so video is drawn there,
  **only while the car is parked**. Moving, the video track is switched off (no video data is
  fetched) and the screen shows cover, title and a "sound only" note; the sound keeps playing.
- **Media app** (`MediaBrowserService`): the same feeds in Android Auto's music screen, sound only,
  plus voice search ("play X on NewTube").

## Parked or moving (`DrivingState`)

- The car's own speed (`CarHardwareManager`, permission `CAR_SPEED`, from `app-projected`).
  Cars report speed on change, so the last value holds while the listener is registered.
- Fallback: the phone's GPS speed (location permission; "Allow all the time" is needed because
  the phone app is in the background while Android Auto runs).
- No source, or any source above ~5 km/h: sound only. Parked = every live source under ~2 km/h
  for 2 s.

## Player (`AutoPlayer`)

Its own media3 player built by `Media3PlayerInitializer` / `Media3PlayerController`, so the car
uses the phone's network stack (Cronet, SABR, DASH, live) without `MobilePlaybackActivity`. Stream
choice mirrors `VideoLoaderController`. A `MediaSessionCompat` serves the steering-wheel buttons
and the media app. Android Auto can start the process without Splash, so `AutoCarAppService`
runs Splash's one-time setup (account store, global Rx error handler, proxy).

## Install note (sideloaded builds)

Android Auto hides Car App Library apps that were not installed by Google Play, even with its
developer setting "Unknown sources" on. The media entry only needs "Unknown sources". For the
video screen, install with the Play installer mark:

    adb install -r -i com.android.vending NewTube_<version>_universal.apk

or with UnknownAuto (Shizuku, on the phone).

## Tested

Pixel 10a (Android 17) with Google's Desktop Head Unit 2.1 (`config/default_sensors.ini`, the
`speed` command): launcher entry, feeds with thumbnails, playback, video parked / sound moving /
video back when parked, switching to Spotify, Maps and Waze, reconnecting, and a force-stopped
process. Also used in a Toyota Yaris 2025 (wireless Android Auto).
