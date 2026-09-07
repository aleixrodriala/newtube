# Bounded next-video media preload (2026-09-07)

Status: implemented as an opt-in experiment, **not enabled in production**. Debug or benchmark
builds require `debug.arc.next_media_preload=1` before engine creation. Unset/default/release
retains the existing format-info + generated-MPD source stash without speculative media reads,
and does not create a preload builder or second selector on the cold-start path.
No user preferences, sessions, signed URLs, client identities or denial recovery policies change.

## Implementation

The existing next-video prefetch still predicts only one autoplay-next source during the final
80 seconds of current playback. Its callback now checks both the captured current Video and a
load/release generation, so a delayed callback cannot inherit a later open (including A→B→A).

`Media3NextPreloader` accepts that ordinary generated-DASH source. Media3's actual
`DefaultPreloadManager` prepares/selects/loads sample queues through its existing media cache,
transport and fail-fast error policy; there is no raw URL downloader, new API request, or cache
purge. Live and recorded-live content remains XML-only. Explicit foreground video overrides and
background audio-only mode disable speculative media loading.

| Boundary | Behavior |
| --- | --- |
| Target | One next source, first 2 seconds of samples; media segments can round this upward. |
| Memory | Shared load control has a separate 4 MiB PRELOAD allocation target; an in-flight segment can overshoot. |
| Time | 15-second loading deadline; prepared stash expires after 90 seconds of elapsed realtime, including device sleep. |
| Foreground priority | Requires READY, play requested, no error/loading, and 10 seconds of playout buffer or a fully buffered short tail. |
| Cancellation | Foreground loading/buffering/pause, seek, format changes, mismatched new open, timeout or release stop an active preload; player events are backed by a 500 ms check. |
| Failure | Discard prepared speculative source, no app-level recovery or second attempt for that target in the current generation. |
| Handoff | Only a completed, exact matching wrapper is adopted; incomplete speculative sources are never handed back as raw stashes. |

The initializer builds the foreground player and preloader from the same builder, sharing the
playback looper, renderer capabilities, bandwidth meter and load-control allocator. Mutable
track selectors remain separate. Preload parameters copy current constraints/preferences but
clear old video/audio group overrides and disable text loading. Foreground manual selections
are never changed. Different resume positions/tracks follow Media3's normal re-selection path.

A completed matching preload survives both `loadVideo`'s early reset and `openMediaSource`'s
reset. Manager ownership is removed only after foreground tracks confirm adoption; releasing
it between `setMediaSource` and preparation would discard the warmed samples. Cancellation
explicitly releases periods/loaders and removes the prepared raw source from the XML stash.

Each attempt captures its own callback token. Stale A completion/error cannot complete or cancel
B, even across same-video reopens. Generated manifests also receive unique **internal MediaItem
IDs**, preventing the otherwise shared `generated.mpd` metadata identity from aliasing manager
entries. These IDs are not network request identities and do not change media URLs.

The controller's synchronized `SourceStash` also retains the first exact source for a video:
a duplicate queued XML build cannot replace an instance already owned by the sample preloader.
Worker-start checks skip redundant builds, while publication stays ordered generation-lock
before stash-lock. An actually cancelled source is discarded, allowing an unprepared XML-only
fallback without another speculative attempt.

