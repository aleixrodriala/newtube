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

The successor description for the existing [PR #124](https://github.com/aleixrodriala/newtube/pull/124) is [prepared locally](queue-pr-draft.md). Read-only discovery confirmed that PR belongs to the owner and previously pointed to `mmifsud:submission/queue-improvements` at `d75ea5c2`, an ancestor of this verified branch. The owner explicitly authorized updating that same PR and making clear that this successor should be applied instead of its earlier version. The newer original PR #102 behaviour remains an explicit integration difference; attribution is preserved.

## Remaining acceptance

Owner-confirmed: R10 queue separation/restoration between two signed-in accounts and restoration after force-stop; earlier queue edits, restart, upgrade retention and R9 incognito checks are recorded in the validation document.

In the requested A → B → C phone check, the owner reported that B, the second queued video, could not be removed while it was playing. After Clear, B continued playing and was the only remaining entry. Record these observed current-item protection and Clear outcomes as passed. Automated regressions separately exercise stale-menu timing and replaced entries.

Controlled battery/wake-up measurement remains unperformed. Existing feedback covers ordinary idle/screen-off use; it is not a controlled comparison. No broad battery optimisation is included in this submission.

The final source reviews made no maintainer mutations. The owner subsequently authorized the PR #124 update: fast-forward its head branch on the personal fork, replace its title/description with the successor version, and add an update notice. The maintainer's main branch and PR #102 are outside this authorization.
