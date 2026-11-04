# Final queue review and submission preparation

Reviewed source: `7f3cd59dc0da56ee9591435d01df21254aaab9bc`.
Baseline: `6f96a1b42bb9272935360d7e5aec34d35f2753f0`.
Scope: complete `git diff 6f96a1b4...7f3cd59d`, including account and privacy follow-ups.

## Standards

Zero actionable findings. The independent review covered model/menu changes, sheet/bar lifecycle, layouts, storage/migration, account identity and private shutdown against workspace guidelines, `CLAUDE.md`, applicable HANDOFF rules and the code-review smell baseline. No applicable non-vendored CONTRIBUTING/CODING_STANDARDS document was found.

## Spec

Zero actionable findings against [the documented queue contract](queue-validation.md). The independent review covered intended-entry edits, inactive restored cursors, private entry/progress exclusion, account separation, one-time legacy ownership, bounded storage and account-exit teardown.

Both reviews were read-only source reviews. They did not rerun the already-passing 325 cases or independently repeat owner-performed phone checks. No runtime source changed during this final review.

## Current integration context

Read-only GitHub checks on 2026-11-04 found that the original [PR #102](https://github.com/aleixrodriala/newtube/pull/102) is still open. Its base remains `6f96a1b4`, matching the selected review baseline. Its current head is `d61ea607506f23dd3257cf0505fe0506ab3347e7`, two commits after the original `fcc67d81` preserved in this branch.

The latest original PR contains additional identity-safe queue edits, current-card refresh and a revised sheet/Clear policy. This branch has independently tested identity/lifecycle protections and broader persistence/account behaviour, but is not based on those two later original commits.

Meaningful UI differences need to be explicit before submission:

- This branch displays the complete retained queue, supports moving the playing entry while preserving playback, and retains access at its final item. The latest original PR displays the playing entry plus upcoming items.
- This branch's tested Clear removes every non-playing entry, including earlier entries. The latest original PR clears only upcoming entries, preserves Previous history and provides Undo.
- Adopting the original PR's newer UI/Clear policy would be a new implementation/testing step; the current phone results apply to the existing R10 policy.

The proposed PR description is [prepared locally](queue-pr-draft.md). It is not a published PR and is not a claim that the newer original PR's behaviour has been incorporated. Decide how to coordinate with the original contribution before publishing a competing or overlapping proposal. Preserve attribution.

## Remaining acceptance

Owner-confirmed: R10 queue separation/restoration between two signed-in accounts and restoration after force-stop; earlier queue edits, restart, upgrade retention and R9 incognito checks are recorded in the validation document.

Still unconfirmed on the phone: open a pending entry's menu, allow playback to advance to it, then invoke Remove; the new playing entry must remain. Clear must preserve the live playing item. Automated regressions cover these boundaries. A small test queue is recommended for the manual check.

Controlled battery/wake-up measurement remains unperformed. Existing feedback covers ordinary idle/screen-off use; it is not a controlled comparison. No broad battery optimisation is included in this submission.

No maintainer repository, PR, review or comment was changed. Publication requires the owner's explicit approval.
