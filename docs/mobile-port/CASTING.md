# Casting to the TV — design (decided 2026-07-20)

**Status (2026-07-21): ALL THREE STEPS SHIPPED — Route B, Route A and the mdx
shim, each verified end-to-end on a Pixel 9 + a Philips Cast-built-in TV.**

- **Route B** (Lounge): pair via TV code, saved-screen reconnect, transport,
  position overlay, disconnect. Disconnect semantics: an explicit Disconnect
  sends `stopVideo` to the TV before teardown (phone resumes locally — without
  the stop both screens play at once); "phone can leave" covers walking
  away/killing the app, where no stop is sent and the TV keeps playing.
- **Route A** (Direct cast): mDNS discovery, hand-rolled CASTV2 (no protobuf
  dep, TLS :8009), Default Media Receiver, phone-side proxy serving the
  rewritten MPD (avc1/mp4a only, ≤1080p, VOD only). **Hard-won:** the DMR is an
  https gstatic page fetching adaptive media via XHR — every proxy response
  (manifest, segments, errors) MUST carry `Access-Control-Allow-Origin: *` +
  expose headers, and OPTIONS preflights MUST allow the `Range` header, or the
  LOAD dies as `LOAD_FAILED` with zero visible cause (requests DO reach the
  proxy; the receiver's browser discards the responses). Same recipe as
  Grayjay's cast handlers. Plain http is fine (media XHR from the receiver is
  not mixed-content-blocked); progressive-vs-adaptive is NOT the issue.
  A Cast *audio* device (soundbar) rejects the video DASH LOAD — expected.
