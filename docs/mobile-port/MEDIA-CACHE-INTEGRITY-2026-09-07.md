# Media cache integrity and remaining playback blocker

The cache defect is fixed and installed. **The media 403 is not fixed.** One
normal Pixel replay still received no media bytes and rendered no first frame.
No successful TTFF, switching or sustained-playback acceptance is claimed.

## Reproduced defect and repair

CacheService published new preference metadata before replacing a shared code
file. Three synthetic fault-injection tests failed on the old implementation:
new keys returned old code, the previous valid key became unreachable, and a
failed same-key update mixed old code with new version/variant metadata.

The new format commits full key, code and nullable metadata as one versioned
envelope. A single monitor serializes load/store/clear; one entry per section
preserves the existing storage and eviction policy. Android's
[AtomicFile](https://developer.android.com/reference/android/util/AtomicFile)
provides write commit/rollback, with application-side synchronization as required.
Malformed/oversized entries are misses and cache IO failures remain nonfatal.
Only the new section entry and its own recovery files are cleared by explicit
cache invalidation; no directory sweep, account reset or legacy-file deletion.

The old split cache cannot prove its key/content association, so it is ignored
without being erased. One normal preprocessing pass populates the new format.
No player algorithm, client identity, account, visitor, token or route policy was
changed. Additional diagnostics report only structural counts of returned
values, never their contents or full media URLs.

## Pixel result and interpretation

Target: Pixel 9 `4A120DLAQ0049N`, `io.github.aleixrodriala.arc`.
Installed debug APK SHA-256, verified against the device:
`5eb8e3dcbbb25c0f975311ed7f6ca5bd65fbaedb22ca13f4e5f374401b07cf41`.
Installation preserved app/account data and inactive test settings.

The new envelope existed before the controlled open, after normal app startup;
the captured processing therefore reports `cached=y`, not a migration miss.
Its decoded code and the untouched legacy code have identical SHA-256:
`798885eacb4f9f22139e9737cba5c3f6f6a9d9aa303b0b21fae056009b81e049`.
Only hashes were displayed; cache bodies were not saved to artifacts.

One availability check opened `Fo89b8zAIE4` at one second:

- Existing authenticated downgraded-TV metadata was `OK`, with 22 usable
  adaptive formats. Both transformation groups returned 23/23 nonempty outputs,
  zero unchanged values and matching list lengths across all four episodes.
- Both initial media ranges still returned 403 with zero bytes. The app reached
  its existing four-error recovery cap about 10.2 seconds after the first open.
- The benchmark exited 2 with `recovery_capped=true`, no first frame and null
  visibility. Total harness elapsed time was 36.30 seconds, including adb/probe
  overhead; this was not a 45-second sustained-playing run. No subsequent
  playback request was initiated by the benchmark.

The cache fault is real but is not the cause demonstrated by this capture.
Missing outputs are also ruled out here. Complete changed outputs do **not**
establish their algorithmic correctness or that the media server accepts them.
The cause of the media rejection is still unestablished.

## Concrete remaining capability gap

The normal authenticated TV response also returns accepted SABR-only metadata.
Media3PlayerController.openSabr has no SABR source; it can only try any regular
low-quality URLs or emit an unsupported-source error. Changing format
classification alone would not implement playback.

SmartTube's implementation is available in this repository's `tv-legacy` tag:
`exoplayer-amzn-2.10.6/library/sabr` (`:exoplayer-library-sabr`), originating in
`yuliskov/SmartTube`. It contains 63 Java files and 28 protobuf schemas: media
source/period, chunk scheduling, stateful UMP/protobuf handling, and MP4/WebM
extractor adapters. MediaServiceCore supplies metadata, not that streaming module.

A VOD-only Media3 port could reuse current decoders, but needs substantial API
adaptation. Confirmed differences include LoadingInfo and ExoTrackSelection
contracts, DRM/event/executor plumbing for ChunkSampleStream, DataSpec.Builder,
new chunk cancellation/release/error APIs, and MatroskaExtractor.read being final.
Stateful POST responses cannot use the existing URI-only media-cache key policy.
No SABR module was added or alternative endpoint tested in this follow-up.
Accepted metadata does not establish that its media endpoint will succeed.

Minimum activation gates for a separately scoped VOD port: capability-aware
metadata validation without overriding denials; correct audio-track identity;
fragmented/truncated UMP and initialization/media sequencing fixtures; actual
MP4/WebM sample-stream integration; POST preservation and cancellation tests;
seek/track-change/EOS handling; and no stale delivery after switch/release.
Keep live/post-live/DVR out of that initial scope. The retained module has no
SABR media fixtures/tests, and the existing plain-MP4 fixture does not cover it.
Do not preserve the legacy Matroska adapter's catch-all conversion of errors
into end-of-input. No session, challenge, identity or token changes belong in
that format-support integration.

## Verification and handoff

- Legacy red run: 3 of 5 cache tests fail at the intended assertions.
- Final cache suite: all 15 pass, including real AtomicFile start-write failure,
  partial-stream failure with rollback, interrupted recovery, malformed data,
  Unicode/null metadata, scoped clear and 16 MiB read/write guards.
- All 161 focused Android tests pass: 51 API, 17 common and 93 app tests.
  All 27 offline benchmark tests pass. Debug and release builds pass.

Private artifacts: `/tmp/newtube-media-cache-20260907-BEFtHR`:
`cache-red.log`, `cache-green-build.log`, `cache-fault-tests-release.log`,
`benchmark-tests.log`, and sanitized `availability/playback.log`/`results.json`.
The first green build had 11 cache tests; the final run adds four real-IO/size
regressions, with no further production change after the installed build.

Root and MediaServiceCore changes remain uncommitted on their existing branches.
No commits, pushes, credentials exported, session rotation or borrowed sessions.
