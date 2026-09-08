# NewTube — Status

**v1.8.0 (versionCode 10800), Eugenio Edition, 2026-09-08.** Release scope,
upstream review, validation and distribution are recorded in
[the release record](../releases/1.8.0.md). Older dated investigations below
remain historical evidence, not the current release verdict.

Phone-only: the TV flavors, vendored ExoPlayer fork and Leanback modules were
deleted. Playback uses Media3 1.10.1 with embedded Cronet and an OkHttp fallback.
Toolchain: AGP 9.2.1 / Gradle 9.6.1 / compileSdk 37 / targetSdk 37 / minSdk 24.

## UX pass: the pink app, the pale band, and a settings screen that lied (2026-09-08)

A sweep of the phone surfaces on the Pixel 9, screenshot by screenshot. Three
things turned out to be app-wide rather than local.

**The theme never had an accent, so it kept Material's.** `mobile_color_accent`
was still `#FF4081` - the stock Material pink - and `colorAccent` feeds far more
than its name suggests. On a black-and-red app that painted: every section header
in Settings, every checked checkbox and radio dot (the whole `Interfaz de usuario`
screen came out hot pink), the search field's text caret, and the three
indeterminate spinners that carried no explicit tint (search, channel, channel
uploads). Selection controls are monochrome now - `colorAccent`, `colorSecondary`
and `colorControlActivated` all point at `mobile_color_on_surface` - the untinted
spinners were given the same `mobile_color_primary` red the other five already
used, and the dialog section header dropped `textAllCaps` for the secondary-white
14sp the You page's own group label uses. The one coloured icon state left in the
app is cast-connected, which got its own name (`mobile_color_cast_active`,
`#4FC3F7`) instead of riding on the theme.

**Every bottom sheet stopped 63px short of the display edge.** Edge-to-edge means
Material pads the sheet frame by the navigation inset - but all seven of our
sheets paint their surface on the view INSIDE that frame, so the padded strip
showed whatever the frame was: a dark band under the gear/quality/captions/speed
sheets, a paler `#3F3F3F` one under comments and accounts, with the gesture pill
floating on it. Two causes, one fix. Without `bottomSheetDialogTheme` a
`BottomSheetDialog` falls back to Material's `Theme.Design.Light.BottomSheetDialog`
(a WHITE frame - which is what the old "paint the frame transparent" workaround in
four files was hiding), and where the frame did get a surface, the 16dp sheet
elevation blended 14.75% white into `#1E1E1E` and produced exactly `#3F3F3F`.
Styling the frame properly - our own `bottomSheetDialogTheme` with
`elevationOverlayEnabled=false` and top-only 16dp corners - fixes both, because
Material paints the frame itself on every layout and quietly wins over any
background assigned in code. That is why the code-side workarounds never stuck.

**The UI settings screen offered choices that did nothing.** `Esquema de color`
(9 options behind a "restart the app" toast) is read only by
`MotherActivity.initTheme()`, which `MobileActivity` overrides to a no-op;
`Velocidad desplazamiento texto tarjeta` (15 marquee speeds), `Vista previa de la
tarjeta`, the card-style checkboxes and the top-bar button pickers have zero
readers anywhere outside `MainUIData` itself. All dropped from
`MainUISettingsPresenter` (stored values untouched, so nothing is lost if a knob
is ever wired up). What is left - thumb source, channel sorting, UI scale, misc -
is live.

Two smaller ones: the `En vivo` section was titled with `badge_live`, the
uppercase thumbnail badge string, so it shouted next to Deportes/Noticias/Música
(new `header_live`, sentence case, EN + ES); and the comments and live-chat sheets
sized themselves from `getResources().getDisplayMetrics().heightPixels`, which
`MotherActivity.initDpi()` replaces process-wide with a single instance cached at
the first Activity's `onCreate` - start the app in landscape and the comments
sheet opened 918px tall (85% of 1080) in portrait for the rest of the process.
Both sheets now measure the live display through `MobileSheets.expandTo`.

**Pixel 9 verified** (portrait and landscape, screenshots + pixel sampling): sheet
surface `#1E1E1E` continuous to y=2423 on the gear, quality, comments and accounts
sheets; comments now opens at 2060px (0.85 x 2424) instead of 918; settings radios
and the search caret white; `En vivo` in the section list. The landscape player's
system bars reappearing while a sheet is open was A/B'd against a build with the
sheet theme removed and behaves identically - pre-existing, not introduced here.
378 unit tests pass (plus 51 in sabr-media3).

**Left open, found but not fixed:** `Listas de reproducción` and `Mis vídeos`
share `icon_playlist` in `BrowsePresenter`'s section mapping (needs a new asset,
the section icons are TV density-bucket PNGs); and the Misc category of the same
settings screen was not audited knob by knob the way the top-level categories
were - several of its entries (corner clock, channels old look, fullscreen mode,
pinned channel rows) look TV-shaped too.

## Playlist Shuffle stops being an app-wide setting (2026-09-08)

Closes the parity gap listed under Open - product/UX: "`Shuffle` turns the
player's repeat mode to shuffle, which is a PERSISTED setting - it stays on
until changed. That is why the button confirms with a toast. YouTube scopes
shuffle to the queue instead."

**The old behaviour, caught in the wild.** Before touching anything, the test
Pixel's gear -> More -> Aleatorio read **Activado** on an ordinary home-feed
video - left over from a playlist Shuffle tapped in some earlier session. One
tap on one playlist had been quietly shuffling everything since.

**The fix.** New `QueuePlaybackMode` (common/) holds a transient shuffle scoped
to the playlist that armed it. `VideoLoaderController.getPlaybackMode()` - the
single funnel every autoplay decision already went through - lays it over the
stored mode, and the two per-video specials (`finishOnEnded` -> CLOSE, shorts
loop -> ONE) still outrank both. Nothing is written to disk, so a process
restart drops it.

Three details worth keeping:
- **The disarm hangs off `onNewVideo`, not off the read.** The first cut
  self-disarmed inside `apply()`, which is also read from the preload tick and
  from menus - and a video can momentarily carry no playlist id while it is
  being resolved, so a passing glance could have ended the shuffle. `apply()` is
  now a pure read; only "playback moved to this video" can disarm.
- **`PlayerData.setPlaybackMode()` clears the override**, so any picker that
  states a mode outright wins, and no UI site has to remember to do it.
- **The player's Shuffle row now reads the EFFECTIVE state.** Reading only the
  stored mode would have shown "Off" while the queue visibly shuffled, and a tap
  meaning "stop shuffling" would have turned shuffle on for everything instead.

The toast is gone with the reason for it, and `mobile_playlist_shuffle_on` was
dropped from both locales.

**Pixel 9 verified, stored mode known to be ALL beforehand:** feed video ->
`Aleatorio: Desactivado`; Shuffle on "Vídeos que me gustan" -> a random item
opens, `Activado`; back to a feed video -> `Desactivado`. 8 unit tests pin the
scoping; 378 unit tests pass overall.

**Not claimed:** that the whole rest of a long playlist keeps shuffling. That
path is gated upstream by `MIN_SHUFFLE_SIZE = 30` (unchanged here) and the test
playlist holds 10, so it never armed. Worth noting from the same run: the
shuffled open showed the queue card as `Reproduciendo de Ver más tarde - 1 / 1`
rather than the playlist it was started from, and pressing next-track left the
playlist for a related video - the playlist-context gaps already listed under
Open, not something this change introduced.

## Edge-to-edge: the rule resolved, and one overlay that was wrong (2026-09-08)

`CLAUDE.md` carried a blocking rule - "`windowOptOutEdgeToEdgeEnforcement` dies
at targetSdk 36, proper per-screen insets are REQUIRED before any targetSdk
bump" - while `SharedModules/constants.gradle` has said `targetSdkVersion = 37`
for some time. Resolved by measurement rather than by reading release notes.

**Which mechanism is actually live, per OS.** Same build, two devices, reading
the private window flags in `dumpsys window windows` (the `pfl=` line):

| device | Android | `pfl=` on our window |
|---|---|---|
| Mi 8 | 15 / API 35 | `NO_MOVE_ANIMATION FORCE_DRAW_STATUS_BAR_BACKGROUND FIT_INSETS_CONTROLLED` |
| Pixel 9 | 17 / API 37 | `NO_MOVE_ANIMATION EDGE_TO_EDGE_ENFORCED FIT_INSETS_CONTROLLED` |

A different app on the same Android 15 device *does* carry
`EDGE_TO_EDGE_ENFORCED`, so the flag's absence is our opt-out working, not the
OS lacking the feature. Conclusion: the attribute is **not** dead - it still
separates the bars at minSdk 24 - and from Android 16 it is ignored, along with
`setDecorFitsSystemWindows` / `setStatusBarColor` / `setNavigationBarColor`.
What keeps modern devices right is `MobileActivity.installContentInsets()`,
which shipped earlier; the rule's requirement was already met. Rule rewritten
from a blocker into a description of the two paths.

**The bug the sweep found.** `MobileAppDialogActivity` (every context menu and
every player picker) inherited the blanket content padding, which is wrong for
a window that paints its own scrim. Measured on the Pixel 9, sampling the
screenshot column at x=20: the dim began at **y=173** - exactly the status-bar
height - and the sheet surface ended at **y=2361**, 63px (the gesture inset)
short of the display edge. So the status bar sat undimmed above the scrim and
the sheet floated above the bottom edge.

Fixed by opting the overlay out of the blanket inset
(`shouldInsetContentForSystemBars() -> false`) and applying the insets inside
it: the scrim is full-bleed, the sheet takes side+bottom padding only so its
rounded background reaches the edge while the last row still clears the gesture
bar, and the sheet's max-height cap is now a fraction of the *usable* height
rather than of the raw display. Re-measured after: dim starts at y=0, sheet
surface runs to y=2423. Full-screen settings unchanged (verified), portrait
player sheet verified, 308 smarttubetv + 62 common unit tests pass.

**Left open, measured not fixed.** The Material `BottomSheetDialog`s hosted by
the activities (player gear sheet, comments, live chat, cast picker, accounts -
10 construction sites) still stop 63px short of the bottom: our theme descends
from `Theme.MaterialComponents`, whose `bottomSheetDialogTheme` does set
`enableEdgeToEdge=true`, but at targetSdk 36+ the window call Material uses to
act on it is a no-op, and `showPlayerSheet` deliberately clears the sheet
frame's background so Material's own inset padding would land outside our
rounded drawable anyway. Fixing it needs per-site layout work - it belongs to
the UI/UX sweep, not here.

## 1.8.1: optional SABR VOD source, shipped OFF (2026-09-08)

Native Media3 SABR has two roles, **both default OFF**. **Fallback** carries a
response whose adaptive formats have no URL at all - the case where the app
otherwise skips to the next video. **“Prefer SABR even when links work”** is the
byte-saving experiment. The decoder handles bounded
initialization/continuation, seeks, quality changes, cancellation and lazy
captions. A failing *preferred* source is terminal; a failing *fallback* source
defers to the normal client recovery.

**Real YouTube SABR delivery now works** (2026-09-08, later round). The earlier
HTTP 403 was the client gate, not the protocol: eligibility required an
authenticated **TVHTML5** response, and TVHTML5 is the one client whose media is
dead. Off-device, from a different network and an anonymous identity with no PO
token, VISIONOS/IOS/ANDROID_VR/ANDROID each return HTTP 200 and 133,605 bytes of
UMP media, while TVHTML5 7.x returns 403 with an empty body. Three defects were
fixed together: the client gate; `enabledTrackTypesBitfield` (there is no
video-only value, so a video request must name and suppress a companion audio
format); and treating the server's paced, media-free response as terminal.

Measured on the Pixel 9 over Wi-Fi with the client pinned to VISIONOS for both
arms — 6 opens each, ABBA order, identical formats (720p AVC itag 136 + AAC 140)
in all 12 opens, zero rebuffers and zero dropped frames: first frame median
**DASH 267 ms vs SABR 333 ms** (+24.7 %), app UID bytes **4,590,625 vs
4,099,509** (−10.7 %), CPU +4.8 %. SABR costs ~66 ms of startup and saves ~490 KB
per eight seconds of 720p. One video, one network, no soak, no cellular arm, no
ABR or battery evidence. The preference stays **off by default**.

**The fallback was dead wiring, was fixed, and still earns nothing**
(2026-09-08, latest round). A link-less response never reached the decoder —
`containsAdaptiveVideoInfo()` reports such a list as no adaptive video, so
`containsSabrFormats()` was false and the `openSabr` branch never ran, while
`isUnplayable()` already said "playable". Enabling SABR turned "skip to the next
video" into "sit with no source". Fixed in the DTO, the preference split and the
failure path; a link-less IOS response now reaches `prepare type=sabr-vod` on
4/4. That fix is *internal to the feature* — with the capability off, upstream's
classification is correct and the ring walks on as it always did.

**Then it was turned off by default, on the measurement.** It has never carried
a video that would not otherwise play: seven unpinned opens all went VISIONOS →
`dash-mpd` → first frame (the ring's lead client still hands out URLs, so the
fallback is never reached), the media 403 that does occur is rescued by the
existing quarantine + ring walk on a client with no SABR endpoint at all, and
when the path is forced it fails on every video. Cost is non-zero — accepting a
link-less answer stops the ring at that client and spends four recovery attempts
on SABR first.

**And it cannot be fixed by request tuning: there is a ~60 s attestation wall.**
`RELOAD_PLAYER_RESPONSE` was a symptom. Sweeping the start position (three
videos, fresh anonymous sessions) shows that without a PO token IOS and
ANDROID_VR are served only up to **between 56.2 s and 60.0 s**, after which
`STREAM_PROTECTION_STATUS` turns `ATTESTATION_REQUIRED` and no media comes back.
The wall is positional, not a session quota. The clients that answer *without
links* — the only ones the fallback ever sees — are exactly the walled ones, so
the fallback is structurally capped at the first minute of any video. Both
switches therefore stay off.

