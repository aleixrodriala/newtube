# Proposed title

queue: extend phone queue management with account-scoped restoration

## Summary

Extend @PimpinPumpkin's phone queue contribution from [PR #102](https://github.com/aleixrodriala/newtube/pull/102) with browsing access, durable account-specific queues and safer edits. The original queue commit and attribution are preserved.

```text
Player / browsing queue bar
  -> select, append, Play next, reorder, remove, Clear
  -> separate local queue for signed-out use and each account
  -> restore order and selection without autoplay
```

Queue edits retain their intended entry when playback or the list changes. The queue remains accessible at its final item. Clear removes every non-playing entry; before playback it clears the restored queue completely.

Snapshots contain validated video IDs, order, selection and bounded display metadata in private no-backup storage. Atomic writes coalesce queue changes on one background worker. Account switching retires outgoing playback before restoring the incoming queue, and suppresses the outgoing player's final history flush under incoming authentication. Incognito entries and new private watch progress are excluded without discarding untouched normal queue entries or earlier bookmarks.

No added permissions, dependencies, wake locks, services, scheduled jobs or periodic polling. Application identity, signing configuration, SDK levels and submodule revisions are unchanged.

## Evidence

- **Before:** stale actions could target a replaced or newly playing entry; restored cursors could be treated as active playback; private sessions could discard normal queue entries or save local progress.
  **After:** regression tests protect entry identity, live playback, inactive restoration, eligible normal rows and private progress through shutdown.
- **Before:** the in-memory queue was shared across account selections and could not restore account-specific state after a stopped process.
  **After:** signed-out use and each account restore their own queue. Rapid switching, pending/failed writes, corrupt storage and account identity hydration are covered by tests.
- **Automated:** 325 targeted cases passed: 223 phone/UI and 102 common/player/queue; zero failures, errors or skips. Persistence/privacy coverage includes simulated API 24, 28 and 35. Reproduction command and limits: [queue validation](https://github.com/mmifsud/newtube/blob/7f3cd59dc0da56ee9591435d01df21254aaab9bc/docs/reviews/queue-validation.md).
- **Device:** owner-performed checks confirmed in-place queue retention, reordering, duplicate prevention, Play next, Clear/restart, private-entry/progress exclusion, switching between two signed-in accounts, and restoration after force-stop.
- **Review:** independent Standards and Spec reviews of the full `6f96a1b4...7f3cd59d` diff found zero actionable findings. Stale-menu timing remains a manual device check; controlled battery/wake-up measurements have not been performed.

## Merge Danger

**Door:** one-way for legacy queue migration; code is reversible.

The first updated startup assigns the legacy shared queue, if present, to the selected account or signed-out use. After a successful scoped save, the old shared file is removed. A downgrade does not automatically reconstruct that shared queue from account-specific files. New scoped files remain private and are removed by uninstalling or clearing app data. A kill before an asynchronous save completes can recover the preceding complete snapshot.

**Blast Radius:** Playback

Touches the shared Playlist model, phone queue/menu surfaces, private playback state and account-switch teardown. Storage failure preserves session isolation but may prevent cross-restart restoration. The latest original PR has additional UI/Clear behaviour; integration must be agreed before submission or merge.
