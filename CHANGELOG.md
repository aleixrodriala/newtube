# NewTube Changelog

All notable user-facing changes to NewTube ("SmartTube for phones").

## Unreleased

- Entering picture-in-picture from the gear menu no longer flashes the whole
  watch page squeezed inside the shrinking window — the animation now shows
  only the video, like the official app.

### Subtitles, done right

- The CC button now toggles subtitles on/off like the official app, with a
  confirmation snackbar ("Subtitles on (English)" / "Subtitles off") and a
  filled-vs-outlined icon showing the current state at a glance.
- New native subtitles picker (long-press CC, or gear → Subtitles): one flat
  track list with a leading check on the active choice, plus a "Caption
  style & size" shortcut. Replaces the old TV-style dialog.
- Captions finally look like YouTube's: white regular text on a per-line
  semi-transparent scrim, sized relative to the video (small under the
  portrait watch page, larger in fullscreen). Existing installs are migrated
  off the old yellow/bold TV default once; a style you picked yourself after
  the update sticks.
- Quality/audio picker rows now use the same leading-check anatomy as the
  official app.
- New native playback-speed picker in the gear menu: 0.25x–2x presets with
  "Normal" for 1x, official-app style, with the same confirmation snackbar;
  the full extended speed list lives behind "More speeds". The gear row now
  shows the current speed as "Normal"/"1.5x".

## 1.5.0 — 2026-07-20

Two big rounds: casting to the TV (without Play Services), and a deep
simplification of the whole UI modeled on the official YouTube app.

### Cast to TV
- **Cast to your TV with no ads.** New Cast button on the home screen and in
  the player. The default mode streams the video through your phone straight
  to the Cast device — completely ad-free, no Google Play Services involved.
- **Or use the TV's YouTube app**: every Cast/DIAL TV also offers the classic
  mode (the TV's own YouTube app plays; your phone is the remote), and
  TVs that can't be reached directly can be linked with a 12-digit TV code.
- One tap connects; if ad-free casting can't handle a video (e.g. live
  streams), the session falls back to the TV's YouTube app automatically.
- Control the **TV's volume** from the phone, see "Playing on <TV>" in the
  player and a persistent notification with a disconnect action, and a subtle
  pulse animation while a session is connecting.

### Simpler, cleaner UI
- **Bottom navigation is now Home / Subscriptions / History / You**, styled
  and metered like the official app. The side drawer, the hamburger icon,
  and the top-bar settings icon are gone — the top bar is just the title,
  Cast, and Search.
- **New "You" tab**: your account (real profile picture, name, email), your
  content (Channels, Playlists, My videos), an "Explore" group with the
  discovery feeds (Kids, Sports, LIVE, Gaming, News, Music), and Settings —
  all in one place, like YouTube's You page.
- **Shorts are gone**: the Shorts tab was removed and Shorts no longer
  appear in the Home or Subscriptions feeds (History still shows watched
  ones).
- **The player went from 11 overlay icons to 8**, and the gear now opens a
  YouTube-style sheet: Quality with its live value ("Auto (1080p60)"),
  Playback speed, Picture-in-picture, Rotate lock — and everything else
  nested under "More", each row with a proper icon.
- Long-press a bottom tab or a You row for section management
  (rename / move / refresh / clear history — nothing was lost with the
  drawer).
- **Pinch to zoom** in fullscreen: snap between "Zoomed to fill" and
  "Original", exactly like the official app.

## 1.4.2 — 2026-07-19

- **Fixed the real "thumbnail flicker" on minimize**: the feed card of the
  video you just watched visibly blinked/reloaded the moment the minimize
  gesture ended (the resume-time watch-progress sync was rebinding the whole
  card). Now only the red progress bar updates, in place.

## 1.4.1 — 2026-07-19