**VISIONOS SABR, by contrast, is unwalled and works end-to-end.** Status `OK` at
every position including the last seconds, and on the Pixel 9 with the
experiment on: `prepare type=sabr-vod` → `first-frame +2369` → still `PLAYING` at
**2:34** with zero protection/reload errors over three and a half minutes. The
opt-in experiment is the working path; the fallback is the broken one. See
HANDOFF §28.

**The companion-audio suppression never worked, and the data saving was an
artifact** (HANDOFF §28b). Bytes over a fixed playback window compare *prefetch*,
not efficiency — at 8 s played DASH had buffered 58.7 s to SABR's 32.0 s, which
is where "~11% fewer bytes" came from. Per minute of media actually fetched SABR
cost **8.9 MB against DASH's 5.8** (+55%), because every video response shipped a
full duplicate audio segment: the whole-track buffered claim was silently ignored
without `start_segment_index`/`end_segment_index`. With those set the companion
drops to a 2,324-byte init segment. Re-measured on cellular, 4 videos, 3 opens
per arm, identical formats, no rebuffers: SABR now costs **5.1 MB/min vs DASH
5.8** — parity, between −9% and +7% per video, with first frame ~60 ms slower.
So there is no data-saver case; what the fix buys is that the insurance path no
longer wastes half its bandwidth.

Shipped as an **opt-in delivery path** in the signed **1.8.1** build (versionCode
10801): both toggles are in Settings and off, DASH carries everything by
default, and the SABR module's runtime classes are in the release DEX while its
proof/fixture code is not. Not published as a GitHub release — no release
record, poster or announcement copy was produced for 1.8.1, only CHANGELOG
entries.

Why keep it at all: IOS, ANDROID and TVHTML5 already return zero formats with
URLs — only VISIONOS and ANDROID_VR still hand them out. When the lead client
stops, this is the path that has to work, and the remaining gap is the reload
handshake, not the 403 the work started from.

Offline tests pass (308 smarttubetv, 62 common, 50 SABR module, 35 youtubeapi
SABR/ring subset); debug/release APK assembly passes.

See [implementation, comparisons and candidate](SABR-MEDIA3-2026-09-08.md) and
HANDOFF §27.

Build a phone APK:
```
ANDROID_HOME=<sdk> ./gradlew :smarttubetv:assembleStmobileDebug
# -> smarttubetv/build/outputs/renamed_apks/stmobileDebug/NewTube_<ver>_universal.apk
```

## Startup bandwidth, real preload, and release profiling (2026-09-08)

Startup ABR now ages measured per-network bandwidth hints, starts cautiously when
the hint is stale, and recovers quality from real transfers. On the controlled
Pixel runs, READY improved about 0.4 seconds at 700 kbit/s and 0.7 seconds at
1.5 Mbit/s. These are small-sample observations, not general speedup guarantees;
the first chunk is deliberately lower quality. A direct LTE/5G-NSA sanity check
reached 1080p about nine seconds after READY without an observed rebuffer.

Bounded actual next-video sample preloading is implemented but remains opt-in.
The local Pixel decoder fixture passed handoff, cancellation and manual-quality
checks. Ordinary autoplay's speculative media returned HTTP 403 and was cancelled
without retry; no live preload hit was established, so the default remains off.
Disabled preloading adds no preload builder or second selector to cold startup.

Added a non-debuggable Macrobenchmark target and generated/reviewed 456
app-specific baseline-profile rules. All rules were verified in both compiled
benchmark and release APKs; profile compilation and live-network speed claims
remain separate. Twelve reversed-order cold starts found no convincing profile
TTFF improvement. The final broad local pass has 391 passing Android and 49 host tests, with
debug, AndroidTest, benchmark, release and measuring-APK builds passing.

Details and exact artifact identities: [startup follow-up](STARTUP-SPEED-2026-09-07.md),
[Pixel network evidence](PIXEL-STARTUP-ABR-2026-09-07.md),
[preload acceptance](NEXT-MEDIA-PRELOAD-2026-09-07.md), and
[release measurements](RELEASE-BENCHMARK-2026-09-08.md).

## Latest TTFF, upstream, and Pixel network pass (2026-09-07, evening)

Removed redundant nested JSON serialization/parsing before playback: the real
24-format metadata fixture now needs one text parse instead of 76, with mapped
values preserved. Ported three missing upstream fixes: strict request JSON,
active-only buffering-watchdog accounting, and integer-overflow fallback.
175 focused Android tests pass, as do debug/release builds and release lint.

Two agents coordinated direct-cellular and whole-app shaped-network tests on
the USB-connected Pixel. The 250 ms gate did not improve the successful startup
episode over 500 ms, and deferred metadata loading showed no convincing gain;
both production policies remain unchanged. The parser candidate's two
1500 kbit/s opens were effectively tied with the prior candidate (median
4.13 vs 4.17 seconds to decoded frame). A ten-second whole-app blackout was
absorbed by the buffer, with media loads resuming afterward. These are not
compositor-visible-frame measurements or an isolated parser-only speed claim.

The current runs reached playback after one initial media 403 and normal app
recovery. Older statements below about total denial or zero initial 403s belong
to their earlier builds/captures, not this test round. Exact candidate hashes,
weak-link results, test scope, restoration, and caveats:
[latest TTFF/network report](TTFF-NETWORK-2026-09-07.md),
[upstream/network comparison](UPSTREAM-NETWORK-2026-09-07.md), and
[cellular gate ABBA](PIXEL-TTFF-GATE-2026-09-07.md).

## The bot check traced to a dead account route (2026-09-07)

Rusowsky (`Fo89b8zAIE4`) plays on the Pixel over LTE and has all along; every
open just burned a failed attempt first. Reproduced off the device with a fresh
anonymous visitor on a different IP, using the app's own bundled solver: the
upstream `+001` signature-timestamp suffix makes YouTube answer with formats
whose media URLs are already dead. Same client, same session, only the timestamp
differing — TVHTML5_SIMPLY serves HTTP 206 at the real five-digit value and 403
on the first byte at the suffixed one. TVHTML5 has no working configuration
either way (real timestamp → "the page needs to be reloaded", suffixed → dead
URLs at 5.x, SABR-only at 7.x), so the account cannot play anything, the phone
plays anonymous, and an anonymous guest identity on carrier CGNAT is what gets
bot-challenged.

Our signature/`n` solver is correct — proven by deciphering TVHTML5_SIMPLY's
URLs to a 206 — which closes the evidence gaps left open by the cache-integrity
and player-metadata rounds. Shipped: the timestamp suffix is scoped to the
Cobalt TVHTML5 client, a SABR-only answer from an account head now counts as a
no-media verdict, the 403 quarantine survives a process restart, and a fresh bot
challenge rotates the anonymous identity (reversing the earlier
session-preservation removal, at the owner's request; the account credential is
untouched). Cold open on the Pixel goes from 5.15–5.48 s to first frame to
3.2–3.8 s, one `/player` instead of three, zero 403s. 139 focused youtubeapi +
218 smarttubetv + 48 common tests pass. Details, the full client/timestamp
matrix and the caveats: [`HANDOFF.md`](HANDOFF.md) section 26.

## TTFF and playback stability take priority (2026-09-07)

Product decision: spend CPU/memory/bandwidth for faster startup and stable
playback. The start gate is now 500 ms; 1500 ms recovery and all forward/back
buffers remain unchanged. Native/cache initialization starts on a worker during
launch, related UI rendering yields until playback, and the ready new-video
still disappears without its 120 ms fade. Local Pixel ABBA reached READY at a
median 693 ms vs 1091 ms, with four clean 45 s soaks plus seek/pause/switch checks.
These are decoder/readiness results, not newly measured YouTube TTFF.

The server bot check still blocks NewTube while the owner reports official
YouTube works. Play-to-retry after pre-media denial is repaired and honors
existing cooldown/cache; the benchmark now aborts at an unavailable phase.
110 Android + 13 offline tests pass. Details and limitations:
[`TTFF-PRIORITY-2026-09-07.md`](TTFF-PRIORITY-2026-09-07.md).

Existing-session follow-up: removed an older denial-triggered visitor reset,
preserving the current session, cooldown and playback-route ordering. The
initial replay still received denial from all nine routes. A subsequent
upstream comparison found the missing TV request-timestamp normalization.
That narrow fix is installed: the same authenticated TV route now returns
`OK` with 22 usable adaptive formats, instead of the reload-page verdict.
The existing account was not the demonstrated cause of that verdict.

Playback is still blocked at the next stage: initial audio/video media ranges
return HTTP 403 with zero bytes and no first frame. This is not a successful
soak or a TTFF measurement. 138 focused Android tests and both APK builds pass;
real-video acceptance remains open. The current finding supersedes the older
claims below that client-side fixes were ruled out or new credentials were
necessary. See [the metadata follow-up](PLAYER-METADATA-2026-09-07.md).

Latest media follow-up: fixed and installed an independently reproduced cache
publication bug (key/code/metadata now commit together). The Pixel still gets
initial media 403 with no frame. Freshly rebuilt cached code matches the legacy
code exactly, and all requested transformations return complete, changed outputs;
neither cache corruption nor missing outputs explains this capture. 161 Android
and 27 harness tests and debug/release builds pass. The accepted SABR-only response
exposes a missing Media3 streaming-source capability, not a demonstrated working
media endpoint. A SABR port is substantial and remains unimplemented. See
[cache evidence and next integration scope](MEDIA-CACHE-INTEGRITY-2026-09-07.md).

SABR proof follow-up: a test-only Pixel probe confirmed raw `OK` and server
authentication with the existing account, then its first **untouched-URL** SABR
audio POST returned HTTP 403 after 333 ms. It stopped without a video request or
recovery. This does not prove a complete SABR port would fail or solve the media
problem. 218 app unit tests and 2 local decoder-helper device tests passed;
the latter are not SABR/YouTube playback. Main APK unchanged. See
[isolated proof and limitations](SABR-PROOF-2026-09-07.md).

## Pixel performance follow-up (2026-09-07, before TTFF-priority decision)

Implemented redundant-seek, artwork, metadata-lifetime, hidden-control polling,
warmup-timer, initialization-order, and cache-key allocation fixes. 26 controlled
cold/immediate/switch/sustained phases reached a first frame; 86 focused checks
pass. Aggregate CPU, PSS, and decoded TTFF did not show a convincing improvement,
so dynamic scheduling and deferred eager setup were rejected. A 500 ms start
gate reduced visible dense-resume latency in short shaped runs, but its longer
test and the final normal-settings soak were blocked by an explicit server
bot check before media preparation. Keep the 1000 ms default; all experimental
device overrides were restored. The reported external PiP was traced to an
adb quoting mistake; the subsequent NewTube PiP-to-next-video test passed.

Full protocol, measured results, retained fixes, and limitations:
[`PERFORMANCE-2026-09-07.md`](PERFORMANCE-2026-09-07.md).

## Measured (added 2026-09-07 — memory profile and how far the account gets)

Two open questions from the LTE rounds closed by measurement. Full evidence in
HANDOFF §19.

**PSS is a working set, not a leak.** Six videos, resting PSS after each:
370 / 358 / 355 / 375 / 364 / 366 MB — flat, no trend. Views pin at 400 from
the first open, AppContexts oscillate 10–11, Activities stay at the two that
are legitimately in the back stack. A forced trim drops the process to 256 MB
(graphics 91 → 6 MB), so ~110 MB of the resting figure is cache handed straight
back under pressure. The 453 MB quoted earlier was a mid-playback sample.
Playback peaks ~500 MB, half of it decoder surfaces, all released on BACK.

**A cold-start peak coincides with SessionWarmup**: native heap spikes 57 → 186 MB for
about two seconds at t+5s, exactly inside the warmup window, then collapses
back. Invisible on the Pixel 9 (11.8 GB). It would matter on a 3–4 GB phone,
where a 471 MB peak five seconds into every cold start is prime LMK territory;
`isLowRamDevice()` gating is the mitigation if NewTube ever targets those.
Which allocation inside the window is responsible was not isolated. The later
performance follow-up above distinguishes the speculative format fetch from
eager startup setup; timing alone does not attribute the entire spike to one.

**Historical result, superseded by the metadata follow-up:** Every `/browse` and `/account`
call is `auth=y`; only `/player` lands anonymous. So feeds, playlists, likes and
subscribe are all fine, and the cost of §17's open thread is precisely:
age-restricted, members-only and private playback, server-side watch history
(tracking pings inherit the anonymous /player session), and Premium
entitlements.

**The earlier credential-only diagnosis was premature.** Our `TV_DOWNGRADED` is
`5.20260707`, byte-identical to yt-dlp's `tv_downgraded`, and still returns
"reload page" with `srvAuth=y`. A three-arm run then isolated the bearer as the
only variable: WEB_EMBED+bearer and WEB+bearer both give HTTP 400 with a
byte-identical body, while the same WEB client without the bearer gives 200.
The 400 followed the bearer in those web-client comparisons; that did not
rule out a malformed TV request. Matching client versions missed the TV-specific
timestamp format. With that fixed, the existing bearer receives accepted TV
playback metadata. No cookie import, new sign-in UX or session borrowing is
justified by those earlier measurements. `debug.arc.web_auth` remains inactive.

## Works (added 2026-09-07 — LTE soak follow-up)

Five soak rounds on LTE found two ring bugs. The auth-route quarantine counted
videos that are unavailable to *every* client as evidence that the account route
is broken — two such videos in a row silently demoted a healthy route and served
everything anonymously from then on. An observation now counts only once some
other client has served the same video.

And the live dash-manifest search, documented as costing one extra `/player`
round trip, was costing six after the quarantine reordering pushed ANDROID_VR to
seventh in the ring. Measured across two 24/7 streams, only ANDROID_VR ever
returns a live dash manifest, so the walk skips the clients that cannot: first
frame +2472ms → +1216ms on one stream, VOD untouched.

