# Android Auto support

This document explains how the Android Auto screen works, how to build and test it, and what to
check when it does not.

> **Passenger use only.** The driver must not watch video while driving. The app is
> experimental and sideloaded; it is not affiliated with Google, Android Auto, YouTube, NewTube
> or SmartTube.

## Scope

The car screen is a **second display for the player that is already running on the phone**. It
does not start a second player, and it does not download or decode video by itself. The phone
keeps ownership of the `ExoPlayer`; the car receives the player's output surface.

This is deliberately a small integration:

- one `CarAppService`;
- one `Session` and one `Screen`;
- one process-wide bridge between the car screen and `MobilePlaybackActivity`;
- one preference (passenger mode);
- one manifest service and two Car App Library permissions.

## Supported setup

- Phone with Android 7.0 (API 24) or later.
- Android Auto app installed on the phone.
- Car or head unit with Android Auto **projected** mode. Tested on a Toyota Yaris 2025.
- USB cable, or wireless Android Auto if both sides support it.
- Sideloaded APK. The app is not on Google Play.

Android Auto's **Desktop Head Unit (DHU)** can be used for development without a car; see
[Testing without a car](#testing-without-a-car).

## Architecture

```text
┌──────────────────────── Android Auto host ────────────────────────┐
│ CarAppService binder     SurfaceCallback        ActionStrip       │
└──────────┬──────────────────────┬──────────────────────┬──────────┘
           │                      │                      │
           ▼                      ▼                      ▼
 NewTubeCarAppService      CarVideoScreen ────────► template / actions
           │                      │
           ▼                      │
   NewTubeCarSession ─────────────┘
                                  │ attach / detach surface
                                  ▼
                            CarDisplayBridge
                             (weak activity ref)
                                  │
                                  ▼
                        MobilePlaybackActivity
                            └── ExoPlayer
                                ├── phone Surface
                                └── car Surface
```

### Components

| File | Role |
|:--|:--|
| `NewTubeCarAppService.java` | `CarAppService` entry point. Declares `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR` because the app is sideloaded. |
| `NewTubeCarSession.java` | One Android Auto session; creates `CarVideoScreen`. |
| `CarVideoScreen.java` | `Screen` + `SurfaceCallback`. Builds the template and hands the host surface to the bridge. |
| `CarModePrefs.java` | Passenger-mode preference in `SharedPreferences` (`newtube_car` / `passenger_mode`, default `true`). |
| `CarDisplayBridge.java` | Process-wide bridge: weak reference to the activity and the current car surface. |
| `MobilePlaybackActivity.java` | Owns the `ExoPlayer`; switches the player's output between the phone surface and the car surface. |

### Why `NavigationTemplate`?

The Car App Library only gives a drawing surface to apps in the **navigation** category. A
`NavigationTemplate` is therefore the template that receives the surface. The app is not a
navigation app and does not draw a map; it uses the template's surface for video.

This is a deliberate workaround, not a supported "video app" category. Google or a car maker can
restrict it, and some hosts will not expose a surface to a third-party app at all.

### Why no second player?

Two players would fight over audio focus, the account session and the video cache. The car
screen instead follows the phone's live player. That also means:

- the car screen shows whatever the phone is playing;
- starting a video on the phone while the car screen is open attaches it to the car surface;
- stopping the car screen gives the phone its own surface back.

## Surface lifecycle

1. `CarVideoScreen` is created and registers itself with
   `carContext.getCarService(AppManager.class).setSurfaceCallback(this)`.
2. The host calls `onSurfaceAvailable(SurfaceContainer)`. The screen stores the `Surface`.
3. When the screen is started, it calls `CarDisplayBridge.attachCarSurface(surface)`.
4. The bridge stores the surface and, if `MobilePlaybackActivity` is alive, calls
   `activity.attachCarSurface(surface)`.
5. The activity sets the player's video surface to the car surface. The phone's own surface is
   kept so it can be restored later.
6. When the screen stops, it calls `CarDisplayBridge.detachCarSurface(surface)`. The bridge asks
   the activity to restore the phone surface.
7. When the screen is destroyed, it clears the `SurfaceCallback`, detaches the surface and drops
   its reference.

`MobilePlaybackActivity` also calls `CarDisplayBridge.attach(this)` in `onCreate` and
`CarDisplayBridge.detach(this)` in `onDestroy`. The weak reference prevents a destroyed activity
from being revived through the bridge.

## Passenger mode

`CarModePrefs` is a boolean preference, **on by default**. It is the only switch that controls
whether the car screen may show video.

The car screen evaluates it in `onGetTemplate()`:

| State | Template |
|:--|:--|
| Passenger mode off | `MessageTemplate` with a "Turn on passenger mode" action. |
| Passenger mode on, no player or no video | `MessageTemplate` with a "Turn off passenger mode" action and a hint to start a video on the phone. |
| Passenger mode on, player and video available | `NavigationTemplate` with the live surface and the action strip. |

The screen polls once per second while it is started and calls `invalidate()` only when one of
these changes: passenger mode, player availability, `playWhenReady`, or the video id. This keeps
the template and the action strip in sync without rebuilding the surface on every tick.

The same preference appears in the phone's Settings as **Android Auto passenger mode**
(`mobile_settings_car_passenger`). It can be turned off from either place.

## Car UI

The `NavigationTemplate` action strip contains four actions:

| Action | Icon | What it does |
|:--|:--|:--|
| Previous | `ic_player_previous` | Plays the previous item; if there is no queue, seeks back 10 seconds. |
| Play / Pause | `ic_player_play` / `ic_player_pause` | Toggles playback. The icon and title follow `playWhenReady`. |
| Next | `ic_player_next` | Plays the next item; if there is no queue, seeks forward 10 seconds. |
| Menu | `ic_player_queue` | Opens `CarMenuScreen`: Home, Subscriptions, History, Playlists, Trending and the passenger-mode toggle. |

Previous and Next have no title (icon only): with four titled actions the host cuts the last
title ("Menu") down to "...". While the "video unavailable" card is shown, Play / Pause is left
out so "Menu" keeps its label.

The host hides the action strip after about 10 seconds without interaction and shows it again
on a tap. That timeout belongs to Android Auto; apps cannot keep the strip on screen.

### Gestures on the video

The host only forwards surface gestures to apps whose **map action strip contains
`Action.PAN`**, so the template sets one (the host does not draw it as a visible button here).

| Gesture | Callback | What it does |
|:--|:--|:--|
| Tap | `onClick` | Only brings the action strip back. It never pauses or seeks. |
| Double tap on the right / left third | `onScale` | Seeks forward / back 10 seconds and shows a toast ("+10 s"). Double taps on the same side within 2 seconds add up (+20 s, +30 s...). |
| Horizontal fling | `onFling` | Seeks 10 seconds in the fling direction. |

The host turns a double tap into a zoom gesture, which is why it arrives as `onScale` and not as
two `onClick`s. A pinch sends a stream of `onScale` events; events closer than 400 ms count as
one gesture, so a pinch seeks once. Gestures need Car App API level 5 or later; on older hosts
the action strip is the only way to control playback.

## Manifest and permissions

`smarttubetv/src/stmobile/AndroidManifest.xml` declares:

```xml
<uses-permission android:name="androidx.car.app.ACCESS_SURFACE" />
<uses-permission android:name="androidx.car.app.NAVIGATION_TEMPLATES" />

<service
    android:name="com.newtube.mobile.car.NewTubeCarAppService"
    android:exported="true">
    <intent-filter>
        <action android:name="androidx.car.app.CarAppService" />
        <category android:name="androidx.car.app.category.NAVIGATION" />
    </intent-filter>
</service>

<!-- Must be a child of <application>, not of <service>. -->
<meta-data
    android:name="androidx.car.app.minCarApiLevel"
    android:value="1" />

<meta-data
    android:name="com.google.android.gms.car.application"
    android:resource="@xml/automotive_app_desc" />
```

`res/xml/automotive_app_desc.xml`:

```xml
<automotiveApp>
    <uses name="template" />
</automotiveApp>
```

Both permissions are **normal** permissions and are granted at install time. If
`androidx.car.app.NAVIGATION_TEMPLATES` is missing, the host can reject the template when the app
is opened; this was the cause of the first "app opens with an error" report.

To verify an installed APK:

```bash
aapt2 dump xmltree app-debug.apk --file AndroidManifest.xml | grep -E \
  'ACCESS_SURFACE|NAVIGATION_TEMPLATES|NewTubeCarAppService|automotive_app_desc'
```

## Build

Requirements:

- JDK 17 or later (the project is verified with JDK 21).
- Android SDK 37 and build-tools 36/37.
- Git submodules `MediaServiceCore` and `SharedModules`.

```bash
git clone --recurse-submodules https://github.com/aleixrodriala/newtube.git
cd newtube
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew :smarttubetv:assembleStmobileDebug
```

If the submodules were not cloned:

```bash
git submodule update --init --recursive
```

Output:

```text
smarttubetv/build/outputs/apk/stmobile/debug/
smarttubetv/build/outputs/renamed_apks/stmobileDebug/
```

The CI workflow at `.github/workflows/android-auto.yml` builds the same debug APK and uploads it
as an artifact.

## Tests

```bash
./gradlew :smarttubetv:testStmobileDebugUnitTest \
  --tests 'com.newtube.mobile.car.CarModePrefsTest'
```

`CarModePrefsTest` is a Robolectric test. It checks that the preference defaults to on and that
it can be turned off and on again. It does not need a car or a phone.

## Install on a car

1. Install the APK on the phone.
2. Open **Android Auto** on the phone.
3. Tap **Version** ten times to enable **Developer mode**.
4. Open **Developer settings** and enable **Unknown sources**.
5. Connect the phone to the car with a data-capable USB cable.
6. Open **NewTube** from the Android Auto launcher.
7. Start a video on the phone.
8. Use the action strip on the car screen.

Android Auto may hide a sideloaded car app that it does not believe came from Google Play. If
NewTube does not appear, install the APK through the system installer with Google Play named as
the installer (copy the APK to the phone first, then use its `content://` MediaStore URI):

```bash
adb push NewTube.apk /sdcard/Download/NewTube.apk
adb shell content query --uri content://media/external/file \
    --projection _id --where "_display_name='NewTube.apk'"
adb shell am start -a android.intent.action.INSTALL_PACKAGE \
    -d content://media/external/file/<id> \
    --es android.intent.extra.INSTALLER_PACKAGE_NAME com.android.vending
```

If the app still does not appear, see [Troubleshooting](#troubleshooting).

## Testing without a car

Android Auto's **Desktop Head Unit (DHU)** runs the projected Android Auto experience on a
computer. It is part of the Android Auto SDK and is useful for checking that the app appears in
the launcher and that the surface is created.

Typical flow:

```bash
# with the phone connected over ADB
adb forward tcp:5277 tcp:5277
# run the DHU from the Android Auto SDK
desktop-head-unit
```

The exact DHU path and commands depend on the Android Auto SDK version. The DHU is a development
tool; it is not a substitute for testing on the target car.

## Troubleshooting

| Symptom | Likely cause | What to check |
|:--|:--|:--|
| NewTube is not in the Android Auto launcher | Developer mode / unknown sources off; app installed for another user; stale Android Auto cache | Enable both settings; reinstall for the connected user; restart Android Auto and reconnect. |
| The app opens with an error | `minCarApiLevel` not declared at application level (log: `Min API level not declared in manifest`); missing `NAVIGATION_TEMPLATES`; Android Auto too old | Check the manifest with `aapt2 dump xmltree`; update Android Auto; capture logcat. |
| The car screen stays black | No player, passenger mode off, or the host did not provide a surface | Start a video on the phone; check passenger mode; check `onSurfaceAvailable` in logcat. |
| Video plays on the phone but not on the car | Surface not attached; host does not expose a surface; the car screen is in the background | Check `CarDisplayBridge.attachCarSurface`; bring the car app to the foreground; try a different car/AA version. |
| The car controls do nothing | No active player; the host does not forward surface gestures | Use the action strip; start a video; check that the player is alive. |
| The app disappears when the car starts moving | The host hides apps that are not allowed while driving, or passenger mode is off | Turn passenger mode on; check the host's restrictions. This is not guaranteed to work on every car. |
| Install fails with a signature mismatch | A different debug key signed the previous build | Uninstall the old app and install the new APK. |
| The phone app crashes on launch | A code or resource problem unrelated to the car | Capture logcat; see below. |

### Logcat

`logcat` dumps can contain NUL bytes; use `grep -a`:

```bash
adb logcat -c
# reproduce the problem
adb logcat -d | grep -a -iE "AndroidRuntime|app.smarttube|newtube|carapp|CarAppService|FATAL|Exception"
```

If you have more than one device connected, pin the serial:

```bash
adb -s DEVICE_SERIAL logcat -d | grep -a ...
```

## Known limitations

- Experimental. The integration has been tested on one car (Toyota Yaris 2025) and one phone.
- `NavigationTemplate` is a workaround for getting a surface. It is not a supported video
  category, and a host may restrict it.
- The car screen is a mirror of the phone's player, not an independent player. The phone must
  stay connected and the player must stay alive.
- The host controls the surface size and shape. The app does not rotate the video, but it does
  keep the video's aspect ratio (see below).
- Some Android Auto versions and some cars do not expose a surface to third-party template apps.
- Passenger mode is a preference, not a legal permission. The driver is responsible for not
  watching video while driving.
- Sideload only. There is no Play Store release.

## Full-screen video without stretching

The host gives the app one surface the size of the whole car screen (800×400 on the desktop
head unit, often wider in real cars). If the player drew straight into it, a 16:9 video would
be stretched to fit.

`CarLetterbox` avoids that:

1. The host surface becomes a private `VirtualDisplay`.
2. A `Presentation` on that display holds an `AspectRatioFrameLayout` in **zoom** mode with a
   `SurfaceView` inside.
3. The frame covers the full height of the screen and the width the host leaves to the app
   (`SurfaceCallback.onVisibleAreaChanged`; in split-screen layouts that is only part of the
   width). The host's buttons float over the video, so no room is reserved for them.
4. The player draws into that `SurfaceView`.

Zoom mode keeps the video's shape and covers the whole frame: what does not fit (a thin strip
at the top and bottom of a 16:9 video on a wider screen) is cropped. The aspect ratio is read
from the player (`ExoPlayer.getVideoSize()`), so vertical videos work too. If a phone cannot
create the virtual display, the screen falls back to the raw surface (stretched) instead of
failing.

## The car menu

The **Menu** button on the player screen pushes `CarMenuScreen`, a `ListTemplate` with the main
NewTube sections: Home, Subscriptions, History, Playlists and Trending, plus the passenger-mode
switch. Android Auto allows four buttons on the player screen, so Menu replaced the old
"Turn off passenger mode" button, which now lives in the menu.

Each section opens `CarVideoListScreen`:

- It loads the section through the app's own `ContentService` (the same data as the phone).
- It shows as many rows as the host allows (`ConstraintManager.CONTENT_LIMIT_TYPE_LIST`,
  usually fewer while driving), then loads the thumbnails and redraws once.
- Tapping a video calls `PlaybackPresenter.openVideo`, the same call the phone app uses, and
  returns to the player screen; the car screen shows the new video.
- Tapping a playlist or channel opens its videos in another list.

Android Auto can start NewTube without opening any phone screen. The phone app normally sets up
its YouTube services (account, language, proxy) on its first screen, so `NewTubeCarSession`
does the same minimal setup when the car session starts; otherwise every list would fail.

## References

- Android for Cars App Library: <https://developer.android.com/training/cars/apps>
- `NavigationTemplate`: <https://developer.android.com/reference/androidx/car/app/navigation/model/NavigationTemplate>
- `AppManager`: <https://developer.android.com/reference/androidx/car/app/AppManager>
- `SurfaceCallback`: <https://developer.android.com/reference/androidx/car/app/SurfaceCallback>
- Android Auto testing and the DHU: <https://developer.android.com/training/cars/testing>
