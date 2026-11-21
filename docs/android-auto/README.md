# Android Auto

(Guia de instalação em português: [LEIA-ME.md](LEIA-ME.md).)

NewTube on the car screen, in two entries that share one player (`com.newtube.mobile.auto`):

- **Car app** (Car App Library, navigation category): Subscriptions, Home, Continue watching,
  Playlists (Watch later included), History and Search as car lists, and a player screen. The
  navigation category is the only one that gets a drawing surface, so video is drawn there,
  **only while the car is parked**. Moving, the video track is switched off (no video data is
  fetched) and the screen shows cover, title and a "sound only" note; the sound keeps playing.
  The driver can override the gate: pressing the video button twice within seconds turns on
  "video always" (`AutoPrefs`, persists across connections, at the user's own risk); a third
  press restores the parked gate. With it on, no speed/location permission is needed or asked for.
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

## Install (outside Google Play)

Android Auto only lists car apps it believes came from Google Play. Installing the APK the usual
way (tapping it in a file manager, or `adb install`, even with `-i com.android.vending`) is
recorded as installed by that app or by the shell, and Android Auto refuses it
(`CAR.VALIDATOR: Package DENIED; failed all other checks`): the car then shows only the sound
entry, never video. Its developer setting "Unknown sources" does not cover Car App Library apps.

What Android Auto accepts is an install made by the phone's own system installer with Google
Play named as the installer. `scripts/android-auto` does exactly that, from a computer:

1. On the phone: Settings > About phone > tap "Build number" 7 times, then
   Settings > System > Developer options > **USB debugging** on.
2. Put the NewTube APK in the same folder as the scripts — optional: with no `NewTube*.apk`
   next to them, the installer downloads this release's APK into the folder by itself
   (`NEWTUBE_AUTO_APK_URL` overrides the URL; you can also pass a path or URL as argument).
3. Plug the phone in with a cable and run **`Install.bat`** (Windows, double-click) or
   **`./install.sh`** (macOS / Linux). Tap "Allow" on the phone's USB debugging prompt.
4. On the phone: **Install / Update**, and if Google Play Protect warns: More details >
   **Install anyway**.
5. Reconnect the car. NewTube is in Android Auto's app grid.

The script downloads Google's `adb` by itself when it is not installed, and also works over
wireless debugging when the phone is already paired. **Every update must go through the script
too**: an update installed the usual way replaces the record and Android Auto hides the app again.

Check (`adb shell dumpsys package <package>`): `installerPackageName=com.android.vending` and
`initiatingPackageName=com.google.android.packageinstaller`.

## Car log

`Android/data/<package>/files/newtube-auto.log` on the phone: connections, parked/moving
changes with the speed source, each video opened (route, video on/off) and whether the picture
came back after parking. If no frame arrives 2.5 s after parking, the player reopens the video at
the same second.

## Tested

Pixel 10a (Android 17) with Google's Desktop Head Unit 2.1 (`config/default_sensors.ini`, the
`speed` command): launcher entry, feeds with thumbnails, playback, video parked / sound moving /
video back when parked, switching to Spotify, Maps and Waze, reconnecting, and a force-stopped
process. Also used in a Toyota Yaris 2025 (wireless Android Auto): six parked/moving changes in
one drive, picture back each time (0.6-2.1 s, once through the reopen).
