# 403 playground

This playground separates four failure classes that previously got mixed together:

1. `/player` client selection (`WEB_EMBED`, sibling Web clients, `ANDROID_VR`, TV/iOS).
2. Token binding (original URL, token removed, deliberately invalid token).
3. Addressing (`Range` header versus a correctly formed query `range`).
4. Network identity (Wi-Fi/cellular and the signed URL's `ip=` binding).

It never prints or stores a complete signed URL. Treat debug `load[E-url]` logcat lines as
short-lived credentials anyway; clear logcat after an investigation.

## Fresh control matrix

`gvs-matrix.sh` asks the installed yt-dlp for one client response, then probes initialization and
deep content ranges with the same mobile User-Agent as NewTube:

```sh
tools/403-playground/gvs-matrix.sh
PLAYER_CLIENT=android_vr FORMAT_IDS=251,248 tools/403-playground/gvs-matrix.sh VIDEO_URL
```

The default Milo J control is useful because the captured failing trace hit audio itag 251 at
request `3087086+165897`, and video itag 248 at request `45945715+1383778`, while initialization
ranges on the same fresh URLs returned 206.

## Replay the app's last 403

On a debug build, `MobilePlaybackActivity` logs the full URL only for an HTTP response error. If
the development host shares the phone's public egress (for example through the phone hotspot), run:

```sh
tools/403-playground/probe-captured.sh
```

It replays the exact failed range as: original URL, `pot` removed, and correctly translated query
`range` with no HTTP `Range` header. If every case is 403, first verify the host and phone really
share an exit IP; a URL minted for another `ip=` is expected to fail.

## Device soak

```sh
SOAK_SECONDS=300 tools/403-playground/device-soak.sh
PLAYER_CLIENT=ANDROID_VR SOAK_SECONDS=120 tools/403-playground/device-soak.sh
```

To exercise the production recovery route without waiting for YouTube to reject a URL, inject one
debug-only 403 episode. Do not combine this with `PLAYER_CLIENT`, because that deliberately disables
the fallback ring:

```sh
POISON_ONCE_ITAG=any SOAK_SECONDS=60 tools/403-playground/device-soak.sh
```

The expected trace is Android VR info, synthetic 403s until one terminal player error, a
`one-shot disarmed` line, an automatic reload whose player ring begins in the Web family, and a new
first frame with no 403 in the recovery episode. A numeric itag is also accepted when testing
Media3's own representation exclusion rather than the app-level route.

To A/B the persisted watch-page identity without clearing app data, add
`FRESH_APP_INFO=1`. The debug build then fetches current app info and visitor data
for that process while retaining the rest of the device state:

```sh
PLAYER_CLIENT=ANDROID_VR FRESH_APP_INFO=1 SOAK_SECONDS=120 \
  tools/403-playground/device-soak.sh
```

The summary intentionally omits full URLs. A healthy initial open has no `player-ring` line (the
first client won), reaches `first-frame`, and produces no 403/416 or app-level `error` line.
`PLAYER_CLIENT` uses a debug-build-only system-property hook to disable the fallback ring; the
script clears the property when it exits. Cronet's summary should show `pot=n` for forced
`ANDROID_VR`, proving a Web/BotGuard token was not cross-attached.

## Interpretation

- `original=403`, `no-pot=206`: token binding/platform mismatch is the differentiator.
- Header range 403 but query range 206: revisit the parked media3 range-query adapter, ensuring
  `DataSpec.position=0` and suppressing the HTTP `Range` header to avoid double-offset corruption.
- Both range forms 403 only on cellular: compare client/token mint, not QUIC connection reuse.
- Init 206 but a deep range 403: the signature and host are valid; focus on GVS enforcement,
  token binding, range policy, or a per-format serving decision.
- Connection reset/timeout without an HTTP status: connectivity/transport health, not a 403.

The production mitigation keeps Web-family clients ahead of non-Web clients across the *entire*
recovery order, treats the recovery cursor as one-shot, leaves Android/TV/iOS URLs tokenless unless
their own platform can attest them, and immediately remints after deterministic 403/416 responses.

## Pixel 9 result (2026-07-13)

The initial disconnected-phone trace was a connectivity failure and was discarded. On shared
Wi-Fi, exact app URL replays returned 403 with the original URL, with `pot` removed, and with the
range translated into the query; yt-dlp's tokenless Android VR URL returned 206 for the same deep
ranges. This ruled out Cronet, QUIC reuse, range syntax, host egress, and cross-platform PO-token
attachment.

The differentiator was `/player` visitor identity. NewTube supplied visitor data obtained from
`/tv` under a Fire TV user agent. Using the already-minted Web-session visitor consistently in the
Android VR JSON and `X-Goog-Visitor-Id` changed the forced-client result from a 403/cap in about
three seconds to two clean 90-second deep-position soaks with first frame. Android Reel also passed
its control soak. iOS still reproduced the deep-range 403 and remains a late fallback rather than
borrowing a Web platform token.

## NewTube versus yt-dlp Android VR request

Compared against yt-dlp `d9813a3` (2026-07-12). Current yt-dlp defaults to `android_vr` followed by
`web_safari` for anonymous extraction, marks Android VR as not requiring the JS player, and uses the
same client version, Quest identity, SDK, user agent, and Android 12L context as NewTube.

| Request property | NewTube during failure | yt-dlp | Result of isolation |
|---|---|---|---|
| `X-Goog-Visitor-Id` and JSON `visitorData` | Visitor extracted from `/tv` with Fire TV identity | Visitor inherited from the Web page/session | Decisive: the old source failed; the Web-session source passed |
| PO token on Android VR GVS URL | Web token had previously been cross-attached | None | Removing it did not fix the old URL; keep VR tokenless |
| Innertube API key | Web key injected | No key by default | Keyless NewTube request still failed before visitor fix |
| Origin/referrer | TV referrer, no explicit Origin | `Origin: https://www.youtube.com` | Adding Origin still failed before visitor fix |
| Anonymous cookies | No matching Web cookie jar | Matching Web visitor cookies | TV cookies did not help; no matching Web cookie jar was needed after the visitor fix |
| Android context | Body said `12`, user agent said `12L` | Both say `12L` | Corrected for consistency; correction alone did not fix 403 |
| Player JSON extras | CPN, safety user, lact, inline-no-ad, device capabilities | Minimal playback context | Removing extras still failed; restoring them after visitor fix remained healthy |
| GVS Range/transport | Cronet HTTP Range | curl/urllib range | Exact URL replay and query-range variants stayed 403; not causal |

Three cold-process samples at the saved failing deep position:

| Forced client | Info median | First-frame samples | First-frame median |
|---|---:|---:|---:|
| `ANDROID_VR` | 2.16 s | 3.08 / 3.63 / 4.86 s | 3.63 s |
| `WEB_EMBED` | 2.54 s | 3.41 / 4.62 / 5.99 s | 4.62 s |

Android VR was about 0.98 s faster at the median in this small cold-process sample. It is a good
primary candidate for ordinary anonymous VOD, but not a universal client: made-for-kids content is
unavailable through it and enforcement can change. The robust policy is Android VR as a fast first
attempt with Web-family fallback and an immediate circuit-break to Web after a deterministic 403,
not Android VR alone.

That hybrid policy is now the mobile default. A Pixel 9 fault-injection run rejected every media
open in the initial Android VR source with synthetic 403s until Media3 emitted a terminal source
error. The app then logged `circuit-break suspect=ANDROID_VR`, began its recovery at `WEB_EMBED`,
and rendered a new first frame 2.52 s after the recovery open. No recovery-source request received
a 403. The one-shot injector remains debug-only and the script resets all fault properties on exit.