- **Hotfix: 1.4.0 crashed on every player minimize.** The 1.4.0 "wrong-size
  video snap" fix released the player's video surface out from under the
  mini-player and was rolled back; minimize, expand, and close all work
  again. (The cosmetic snap fix returns in 1.4.2 done properly — see above.)

## 1.4.0 — 2026-07-18

The polish round: the app now looks and moves like a native phone video app,
feeds load in a fraction of the time, and picture-in-picture finally behaves.

### Feeds & startup
- **Feeds paint instantly.** Sections are snapshotted to disk, so a cold start
  shows your Home feed in ~0.6 s — before the first network request even
  leaves. Switching between Home/Subscriptions tabs within 5 minutes no longer
  refetches anything, and the restored grid still paginates when you scroll.
- **Subscriptions appear after a single request** (~0.4 s on Wi-Fi) instead of
  waiting for five serial ones.
- Faster video opens: warm open tap-to-first-frame 1.7 s → 1.1 s, cold open
  from a link 4.6 s → 2.8 s (measured on a Pixel 9).
- API traffic is now brotli-compressed and the connection to YouTube is warmed
  up at app start.

### Mini-player & picture-in-picture
- The mini-player now docks onto whatever screen you came from — Search,
  Channel, or uploads — instead of always yanking you back to Home, and the
  back button no longer reveals a buried fullscreen player or corrupts the
  back stack. Your mini session survives backgrounding and relaunch.
- The minimize animation is smooth: the brief "wrong-size video snap" on the
  docked card is gone.
- **Closing the PiP window actually closes the video** on Android 16 — audio
  no longer keeps playing forever with no way to stop it.
- Swiping home while watching no longer makes the PiP window instantly bounce
  back to fullscreen, and the watch-page UI no longer leaks into the tiny
  window.

### UI
- Bottom navigation bar, toolbar, and spacing now match the real YouTube app's
  metrics, measured side-by-side on a Pixel 9.
- Peeking at your notifications in fullscreen no longer minimizes the player —
  only a mid-screen swipe-down does.
- Assorted feed, search, and player visual polish; more consistent card and
  suggestion layouts.

### Reliability & efficiency
- Playback recovers automatically from YouTube "bot check" interruptions, and
  a smarter mix of API clients further reduces mid-playback 403 errors.
- Fixed a case where a recoverable error wrongly dropped you to audio-only.
- Less background battery and data: live-chat polling stops while the chat
  sheet is closed (previously ~700 invisible requests/hour on a backgrounded
  live stream), an unused image host that failed on every watch-page open was
  removed, and a per-video storyboard fetch the phone UI never used is gone.
- The in-app updater no longer re-downloads an APK you already have pending,
  and checks for updates at most every 12 hours.

## 1.3.1 — 2026-07-13

The mobile-network round: fixes for playback dying on carrier (LTE/5G)
connections, plus smarter audio-language selection and error recovery.

> **Note:** starting with this release the application ID changed to
> `io.github.aleixrodriala.arc`, so upgrading from an older build requires a
> one-time uninstall/reinstall.

### Playback on mobile networks
- **Fixed videos dying exactly 60 seconds in** (with visible reloads at 60 s /
  120 s / 180 s) on carrier networks: YouTube's servers enforce integrity
  checks on those connections, and only properly attested requests survive.
  Playback now routes through attested clients first, with the attestation
  warmed up at app start so opens stay fast.
- Live streams no longer 403 instantly on those networks, and keep their DVR
  window.
- Fixed an infinite error-reload loop that could replay the same few seconds
  of a video forever: reloads now resume at the exact position where playback
  died, repeated failures at the same spot stop after a few attempts instead
  of looping, and a pinned video or audio quality that keeps failing is
  temporarily released so playback continues on an alternative.

### Audio & background playback
- **Multi-language videos now play the correct audio track.** A saved audio
  preference no longer accidentally pins an auto-dubbed track; the original
  language variant is preferred when your saved pick isn't available.
