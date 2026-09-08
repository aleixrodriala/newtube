# NewTube Changelog

All notable user-facing changes to NewTube ("SmartTube for phones").

## 1.8.1 — 2026-09-08

### SABR ships as an optional playback source — off by default

- NewTube can now play a YouTube response that carries **no direct links** —
  video and audio tracks with only a streaming endpoint — using SABR. Both SABR
  switches are in Settings and both are **off**: "Play videos that have no
  direct links" and "Prefer SABR even when links work".
- **Why it is off.** It was built on by default and turned off before release,
  because it never once carried a video that would not otherwise play. In seven
  normal openings the client NewTube uses always returned working links, so the
  fallback was never reached. When it was forced onto the path, the server
  answered every request by asking for the video page to be reloaded, and
  playback failed. Turning it on is also not free: NewTube stops asking further
  clients for that video, and spends its retry budget on SABR before trying
  anything else. It stays off until it can finish a playback.
- **Nothing you see today changes.** Videos still open the way they did in
  1.8.0, at the same speed, and the playback error that does occasionally happen
  is still handled by the existing client retry — not by SABR.
- For anyone who wants to try it: SABR uses about 11% fewer bytes and reaches
  the first frame about 66 ms later, measured over six openings per source on
  Wi-Fi.
- Three faults in the SABR implementation itself were fixed on the way. The
  previous build could never receive SABR video at all, because the source was
  only offered for signed-in TV responses - the one client whose media endpoint
  answers every request with an empty HTTP 403. A video request also has to name
  its companion audio track and declare it already downloaded, and a response
  that deliberately carries no video (the server pacing a client that is far
  enough ahead) is a wait, not a failure.

### Still limited

- Turned on, the fallback cannot finish a playback, and now we know why: for the
  clients it uses, YouTube serves only the **first minute** of a video without a
  device attestation NewTube cannot produce. Past roughly 60 seconds the server
  simply returns no video. Resuming a part-watched video starts past that line,
  which is why it failed immediately in testing.
- SABR speed and data use were measured on one video, one network and one phone;
  there is no evidence yet for long playbacks, mobile data or battery use.
- Everything listed under 1.8.0 below still applies, except that SABR is no
  longer test-only.

## 1.8.0 — 2026-09-08 — Eugenio Edition

“Saben aquell que diu… que el vídeo no arrancaba.” A fictional homage to
Eugenio, with fewer long pauses from the player. This release collects all ten
main-repository commits and the dependency changes since 1.7.0 (`1997fdb`,
4 August), plus the final upstream correctness fixes listed below.

### Playback startup and data use

- Related-video thumbnails request an appropriately sized CDN image instead of
  downloading a large image and shrinking it afterward. Related rows are loaded
  in windows; image downloads have bounded concurrency and a mobile-friendly timeout.
- The loading still prefers cached artwork and avoids a separate full-resolution
  download. Watch-page layout, images and nonessential work are coordinated with
  playback startup; cancelled opens cannot publish stale UI or media sources.
- Initial automatic quality now uses recent, measured bandwidth. Old or invalid
  estimates expire, network changes reset confidence, and quality can climb as
  useful transfers complete. Estimates are saved during playback, not just on exit.
  Explicit manual-quality choices are preserved.
- Adaptive down-switch thresholds now follow the selected buffer preset, so a
  weak connection can reduce quality before the buffer runs dry.
- Player/network infrastructure warms off the UI thread. Warmup cannot start a
  competing fetch after the user has already opened a video. Media-host warmups
  are bounded, shared while in flight and retriable after failure or network change.
- Nested metadata mapping reuses the parsed JSON tree instead of reparsing every
  child. The dense regression fixture needs one text parse instead of 76; this
  is a reduction in parser work, **not** a 76× playback speedup.
- Pixel weak-link comparisons observed approximately **0.4–0.7 seconds less time
  to READY** in the startup-bandwidth round. Samples are small and initial quality
  is deliberately lower; this is not a universal or direct-LTE speed guarantee.

### Network failures, recovery and playback UI

- Cronet remains the primary media transport, with Media3's OkHttp adapter as the
  fallback instead of the slower plain HTTP path. API calls are bounded while bulk
  transfers retain their own budgets; connections can be reused.