- **mdx shim**: the "— YouTube app" row on a Cast device reads the Lounge
  screenId over `urn:x-cast:com.google.youtube.mdx` (launching/reusing appId
  233637DE, never stopping it) and hands off to a normal Lounge session; the
  screen persists as a saved row. This is what makes Cast-built-in devices
  (which don't answer DIAL) appear organically for Route B.
- **One-tap picker + auto-fallback** (second UX round, 2026-07-20; session-load fix
  2026-07-21): tapping a
  device row connects immediately with Direct cast and, if it fails before
  playback is proven (unreachable receiver, live stream, no avc1, receiver
  LOAD rejection), auto-switches to the TV's YouTube app
  (`CastSessionManager.connectWithFallback`; the mdx shim always launches the
  YouTube receiver before Lounge binds—even when a screenId was saved, because
  Direct cast displaced the receiver app). The recommended session keeps that load-level fallback after
  its first VOD succeeds, so VOD → live/incompatible video no longer strands
  the session. The two-option chooser lives behind the row's "⋮"; explicit
  chooser picks never auto-switch. Verified live: a live-stream load refused →
  YouTube receiver launch → Lounge fallback.
- **Honest playback controls** (2026-07-21): the active-player overlay opens a
  route-specific "TV playback options" sheet. Direct cast exposes a real
  phone-side adaptive quality ceiling (Auto/1080p/720p/etc.); subtitles clearly
  offer a switch to the TV app instead of pretending Direct supports them. The
  TV-app route sends subtitle selections over Lounge, while quality is explicitly
  delegated to the TV player's own settings. This avoids presenting the TV app
  as another way to control a quality setting Direct already controls.
- **Volume slider** (`CastVolumeOverlay`): volume keys pop a draggable
  top-center slider pill (accent tint, auto-hide 2s) instead of the old toast;
  drags are throttled (200 ms) because Lounge setVolume is an HTTP POST each.
- **Connecting indicator**: `CastSessionManager.isConnecting()` +
  `Listener.onCastConnectingChanged` (deduped, covers the fallback's mdx
  window too); the Browse cast icon plays the official-style arc-pulse
  animation (`ic_mobile_cast_connecting` animation-list, arcs fill 1→2→3)
  from tap until connected/failed. A spinner overlaid on the icon was tried
  first and rejected in review ("looks weird").
- **Later** (user-parked): the player top-bar icon count is getting crowded —
  study how the official app arranges its player/top-bar actions and redesign
  then; no design work now.

Sender lives in the MediaServiceCore fork
(`youtubeapi/lounge/sender/` + `YouTubeCastSenderService`, 20 unit tests on
framing/encoding/events); app side in `stmobile .../casting/` (DIAL discovery,
picker sheet, TV-code pairing, foreground session service, player overlay).
Wire-protocol reference spec (extracted from ytcast/casttube/plaincast sources):
`docs/mobile-port/lounge-protocol.md`.

Decision (Aleix): build **both** casting routes, surfaced through **one cast picker**
where every target row honestly states its tradeoffs (ads / phone-free / reliability).
The routes cover different receiver hardware and different user priorities; neither
is a stopgap for the other.

## The two routes

### Route A — "Direct cast" (Cast v2 + phone-side proxy, the Grayjay model)
- **What the user gets:** ad-free VOD and a phone-side adaptive quality ladder.
- **Does not support:** live streams or subtitles. Choosing subtitles offers an
  explained switch to the TV-app route.
- **Cost:** the phone must stay on the LAN with the app alive; all media bytes relay
  phone → TV (no transcode, network relay only).
- **Reaches:** Google Cast receivers only — Chromecast dongles, Google TV / Android TV,
  Nest hubs. (Samsung/LG TVs and consoles have **no** Cast receiver.)
- **How:** mDNS discovery (`_googlecast._tcp`, Android `NsdManager`, no Play Services)
  → CASTV2 protocol (protobuf over TLS :8009; connection/heartbeat/receiver/media
  namespaces) → launch the **Default Media Receiver** (never the YouTube receiver)
  → LOAD a manifest URL served by a **local HTTP server on the phone**: DASH manifest
  derived from `YouTubeMPDBuilder` with segment URLs rewritten to local paths; each
  segment fetch relays through the app's existing authenticated googlevideo path.
  The proxy is what neutralizes IP binding, URL expiry, and PoToken/UA identity.
- **v1 scope cuts:** H.264 formats only (universal decode); VOD only (live needs
  proxied HLS — later).
- **Reference implementations:** protocol doc = thibauts/node-castv2; Java senders =
  vitalidze/chromecast-java-api-v2 and DigitalMediaServer/Cast-API (Apache-2.0, OK to
  build from). Grayjay's `StateCasting.kt` is the architecture reference but is
  FUTO Source-First licensed — **read, never copy** (we're MIT).
- **Range requests:** pass `Range` headers through untouched. Do NOT mirror ranges
  into query params — see the `GOOGLEVIDEO_RANGE_QUERY` post-mortem in HANDOFF.

### Route B — "Play on the TV's YouTube app" (Lounge sender)
- **What the user gets:** the TV plays natively; the phone becomes a remote and
  **can disconnect / leave**. Ads on stock YouTube receivers; **ad-free when the
  receiver is SmartTube on an Android TV** (SmartTube implements the Lounge
  receiver — MediaServiceCore `youtubeapi/lounge/` is that code).
- **Reaches:** anything with a YouTube app — Samsung/LG smart TVs, consoles,
  Android TV — plus Chromecast dongles via a Cast-launch shim.
- **How:** Lounge *sender* in the MediaServiceCore fork: `pairing/get_screen`
  (TV-code → screenId + lounge token; new Retrofit endpoint next to the existing
  receiver plumbing), remote-role bind, `setPlaylist` / play / pause / seekTo /
  setVolume. Discovery/launch per device class:
  - Smart TVs / Android TV: **DIAL** (SSDP M-SEARCH → app endpoint → screenId).
  - Chromecast dongles dropped DIAL: Cast v2 `LAUNCH` of the YouTube receiver +
    read screenId from its mdx status channel (needs a slice of Route A's stack →
    this mode lands last).
  - Universal fallback: manual "Link with TV code" entry.
- **Risk (accepted):** the Lounge API is undocumented and Google has churned it
  before. Shared fate with ytcast (alive, v1.4.1 Mar 2026) and upstream SmartTube's
  receiver — fixes propagate through the fork. Mark the route "best-effort" in UI.

SmartTube does not advertise itself through same-Wi-Fi discovery. Open SmartTube on
the TV, then use SmartTube Settings → Remote control and NewTube's "Link with TV
code" row. The resulting Lounge screen is saved like a YouTube pairing; playback is
ad-free because SmartTube is the receiver.

## UX (as shipped, after two on-device review rounds)

One cast icon → one device picker → **one tap connects**. One row per physical
device (mDNS/DIAL/saved-screen merged), subtitle in plain words ("No ads · quality
controls on your phone" / "YouTube or SmartTube on the TV"). A Cast-device tap starts Direct cast and
auto-falls-back to the TV's YouTube app on pre-playback failure; the explicit
two-mode chooser is behind the row's "⋮" and never auto-switches. "Link with
TV code" lives at the bottom of the picker (mDNS on Android is flaky). The
original badge-row + explainer-sheet design and the tap-opens-chooser design
both died in review ("too technical", "too many clicks").

While connected, "TV playback options" changes with the active route:

- **Direct cast:** quality is selected on the phone; subtitles say "Switch to TV
  app" and explain the ad/control tradeoff before doing anything.
- **YouTube/SmartTube receiver:** subtitles are selected on the phone; quality says
  "Use TV remote" because Lounge has no sender-side quality selector.

## Shared architecture

`CastTarget` interface: `connect / load(videoId, positionMs) / play / pause /
seekTo / setVolume / disconnect` + capability flags (`adFree`, `phoneFree`,
`requiresProxy`). Shared infra: discovery aggregator (mDNS + SSDP), picker UI,
foreground session service (wifi lock, notification with transport controls),
player "playing on TV" state.

## Build order

1. **Scaffolding + Route B** (smaller; instantly covers smart TVs, consoles,
   SmartTube-TV ad-free): Lounge sender in MediaServiceCore fork (own commit,
   push fork first per repo rules), DIAL, TV-code pairing, picker + session service.
2. **Route A** (the flagship ad-free path): Cast v2 channel, local proxy server,
   Default Media Receiver control.
3. **Dongle YT-app mode** (Cast launch + mdx → Lounge) — trivial once 1+2 exist.

Neither step throws work away: Route A reuses the picker/session scaffolding, and
step 3 reuses both stacks.