- Background (screen-off) listening no longer downloads and decodes the video
  stream — pure audio chunks only, which saves substantial data and battery.
  Video returns instantly on wake.

### Error recovery
- Losing connectivity now shows a friendly "no connection" message instead of
  a raw error dump, playback retries automatically once when the connection
  comes back, and tapping play retries manually.
- Faster recovery after rebuffering on a starved connection (median stall
  3.2 s → 1.7 s), and quality now steps down properly when bandwidth
  collapses mid-stream.
- Opening a shared link for a video the app already had in its task no longer
  silently does nothing.
- A brief audio-focus steal right after an error recovery no longer leaves the
  player paused.

### Branding & legal
- New launcher icon (the "arch-n" mark) and neutral branding, rewritten
  privacy policy, MIT license, and third-party attributions.

## 1.3.0 — 2026-07-12

Live DVR and the first performance-loop round.

- **Live streams are fully seekable.** LIVE chip, a DVR window you can scrub
  back through (hours deep), and the chip dims when you're behind the edge and
  jumps back to live on tap. Previously live videos could instantly end and
  auto-advance to something unrelated.
- **Autoplay-next is near-instant**: the next video's stream is pre-built
  while the current one finishes — first frame in ~0.35 s instead of ~2.6 s.
- The player remembers its bandwidth estimate across restarts, so quality no
  longer ladder-walks up from the bottom after every app start.
- The **Video buffer setting (Low/Medium/High/Highest) now actually works**;
  it previously had no effect on the modern player engine.
- Fewer and faster API calls when opening videos: redundant TV-client
  fallbacks skipped, failed lookups aren't retried for 30 s, and a request
  logger that printed 18,500 log lines per session is off.

## 1.2.2 — 2026-07-12

- First working live playback on the new player engine, including the DVR
  manifest handling that 1.3.0 builds on.
- Open-latency work: manifest processing moved off the main thread, larger
  (512 MB) media cache, next-video prefetch actually wired up.

## 1.2.1 — 2026-07-11

- **Seeking fixed**: jumping forward could stall 5–16 s with no error; stalled
  requests now fail fast and retry, so seeks resume in ≤2 s.
- Endless-spinner fix: repeated playback errors now stop after 3 automatic
  reloads and show a real error instead of hammering YouTube forever.
- Media notifications work again on fresh Android 13+ installs (the app now
  asks for notification permission).
- Status bar and navigation bar are opaque again on Android 15/16 — no more
  player controls colliding with the clock or tab labels under the gesture
  pill.
- Background playback no longer silently loses its foreground-service grant
  when the engine restarts while the screen is off.
- Fixed live-stream segments poisoning the disk cache (all segments could
  collapse into one cache entry).

## 1.2.0 — 2026-07-11

The big one: NewTube became a true phone app.

- **Phone-only**: all Android TV code is gone. The universal APK dropped from
  ~90 MB to ~39 MB (release).
- **New playback engine**: androidx.media3 (modern ExoPlayer) with Cronet
  transport (HTTP/2 + QUIC), replacing the 2019-era TV fork engine. Real
  adaptive quality under "Auto" (the whole quality ladder, not one locked
  rung), stable disk caching across sessions, and a process-wide bandwidth
  meter that learns from every transfer.
- **Modern Android baseline**: targets Android 15, requires Android 7.0+
  (previously 5.0+).
- API connections use HTTP/2.
- **Sign-in fixes**: signing in no longer fails if you switch to the browser
  to approve the code (Android was cutting the app's network in the
  background), and a "Try again" button issues a fresh code with an honest
  error message.
- With the player pinned in picture-in-picture, opening Search (and other
  screens) no longer launches them *inside* the tiny PiP window.
- Smoother navigation: screens stay in one task, the player morphs between
  full and minimized states, and feeds show skeleton placeholders while
  loading.

## 1.1.0 — 2026-07-10

Tester-feedback round (baseline for this changelog).
