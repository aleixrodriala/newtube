# Phone Settings (issue #2, 2026-10-02)

Settings used to be the TV app's: `*SettingsPresenter` classes in `common/` building
`OptionCategory` lists that `MobileAppDialogActivity` drew full-screen, every choice spelled out as
an inline radio list. Issue #2 ("way too convoluted, lots of options per section") was the most
repeated complaint after launch. The phone now has its own Settings, modelled on YouTube's
(a top level of sections with icons; each section one short page; a choice shows its value and
opens a dialog) with LibreTube's grouping and wording habits.

## Before and after

Measured on API 35 emulators at the same density (420 dpi, 1080 px wide), signed out, by walking
every page, old and new, with uiautomator dumps and scrolling to the end.

| | Before (1.14.1) | After |
|---|---|---|
| Screens | 25 (incl. 9 identical SponsorBlock sub-pages and two Auto backup pages with no title) | 16 |
| Rows drawn | 1,015 + 56 headers, 800 of them inline radio options | about 170 + 25 headers |
| Scrolling, all pages | about 79 screens | about 21 screens |
| Longest page | Player: 487 rows, 37.7 screens | Video menu: 41 switches, about 3.3 screens (then Advanced, 2.2) |
| Choices | a header plus every option, no current value on the parent | one row with the value under it; a tap opens a radio dialog |
| TV-only rows shown | dozens (clock, screensaver, OK-button long press, ATV launcher, TV layouts…) | none |

## The tree

```
Settings
  Account                       name / email, or "Not signed in"; opens the accounts sheet
  App
    General                     Theme · Pure black · Language · Location · Start screen · Interface size ·
                                Search by voice right away · Use 24-hour time
    Tabs and feeds              Tabs › · Hidden videos › · Video menu › · Thumbnails ·
                                Original titles · Order of Channels in You
    History and privacy         Watch history · Clear watch history · Don't keep search history ·
                                Clear search history
  Video and audio
    Playback                    When a video ends · When you leave the app · speed · sleep timer ·
                                audio focus · Resume · Under the video
    Video quality               Default quality (Auto or a cap: 2160p…360p) · loudness · volume
    Captions                    Style · Size · Distance from the bottom · per channel · Android's settings
    SponsorBlock                on/off · what to do per kind of part · Marks on the seek bar › · More
    DeArrow                     Better titles · Better thumbnails
  Other
    Backup and restore          Back up now · Restore · Automatic backup
    Advanced                    Streaming · Live streams · Player · Network · Accounts · Tabs and feeds
    About                       updates · diagnostic log · star / share · source · license
```

Things that are per video stay in the player, not here: quality for this video, audio track,
speed, captions on/off, zoom (gear › More › Zoom / aspect ratio, or pinch).

## Search

A "Search settings" bar at the top of the top level opens a search page (`SettingsSearch`, the index;
`SettingsSearchFragment`, the page). It lives in its own commits so it can be dropped
without touching the rest.

