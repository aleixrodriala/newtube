# Startup speed follow-up: bandwidth confidence, real preload, and release profiling

## Startup bandwidth policy

`StartupBandwidthMeter` is enabled by default. It wraps Media3's existing meter instead of
changing the HTTP engine, media requests, startup/rebuffer gates, or the steady-state ABR
algorithm. Explicit fixed-quality selections still bypass adaptive bitrate decisions.

- Undated, invalid, future-dated, or older-than-five-minute bandwidth hints start at 1 Mbit/s.
- A recent measured hint is reused, capped at 4 Mbit/s for initial selection. These are
  bandwidth estimates, not permanent video bitrate limits.
- A completed transfer of at least 32 KiB and 50 ms can provide an early measured rate.
  Media3's normal transfer listener still excludes cache reads and transfers flagged as
  not representing full network speed, and accounts for overlapping audio/video loads.
- After substantial real samples meet the stock 512 KiB or two-second confidence gate,
  the stock estimate takes over. Tiny/error responses do not establish confidence.
- Network-type changes and 30 seconds without a completed sample clear startup confidence.
  This does not claim detection of every same-type Wi-Fi/cellular network handover.
- Only measured, sufficiently supported estimates are persisted, with their timestamp and
  network type, during playback. Saves are throttled to five seconds on the same network;
  process death no longer depends on player teardown to save a useful measurement.

The debug/benchmark-only `debug.arc.startup_abr=off` comparator disables the wrapper policy
before process creation. Release ignores this control. Existing legacy bitrate values are
retained; an old value without a measurement timestamp is not trusted as current capacity.

The [Pixel network report](PIXEL-STARTUP-ABR-2026-09-07.md) identifies the immutable APKs,
whole-app shaper, actual initial video tracks, first-frame/READY distinction, recovery
episodes, byte counters, and limits. Observed READY improvements were 389–394 ms on the
700 kbit/s link and 697 ms on the 1.5 Mbit/s link. This is a small, non-randomized sample,
not a general percentage speedup or a compositor-visible-picture measurement.

There is an explicit quality tradeoff: the first chunk is smaller. On the direct cellular
sanity check, 360p rose to 1080p about nine seconds after READY, with no observed rebuffer.
The radio transitioned from LTE to 5G NSA; no pure-LTE baseline improvement is claimed.

## Actual next-video preload

The [preload implementation and acceptance report](NEXT-MEDIA-PRELOAD-2026-09-07.md) covers
the real Media3 sample-queue handoff, shared-builder requirements, cancellation and ownership
guards. It is currently opt-in, not enabled in production. When off, the existing XML source
stash remains and no extra preload builder or track selector is created at player startup.

The final debug APK passed both offline hardware-decoder instrumentation tests. A bounded 1,048,956-byte
preload reached decoded frame at 134 ms and READY at 149 ms during handoff, then advanced
through at least three seconds and 60 frames. Cancellation closed the speculative loader
and stopped reads; a manual quality pin caused zero speculative reads. This validates local
sample handoff, not network autoplay performance.

The single ordinary near-tail autoplay attempt did not pass live preload acceptance. The
natural next-video prefetch started, received HTTP 403 for speculative media, and stopped
with `reason=error` about 115 ms later, without a ready/hit or speculative retry. Existing
Shuffle mode advanced naturally to the predicted video; no mode/client/route change was
made by the operator. The current video had a separate seven-millisecond BUFFERING/audio
underrun about 51 seconds before speculation began, so this run is neither a zero-rebuffer
soak nor evidence that speculation caused the underrun. Production preloading stays disabled.
The normal automatic next open recovered once using the existing app policy, reached a
decoded frame at 936 ms and READY at 1,026 ms, and continued for about 30 captured seconds
without another post-READY buffering event or final denial. That is a successful normal
autoplay recovery, not a preload speedup; no extra operator request/retry was issued.

## Release-like measurement infrastructure

The [Macrobenchmark module and restoring harness](../../macrobenchmark/README.md) build a
non-debuggable, profileable benchmark target and a separate measuring APK. Its shell-only
fixture uses the real player initializer with bundled media and suppresses speculative
service warmups before process creation. It measures both first decoded frame and READY,
requires continued decoded progress, and reports fully drawn only after both milestones.

The API-34+ requirement prevents older-device compilation-reset uninstall/reinstall behavior.
Accounts and app preferences are not cleared. Explicit profile/compilation runs do alter
ART profiles/code-cache state; the harness does not claim to restore those byte-for-byte.
Production release is not profileable, and R8/minification is unchanged.

Profile generation filters to app namespaces and excludes fixture classes. It deliberately
does not produce a startup-layout profile from a fixture-only entry route. Generated profile
coverage and performance comparisons are verified separately from this infrastructure;
the existing dependency baseline profiles alone did not establish app-specific coverage.