- A tunnel can leave Android reporting a validated network even though no request
  gets through. Such transport failures now remain eligible for bounded recovery;
  actual server refusals are distinguished from connectivity loss.
- A validated replacement network wakes recovery even without an `onLost` callback.
  Cancelled callbacks cannot revive an old playback episode. In-player switches
  cancel abandoned requests and source builds.
- Existing playback-route cooldowns survive process restarts, avoiding repeated
  known failures. An unavailable video alone no longer counts as evidence that an
  account route is broken. Live-manifest lookup avoids unnecessary round trips.
- A pre-media failure has a visible, persistent explanation and Play can retry.
  Denied opens clear unrelated suggestions instead of leaving a misleading watch
  page. Opening the player without a video returns to Home rather than a dead 00:00.
- Network errors from watch metadata no longer throw raw stack traces over playback.
  Buffering no longer permanently disables the user's subtitles. The feed has an
  offline/retry state, and position restoration preserves valid requested timestamps.
- Notification/lock-screen artwork shares in-flight requests, rejects stale images
  after video switches and updates when artwork arrives. Batched player events
  avoid rebuilding identical session metadata repeatedly.

### Casting and TV pairing

- Recommended receiver priority is paired **SmartTube → direct Cast → unidentified
  saved TV apps → stock YouTube**. Explicit receiver choices remain explicit;
  falling back to stock YouTube warns that it may show ads.
- One physical TV can retain several receiver identities without duplicate rows
  or losing a SmartTube pairing when discovery updates. Ambiguous devices stay
  separate, and old pairings are not guessed to be SmartTube just from their names.
- A brief discovery window avoids selecting a saved YouTube route just before an
  ad-free option appears. Connection and playback deadlines permit bounded fallback;
  disconnect, pause or a new selection cancels pending work.
- One **Link TV** action, a shorter Spanish prompt, and an app-specific dark pairing
  dialog. SmartTube instructions point to **Settings → Remote control**. Choose
  the TV app, then enter its 12-digit code; incomplete input cannot be submitted.

### Upstream fixes and data correctness

- Incorporated SmartTube's buffering-duration fix, with monotonic time, duplicate
  event guards and a nonnegative recovery delay.
- Seeking while still buffering re-arms the stall watchdog after its seek reset,
  without scheduling false recovery for paused or already-ready playback.
- Incorporated request-JSON validation without changing quoted string values, plus
  safe integer-overflow fallback when parsing metadata.
- Final release review also ports ordinary-video title refresh while preserving
  upcoming-event titles, clears the watch-history record cache after removal
  feedback so a replay can create it again, and retains remote playlist titles
  even before a local playlist cache exists.
- Cached preprocessing data is now published atomically with its key and metadata.
  Interrupted writes preserve the previous valid entry; invalid or oversized data
  is a cache miss. No account reset or user-cache deletion is required to upgrade.

### Verification and developer tools

- Added whole-app network shaping, blackout controls, sanitized playback metrics,
  lifecycle/cleanup tests, offline decoder fixtures and release-style benchmarks.
- Included an app baseline profile and tests for startup, autoplay handoff,
  cancellation, manual quality, casting, parsing, caches and recovery. The measured
  profile comparison did **not** establish a startup speed benefit.
- Full commit inventory, upstream decisions and release validation are recorded in
  [the release record](docs/releases/1.8.0.md). The
  [Spanish announcement](docs/releases/whatsapp-1.8.0.txt) and
  [Eugenio poster](images/release_1.8.0.png) accompany the APK.

### Still limited

- This is not a blanket fix for every YouTube 403 or bot/account restriction.
  Public playback can use an anonymous fallback; account-only videos and server
  watch history may consequently remain unavailable even when feeds are signed in.
- **SABR was not a production playback source** in this release; it is fixed and
  selectable in 1.8.1 above.
  Next-video sample preloading is implemented but **disabled by default** after
  the real-network acceptance check failed; no preload speedup is advertised.
- Direct Cast still requires the phone on the network and does not support live
  streams or subtitles. The latest pairing UI was checked on the Pixel, but the
  new SmartTube receiver-priority flow still needs an end-to-end TV-code test.
- Upstream was reviewed through SmartTube `f23438b`, MediaServiceCore `0b01a017`
  and SharedModules `86f0327`. TV-only changes, reverted patches and incompatible
  alternatives were not blindly merged; see the release record for exclusions.