- **Index:** every page is built (the same rows the pages show, in the current state) on a
  background thread each time the search opens: 164 rows, ~130 ms warm and ~900 ms on the first open
  after a cold start (the language and country lists, the caption styles) on an x86_64 emulator, so
  never on the main thread. The lazily created singletons the pages read are created on the main
  thread first (`warmUp`), so the worker never races a screen to create its own. Each switch, choice and link is an entry with its page, a path ("Tabs and
  feeds › Hidden videos › Live streams") and its section's icon. A page that fails to build is left
  out, not fatal.
- **Matching:** case- and accent-blind, by word start, every word must match. Weight: title, then
  `strings_settings_search.xml` keywords (words people type that aren't in the title: "subtitles"
  for Captions, "autoplay" for When a video ends; translated as words, not sentences), option labels
  ("dark" finds Theme), summary, path. Rows also answer weakly to their section's keywords. The
  matched part of the title is bold.
- **Results:** a page link opens that page; the account row opens the accounts sheet; any other row
  opens its page scrolled to the row, which glows once (`SettingsPageFragment` `ARG_HIGHLIGHT`).
  Everything opens on top of the search page, so Back returns to the results with the query and
  the scroll position; Back from the search page returns to the top level.
- **Motion** (the owner's call, 2026-10-02): the bar is pinned under the title, and the search page
  slides in like any other page, field on top and keyboard up. Two versions came before: the bar
  growing into the page (Material container transform, "Settings: search, like Android's own"),
  and the results laid over the top level in place ("Settings search: in place"). The owner
  disliked the first one's motion and wanted a page of its own back.
- **Keyboard:** edge to edge the window doesn't shrink for it, so the results list pads itself by the
  IME inset; dragging the list hides the keyboard.

## How it is built

`smarttubetv/src/stmobile/java/com/newtube/mobile/ui/settings/`:

- `MobileSettingsActivity`: hosts one `SettingsPageFragment` per page on a fragment back stack with
  `MaterialSharedAxis.X`. Not exported; opened from You › Settings (`MobileBrowseActivity.openSettings`).
  It is registered in `ViewManager` with Home as its parent: without the mapping, `addTop()` of a
  screen with no parent clears ViewManager's stack, and Back from Settings left the app.
- `SettingsPages` (ids, the root page, About), `AppPages` (General, Tabs and feeds and its three
  sub-pages, History and privacy, Backup), `PlayerPages` (Playback, Video quality, Captions,
  SponsorBlock and its marks page, DeArrow, Advanced). Each page is a static method that reads the
  prefs and returns rows. Page ids are strings, so a recreated activity (the theme switch recreates
  it) rebuilds the same stack.
- `SettingsRow`: HEADER, LINK (opens a page or runs an action), SWITCH, CHOICE, NOTE, DIVIDER.
  Values are suppliers, so `rebuild()` after a change re-reads everything (a change can show, hide
  or enable other rows). `fromRadio(OptionCategory, title)` reuses an existing radio factory
  (language, country, caption style…) as one CHOICE row.
- `SettingsAdapter`: same row shape → rebinds in place (`PAYLOAD_VALUES`); otherwise a full refresh.
- `SettingsPageFragment`: a CHOICE opens a `MaterialAlertDialog` radio list; a tap applies and
  closes (YouTube's behaviour; Cancel is the only button). `needsRestart()` rows offer a Restart
  snackbar after the change.

Adding a setting is one line in the right page, for example:

```java
rows.add(SettingsRow.toggle(context.getString(R.string.…), context.getString(R.string.…_summary),
        playerData::isFooEnabled, playerData::setFooEnabled));
```

Write the side effects the old presenter ran next to the setter (the audits list them per row); a
row that only writes the pref is the main way this screen can be wrong.

## Rules learned while building it

- **Rows go in before the first layout** (`onCreateView`). A layout pass with 0 items makes
  `LinearLayoutManager` drop its pending saved state, so the page lost its scroll position every
  time you came back to it.
- **`android:tint`, not `app:tint`,** on a plain `ImageView`: these activities are not AppCompat, and
  `app:tint` is ignored there (the root icons were invisible in the light theme).
- **`MobileAlertDialog` sets `elevationOverlayEnabled=false`.** Without it the dialog's 24 dp
  elevation blends white into the surface: #1E1E1E became #3F3F3F. This applies to every dialog in
  the app.
- The activity is not exported: `am start` cannot open it in a test. Go through You › Settings.

## What left the screen, and why

Per-row evidence (what each pref does on the phone, with file:line) is in
[AUDIT-app.md](AUDIT-app.md) and [AUDIT-player.md](AUDIT-player.md), both reviewed by codex.

- **Rows the phone never reads** (TV layouts, the clock, screensaver, OK-button and D-pad
  behaviour, Android TV channels and launcher, TV player buttons, decoder and frame-drop fixes for TV
  boxes, the network-engine picker that media3 ignores, audio delay, and so on). Their prefs are untouched.
- **Rows the phone reads only to do harm or to do something other than the label**: the "Oculus
  fix" (landscape-locks every screen), "Ambilight"/TextureView (stops SponsorBlock skipping near a
  part's end), the auto-hide timeout, the likes counter (it only gated the dislike fetch), Channels'
  old look and auto-load (they change what a tap on a channel does), "Fullscreen mode" (unticked, it
  adds a TV inset theme), and the card-menu items Open comments (a stub) and Pause history (never
  pauses). `PhoneOnlyPrefs` pins these at every start and on every profile change, writing only
  what differs, so nobody is stuck with a value there is no row to change. (A one-shot migration
  didn't hold: the prefs classes save 10 s after a change, and it covered one profile.)
- **Kept as choices, not reset:** what pinned channels show (their home page or just their videos)
  and Playlists in You (each playlist, or all their videos in one list). They looked like TV
  layouts, but on the phone they change what the feed holds, and both work (Tabs and feeds).
- **Removed features:** Google Drive backup (broken on the phone: its sign-in step opens the YouTube
  sign-in screen and never reaches Google), the
  GrayJay/PocketTube/NewPipe import, "Protect all settings with password" (nothing enforced it),
  child mode and the start-up and account passwords (child mode does not block search or lock
  Settings as it claimed; anyone in Settings could clear the start-up password, and the accounts
  sheet switches account or signs out without the account's). A first version kept a turn-off row
  for people who had them on; then the owner's call (2026-10-02): what only made sense on a TV goes,
  rather than staying behind a row. `PhoneOnlyPrefs` turns them off (child mode the way it undoes
  itself: the phone's card menu, Home, suggestions and autoplay back).
- **Other TV leftovers removed** (same call): the accounts sheet's "Account settings" (a TV dialog: the password
  lock, the TV's account picker on start, and "separate settings per account", which moved to
  Advanced › Accounts), the video menu's QR code, Switch account (the TV's picker) and Check for
  updates (it is in About), and in the player's More › Zoom the aspect-ratio and rotation lists and
  the 50–300 % zoom steps, which did nothing on the phone (it keeps the five fit modes). Conscrypt
  stays (Advanced › Network): upstream filed it under "Internet censorship" (ByeByeDPI), which
  phones need as much as TVs.
- **Duplicates merged:** history (General radio, Search switch, menu items) is one History and privacy
  page.
- **Video menu:** the per-item position picker is gone. People whose order was customised get a
  "Usual order" row that puts back the phone's order. No switch for items whose flag the phone
  ignores (exit PiP; Open playlist always shows where it applies; Move up follows Move down) or that
  `PhoneOnlyPrefs` keeps off (Open comments, Pause history, and the QR code, Switch account and
  Check for updates above).
- **Shorts** rows are gone with Shorts (1.12.0); **Hide Mixes** too (its filter only runs on the old v1
  lists; every phone feed is v2).

Labels: every row has its own phone string in `strings_settings.xml` (English and Spanish). Other
locales fall back to English until Weblate catches up; the upstream TV strings were not reused
where their wording was the problem ("Use Web Proxy", "Enable Conscrypt", the SponsorBlock
descriptions written for a remote).
