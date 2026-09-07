# Pixel release-style playback benchmarks

The `stmobileBenchmark` target is release-derived, non-debuggable and shell-profileable. It uses the same signing configuration and application ID as the existing debug target so it can replace that installation with `adb install -r`, preserving app data. If the signing key differs from the installed app, stop: never uninstall to make a benchmark install succeed. Production `release` remains non-profileable and unchanged; neither target enables R8 yet.

The separate `macrobenchmark` APK is the measuring process, not the app under test. It requires API 34+, where AndroidX resets ART profiles without its older-device uninstall/reinstall fallback. The default `keep` compilation mode preserves existing compilation state. Explicit `none`, `baseline`, and profile-generation runs modify ART compilation/profiles, not accounts or app preferences. AndroidX also writes a ProfileInstaller skip marker, and cold-start measurement attempts to drop shader/kernel page caches. Those benchmark code-cache/profile effects are not a media-cache reset and are not restored byte-for-byte by this wrapper. This guard deliberately excludes older daily-driver devices.

## What is measured

`BenchmarkPlaybackActivity` exists only in the benchmark target and requires the shell's `DUMP` permission. It uses the production Media3 player initializer, a surface and the bundled generated H264/AAC fixture. Every media request is unconditionally resolved to an asset. With `debug.arc.benchmark_fixture=1` set before process creation, the benchmark application suppresses service token/API warmups; native/cache initialization still runs. This does not copy an account, clear app data, or send a service playback request.

The test requires both a decoded first frame and `STATE_READY`, then at least 800ms position advance and 20 decoded output buffers without intervening buffering. It records:

- `StartupTimingMetric`: process/activity initial draw and fully drawn playback. `reportFullyDrawn()` occurs only after both frame and READY.
- `NewTubeFixture.firstFrame`: activity `onCreate` to first decoded frame.
- `NewTubeFixture.ready`: activity `onCreate` to READY.

These are local decoder/startup measurements, not tap-to-network-picture measurements. The fixture does not exercise the real watch UI, service extraction, live networking, or next-item preloading. Use the separate playback/network rig for those and report its APK, network, selected track, frame, READY and sustained-playback evidence. A [non-debuggable, profileable target and an external measuring process](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview) remove debug-build measurement overhead but do not themselves establish a performance improvement.

## Build, verify, install

Coordinate exclusive device and Gradle ownership first. The harness never builds or installs automatically.

```sh
./gradlew :smarttubetv:assembleStmobileBenchmark :macrobenchmark:assembleBenchmark
```

Resolve the actual APKs from `smarttubetv/build/outputs/apk/stmobile/benchmark/` and `macrobenchmark/build/outputs/apk/benchmark/`. Prefer the target arm64 APK for a Pixel. Inspect its package name, manifest (`debuggable=false`, shell profileable), signing certificate and SHA-256 before installing. Preserve an immutable copy of the previously installed APK if an exact rollback is needed. Install the target and test APK with `adb -s SERIAL install -r /absolute/path.apk`, one at a time; do not use a task that uninstalls the target or clears its data.

On machines without `keystore.properties`, the target application ID is `io.github.aleixrodriala.arc.debug`; pass that explicitly below. The test application ID is always `io.github.aleixrodriala.arc.benchmark`.

With the chosen Pixel awake, unlocked and exclusively owned, run:

```sh
python3 tools/benchmark-pixel.py --serial SERIAL --output /tmp/newtube-release-fixture-run --mode fixture --iterations 5 --compilation keep
```

The output must be a new directory. The tool verifies the installed target, captures its APK hash, saves/restores only `debug.arc.benchmark_fixture`, captures scoped fixture logs, and pulls AndroidX JSON/Perfetto artifacts. It does not change radios, proxy settings, media caches, credentials or server-client selectors. AndroidX's code-cache/profile effects are described above. It force-stops only the target and its own test process at cleanup. Review `run-state.json` for successful restoration. Private device-side artifacts are retained at the recorded unique path; remove only that exact run directory if no longer needed.

The wrapper records battery temperature, system thermal status and exact target-UID byte counters before/after each block, outside timed traces. Missing counters remain unknown, not zero. The package lookup deliberately excludes the separate `.test`/`.benchmark` packages returned by Android's prefix search. Host-only parser checks can be run with `python3 -B tools/test_benchmark_pixel.py`.

For an ART-controlled comparison, use separate new output directories with `--compilation none` and `--compilation baseline`. The latter requires an installed embedded baseline profile; passing it with dependency-only profiles does **not** demonstrate app-specific profile coverage. Compare matching workload/build/profile artifacts and enough repetitions, as described in [Android's profile measurement guidance](https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile).

On an APK containing both dependency and app rules, this comparison measures their combined compilation effect. It does not isolate the incremental benefit of the app rules over dependency-only profiling.

## Generate, then validate an app-specific profile

```sh
python3 tools/benchmark-pixel.py --serial SERIAL --output /tmp/newtube-release-profile-run --mode profile
```

`BaselineProfileRule` collects the same offline journey, requires a stable rule set, and filters to mobile/common/player and the GoogleCommon/YouTube API namespaces while excluding the fixture activity. The filter also excludes the TV base `MainApplication` and sharedutils helpers; it does not represent complete app-startup coverage. It intentionally emits no startup profile: a fixture-only entry path is not the production launcher or deep-link journey. This is a reproducible [on-device generation workflow](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile), not a checked-in or validated optimization by itself. The direct rule avoids adding an AGP baseline-profile plugin before its AGP 9 compatibility is proven here.

The wrapper requires a nonempty `*-baseline-prof.txt`, app-specific method rules and no fixture-only rules, then records counts and hashes in `verified-profiles.json`. Before incorporating a generated artifact:

1. Review the text and prove the covered methods belong to the production player/application paths, not test scaffolding. Offline coverage does not imply service/watch-UI coverage.
2. Deliberately copy approved rules into an app profile source location, rebuild, and inspect the merged text and compiled `assets/dexopt/baseline.prof`/`.profm` in that immutable APK. Do not infer app coverage merely from the presence of those files; the existing release already contains dependency profiles.
3. Run the installed profile-required comparison against `none`, inspect frame/READY and sustained progress, and report confidence and any regressions. Do not claim a generated profile improved release startup until this comparison and artifact verification succeed.

No generated app profile or R8 change is included by this harness alone.