## 1.7.0 — 2026-08-04

Playlists finally behave like playlists: a real playlist page, a "Playing
from…" queue card that only shows up when you actually chose a queue, and
Save one tap away. Playback now survives the metro — a tunnel-shaped outage
recovers on its own and the player tells you why it stopped. Plus a much
faster first video of a session, and a Spanish app that is actually in
Spanish.

### Playlists, queue and saving
- **New "Playing from …" card** above Up next, with your position in the
  queue (i / N) and a collapsible list you can tap to jump to any video —
  the playing one is badged, and the list scrolls to it when you expand.
- **The card only appears when you really picked a queue.** Opening a video
  from Home, Subscriptions, search or history used to turn that row into a
  playlist ("Playing from Recommended — 2 / 5"); it no longer does. As a
  result, Up next stops being filled with feed videos and autoplay goes to a
  related video, the way YouTube does.
- **Real playlist page**: wide cover, playlist name, owner, a "N videos ·
  Private" line, and a wide **Play all** pill with **Shuffle** next to it
  (shuffle keeps shuffling for the rest of the queue).
- **Save is now a watch-page action**, next to Like / Dislike / Share — it
  flips to a check and "Saved" while the video is in a playlist. It used to
  be buried under gear → More → Save to playlist.
- **Save to Watch later on every card menu**, existing installs included.
- The Save sheet is reworded to YouTube's ("Save to playlist"), offers **New
  playlist** as its first row, and — when you are signed out — says what to
  do instead of opening empty.
- Fixes: opening a second playlist no longer keeps the previous title (or
  shows "Recommended"); **Play all** no longer hides itself on playlists
  reached from a video card; and the position counter now counts the whole
  playlist instead of the first page ("1 / 30", not "1 / 15").

### Playback that survives a tunnel
- **Outages recover on their own.** Real mobile dropouts (a tunnel, a lift,
  the metro, a Wi-Fi → cellular handover) never report a clean disconnect,
  so the player used to give up in seconds and stay dead until you reopened
  the video. It now retries on an escalating schedule (5 s, 15 s, 45 s, 2
  min, 5 min) and resumes at the exact position it died; an actual network
  change retries immediately.
- **The player says why it stopped**, in one persistent line over the video:
  "retrying…" while it is still trying, "tap play to retry" once it has
  given up. It stays put for as long as the outage lasts instead of blinking
  once per attempt.
- **No more raw error dumps** thrown over the video: the 403 and
  stack-trace toasts are gone (the next retry was usually already fixing
  them), and the messages that remain are localized.
- **The notification, lock-screen and headset play buttons now retry.** They
  were dead in the error state, which left a backgrounded audio session with
  no way back.

### Faster and steadier
- **The first video of a session loads its watch page ~2.6 s sooner**
  (measured, Pixel 9 over LTE): the eager metadata fetch now also covers the
  very first open — a deep link, a notification tap, or simply the first
  card you tap.
- Deep-link and notification opens **fill in the title and channel right
  away** when the server sends them, instead of a blank header until the
  rest of the page lands.
- **Signed-in playback stays on your account's route.** A signed-in open no
  longer starts on a client whose media URLs 403 every chunk past ~60 s, and
  a single 403 (or one slow request on a cold connection) no longer banishes
  the whole signed-in session to the anonymous route — where a mobile
  carrier's shared IP gets bot-challenged and the challenge text ended up in
  the video title.
- The anonymous fallback now leads with a client that needs no token
  handshake, so the slowest path no longer starts with the slowest step.

### Fixes
- **Picture-in-picture**: minimizing the player at the same moment as a home
  press could draw the **whole app** — feed, tabs and all — inside the PiP
  window; it now docks in-app instead. The player also no longer pops itself
  back into a corner window from the background, and the forced landscape
  lock is released while in PiP.
- **The Spanish UI is finished**: ~130 watch-page and player strings
  (Comments, Up next, Playing from, Share, Subscribe…) were still English on
  a Spanish phone.
- The New playlist field no longer warns that your playlist "won't be seen
  in the YouTube app" — untrue when you are signed in, and it sat exactly
  where the field should say what to type.

## 1.6.1 — 2026-07-24

