# Phone queue: behaviour and validation

Extends the queue sheet introduced in [PR #102](https://github.com/aleixrodriala/newtube/pull/102) by @PimpinPumpkin. The original contribution remains in this branch's history. The queue is available from the player and from a collapsed bar on Home, Search, channels and playlist/upload screens.

## Intended behaviour

- Append adds at the end; Play next inserts after the selected item, or first before playback starts. Adding an item does not start playback.
- The sheet supports selection, reordering, removal, Clear and accessible move/remove actions. Moving the playing row preserves playback. Removal and Clear preserve the playing occurrence, including when playback advances while a menu is open. Restored cursors are editable because restoration does not start playback.
- Row actions validate the live snapshot before editing. Duplicate IDs refer to their intended occurrence; internal callers using equivalent clones retain their existing logical-match behaviour.
- The explicit queue remains reachable at its final item and while recommendations refresh. Queue-card and related rows expose the existing long-press menu.
- Structural/current-item changes save order, selection, validated video IDs and bounded display metadata to `mobile-queue-v1.json` in private no-backup storage. Restoration never starts playback automatically. Only queues already saved by this version can survive a later process restart or in-place update; unsaved process memory cannot be migrated.
- Queue storage remains shared within the installation, matching the original in-memory model. It does not add account-specific queues, seek-position checkpoints or cross-device sync.

## Reliability, privacy and efficiency

- Versioned reads are bounded to 512 KiB and 300 entries; the device's existing Playlist capacity still applies. Invalid, corrupt, unsupported or oversized data is ignored. Atomic writes retain the previous complete file if interrupted.
- Saves coalesce main-thread bursts and run on a background writer with at most one active and one pending immutable snapshot. Compound requeue operations publish only their completed state. A kill before a pending write finishes can recover the previous complete snapshot.
- Incognito playback does not overwrite the saved normal queue. Private IDs remain excluded after their temporary routing flags are consumed, including after Play next or append moves the entry. Exclusions remain bounded by entries still in memory.
- No credentials, account identifiers, stream URLs, raw thumbnail URLs, local paths or playback objects are stored. Local-file and remote entries are excluded; thumbnails are derived from validated video IDs. Valid live/upcoming entries can be saved for later selection. Uninstalling or clearing app data deletes the file.
- Visible UI updates are event driven. Lifecycle stop/detach releases queue subscriptions and pending UI callbacks. Image requests use bounded sizes, placeholders and recycle/detach cancellation.
- No added permissions, dependencies, wake locks, jobs, services, network clients, polling or periodic progress listeners. Minimum Android version remains API 24.

## Validation

The extraction is tested separately from the personal test branch. Results below apply to this queue-only submission source, with the existing project toolchain and unchanged submodule revisions.

**214 targeted cases passed: 146 phone/UI and 68 common/player/queue, with zero failures, errors or skips.** The lower count than the personal test branch reflects its unrelated recovery tests being absent here. All queue regressions remain included.

Automated checks cover append/order/current selection, complete-state notifications, duplicate removal, stale menus and sheet edits, Clear, visibility/lifecycle cleanup, image cancellation, local persistence, interrupted writes, invalid files and private-entry requeuing. Privacy/persistence coverage includes simulated API 24, 28 and 35.

To reproduce:

```sh
./gradlew --no-daemon :smarttubetv:testStmobileDebugUnitTest --tests '*Queue*Test' --tests '*RelatedVideoMenuTest' --tests '*CardMenuMigrationTest' --tests '*PressIntentDetectorTest' --tests '*MobilePlaybackLaunchTest' :common:testDebugUnitTest --tests '*Playlist*Test' --tests '*QueuePlaybackModeTest' --tests '*VideoStateController*Test' --tests '*VideoLoaderController*Test'
```

Use JDK 21 and Android SDK 37.0. Set `JAVA_HOME` and `ANDROID_HOME` for the local installation when necessary.

## Limits and scope

Robolectric tests simulate Android; they do not verify physical-device decoder behaviour, OEM background kills, battery consumption or wake-ups. Earlier phone feedback on the related test build confirmed normal queue use and idle/screen-off operation, but this extracted build has not been installed on a phone. Device acceptance of the extraction remains outstanding.

This branch contains queue UI, local persistence and their tests. It does not contain upload alerts, activity/surface recovery changes, private test packaging, signing material or source-backup bundles. SDK levels, application identity, signing configuration and submodule pointers are unchanged from the fork point. Existing project lint/translation findings have not been globally repaired or suppressed.
