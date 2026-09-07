# Pixel non-debuggable playback/profile verification

The reproducible harness is documented in [macrobenchmark/README.md](../../macrobenchmark/README.md). These measurements use the bundled generated local H264/AAC fixture and production player initializer, not the real watch UI or service extraction. Do not present them as live-video TTFF improvements. The separate [network report](PIXEL-STARTUP-ABR-2026-09-07.md) covers live startup ABR observations.

## Target and data safety

Pixel 9, Android 17/API 37, USB, existing app/account retained by matching-signature `adb install -r`. The `stmobileBenchmark` target is non-debuggable, shell-profileable, release-derived and unminified. Production `release` remains non-profileable; neither build enables R8. The fixture activity exists only in the benchmark target and is protected by the shell's `DUMP` permission. Every fixture media read resolves unconditionally to a bundled asset; `debug.arc.benchmark_fixture=1` suppresses speculative service warmups before process creation and is restored after each block.

The API 34 minimum prevents AndroidX's older unrooted-device uninstall/reinstall fallback during compilation reset. No accounts, app data, media caches or route/client settings were cleared or copied. AndroidX deliberately changes ART compilation/profile state, writes a ProfileInstaller skip marker and attempts cold shader/kernel-page-cache resets. These benchmark code-cache effects are not a byte-for-byte restoration of previous ART state. Proxy/radio preferences and the fixture control are retained/restored separately.

## Smoke and generation provenance

Generation/smoke target SHA-256: `1b8556000efbec0d1b275cf1ec14483dd62ea4d6ff4efac65e6e2446fa45742a`.
Measuring APK SHA-256: `5e2778cf772204da3f979ca6e8945b29b478ba0757e4f531c6d0f0cb9a0483f9`.

The one-iteration `keep` smoke passed, including both READY and continuing decoded progress. It measured 367.8ms activity→first-frame, 444.7ms activity→READY, 379.3ms initial display and 636.5ms full display. The asynchronous traces begin immediately after the activity records its creation time. `reportFullyDrawn()` requires both a decoded frame and READY; the harness then requires 800ms position and 20 output buffers without unexpected buffering. These single-iteration numbers validate observability, not a performance distribution.

`BaselineProfileRule` then passed in 35.512s and accepted a stable filtered rule set. The captured fixture log has two successful decoding/progress journeys. The generated artifact contains 456 rules / 305 app methods, SHA-256 `e770425ffeec1d26fe59160924603db5038a26ead7a2a965e7f82c5520b9280e`, with no fixture-only activity rules. The namespaces comprise 87 mobile, 198 common, 167 YouTube API and 4 GoogleCommon rules. This includes filtered player/application initialization but excludes the TV base `MainApplication`, sharedutils and the real watch/service path. No startup-layout profile is inferred from a fixture entry point.

The rule list was reviewed and imported at `smarttubetv/src/main/baseline-prof.txt` with provenance, then rebuilt. A strict profgen dump of the actual embedded binary profile was reviewed separately to verify all 456 app rules in both benchmark and production release, including expected player initializer/application entries and no fixture-only rules. Merely finding `baseline.prof` would have been insufficient because the previous release already shipped dependency profiles.

Private evidence is under `/tmp/newtube-pixel-startup-abr-20260907-hFM2tk/benchmark-smoke` and `profile-generation`. Two early wrapper issues were corrected before the comparison: a no-intent-filter activity must be explicitly resolved, and package UID lookup must exact-match rather than select the `.test`/`.benchmark` prefix match. Consequently the smoke/generation UID fields refer to the instrumentation UID and are **not** app-traffic evidence. The generation run's old metadata says `compilation=keep`/`iterations=5`; the profile rule actually performed compilation/reset and accepted stability after the two captured journeys. The corrected wrapper labels profile collection explicitly and has four host parser regressions.

## Same embedded APK comparison

Target SHA-256: `2ea56b93a3887c455d4b762a91eba8b357c986b68bbf4009d61d2137d10d5102`.

Ordered blocks are None ×3, Baseline-required ×3, Baseline-required ×3, None ×3. All are cold starts on the same installed artifact. Temperature/thermal status and exact app-UID counters are captured outside timed traces for each block. `None` versus `Partial(Require)` measures the combined embedded app **and dependency** profile effect; it does not isolate the incremental effect of the new app rules over dependency-only profiling.

All 12 iterations passed first frame, READY and continued decoding. The result does **not** establish a defensible general profile startup gain: the last None block was faster than both Baseline blocks, removing the apparent advantage seen against the first None block.

| Cold local-fixture metric | None median, n=6 | Embedded Baseline median, n=6 | Baseline − None |
|---|---:|---:|---:|
| Activity → decoded frame | 263.26ms | 263.93ms | +0.68ms |
| Activity → READY | 329.13ms | 327.09ms | −2.04ms |
| Full display | 498.25ms | 486.27ms | −11.98ms |
| Initial display | 344.73ms | 356.48ms | +11.75ms |

| Ordered block | READY runs | Battery temperature, before → after | Thermal status | Exact app UID RX/TX |
|---|---|---|---|---|
| None A | 667.9, 346.4, 436.4ms | 31.0 → 31.1°C | 0 → 0 | 0 / 0B |
| Baseline A | 316.2, 350.5, 322.2ms | 32.0 → 32.0°C | 0 → 0 | 0 / 0B |
| Baseline B | 332.0, 332.1, 304.1ms | 32.1 → 32.3°C | 0 → 0 | 0 / 0B |
| None B | 308.5, 311.8, 305.8ms | 32.3 → 32.2°C | 0 → 0 | 0 / 0B |

The first None block's decoded-frame range was 277.7–589.5ms versus 232.2–248.8ms in the final None block. That order/cache variation is larger than the pooled 2ms READY difference. No reduction in tails or variance can be established from these six samples per mode either. Thermal status was never throttled; the exact target UID was 10707 and had zero network-byte delta in all four blocks while normal Wi-Fi/mobile data were enabled. All fixture-control restoration readbacks matched and cleanup reported no errors.

Private JSON/Perfetto/log evidence is retained in `profile-abba-none-a`, `profile-abba-baseline-a`, `profile-abba-baseline-b` and `profile-abba-none-b` under the evidence directory above. The matrix was not extended to seek a favorable outcome. This validates reproducible release-style measurement, embedded-profile installation/coverage and local playback correctness—not a proven profile TTFF optimization. The final daily-driver debug APK does not embed baseline profiles; do not attribute these profile results to the debug installation.

## Final handoff

Final normal debug `42047861f47cb8e5e66c9e2ecd0d2f8e0733cc583877635f5821223c8dae463d` was reinstalled in place. Its matching offline preload hardware tests passed again (2/2, 8.513s) before the single ordinary autoplay attempt. That attempt did not produce a preload ready/hit because the media request was rejected; the experiment remains off. See the linked network/preload reports for the full distinction between local hardware correctness and failed real-network acceptance.

All 19 saved debug controls and original radio/proxy state were restored and verified; quality/mode/speed were unchanged. The app was restarted through its normal Splash entry to Browse, with no playback running, and Pixel ownership released. Test packages and private evidence remain available; no user app data was cleared.