The Pixel's stable collection produced 456 app rules, including 305 methods. These were
reviewed and incorporated at `smarttubetv/src/main/baseline-prof.txt`, the supported
[manual profile source location](https://developer.android.com/topic/performance/baselineprofiles/manually-create-measure).
Coverage includes mobile application initialization, common preferences and the actual player
initializer/cache warmup, but excludes the TV-namespaced base Application and sharedutils.
It does not cover watch-UI methods or live service extraction. A class-only watch activity rule
is not evidence that its playback journey was profiled.

Strict `profgen dumpProfile` round-trips of the profile extracted from each immutable benchmark
and release APK verify all 456 app rules, including `MobileMainApplication.onCreate` and
`Media3PlayerInitializer.createPlayer`, survive compilation. No fixture activity rule appears.
Release contains `baseline.prof` (4,943 bytes) and `.profm` (436 bytes), no bundled media fixture,
and no debug/profileable/fixture manifest entry. Before this addition, the app's profile was
dependency-only. Compilation-profile performance results are reported separately: comparing
the combined embedded profile with no compilation does not isolate the incremental benefit
of these app rules over the old dependency profiles.

The [12-cold-start Pixel ABBA comparison](RELEASE-BENCHMARK-2026-09-08.md) found no defensible
profile TTFF improvement after the reversed block: pooled first-frame medians were 263.26 ms
without compilation versus 263.93 ms with the combined baseline profile; READY was 329.13
versus 327.09 ms. The final uncompiled block was faster than both baseline blocks, indicating
order/cache drift. All iterations advanced normally, with zero app-UID network bytes and
thermal status zero. The profile's installation/coverage and reproducible measuring workflow
are validated; a performance benefit is not. Normal debug APKs do not embed this profile.

## Verification and artifacts

The final broad local pass completed 391 unit/Robolectric tests in 51 suites, with zero failures,
errors or skipped tests: all app and common suites, plus the focused integer, metadata parser,
request JSON/timestamp and existing playback-verdict regressions. This includes 19 startup
bandwidth tests using the real stock transfer meter, adaptive selection and fixed selection.
Debug, debug AndroidTest, benchmark target, release and Macrobenchmark APK builds also passed,
including release/benchmark lint. All 49 host benchmark/parser/lifecycle tests passed; exact package UID
matching prevents installed `.test`/`.benchmark` packages from polluting future app byte totals.
The new harness tests mock every subprocess and cover preflight rejection plus cleanup after
success, failure and timeout without issuing any real device command.

Private build/test evidence is retained in
`/tmp/newtube-startup-preload-20260907-0vb4Ic/`; Pixel evidence is in
`/tmp/newtube-pixel-startup-abr-20260907-hFM2tk/`. The network-measured candidate is
`11626d61c30f2ad616d1b26a6fc7463b7650e1a584cac20074a2d7e40b97482a`.
The subsequent offline-preload debug APK is
`577fcd6e7b0b1231413ed001948608cbde42e055ce77effcbca79dd228f07943`.
The final profile-embedded benchmark is
`2ea56b93a3887c455d4b762a91eba8b357c986b68bbf4009d61d2137d10d5102`;
final debug is `42047861f47cb8e5e66c9e2ecd0d2f8e0733cc583877635f5821223c8dae463d`;
final release is `6998d868e88cbbdb4be6da714ffd4e4663842d74681c0b2b1c175602edaf0735`.
The final binaries also include elapsed-time preload expiration and duplicate-source stash
ownership fixes added after the first offline hardware check. Both hardware tests passed again
on the exact final debug artifact; the earlier 577f check measured 170/183 ms for frame/READY.
These are local correctness-check timings, not a controlled comparison of the two builds.
Final autoplay checks and device restoration are recorded separately.

## Final Pixel handoff

The installed APK was verified as the final debug `42047861…` artifact. The phone was returned
to `MobileBrowseActivity` with no video playing. All 19 captured experiment properties matched
their original values, including unset startup-ABR/preload/benchmark-fixture controls and
`none` for media-cache/startup-gate controls. Original Wi-Fi and mobile data were enabled,
including the active subscription, the proxy was restored to `:0`, no reverse mappings
remained, and the owned shaper was stopped. Playback mode remained Shuffle, speed remained
1.0, and the saved video-quality field's hash was unchanged.

No app data was cleared. Test packages and private diagnostic artifacts were retained for
reproducibility. Normal benchmark compilation/profile effects are documented above; this is
not a claim that ART caches/profiles were restored byte-for-byte. Pixel ownership has been
released, and no further device/build work is pending from these agents.

No transport/client replacement, credential/identity change, commit, push or branch switch
is part of this follow-up. Unrelated concurrent casting changes are retained but are not
claimed as work from this round.
