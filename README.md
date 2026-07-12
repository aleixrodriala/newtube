# NewTube

**A smartphone-optimized YouTube client for Android — a touch-first port of [SmartTube](https://github.com/yuliskov/SmartTube).**

NewTube takes SmartTube's powerful, ad-free YouTube engine — its InnerTube
backend, ExoPlayer-based playback, SponsorBlock/DeArrow/Return-YouTube-Dislike
integrations, and account system — and rebuilds the interface from the ground up
for **phones and tablets**: portrait-first layouts, bottom navigation, touch
gestures, and a minimizable mini-player, instead of SmartTube's TV/D-pad
(Leanback) UI.

> ⚠️ **Status: early, active development.** The backend, player engine, and
> account login are reused from SmartTube and work today; the touch UI is being
> built in waves. Not yet ready for daily use. See the roadmap below.

---

## Why a separate project?

SmartTube is built for Android TV — couch UI, remote control, leanback widgets.
NewTube keeps everything that makes SmartTube great under the hood but delivers a
native **smartphone** experience. It is a derivative work, not a replacement, and
all credit for the heavy lifting (the YouTube/InnerTube engine, stream decoding,
features) belongs to SmartTube.

## Relationship to SmartTube & license

NewTube is a fork/derivative of **SmartTube by Yuri Liskov (@yuliskov)** and is
distributed under the **MIT License** — the same license as SmartTube
(MIT © 2020–present yuliskov). The original SmartTube README is preserved at
[`docs/UPSTREAM_README_SmartTube.md`](docs/UPSTREAM_README_SmartTube.md), the
license is in [`LICENSE`](LICENSE), and SmartTube is tracked as the `upstream`
git remote.

Huge thanks to @yuliskov and all SmartTube contributors. Please support the
upstream project: https://github.com/yuliskov/SmartTube

## Architecture & roadmap

The port reuses SmartTube's MVP core (presenters, data, player engine, account
login) and replaces only the Leanback UI with touch screens.

- [`docs/mobile-port/ARCHITECTURE.md`](docs/mobile-port/ARCHITECTURE.md) — what we
  keep vs reuse vs rebuild, and the exact seams the touch UI attaches to.
- [`docs/mobile-port/ROADMAP.md`](docs/mobile-port/ROADMAP.md) — the sequenced
  waves toward full feature parity on touch.

**Tech choices:** Android Views + Material Components, Activity-per-screen
navigation (reusing SmartTube's `ViewManager`), Java/Kotlin, minSdk 21.

## Building

Requirements: JDK 17, Android SDK (compileSdk 34, build-tools 30.0.3). Point
`local.properties` at your SDK (`sdk.dir=...`).

```bash
# Build the mobile (touch) debug APK
./gradlew :smarttubetv:assembleStmobileDebug

# Output (per-ABI + universal):
#   smarttubetv/build/outputs/apk/stmobile/debug/
```

Install the universal APK on a connected device:

```bash
adb install -r smarttubetv/build/outputs/apk/stmobile/debug/*universal*.apk
```

The original TV build flavors (`stbeta`, `ststable`, `stfdroid`) remain intact and
continue to build unchanged.

## Credits & third-party data

NewTube stands on other people's work. Full notices are in
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md); the essentials:

- **SmartTube** (© yuliskov, MIT) — the upstream this project forks. Huge thanks;
  please support it: https://github.com/yuliskov/SmartTube
- **SponsorBlock** and **DeArrow** — community data © their contributors,
  licensed **CC BY-NC-SA 4.0**. NewTube uses this data unmodified and
  non-commercially. (The non-commercial term is one reason NewTube takes no
  donations, ads, or paid tiers.)
- **Return YouTube Dislike** — dislike data via its public API.
- Bundled libraries: DoubleTapPlayerView (MIT), Slidr/SlidableActivity
  (Apache-2.0), filepicker-lib (Apache-2.0); j2v8 (EPL-1.0) and Commons IO
  (Apache-2.0) via SharedModules.

---

*NewTube is an independent, unofficial fork. It is not affiliated with,
sponsored, or endorsed by Google, YouTube, or SmartTube's developer. "YouTube"
and "Android" are trademarks of Google LLC.*