A 25s LTE outage mid-video is covered entirely by the buffer — position advances
at 0.96x wall clock with zero buffering events — and costs one 3s reload at the
same position when the buffer runs dry. 252 focused tests pass. Detail:
[`HANDOFF.md` §18](HANDOFF.md).

## Works (added 2026-09-07 — bot check walked past, playback anonymous)

The denial above was three problems, not one. Authenticated TVHTML5 answers
`UNPLAYABLE` "reload page" for every video (yt-dlp #17389); the anonymous
partition is separately challenged on the carrier CGNAT; and our circuit
breaker aborted the client walk at attempt 3 of 10, then blocked every video
for 15 minutes.

The ring now keeps walking past a challenge while a later client neither needs
a web PO token nor is skipped, and only raises the verdict if it genuinely runs
out — with one probe per minute so a cleared challenge is noticed early. Two
authenticated heads returning `UNPLAYABLE` with no media of any kind quarantine
that route after two hits on different videos; `VISIONOS` leads the signed-in
fallback from then on. Five videos at shipping defaults: 0 bot checks, 0 load
errors, 0 403s, 70 clean media loads, first frame 2221 → 822 ms as three
`/player` round trips became one.

Two paths were measured and rejected, and are documented at their flags rather
than deleted. A player PO token for `ANDROID_VR` does not prevent its deep-range
403 (both A/B arms died at the same wall). Putting the account on `WEB_EMBED`,
yt-dlp's signed-in head, returns HTTP 400 "Request contains an invalid
argument." every time — InnerTube will not take a TV device-flow bearer on a
web client.

**Historical open issue:** every winning line in that round read `auth=n`.
The later metadata fix now yields accepted signed-in TV responses, but media
403 still blocks playback. Do not infer that a different credential is required.
244 focused tests passed in that earlier round. Detail and measurements:
[`HANDOFF.md` §17 and §23](HANDOFF.md).

## Playback denial and scheduling follow-up (2026-09-07)

Captured an explicit YouTube LOGIN_REQUIRED/bot-check result before media
preparation on Rusowsky. The owner reports normal playback in YouTube itself.
The server denial remains unresolved; this is separate from the earlier media
transport delay.

Pending session warmup now yields to a selected video, with atomic scheduling
and a post-delay check. Idle-browse warming remains available for cache refresh.
Obsolete queued MPD builds are skipped before work, and late source publication
cannot undo reset/release cleanup. An eager `/next` request is now cancelled and
its rendered result cleared when `/player` reports a bot check. The three
profiles in the captured walk were internally consistent and match current
upstream definitions, so no profile or auth-header values were changed. No
external-player flow was added. The debug APK and 87 focused tests pass.
Evidence and validation: [`BOT-CHECK-2026-09-07.md`](BOT-CHECK-2026-09-07.md).

A controlled replay at 12:27 reproduced the denial in 2.55 seconds and never
reached media preparation. Suggestions cancellation worked, but QA found that
portrait hid the server reason in its unused overlay-title field. Unplayable
reasons now use the persistent playback notice and clear only on a different
video or a playable result. The rebuilt candidate was installed at 12:35:32;
there was no post-install replay.

## Works (added 2026-09-07 — startup transport follow-up)

Cronet remains the primary media transport with QUIC enabled. Its fallback is
now the official Media3 OkHttp adapter: on the Pixel, successful initialization
requests took 121–366 ms through standard OkHttp versus 8185–8292 ms through
the old regular HTTP fallback. Both Cronet and OkHttp still encountered the
existing initial media 403 pattern; this change does not claim to fix it.

Preconnect no longer suppresses a host indefinitely after one attempt. Success
expires after 60 seconds, failures retry after five seconds, and a different
default network invalidates stale warming. Active work and remembered hosts
are bounded. All 38 focused tests pass. Measurements, comparison limits and
live recovery verification:
[`STARTUP-TRANSPORT-2026-09-07.md`](STARTUP-TRANSPORT-2026-09-07.md).

## Works (added 2026-09-07 — Pixel pass and default-network recovery)

Live Pixel QA covered four Tiny Desk sessions plus an autoplay video, deep
seeks, related switches, and PiP. Milo J reproduced a media HTTP 403 on both
the installed 1.6.1 baseline and the 1.7.0 candidate; existing automatic
recovery rendered a frame in about 2.7–2.9 s overall. The rejection remains
reproducible. Client identity and token settings were unchanged.

Fixed a separate capped-player recovery gap: a validated replacement default
network can arrive without `onLost(old)`. The callback now recognizes that
handover, waits for validation, and retries once. Healthy registration replay
does not retry, and cancelled callbacks cannot reload a later episode.
Twelve callback regression tests pass, including an isolated failing test with
the handover detection removed. Six media load-policy tests pass, including a
local socket reproduction of a ranged HTTP 403.

The final candidate also recovered automatically after a 150-second computer
emulator blackout; first frame arrived about 4.5 s after recorded restoration.
This is one observation, not a worst-case latency bound. Full video timings,
build verification, and test limitations:
[`LIVE-PASS-2026-09-07.md`](LIVE-PASS-2026-09-07.md).

## Works (added 2026-08-06 — netshape round: a real bad-link rig, and what it found)
The previous round's fixes were reasoned about but never measured under
contention, because nothing on the bench could shape the WHOLE app. This round
built that, and it immediately found three defects that account for all three
field symptoms.

**The rig: `tools/netshape.py`.** An HTTP CONNECT proxy in WSL with ONE shared
token bucket per direction, live-controllable RTT/bandwidth, and a blackout
toggle. The emulator reaches it at `10.0.2.2` (emulator → Windows loopback →
WSL localhost forwarding):
```
python3 tools/netshape.py --port 18080 --control 18081 --down-kbps 1200 --up-kbps 400 --rtt-ms 120
adb -s emulator-5554 shell settings put global http_proxy 10.0.2.2:18080   # (:none to undo)
curl -s 'localhost:18081/set?blackout=1'   # enter a tunnel
curl -s localhost:18081/timeline           # per-second bytes, per host
```
Verified to carry all four traffic classes: API (`www.youtube.com`), media
(`*.googlevideo.com`), thumbnails (`*.ytimg.com`), avatars (`*.ggpht.com`).
Sharing one bucket is the point — a thumbnail burst genuinely steals bytes from
the media stream, which a per-DataSource shaper cannot reproduce.

**Why not Bright Data** (the originally-requested approach): all three zones
(datacenter, ISP-ES, residential-ES) refuse `CONNECT www.youtube.com:443` and
`CONNECT *.googlevideo.com:443` with `x-brd-err-code: policy_20050` — YouTube
is KYC-gated on their compliance list. `youtubei.googleapis.com` passes; the
media and thumbnail hosts do not, so the app cannot run through it at all. Not
a credential problem: the residential exit itself was healthy (Vodafone ONO,
Madrid). Credentials live in `~/projects/hola-vivi-scrapers/.env`.

### 1. Related thumbnails were starving the video (the "switching takes ages" bug)
The watch page binds 12 related rows at once, each asking the CDN for
`sddefault.jpg` — **114 KB** — into a **160dp × 90dp** row. Glide downsamples
on the DEVICE, so the full 114 KB crossed the wire before most of it was thrown
away. Per-second attribution at 1200 kbps, feed idle, media vs thumbnails:
```
sec 5:  21 kB / 48 kB     sec 8:  96 kB / 74 kB
sec 6:  32 kB / 93 kB     sec 9:  58 kB / 94 kB
sec 7:  64 kB / 69 kB     sec 11: 134 kB / 0 kB   <- thumbnails done, media doubles
```
Thumbnails took MORE bytes than the video through the whole startup window.
Fix: `ClickbaitRemover.fitThumbnail(url, targetWidthPx)` asks the CDN for the
narrowest always-available rendition that still covers the view — `hqdefault`
(480×360, **25.6 KB**) is a pixel-exact match for a 480px-wide row at 3x. Only
ever downgrades, and never touches `hq1/hq2/hq3` (those are the clickbait
remover's FRAME selectors, not sizes). Unit-tested:
`ClickbaitRemoverFitTest` (5 cases incl. never-widen and a `/hq720-abc/` path
collision). The loading still now asks for the wide rendition
`onlyRetrieveFromCache`, falling back to the narrow one, so it costs zero bytes
from either entry point (feed tap or related tap).
**A/B, same fixed video, cold image cache, 3 runs each, 1200/400/120:**

| | thumbnails | media in same 30 s | first-frame |
|---|---|---|---|
| before | 1873 / 1844 / 1844 kB | 2340 / 2348 / 2402 kB | 2361 / 3224 / 2577 ms |
| after | 467 / 437 / 445 kB | 3503 / 3782 / 3734 kB | 2288 / 1785 / 2007 ms |

−76% thumbnail bytes, **+55% media throughput**, −25% time to first frame.

### 2. A dead link was being blamed on the /player clients
In a 150 s blackout the ring walked VISIONOS (7 s timeout) → WEB_EMBED (**20 s**)
→ WEB → WEB_SAFARI → GEO → budget exhausted at 45 s → reload → another walk.
Every failure was a transport timeout with no HTTP response at all, so the
client was never the variable. Fix (`VideoInfoService`): two CONSECUTIVE
no-response attempts (`TRANSPORT_DOWN_STREAK`) end the walk with
`player-ring transport-down` instead of burning the budget. Transport failures
are identified by an `IOException` anywhere in the cause chain — `RetrofitHelper`
rethrows those as `IllegalStateException` specifically to "notify caller about
network condition", and HTTP error statuses are NOT IOExceptions, so 403s still
advance the ring exactly as before. Walks now end in 2 attempts, not 5.

### 3. A tunnel left the player permanently dead (the headline field bug)
Reproduced deterministically, then fixed. Two separate causes:
- **`isConnectivityError` returned false during a tunnel.** It fell back to
  `!hasValidatedNetwork()`, but a tunnel keeps the radio attached, so
  `NET_CAPABILITY_VALIDATED` stays set for the entire outage — every
  `recovery-count` line in the capture reads `validated=y internet=y`. So the
  cap was reached with `connectivity=n`, which armed NEITHER the retry timer NOR
  the network listener, and **the player emitted not one further log line for
  the rest of the run.** (`scheduleAutoRetry`'s own comment already described
  this exact case; the classifier contradicted it.) Fix: stop enumerating what
  an outage looks like and identify the one positively-knowable thing instead —
  whether YouTube actually answered. A verdict-free failure (`hasServerVerdict`
  false: a cause-less `IllegalStateException`, i.e. "no answer") is retriable; a
  real server verdict is not. The ladder is bounded (5 attempts ending at 300 s).
- **Recovery abandoned the known-good client.** A recovery walk deliberately
  steps past the last winner, which is right for an expired-GVS-URL 403 and
  wrong for an outage. A 150 s tunnel ended with playback restored on
  `WEB_EMBED`/`ANDROID_VR` (`auth=n`, `sabr=y`, 28–36 formats) instead of
  `TV_DOWNGRADED` (`auth=y`, 41 formats) — an outage silently cost the user
  authenticated playback and 13 renditions. Fix: `mLastWalkTransportDown`
  suppresses the recovery cursor for one walk (`player-ring recovery-suppressed`).

**Verified end-to-end, 150 s blackout:**
```
20:12:54  player-ring recovery-suppressed reason=transport-down keeping=TV_DOWNGRADED
20:13:19  player-result client=TV_DOWNGRADED status=OK auth=y formats=41+1 sabr=n
20:13:22  first-frame +10935          <- link returned at 20:13:19
20:13:43  (next video) first-frame +2080
```
Playback resumes **3 s after the link returns, on the authenticated client**.
Before: nothing, ever. A 45 s tunnel already recovered before this round (media3
holds one chunk load across ~43 s of retries) — it is the longer outage, past
the reload cap, that was dead.

**Honest limits.** All numbers above are emulator + netshape, not a real radio:
no packet loss, no bufferbloat, no RRC state transitions, no handover. The
shaper is a token bucket, so it models a clean rate limit, not a congested cell.
The thumbnail A/B is the most solid result (fixed video, cold cache, 3 runs,
non-overlapping ranges); the tunnel results are single runs per configuration,
verified by mechanism in the logs rather than by repetition. Not re-measured
this round: the first-frame-on-a-healthy-link question from the previous round
(see below) — it is now confounded further by the thumbnail fix, which moves the
same metric, so treat the old 947→1183 ms observation as superseded rather than
resolved.

## Works (added 2026-08-06 — bad-network round: 5-agent audit + fixes)
Triggered by a field report: on real LTE, especially with poor signal, "nothing
loads", switching videos "takes ages", and a tunnel is never recovered from.
Five parallel read-only audits (InnerTube/OkHttp path, media3 byte path,
recovery under flaky links, bandwidth contention, cold-start critical path)
produced ~50 findings; the ones below are built. Full detail in HANDOFF §12.

- **The long-buffering "rescue" was RAISING quality — device-reproduced.**
  `ErrorFixerController.lowerVideoQuality()` anchored on
  `getPlayer().getVideoFormat()`, which in Auto answers with the ceiling PRESET;
  `ExoFormatItem.equals` compares `isPreset` (and `"vp9"` vs `"vp09.00.41.08"`),
  so a preset can NEVER equal a concrete rung → `indexOf` was always -1 →
  `idx + 1` = element 0 of a quality-DESCENDING list = the HIGHEST rendition the
  video has. After 20s of accumulated buffering on a starved link the app pinned
  the top rung AND (via TrackSelectionOverride) switched adaptation off for the
  session. Emulator A/B, same video/protocol (shaper 250→60 kbps, `aqz-KE-bpKQ`):
  pre-fix the itag sequence was 242 → 278 → **302** (720p60, and it began a
  567 KB chunk = ~76s at 60 kbps); post-fix it stays at 278. Now: anchor on the
  rung actually PLAYING (`FormatItem.isSelected()`), step to the next strictly
  lower height, and apply it as a ceiling PRESET so ABR keeps adapting
  underneath. Logged as `recovery-lower-quality from= to= mode=ceiling|pin`.
- **ABR was blind to a bandwidth collapse for ~56s.** `maxDurationForQualityDecreaseMs`
  sat at media3's 25s default while the buffer presets were raised to 50-75s, so
  the down-switch threshold was BELOW the load control's min buffer: in steady
  state a down-switch could never even be considered. Now scaled to the midpoint
  of the preset's own band (HIGH → 62.5s). Logged: `buffer=HIGH … abr-up=5s
  abr-down=62s` (device-confirmed).
- **A cause-stripped outage no longer dead-ends the player.** `RetrofitHelper`
  swallows `ConnectException` to null, which surfaces as "fromNullable result is
  null" with no cause — so `isConnectivityError` said "not connectivity" and
  `surfaceCappedError` armed NEITHER the retry timer NOR the network listener:
  a dead player until app restart. It now falls back to asking the device, and
  treats "no validated default network at cap time" as a connectivity failure.
- **The retry budget is no longer the end of the road.** Returning to the
  foreground when the ~8-min budget is spent now retries once and refills it
  (`recovery-foreground-retry`) — the metro-ride case, where the user coming back
  IS the user action, but the only affordance was a one-line notice to tap.
- **Cronet transport errors stopped nuking the buffer.** A branch matching
  "Exception in CronetUrlRequest" ran AHEAD of the SOURCE branch and, for VOD,
  left `restartEngine = true` (destroy + recreate the player = the whole 50-75s
  buffer re-downloaded) while "fixing" it by writing `setPlayerDataSource`, a
  pref the media3 stack never reads. Removed; those errors now take the normal
  remint-and-reload path.
- **In-player video switches cancel the previous open.** `openVideoInt` (related
  tap, queue tap, next) never called `prefetchFormatInfo`, so it neither warmed
  the new fetch nor cancelled the old one — and `VideoInfoService.getVideoInfo`
  is `synchronized` on the singleton for a whole client-ring walk, which
  `disposeActions()` cannot interrupt (RxHelper's scheduler is non-interruptible).
  The video the user picked waited behind the one they abandoned.
- **The /player ring is bounded.** Web-pot clients (6 of ~10 phone-ring entries)
  had NO per-attempt deadline at all — only OkHttp's 8s+8s, which does not cover
  PO-token minting (`PoTokenWebView` awaits a latch with no timeout). Worst case
  ~2 min for one open, under the process-wide monitor. Now: 20s per web-pot
  attempt (sized for a cold BotGuard mint, HANDOFF §8) and a 45s wall-clock
  budget for the whole walk, with attempts clamped to what remains. A budget trip
  returns null rather than publishing a partially-walked "unplayable" verdict —
  that would seat an unestablished verdict in the 30s negative cache and disable
  `switchNextFormat` recovery. Ring ORDER untouched; `VideoInfoVisitOrderTest`
  passes, plus a new test pinning the web-pot budget.
- **Watch-open bandwidth.** `http.keepAlive=false` (a JVM-GLOBAL upstream
  throttling workaround) was making every Glide thumbnail pay a fresh
  DNS+TCP+TLS handshake — ~3 RTT each on a mobile link — while helping nothing,
  since media is Cronet and the API is OkHttp. Removed. The loading still no
  longer fetches `maxresdefault` (108-180 KB) but reuses the exact card-image
  request the feed already cached. Related-list thumbnails (30-80 images,
  1-2.5 MB, in one burst, never recycled because the list is `wrap_content`
  inside a NestedScrollView) are now held until the still lifts, and bound in a
  12-row window. Glide itself is configured for the first time: 2 source threads
  (was CPU-count, i.e. 4 parallel fetches against the stream) and a 15s HTTP
  timeout (was Glide's 2500ms LAN default, which systematically times out at
  mobile RTT and then retries — grey cards plus a request storm).
- **Transport resilience.** `pingInterval(10s)` so a half-open H2 connection is
  detected in ~10s instead of stalling every multiplexed API call for the full
  read timeout; the OkHttp pool is evicted when the default network is REPLACED
  (handover); and a 45s `callTimeout` — the only TOTAL bound, since connect/read
  are per-phase and a link that dribbles a byte every 19s never trips them. Bulk
  transfers (in-app APK download, cast proxy) use a new exempt
  `OkHttpManager.getStreamingClient()`.
- **The player JS is fetched once, not twice**, on a cold `PlayerDataExtractor`
  (~680 KB each, uncached, under the player lock) via a thread-scoped memo.
- **Two UX honesty fixes.** 20s of buffering no longer permanently disables the
  user's subtitles (it wrote two PERSISTED prefs on a wrong attribution — a
  sidecar is a few KB and is never why a link is slow); and `/next` failures no
  longer throw the raw exception over the video (`loadSuggestions error: …`),
  the call site the remove-the-toasts round missed — it re-fired once per
  recovery reload. The feed's offline empty state now says "No connection" with
  a retry button instead of "Nothing to show here yet" (en + es).

**Rig notes (cost real time — read before the next round):** `adb emu network
speed` is unusable, confirmed with the app: ZERO api-http completions in 30s at
4000/2000/1000 kbps — it stalls connections rather than shaping them. `adb emu
network delay` DOES work but only on NEW connections (measured: +500ms on the
first request, unchanged on the 8 that followed over the same H2 connection), so
it models handshake cost, not steady-state RTT. Per-app byte accounting works
via `dumpsys netstats detail --uid` summing `rb=`, but its buckets only refresh
lazily, so sub-phase splits within one run are not reliable — compare whole runs.
`am start -a VIEW -d <url>` opens the REAL YouTube app on a Play-image AVD; pass
`-p io.github.aleixrodriala.arc`.

**Not measured, stated honestly:** the bandwidth-contention fixes are
code-verified and functionally verified (thumbnails all render; no blank cards),
but NOT byte-verified — per-session bytes were 15.2/15.4/16.1 MB before and
14.5/15.0/15.5/15.7 MB after, i.e. inside the noise, because on a fast emulator
link media dominates and ABR variance swamps a ~1-2 MB image delta. Proving them
needs a whole-app shaper (`DebugMediaShaper` only shapes the media leaf, so the
contention this round is about has never been reproducible on the bench) or a
real cellular link. Also unresolved: first-frame on a HEALTHY link measured
947/990/1102 ms before and 1183/1223/1333 ms after, with the network milestones
(open/info/prepare) unchanged — so the delta is in the media/decode phase. The
"after" runs shared the host with gradle builds and unit tests, which is a
plausible confound, but it has NOT been re-measured cleanly. Do that before
shipping.

## Works (emulator-verified; see HANDOFF.md for evidence per claim)
Everything in the original feature set (grid Home + bottom nav, search with
suggestions/voice, channel pages, full watch page with comments/live chat/
SponsorBlock/DeArrow, background playback + notification, PiP, mini-player,
settings, device-code OAuth multi-account) PLUS, from the 2026-07-11/12 rounds:
- **Live playback + DVR on media3**: live routes to the DASH manifest URL
  through a ported `LiveDashManifestParser` (zero-base + growing DVR window);
  timebar/scrub/LIVE-chip/double-tap all verified against a real stream.
- **Seek/network hardening**: fail-fast 403/416 retries, 4 s media read
  timeouts, auto-reload capped at 3 (anti-abuse), persisted+seeded bandwidth
  estimate (no post-restart quality ladder walk), off-main DASH source builds.
- **Open-latency wins**: prefetch-at-tap + preload of the next video's info
  (80 s window) + pre-built next MediaSource → autoplay advance first-frame
  ~350 ms (vs ~2.6 s cold); replay-from-cache ~1.5 s.
- **Request hygiene**: phone /player failover ring 13→8 clients (TV clients
  gated out), single-pass failover, 30 s negative-result cache (gated video
  re-open = 0 network calls), 8 s bound on /player+/next, signed-out
  get_add_to_playlist gated off, log floods killed (JsonPath, OkHttp BODY,
  OkHttp profiler).
- **Video-buffer setting is live** (Low/Med/High/Highest → real LoadControl
  presets, RAM-capped; one-shot pref alignment protects existing installs);
  OOM recovery actually lowers the buffer now.
- Opaque system bars restored on Android 15+ (edge-to-edge opt-out; dies at
  targetSdk 36 — insets work required before bumping).
- **Pot-enforced networks fixed (first real-device round, Pixel 9 on Telefónica
  LTE/5G, 2026-07-12)**: carrier CGNAT IPs make googlevideo demand PO-token
  integrity — pot-less VOD streams died at exactly 60s of served media
  (Source error + reload cascade), live segments 403'd instantly. Fix: BotGuard
  warmup at app start + WEB_EMBED-first again for VOD (warm pot mints ~10ms, so
  the old ~2.7s penalty that justified ANDROID_VR-first is gone) + live walks
  on to a dash-manifest client with `/pot/` on the manifest URL. Verified: VOD
  250s+ soak clean, live DVR window (2h) renders with all segments 200, LTE
  mid-chunk SocketTimeout recovers via retry with no user-visible error.
  Full post-mortem: HANDOFF §8.
- **Second Pixel-9 round (2026-07-12 evening, HANDOFF §9)**: live DVR
  interactions on real 5G — 67-min scrub-back lands exactly (BUFFERING→READY
  3.3 s), LIVE-chip jump to edge (2.3 s), 18+ min soak with ~2 s manifest
  refreshes all 200 `pot=y`, zero errors. Background audio-only: proper
  media-playback FGS + notification, zero
  ForegroundServiceStartNotAllowedException, audio keeps advancing. PiP→search
  routing correct: pinned player task collapses back into the main task when a
  new video opens from search — single task, no duplicate player, no double
  audio; search-result tap→first-frame 1.9 s.
- **Original-audio default fixed (2026-07-12 late, main `cce4344` + MSC
  `b2d09bc6`)**: multi-language videos defaulted to an auto-dub (pt-br on a
  Spanish video, device-verified). Two causes: the generated MPD wrote the
  display string into `lang` and stamped Role=main on every set; and
  findTrack treated an itag hit as exact although audio itags repeat per
  language variant, so a persisted "en-us (original)" 251 pinned the first
  251 in the manifest = the dub. Now: MPD carries clean lang + label +
  Role (only original = main, dubs = dub), and audio id matches require
  language agreement with an original-preferring second tier + prefer-original
  fallback. Verified on the repro video (es-us original selected, dubs
  role=dub), single-language VOD, and live.
- **Background audio-only stops downloading video (2026-07-12 late night)**:
  true background audio (no PiP, no mini-player) now disables the VIDEO track
  type entirely — no download, no decode; re-enabled on foreground return /
  engine restart re-applies while backgrounded. Pixel-9 verified: audio-only
  chunk stream after screen-off, instant video return on wake. PiP and the
  Browse mini-player keep video. HANDOFF §10.
- **Offline recovery UX fixed (2026-07-12 late night)**: connectivity-class
  errors show a friendly title instead of the raw exception; when the reload
  cap trips offline, an edge-triggered connectivity listener fires exactly ONE
  automatic reload when the network validates again; play tap in the dead
  state is a manual retry (cap resets). Emulator-verified end to end, incl.
  the no-hot-loop guard when the cap trips while the network is up. HANDOFF §10.
- **Tunnel-shaped outages now recover on their own (2026-08-01)**: the
  connectivity edge above never arrives when the link dies but Android keeps
  reporting the network connected+VALIDATED (tunnel, lift, metro, Wi-Fi→cell
  handover) — measured elsewhere at ~12 min for data-stall detection, while
  the player gives up in seconds. A timer now retries on an escalating budget
  (5/15/45/120/300 s, then stop) alongside the unchanged edge listener; the
  transport controls (notification / lock screen / headset) reach the retry
  instead of no-op'ing on the IDLE player; raw error toasts are gone, replaced
  by a persistent one-line notice in the video box that survives the retries and
  clears only when playback really resumes. Pixel-9 verified. HANDOFF §11.
- **Third Pixel-9 round — deferred loop items (2026-07-13, real 5G)**. New
  debug-only in-app harness (`DebugMediaShaper`, leaf DataSource wrapper) makes
  bandwidth experiments possible at last: `debug.arc.throttle_kbps` token-bucket
  shaping, `debug.arc.poison_itag` synthetic 403s, `debug.arc.rebuffer_gate_ms`
  gate override; all debug-gated, release path untouched.
  - **Post-rebuffer resume gate 2500→1500 ms: SHIPPED.** 5-pair interleaved
    starve/refill A/B (pinned 1080p vp9 248, 800→2400 kbps): 1500 won all 5
    pairs, median stall 3.21 s → 1.71 s (−1.09 s; matches the theoretical
    refill time of the removed 1000 ms of media, so it generalizes).
  - **Pinned-quality fallback rescue: VERIFIED.** Poisoned pinned itag →
    `rescue pin->auto` fires on the 403 source error → reload on Auto → media3
    track-exclusion skips the failing rendition → playing again in ~7 s (720p).
    One-shot per videoId held; persisted pin untouched (next launch re-pins).
  - **ABR down-switch under constrained bandwidth: VERIFIED** (long-blocked
    item). Under sustained 800 kbps against a 2.77 Mbps track the selector
    lands on 240p (id=242, then 243 as the estimate refines) and playback
    reaches READY ~12–15 s after collapse. Caveat: on this carrier the
    collapse path goes through a genuine googlevideo 403 first (mechanism
    CORRECTED next day — see the 403-mechanism bullet below; the original
    "CGNAT gives the new flow a different exit IP" explanation was refuted
    by measurement), so the observed recovery is 403 → auto-reload → ABR
    re-select → READY, twice reproduced.
    Up-switch after recovery not yet observed on-device (test window ended).
- **Stuck-state fixes (2026-07-13, Pixel-9 fault-injection verified)** — three
  bugs adjacent to the error-reload path:
  - Watch page blanked by error-reloads: reloads re-enter with a bare Video
    (no title/author) and the re-fetch can die on a bad network. Now:
    same-video rebinds never overwrite the title/channel name with empty, and
    bindWatchMetadata (re)populates the title from the metadata document.
  - Same-URL external intent silently swallowed: Android dedupes a launch
    whose intent filterEquals a recents task's ROOT intent into a bare
    task-to-front (no callback; dead task records survive force-stop, so the
    first URL a task ever opened kept matching forever). External filters
    (watch links/shares/vnd.youtube) moved off the main-task Splash onto a new
    ephemeral-task IntentRouterActivity (taskAffinity ":router",
    excludeFromRecents, noHistory) — same-URL re-opens now route (verified:
    tap→open→prepare ~300ms where before there was silence). Launcher opens
    still use the main-task Splash (same-task animation preserved).
  - Error-reload's play() killed by an external AUDIO_FOCUS_LOSS ~200ms after
    re-prepare → recovery completed but sat paused. Now: a focus loss within
    5s of prepare() retries play() ONCE (media3's focus request suppresses the
    retry if the thief still holds focus — no fight loop). Live thief not
    re-observed (1-in-8 reloads); "focus-grace" NetPath line will identify it.
- **Groundhog-loop fixes (2026-07-13 afternoon, Pixel-9 dogfood round)**. Live
  dogfooding caught the app in an INFINITE error-reload loop: a googlevideo
  edge served a deterministic 403 for one audio chunk's byte range (same media
  position, every fresh URL mint, ~20min episode, real 403s with req well
  inside clen) → each ~45s cycle replayed the same 41s of video forever.
  Five compounding defects fixed, each verified on-device (shaper poison +
  one organic 403 episode mid-verification):
  - `containsMedia()` was playback-state based, but a fatal error IDLEs the
    player BEFORE onPlayerError, so VideoStateController's error/seek/release
    position saves ALL silently no-oped → every reload resumed at a stale
    position (the 41s rewind). Now media-item based; reloads resume at the
    death position (verified to the decisecond: died 795.67 → resumed 795.67).
  - Same-position error cap: errors recurring at (±5s) the same media position
    count in a window that onPlay does NOT reset — post-reload READY comes
    from the disk cache and proves nothing (that false-healthy signal is what
    reset the plain consecutive cap every cycle). 4th same-position error →
    dead state (verified: surfaced in 7.7s where the old build looped 6min+).
  - Audio pin rescue (twin of round-3 video pin-rescue): a persisted audio
    language pin maps to ONE itag; when its URL persistently 403s every reload
    re-selected the dead rendition (manifest's other same-language codec never
    tried). Now: 403 SOURCE error with a pinned audio format → session-scoped
    fallback to the default preset via new PlayerData.setTempAudioFormat →
    selector freely picks the alternative codec (verified: pinned aac 140
    poisoned → reload picked opus 251 → played through).
  - Dead-state manual retry re-fetches: retryNow() now calls
    applyNoPlaybackFix() like the automatic 403 path — it used to reload into
    the still-actual format-info cache and replay exactly the URLs that just
    died (observed burning a full error cycle; also right for
    connectivity-restore, where a network reattach may sit behind a new
    public IP that no longer matches the URLs' ip= binding).
  - Ring memory (MediaServiceCore): error-reload /player walks probe the last
    WINNING client second instead of last — applyNoPlaybackFix starts the walk
    after the winner, and with 8/9 clients unplayable midday every reload
    burned 8 playable=n calls (~2.3s) re-finding TV at the ring's end.
  - Diagnostics: NetPath load[E] lines now append req=<pos>+<len> plus clen/
    lmt so a recurrence of the per-range 403 is diagnosable from logcat.
- **403 mechanism MEASURED (2026-07-13 pm) — the CGNAT exit-IP theory is
  REFUTED; do not re-propose transport fixes off it.** Measurements on the
  live network (Mac egresses through the Pixel 9's 5G): exit IP is STABLE
  across fresh connections — 30 samples / 5 min, same public IPv4
  (88.29.x.x) and IPv6 every time (phone is dual-stack: CGNAT'd v4
  internally, global v6). And in the same session's logs: 24 of 34 real
  googlevideo 403s hit REUSED (warm) connections; all 403s carried pot=y;
  206s and 403s interleave within the same second on the same host and
  connection, split purely by which format URL / byte range was asked for.
  Conclusion: the episodes are server-side per-format/per-range URL
  rejections (some googlevideo serving/anti-abuse heuristic), transient
  (healed ~20 min later), not client transport, flow churn, IP binding, or
  pot. Consequently the Cronet QUIC idle-timeout/keep-alive idea was
  evaluated and DROPPED — it would not have prevented any observed 403.
  The mitigation is the recovery stack above (format/client failover with
  exact-position resume, ~8 s per episode). Caveat: cross-network moves
  (WiFi↔5G) and PDN reattach were NOT tested and may still invalidate ip=
  bindings — that class is already handled by the invalidate-on-403 path.
  MECHANISM REFINED 2026-07-13 (later) — see the attested-web-first bullet:
  the "per-format/per-range rejection" is integrity/attestation enforcement of
  NON-ATTESTED client mints. The pm refutation (IP/transport/flow-churn ruled
  out) stands; "not pot" was too strong — the pot is present but NON-VALIDATING
  on non-web clients, which is the actual trigger.
- **403 root cause CONFIRMED + attested-web-first fallback (2026-07-13 pm,
  Pixel-9 + off-device replay).** Deep-dive on "why 403s at all, reliably
  avoid them". Off-device replay through the phone's own 5G egress: a pot-LESS
  ANDROID_VR URL (yt-dlp mint) is bulletproof — 500 range sweeps / 76 MB, 300
  identical-range replays, deep ranges to 99 % of a 1 GB file: ZERO 403s. So
  byte-volume, replay, deep-range, and exit-IP are all excluded as triggers
  (reconfirms the pm bullet). The differentiator is the mint: the APP attaches
  a NON-VALIDATING app-visitor pot (`pot=y`) to non-web client URLs, and on
  carrier/CGNAT (pot-enforcing) networks googlevideo integrity-enforces those
  — a non-attested client (ANDROID_VR/ANDROID_REEL/TV/IOS) serves ~60 s then
  403s forever; ONLY an attested WEB-family flow (WEB_EMBED/WEB/WEB_SAFARI/GEO/
  MWEB, BotGuard-attested `/player` body) mints URLs that survive. This is
  exactly the 2026-07-12 on-device finding recorded in `MobileMainApplication`
  (WEB_EMBED-first) — now confirmed by a MediaServiceCore ring/pot audit and
  the replay tests. Residual 403 storms = videos WEB_EMBED returns playable=n
  for (embed-disabled/geo/age): the ring then falls through the LIST order
  (VR → REEL → TV → …) and wins on a 403-prone non-attested client BEFORE the
  other attested web clients (WEB/WEB_SAFARI/GEO/MWEB sit later in the list).
  Fix: `VideoInfoService.setPreferAttestedWebFallback(true)` (mobile gate,
  VIDEO_INFO_TYPE_LIST untouched) stable-partitions the fallback tail so all
  attested clients are probed before any non-attested one — an embed-disabled
  video that plain WEB can serve now gets a SURVIVING attested URL on the first
  open instead of starting a storm on TV. Non-attested clients stay as the
  final fallback for auth-walled (TV) / SABR-only (VR) videos. Verified
  on-device (debug.arc.fail_clients force-fail hook, since removed): walk order
  is WEB_EMBED→WEB→WEB_SAFARI→GEO→MWEB→ANDROID_VR→…, and ring-memory
  (last-winner probed second) still bounds the reload walk at 2. Happy path
  unchanged (WEB_EMBED wins at attempt 1, 10/10 test opens clean). Caveat:
  helps only the subset of WEB_EMBED-fails a sibling WEB client can also serve;
  videos only TV/VR can serve still take a non-attested win (unchanged). Also
  shipped: `load[E-http]`/`load[E-url]` forensics (the 281-byte 403 body + full
  failing URL) so the next organic episode is curl-replayable.

## Works (added 2026-07-16 — network round: Tiny-Desk timings + 69-agent code audit)
All device-verified on the Pixel 9 (WiFi, signed in, wireless adb
`adb-4A120DLAQ0049N-JPKPvP._adb-tls-connect._tcp`):
- **Signed-in ring memory**: authenticated TV /player is currently SABR-only
  (playable=n, usableAdaptive=0) — measured 4/4 opens; the ring now learns it
  per-process (`player-ring learn tv-sabr-only=y`) and starts later signed-in
  opens at TV_DOWNGRADED (TV keeps one re-probe per process — normally the
  app-start session warmup — and a playable TV response clears the flag).
  Warm open tap→first-frame **1.70s → 1.08s**, one /player per open not two.
- **Cold-open V8 stall gone**: PlayerDataExtractor's restored-cache path now
  warms V8 on a background thread instead of inside the first /player's
  request path (it ran under AppServiceIntCached's player lock). Measured
  player-context→player-http gap 2.3s → 58ms; cold intent-open first-frame
  **4.63s → 2.78s**. The once-per-JS-rotation dummy-solve validation stays
  synchronous on purpose — firstValidExtractor's validate() contract needs it.
- **Storyboard enrichment gated off** (`setSkipStoryboardEnrichment`, mobile
  gate): the touch UI never renders seek previews (loadStoryboard is a stub),
  yet a broken storyboard on the winning client fired a deferred IOS /player
  per non-live open. Re-enable when seek-preview UI ships.
- **Live-chat poll lifecycle**: get_live_chat (~5s cadence) used to run until
  video change/destroy — with the sheet closed, in background audio, and in
  PiP (~700 req/h invisible). Now stops on sheet dismiss / background audio /
  PiP enter, revives on foreground return / PiP exit while the sheet is open.
- **Updater fixed**: pending-update APK re-downloaded IN FULL on every cold
  start >15min (freshness heuristic) — now a getPackageArchiveInfo integrity+
  version check, at most one download per advertised version. Manifest check
  60s → 12h (one-shot migration of the persisted legacy 60s pref); definitive
  answers (404 = nothing published, today's reality) stamp the throttle clock,
  connectivity failures don't. Verified: relaunch fires zero manifest GETs.
- **Dead-host placeholder removed**: Video.getBackgroundUrl no longer returns
  a via.placeholder.com URL (dead host — one failed TLS per watch open);
  callers render solid black on null.
- **ABR up-switch after collapse VERIFIED** (closes the round-3 open item):
  700 kbps shaper on a fresh open → clean 1080p→480p(+4.6s)→240p(+20s)
  down-switch, zero errors (no 403 involvement on WiFi); lift → chunk loads
  back at 1080p in ~2s, selector event +31s (buffered low-res plays out —
  media3's data-frugal default). Minor 480p↔240p flapping only when available
  bandwidth sits exactly at a rendition's bitrate; not worth tuning.
- Tiny Desk concerts (the "hard" test set) play clean on WiFi — zero 403s,
  zero reloads across Mumford/RAYE/Sting/Parcels; their historical difficulty
  is carrier-attestation dynamics (HANDOFF §8), not content.

## Works (added 2026-07-28 — VISIONOS, a token-free /player client)
Came out of an explicit hunt for routes we had not tried (different keys, older
deprecated systems). One real find, plus several dead ends now closed with
evidence so nobody re-opens them.

- **`VISIONOS` (clientName `VISIONOS`, cver `1.02`, InnerTube id `101`) is the
  only client left that requires neither a PO token nor a JS player.** In
  yt-dlp's current `_INNERTUBE_CLIENTS` its entry declares no `GVS_PO_TOKEN_POLICY`,
  no `PLAYER_PO_TOKEN_POLICY` and `REQUIRE_JS_PLAYER: False` — every other client
  in that table declares at least one. It now leads their defaults:
  `_DEFAULT_CLIENTS = ('visionos', 'android_vr', 'web')`.
- **Verified live, not taken from the docs.** Straight `/player` from a Mac on
  2026-07-28: `VISIONOS status=OK adaptive=32 cipher=0 pot_in_url=0` while
  `ANDROID_VR` and `TV_DOWNGRADED` both answered `LOGIN_REQUIRED "Sign in to
  confirm you're not a bot"` **in the same second, same IP**. Byte ranges off
  the returned URLs served `HTTP 206` at init, mid (~14 MB in) and ~95 %.
- **Request shape doesn't matter.** Our template always emits `clientScreen`
  and our UA sniffer would add `browserName`/`browserVersion` (the visionOS UA
  is a Safari string). Probed all three shapes — yt-dlp-exact, `+clientScreen`,
  `+clientScreen +browser` — all `status=OK`, all `media_http=206`. So no
  special-casing was needed in `AppClient`.
- **On-device A/B (Pixel 9, counterbalanced ABBA, one apk, forced client via
  `setprop debug.arc.player_client`).** VISIONOS won attempt 1 on every open:
  `status=OK playable=y`, **more** usable adaptive formats than VR (32 vs 28 on
  `aqz-KE-bpKQ`; 23 vs 22 on `Oa_Wpi-KWrg`), first frame 1.92–2.02 s vs VR's
  1.86 s warm. Both heads are healthy on this network, so this run proves
  parity + format count, NOT the bot-check advantage — that was only observable
  from the challenged Mac IP.
- **No 60 s cliff.** Forced-VISIONOS playback ran **~2 min continuous on
  cellular** (`net=cell:197`), ABR climbing to itag 303 (1080p60 VP9), last
  chunk at `pos=116633` / byte 32.4 MB of 168 MB — **zero 403s**. That is the
  exact deep-range case that kills the full-fat `TV` client.
- Wiring: `CLIENTS.VISIONOS` + `CLIENT_NAME_IDS["VISIONOS"]="101"`, an
  `AppClient` entry, and `PREFERRED_FIRST_CLIENT = AppClient.VISIONOS`.
  `VIDEO_INFO_TYPE_LIST` is untouched (upstream churns it).

**Two hazards this surfaced, both fixed:**
- `AppClient.VISIONOS` is appended at the **END** of the enum on purpose. The
  winning client is persisted **by ordinal** (`getData().setVideoInfoType`), so a
  mid-enum insert would silently re-point every value saved by an older build.
- The fast head is now **off-ring**, and `Helpers.getNextValue` answers with
  element 0 for a value it can't find — so `buildVisitOrder`'s lap never met its
  `type != beginType` stop condition and **span forever**. Fixed by anchoring the
  lap at element 0 and visiting that anchor explicitly. Covered by
  `offRingFastHeadWalksTheWholeRingExactlyOnce` (which hangs rather than fails if
  it regresses — that is the signal). Head preservation was also re-keyed from
  the `PREFERRED_FIRST_CLIENT` constant to `beginType`, otherwise a restored
  previous-session winner would have been demoted behind the Web family, turning
  the first cold start after upgrade into a pot-minting WEB open.

**Follow-up shipped same day — VISIONOS now leads the anonymous partition.**
This is what makes the client pay off for a signed-in user. An authenticated walk
that has fallen through to anonymous (error recovery, or the account head fully
403-quarantined) used to begin at `WEB_EMBED`, which mints a PO token before it
can even ask — on the exact path taken after a media 403, when the open is
already slow. `leadWithTokenFreeClient` injects VISIONOS at the head of those
orders (it is off-ring, so it is otherwise absent from an authenticated order).
- The **healthy** account head is never displaced — asserted by
  `healthyAccountHeadIsNeverDisplacedByTheTokenFreeClient`. That matters, because
  anonymous is genuinely worse when the account works: on device the authed
  client returned **41 usable formats vs VISIONOS's 32** on the same video, and
  `VideoInfo` reads `playbackTracking.videostatsWatchtimeUrl` straight out of the
  `/player` response — so an anonymous response reports an anonymous watch (no
  history, no resume, no recommendation feedback), and age-restricted /
  members-only content stops playing.
- Safe against the anon-challenge detector, which needs the **same reason string**
  from two clients (`BotCheckDetector.isRepeatedLoginRequired`); an age gate
  changes outcome on the embedded client, so this adds a third independent
  identity rather than a false hit.
- **Verification honesty: unit-tested, NOT observed on device.** Four tests pin
  the exact resulting order, including the interactions with quarantine and the
  anon-challenge override. Triggering it live needs a real media 403, which the
  2026-07-27 timeout fix specifically stops us from reaching.

**Also shipped, OFF by default — the player-token lever (`debug.arc.player_pot`).**
yt-dlp's `android`/`android_vr`/`ios` carry `not_required_with_player_token`: a PO
token in the **/player request** removes the requirement from the returned media
URLs. Only `ANDROID_VR` can use ours — a web-minted token is bound to the web
`visitorData`, and VR is the one non-web client we send that identity with;
`ANDROID`/`IOS` carry the app visitor, so the binding would not match.
- **Measured on device (Pixel 9, off/on/off):** the token is **accepted** —
  `playerPot=y`, `status=OK playable=y`, same `formats=28+1`, first frame 3.26 s
  vs 3.88/3.08 s off. No rejection, no format change, no latency penalty (the web
  pot is already warm because VR shares that visitor session).
- **Left off by default deliberately.** It protects against the enforcement
  upstream flagged in 2026.07 — which we have **not** observed here — and it trades
  away the one property that makes that client worth having: needing no token. The
  measurement above says the lever works if we ever need it; flip
  `debug.arc.player_pot=1` on a debug build to A/B it again.

**Known limitation of the head itself — inert while signed in.** A signed-in open begins at
`AUTHENTICATED_HEAD` (`TV_DOWNGRADED`), never at `PREFERRED_FIRST_CLIENT`;
verified on device with the forced client cleared (`player-ring
authenticated-first=TV_DOWNGRADED`, VISIONOS absent). Since VISIONOS is off-ring
it does not appear in an authenticated walk at all. So today it helps
**signed-out** opens, and authenticated recovery only when the cursor is null.
Making it count for signed-in users means leading the *anonymous partition*
(authenticated recovery / auth-head-exhausted) with it instead of `WEB_EMBED` —
strictly cheaper, since it mints no token. Not done; see Open.

**Dead ends, closed with evidence:**
- `get_video_info` — **HTTP 410 Gone**, both `el=detailpage` and the `eurl`
  embed variant. Gone, not merely unreliable.
- **InnerTube API keys are not a lever.** The long-published `AIzaSy…qcW8` key
  and a fabricated garbage key produced byte-identical responses; the key is no
  longer validated. Bot-checking is what gates you.
- **VISIONOS does not rescue the feed.** `/next` and `/search` answered fine;
  `/browse` returned **HTTP 400**. Same SAPISID wall as the rejected home-feed
  work — unchanged.
- yt-dlp deleted `tv_embedded` and `ios_downgraded` as broken in Jan 2026. We
  still carry `TV_EMBED` in `TV_FALLBACK_CLIENTS`; harmless, the phone gate
  skips that tail.

## Works (added 2026-07-27 — playlist queue card in the watch page)
- **"Playing from X" card with an `i / N` position and a collapsible list**,
  above Up next. Collapsed by default; the header toggles it and rotates the
  chevron; the list is a `MaxHeightRecyclerView` capped at 50% of screen height
  so a 200-item playlist can't swallow the page.
- **Which row is the queue is decided by content, not by ordering**
  (`findQueueGroupId`): the row that CONTAINS the playing video is the queue - a
  playlist row always does, an algorithmic related row never does. That leans on
  no row order, no group title we don't own, and no playlist-id plumbing (which
  differs between a section playlist, a /next playlist and the local queue).
- **`PlaylistInfo` is trusted only when the queue is NOT the section group.** A
  feed-opened video has both a section group AND a Mix `PlaylistInfo`; naming the
  feed rows after the Mix is how this first read as a bug ("Playing from <mix>"
  over unrelated feed videos).
- **Position is matched by videoId, never `List.indexOf`.** `Video.equals` is a
  composite hash (playlistId, sectionId, channelGroupId, mediaItem, ...), so the
  playing Video and its own queue row - which arrived in a different group -
  almost never compare equal. `indexOf` returned -1, so the scroll-to-current on
  expand silently no-opped on exactly the long playlists it exists for. Both the
  scroll and the subtitle fallback now go through `indexOfQueueVideo(videoId)`.
- Pixel 9 verified end to end on a signed-in feed open: card reads
  `Playing from Recomendados` / `1 / 5`; collapsed by default (list absent from
  the hierarchy); header tap expands to a 734 px list; exactly one `Now playing`
  badge, on the playing row; tapping queue row 2 advances playback, moves the
  badge to row 2, reverts row 1 to its duration and updates the subtitle to
  `2 / 5`; header tap collapses again. No crashes.

## Works (added 2026-07-30 — the queue card only shows for real playlists)
The card above shipped on EVERY video: `Playing from Recomendados - 2 / 5` over
a home-feed open, which YouTube never shows (Pixel reference shot: a feed video
goes title → actions → comments → related, no panel). Two independent sources
fed it, and both are now closed:
- **The browse row you opened from was being turned into a playlist.**
  `appendSectionPlaylistIfNeeded` pushes `video.getGroup()` as a suggestion row
  whenever `isSectionPlaylistEnabled` - a TV feature (the D-pad row keeps
  playing) that on a phone made the feed the queue. `Video.isSectionPlaylistEnabled`
  now also requires `isRealPlaylistSection()` (this video has a playlist id AND
  its neighbours in the row share it), which a home/subs/search/history row fails.
  One predicate, three effects: no queue card, no feed videos in Up next, and
  `SuggestionsController.getNext()` falls through to `nextMediaItem` - so autoplay
  goes to a related video like YouTube's instead of walking the feed row.
- **YouTube hangs an auto-radio off ordinary videos.** With the section row gone
  the card came back named after the video itself (`1 / 20`): the feed item
  carried `playlistId=RDrLNaachBzBI` = literally `"RD" + its own videoId`, and the
  `/next` radio panel does contain the playing video. `findQueueGroupId` now
  requires `isChosenPlaylist()` - a playlist id that is not an `RD...` radio.
  Trade-off, deliberate: deliberately tapping a Mix card shows no card either (its
  videos still list under Up next), because at that point a Mix and an auto-radio
  are the same object with no signal left to separate them.
- Emulator-verified (NewTube_Verify, signed out): home-feed video → no card, Up
  next = related only; real playlist (`Lofi Girl - Compilations & Mixes`) → card
  reads `Playing from ♪♪ Lofi Girl - Compilations & Mixes` / `3 / 51`, expands
  with one `Now playing` badge. Unit tests green.

## Works (added 2026-07-30 — saving a video to a playlist)
- **`Save` is now a watch-page action**, next to Like/Dislike and Share, where
  YouTube puts it; it fires the same `action_playlist_add` as gear → More → Save
  to playlist, and flips to a check glyph + `Saved` while the video is in a
  playlist (`updateButtonVisual`). It was only reachable two levels deep in the
  gear sheet, which is why it read as missing. The action row is now a
  `HorizontalScrollView` so a fourth pill can't push an action off a narrow screen.
- **The signed-out sheet is no longer empty.** `getPlaylistsInfo` returns an
  EMPTY list while signed out (it short-circuits `/playlist/get_add_to_playlist`,
  which 401s), and `showAddToPlaylistDialog` only special-cased `null` - so the
  sheet opened titled and empty, with no hint that signing in was what was
  missing. Empty now takes the same path as null: `Signed users only`.
  Emulator-verified (toast, no empty sheet).
- **Signed-in verification (emulator, account `trufujocs@gmail.com`).** `Save`
  on a feed video opens the real sheet (`Watch later` + every user playlist,
  checkboxes reflect membership), checking `Watch later` writes through (the
  Playlists page shows `Updated today`, count 29 → 30) and the pill flips to
  `✓ Saved`; reopening the video later restores `Saved` from `/next`. The rest
  of the signed-in gaps are listed under "Open — product/UX".

## Works (added 2026-07-30 — playlist page title, Play all, queue count)
All three found while verifying the Save flow signed in; emulator-verified.
- **The playlist page kept the PREVIOUS destination's title.**
  `MobileChannelUploadsActivity` is `singleTop` and every `startView()` adds
  `FLAG_ACTIVITY_REORDER_TO_FRONT`, so opening a second playlist REUSES the
  instance and `onCreate` (the only place that read the opener's title) never
  ran again; the toolbar then waited for a `VideoGroup` that carried a title,
  which the first delivered group often doesn't. `Watch later` rendered as
  `Recommended` for the whole content load. Fixed by extracting
  `applyOpenerTitle()` and calling it from `onNewIntent` too.
- **`Play all` hid itself (and could start a queue-less video) whenever the
  listed items carried no playlist id.** A playlist reached through a card that
  also has a video resolves via `getMetadataObserve() -> findPlaylistRow()`,
  whose rows are plain suggestion items. `playAll()` now takes the first
  PLAYABLE item and borrows the opener's `playlistId`/`playlistParams` when the
  item has none — on a COPY, since `Video`'s identity hash includes the playlist
  and the grid holds the original. Visibility follows the same rule.
- **The queue card counted a page, not the playlist.** `bindQueueCard` distrusts
  `PlaylistInfo` when the queue IS the section group (that guard exists because
  a Mix's `/next` info can name a different list than the rows on screen), so it
  fell back to `mQueueVideos.size()` — one page. Now `PlaylistInfo` is also
  trusted when its `playlistId` equals the playing video's, i.e. when both
  describe the same list; its `getSize()` is the server's `totalVideos`.
  `Play all` on a 30-video `Watch later` now reads `1 / 30`, not `1 / 15`.

## Works (added 2026-07-30 — playlist page header, Watch later, Save wording)
Pixel-9 verified, signed in, against reference screenshots of the real YouTube
app (ReVanced build on the same phone).
- **The playlist page has a real header**: wide cover, playlist name, owner, a
  "N videos - Private" meta line, a wide `Play all` pill and a round `Shuffle`
  button - the shape YouTube uses. Everything in it comes from the card that
  opened the screen, so it needs no extra request. The cover art doubles as a
  dimmed backdrop; YouTube extracts an accent colour with Palette, which we have
  no dependency for.
- It is the grid's first FULL-SPAN ITEM (`PlaylistHeaderAdapter` +
  `ConcatAdapter` + a span-size lookup), not an AppBarLayout child. Two dead
  ends worth not repeating: AppBarLayout stops counting its scroll range at the
  first child WITHOUT a `scroll` flag, so a pinned toolbar above the header
  froze it in place; and moving that toolbar inside a `CollapsingToolbarLayout`
  then hid it, because CTL paints its contentScrim over every child except a
  real androidx `Toolbar` (ours is a LinearLayout). As a list row it just
  scrolls, and the toolbar title fades in over the last quarter of the scroll -
  which is what YouTube does anyway.
- **`Shuffle`** starts a random item AND keeps the rest of the queue shuffling.
  (SUPERSEDED 2026-09-08: it used to do that by writing the PERSISTED repeat
  mode, with a toast warning about the side effect; it is now scoped to the
  queue via `QueuePlaybackMode` and the toast is gone - see the 2026-09-08
  section at the top.)
- **`Save to Watch later` is on every card menu.** Upstream ships the item OFF;
  flipping `MENU_ITEM_DEFAULT` alone only reaches fresh installs, because
  MainUIData's upgrade path enables a new default only for items MISSING from
  the persisted order list and this one was always in it. A one-shot migration
  in `MobileMainApplication` (`newtube_migrations`, same pattern as the caption
  default) enables it once for existing installs; disabling it afterwards sticks.
- **Save sheet reworded and completed**: title `Save to playlist` (was the TV
  `Add/Remove from playlist`, which also rendered twice - as the sheet title AND
  as a category header, now dropped), `New playlist` as the first row, and the
  signed-out message is `Sign in to save videos to playlists`. English and
  Spanish updated; other upstream locales still carry the old TV wording.

## Works (added 2026-07-31 — the Spanish UI is actually Spanish)
Found while checking the queue card on the Pixel 9: the watch page read
`Comments / Up next / Playing from X / Share / Subscribe` in English on a
Spanish phone. **130 of the 177 `strings_mobile` strings had no `values-es` at
all** - the flavour's translations had only ever been added string-by-string as
features landed. Now translated in full, plus every phone-reachable string the
other two sources were missing:
- `smarttubetv/src/stmobile/res/values-es/strings_mobile.xml`: +132. The 7 left
  are locale-neutral on purpose (brand names, `0:00`, `%1$d / %2$d`, `LIVE`).
- `common/.../values-es/strings.xml`: +36, everything upstream had added since
  its last es sync. Phone-visible ones: the comments sheet, player errors,
  `Play from start` in the card menu, the New playlist dialog.
- `search_hint` ("Search for videos"): its default sits in the app module's
  `src/main/res/values`, which has **no locale folders at all**. It is the only
  string in there the phone still reaches (the other 77 are dead TV leftovers),
  so it is translated in the flavour file with the rest.
- Verified in both languages: Pixel 9 (device locale es) for the watch page,
  player sheets, card menu and search field; emulator (`cmd locale
  set-app-locales`, en then es) for the New playlist dialog.
- **The New playlist field no longer shows a false warning.** Its hint was
  `create_playlist_note` - "NOTE: It won't be seen in the YouTube app" - which
  is only true for the LOCAL shadow playlist `PlaylistServiceWrapper` writes
  when the server call fails. A signed-in create really does reach the account,
  so the note read as a plain false statement, and it also stole the one place
  that should say what to type. Both call sites (`AppDialogUtil`,
  `BaseMenuPresenter`) now use a new `playlist_name_hint` = "Playlist name" /
  "Nombre de la lista".

## Works (added 2026-07-27 — carrier soak of the signed-in TV route)
Ran on the Pixel 9 over **roaming LTE (AndorraTelecom, `drei.at`, metered,
`net=vpn:183` split-tunnel Tailscale — default route is cellular)**, release
build, signed in. This closes the "never explicitly soaked" item above.
- **No 60s pot cliff on signed-in TV_DOWNGRADED.** A TV_DOWNGRADED `auth=y`
  stream (`2Szdo6fRc5c`, first frame +5041 ms) played **~3.5 min continuous**
  with zero 403s, zero reloads, zero recovery events — sampled every ~16 s off
  the player's own clock. Two further TV_DOWNGRADED opens (18:37, 18:48) also
  returned `status=OK playable=y auth=y`. The premise behind the open item —
  authenticated non-attested URLs dying at 60 s on an enforcing carrier — did
  NOT reproduce.
- **Found instead: the full-fat `TV` client 403s at position 0** on this
  network. Chain was `TV_DOWNGRADED attempt=1 parsed=null` → fall to `TV` →
  `playable=y auth=y` → the googlevideo URLs reject immediately:
  `error +11920 InvalidResponseCodeException(http=403) pos=0`. So it is not a
  60 s cliff, it is an instant reject of that client's media URLs.
- **The ring recovers correctly, and that path is now proven on carrier.**
  `quarantine-auth-route client=TV cooldownMs=600000` → `authenticated-recovery
  first=WEB_EMBED` → `recovery-action remint-reload` → WEB_EMBED (`pot=y
  auth=n`) → `first-frame +11943`. Cost is ~12 s to first frame on that one
  open; every later open in the 10-min cooldown goes straight to WEB_EMBED, and
  after it expires TV_DOWNGRADED is used again and works.
- **`parsed=null` root-caused and FIXED (same night).** It was never a parse or
  response-shape failure: `player-context 18:29:33.273` → `parsed=null
  18:29:40.275` is **exactly 7.000 s**, and
  `VideoInfoService.CLIENT_ATTEMPT_TIMEOUT_MS = 7_000`. The head request simply
  overran the per-attempt budget and was cancelled (`getVideoInfoWithTimeout`
  returns null on deadline).
  That budget was written for a *speculative* client — `PREFERRED_FIRST_CLIENT
  = ANDROID_VR`, "often hangs?" — where failing over early costs a second and
  gains a second. But for a signed-in open `beginType = authBegin`, so the head
  is `TV_DOWNGRADED`, and the timeout was never revisited when
  `AUTHENTICATED_HEAD` was introduced in the antibot round. It applied to every
  non-web-pot client, head included.
  The cost is asymmetric: one slow COLD request (DNS + TLS, no warm connection,
  roaming link) → fall to `TV` → media 403 at `pos=0` → ~12 s to first frame on
  that open (the recovery reload is served anonymously by WEB_EMBED) and a
  10-minute quarantine of `TV`.
  **CORRECTION (an earlier draft of this section, the memory note and the commit
  messages for `cbcbd4e6`/`02c001a` all overstated this as a 10-minute
  quarantine of the ENTIRE authenticated route, with every open in the window
  served anonymously — that is wrong).** The quarantine is PER CLIENT
  (`mAuthRouteForbiddenUntilMs.put(failedClient, ...)`), and the constant's own
  javadoc says only when EVERY client in `AUTHENTICATED_HEAD` is quarantined
  does the walk give up on the account. The logs confirm it: the very next opens
  (18:29:53, 18:37, 18:48) all read `authenticated-first=TV_DOWNGRADED
  demoted=[TV]` and returned `auth=y`. Exactly ONE open was served anonymously —
  the immediate recovery reload — not ten minutes' worth.
  Fix: `AUTH_HEAD_ATTEMPT_TIMEOUT_MS = 15_000` applied via
  `attemptTimeoutMsFor(client)` — the head gets a cold-start budget, every other
  fast client keeps the short speculative one, and 15 s still fails over before
  OkHttp's own 20 s read/connect timeout. Regression test
  `VideoInfoVisitOrderTest.authenticatedHeadGetsAColdStartBudget`.
  Observed head latencies for calibration: 0.9 / 2.4 / 2.6 s warm, >7 s cold.
  **Honest limits of the verification.** 5 cold opens after the fix: 5/5
  `TV_DOWNGRADED attempt=1 status=OK auth=y`, no `parsed=null`, no timeout, no
  quarantine, no 403; head latency 3.40 s on the genuinely cold first open then
  0.64–0.78 s. So: no regression, and the head is winning. But **nothing in that
  run exceeded 7 s, so the extra headroom was never exercised** — the fix is a
  targeted hypothesis, not an observed save. And the original request was
  cancelled AT 7.000 s, so its true latency is unknown; 15 s may or may not have
  covered it. What the change rests on is the cost asymmetry, not a measured
  duration.
  **Tradeoff accepted:** if the head ever hangs for real, the user now waits up
  to 15 s instead of 7 s before failover. Judged worth it because the 7 s
  failover was not cheap either (it landed on `TV` → 403 → ~12 s + quarantine),
  and 15 s still beats OkHttp's 20 s.
- Still latent, NOT fixed by the above: **the full-fat `TV` client's media URLs
  really do 403 at `pos=0` on this network.** The timeout fix removes the usual
  way we *reach* `TV`; it does not make `TV` work. If the head fails for a real
  reason, the same 403 → quarantine → WEB_EMBED cascade still runs (correctly).
- Method note: **`dumpsys media_session` is useless for progress here** — the
  session posts no periodic updates, so `position`/`updated` stay frozen
  between transitions and a healthy stream looks identical to a hung one. Read
  `mobile_player_position` off the player instead (center tap reveals controls;
  the tap does not toggle play/pause). Also: a slow horizontal drag near the
  seekbar can land on the watch content and OPEN A DIFFERENT VIDEO rather than
  seek — check the video id in NetPath before reading a "seek" result.

## Works (added 2026-07-27 — watch page on the FIRST open of a session)
- **Eager watch-page fetch now covers the cold open** (`setEagerColdOpenEnabled`,
  SuggestionsController). The eager /next introduced earlier only ran when
  `mMediaItemService != null`, i.e. only once `onInit()` had run — which
  excluded exactly the open that needs it most: the FIRST player open of a
  session (deep link, notification, or just the first feed tap), where
  `openVideo()` calls `onNewVideo` and only THEN starts the playback
  Activity. The fetch there waited for `onVideoLoaded`. Measured on a Pixel 9
  over roaming LTE, 6 counterbalanced pairs, one apk (`debug.arc.eager_cold`):
  fetch start **+40ms vs +2973ms**, watch page ready **paired median −2625ms,
  A faster in 6/6 pairs**; first frame did not regress (paired −831ms, 4/5).
- **Park/replay** is what makes it safe: the metadata can land before there is
  anything to paint into, and every delivery point (`syncCurrentVideo`,
  `appendSuggestions`, `onWatchMetadata`) silently no-ops on a missing player,
  so the document is parked and replayed from `onInit()` — the Activity
  inflates its whole watch UI *before* `setView`/`onViewInitialized`, so that
  is a legal moment to paint. Verified with a temporary 6s delay on the player
  Activity launch: `suggest parked +2793` → `suggest replay +6087` (at onInit,
  54ms before the `open` milestone) → title + related cards painted.
- The liveness test is `isPlayerAlive()` (new, BasePlayerController), NOT
  `getPlayer() != null`: `PlaybackPresenter.getPlayer()` deliberately keeps
  returning a view whose Activity is finishing/destroyed, and painting into
  one is the same silent drop. This also covers "player Activity was backed
  out of, process still warm, open another video" — verified `view=n` there.
- Failure path verified for free (the test link's VPN DNS was dropping the
  first request per open): eager /next fails → `mEagerDelivered` stays false →
  `onVideoLoaded` refetches the classic way → page still paints.
- NetPath gained `suggest fetch/parked/replay/ready` (mobile gate only).

## Works (added 2026-07-18 — feed-load round, tier 2)
All six approved tier-2 items shipped (Pixel 9 WiFi-verified same day, cold
start × 2 + subs + TTL switches + pull-to-refresh + playback soak):
- **Disk-backed FeedCache snapshot**: sections persist to
  `files/feed_snapshots/<sectionId>.snap` on Browse onStop (top 40 videos,
  `Helpers.mergeList` of `Video.toString`, atomic tmp+rename) and restore on
  the process's first in-memory miss — verified cold start paints 40 Home
  cards at ~0.6s, BEFORE the first /browse even leaves ("Restored 40 videos
  from disk"). Display-only until the refetch replaces it (a deserialized
  Video has no live VideoGroup → no page key; the refetch ALWAYS follows
  because the browse TTL map is in-memory and empty on a fresh process).
  Account switch wipes memory + disk via the existing FeedCache.clear()
  listener — whatever is on disk always belongs to the selected account.
- **SessionWarmup deferred to first feed paint**: the throwaway BBB format
  fetch (JS parse + /player) used to fire at +1.2s and race the launch
  /browse chain; now MobileBrowseActivity kicks it after the first FRESH
  content paint (verified ordering in logcat), with a 15s launch fallback
  (offline / deep-link paths) and `init()` restoring the persisted warm flag
  early (the first-run player hint reads it before any feed paints).
- **Brotli for InnerTube JSON (phone-gated)**: `DefaultHeaders.brotliEnabled`
  + request-time Accept-Encoding resolution in RetrofitOkHttpHelper; decode
  side (UnzippingInterceptor) was wired all along. Verified "brotli active:
  first br response /youtubei/v1/visitor_id" + feeds/watch-page/playback all
  parse fine. Upstream's four `br` reverts were TV-box RAM + ByeByeDPI
  concerns — neither applies to phones; TV keeps gzip-only (gate off).
- **www.youtube.com preconnect at app start**: background HEAD to
  /generate_204 through OkHttpManager (whose pool the InnerTube client
  SHARES via newBuilder) — verified 204 in ~190ms before the first API call.
  OkHttpManager.instance()/getClient() made synchronized in SharedModules
  (the preconnect thread racing the first API call could otherwise build two
  clients with separate pools, silently voiding the warmup).
- **Boot double-load guard**: onViewInitialized used to select the boot
  section twice (refreshSections tail + its own tail) → dispose+resubscribe
  of the same in-flight observable. Phone gate in BrowsePresenter
  .onSectionFocused skips a same-section refocus while its load is running —
  verified "Section Inicio load already in flight — skipping refocus
  reload" on both cold starts. onAccountChanged disposes in-flight loads
  first (gate-tied) so the guard can never pin a stale account's fetch.
- **Row-pad continuations gated off** (`setRowPadContinuationsDisabled`):
  the MIN_ROW_GROUP_SIZE=5 eager fills exist for TV shelf rows; the phone
  flattens rows into one grid. NOTE the audit mis-attributed Home's
  continuation storm to this — the real driver is YouTubeContentService
  .emitGroupsPartial's while-loop draining EVERY home section-list
  continuation (~6 × ~35KB, growing ctoken bodies). That drain was
  DELIBERATELY KEPT: it runs after first paint (page 1 emits before
  continuation 2 fires, verified), and it is what fills the phone grid's
  whole scroll depth, which the 5-min TTL then serves for free. Capping it
  would shorten Home's scroll depth for a post-paint-only saving. A proper
  fix would be a lazy scroll-driven section-list continuation (new plumbing:
  BrowsePresenter has no notion of a section-list key) — future item.
- Net cold-start on WiFi: launch → painted cards ~0.6s (disk snapshot) with
  the fresh replace landing ~1.4s later; zero auth requests (tier-1 token
  restore, verified again at age 41 min); subs still 1 request; TTL switches
  still zero; pull-to-refresh bypass intact; 95s playback soak clean.

## Works (added 2026-07-18 — feed-load round, tier 1)
Root cause was measured 2026-07-16 on LTE ROAMING (~800ms RTT amplifies every
serial round trip): cold→Home first cards 5.2s = token refresh 1.9s →
accounts_list 0.8s → /browse 2.1s, ALL serial; Subs first visit 4.6s of blank
skeleton = 5 serial /browse (continueIfNeededTV pre-combining >60 items for
the LIVE-first sort before ANY emission); every Home re-focus refetched
~350KB with no TTL. 55-agent audit: 16 confirmed / 0 rejected findings.
Fixes (Pixel 9 WiFi-verified 2026-07-18):
- **Subs pre-combine gated off on phone** (`BrowseServiceGates
  .setSkipContinuationPreCombine`, set in MobileMainApplication):
  continueIfNeededTV returns page 1 as overrideItems/overrideKey → ONE
  /browse then paint (395ms on WiFi; was 5 serial requests). Page 1 keeps its
  live-first stable sort (MediaGroupImpl sorts the override window); deeper
  pages arrive via normal scroll pagination. TV default unchanged.
- **Access token persisted across process starts** (YouTubeSignInService
  AuthTokenCache pref: header + mint time + owning refresh token): cold start
  within the 60-min token lifetime restores the header from disk — zero
  /o/oauth2/token calls, verified "Restored persisted authorization header".
  Invalidated on account change/sign-out (invalidateCache clears disk too);
  revoked-early tokens are handled by a transport-level one-shot 401
  retry (RetrofitOkHttpHelper.retryOnceIfAuthRejected → refresh → replay).
- **accounts_list off the auth lock**: syncStorage (avatar/name/email sync,
  drawer cosmetics) used to run INSIDE synchronized updateAuthHeaders — the
  first feed's checkAuth blocked on its round trip. Now a named background
  thread ("AccountStorageSync"), once-per-process semantics kept
  (synchronized syncStorage). First browse no longer waits on it.
- **Per-section browse TTL (5 min)** in BrowsePresenter: a re-focused section
  fetched successfully within the TTL skips the refetch entirely — verified
  ZERO requests on Home↔Subs switches ("Section X is fresh — skipping
  refetch"), where each Home re-focus used to cost 1 browse + ~6 serial
  continuations (~350KB). Pull-to-refresh / refresh() force-bypass; History
  is exempt (just-watched must appear); invalidated on account change and on
  channel-sorting/playlists-style changes (backing observable swapped). New
  BrowseView.onSectionContentCurrent default method tells the phone view its
  painted snapshot is current (clears mAwaitingFreshContent so a later
  scroll-end APPEND extends instead of swap-replacing).
- **FeedCache now pins its snapshot's VideoGroups** (strong refs, replaced
  per put / dropped on clear): Video.group is upstream's WeakReference
  memory-leak fix, so after any GC a repainted snapshot answered
  getGroup()==null and scroll-end pagination died silently ("Can't continue
  group") — previously masked because every focus refetched. Verified:
  TTL-skipped subs grid paginates (2 continuation pages appended, list
  extended not replaced). Walk this timeline again if snapshot scope changes
  (CLAUDE.md single-slot-cache rule).
- Net effect measured on WiFi: cold start #2 launch→rows ~2.0s with zero
  auth requests; subs tap→cards 1 request; tab switches free. On the roaming
  profile this removes ~2.7s of the 5.2s cold chain and ~3.7s of the 4.6s
  subs wait per the 2026-07-16 request-level measurements.
Tier 2 shipped 2026-07-18 — see the tier-2 section above. Rejected by
verify (do not re-propose): switching feeds TV→WEB client (upstream: WEB
home breaks signed-in parity).

## Open — network audit backlog (2026-07-16, verified findings not yet built)
From the 69-agent audit (21 confirmed after 2-lens adversarial verify; the
items above are done). Ordered roughly by value.

**From the 2026-07-28 client hunt** (see the VISIONOS section above):
- ~~Lead the anonymous partition with VISIONOS instead of `WEB_EMBED`~~ **DONE
  2026-07-28** (`leadWithTokenFreeClient`) — unit-tested, not device-observed;
  see the VISIONOS section. **Still wants a live soak**: it changes the ordering
  the 2026-07-27 quarantine work depends on, and reaching it needs a real media
  403 that we can no longer trigger on demand.
- ~~Player-token exemption for the android/iOS family~~ **BUILT 2026-07-28, OFF by
  default** behind `debug.arc.player_pot`; only `ANDROID_VR` can use our
  web-bound token. Device-measured as accepted and free, but it guards an
  enforcement we have not observed and costs the client its token-free property.
  Flip it on if `ANDROID_VR` starts failing with a POT signature.
- **HLS as a resilience lane.** GVS POT policy for HLS is `required=False` across
  the android/VR family, and VISIONOS returns an `hlsManifestUrl` (`hls=y` in our
  own device logs). media3 speaks HLS. Worth knowing exists if the HTTPS/DASH lane
  gets potted; not worth building speculatively.
- ~~**Premium exemption.** `WEB`/`MWEB` carry `not_required_for_premium=True` —
  a Premium account drops the web partition's pot requirement entirely.~~ **DEAD
  2026-07-28: the signed-in account is not Premium** (confirmed by Aleix), so the
  exemption never fires and the web partition keeps minting tokens. Nothing to
  build. Would only ever have helped Premium users, i.e. not the default install
  — do not re-propose as a general lever.
- **Possible wrong constant:** `CLIENT_NAME_IDS` maps `TVHTML5_SIMPLY` to `"74"`,
  upstream says `75`. Low stakes (the phone gate skips TV_SIMPLY) but if it's
  wrong it is silently wrong.
- ~~Browse section switches refetch /browse every time~~ DONE 2026-07-18
  (per-section 5-min TTL, see the feed-load round above).
- ~~SessionWarmup fires a throwaway Big Buck Bunny /player + googlevideo
  preconnect every launch~~ DEFERRED to first feed paint 2026-07-18 (tier 2)
  so it never races the launch /browse chain; a FULL skip (needs an
  nsig-extractor freshness probe) remains open.
- ~~FailFastLoadErrorPolicy: treat Cronet net::ERR_NAME_NOT_RESOLVED /
  ERR_INTERNET_DISCONNECTED as fatal~~ ALREADY DONE (shipped in `4315f15`, this
  line was stale). `FailFastLoadErrorPolicy.isFatalTransportError` matches
  UnknownHostException, NoRouteToHostException and both Cronet `net::ERR_`
  strings, and `getRetryDelayMsFor` returns `C.TIME_UNSET` for them so the very
  first failure surfaces to the app-level reload. Covered by
  `FailFastLoadErrorPolicyTest.dnsAndDisconnectedErrorsAreFatalAtMediaLayer`.
- Fixed 1000ms reload delay on the 403-remint path (VideoLoaderController:461;
  shorten via a call-site overload, NOT the shared reloadVideo default).
- ~~ABR seed persists across network types~~ ALREADY DONE (this line was stale
  too): Media3SourceFactory keys the persisted EWMA per Android network type
  (`bw_estimate_bps_net_*`, `SEEDED_NETWORK_TYPES`) and feeds them through the
  per-networkType `setInitialBitrateEstimate` overload, with a one-time
  conservative migration off the old global key.
- No metered cap on buffer-ahead (75s of 1080p prefetch on abandoned videos).
- ~~Brotli for InnerTube JSON~~ DONE 2026-07-18 (tier 2, phone-gated;
  WiFi-verified — still worth a one-off sanity check on cellular/roaming).
- 10s connect timeout for non-open-path API calls (currently 20s).
- CronetManager.getEngine catches only UnsatisfiedLinkError → broaden to
  Throwable, keep null-fallback.
- Account avatars fetched with ALL caching disabled (GlideIconFetcher:45).
- UnlocalizedTitleProcessor unbounded flatMap (add maxConcurrency).
- CLOSE/PAUSE queue auto-advance misses the next-video prefetch (NOT
  REVERSE_LIST — it advances backwards, would warm the wrong video).
- DeArrow: per-card uncached GETs (batch = k-anonymity hashPrefix endpoint —
  bucket API, needs client-side filtering).
Rejected by verification (do NOT re-propose without new evidence): IPv4-first
DNS change, Glide→OkHttp loader swap, related-thumb downsizing, live manifest
cadence backoff (refresh is emsg-driven), proactive WiFi↔cell URL invalidate,
gating the /player fingerprint logging.

## Open — needs a real device (Pixel 9)
Round 2 (2026-07-12 evening) closed most of this list (see Works): live DVR
scrub-back + LIVE-chip + soak, background-audio FGS, PiP→search.
Round 3 (2026-07-13) closed the rest via the in-app debug shaper (see Works):
ABR down-switch, resume gate A/B, pin-rescue. The 2026-07-16 round closed the
ABR up-switch item (see Works). Radio-based constraining stays
OFF THE TABLE (HANDOFF §9 GSM-flip incident) — the shaper replaces it. Still
open:
- WEB_EMBED /player RTT varies 0.3–2.1s on LTE (cold TTFF 3.8s worst case vs
  ANDROID_VR's 3.4s — acceptable since ANDROID_VR streams die at 60s on
  enforcing networks, but worth optimizing; TV+serviceIntegrityDimensions is
  the candidate). Note 2026-07-16: signed-in flows now ride TV_DOWNGRADED
  (ring memory) — re-measure on carrier before optimizing.
- ~~Signed-in TV_DOWNGRADED streams on a pot-ENFORCING carrier network~~
  SOAKED 2026-07-27 (see Works below) — no 60s cliff. Closed.

## Open — product/UX
- ~~Playlist queue UI in player ("Playing from: X · i/N", collapsible)~~ DONE
  2026-07-27, Pixel 9 verified (see Works below).
- **Playlist parity gaps left after the 2026-07-30 passes** (measured against
  the YouTube app, most of the list is now CLOSED - see the Works sections):
  - The playlist PAGE count can still disagree with the card: the page said
    `69 episodes` where the card said `3 / 51` for the same playlist. The card
    side is now the server total, so what is left is which number the page
    listing reports.
  - No colour wash behind the header. YouTube extracts the cover's accent colour
    with Palette; we dim the cover art itself as a backdrop instead (no Palette
    dependency). Close, not identical.
  - The playlist page has no `+` / edit / share circular buttons next to
    `Play all` (YouTube shows them on playlists you own).
  - A deliberately-opened Mix shows no queue card (see the RD trade-off above).
  - ~~`Shuffle` turns the player's repeat mode to shuffle, which is a PERSISTED
    setting~~ DONE 2026-09-08: scoped to the queue via `QueuePlaybackMode`,
    Pixel 9 verified (see Works above). What is still open next to it: the
    per-queue randomisation itself only arms above `MIN_SHUFFLE_SIZE = 30`.
  - Only English and Spanish are current. Spanish is now COMPLETE for the phone
    UI (see the 2026-07-31 section); the other upstream locales still read the
    old TV wording for the reworded playlist items, and have no translation at
    all for the 177 `strings_mobile` entries.
- "Not interested"/"Don't recommend channel" feedback tokens (server moved
  them; MediaServiceCore dig needed).
- Channel rows in search suggestions; channel page header/sort polish.
- Age-gated videos: silent ~6 s stall then auto-skip — needs an error dialog.
- In-player "Video buffer" row (knob currently applies at next player open).
- UI/UX pass DONE 2026-09-08 (see the section at the top): the Material-pink
  accent leak, bottom sheets stopping 63px short of the display edge, and the
  dead TV knobs in the UI settings screen. Two follow-ups it recorded rather
  than fixed: `Listas de reproducción` and `Mis vídeos` share one icon, and
  the settings Misc category still needs the same knob-by-knob audit the
  top-level categories got.
- UI sweep DONE: PiP enter-animation flash fixed (gear→PiP pre-strips the
  window to video-only BEFORE enterPictureInPictureMode, so the shrink never
  captures the squeezed watch page; refused-entry path restores the layout;
  rotation now re-pushes PiP params so the auto-enter sourceRectHint stays
  fresh). Quality + captions + speed are all native sheets.
- Captions rework DONE (post-1.5.0): CC tap toggles with YouTube-style
  snackbar + filled/outlined icon state; native captions sheet (long-press CC
  or gear→Subtitles) with flat track list + "Caption style & size" footer;
  default render = white-on-semi scrim, regular weight, fractional sizing
  (one-shot migration off the old yellow TV default in MobileMainApplication).
- Carry-overs: occasional first-frame black in mini player. Two minor gaps
  left by the audio-only fix (HANDOFF §10): mini-player-then-home keeps video
  enabled (needs a Browse-host hook), and screen-on-at-keyguard streams video
  behind the lockscreen.

## Open — tech debt
- **16 KB page-size compliance**: Android 17 flags the debug build's native
  libs as unaligned (`libcronet.105.0.5195.68.so` LOAD segment, plus libj2v8,
  libconscrypt_jni, libglide-webp). Runs fine on 4 KB-page devices today, but
  16 KB-only devices are coming; needs updated .so dependencies or repack.
- Unstripped native libs (needs NDK 21); `newtube.json` update manifest for
  the in-app updater.
- 77 of the 78 strings in `smarttubetv/src/main/res/values/strings.xml` are TV
  leftovers nothing references any more (only `search_hint` survives). Dead
  weight, and they make the translation delta look bigger than it is.
- Parked (documented in HANDOFF): googlevideo range-query leaf wrapper, SABR,
  media3 DefaultPreloadManager.

## Pending decision
`NewTube_1.3.0_universal.apk` is built and unpublished — GitHub Releases +
download page (see `docs/gtm/`) are the ready channels; publishing is Aleix's
call.
