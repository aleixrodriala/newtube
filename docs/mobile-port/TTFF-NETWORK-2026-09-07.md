# TTFF, upstream fixes, and Pixel network validation — 2026-09-07

This round removes redundant work from playback metadata conversion and ports
three applicable SmartTube dependency/recovery fixes. Two agents coordinated
exclusive ownership of the same Pixel: first direct-cellular startup testing,
then whole-app network shaping and candidate validation. Settings, credentials,
app data, and unrelated concurrent casting work were preserved.

## Retained changes

- `JsonPathTypeAdapter` passes already-parsed Gson query results to nested
  adapters instead of serializing each format, range, caption, or other nested
  record back to text and parsing it again. The original response text is still
  parsed once. There is no provider, mapped-model, networking, account, or
  playback-route change.
- `QueryBuilder` now removes trailing syntax commas before closing objects.
  Unlike the upstream literal replacement, it preserves commas/braces inside
  quoted values and respects escaped quotes and backslashes.
- `BufferingDetector` counts active buffering intervals once, ignores repeated
  start notifications, uses monotonic uptime, and never schedules a negative
  callback delay. Healthy play/pause time no longer exhausts the stall budget.
- `Helpers.parseInt` returns the caller's default when numeric metadata exceeds
  the signed-int range, rather than throwing during playback conversion.

The [upstream comparison](UPSTREAM-NETWORK-2026-09-07.md) records exact repository
revisions, primary commit links, coverage already present locally, and changes
deliberately not imported. In particular, the upstream bounded Rx pool can run
rejected work on the submitting thread; copying that behavior could put blocking
service work on a UI caller. Legacy TV/SABR changes do not supply this phone
player's missing Media3 SABR source.

## Parser regression evidence

Seven offline tests exercise the actual converter and `VideoInfo` models. A
legacy comparison arm sends each nested object back through text serialization
and parsing, matching the old adapter's recursion. Every mapped field is compared
before lazy getters, with additional checks for inherited format fields,
initialization/index ranges, thumbnails, captions, translated languages, Unicode,
escaped text, primitive lists, `JsonPathObj` arrays, missing/null/empty data,
fallback paths, prefixed responses, and denied responses without media.

The unchanged 24-format fixture requires **one text parse instead of 76**,
eliminating 75 nested serialization/parsing pairs and 7,228 serialized characters.
The JsonPath provider can itself copy nodes while collecting query results;
the optimization reuses those returned nodes, not necessarily nodes with the
original response tree's identity. Tests check exact reuse at the adapter
boundary and that querying does not mutate the root tree.

These are deterministic reductions in redundant parser work, not a claimed
75-fold speedup or an isolated end-to-end TTFF saving.

## Network-test decisions

The [first agent's direct-cellular ABBA](PIXEL-TTFF-GATE-2026-09-07.md) retained
the production 500 ms startup gate: the successful playback episode was a median
906 ms at 500 versus 904.5 ms at 250. All four 45-second arms and two additional
video opens reached playback after one existing initial media 403 and ordinary
app recovery. LTE with an NR-NSA override was reported; the mobile-network
generation preference was not changed.

The second agent confirmed media, API, and artwork traffic sharing one proxy
bandwidth bucket. This proxy exits through the Linux host and disables QUIC;
its shaped results are not direct LTE results. Added latency is a connection
establishment delay, not per-packet RTT. Deferring cold watch-page metadata had
overlapping results, opposite pair directions, and duplicate metadata requests
during recovery, so eager loading remains unchanged. Forward/recovery buffers,
ABR, explicit quality selection, and media-cache contents are also unchanged.

The [network report](UPSTREAM-NETWORK-2026-09-07.md#verification-and-pixel-network-pass)
contains the candidate comparisons, outage check, timing definitions, and final
device restoration. Decoded-frame timestamps are distinct from visible-picture
presentation. Initial-error recovery removes the loading still early, so the
harness does not report that earlier event as a valid visible frame for the
later successful episode.

The final 30-second direct-cellular check on the parser candidate reached a
decoded frame in 3985 ms, used HTTP/3 media responses after proxy removal, and
had no post-READY buffering or terminal denial. Its one initial media 403 still
recovered through the existing app flow. Final readback confirmed the installed
candidate hash, Wi-Fi/mobile data restored to `1/1`, proxy restored to `:0`, all
test properties restored, owned reverse mappings/listeners removed, and the
unlocked phone left on Browse without playback running.

## Reproducibility

The final focused verification passed **175 Android unit/Robolectric tests in
26 suites**, with zero skipped tests, failures, or errors. It includes all
common tests, the integer helper, converter/value-preservation and request-JSON
tests, existing playback-verdict tests, and the app's player/playback-UI suites.
JUnit reports were routed to private directories after another concurrent
casting job replaced the normal app test-output directory. The authoritative
log is `isolated-final-tests-v3.log`, with XML under `isolated-results/` in the
build evidence directory below. Debug and release APK builds also passed,
including release lint; their log is `parser-build.log`.

The focused test selection is:

```sh
./gradlew :common:testDebugUnitTest \
  :sharedutils:testDebugUnitTest --tests '*HelpersParseIntTest' \
  :youtubeapi:testDebugUnitTest \
  --tests '*JsonPathTreeReuseTest' --tests '*VideoInfoParsingTest' \
  --tests '*QueryBuilderJsonTest' --tests '*QueryBuilderTimestampTest' \
  --tests '*AuthRouteSabrOnlyVerdictTest' \
  :smarttubetv:testStmobileDebugUnitTest \
  --tests 'com.newtube.mobile.player.*' --tests 'com.newtube.mobile.ui.playback.*'
```

The immutable three-upstream-fix baseline is
`/tmp/newtube-ttff-upstream-20260907-WL5QR2/upstream-fixes-debug.apk`, SHA-256
`fc20a2170bb2dbdabb41d8cbf0f059a7db8e15b1f669d9349d1a3d6cd6ed979f`.
The parser candidate is `parser-debug.apk` in the same directory, SHA-256
`88c4e8a365b863b21a1f75956dc45d3fa67308376942e15328f21a250e95dbec`.
Both are debug 1.7.0 APKs installed in place without clearing user data.

Unrelated casting edits appeared during the builds. The candidate includes some
earlier casting edits, but not every later workspace edit. Accordingly the saved
APK, not a mutable output path or an assertion of parser-only APK differences,
identifies the tested artifact. No casting changes are claimed by this round.
No commits, pushes, branch switches, or dependency-pointer updates were made;
the dependency changes remain in their existing submodule worktrees.

Private sanitized device evidence:
`/tmp/newtube-pixel-ttff-baseline-20260907-ChOBBw` and
`/tmp/newtube-pixel-network-20260907-os5xFV`.
Build/test logs and immutable APKs:
`/tmp/newtube-ttff-upstream-20260907-WL5QR2`.
