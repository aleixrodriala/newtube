# Successor update for PR #124

Title: Queue successor: account-specific persistence and safer queue management

## Summary

**Successor update to this PR:** this revision includes all earlier PR #124 changes and supersedes its previous patch at `d75ea5c2`. Please review and apply the current PR head instead of the earlier version; the earlier patch does not need to be applied separately.

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
- **Automated:** 325 targeted cases passed: 223 phone/UI and 102 common/player/queue; zero failures, errors or skips. Persistence/privacy coverage includes simulated API 24, 28 and 35. Reproduction command and limits: [queue validation](https://github.com/mmifsud/newtube/blob/submission/queue-improvements/docs/reviews/queue-validation.md).
- **Device:** owner-performed checks confirmed in-place queue retention, reordering, duplicate prevention, Play next, Clear/restart, private-entry/progress exclusion, switching between two signed-in accounts, restoration after force-stop, protecting the playing entry from removal, and Clear leaving only that entry while playback continues.
- **Review:** independent Standards and Spec reviews of the full `6f96a1b4...7f3cd59d` diff found zero actionable findings. Stale-menu timing is covered by automated regressions; the owner confirmed the playing-entry protection and Clear outcomes on the phone. Controlled battery/wake-up measurements have not been performed.

## Merge Danger

**Door:** one-way for legacy queue migration; code is reversible.

The first updated startup assigns the legacy shared queue, if present, to the selected account or signed-out use. After a successful scoped save, the old shared file is removed. A downgrade does not automatically reconstruct that shared queue from account-specific files. New scoped files remain private and are removed by uninstalling or clearing app data. A kill before an asynchronous save completes can recover the preceding complete snapshot.

**Blast Radius:** Playback

Touches the shared Playlist model, phone queue/menu surfaces, private playback state and account-switch teardown. Storage failure preserves session isolation but may prevent cross-restart restoration. The latest original PR #102 has additional UI/Clear behaviour: its Clear preserves earlier session history and offers Undo, while this tested revision clears every non-playing entry. That integration choice remains explicit for maintainer review.

## Screenshots

Queue opened from Search, with the current item at position 2 of 5:

![Queue opened from Search](https://raw.githubusercontent.com/mmifsud/newtube/submission/queue-improvements/docs/images/queue/queue-search.png)

Channel browsing with the mini-player above the collapsed queue bar:

![Channel mini-player and queue bar](https://raw.githubusercontent.com/mmifsud/newtube/submission/queue-improvements/docs/images/queue/queue-channel.png)
