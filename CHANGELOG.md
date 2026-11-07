# NewTube Changelog

All notable user-facing changes to NewTube, the phone app built on SmartTube.

## 1.17.1 — 2026-10-22 — Pepe Viyuela Edition

"Something trips over a chair, gets back up, and your quality is still where
you left it." A fictional homage to Pepe Viyuela, for a decoder that stumbles
without taking your settings down with it.

### Fixed

- **A video decoder that fails to start no longer lowers your quality for
  good.** When the phone's decoder couldn't start (for example right after
  the screen woke up, or with another app holding it), NewTube switched that
  video to 1080p AVC to recover, and saved it as your format, so every later
  video stayed capped. Now the fallback is only for the video that failed;
  the next one starts on your choice again. Same for audio. Thanks to
  SmartTube's fix (#6326).

## 1.17.0 — 2026-10-20 — Florentino Fernández Edition

"Every video now comes with a face, and none of them is an impression." A
fictional homage to Florentino Fernández, for channel avatars on Home and
Subscriptions.

### New

- **Channel avatars on Home and Subscriptions.** Each video shows its
  channel's avatar next to the title, like YouTube. Tap the avatar to go
  straight to the channel; tap anywhere else on the card to play. Avatars are
  remembered, so they show up at once the next time. Mixes and playlists have
  none.

### Fixed

- **A quick double tap on play/pause plays or pauses**, like YouTube. When
  the controls were hidden, the second tap hid them again instead.