A reliability round: playback errors recover faster and repeat less, videos
start at the right quality for your connection, and a handful of paper cuts
(hardware-keyboard search, localized dates, a PiP glitch) are fixed.

### Playback reliability
- **Stream errors recover faster and stop repeating.** When YouTube rejects a
  stream URL (the classic mid-video "403" failure), the app now remembers
  which delivery route failed on the current network and steers the retry —
  and the next videos you open — away from it for a short self-healing
  window, while fetching fresh URLs immediately.
- **No more minute-long silent spinners on dead streams.** Fatally broken
  streams (expired links, bad ranges) and startups that stall before the
  first byte now fail fast into a clean automatic reload instead of the
  player quietly retrying the same doomed request for up to a minute.
- **Stalled startups reroute transport.** If the fast QUIC network path hangs
  while a video is starting, the automatic reload temporarily switches to the
  regular HTTP path so the video plays; the fast path comes back on its own
  after a couple of minutes or when you change networks.

### Smarter startup quality
- The player now remembers your measured bandwidth **per network type**
  (Wi-Fi, 5G, 4G, …) and starts videos at a quality that matches the
  connection you're on right now — no more Wi-Fi-grade first seconds on
  mobile data or needlessly cautious starts on fast Wi-Fi.
- **Rapid video switching is latest-wins**: tapping a new video while the
  previous tap is still preparing cancels the stale work, so the video you
  actually chose starts without waiting in line behind it.

### Fixes
- Search now submits with Enter on hardware and Bluetooth keyboards (some
  only send raw key events, which were ignored), and keyboards that report
  the same submission twice no longer trigger a double search.
- The publish date under the player is no longer cut off on non-English
  locales (e.g. "Data de publicació:"), and it wraps properly while the
  description is expanded.
- Picture-in-picture can no longer capture a frame of the watch page when
  the video surface got detached during a task or mini-player hand-off.

## 1.6.0 — 2026-07-21

Three fronts this round: sign-in went from a TV-style chore to a guided,
hands-off flow; the app is finally pleasant to use **without** an account;
and subtitles, playback speed and casting all got the native-sheet
treatment. Plus a new app icon.

### Sign-in, reworked
- **Guided sign-in**: the device-code screen now walks you through 3
  numbered steps (Continue with Google → approve → come back), with the
  pairing code demoted to a small "check it matches" row and a manual
  fallback link.
- **Automatic return**: after tapping Allow on Google's page you're back in
  the app in seconds — waiting and success (checkmark) states included, no
  more switching back by hand. A "Signing in…" notification keeps the
  hand-off alive while the browser tab is up.
- **Native accounts sheet** (You tab → account row): tap an account to
  switch, "Use without account", Add account, Sign out (with a proper
  confirmation dialog) and Account settings for the advanced options.
  Also reachable while browsing signed-out with stored accounts — that
  state used to dead-end in the sign-in screen.

### Better without an account
- **Signed-out Home is no longer empty**: it fills with trending/topic
  feeds out of the box, and once you've watched a few videos it becomes
  anonymously personalized to your watch history — no account needed.
- Fixed the Subscriptions sign-in gate sticking over Home after switching
  tabs while signed out.

### Casting: live streams and TV controls
- The cast picker and its options now state the real trade-offs up front:
  direct cast is ad-free with quality controlled from your phone (no
  subtitles); the TV-app mode has subtitles and TV-remote quality, and is
  what live streams need. Falling back for a live stream is quicker and
  the messaging clearer.
- **New "TV playback options" sheet** while casting: cap the quality from
  your phone on direct cast ("Auto (up to 1080p)", "Up to 720p", …), and
  on TV-app sessions send your subtitle pick to the TV. Switching a
  direct-cast session to the TV app for subtitles shows a clear
  side-by-side comparison first.
- **Link with TV code now works with SmartTube on the TV** (Settings →
  Remote control), not just the YouTube app — and SmartTube keeps casting
  ad-free. The dialog shows where to find the code in each app and accepts
  codes with dashes/spaces.

### Player polish
- Entering picture-in-picture from the gear menu no longer flashes the whole
  watch page squeezed inside the shrinking window — the animation now shows
  only the video, like the official app.

### New app icon
- The launcher icon was reworked around the arch-"n" mark.

### Subtitles and speed, done right

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