This follows the upstream [shared-builder preload guidance](https://developer.android.com/media/media3/exoplayer/preloading-media/preloadmanager/concepts)
and was checked against the exact Media3 1.10.1
[PreloadMediaSource implementation](https://github.com/androidx/media/blob/1.10.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/preload/PreloadMediaSource.java).

## Verification

The final coordinated local test run passed all 41 new tests: 27 coordinator/eligibility/ownership
regressions, two actual-builder flag-off/lazy-initialization tests, eight direct production-stash
regressions, and four tests with real Media3 `PreloadMediaSource`, `PreloadMediaPeriod`,
`SampleQueue` and allocator objects. The latter verify actual queued bytes stop at a two-second
target, same-position sample/period reuse, incompatible-resume discard, and zero backing
allocations after cancellation. Synthetic sample payloads are not decoder-valid media and are
not presented as hardware playback evidence.

The `SourceStash` cases cover exact same-video identity retention, different-video replacement,
one-shot consumption, matching/mismatched reset, old-source cancellation, cancelled-source fallback,
and obsolete/duplicate generation publication.

Separate `Media3NextPreloadPlaybackTest` instrumentation uses the existing generated 54-second
H264/AAC APK asset and the actual shared-builder player/preloader. It asserts bounded nonzero
asset reads, stopped reads at completion, wrapper handoff to a SurfaceView decoder, continued
decoded progress, cancellation/loader close during seek, and no speculative bytes with a manual
quality pin. Its source factory contains only `AssetDataSource`; logical HTTPS identifiers cannot
open network sockets.

Both hardware tests passed again on the Pixel in **8.513 seconds** using the final immutable
debug APK with Wi-Fi and mobile data off. This build includes the elapsed-realtime age-clock
and duplicate-stash ownership corrections. Artifact SHA-256 values:

- Debug APK: `42047861f47cb8e5e66c9e2ecd0d2f8e0733cc583877635f5821223c8dae463d`.
- AndroidTest APK: `4c101e06b961870b5ecd853b1831e72d00171cc524b641bda406502af61a5e0f`.

The completed preload read **1,048,956 bytes** (less than the full asset) and stopped reading;
matching wrapper handoff rendered a frame in **134 ms**, reached READY in **149 ms**, and
passed the further 3-second/60-frame progress check. The seek cancellation case stopped after
**248 speculative bytes**, closed its loader, and performed no further reads. Foreground
playback reached position **10,323 ms** with **109 decoded frames** and **zero errors/rebuffers**.
Explicit quality pinning preserved the override and read **zero speculative bytes**.

Private final evidence:
`/tmp/newtube-pixel-startup-abr-20260907-hFM2tk/preload-final-instrumentation.txt`.
The earlier `577f` hardware run is superseded by this final-build check.

An ordinary generated-DASH autoplay check was also attempted on final debug `420478…`, with
the existing Shuffle mode unchanged. During a 111.51-second capture, the natural next-video
preload started around 57 seconds, received speculative media HTTP 403 responses, and stopped
with `reason=error` **115 ms** later. There was **no `ready` or `hit`**, and no operator retry.
Natural autoplay advanced around 79 seconds; ordinary next-video playback recovered once
through its existing path, rendered a frame in **936 ms**, reached READY in **1,026 ms**, and
continued for roughly 30 captured seconds without further next-video buffering or a final denial.
An outgoing-video 7 ms BUFFERING/audio-underrun event occurred roughly 51 seconds before the
preload began and is not attributable to speculative loading. Quality settings, mode and speed
were unchanged.

This attempt demonstrates speculative-error cancellation and continued ordinary autoplay, but
**does not pass real-network preload readiness/handoff acceptance** or establish an autoplay
TTFF improvement. The production gate remains off. All 19 recorded controls were restored,
the phone was released with Browse foreground, and no app data was cleared. Private evidence:
`/tmp/newtube-pixel-startup-abr-20260907-hFM2tk/final-autoplay-acceptance`.

Do not promote the default based only on offline unit and hardware tests. The following
ordinary Pixel checks remain required:

- Successful generated-DASH acceptance remains required: same immutable debug/benchmark APK,
  experiment on, actual autoplay-next VOD; observe
  `next-preload start`, `ready`, then matching `hit`, decoded first frame and sustained progress.
  This must exercise generated DASH and its normal cache; the hardware fixture is progressive.
- Constrained whole-app link: foreground loading/low buffer must prevent or stop speculation,
  with no extra foreground rebuffer or lost playhead. A completed preload may retain samples
  because it is no longer reading media.
- Seek, manual video switch, pause/background-audio, and release while preload is reading:
  confirm cancellation and continued foreground health. Change manual quality and verify the
  selected foreground track is unchanged and no new speculative bytes are requested.
- Record normal source-error behavior without injecting new authentication failures; speculative
  denial must stop that attempt and must not invoke an app-level remint/retry. Stop online testing
  after a final denial. Never alter credentials, client identity, token or request-policy settings.
- Compare a few matched autoplay transitions with the flag off/on, reporting full TTFF and
  rebuffer/progress rather than only prepared-source time. Preserve existing caches/session data
  and report cache/metadata-order confounding; restore the original experiment/network settings.

Fixture timing is not YouTube TTFF and does not measure the API/metadata part of startup.
