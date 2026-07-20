# YouTube Lounge (cast) protocol — SENDER-side wire specification

Derived from reading the source of:

- **ytcast** (Go, maintained) — `youtube/remote.go`, `youtube/util.go`, `youtube/remote_test.go`, `dial/dial.go`, `dial/ssdp.go`, `ytcast.go` @ github.com/MarcoLucidi01/ytcast, branch `master` (fetched 2026-07-20). **Preferred source where implementations disagree.**
- **casttube** (Python) — `casttube/YouTubeSession.py` @ github.com/ur1katz/casttube.
- **plaincast** (Go, RECEIVER side — useful because it documents exactly what real senders put on the wire) — `apps/youtube/youtube.go`, `apps/youtube/util.go`, `apps/youtube/mp/mp.go` @ github.com/aykevl/plaincast.
- **pychromecast** — `pychromecast/controllers/youtube.py` (mdx screenId flow only).

Anything not observable in these sources is marked **UNKNOWN** explicitly. The API is
unofficial; ytcast's own header says it "CAN BREAK AT ANY TIME".

Conventions used below:

- Base: `https://www.youtube.com/api/lounge`
- All request bodies are `application/x-www-form-urlencoded` unless stated otherwise.
- All Lounge responses are HTTP 200 on success; ytcast treats any non-200 as an error.
- **All numeric payload values in Lounge messages travel as STRINGS** (e.g. `"currentTime":"123.456"`), and times are in **seconds** (fractional allowed). Token expirations are **epoch milliseconds**.

## 0. Standard headers

ytcast sends on every Lounge/pairing request (`doReq` in `remote.go`):

```
Origin: https://www.youtube.com
User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/96.0.4664.45 Safari/537.36
Content-Type: application/x-www-form-urlencoded        # only when a body is present
```

The `Origin` header is sent unconditionally ("doesn't hurt" per ytcast comment). casttube
sends `Origin: https://www.youtube.com/` (trailing slash) and always sets the
form Content-Type; it sets no explicit User-Agent (python-requests default) and still works —
so UA appears non-critical, but sending a browser UA is the safe ytcast-verified choice.
ytcast's HTTP timeout for all Lounge calls: **30 s**.

---

## A. Pairing & tokens

### A.1 Manual TV-code pairing — `get_screen`

The TV shows a 12-digit "TV code" under Settings → Link with TV code (displayed with
spaces, e.g. `123 456 789 012`).

```
GET https://www.youtube.com/api/lounge/pairing/get_screen?pairing_code=<code>
```

- **Method: GET** with a **query** parameter (ytcast). Exact param name: `pairing_code`.
- Code normalization (ytcast `removeSpaces`): strip **all Unicode whitespace** from the
  user-entered code before sending. ytcast does NOT strip dashes — whether the server
  tolerates dashes is **UNKNOWN**; strip both defensively but at minimum strip whitespace.
- No body, no special headers beyond §0.

