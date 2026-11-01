# Phone queue improvements: validation

Extends the queue sheet introduced in [PR #102](https://github.com/aleixrodriala/newtube/pull/102) by @PimpinPumpkin with a persistent collapsed bar on Home,
Search, channel pages, playlist/upload lists, and the non-fullscreen watch screen. Adds
the existing append-to-queue menu action to the phone menu, retaining its visibility
setting and the distinct Play next behavior. The playing row can be reordered without
restarting playback; removal and Clear preserve the playing item.

## Reliability and efficiency

- Queue changes drive visible UI updates; no polling or periodic progress listeners.
- Lifecycle stop/detach releases queue subscriptions and pending UI callbacks.
- Snapshot checks reject stale row actions rather than applying outdated indexes.
- Thumbnail requests use bounded sizes and placeholders for absent/failed images;
  recycled/detached rows cancel their requests and clear view callbacks.
- No added permissions, dependencies, wake locks, jobs, or media/network clients.
- Minimum Android version remains API 24 (Android 7.0).

## Validation completed

- Release-style acceptance APK built successfully; release vital lint passed.
- 62 focused tests passed: 9 common Playlist tests and 53 phone UI/policy tests.
- Layout tests cover Search, Channel, and playlist/uploads on API 24, 28, and 35,
  including enqueue before playback, reduced viewport geometry, mini-player/footer
  separation, sheet open/dismiss, stop/resume state refresh, and listener cleanup.
- Additional tests cover stale edits, current-item movement, image failures/cancellation,
  duplicate sheet opens, invalid window tokens, menu migration, and destroyed hosts.
- Acceptance APK permissions matched the preceding build; minimum/target SDK, signature,
  separate test package identity, and embedded ART profile were checked.
- Manual phone testing confirmed Search visibility, keyboard and mini-player coexistence,
  preserved query text, append order, Play next insertion before playback/after the
  playing item, and reordering including the playing row while playing or paused.
  Channel browsing was checked with the mini-player above the queue bar. Queue position
  and upcoming counts were consistent in the checked state.

## Reproduce focused tests

With the repository's Android SDK/JDK requirements installed:

```sh
./gradlew :common:testDebugUnitTest --tests '*PlaylistMoveTest' --tests '*PlaylistListenerTest'
./gradlew :smarttubetv:testStmobileDebugUnitTest --tests '*Queue*Test' --tests '*CardMenuMigrationTest'
```

The 53 phone tests also passed using the normal debug unit-test command above, with the upstream build configuration. The release-derived acceptance APK used the
same app/test sources; its separate packaging configuration is not part of this change.

## Limits

Robolectric tests are simulations, not end-to-end device tests. API 36 simulation requires
Java 21 and was not completed on the available Java 17 JVM. No usable emulator was
available. Real-device heap profiling, battery measurements, extended network-loss playback, and
broad device coverage remain unverified.
