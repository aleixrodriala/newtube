# Casting to the TV — design (decided 2026-07-20)

**Status (2026-07-20): scaffolding + Route B SHIPPED — verified end-to-end on a
Pixel 9 + TV** (pair via TV code, reconnect from saved screen, load/play/pause/
seek, position overlay, disconnect). Disconnect semantics: an explicit
Disconnect sends `stopVideo` to the TV before teardown (phone resumes locally —
without the stop both screens play at once); "phone can leave" covers walking
away/killing the app, where no stop is sent and the TV keeps playing. Known
gap: Google Cast devices (Chromecast, Cast-built-in TVs) don't answer DIAL —
they only appear via manual TV-code pairing until the mdx shim (step 3) lands.
Sender lives in the MediaServiceCore fork
(`youtubeapi/lounge/sender/` + `YouTubeCastSenderService`, 20 unit tests on
framing/encoding/events); app side in `stmobile .../casting/` (DIAL discovery,
picker sheet, TV-code pairing, foreground session service, player overlay).
Wire-protocol reference spec (extracted from ytcast/casttube/plaincast sources):
`docs/mobile-port/lounge-protocol.md`. Route A (Cast v2 + proxy) and the dongle
mdx shim: not started.

Decision (Aleix): build **both** casting routes, surfaced through **one cast picker**
where every target row honestly states its tradeoffs (ads / phone-free / reliability).
The routes cover different receiver hardware and different user priorities; neither
is a stopgap for the other.

## The two routes

### Route A — "Direct cast" (Cast v2 + phone-side proxy, the Grayjay model)
- **What the user gets:** ad-free, our quality ladder / SponsorBlock keep working.
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

## UX

One cast icon → one device picker. A physical Chromecast offers **both** modes
(default = Direct cast). Rows carry short badges, not paragraphs:

- Direct cast → `Ad-free · plays through your phone`
- YouTube app on TV → `Plays on the TV · has ads · phone can disconnect`
- SmartTube on TV → `Ad-free · plays on the TV`

First use shows a one-time explainer sheet (what the badges mean, why "through your
phone" exists). Same physical device may be discovered via both mDNS and DIAL —
dedupe by IP into one row with a mode choice. Manual IP / TV-code entry lives at the
bottom of the picker (mDNS on Android is flaky).

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