Response (JSON, single `screen` object — realistic example from ytcast's test suite):

```json
{
  "screen": {
    "accessType": "permanent",
    "screenId": "screen-id-foo-bar-baz",
    "dialAdditionalDataSupportLevel": "unknown",
    "loungeTokenRefreshIntervalMs": 1123200000,
    "loungeToken": "lounge-token-foo-bar-baz",
    "clientName": "tvhtml5",
    "name": "YouTube on TV",
    "expiration": "1645614559007",
    "deviceId": "device-id-foo-bar-baz"
  }
}
```

Fields the sender must persist: `screenId` (durable identity of the TV app),
`loungeToken`, `expiration` — **NOTE: `expiration` here is a STRING** of epoch
milliseconds (ytcast parses it with `ParseInt`); optionally `deviceId` and `name`
(display name of the screen) for UI. An invalid/expired code yields a non-200 or a
response missing `screenId`/`loungeToken` (ytcast errors on either).

### A.2 Lounge token minting / refresh — `get_lounge_token_batch`

Used both for the first token when you already have a `screenId` (DIAL flow) and to
refresh an expired token.

```
POST https://www.youtube.com/api/lounge/pairing/get_lounge_token_batch
Content-Type: application/x-www-form-urlencoded

screen_ids=<screenId>
```

- Exact form field name: `screen_ids`. It is a batch API; ytcast/casttube/plaincast all
  send a single id. Multiple ids comma-separated is implied by the name and the array
  response but **not exercised in any source (UNKNOWN)**.
- No query params.

Response (realistic example from ytcast's test suite):

```json
{
  "screens": [
    {
      "screenId": "screen-id-foo-bar-baz",
      "refreshIntervalInMillis": 1123200000,
      "remoteRefreshIntervalMs": 79200000,
      "refreshIntervalMs": 1123200000,
      "loungeTokenLifespanMs": 1209600000,
      "loungeToken": "lounge-token-foo-bar-baz",
      "remoteRefreshIntervalInMillis": 79200000,
      "expiration": 1637512182177
    }
  ]
}
```

- **`expiration` here is a NUMBER** (epoch ms) — unlike `get_screen` where it is a string.
  Handle both formats.
- Token lifetime: `loungeTokenLifespanMs` = 1 209 600 000 ms = **14 days**;
  `remoteRefreshIntervalMs` = 79 200 000 ms = 22 h (suggested refresh cadence for
  remotes). ytcast's actual policy: before each cast, if `now > expiration` call
  `get_lounge_token_batch` again; if the refresh fails (e.g. the pairing was removed on
  the TV), fall back to a full re-connect (re-pair / re-fetch screenId).
- Errors: missing `screens` array or empty `loungeToken` ⇒ treat as "screen unknown /
  unpaired"; re-acquire the screenId (DIAL) or re-pair (TV code).

There is also `pairing/generate_screen_id` (GET, returns a bare screenId string) and
`pairing/register_pairing_code` (POST form `access_type=permanent&pairing_code=<code>&screen_id=<id>`)
— those are **receiver-side** endpoints (plaincast), listed only for orientation.

---

## B. Session bind (device=REMOTE_CONTROL)

Everything below hits the single channel endpoint:

```
POST https://www.youtube.com/api/lounge/bc/bind
```

This is Google's "browserchannel" transport. A *channel session* is identified by two
ids returned by the first bind: `SID` (session id, JSON key `"c"`) and `gsessionid`
(JSON key `"S"`). Both are required as query params on every subsequent request of the
session. Sessions die quickly (ytcast comment: "it can expire very often, so we fetch it
at each Play() or Add()").

### B.1 Initial bind — obtaining SID and gsessionid

ytcast (everything in the **query string**, EMPTY body, therefore no Content-Type):

```
POST /api/lounge/bc/bind?CVER=1&RID=1&VER=8&app=youtube-desktop&device=REMOTE_CONTROL&id=remote&loungeIdToken=<loungeToken>&name=<displayName>
```

Exact query params ytcast sends on the initial bind:

| param | value (ytcast) | meaning |
|---|---|---|
| `device` | `REMOTE_CONTROL` | we are a sender (receivers use `LOUNGE_SCREEN`) |
| `id` | `remote` | sender device id. casttube uses `aaaaaaaaaaaaaaaaaaaaaaaaaa`; plaincast a persisted UUIDv4. Any stable opaque string works; a per-install UUID is the sane choice. |
| `name` | user-visible name | shown on the TV ("<name> connected"). URL-encode it. |
| `app` | `youtube-desktop` | client identifier. casttube uses `android-phone-13.14.55`. Either works. |
| `loungeIdToken` | the lounge token | credential for the session |
| `VER` | `8` | browserchannel protocol version, fixed |
| `v` | — | ytcast does not send it on bind; casttube sends `v=2` only on its event-poll request. Optional. |
| `CVER` | `1` | client version, fixed |
| `RID` | `1` | request id (see B.3). ytcast hardcodes `1` for the bind, `2` for the following command; casttube starts an incrementing counter at `0`. |
| `zx` | — | not sent by ytcast/casttube on bind. Optional random string, see B.3. |

casttube variant (works equally): query only `RID=0&VER=8&CVER=1`; the token goes in a
**header** `X-YouTube-LoungeId-Token: <loungeToken>`; and the device identity goes in the
**form body**: `device=REMOTE_CONTROL&id=aaaaaaaaaaaaaaaaaaaaaaaaaa&name=Python&mdx-version=3&pairing_type=cast&app=android-phone-13.14.55`.
So the server accepts these fields in query, header, or body. `mdx-version=3` and
`pairing_type=cast` are only in casttube; ytcast omits them and works fine against real
TVs — treat them as optional (casttube targets Chromecast receivers).

plaincast (as a receiver) additionally sends `count=0` as the form body of its initial
bind. Senders in ytcast/casttube send no body/no count on the initial bind. Both accepted.

### B.2 Initial-bind response: chunked array framing

The response body is NOT plain JSON. It is a sequence of chunks, each chunk being:

```
<decimal byte-length of the JSON that follows>\n
<JSON array>
```

The initial bind returns one such chunk. Realistic example (verbatim from ytcast's test):

```
270
[[0,["c","sid-foo-bar-baz","",8]]
,[1,["S","gsessionid-foo-bar-baz"]]
,[2,["loungeStatus",{}]]
,[3,["playlistModified",{}]]
,[4,["onAutoplayModeChanged",{"autoplayMode":"UNSUPPORTED"}]]
,[5,["onPlaylistModeChanged",{"shuffleEnabled":"false","loopEnabled":"false"}]]
]
```

Structure: outer JSON array of messages; each message is `[<index>, [<eventName>, <args...>]]`.

- `<index>` is a monotonically increasing per-session message counter (this is what `AID`
  acknowledges, see B.4).
- Message `["c", "<SID>", "", 8]` → save element [1] as **SID**. (Third element is an
  empty string, fourth the number 8; ignore them.)
- Message `["S", "<gsessionid>"]` → save element [1] as **gsessionid**.
- Remaining messages are ordinary events (see §D) describing the current receiver state.

Parsing strategy (ytcast): skip bytes until the first `[`, then `json`-decode the rest as
`[][]interface{}` and scan for keys `"c"` and `"S"`. If either is missing ⇒ bind failed.
casttube instead regexes the raw text: SID via `"c","(.*?)",\"` and gsessionid via
`"S","(.*?)"]`. For robustness parse the framing properly (length-prefix loop) like
plaincast does: read a line → `Atoi` → read exactly that many bytes → decode JSON → repeat.

### B.3 `RID`, `zx`, `ofs` semantics

- **`RID`** ("request id"): a per-session increasing counter identifying each *POST
  request* on the channel. casttube starts at 0 (the bind) and increments by 1 on every
  POST it makes. plaincast uses a random starting value and increments. ytcast fakes it
  with constants (1 for bind, 2 for the single command POST it makes per session) — valid
  because ytcast opens a fresh session for every command batch. The special value
  `RID=rpc` marks the long-poll/event request (B.4), which has no request id.
- **`zx`**: a random cache-busting string. plaincast's generator: **12 random lowercase
  letters `a`–`z`**. Optional — ytcast and casttube never send it and work.
- **`ofs`** ("offset"): sent in the **form body** of command POSTs; the index that the
  first `req0_` message of this POST occupies in the session-global count of messages this
  client has sent. Starts at **0** for the first command of a session; next POST's `ofs` =
  previous `ofs` + previous `count`. Only plaincast (receiver) sends it explicitly;
  casttube omits it entirely (see gotcha G.6), ytcast sidesteps it by using a new session
  per command and by moving the counter into the `reqN` prefix instead (its i-th addVideo
  of a session is `req<i>__sc`, still with `count=1`). For a long-lived Java sender: keep a
  session-scoped outgoing counter, send `ofs` and use `req0_…` numbering within each POST
  (plaincast's scheme, matches the official web sender it was reverse-engineered from).

### B.4 Receiving events — the long-poll (RID=rpc, TYPE=xmlhttp)

ytcast never listens for events (fire-and-forget sender). The two shapes in the sources:

**One-shot state snapshot (casttube `get_session_data`)** — POST (empty body) with query:

```
POST /api/lounge/bc/bind?loungeIdToken=<tok>&VER=8&v=2&RID=rpc&SID=<sid>&gsessionid=<gsess>&TYPE=xmlhttp&t=1&AID=5&CI=1
    &device=REMOTE_CONTROL&id=<id>&name=<name>&mdx-version=3&pairing_type=cast&app=android-phone-13.14.55
X-YouTube-LoungeId-Token: <tok>
```

With `CI=1` the server flushes the current backlog of events and **closes immediately** —
casttube then json-parses the body (after stripping newlines and skipping to the first
`[`) to read e.g. `nowPlaying`. Note casttube hardcodes `AID=5` for this snapshot; a real
implementation should pass its true last-seen AID.

**Persistent long-poll stream (plaincast `openChannel(false)`, receiver side but the
transport is identical — swap `device=LOUNGE_SCREEN` for `device=REMOTE_CONTROL`)** — GET:

```
GET /api/lounge/bc/bind?device=REMOTE_CONTROL&id=<id>&name=<name>&loungeIdToken=<tok>&VER=8&RID=rpc&SID=<sid>&CI=0&AID=<lastAid>&gsessionid=<gsess>&TYPE=xmlhttp&zx=<zx>
```

- `TYPE=xmlhttp` selects the streaming transport; `RID=rpc` marks it as the receive
  channel; `CI=0` keeps the connection **open** (server streams chunks as events happen).
- `AID` = highest message index you have processed; the server replays anything newer.
  After the initial bind, before any event, plaincast initializes its counter to −1
  conceptually (first expected index 0) and thereafter sets `aid = <index of last message>`.
- The body is an endless sequence of `<length>\n<JSON array>` chunks (same framing as
  B.2). Each chunk contains one or more `[index, ["event", {...}]]` messages. Duplicate
  delivery happens: if `index <= aid`, drop the message as "old"; if `index > aid+1`
  you missed messages (plaincast just logs and continues).
- **`noop` keepalive**: the server periodically emits `[n, ["noop"]]` to keep the
  connection alive; ignore it (but it still advances AID). Interval not defined in the
  sources (**UNKNOWN**; empirically ~30 s, unverified).
- When the server ends the response body cleanly (EOF), that is normal long-poll
  termination: immediately issue a new GET with the updated `AID`. plaincast reconnect
  policy: EOF → exponential backoff retry (see §E); network timeout → wait 30 s, retry.

### B.5 Rebinding after errors

plaincast's third bind form, used after a `400 Unknown SID`, carries the *old* session so
the server can migrate state:

```
POST /api/lounge/bc/bind?device=...&id=...&name=...&loungeIdToken=<tok>&OSID=<oldSid>&OAID=<lastAid>&VER=8&RID=<next rid>&zx=<zx>
```

`OSID` = old SID, `OAID` = last AID seen on the old session. Response is a fresh B.2
frame with new `c`/`S` values. (For a simple sender, plain re-bind without OSID/OAID —
what casttube does — also works; you just lose event continuity.)

---

## C. Commands (session POSTs)

All commands are POSTs to the bind URL. Query string (ytcast, exact):

```
POST /api/lounge/bc/bind?CVER=1&RID=<rid>&SID=<sid>&VER=8&gsessionid=<gsess>&loungeIdToken=<loungeToken>
```

(casttube sends the same minus `loungeIdToken`, which it puts in the
`X-YouTube-LoungeId-Token` header, and with its incrementing `RID`.)

Body — form-encoded message batch:

```
count=<number of reqN groups>
ofs=<session offset, see B.3>            # plaincast; omitted by ytcast/casttube
req0__sc=<commandName>                   # NOTE: DOUBLE underscore before "sc"
req0_<field>=<value>                     # single underscore for every argument field
req1__sc=...                            # only if count>1
```

The `reqN` index within one POST goes 0..count-1 (plaincast batches several; ytcast and
casttube always send `count=1`).

### C.1 Commands present in the sender sources

**`setPlaylist`** — replace the queue and start playing immediately.
ytcast fields:

```
count=1
req0__sc=setPlaylist
req0_videoId=<video id of the first video>
req0_currentTime=<start position in SECONDS, integer>      # ytcast sends whole seconds
req0_currentIndex=0
req0_videoIds=<comma-separated list of ALL video ids, first included>
```

casttube fields (same command, playlist-id flavor):

```
count=1
req0__sc=setPlaylist
req0_videoId=<video id>
req0_listId=<playlist id or empty>
req0_currentTime=0            # seconds, string
req0_currentIndex=-1
req0_audioOnly=false
```

Receiver confirmation (plaincast) of what the server forwards: `videoIds`
(comma-separated), `currentIndex` (int), `currentTime` (seconds, fractional ok, parsed as
float), `listId`. Divergence note: ytcast uses `currentIndex=0` + explicit `videoIds`;
casttube uses `currentIndex=-1` + `listId`. Both shapes verified working; prefer ytcast's
when you supply explicit ids.

**`addVideo`** — append one video to the queue without interrupting playback.

```
count=1
req<i>__sc=addVideo
req<i>_videoId=<video id>
```

One video per request — there is **no `videoIds` param for addVideo** (ytcast comment).
ytcast inserts a random **2–5 s delay between consecutive addVideo requests** and
increments the `req<i>` prefix across requests within the session, "or the queue may get
messed up and some video may get lost".

**`insertVideo`** — play next (insert after current). casttube: `req0_videoId=<id>`.

**`removeVideo`** — remove from queue. casttube: `req0_videoId=<id>`.

**`clearPlaylist`** — empty the queue. casttube: no argument fields (just
`count=1&req0__sc=clearPlaylist`, plus its empty `req0_videoId=` since it reuses the same
code path — an empty `videoId` field is harmless).

### C.2 Commands confirmed by the receiver source (plaincast parses these from real senders)

Field names and units are exactly what plaincast reads out of incoming sender messages;
send them with the same `req0_` encoding as above:

| command | fields | notes |
|---|---|---|
| `play` | none | resume |
| `pause` | none | |
| `seekTo` | `newTime` — **seconds**, fractional allowed | `req0_newTime=95.5` |
| `stopVideo` | none | stops playback |
| `setVolume` | `volume` (absolute 0–100 int) OR `delta` (signed relative int) | plaincast checks `delta` first |
| `getVolume` | none | receiver answers with `onVolumeChanged` |
| `getNowPlaying` | none | receiver answers with `nowPlaying` |
| `getPlaylist` | none | receiver answers with `nowPlayingPlaylist` |
| `setVideo` | `videoId`, `currentTime` (seconds) | jump to a specific video |
| `updatePlaylist` | `videoIds` (comma-sep), `listId` | replace queue without changing playback; receiver replies `confirmPlaylistUpdate` |
| `getSubtitlesTrack` | none | receiver replies `onSubtitlesTrackChanged` |

### C.3 Commands NOT present in any of the read sources — **UNKNOWN**

`previous`, `next`, `setAutoplayMode`, shuffle/loop setters. These exist in the wider
Lounge ecosystem (the initial bind reports `onAutoplayModeChanged` /
`onPlaylistModeChanged` state, implying settable counterparts) but **none of the four
sources sends them, so their exact `req0_` field names are UNVERIFIED here**. Do not ship
them without capturing real traffic. (Widely reported names elsewhere — unverified against
these sources: `previous` / `next` with no args, `setAutoplayMode` with `autoplayMode`.)

Command response: HTTP 200 with a small browserchannel body (ytcast ignores it entirely).
Non-200 ⇒ see §E.

---

## D. Incoming events on the status channel

Every event arrives as `[index, ["<name>", <payload?>]]` inside the framing of B.2/B.4.
Payload, when present, is a single JSON object whose values are **all strings**
(plaincast warns and drops non-string values). Times in seconds (fractional strings like
`"123.456"`), volume `"0"`–`"100"`.

Events observed across the sources:

- **`c`**, **`S`** — session ids (B.2). Only at session start / rebind.
- **`noop`** — keepalive, no payload. Ignore.
- **`loungeStatus`** — sent at bind; payload `{}` in ytcast's captured trace. The
  populated shape (a `devices` JSON string listing connected devices) is **UNKNOWN from
  these sources** (none parses it).
- **`playlistModified`** — queue changed. Payload `{}` at bind time; populated field
  shape **UNKNOWN from these sources**.
- **`onAutoplayModeChanged`** — `{"autoplayMode":"UNSUPPORTED"}` (other values presumably
  `"ENABLED"`/`"DISABLED"` — unverified).
- **`onPlaylistModeChanged`** — `{"shuffleEnabled":"false","loopEnabled":"false"}`.
- **`nowPlaying`** — full now-playing state. Fields (from plaincast's emission and
  casttube's consumption): `videoId`, `currentTime` (s), `duration` (s),
  `seekableStartTime` (s), `seekableEndTime` (s), `state` (int-as-string, table below),
  `currentIndex` (int-as-string), `listId`. Sent empty (`{}`) when nothing is playing.
  A `cpn` field is **UNKNOWN from these sources** (not read or written by any of them).
- **`onStateChange`** — playback state tick: `currentTime`, `duration`,
  `seekableStartTime`, `seekableEndTime`, `state`. Same units as `nowPlaying`.
- **`nowPlayingPlaylist`** — queue snapshot: `videoIds` (comma-sep), `videoId`,
  `currentTime`, `duration`, `state`, `currentIndex`. Empty `{}` when no playlist.
- **`onVolumeChanged`** — `{"volume":"85","muted":"false"}`.
- **`onSubtitlesTrackChanged`** — `{"videoId":"..."}` plus (when a track is active)
  fields like `languageCode` (plaincast comment; full shape unverified).
- **`confirmPlaylistUpdate`** — `{"updated":"true"}` reply to `updatePlaylist`.
- **`remoteConnected`** / **`remoteDisconnected`** — another sender joined/left:
  `{"name":"...","user":"..."}` (receiver-side observation; a REMOTE_CONTROL channel
  presumably receives them too — unverified).
- **`loungeScreenDisconnected`** — **UNKNOWN from these sources**: none of the four
  handles it. Treat any event name you don't know as ignorable; treat channel HTTP
  failures (§E) as the reliable disconnect signal.

### D.1 `state` integer meanings

From plaincast (`apps/youtube/mp/mp.go`), the values it puts on the wire:

```
0 = stopped
1 = playing
2 = paused
3 = buffering
(4 = seeking — internal only; plaincast maps it to 3/buffering before sending:
 "YouTube only knows buffering, not seeking")
```

**Caveat — UNKNOWN/unverified:** whether real TV receivers use additional values
(e.g. a distinct "ended" or "video cued" state, or −1 "unstarted" as in the YouTube
IFrame API) cannot be determined from these sources. Code defensively: parse as int,
treat 1 as playing, 2 as paused, 3 as buffering, anything else as not-playing.

---

## E. Error handling

What the sources actually do:

- **HTTP 400 on a session POST** — bad/expired `SID` ("`400 Unknown SID`"; plaincast also
  matches the literal HTML body `<TITLE>Unknown SID</TITLE>` on a generic
  `400 Bad Request`). Action: **re-bind** (B.1, optionally with `OSID`/`OAID` per B.5) —
  the lounge token is still fine. casttube/pychromecast: on 400 during a session request,
  call `_bind()` then raise so the caller retries the command.
- **HTTP 404 on a session POST** — dead session (casttube comment: "404 resets the sid,
  session counters"). Same action as 400: re-bind, reset `RID`/`ofs`/req-counters to 0.
- **HTTP 410 Gone on the channel** — session unrecoverable at a deeper level. plaincast:
  clear `sid`, **re-mint the lounge token** (`get_lounge_token_batch`), then full
  re-bind.
- **HTTP 401 / 403** — not handled anywhere in the sources (**UNKNOWN**); sensible
  mapping: token invalid ⇒ re-mint; re-mint fails ⇒ full re-pair.
- **Expired lounge token (client-side clock check)** — ytcast checks `now > expiration`
  before casting; if expired, `RefreshToken()` (A.2). If refresh fails or the DIAL
  `screenId` differs from the stored one, do a full reconnect (new token for new
  screenId, or re-pair for TV-code devices).
- **HTTP 502 on the channel** — transient; retry with backoff (plaincast).
- **Retry/backoff (plaincast)**: retry #n sleeps `n² × 500 ms`, giving up after 25
  retries (~5 min total). Read-timeout on the long-poll: log, sleep 30 s, reconnect.
- **ytcast's overall strategy** (simplest robust sender): no persistent channel at all;
  for each user action → (refresh token if expired) → fresh bind (new SID/gsessionid) →
  one command POST → done. Any non-200 anywhere surfaces as an error to the user.

---

## F. DIAL discovery & launch (smart TVs)

> Chromecast dongles do **not** expose DIAL for this purpose — on Chromecast the screenId
> is obtained over the Cast protocol (§F.5); everything else in this section is for
> DIAL-capable smart TVs.

### F.1 SSDP M-SEARCH

Send this UDP datagram to multicast **`239.255.255.250:1900`** (ytcast `ssdp.go`, CRLF
line endings, note MAN value is **quoted**):

```
M-SEARCH * HTTP/1.1
HOST: 239.255.255.250:1900
MAN: "ssdp:discover"
ST: urn:dial-multiscreen-org:service:dial:1
MX: 3
```

Then read unicast responses on the same UDP socket until a deadline. ytcast timings:
`MX=3`, listen deadline defaults to **MX+1 = 4 s** (clamped to max 2 min); max response
datagram size considered: 4096 bytes. Each response parses as an HTTP response
(`HTTP/1.1 200 OK` + headers, no body). Required headers: **`USN`** (unique service name
— use as the device's stable identity/dedup key), **`LOCATION`** (URL of the UPnP device
description), **`ST`** (must equal the search target; ignore others). Optional header
**`WAKEUP`**: `MAC=aa:bb:cc:dd:ee:ff;Timeout=10` (regex `MAC=(.+);Timeout=(\d+)`, timeout
in seconds) — enables Wake-on-LAN (magic packets to `255.255.255.255:9`, retry+rediscover
loop bounded by clamp(2×Timeout, 10 s, 2 min)).

### F.2 Device description

```
GET <LOCATION>
```

- The DIAL REST base is NOT in the XML — it is the **`Application-URL` response header**
  (trim whitespace; missing header ⇒ not a DIAL device).
- From the XML body read `<root><device><friendlyName>` for display.
- ytcast HTTP timeout for all DIAL calls: **5 s**.

### F.3 YouTube app resource

App name registered in the DIAL registry: **`YouTube`** (constant `DialAppName`).
Resource URL = `Application-URL` joined with `/YouTube`.

**Query state:**

```
GET <Application-URL>/YouTube
Origin: https://www.youtube.com
```

Response: XML like

```xml
<service xmlns="urn:dial-multiscreen-org:schemas:dial">
  <name>YouTube</name>
  <state>running</state>
  <options allowStop="true"/>
  <link rel="run" href="run"/>
  <additionalData>
    <screenId>screen-id-foo-bar-baz</screenId>
  </additionalData>
</service>
```

- `state` values: `running` (starting or running), `stopped`, `hidden` (running,
  not visible), `installable=<URL>`; anything else is invalid (ytcast errors on it).
- `additionalData` inner XML has **no single root element** — ytcast wraps it in a dummy
  root before XML-parsing, then reads `<screenId>` (trim whitespace). While the app is
  still starting, `state` may be `running` with the `screenId` **not yet present**.

**Launch:**

```
POST <Application-URL>/YouTube
Origin: https://www.youtube.com
```

- ytcast sends an **empty body** (thus no Content-Type). If you do send a body (DIAL
  launch parameters), Content-Type is `text/plain; charset=utf-8`; plaincast's receiver
  shows the accepted body is a query-string like `pairingCode=<uuid>&v=<videoId>&t=<seconds>`
  — not needed for the Lounge flow.
- Success = any 2xx (typically 201 with a `Location` header carrying the running-instance
  URL, usable later for DELETE-to-stop when `allowStop="true"`).

**Polling loop (ytcast `launchYouTubeApp`)**: every **3 s**, up to **1 min** total:
GET the app state → if `running` and `screenId` present ⇒ done, use that screenId with
§A.2 to get a lounge token; if `stopped` or `hidden` ⇒ POST launch and keep polling; if
`installable=…` or unknown ⇒ fail. Before all this, if the device doesn't answer at all
(`GET <Application-URL>` fails at transport level — ytcast's `Ping()`, note any HTTP
status incl. 404 counts as "up"), try Wake-on-LAN (F.1) then rediscover.

Also: DIAL `screenId` values can change between app launches — ytcast compares the fresh
screenId with the cached one and reconnects (new lounge token) if it changed.

### F.4 Connecting after discovery

DIAL gives you `screenId` only; then: `get_lounge_token_batch` (A.2) → bind (B.1) →
commands (C). No TV-code needed.

### F.5 Chromecast (mdx) screenId flow — future work, for orientation only

pychromecast obtains the screenId over the Cast (CASTV2) channel, not DIAL: launch the
YouTube receiver app (Cast app id, `APP_YOUTUBE`), then on namespace
**`urn:x-cast:com.google.youtube.mdx`** send `{"type":"getMdxSessionStatus"}` and wait
(10 s timeout) for `{"type":"mdxSessionStatus","data":{"screenId":"..."}}`. From there
the flow is identical: `get_lounge_token_batch` with that `screenId`, bind, commands
(that is exactly what pychromecast does — it delegates to casttube).

---

## G. Gotchas

1. **`req0__sc` has a DOUBLE underscore** before `sc`; argument fields have a single one
   (`req0_videoId`). Getting this wrong fails silently.
2. **`get_screen` returns `expiration` as a JSON string; `get_lounge_token_batch` as a
   JSON number.** Parse both (ytcast has an explicit test for this asymmetry).
3. The channel response is **not JSON**: it is `<length>\n<json>` chunks, and there may be
   leading bytes before the first `[` — skip to `[` (ytcast) or honor the length prefix
   (plaincast). casttube also strips embedded `\n` before parsing.
4. **All event payload values are strings**, including numbers and booleans
   (`"currentTime":"123.456"`, `"muted":"false"`). All media times are **seconds**
   (fractional); token expirations are **epoch ms**.
5. **addVideo**: one video per request, no `videoIds` field; without a ~2–5 s random
   delay between consecutive addVideo posts the receiver's queue gets corrupted /drops
   videos (ytcast comment); and the `req<N>` prefix index must keep incrementing across
   those posts within a session.
6. casttube ships a workaround: "session gets out of sync after about 30 seconds —
   binding again works", so it **re-binds before every queue action**. ytcast likewise
   opens a fresh session per command. Root cause is plausibly its missing `ofs`/wrong
   `AID` bookkeeping; if you maintain `ofs`, `RID` and `AID` correctly (plaincast-style)
   a long-lived session works — but the rebind-per-action strategy is the proven-simple
   fallback.
7. **`ofs` starts at 0** per session and advances by `count` per POST; `RID` is
   per-request; `AID` is per-received-message. Three separate counters — don't conflate.
8. `zx` = 12 random lowercase `a–z` letters; purely cache-busting, optional (ytcast omits
   it entirely).
9. The lounge token is accepted either as query param **`loungeIdToken`** (ytcast) or as
   header **`X-YouTube-LoungeId-Token`** (casttube). Pick one; ytcast's query param is
   the maintained-source choice.
10. In the M-SEARCH request the MAN value **must be quoted**: `MAN: "ssdp:discover"`.
11. TV pairing code: strip all whitespace before sending (`pairing_code` param); ytcast
    does not strip dashes (behavior with dashes UNKNOWN — strip them too defensively).
12. `Content-Type: application/x-www-form-urlencoded` only when a body is present;
    ytcast's initial bind has query-only params and an empty body with no Content-Type,
    and that works.
13. The TV shows the sender's `name` query param on connect — URL-encode it properly
    (plaincast has a TODO noting fields must be query-escaped).
14. Expect duplicate event delivery on reconnect — dedupe by message index vs your AID.
15. DIAL: the REST base comes from the **`Application-URL` HTTP header** of the device
    description response, not from the XML; and `<additionalData>` content needs a dummy
    XML root wrapper before parsing.

## Open UNKNOWNs (not determinable from these sources)

- Exact wire names/fields for `previous`, `next`, `setAutoplayMode`, shuffle/loop set
  commands (C.3).
- Populated payload shapes of `loungeStatus` (`devices` list) and `playlistModified`.
- `loungeScreenDisconnected` event (name/payload unhandled by all four sources).
- `cpn` field on `nowPlaying`.
- Full `state` enum beyond {0 stopped, 1 playing, 2 paused, 3 buffering} — dedicated
  "ended"/"cued"/"unstarted" codes unverified.
- Server semantics of 401/403 on Lounge endpoints; `noop` keepalive interval; whether
  `screen_ids` truly accepts a comma-separated batch; dash tolerance in `pairing_code`.
