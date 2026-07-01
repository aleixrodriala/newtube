# NewTube — Status

**Feature-complete and verified on the emulator (2026-07-01).** Built as the
`stmobile` flavor of this fork; the TV flavors (stbeta/ststable/stfdroid) still
build unchanged. All touch UI lives in `smarttubetv/src/stmobile/`; the backend
(`MediaServiceCore`), player engine (ExoPlayer), and account login are reused
unmodified.

Build a phone APK:
```
./gradlew :smarttubetv:assembleStmobileDebug
# -> smarttubetv/build/outputs/apk/stmobile/debug/SmartTube_mobile_*_universal.apk
```

## Implemented (verified on emulator, zero crashes)
- **Home**: lean 2-column grid of the live feed; 5-item bottom nav
  (Home/Subscriptions/History/Playlists/Shorts) + nav drawer to every section
  (Music/Gaming/News/Live/Kids/Sports/My videos/…); per-section long-press
  management menu (refresh/rename/move/unpin/mark-watched/create-playlist/clear).
- **Search**: field + live tag suggestions + voice (RecognizerIntent) + results grid.
- **Channel / Channel-Uploads**: touch pages; mixes/playlists/charts open here.
- **Player**: YouTube-style watch page — title, views/date, Like/Dislike (real
  Return-YouTube-Dislike), Share, channel + Subscribe, expandable description,
  related/up-next list. Custom touch controls (play/pause, drag scrubber, prev/next,
  double-tap ±10s, fullscreen), smooth open/close + swipe-to-dismiss. Overflow menu:
  repeat, shuffle, zoom/aspect, play-in-background, screen-off, stats, rotate lock,
  save-to-playlist, queue. Quality (up to 4K/codec), speed, subtitles (**styled**),
  audio track. SponsorBlock skip + seekbar markers. DeArrow. Background playback with
  media notification + lock-screen controls. Picture-in-Picture. Comments (+ replies,
  like). Live chat (live streams).
- **Settings**: all categories via one Material bottom-sheet/list renderer; context
  menus; proxy, backup/restore, in-app update, channel groups reachable through it.
- **Account**: device-code OAuth sign-in (code + tappable link + client-side QR) +
  multi-account management.
- **Branding**: NewTube name + launcher icon; external links open the device browser.

## Deliberately deferred (not SmartTube-TV parity, or by preference)
- Tablet two-pane / NavigationRail large-screen layout (phone-first).
- In-app minimize-to-floating mini-player while browsing (PiP covers backgrounding).
- Multi-row Home shelves — kept as the single lean flat grid by preference.

## Not done yet
- Pushed to GitHub (kept local for now).
- Real end-to-end Google sign-in (manual step; the flow + screen are verified with a
  live device code).
