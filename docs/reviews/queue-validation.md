# Phone queue: behaviour and validation

Extends the queue sheet introduced in [PR #102](https://github.com/aleixrodriala/newtube/pull/102) by @PimpinPumpkin. The original contribution remains in this branch's history. The queue is available from the player and from a collapsed bar on Home, Search, channels and playlist/upload screens.

## Intended behaviour

- Append moves pending entries to the end. During playback, Play next inserts after the playing entry; before playback, it inserts at the restored pending cursor, or first when there is no cursor. Requeuing the playing occurrence preserves playback. Adding an item never starts playback.
- The sheet supports selection, reordering, removal, Clear and accessible move/remove actions. Moving the playing row preserves playback. Removal and Clear preserve the playing occurrence, including when playback advances while a menu is open. Restored cursors are editable because restoration does not start playback.
- Sheet actions validate the live snapshot before editing. Queue menu actions retain the resolved occurrence and reject a target that disappears, is replaced, or becomes the playing item while the menu is open. Distinct equal duplicates can be appended/moved independently; internal playback callers using equivalent clones retain their existing logical-match behaviour.
- The explicit queue remains reachable at its final item and while recommendations refresh. Queue-card and related rows expose the existing long-press menu.
- Structural/current-item changes save order, selection, validated video IDs and bounded display metadata to `mobile-queue-v1.json` in private no-backup storage. Restoration never starts playback automatically. Only queues already saved by this version can survive a later process restart or in-place update; unsaved process memory cannot be migrated.
- Queue storage remains shared within the installation, matching the original in-memory model. It does not add account-specific queues, seek-position checkpoints or cross-device sync.

## Reliability, privacy and efficiency

- Versioned reads are bounded to 512 KiB and 300 entries; the device's existing Playlist capacity still applies. Invalid, corrupt, unsupported or oversized data is ignored. Atomic writes retain the previous complete file if interrupted.
- Saves coalesce main-thread bursts and run on a background writer with at most one active and one pending immutable snapshot. Compound requeue operations publish only their completed state. A kill before a pending write finishes can recover the previous complete snapshot.
- Incognito playback does not overwrite the saved normal queue. Only the private open itself and entries newly queued during that private session are excluded; untouched normal rows remain eligible. Private IDs remain excluded after their temporary routing flags are consumed, including after Play next or append moves the entry. Exclusions remain bounded by entries still in memory.
- Private playback also avoids writing local watch progress or resetting earlier normal bookmarks, including the final engine flush after the player closes. Private restarts of completed/short/live videos use transient position handling; explicit timestamps and live-edge behaviour remain intact. Existing progress from earlier builds is not deleted.
- No credentials, account identifiers, stream URLs, raw thumbnail URLs, local paths or playback objects are stored. Local-file and remote entries are excluded; thumbnails are derived from validated video IDs. Valid live/upcoming entries can be saved for later selection. Uninstalling or clearing app data deletes the file.
- Visible UI updates are event driven. Lifecycle stop/detach releases queue subscriptions and pending UI callbacks. Image requests use bounded sizes, placeholders and recycle/detach cancellation.
- No added permissions, dependencies, wake locks, jobs, services, network clients, polling or periodic progress listeners. Minimum Android version remains API 24.

## Validation

The extraction is tested separately from the personal test branch. Results below apply to this queue-only submission source, with the existing project toolchain and unchanged submodule revisions.

**271 targeted cases passed: 172 phone/UI and 99 common/player/queue, with zero failures, errors or skips.** This is the final queue-only extraction with the full-diff review fixes. Its test selection excludes unrelated recovery tests and includes all queue regressions.

The initial extraction passed 214 existing cases. Independent review then exposed further current-item, duplicate, stale-menu and restored-cursor boundaries; 10 additional regression cases were demonstrated failing before fixes. Those cases and eight compatibility/control cases now pass. After that 232-case checkpoint, R8 phone feedback exposed excessive exclusion of normal rows during private playback and local incognito progress writes. Regression tests reproduced 21 additional failing API cases before fixes; 18 further compatibility/control cases cover actual controller/queue ordering, shutdown, existing bookmarks, subsequent normal playback, completed videos, timestamps and live playback. Final Standards review: zero remaining actionable findings. Final Spec review: zero remaining actionable findings. Reviews cover the complete queue diff from `6f96a1b4` and the follow-up privacy fixes.

Automated checks cover append/order/current selection, complete-state notifications, duplicate removal, stale menus and sheet edits, Clear, visibility/lifecycle cleanup, image cancellation, local persistence, interrupted writes, invalid files and private-entry requeuing. Privacy/persistence coverage includes simulated API 24, 28 and 35.

To reproduce:

```sh
./gradlew --no-daemon :smarttubetv:testStmobileDebugUnitTest --tests '*Queue*Test' --tests '*RelatedVideoMenuTest' --tests '*CardMenuMigrationTest' --tests '*PressIntentDetectorTest' --tests '*MobilePlaybackLaunchTest' :common:testDebugUnitTest --tests '*Playlist*Test' --tests '*QueuePlaybackModeTest' --tests '*VideoStateController*Test' --tests '*VideoLoaderController*Test'
```

Use JDK 21 and Android SDK 37.0. Set `JAVA_HOME` and `ANDROID_HOME` for the local installation when necessary.

## Limits and scope

Robolectric tests simulate Android; they do not verify physical-device decoder behaviour, OEM background kills, battery consumption or wake-ups. On 2026-11-04 the owner reported that signed queue-only R8 (`1.17.1-test-r8`, source `66faee67`) installed through Brave and retained the queue. Further owner feedback confirmed reordering, avoiding duplicate additions, Play next repositioning, Clear surviving restart, and the documented shared queue across signed-in/signed-out use. The subsequent incognito check failed: normal B disappeared after force stop, and private playback showed new progress. Those failures are now addressed by automated regressions and the follow-up code; phone acceptance of the corrected R9 candidate is still pending. Stale-menu interactions also remain a manual acceptance gap. Earlier feedback on a related test build covered normal use and idle/screen-off operation; controlled battery/wake-up measurements remain outstanding.

This branch contains queue UI, local persistence and their tests. It does not contain upload alerts, activity/surface recovery changes, private test packaging, signing material or source-backup bundles. SDK levels, application identity, signing configuration and submodule pointers are unchanged from the fork point. Existing project lint/translation findings have not been globally repaired or suppressed.
