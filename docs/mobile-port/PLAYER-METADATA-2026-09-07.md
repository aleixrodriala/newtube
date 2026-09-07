# TV metadata compatibility fix — Pixel 9, 2026-09-07

Follow-up: [cache-integrity repair and fresh Pixel evidence](MEDIA-CACHE-INTEGRITY-2026-09-07.md)
supersedes this document's installed APK and closes the cache/output-completeness
evidence gaps below. Initial media 403 remains unresolved.

The existing authenticated TV request now returns accepted playback metadata.
Playback itself is **not fixed**: the subsequent media requests return HTTP 403
before any frame. No credentials, visitors, account selections, client ordering,
proxies or network settings were changed for this fix.

## Concrete defect and narrow patch

`QueryBuilder` sent the extractor's five-digit timestamp unchanged to TV routes.
Upstream introduced TV-specific formatting in
[bcb2e24](https://github.com/yuliskov/MediaServiceCore/commit/bcb2e24e64f796ed92d9c781f4c3babcb1505bb7),
then guarded already-normalized timestamps in
[daf417c](https://github.com/yuliskov/MediaServiceCore/commit/daf417c395dbb18230c1219354e2c28b0f92df32).
The fork missed that change. The Pixel's cached timestamp was five digits.

The patch applies upstream formatting only to automatically sourced five-digit
TV timestamps. Non-TV requests, explicit overrides and already-eight-digit
values retain their existing behavior. The persisted extractor scalar is not
rewritten. Request diagnostics add only `stsDigits`, not field values or bodies.

Eight tests exercise the real request serializer with synthetic session fields;
eight more exercise the actual request-body diagnostic with malformed and valid
values. Session preservation and parser tests from the preceding round remain.

## Actual device result

Target: Pixel 9 `4A120DLAQ0049N`, package `io.github.aleixrodriala.arc`.
Installed debug APK SHA-256, verified against the device:
`96ad46cc0315fbc797213d3b21ba973a4381609a8dbcb02d447e314ae6663c95`.
Install preserved app/account data; no test override was enabled.

One normal-settings availability check opened `Fo89b8zAIE4` at one second:

- First existing `TV_DOWNGRADED` request logged `stsDigits=8`, `auth=y`,
  `srvAuth=y`, `status=OK`, 22 usable adaptive formats and one regular format.
  The previous candidate returned reload-page `UNPLAYABLE` with no formats.
- The response reached URL processing and generated-DASH media preparation.
  Audio/video initialization ranges then returned HTTP 403 with zero bytes.
  Their URLs were unexpired; the active network stayed `cell:340`.
- Existing app recovery made three further attempts and capped at four errors.
  Each downgraded-TV metadata response was still accepted. Ordinary TV returned
  accepted but SABR-only metadata, which this Media3 integration cannot play.
- No first frame was observed. The original 180-second availability phase
  finished after 184.04 seconds with exit 2; this was waiting on failed playback,
  **not** a sustained-playing run. No further playback tests were started.

The original harness mistakenly assigned 2079 ms to legacy still removal during
error cleanup, despite `ttff_ms=null`. That is not picture evidence. Harness
follow-up requires frame evidence in the same playback episode and stops when
non-connectivity recovery is exhausted. It does not abort on intermediate
denials or temporary connectivity recovery.

The earlier inference that different credentials were the only remaining route
was incorrect: correcting a request field changes the authenticated TV verdict.
The 403's cause remains unestablished; accepted metadata is not proof of playable
media. Cold-start, immediate browse-card, warm-card, mid-playback/rapid switches,
PiP handoff and sustained real-video acceptance remain open on this candidate.
Earlier local-asset decoder tests are not substitutes.

## Upstream comparison and limits

With the owner's approval, upstream MediaServiceCore was cloned separately at
`0b01a01730f256b3dde3cc9eb4d8ac90e48f3946` into
`/tmp/newtube-upstream-20260907-fptdTZ/MediaServiceCore`.
Existing local yt-dlp source: `/home/aleix/projects/yt-dlp`, revision
`bbc809a1161d3bfca51fa36f59dda35556ee85a0`.

The bundled solver core and parser libraries match current upstream code.
Exact player URLs survive the normal lookup path. URL-to-MPD-to-Media3 review
found no demonstrated query/header mutation explaining this capture. Upstream's
new legacy/Innertube selector activates on unavailable metadata, not media 403,
so it was not blindly ported. No new protocol engine or session backend was added.

Two evidence gaps remain: existing logs count input transformations rather than
successful outputs, and a non-atomic shared cached-code file can theoretically
be mismatched after interrupted publication. Neither is demonstrated in this
capture; no speculative cache or algorithm changes were made.

## Validation and artifacts

All 138 selected Android tests pass: 28 API, 17 common-controller and 93 app tests.
All 27 offline benchmark tests pass (13 preceding tests plus 14 new regressions).
Reclassifying the saved device log gives zero frames, null visibility and the
terminal recovery cap 9.655 seconds after opening, without any new device request.
Debug and release APK builds pass, including release lint. The first diagnostic
test run failed on host Android initialization; running those tests under
Robolectric fixed the harness, and the complete second run passed.

Private artifacts: `/tmp/newtube-player-metadata-20260907-tW37c3`, including
`build-test-2.log`, `release-build.log`, `benchmark-tests.log` and sanitized
`availability/playback.log`.
The failed availability result is preserved as originally captured; do not use
its legacy visibility value or idle CPU/PSS as performance results.

Source changes remain uncommitted: root on `main`, MediaServiceCore on its
existing branch. No commits, pushes, credential exports or account resets.