- **Translations:** Romanian now covers almost all of the app's own screens,
  thanks to the translators on
  [Weblate](https://hosted.weblate.org/projects/newtube/).

## 1.16.2 — 2026-10-19 — Dani Rovira Edition

"Eight rows in a menu, and now you can reach all of them." A fictional homage
to Dani Rovira, for player menus that stay put with the phone sideways and
videos that get sharp sooner. (1.16.1 was tagged but never published; its
fixes ship here.)

### Fixed

- **Vertical videos play in 1080p.** They were capped at 480p, whatever the
  quality setting.
- **Full quality arrives sooner.** The low-quality start of a video is
  replaced once the connection allows more, so 1080p shows up in about 5 s on
  Wi-Fi instead of about 10.
- **No 720p cap on the watch page** when NewTube's "Background data" is off
  on mobile data: Android reports that like Data Saver, so it capped the
  picture for people who only meant "not in the background".

- **Player menus in fullscreen stay still.** The settings menu no longer
  jumps a couple of seconds after it opens: the system bars stay hidden, and
  the controls behind it don't move until it closes.
- **More scrolls in landscape.** Every row is reachable, the last row on
  screen is cut in half so you can tell the list goes on, and dragging the
  list no longer closes the menu.
- **Quality, Subtitles and Speed** keep their title (and their bottom row) in
  place while the list scrolls, and going from one menu to the next fades
  across without the video flashing up in between.
- **The Quality row shows what's playing** ("Auto (720p)") instead of always
  "Auto (1080p60)".
- Smaller: menus keep their rounded corners when fully open, Repeat lost an
  empty title bar, and turning the phone with a menu open closes it.

## 1.16.0 — 2026-10-19 — Andreu Buenafuente Edition

"Good evening, and welcome to a release where the screen goes black on
purpose." A fictional homage to Andreu Buenafuente, for pure black, upright
vertical videos and a minute that no longer stutters.

### New

- **Pure black (#73, #74).** Settings › General › Pure black turns the dark
  theme black (#000000) for OLED screens, also when the theme follows the
  system. Sheets and cards stay one shade lighter so they still read as
  raised. It applies live, no restart.
- **Vertical videos go fullscreen upright (#69).** Fullscreen (button or
  swipe up) on a portrait video now fills the screen in portrait instead of
  rotating to landscape with black bars. Landscape videos still rotate.

### Fixed

- **The app opens on Android 7.0 again (#78).** Every Android 7.0 phone
  closed NewTube right at launch, since the first public release.
- **No pause and rewind at the one-minute mark (#84).** When YouTube refuses
  a session at 60 seconds, the buffered video keeps playing while NewTube
  fetches a working link for the same stream, so there's no stop and no jump
  back. If that fails, the old recovery resumes at the exact second.
- **⏮ goes back even with SponsorBlock.** After a restart, a second tap now
  returns to the previous video even when SponsorBlock skipped an intro, and
  the previous video resumes where you left it.
- **Fullscreen on Android 11 (#68).** The first tap shows the player
  controls, and the gesture bar no longer stays on screen.
- **Audio tracks are labelled better:** "original", "dubbed" and audio
  description tracks are told apart, and auto-translated subtitles are no
  longer built from an auto-dubbed track. Search retries more reliably
  (fixes from SmartTube upstream).
- **Translations:** the app's own screens are now in Russian and
  Simplified Chinese, and half in Romanian, thanks to the translators on
  [Weblate](https://hosted.weblate.org/projects/newtube/).

## 1.15.3 — 2026-10-13 — Berto Romero Edition

"Nothing changes, and that's the news." A fictional homage to Berto Romero,
for a release you won't notice: a build change on the way to F-Droid.

### Changed

- **One less library inside the app.** Videos still stream over the same
  built-in Cronet engine (HTTP/3 and HTTP/2), now through NewTube's own copy
  of media3's Cronet code, so the app no longer carries a library that
  declares Google Play Services. Nothing changes in how videos load.
- **Translations are open on [Weblate](https://hosted.weblate.org/projects/newtube/).**

## 1.15.2 — 2026-10-13 — Joaquín Reyes Edition

"A short video, you say? Well, round here there aren't any." A fictional
homage to Joaquín Reyes, for a same-day patch: channel pages without
Shorts, and a few loose ends from 1.15.1.

### Fixed

- **No Shorts on channel pages (#63).** The Shorts shortcut tile in a
  channel's last tab is gone (it opened a blank page), and that tab is now
  labelled "More" instead of a second "Videos".
- **Downloads: a partly downloaded audio file is only resumed if it is the
  same language.** A download stopped halfway could otherwise carry on with
  a different audio track.
- **youtube.com/@handle links open the channel,** and a channel page opened
  from a link shows the channel's name.
- **With a menu open over the mini player, pausing lets the screen sleep.**
- **"Remove from playlist" in your own playlists, Watch later included
  (#36),** with Undo.

## 1.15.1 — 2026-10-13 — José Mota Edition

"If you have to leave fullscreen, you leave. But leaving for nothing is
nonsense." A fictional homage to José Mota, for a patch release of things
that should already have worked: leaving fullscreen on a tablet, letting the
screen sleep, and a dub that stays on the video you picked it for.

### Fixed

- **Leaving fullscreen on tablets (#44, #60).** On Android 16 tablets, Back,
  the fullscreen button and the swipe down did nothing, so the player stayed
  in fullscreen. Fullscreen on a tablet is now a layout of the screen as it
  is, so it works without rotating, in both orientations.
- **Picture-in-picture on tablets.** The video keeps the right size in the
  small window, and coming back from it returns to the layout you left.
- **The screen can turn off when a video ends or is paused (#50).** The
  player kept the screen on for as long as it was open.
- **Audio track: your pick is for that video only (#52, #53).** A dub you
  picked once followed you to every later dubbed video. Now the next video
  starts on its original language, like YouTube.
- **Downloads keep the original audio language (#29),** also after Retry or
  a resumed download. They sometimes came with a dub, such as Arabic.
- **The "Original titles" setting works again (#45).**
- **Playlist menus no longer offer "Block the channel" or "Add/Remove
  from subscription group" (#56).** Neither did anything useful on a playlist.

### Privacy

- **Android backup and device transfer no longer copy your sign-in.** A
  restored or transferred NewTube comes back signed out; your settings still
  come along.
- **The in-app Backup and restore zip no longer contains the sign-in tokens.**
  Restoring a backup means signing in again.

### Other

- F-Droid submission: the app name in the source and the APK signing block
  now pass F-Droid's checks. Nothing changes in the app.

## 1.15.0 — 2026-10-02 — Los Morancos Edition

"Swipe up? — Up! And now swipe it down. — Down!" A fictional homage to Los
Morancos and their two-brothers routines, for a release where every swipe has
a twin: up into fullscreen and down out of it, up and down for brightness and
volume, left and right to seek. Settings got short pages and a search, too.

### New

- **Swipe gestures in the player (#12).** Swipe the video up to go
  fullscreen, and down in the middle to leave it. In fullscreen, swipe up or
  down on the left for brightness and on the right for volume, with a pill
  showing the level. Swipe sideways on the video to seek. Brightness is only
  the player's: everywhere else keeps your phone's. Settings → Playback →
  Gestures turns the brightness/volume and seek swipes off.
- **Search in Settings.** A search bar at the top of Settings opens its own
  page; results come as you type, also for words a setting's name doesn't
  have ("subtitles" finds Captions, "dark" finds Theme), and a result opens
  its page with the row highlighted.

### Changed

- **Settings in short pages, like YouTube's (#2).** The old Settings came
  from the TV app: 25 screens and over a thousand rows. They're now 16 short
  pages under App, Video and audio, and Other, and a choice is one row that
  shows its value. Options nothing on the phone used are gone, along with
  Google Drive backup (its sign-in never worked on phones), settings import
  and the settings password. Child mode and the start-up password are turned
  off and keep only a row to turn them off.

### Fixed

- **Fullscreen with an app language or country set (#17).** The video was
  cropped and the seek bar off screen, and coming back from the mini player
  showed the status bar.
- **The app language holds with a forced theme or interface size.** Some
  screens came out in the phone's language until they were reopened.

## 1.14.1 — 2026-10-01 — Pepe Viyuela Edition

"Turn it up? — It was never down, it was the app." A fictional homage to
Pepe Viyuela and his fights with everyday objects, for a release that wins one
against the volume knob.

### Fixed

- **Videos are as loud as in YouTube's app.** "Auto volume adjustment" turned
  almost every video down by about half (6 dB). It now does what YouTube does:
  a video louder than YouTube's level is lowered by exactly that much, and
  nothing else is touched. Master volume stops at 100%, since the higher
  values never made anything louder.

## 1.14.0 — 2026-10-01 — Cruz y Raya Edition

"Is that a line? — It's a line that follows your finger." A fictional homage
to Cruz y Raya and their double-act sketches, for a release that draws its
lines straight: the seek bar follows your finger, drags click under it, a
channel's tab names stay on one line, and About gets a star and a Share row.

### New

- **Star and share NewTube.** Settings → About ends with two rows: "Star
  NewTube on GitHub" opens the project page, and "Share NewTube" opens your
  phone's share sheet with a line about the app and its website link. Nothing
  asks you to do either.

### Changed

- **The seek bar follows your finger.** The dot moves by as much as your
  finger does instead of jumping under it, and a drag to the start now reaches
  0:00 (the end is reached just before the edge of the screen). A plain tap on
  the bar no longer seeks, the same as YouTube, and "Release to cancel" no
  longer sticks.
- **Drags you can feel.** Swiping the video down to minimize, swiping the mini
  player away and pulling to refresh click under your finger when they cross
  the point of no return, and the video follows the finger more closely on the
  way there. When you let go it lands with a small spring.

### Fixed

- **Channel tab names stay on one line.** Long section names on a channel
  page (such as "WING IT! Production Logs (Pet Projects)") no longer wrap onto
  two lines; the tab takes the width of its name, like YouTube's.

## 1.13.0 — 2026-09-30 — Faemino y Cansado Edition

"You say one thing, I answer another, and each of us gets a chapter…" A
fictional homage to Faemino y Cansado and their deadpan back-and-forth, for a
release where you can finally answer back. Write comments and replies, find a
video's chapters, hold the video for 2x, and a seek bar like YouTube's.

### New

- **Write comments.** When you're signed in, "Add a comment…" heads the
  comments panel and a Reply button sits under every comment (it starts the
  reply with the person's @handle). What you post shows up at the top right
  away. Your own comments get a ⋮ menu with Delete, after a confirmation. A
  half-written comment waits for you until you change videos, and if posting
  fails the app says why and keeps your text.
- **Chapters** ([#13](https://github.com/aleixrodriala/newtube/issues/13)).
  A video's chapters show on the seek bar as small gaps, and the current
  chapter's name sits after the time ("1:10 / 4:26:52 · Introduction ›"). Tap
  it for the full list with a frame of each chapter, then tap a chapter to
  jump to its start.
- **Press and hold for 2x.** Hold a finger on the video to play it at double
  speed; let go and it returns to your speed.

### Changed

- **A seek bar like YouTube's.** Thin, the full width of the video, on its
  bottom edge, with a smaller dot, and easy to grab: a touch just above or
  below it takes it, and a drag from the screen's edge moves it instead of
  going back. With the controls hidden, a thin line keeps showing how far
  along you are. While you drag it, the other controls
  step aside, a label shows the time and chapter under your finger, a light
  vibration marks each chapter, and dragging back to where you were snaps
  there: let go to cancel.
- **A player that answers your touch.** Play and pause morph into each
  other; likes, dislikes and Subscribe give a short vibration; buttons that
  had no press feedback now have it; tapping the tab you're on scrolls the
  feed back to the top. Vibrations follow your phone's touch-vibration
  setting.

## 1.12.0 — 2026-09-30 — Paco Martínez Soria Edition

"Everything in its place, and the lights on…" A fictional homage to Paco
Martínez Soria and the spirit of his country folk finding their way around
the big city, for a release that puts things where you'd look for them. A
light theme, comments under the video, Back that minimizes, a Home that keeps
loading, and no more Shorts.

### New

- **Light theme** ([#8](https://github.com/aleixrodriala/newtube/issues/8)).
  Settings → User interface → Theme: System default, Light or Dark. The
  player stays dark in both, like YouTube's. New installs follow the system;
  an update from an earlier version keeps the dark theme until you change it.
  Switching doesn't stop the video.
- **Comments, under the video.** Comments open in a panel that slides up
  under the video while it keeps playing, sorted by Top or Newest. Replies
  open on their own page and Back returns to your place; long comments fold
  after four lines with "Read more"; timestamps and links can be tapped. Pull
  the panel down or press Back to close it.
- **"Not interested" and "Don't recommend channel"** are back in the menu of
  Home cards when you're signed in
  ([#1](https://github.com/aleixrodriala/newtube/issues/1)). The card leaves
  Home once YouTube takes it; if YouTube doesn't, a message says so and the
  card stays.
- **Audio track, in its own row.** On videos in more than one language
  (dubbed or auto-dubbed), the player settings show "Audio track" under
  Quality, like YouTube, and Quality lists only resolutions. Videos start in
  their original language; a language you pick carries over to the next video
  that has it.

### Changed

- **Back minimizes the player** to the mini player, like YouTube. The back
  gesture previews it, and in fullscreen Back leaves fullscreen first. The
  top-left button is now a down arrow, "Minimize". Swipe the mini player
  sideways to close it.
- **Smoother, just as fast.** A video grows out of the card you tapped, the
  controls answer the first tap, and the mini player opens without a blink.
  Loading placeholders shimmer, also on a cold start (instead of last time's
  cards, which then jumped) and in Search (instead of a spinner). Switching
  tabs no longer shuffles the cards.
- **No Shorts.** Shorts no longer appear anywhere: Home, Subscriptions,
  History, search, channels, playlists and Up next. Channels have no Shorts
  tab, autoplay skips Shorts, and the Shorts settings are gone. A Shorts link
  someone shares still opens in the normal player.
- **No Notifications section.** YouTube refuses its notifications inbox to
  the TV-style sign-in NewTube uses, so the section could only stay empty. It
  and its setting are gone.
- **Rotate lock is gone** from the player settings. Instead, after you go
  fullscreen with the button, rotation goes back to the phone (with
  auto-rotate on): turn the phone upright and fullscreen ends, like YouTube.
  Videos opened from another app rotate too.
- **"Play in background" has two choices:** Picture in picture and Only
  audio.

### Fixed

- **"Only audio" no longer shrinks the video into picture-in-picture** when
  you go Home. The audio keeps playing, with its controls in the
  notification.
- **Home keeps loading.** Signed in, Home stopped after a while and only a
  refresh brought more. Now each of Home's shelves carries on in turn as you
  scroll, then Home is fetched again for new videos (signed out on an
  emulator: 124 videos before the end, now 261). Subscriptions and History
  carry on past pages that were all Shorts, Up next loads its second page (60
  videos instead of 30), and search results load more as you scroll (they
  stopped at the first page).
- **Picture-in-picture from the player menu stays in picture-in-picture**
  instead of jumping back to the full player.
- **Tapping the video that's already playing** (in the mini player or in
  picture-in-picture) brings it back where it was, instead of loading it
  again.
- **New versions show up by themselves.** The You tab's "Update available"
  row and dot only appeared when NewTube started from scratch and its last
  check was over 12 hours old, so a new version could go unnoticed for a day
  or more unless you tapped Check for updates. NewTube now checks quietly
  whenever you come back to it, at most once an hour. This helps from the
  next version on: 1.11.0 finds this one the old way, or with Check for
  updates.

### Still limited

- **Search with the keyboard open and the mini player showing** leaves some
  empty space at the bottom.
- **Light theme on Android 11 and older:** the launch screen follows the
  system theme, not the one you picked.
- **Some videos only play in YouTube's own app or after a purchase** (paid
  movies, some music).

## 1.11.0 — 2026-09-29 — Tony Leblanc Edition

"There's always a way in…" A fictional homage to Tony Leblanc and the spirit of
his street-smart chancers, for a release about finding the way to every video.
Videos no longer stop after a minute, made-for-kids videos play, videos start
sooner, and updates come in one sheet.

### Fixed

- **Videos no longer end after a minute with "Unknown source error".** For
  some anonymous sessions, YouTube serves only the first minute of a video to
  the services NewTube asks first and refuses the rest, also after a skip or a
  resume past that minute. NewTube retried those same services until it gave
  up. It now recognises that refusal, remembers it, and carries on with a
  service that keeps serving (the embed player, the TV service, or a
  low-resolution stream as a last resort). It also starts a fresh anonymous
  identity for playback, so the next videos play normally; your Home feed
  keeps its own. Tested with real refusals on emulators and a simulated one on
  two phones: the video paused for about a second on the phones (a few seconds
  on the slower emulators), sometimes repeated a few seconds, and played on.
- **Made-for-kids videos play.** They stopped with "Unknown source error"
  ([#5](https://github.com/aleixrodriala/newtube/issues/5)): the YouTube
  service NewTube asks first refuses them, and what it tried next was refused
  too, or held the video for its pre-roll ad and gave up. NewTube now asks, in
  an order measured on real phones, the one that serves them, and waits out an
  ad hold instead of failing. On a Pixel 9 over mobile data, 25 kids videos
  out of 25 played, starting in about 1.3 s (median).
- **Signed in, videos start on the first or second try.** NewTube first asked a
  route YouTube no longer serves to signed-in apps, then walked many others.
  In a test with the same account, the previous version played 3 of the first
  7 videos and needed 128 requests for them; this one played all 13 of the 16
  test videos that can play (the other three are members-only, a paid movie and
  a music-only video) with 40 requests.
- **Videos that can't play say so quickly.** A removed, age-restricted (and not
  embeddable) or members-only video stops at its own reason after two to four
  requests instead of trying every service, also in Spanish. A private video
  no longer makes NewTube treat the next videos as blocked, and autoplay stops
  after two unplayable videos in a row instead of skipping through a list.
- **Watch history sync is retried** when its first sync with your account
  fails (up to three tries), instead of being dropped.
- **Wide videos fill the screen properly.** On videos wider than 16:9 (films,
  2.35:1) the fullscreen controls dimmed only a 16:9 strip, leaving hard edges
  across the picture, and the mini player stretched the video to its card
  ([#9](https://github.com/aleixrodriala/newtube/issues/9)). The dimming now
  covers the whole video, the controls stay clear of the camera cutout, and the
  mini player shows the video at its own shape.

### Faster

- **Videos start sooner in everyday use** (the app installed and opened
  before). On a 2018 phone (Xiaomi Mi 8, Wi-Fi), the picture of an ordinary
  video shows in about 0.55 s instead of 0.8 s, a made-for-kids one in about
  1.05 s instead of 8.3 s, and an 18+ one in about 1.1 s instead of 3.8 s when
  YouTube shows no pre-roll ad; live videos start as before. YouTube's
  background security check now waits until your video is on screen, the
  loading image lifts at the first frame, and NewTube keeps what it worked out
  about YouTube's player instead of redoing it for every video.
- **The first video after YouTube updates its player** (every few days, and
  right after installing) started 1.1 to 2.8 s sooner in tests of that
  situation when it is an ordinary or a live one: NewTube no longer waits to check the new player before asking for
  a video that doesn't need it.
- **Fewer requests to YouTube.** A second video from the same made-for-kids
  channel, a live video tapped from a list, and a kids video recovering from a
  failed stream each need a single request to YouTube.

### Changed

- **Updates, in one sheet.** Settings → About → Check for updates, the new row
  at the top of the You tab and a dot on its tab all open the same sheet: the
  version, its size and what's new, then Update. The download shows its
  progress, can be cancelled, keeps going if you leave the app, and Android's
  installer opens straight away when it's done. The first time, the sheet
  explains Android's "install unknown apps" permission. After updating, NewTube
  tells you and shows what's new. Checking no longer downloads the update by
  itself.

### Still limited

- **Some videos only play in YouTube's own app or after a purchase** (paid
  movies, some music), and still stop with YouTube's "not available".
- **The switch after a one-minute refusal is visible:** the video pauses
  briefly and may repeat a few seconds before it plays on.

## 1.10.4 — 2026-09-28 — Lina Morgan Edition

"Grateful and up to date…" A fictional homage to Lina Morgan and the spirit of
her curtain calls, for a release that answers the people who wrote in. Text
follows the size you chose on your phone, and Check for updates works.

### Fixed

- **Text follows your phone's font size.** NewTube sized its top bar, tabs,
  menus and settings from a density of its own, inherited from SmartTube's TV
  layout, so the phone's font size and display size only reached the video
  feed. Now the rest of the app follows both (the tab labels only the display
  size, a Material rule), and Settings → User interface → UI scale zooms
  everything on top of them
  ([#3](https://github.com/aleixrodriala/newtube/issues/3)). At default settings
  the top bar, tabs and menus come out a little larger than before (4% on a
  Pixel 9, 18% on a test phone with navigation buttons), the same size as in
  other apps. If you had raised UI
  scale to read more easily, try a lower value: it now enlarges the feed too.
- **Check for updates works.** Settings → About → Check for updates looked for a
  file NewTube's releases didn't publish, and failed with "Value Not of type
  java.lang.String" ([#4](https://github.com/aleixrodriala/newtube/issues/4)).
  Every release now carries it, 1.10.2 and 1.10.3 can update from the app too,
  and a failed check says so in plain words.

### Still limited

- **Some made-for-kids videos still stop with "Unknown source error"**
  ([#5](https://github.com/aleixrodriala/newtube/issues/5)). We found why and a
  way to play them, and it is being measured on real phones before it ships.

## 1.10.3 — 2026-09-28 — Martes y Trece Edition

"Where were we?…" A fictional homage to Martes y Trece and the spirit of their
phone-in sketches, for a release about not losing your place. Settings remember
where you were, the app stops talking about ads, dislike counts become opt-in,
and the APKs are now built on GitHub's servers.

### Changed

- **Dislike counts are now opt-in.** Return YouTube Dislike is a community
  service that learns which videos you open, so NewTube no longer asks it
  unless you turn on Settings → Player → Dislike counts. Without it the watch
  page shows YouTube's like count and a plain dislike button, instead of an
  estimate made up from the likes.
- **The cast screens describe each option by what it does** (where quality and
  subtitles are controlled, which TV app plays) instead of by ads.

### Fixed

- **Settings keep their place.** Ticking a box or picking an option halfway down
  a long settings page no longer throws you back to its top, and going back from
  a sub-page returns you to where you were
  ([#2](https://github.com/aleixrodriala/newtube/issues/2)).
- **Older Android versions.** On phones older than Android 10, a class that only
  exists from Android 10 on could stop NewTube from getting the token YouTube
  asks for, and then every video failed with "Can't get video info" (seen by
  SmartTube on Android 7.1; their fix).
- **A slow start of YouTube's bot check no longer leaves a broken token
  generator behind.** If it isn't ready within 20 seconds, NewTube now gives up
  cleanly and tries again the next time it needs a token.

### Behind the scenes

- **Release APKs are built on GitHub's servers** from the tagged source, with a
  build attestation for every file: `gh attestation verify <file>.apk -R
  aleixrodriala/newtube`. They're also about 20 MB smaller, because the
  JavaScript engine's library no longer ships with its debug symbols.

## 1.10.2 — 2026-09-28 — Tip y Coll Edition

"First you pick up the phone. Then you go to Settings…" A fictional homage to
Tip y Coll and their step-by-step lessons, for a release about telling us what
broke. It follows reports of "Unknown source error" on every video after a while
of playback (27 September, a Redmi Note 14 4G on HyperOS 3). It didn't happen on
an Android 16 emulator, and testers had no way to send a log without a computer.

### Send us what broke

- **Settings → About → Send diagnostic log.** It shares a text file with the
  app's recent log, about the last hour. It includes the session before a
  force-close, so the file still holds the failure after you restart the app.
  A short header comes first: app version, phone model and Android build,
  whether you're signed in, network type and caption language.
- **What goes in and what comes out.** The log lists the videos and channels you
  opened. Before the file is written, the app removes passwords, sign-in tokens,
  cookies, e-mail addresses, proxy passwords, PO tokens and your IP address from
  video links. Nothing leaves the phone until you pick an app to share it with.

### Fixed

- **Sign-in secrets stay out of the system log.** OAuth request bodies and the
  refresh token are no longer logged (a SmartTube fix), nor is the device code
  when a TV-code sign-in fails. Live-chat messages aren't logged either.

### Still limited

- **"Unknown source error" after a while is not fixed yet.** We couldn't
  reproduce it, and the diagnostic log is how we'll find it.
- The scrubber works line by line: a secret split across two log lines would
  not be recognised. The first 240 characters of a video server's error page
  stay in the log; they are YouTube's generic error text.

## 1.10.1 — 2026-09-27 — Gila Edition

“¿Es el enemigo? Que se ponga… rápido.” A fictional homage to Miguel Gila, for
a release about picking up faster: videos, the app and the network. It ships
everything since 1.9.0 (`7276579`, 11 September): two network rounds (24 and
25 September), a speed and smoothness round measured on a Pixel 9 over Wi-Fi
and Movistar LTE, and a full pass over the phone UI. 1.10.0 was tagged but never
distributed (see below); this is the build that goes out.

### Faster

Pixel 9, release builds, 1.9.0-era build vs this one (medians; small samples,
one phone, one carrier):

| | Wi-Fi | Mobile data |
| --- | --- | --- |
| App open, first screen | 397 → 240 ms | 458 → 241 ms |
| Home fully painted | 1.83 → 1.35 s | 2.05 → 1.56 s |
| Tap a related video → first frame | 554 → 497 ms | 1033 → 528 ms |
| Tap a shared link → first frame | 952 → 664 ms | 8.96 s → ~0.84 s |
| Reopen a half-watched video → picture | up to 1.4 s → ~0.37 s | |

- **Videos start sooner.** The decoders stay open between videos, the codec
  lists are read at app start, and the answer from YouTube is parsed about four
  times faster. Autoplay fetches the next video 20 seconds before the end.
- **Reopening a half-watched video is instant.** It resumes at the start of
  the nearest video segment, and the audio no longer decodes the seconds it
  skips.
- **The app opens faster and Home fills sooner**, with no blank flash when the
  fresh feed replaces the saved one. Home loads its first pages at once and the
  rest as you scroll. The APK carries its own start-up profile, so Android
  optimises it at install instead of the next night.
- **Going back to the video you just left** shows its related list straight
  away.

### Steadier on bad networks

- **Stuck video servers.** Some mobile networks stall the connection to some
  YouTube video servers. The app now switches connection within the same
  request, remembers the problem per carrier (also after a restart), and later
  videos go straight to the working path instead of waiting again. It lets go
  once two different servers answer again.
- **No connection:** the player waits for the network and tries once when it
  comes back, instead of retrying in bursts. A network that Android blocks for
  the app no longer triggers a storm of retries. Home backs off while offline;
  channel, uploads and playlist pages show No connection / Try again.
- **Blocks and bot checks.** When YouTube challenges the anonymous clients, a
  signed-in phone plays through a TV route with the account, and the app
  remembers the wall instead of asking every client again on each video.
  Age-restricted videos play through the same route when the account is
  allowed to watch them. A removed video stops after three matching answers
  instead of trying all eleven clients. The embedded-player client, which
  YouTube refuses everywhere (error 152-18), is no longer asked.
- **Mobile data is spent for smoothness.** On mobile data the player keeps the
  full buffer and quality; the byte-saving caps apply only when Android's Data
  Saver is on. PiP and the mini-player still fetch only what their window shows.

### Looks and feel

- **Snackbars instead of system toasts**, with Undo or View where it helps
  (subscribe, like, dislike, download), sitting above the mini-player.
- **Like and Dislike** show a filled or outlined thumb instead of turning red,
  confirm with Undo, reach YouTube in the order you tapped them, and roll back
  with a message when they could not be saved.
- **The watch page holds still while it loads**, shows views and a relative
  date ("4 days ago"), names dubbed audio tracks, and says when you are
  offline.
- **Switching between light and dark mode keeps the video playing**, the You
  tab and Settings; the dark theme holds when the phone is in light mode.
- **Search** tells offline, failed and empty results apart, keeps Retry, and
  suggests matching history instead of the whole history.
- **Back from any tab goes to Home; menu sheets fit in landscape;** local
  History cards have thumbnails; bigger touch targets and TalkBack labels; the
  card menu leads with the everyday actions (only on a menu nobody customised).
- **Auto Frame Rate and Remote control leave phone Settings** (TV features;
  switched off once).
- Minimizing after opening a shared link no longer flashes the launcher; a
  second shared link no longer lands in picture-in-picture; downloading a video
  again after deleting its download works.

### Still limited

- The first time a carrier stalls, one video still waits ~7-8 s while the app
  learns it.
- Picking the same download twice while it runs queues a duplicate; on a phone
  short of storage a download can stay at "Finishing…".
- YouTube can still refuse some videos and accounts, and SABR stays off.

## 1.10.0 — 2026-09-26

Tagged (`v1.10.0`) and installed on the test Pixel, never sent to the tester
group. It added a mini-player "park": X paused the video and left a
notification to resume it for 10 minutes. On the owner's Android 17 phone the
system dropped that paused notification at once, so nothing was left to tap,
and the feature was withdrawn in 1.10.1 (X closes the video again, as in
1.9.0). Everything else in 1.10.0 ships in 1.10.1.

## 1.9.0 — 2026-09-11 — Chiquito Edition

“¡Te das cuen! Ya se descargan.” A fictional homage to Chiquito de la Calzada,
with videos that now travel in your pocket. This release ships everything since
1.8.0 (`cddae01`, 8 September): the 1.8.1 SABR work below, the phone UX sweep of
8 September, and the new Downloads feature.

### Downloads

- **Download any video** from the card menu, the watch page (new Download pill)
  or gear → More. Pick a quality (every H.264 rung up to 1080p, with the real
  size) or audio only. Live streams can't be downloaded.
- **A Downloads tab** on the bottom bar lists what's on the phone as ordinary
  cards: progress and a percentage while it fetches, then the duration badge
  and "144p · 67.1 MB". Tap to play, long-press (or ⋮) for Share / Delete /
  Retry. A card that isn't ready yet is dimmed and says so.
- **Downloaded videos play like any other video**, in the same player with the
  same watch page - also with no connection at all. When a video fails to load
  online and a downloaded copy exists, the copy plays instead.
- Files land in `Movies/NewTube` (video, MP4) and `Music/NewTube` (audio, M4A),
  visible to the gallery and any file manager. Downloads continue in the
  background with a progress notification and can be cancelled from it.

### Looks, sheets and settings

- **The app is no longer pink where nobody chose pink.** The theme accent was
  still Material's stock `#FF4081`, which painted every settings section
  header, every checked checkbox and radio dot, the search caret and three
  loading spinners. Selection controls are white now, spinners are the app red,
  and the cast icon keeps its own blue while a session is live.
- **Bottom sheets reach the bottom of the screen.** Every sheet (gear, quality,
  captions, speed, comments, accounts) stopped a gesture-bar's height short of
  the edge with a band of the wrong colour under it. The sheet frame is styled
  through the theme now, so the surface runs under the gesture bar. Sheets that
  size themselves from the screen also stopped inheriting the height of
  whatever orientation the app was launched in.
- **Context menus dim the whole screen.** The overlay behind card menus and
  player pickers began below the status bar and its sheet floated above the
  bottom edge; both now reach the display edges.
- **Shuffle on a playlist stays on that playlist.** One tap on a playlist's
  Shuffle used to persist shuffle for everything played afterwards. It is now
  scoped to the queue that armed it and ends with it; the player's Shuffle row
  shows the effective state.
- **Settings that did nothing are gone.** Colour scheme (nine options behind a
  "restart the app" toast), card text scroll speed, card preview and the
  card-style checkboxes had no reader on the phone. The `Live` section title
  also dropped the shouting badge caps.

### Still limited

- Downloads have no pause: cancel and retry, and a retry resumes the part files
  already fetched. Playlists cannot be downloaded in one go, subtitles are not
  saved, and VP9/AV1 rungs above 1080p are not offered (no trustworthy WebM
  muxer on the platform). Joining a 300 MB video and its audio takes about
  90 seconds on a Pixel 9; the card says "Finishing…" and is not playable yet.
- Everything under 1.8.1 and 1.8.0 still applies: YouTube can still refuse some
  videos and accounts, and SABR stays off.

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
- **A fourth fault: SABR was downloading every soundtrack twice.** Each video
  request was supposed to tell the server "I already have the audio", but the
  claim was being ignored, so the audio arrived again alongside every chunk of
  video. Fixed. It made no difference to anyone today (SABR is off), but it had
  been making the experimental path cost about 50% more data than it needed to.
  Measured on mobile data across four videos, SABR now uses about the same data
  as the normal path rather than substantially more.
- Three further faults in the SABR implementation were fixed on the way. The
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
