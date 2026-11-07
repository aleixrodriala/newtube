> Copied from a private working folder on 2026-09-29: the tools it names now live in `tools/netbench/`; the raw results and logs it cites, and the reviews and analyses it names (`review/`, `audit-current-rules.md`, `ttff-analysis.md`, `recap/RECAP.md`), are not in the repo.

# Player routes: one planner for both lanes (design 3)

Final, 2026-09-29 ~14:00. Draft 3 (~10:30) added the morning's Pixel and emulator acceptance, the fixes it led to (a private video is not a bot check, members-only in Spanish, history sync), the TTFF work and the deleted phone gates. This version adds the afternoon: the signed-in head decided by an A/B (§2.1), a second phone (a Mi 8, Snapdragon 845) and what it found, the player-JS gate (v18), the V8 solver lane (v19), the walk replay tests (§7), and the benchmark harness changes that made two phones safe on one network. Draft 2 (~02:10) followed a Codex astra adversarial review (`review/codex-lanes-astra.md`, reconciled in §10) and the TTFF breakdown (`ttff-analysis.md`).

It replaces PLANNER.md §3, which covered only the signed-out lane, and the signed-in ring.

**Implemented** (main / MediaServiceCore):
- the planner and design 3: main 1ee8d30b / MSC 10f6bf65; refinements up to main d5f0e600 / MSC ef260c6b;
- the player-JS gate: main 87d65cd9 / MSC 993ba593; the walk replay tests: main f086a4c1 / MSC a45493e4; the V8 solver lane: main 6f7fed00 / MSC 8e92399b;
- device builds (`tools/netbench/appbench`, `.check` signed out, `.auth` signed in with history writes blocked): v15 = main 4a7308c6; v16 = a261a486; v17 = 68620642 (history, private/members, TTFF work, PO token v4 behind a switch, the account-first switch); v18 = 87d65cd9 (player-JS gate); v19 = 6f7fed00 (V8 lane).

**Inputs:**
- `audit-current-rules.md`: every rule in the old code, with 20 contradictions.
- `recap/RECAP.md`: signed-out evidence per source × category × network.
- The signed-in route matrix of 2026-09-29, `appbench/results/auth-emu-heads.jsonl`:
  - the owner's account, an emulator, home Wi-Fi;
  - a benchmark build that blocks every watch-history write (`account-write blocked`).
- The v14/v15 LTE runs on the Pixel.
- `ttff-analysis.md`.

**Scope.** NewTube's phone walk. Every rule in it is NEWTUBE-only (`sPreferNoPotClient`). Upstream's code paths and `VIDEO_INFO_TYPE_LIST` stay as they are.

## 0. Why a third design

**The release ran none of the signed-out redesign.** The planner, anonymous TV_TIZEN and HLS for VOD were read only by debug and benchmark builds. The phone's order was upstream's ring bent by about fifteen helpers, and five mechanisms decided where WEB_EMBED went.

**Signed in, it led with the two account shapes that are dead:**

| route, with the owner's account (one open each, emulator, 45 s) | ordinary (dQw4w9WgXcQ) | 18+, not embeddable (qkO6iBwcoe4) | 18+, embeddable (WaOKSUlf4TM) | made for kids (_WB5hh7WOb4) | live (5yx6BWlEVcY) | members only, not a member (w664JpkrDio) |
|---|---|---|---|---|---|---|
| TV_DOWNGRADED (the old head) | OK, then **media 403 ×3** | 403 ×3 | 403 ×3 | 403 ×3 | no start | refused |
| TV 7.x (second head) | OK, **SABR-only** | same | same | same | no start | refused |
| **TV_TIZEN** | **plays** (ff 2.6 s) | **plays** (2.2 s) | **plays** (2.1 s) | **plays** (2.5 s) | **no start** (live manifest built from formats: IllegalStateException) | refused ("Join this channel…") |
| TV_EMBED | "no longer supported" | same | same | same | same | same |
| TV_KIDS | plays | refused | refused | refused | refused | refused |

**What that cost signed in:**
1. a TV_DOWNGRADED answer whose media 403s;
2. then a quarantine and a recovery walk;
3. then anonymous VISIONOS: 3.2 s to first frame on the emulator against 2.2 s cold signed out.

Once both heads were quarantined, every open went anonymous, and every quarantine expiry paid for the dead probe again. The shape that serves (TV_TIZEN) was reached only after an anonymous LOGIN_REQUIRED, or under a bot wall.

**Evidence limits (astra, A).**
- Four account opens on one emulator, one network, 45 s each, with no seek.
- The Pixel runs (§7) are the sustained, two-network check.
- TV_TIZEN is the broadest serving shape measured, not the only one: TV_KIDS served one ordinary video.

## 1. The model

**Route = (source, identity).**
- A source is a `PlayerSource` from the catalog: request shape, visitor, token, endpoint and budget.
- The identity is **anonymous** or **the account**.
- The account goes with exactly one source, TV_TIZEN, the broadest serving account shape measured.
- The web clients refuse OAuth (HTTP 400, 3/3). The app clients (VISIONOS, ANDROID_VR, IOS, ANDROID_REEL) do not take it.

**A request carries the account iff its route's identity is the account.** `VideoInfo.isAuth()` now means exactly that. Before, it meant "auth-capable client", which caused audit C-5: a signed-out TV_TIZEN refusal benched the account route and made signed-out consensus unreachable.

**Lane.** Signed in or signed out, read once per walk. It changes:
1. the identity of TV_TIZEN;
2. when TV_TIZEN is admitted;
3. which sources can serve an age gate;
4. whether consensus needs the account witness.

Everything else is one implementation.

## 2. The planner (`PhoneSourcePlanner`, both lanes)

### 2.1 Order

| # | source | signed out | signed in | why |
|---|---|---|---|---|
| 1 | VISIONOS | always | always | serves every ordinary category (H 11/11, device); no cipher, no ads; ordinary first frame 0.93 s median on Pixel LTE (n=6) |
| 2 | TV_TIZEN | anonymous, **admitted only after an anonymous content refusal** (§2.2) | **with the account, second unless benched** | anonymous: made-for-kids videos, 15/15 on LTE at request 2 (v14), ff median 1.26 s. With the account: ordinary, both kinds of 18+, kids (4/4, emulator) |
| 3 | WEB_EMBED | always | always | the only anonymous source for embeddable 18+; kids when TV_TIZEN fails; pre-roll wait honoured; SABR-only + HLS on ~1 answer in 3 (HLS for VOD plays those) |
| 4 | ANDROID_VR | always | always | the live-DASH source |
| 5-9 | IOS, ANDROID_REEL, MWEB, WEB, WEB_SAFARI | always | always | never measured to serve what 1-4 refuse; last, not dropped |

Not on the phone:
- GEO: refused everywhere;
- TV 7.x and TV_DOWNGRADED: media dead, signed in and signed out;
- TV_EMBED: dead;
- TV_LEGACY, TV_SIMPLY, TV_KIDS.

**Signed out, TV_TIZEN is not second.**
- Anonymously it refuses every age gate.
- Its anonymous identity is the bot wall's single probe.

**Signed in, TV_TIZEN is second, not first: decided by the Pixel `.auth` A/B (2026-09-29, Wi-Fi, v17, `debug.arc.account_first`).** The same 16 videos, both orders:

| | VISIONOS first | account route first |
|---|---|---|
| /player requests (16 videos) | 40 | 35 |
| ordinary, cold start (n=7): answer / signature / first frame | 229 / 0 / **561 ms** | 321 / 188 / 738 ms |
| kids and 18+, cold start (n=5) | 424 / 131 / 860 ms (request 2) | 341 / 179 / **690 ms** (request 1) |
| in-process opens (memo warm), ordinary | first frame 330-755, median 418 ms | 553-652, median 575 ms (signature 5-8 ms) |
| in-process, kids and 18+ | 610, 1125 ms | 368, 439 ms |
| watch history (dry-run) | 7/7 complete (VISIONOS winners through the TV 7.x sync) | 6/6 complete (no sync request) |

The account route first costs ordinary videos 100-180 ms (its answer is slower, and on a cold start its signature solve is on the path) and saves kids and 18+ videos 170 ms or more, plus one request each and the history sync request. Ordinary videos are most of what people watch, so VISIONOS stays first. The switch stays (`VideoInfoService.setAccountRouteFirst`, `debug.arc.account_first`). Reasons to revisit:
- the kids channel memory (§9) gives kids videos the saving without the ordinary cost;
- Premium formats, which only the account route serves (unmeasured);
- a user whose opens are mostly kids videos.

Live stays on VISIONOS and ANDROID_VR whatever leads (live at request 2 via ANDROID_VR in both orders).

**Health outranks position.**
- A benched account route is not planned (BotWallBook's route record: a media 403, a challenge, a reload-page or a SABR-only answer, per video, then per attachment).
- A held live answer skips every non-live-DASH source.

### 2.2 Answers, and what each does

| answer | recognised by | effect |
|---|---|---|
| served | a playable delivery (§5) | win |
| live, no DASH manifest | `isLive`, playable | hold it; only live-DASH sources after it (unchanged) |
| content refused | UNPLAYABLE, anonymous, non-web source, no live signal, **no terminal kind** | signed out: admits TV_TIZEN (anonymous) next, once, unless walled, route-failed, or the recovery suspect |
| age gate | `isAgeGate()` (structured marker or AGE_*) | walk on; see the age-gate stop below |
| sign-in request | LOGIN_REQUIRED without the age marker (private, shared) | walk on; signed in, the account route is second anyway |
| bot challenge | explicit bot text, or the same LOGIN_REQUIRED text from two clients | BotCheckWalkState / BotWallBook. **The second signal trips the circuit only when a second video repeats it within 10 min** (draft 3): a private video answers exactly that way (Pixel, yZIXLfi8CZQ: VISIONOS and ANDROID_VR both "Inicia sesión"), and it used to arm the 15-minute circuit and answer the next opens "confirm you're not a bot". An explicit bot text trips as before. |
| no delivery | OK, no playable delivery (SABR-only) | walk on |
| terminal | allowlisted kind: removed by uploader, account terminated, removed for a violation, live recording gone, **members only** (new: "Join this channel…", and since draft 3 "Hazte miembro de este canal…" as the Pixel answers, plus unverified guesses for fr/de/it/pt) | **consensus** (below) |
| no response | timeout or IOException, **including a refused connection** (audit C-11, fixed) | transport-down after 2 in a row (unchanged) |

**The age-gate stop is new.**
- The walk settles once:
  - some answer was an age gate;
  - every source of the lane that can serve one (signed out {WEB_EMBED}; signed in {TV_TIZEN with the account, WEB_EMBED}) has answered without serving it, or is not in the walk (benched).
- Their answer need not be the gate: WEB_EMBED refuses a non-embeddable 18+ video for its embed policy.
- A source asked but silent (a timeout) refused nothing, and the walk goes on.
- The verdict returned is the age gate.
- **Effect:** an 18+ video signed out ends at request 2 instead of 8 plus three web-token mints.
- **Evidence:** every anonymous source except WEB_EMBED answered the gate on the embeddable 18+ videos (Wi-Fi harness; LTE has no anonymous age evidence yet, v15 LTE adds it).

**Terminal consensus.**
- The same terminal kind from three identities (web session, app visitor, embed page, confirmed account) settles the walk:
  - signed out at request 4;
  - signed in at request 3 (VISIONOS, TV_TIZEN with the account, WEB_EMBED).
- **Signed in, the confirmed account witness is required.** Three anonymous answers must not settle a video the account was never heard on (astra C).
- A terminal refusal does not admit anonymous TV_TIZEN: members-only and removed videos gain nothing from it.

### 2.3 Recovery (the watched video's reload after a media failure)

- **Order:** the planner order with the failed source (the suspect) moved **last**, in both lanes.
- **Signed out, a TV_TIZEN suspect** is asked last. The refusal rule no longer puts it straight back next.
- **A benched account route** stays out.
- **Blame is anchored to the failing video in every caller.** It was missing in `runFormatErrorAction`, the "Unable to connect to" branch and DownloadJob (audit C-12, fixed).
- **The one-minute wall (router v22, on by default; §9).** A walled visitor's media is refused past 60.0 s of stream position, per (visitor, source). The recovery used to alternate VISIONOS and ANDROID_VR (only the latest suspect went last) until the reload cap ("Unknown source error"). Now:
  - a 403 is read against the open's media request starts: refused at or past 55 s after requests below 60 s were served, nothing served past 65 s = the wall; nothing served below it (a resume or seek straight past it) is ambiguous until a second past-the-wall 403 on the video or a remembered pair confirms it (`playback-media403 … signature=`, `playback-wall … how=`);
  - a wall benches that source for the rest of the video's recovery (30 min) and records (visitor fingerprint, source) for 6 h, persisted: those sources go last in every walk while they would send that visitor (`walled-here=`, `walled=` on the plan line). An ordinary 403 benches nothing (a kids video's WEB_EMBED is one of its two servers);
  - recovery walks and a walled visitor's walks ask WEB_EMBED, TV_TIZEN (anonymous), ANDROID_REEL, then ANDROID_VR (`vod-vr-late`); first opens with nothing walled and live cards are unchanged;
  - the first wall of VISIONOS or ANDROID_VR re-rolls the playback identity (the web session's visitor; the browse identity is untouched), 2 per 6 h, persisted until minted, and keeps the fresh visitor 6 h; in the recovery a re-rolled VISIONOS comes back after WEB_EMBED and TV_TIZEN, before ANDROID_REEL's 360p (`refreshed=`).
  Codex sol review, reconciled: a failed visitor mint now refunds its re-roll; the engine's request ledger is read under one lock. Kept as designed: one wall persists without a second confirmation (a stale link or a bad rendition refused exactly between 55 and 65 s of stream, before anything past 65 s played, is the only false positive, and its cost is one re-roll plus VISIONOS asked last for that visitor), and the route caches stay bounded (8 winners, as since v19).
  Rollbacks: `debug.arc.wall_memory=0`, `debug.arc.vod_vr_late=0`, `debug.arc.playback_reroll=0`, `debug.arc.playback_keep=0`; `debug.arc.wall_memory_ttl_min`, `debug.arc.playback_reroll_budget`; synthetic wall `debug.arc.poison_wall_s=60`.

### 2.4 Kept around the order, unchanged

- **The bot wall's plan**: the account route plus one anonymous probe per interval. Signed in, that is TV_TIZEN with the account, never the dead heads.
  - Probes now rotate between VISIONOS and ANDROID_VR.
  - WEB left the rotation because it answers SABR-only or progressive-only, so it could re-confirm a wall but never end one. Under the planner it would have been the first probe.
- **Live** hold and skip.
- **Walk roles.**
- **Budgets:**
  - 45 s per walk;
  - per attempt: app sources 7 s; WEB_EMBED and web 20 s; TV_TIZEN 7 s anonymous after a refusal, 15 s with the account or under a wall;
  - transport-down after two no-responses;
- **The negative and format caches.**

## 3. Account-route health and history

- TV_DOWNGRADED and TV are no longer asked for playback on the phone.
  - The head quarantine (`AuthRouteQuarantineBook`) gets no records, and its persistence is no longer wired.
  - The account route's health is BotWallBook's route record.
- `onAccountChanged` still bumps the account generation and clears the route benches (astra D).
- **History:** `getAuthVideoInfo` still sends one TV 7.x /player with the account, during playback, and only when the winner was anonymous. "TV is no longer asked" means for playback selection only.
- **Fixed (draft 3):** `YouTubeMediaItemFormatInfo.sync` marked the sync done before validating it, so one failed TV answer lost the video's history for good (every later ping threw "should be synced first"). Now up to three tries per video.
- **Measured without writing (draft 3):** benchmark builds block only the write; the reads run and `history-ping dry-run video=… auth=y|n tracking=complete|missing` says whether the ping would have carried the account's tracking data. Results in §7.
- **Still unverified:** that YouTube credits the ping. That needs a real write, so it is the owner's check on his own app, not a benchmark.

## 4. What each scenario costs (requests)

| scenario | signed out, before | signed out, design 3 | signed in, before | signed in, design 3 |
|---|---|---|---|---|
| ordinary | 1 | 1 | dead head + 403 + recovery walk; later VISIONOS | **1** |
| made for kids | 10 (+ pre-roll wait) | **2** | head 403, recovery, …, WEB_EMBED last | **2** |
| 18+ embeddable | ~8 | **2** | head, recovery, TV_TIZEN via LOGIN_REQUIRED | **2** |
| 18+ not embeddable | ~10, fails | **2**, fails with the age reason | as above | **2** |
| members only, not a member | ~10 | **4** | ~3 | **3** |
| removed / terminated | 8 | 4 | ~3 | 3 |
| live | 1-2 | same | same | same |
| VISIONOS media 403 | recovery without VISIONOS | suspect last | VISIONOS first again | TV_TIZEN with the account first, VISIONOS last |

## 5. Delivery

- **HLS for VOD is on by default.** A SABR-only answer with an HLS manifest from a source measured to serve it (WEB_EMBED) plays over the manifest:
  - the `/n/` path challenge is solved;
  - it plays behind the readiness gate;
  - Pixel LTE: HLS played at the pre-roll time;
  - rollback: `debug.arc.hls_vod 0`.
- **The "SABR VOD" experiment accepts VISIONOS answers only.** Without a PO token every other client's SABR stops at ~60 s (HANDOFF §28). Before, a SABR-only IOS or ANDROID answer ended the walk as "playable" and died at a minute (audit A2).
- **Not in this change** (astra C refinements included):
  - One `Delivery select(answer)` shared by the walk and the loader (audit C-10).
  - A held progressive fallback, which needs a bounded quality budget and must survive timeout and consensus exits.
  - Today a SABR+progressive answer is still "unplayable" to the walk and playable to the loader.

## 6. Retired

- **Switches:**
  - `setPlannerEnabled` / `debug.arc.planner`;
  - `setAnonTizenAfterRefusal` / `debug.arc.anon_tizen`;
  - `setWebEmbedLast`;
  - `setSkipWebEmbed`;
  - the phone's `setPreferAttestedWebFallback` call. The recovery cursor is now one-shot on the phone lane itself.
- **Memories and budgets:**
  - the head quarantine store (`AuthRouteQuarantineStore`);
  - the 15 s head budget for TVHTML5.
- **Tests** that pinned the phone's ring order (the WEB_EMBED switches, anonymous TV_TIZEN) are gone. The planner and walk tests replace them.
- **Deleted (draft 3, MSC 10f6bf65, 185 lines):** the TV fallback trim (`setSkipTvFallbackClients` and its skips), `setPreferAttestedWebFallback`, the cold-start winner-hint restore, `leadWithAuthenticatedWebClient`. Each was proved unreachable or a no-op in every build (phone release, phone forced-client debug, TV), and a Codex sol review found no behaviour change.
- **Kept because the TV path runs them:** `buildVisitOrder` / `buildRequestVisitOrder` and their helpers, `AUTHENTICATED_HEAD`, `AuthRouteQuarantineBook` (and its unwired store), the no-media streak. They are upstream's ring; deleting them would only make upstream merges conflict. The winner hint is still written; `mActualInfoType` stays as the blame fallback (astra D).

## 7. Acceptance

**Builds:**
- v15 `.check` and `.auth` (arm64 and universal) = the design without the §10 fixes;
- v16 adds the §10 fixes;
- v17 adds the draft-3 fixes (private video, members-only in Spanish, history) and the TTFF work (§8).

**Results, 2026-09-29 morning** (`appbench/results/`; "request N" = the /player request that served; ff = first frame; "visible" = the loading image gone):

| lane × network | run | result |
|---|---|---|
| signed out, LTE (Pixel) | v14 planner on/off, kids | on 15/15 at request 2 via TV_TIZEN, ff 0.9-3.0 s; off: WEB_EMBED last, ff 8.1-10.3 s |
| signed out, LTE | v15 + v16 kids | 10/10 at request 2 via TV_TIZEN, ff median 1.2-1.3 s |
| signed out, LTE | v16 categories (11) | ordinary, Shorts, 4K HDR, embedding disabled: request 1, ff 0.7-1.3 s; 18+ embeddable: request 2 via WEB_EMBED (2.8 s; 6.3 s with a pre-roll wait honoured); 18+ not embeddable: **settled at request 2** in 0.7 s with the age reason; live: request 2 via ANDROID_VR, 1.7 s |
| signed out, LTE | v16 negatives | removed for a violation: request 4 (consensus); private: 8 requests and a **bot-check trip** (fixed in v17); members only: 9 (Spanish wording, fixed in v17); paid movie, music-only: 9 (generic "not available", correct) |
| signed out, LTE | v16 recovery (media 403 injected) | 3/4 recovered through WEB_EMBED; the 4th met a **real bot wall on the carrier IP** (WEB_EMBED, IOS, ANDROID_REEL explicit bot text; VISIONOS and ANDROID_VR still answering). LTE runs stopped there. |
| signed out, Wi-Fi (Pixel) | v17 negatives in one process | private: no trip (`repeated-login unconfirmed reason=one-video`), the next video served at request 1 (0.8 s); members only: **request 4** |
| signed out, Wi-Fi (Pixel) | v17 TTFF on vs off (V8 memo + still lift + embed identity; cold starts, 3 rounds; median picture visible) | on is faster on every kind: kids 803 vs 983 ms and 793 vs 979, 18+ via WEB_EMBED 811 vs 1066, ordinary 547 vs 606. `player-sig` on a cold start 172-199 vs 220-222 ms |
| signed out, Wi-Fi (Mi 8: LineageOS 22.2 / Android 15, Snapdragon 845, a 2018 phone) | the same A/B | **kids slower with it on**: 1382 vs 1217 ms and 1451 vs 1280; 18+ via WEB_EMBED even (1429 vs 1428: the kept embed identity saves ~130 ms, the memo loses it); ordinary 770 vs 780. `player-sig` 736-774 vs 545-556 ms: a cold process's first solve queues behind the memo warm-up (~1 s on the Snapdragon 845: load 103 ms, check 770 ms) in the single V8 runtime, while the plain solve is ~320 ms. Fix in progress (§8 item 2) |
| signed in, Wi-Fi (emulator, the owner's account) | v1 vs v16 ring (16 videos, 45 s) | v1: kids NO-START after ~40 requests each, 128 requests for 7 videos; v16: 13/16 (the 3 others: members, paid movie, music-only, expected), 40 requests for 16, ordinary at request 1, kids and 18+ at request 2 via TV_TIZEN with the account, live at 2 via ANDROID_VR, members settled at 3 |
| signed in, Wi-Fi (emulator) | v16 sustained, 150 s + seek | 4/4 (ordinary, kids, 18+ ×2) |
| signed in, Wi-Fi (Pixel `.auth`) | v17 sustained, 150 s + seek | 4/4: ordinary via VISIONOS at request 1; kids and 18+ (both kinds) via TV_TIZEN with the account at request 2; ff 0.6-0.8 s |
| signed in, Wi-Fi (Pixel) | v17 ring (16 videos, 45 s) | 13/16, **the same decision per video as v16 on the emulator** (40 requests; members settled at 3; paid movie and music-only 9, generic); ff median 0.65 s (emulator 1.81 s). v1 on the emulator: 3/7 in 128 requests |
| signed in, Wi-Fi (Pixel) | v17 history dry-run (keep-process, 7 videos) | 7/7 `auth=y tracking=complete` (VISIONOS winners via the TV 7.x sync, TV_TIZEN winners directly); nothing written |
| signed in, Wi-Fi (Pixel) | ring and history with the account route first (`account_first=1`, settle cells) | 13/16, the same outcomes; 35 requests (kids and 18+ at request 1); history 6/6 complete. The trade-off is §2.1 |
| signed out, Wi-Fi (Mi 8) | v17 negatives in one process | the Pixel's result: private refused with no trip, the next ordinary video at request 1 (0.5 s), members settled at 4 |
| signed out, Wi-Fi (Mi 8) | v17 categories (11) | **the Pixel's decision for every category** (v16 on LTE): 10/11, 15 requests, 18+ not embeddable settled at 2; ff median 0.79 s |
| signed out, Wi-Fi (Mi 8) | v17 first open after install | VISIONOS asked 3.0 s after the tap: the request waited for the player JS (120 ms) and a first V8 run of 2.34 s it does not need (no cipher). The Pixel's ordinary cold starts send it 80-100 ms after the tap. See §9, the first-open gate |
| signed out, Wi-Fi (Pixel, Mi 8) | v18 player-JS gate on vs off, a fresh install's first open (`--pm-clear` before every open, 3 rounds; median first frame) | ordinary: Pixel **0.79 vs 1.93 s**, Mi 8 **0.93 vs 3.70 s**; live: Pixel **1.20 vs 2.51 s**, Mi 8 **1.29 vs 3.98 s**; kids even: Pixel 2.41 vs 2.56 s, Mi 8 4.33 vs 4.24 s (one Mi 8 open 5.04 s) (kids wait for the validated player, which now runs beside the app launch and is slower: 1343 vs 983 ms on the Pixel; then the memo warm-up; fix in progress, §8 item 2) |
| signed out, Wi-Fi (Pixel) | v18 normal cold starts (player cached) | kids 889 and 829 ms, ordinary 590 ms visible: within v17's range; no regression |
| signed out, Wi-Fi (Mi 8) | v18 in-process opens (`--keep-process`, kids and 18+ after a first open), V8 memo on vs off, 2 rounds (n=8 each) | `player-sig` median **8 vs 532 ms**; picture visible median **592 vs 1027 ms**. The memo's everyday gain on a slow phone is ~435 ms an open; its only cost was the cold-start wait (v19) |
| signed in, Wi-Fi (Pixel) | v18 ring + history dry-run | the v17 outcomes (13/16, same requests; the one extra is a cell aborted when the owner opened the shade, re-run); history 4/4 complete |
| signed out, Wi-Fi (Pixel, Mi 8) | **v19** (MSC 8e92399b: a real solve never waits for the memo warm-up) normal cold starts, memo on vs off, 3 rounds; median picture visible, v17 on / **v19 on** / v19 memo off | Pixel kids 803 / **745** / 873 and 793 / **732** / 885 ms, `player-sig` ~190 / **~93** / ~215 (the solve runs on the player code the warm-up keeps in V8); ordinary 547 / 529 / 526. Mi 8 kids 1382 / **1162** / 1200 and 1451 / **1198** / 1267, 18+ 1429 / **1204** / 1308, `player-sig` ~760 / **~530-570** / ~560-600: the slow-phone regression is gone. Watch: Mi 8 ordinary 770 / 822 / 744 (n=3, overlapping ranges; answers also came later) |
| signed out, Wi-Fi (Pixel, Mi 8) | v19 switching videos (in-process) and a fresh install's kids open | in-process `player-sig` median 2 ms (Pixel) and 10 ms (Mi 8), visible 743 and 710 ms; fresh-install kids 2.0-2.1 s (Pixel, v18 2.1-3.0) and 3.9-4.2 s (Mi 8, v18 4.1-5.0) |

**Walk replay (no network, MSC a45493e4 / main f086a4c1):** `VideoInfoReplayTest` replays YouTube's recorded answers from these runs through the real walk: 17 cases (29 walks, 85 answers; kids, both 18+ kinds, live, removed, private → ordinary → members in one process, paid/music-only, signed-in ordinary/kids/18+/live/members, the LTE bot wall across four processes). It asserts the same clients in the same order, the same winner or refusal and the same bot-check trip. A client the device never asked fails with "needs a device answer for X". Replaying all 141 v16/v17 opens found no disagreement except the two deliberate v17 changes (excluded with reasons) and opens whose process started with saved bot-wall state the log does not show. Mutations of the planner fail 5-9 cases. Debug and benchmark builds now log `player-playability` (reason, subreason, age-gate marker, live signals) so new fixtures are exact: `tools/netbench/appbench/replay_fixtures.py`. A planner change runs this first; devices then measure only time and playback.

**Block on:**
- a reproducible playback regression;
- a shortened ad wait;
- a reselected benched route;
- a new bot challenge;
- a request count above §4;
- any `account-write` other than `blocked`.

## 8. TTFF (both lanes)

Numbers are Pixel LTE, cold share-link opens (`ttff-analysis.md`).

| path | first frame (median / p90) | picture visible |
|---|---|---|
| signed out, ordinary (VISIONOS) | 928 / 1367 (n=6) | 1120 / 1608 |
| signed out, kids (VISIONOS → TV_TIZEN) | 1260 / 2098 (n=22) | 1404 / 2312 |
| signed out, 18+ via WEB_EMBED | 1284 (n=2) | 1482 |
| WEB_EMBED with a pre-roll (the wait honoured) | 6156 / 6415 (n=9) | 6407 / 6616 |
| signed in, before (emulator) | 3175 (the dead head probe) | — |

What design 3 and the work in progress remove:
1. **Signed in, the dead head:** ~0.7-1.3 s on the Pixel for every open that probed it (estimate). Done.
2. **The signature/n solve re-evaluates the 3.7 MB player on every call**, 206-240 ms on every TV_TIZEN, WEB_EMBED and MWEB answer.
   - Fix: memoise the solver per player URL inside the kept-alive V8 runtime; pre-load it at warm-up; cross-check it once per player against the full path, falling back on any mismatch. Rollback: `debug.arc.v8_memo 0`.
   - Expected first frame: −100 ms on kids (median) to −200 ms (TV_TIZEN / WEB_EMBED heads).
   - **On a slow phone it cost a cold start ~200 ms** (Mi 8, §7): the first solve waited for the warm-up's memo load and check. **Fixed in v19 (MSC 8e92399b):** the runtime is a lane where solves go before any warm-up step not yet started, the warm-up is split into one-evaluation steps held back until the first solve, and full solves run on the player code kept in V8. Pixel cold kids `player-sig` ~190 → ~93 ms; Mi 8 back to the memo-off level; in-process 2-10 ms.
   - **Merged (MSC 3a578d4c).** Fails closed: a player's kept solvers answer nothing until the warm-up has checked them against the full path; any mismatch or error sends that player back to today's path for the process. Offline, 120 random challenge sets on three real players matched fresh evaluations exactly, 0.4 ms against 35-43 ms. v16 Pixel baseline: `player-sig` 228 ms on a kids open.
3. **The loading still hides a decoded frame for 205 ms (median) on every open.** Lift it at READY once this open's first frame rendered (−128 ms). Rollback: `debug.arc.still_lift texture`. **Merged (main 0c4c2f01)**; a stale frame is ruled out by a marker message sent through the player after `prepare()`.
4. **WEB_EMBED's embed page (220-390 ms) is fetched once per process** although the identity is valid for 6 h. Persist it. Rollback: `debug.arc.embed_persist 0`. **Merged (MSC a5178dd7)**; a refused (152) identity is dropped from disk too.
5. **The googlevideo connection is on the critical path in 43% of opens** (one edge group: 483 ms median connect).
   - Step 1, Cronet connect metrics on the `warm` line: **merged (MSC b1f2487d)**.
   - Step 2 (warm earlier or route around it) waits for that data.
6. **v21, on branch `router/v21` (the r11 analysis's §4.2-4.4; device round pending, `v21-ttff.sh`):**
   - **The BotGuard WebView warm-up waits for the open's first frame** when an open is in flight (a share-link start, a tap before it ran), or its failure; the 4 s fallback from process start stays. The WEB subtitle-enrichment `/player`, the one request that would build the WebView on its own, waits with it while no token session exists (`player-enrichment hold … / release … heldMs=`) and then leaves with `pot=y` as before; a warm session is never held. Expected: Mi 8 ordinary cold answer → first media request ~280 → ~110 ms. Rollback `debug.arc.token_warmup=frame` (static switch `MobileMainApplication.TOKEN_WARMUP_AFTER_OPEN`). App: `OpenSettle` (NetPath's tap/open start an open, its first-frame/error settle it).
   - **The loading still lifts at this open's first rendered frame** (`picture-visible … lift=frame`), before READY, under the same per-open marker (item 3). Known limit, shared with the READY lift since v17 (the Codex sol review of v21): the texture callback's time says a buffer was latched after this open's first frame was released, not which one, so a buffer the previous stream queued and the view had not drawn could show for a frame. The buffer's own timestamp cannot be read at that callback (it runs before the RenderThread latches: `SurfaceTexture.getTimestamp` reports the previous buffer, 0 on the emulator's first open); counting the renderer's releases (a `VideoFrameMetadataListener`) against latches is the follow-up. Expected: first frame → picture visible 67-74 → ~0-15 ms (Pixel), 20-48 → ~0-15 (Mi 8). Rollback `debug.arc.still_lift=ready` (v20), `texture` (before v17); static switch `SwitchExperiments.STILL_LIFT_DEFAULT`.
   - **A SABR-only WEB_EMBED answer re-rolls the embed identity**: it plays over HLS as before, then the identity (both copies) is dropped and a new one fetched off the walk (`embed-identity reroll reason=sabr-only`, `reroll-fetched ok=`), at most once per 6 h: the budget goes to disk before the new pair exists (a pair-less mark), survives a 152 refetch and a restart, and an identity a re-roll fetched is never re-rolled itself. Only the identity that got the answer is re-rolled (the request's visitor must still be the cached one). The 152 path is unchanged. Rollback `debug.arc.embed_reroll=0`.
   - **v21 on the emulator (decisions only, no timing):** a share-link cold start logs `launch token-warmup hold reason=open`, then `start after=open` at the open's first frame; kids opens hold the enrichment (`player-enrichment hold … reason=web-pot-cold`, released at the first frame, `heldMs` 820-940) and it leaves with `pot=y` and answers 200. A recovery's WEB_EMBED answer from a freshly fetched identity was SABR-only: `embed-identity reroll reason=sabr-only`, `reroll-fetched ok=y` (166 ms, off the walk), `player-sig … hlsN=folded` and `hls-vod-n … shared=y`; the next process restored the new identity and its WEB_EMBED answers were DASH (4 of 4 18+ opens, `prepare type=dash-mpd`). The emulator reaches READY before the texture shows the first frame, so its still lifts on the texture path (`lift=texture`, 5-21 ms after the first frame): the frame lift is for the phones to measure.
   - **The HLS-for-VOD manifest's `/n/` challenge rides the answer's bulk solve** (`player-sig … hlsN=folded`, `hls-vod-n … shared=y`) instead of a second V8 run (65-195 ms on the Pixel); a bulk answer that does not carry it falls back to the separate solve. Rollback `debug.arc.hls_n_fold=0`.

Not supported by the data:
- hedged or parallel /player;
- lazy per-itag solving;
- a smaller first segment.

## 9. Next (not in this change)

**Done since draft 2** (removed from this list):
- history sync validation, with three tries (§3);
- a private video no longer reads as a bot check (§2.2);
- members-only in Spanish (§2.2);
- PO token v4: merged behind `debug.arc.pot_gen v4`, untested on a device; its 10 s bound on the default mint wait is on for everyone.

**Open:**
- **The one-minute wall (r11 at home, 2026-09-29).** Two walled visitors (emulators 5592, 5588): VISIONOS walled 6/6, ANDROID_VR 2/2; TV_TIZEN anonymous on the same visitor OK 2/2 (DASH), ANDROID_REEL OK 2/2 (360p progressive only), WEB_EMBED OK 2/2 (its own identity), IOS no URLs. P(wall | fresh VISIONOS visitor) = 3/14 at home. On stream position, not elapsed time (a jump to 73.7 s was refused at the first request past 60 s). No expiry after 33 min. **On branch `router/v22`:** §2.3's wall bullet. Why v21's `reason=media-403 scope=video` bench did not hold VISIONOS back: that record (BotWallBook) only ever benches the account route; the anonymous sources were only demoted as the latest suspect. Replay: every seed case unchanged with v22's switches on (the VOD order applies to recoveries and walled visitors only: on first opens it would have added an anonymous TV_TIZEN ask to six refusal walks); `replay_fixtures.py` now carries each media 403's request starts (`media403`, exact from v22's `playback-media403` line, rebuilt from the episode's chunk lines before).
  **v22 on the real walled visitors** (5592 `5b2b1c91eb`, 5588 `348c008291`; v22 installed over v19p, data kept): MeJVWBSsPAY VISIONOS walled (`signature=wall`), WEB_EMBED refused, TV_TIZEN served past the wall, one recovery, no cap (RECOVERED@129 s / @120 s); the re-roll minted `749b8b6641` / `eefb190e60` at the next process's first web-session use, and VISIONOS on it played two new videos past 60 s on each (147-162 s); /next kept `5b2b1c91eb` / `348c008291` (browse identity untouched); one re-roll each, budget left 1. Jump (YQHsXMglC9A on the walled visitor, re-roll and memory off for the cell): the 403 at 70001 with nothing served is `ambiguous`, the recovery still went to WEB_EMBED, RECOVERED. Live 5yx6BWlEVcY: VISIONOS then ANDROID_VR (dash), as before. Rollback switches on: the v21 alternation to the cap, reproduced. **The stall is still visible:** Media3 surfaces the 403 as a fatal source error, sometimes with 10-25 s still buffered (5592: position 49.9 s; 5588: 34.8 s), and the reload restarts from the previous sync point (48.0 s for 49.9 s); error to next first frame 1.3-11.5 s on emulators (a TV_TIZEN signature solve, a 2.3 s manifest parse, WEB_EMBED's 5 s pre-roll wait). A spare identity would not shorten it; keeping the buffer through a source swap would (next bullet).
  **v22b: the re-roll mints in the background.** v22 minted the fresh visitor inside the next web-session request's session build, which made that open's first request wait (+1.7 s and +3.3 s to the first frame on 5592 and 5588, in the next process). v22b mints it (the visitor_id API) on a background thread as soon as the wall is confirmed, while the recovered video plays; when it lands the re-roll is complete, the visitor is kept (persisted) and the walled web session is retired, so the next open - same process or after a restart - peeks the kept visitor at once. A failed mint leaves the re-roll pending for the next session build, which mints it as in v22 (and refunds the budget if that fails too); with keeping off the v22 path runs. Log: `playback-identity reroll … mint=background`. On emulator-5590 (synthetic wall): minted 39 ms after the wall; the next process's first two opens asked VISIONOS with the kept visitor 1-2 ms after the plan (`visitorSource=kept` on the session built after the first frame), first frames +3.0 s and +1.9 s (a cold open without any re-roll: +3.0 s), both played past 60 s.
- **The stall at the wall, and the rewind: done on branch `fix/wall-seamless` (issue #84, HANDOFF §34): the held request is re-pointed at the recovery answer's URL for the same file (`MediaRepoint`), no reload; the fallback reload resumes exactly.** Original analysis: v22 ends the "Unknown source error" but the recovery is still visible. Per wall on the emulators, error to the recovered first frame: 11.5 s (5592, real), 4.2 s (5588, real), 5.7 and 5.6 s (the real jump cells), 3.2 and 1.3 s (synthetic). Root causes, in order of size: (1) **Media3 treats googlevideo's 403 as fatal**: the ChunkSource's load error ends the playback with media still buffered - 10 s at 5592 (the error at position 49.9 s, loaded to 60 s), 25 s at 5588 (34.8 s) - and the recovery rebuilds the source from the position, snapping back to the previous sync point (49.9 s resumed at 48.0 s; 60.0 s at 54.1 s): a 2-6 s rewind the user sees again; (2) the new source's manifest parse (2.3 s on the emulator, `source-build … parseMs=2343`); (3) TV_TIZEN's signature/n solve and its `player-transform` (1.1 s); (4) WEB_EMBED's pre-roll wait (`readiness-wait ms≈4.9 s`, the ad-wait honour rule). What would remove most of it: act on the FIRST past-60 s 403 while the buffer still plays - a LoadErrorHandlingPolicy that does not fail the playback on a 403 of a chunk starting at or past the wall, and a source swap (the recovery walk's answer, same itags, prepared in the background) that joins at the buffered end instead of a reload from the position; then neither the stall nor the rewind happens as long as the recovery lands within the buffer (10-25 s here). Needs Media3 work (a seamless swap between two DASH sources of one video: ConcatenatingMediaSource2 or a custom MediaSource that re-points its chunk URLs) and its own device round; a pool of spare identities would add nothing (no mint is on this path).
- **The first open after a new YouTube player version: done for VISIONOS and ANDROID_VR** (v18 = main 87d65cd9 / MSC 993ba593, rollback `debug.arc.player_js_gate 0`; results §7). Left: kids and every source with something to solve still wait for the validated player, and gain nothing from the gate (even on both phones; the validation shares the CPU with the launch, then the memo warm-up goes first). Being fixed with §8 item 2.
- **Recovery re-asks a source that just refused the same video.** v16 LTE, the kids video: VISIONOS refused it (UNPLAYABLE), TV_TIZEN served it, the injected 403 reloaded it, and the recovery plan put VISIONOS first again: one wasted request (~90 ms) before WEB_EMBED served. **On branch `router/v20` (v20 builds, not merged):** every planned walk records which source refused which video (`RecentRefusals`: per video, source and identity, 30 min, 16 videos, per process, cleared on an account change behind a generation guard, dropped when that source serves the video). A recovery walk asks those sources after everything else, the suspect included: kept, not dropped (signed out that includes an anonymous TV_TIZEN that refused, which the refusal rule no longer re-admits early; signed in, the sign-in rule no longer moves a refusing account route back to next). Counted: UNPLAYABLE, a sign-in request or an age gate, but not a bot check, a reload-page answer, a live signal or WEB_EMBED's stale identity "152"; ERROR, SABR-only answers and timeouts are not (a Codex sol review, reconciled on the branch). The replay case `lte-bot-wall-after-a-kids-403` carries the change (`changed`: the recovery asks WEB_EMBED alone). Log: `player-ring recovery-refused video=… suspect=… refusedAgoMs={VISIONOS=…} first=…`.
- **Kids channel memory.** A made-for-kids refusal is a property of the channel. Remembering the channel would send its next video to TV_TIZEN first (signed out, one request and ~0.3 s less). **On branch `router/v20` (MSC `KidsChannelMemory`, app `MediaServiceManager.noteChannel`), not device-checked yet.** Where the channel comes from:
  - **the proof** (VISIONOS or ANDROID_VR refused, TV_TIZEN served): the answers. Every recorded harness answer for the kids corpus carries `videoDetails.channelId`, refusals included (VISIONOS and ANDROID_VR UNPLAYABLE, TV_TIZEN OK; 3 channels × Wi-Fi and LTE, `harness/results/sweep*`), so the proof never waits for /next. The device appbench logs had no field for it; v20 logs it: `kids-channel remember … src=answer answerChannel=<tag> refusalChannel=<tag>` and a `channel=<tag>` at the end of each `player-playability` line (debug and benchmark builds);
  - **the hint** (before the next video's first request): only the app, from the card tapped, the touch preload, the next-video slot and its prefetch (`kids-channel named video=… channel=…`). A share-link open (every appbench cell) names no channel before its walk, so it is never hinted; the next-video prefetch near the end of a kids video is, when the next video is of the same channel.
  - **v20 on the emulator (2026-09-29, both lanes):** the proof works from the answers (`answerChannel` = `refusalChannel` in every proof; TV_TIZEN at request 2) and the next-video slot names its channel, but YouTube's autoplay-next was another channel each time (`e_04ZrNroTo` → `extcXqu8Ki4`), so no hint ran. The hint needs an in-app open of a second video of the same channel: `inapp-v21.sh kids` (the launch folder) taps two cards on the channel page. **v21 on the emulator (emulator-5584, signed out):** the channel page's cards carry no channel of their own, so the first try named the channel only after the walk (from /next) and nothing was hinted; `ChannelPresenter` now names the page's channel on a tap (the card's own `channelId` untouched). Then: card 1 proves CoComelon (VISIONOS refused, TV_TIZEN served), card 2 logs `kids-channel hint … order=[TV_TIZEN, …]` and is served at **one request** (`hint-served … hintsLeft=3`), against VISIONOS then TV_TIZEN with `debug.arc.kids_channel=0`.
  - **The analysis's two review points (v21):** hints during a bot-wall *suspicion* were already skipped signed out (v20, `hint-skip reason=suspicion`; signed in the account route carries the account, which the anonymous wall says nothing about). The served video's channel is now checked against the note: a hinted TV_TIZEN whose answer names another channel spends none of the remembered channel's hints and proves nothing (`kids-channel hint-mismatch`). The proof keeps the answer's own channel over the note (the route pattern is the point: a music "- Topic" channel whose videos VISIONOS refuses and TV_TIZEN serves is worth the same hint).
- **A kids recovery asked six sources that never serve kids videos first** (v20, emulator, both lanes: TV_TIZEN benched, WEB_EMBED the suspect, then ANDROID_VR refused and IOS, ANDROID_REEL, MWEB, WEB, WEB_SAFARI answered SABR-only; WEB_EMBED served again at the seventh request, 8.3 s to the first frame). **On branch `router/v21`:** a recovery of a video VISIONOS or ANDROID_VR refused as made for kids in the last 30 min (`RecentRefusals`, the refusal's kind recorded) asks only TV_TIZEN and WEB_EMBED before the suspect, and everything else after it (kept, not dropped); `player-ring plan … made-for-kids`. Only when the suspect is TV_TIZEN or WEB_EMBED: a video another source served is not a kids video, whatever VISIONOS said (music-only and paid videos get the same refusal; the Codex sol review). Rollback `debug.arc.recovery_kids=0`. Replay case `emu-kids-recovery-after-a-bench` (`changed`: WEB_EMBED alone). **v21 on the emulator** (the same cell, `poison_once_itag=any`): the recovery after WEB_EMBED's 403 with TV_TIZEN benched planned `made-for-kids order=[WEB_EMBED, …]` and was served at **one request**.
- **The live card (r11 analysis §4.1(ii)). On branch `router/v21`:** a video the app opens from an item that says live (the card's badge, the next-video slot) asks ANDROID_VR first and VISIONOS second (`player-ring plan … live-card`). A stale flag costs one request: ANDROID_VR's answer to a video that is not live is set aside (`live-card stale`), the lane goes on from VISIONOS, and the answer set aside plays at ANDROID_VR's own turn in the lane (`live-card stale-served`), so the walk never asks more than without the flag; it also beats anything the walk learns after it (a challenge, a dead link), as a serve does. Not in a recovery walk. VIEW intents name no live flag: `inapp-v21.sh live` taps a live search result on the emulator. Rollback `debug.arc.live_card=0`. **v21 on the emulator:** a lofi 24/7 stream tapped from search: `live-card named … live=y`, plan `live-card order=[ANDROID_VR, …]`, **one request** (ANDROID_VR, dash=y), against VISIONOS (held HLS) then ANDROID_VR with the flag off.
- **The first open after a new YouTube player version, off the tap path (considered for v21, not done).** The r11 Pixel LTE case (`Fc5aA77Nlf0`, kids via TV_TIZEN: `player-js fetch kb=2156 ms=244 memo=n`, `player-sig … ms=2567`, first frame +4372) is a new player met inside the open. What the app has: the player URL comes from the app info (`www.youtube.com/tv`), persisted and reused for 10 h (`AppServiceIntCached`), so between refreshes the app never sees a new version at all; launcher starts already refresh an expired app info and stage its player off the tap path (SessionWarmup after the first feed paint); the case left is a share-link cold start right after the 10 h expiry, or a player the persisted app info names but the disk no longer caches. The only signal is that page: one request per check (plus the 2.1 MB player and ~0.3-1 s of V8 when it changed), and the app info also carries the visitor the BotGuard session adopts, so refreshing it early changes the anonymous identity and has to happen before that session is minted. A bounded version (at launch, when the persisted app info is older than ~8 h, before the token warm-up, then stage at background priority) is possible but touches visitor identity and launch CPU (§4.6's risk): not safe to do blind; it needs its own measurement first (`debug.arc.player_prefetch`).
- **The episode reducer (astra F).** One walk object owning eligibility, attempted routes, held deliveries and the remaining budget, fed by outcomes on separate axes:
  - content permission;
  - transport;
  - challenge;
  - transformation;
  - delivery.
  It replaces "sort, then insert" and the four guest-challenge memories (audit C-4) and the separate recovery counters (C-18). Design 3 moved the order and the stop rules into the planner; the reducer is the next step.
- **The account (astra B):**
  - Supervised and unverified accounts get VISIONOS anonymously first, as they did while both heads were quarantined. Measure account-policy refusals before changing it.
  - Premium formats need TV_TIZEN first (§2.1).
  - An account-wide challenge or rate limit is not represented; route benches are per attachment.
  - A failed signature/n transformation must not bench the account route: record it apart.
- **Classification (astra D):** `isAgeRestricted` includes every LOGIN_REQUIRED.
- **ACTIVE-role leaks** from downloads, reminders, cast and the info menu (C-13).
- **googlevideo warm-up, step 2** (§8 item 5), from the `warm` line's connect metrics.

## 10. The astra review, reconciled

Source: `review/codex-lanes-astra.md`.

**Applied:**
- The age-gate stop counts any non-serving answer from the capable sources, never a timeout (C).
- Signed-in consensus needs the confirmed account witness (C).
- A TV_TIZEN suspect is asked last and not re-inserted by the refusal rule (C).
- TV_TIZEN at #2 is stated as conditional on health (C).
- `setWebEmbedLast`'s caller was removed together with the switch (D).
- `mActualInfoType` is kept for blame (D).
- The account-change protections are kept (D).
- History is described as "unverified", not "unchanged" (B).
- The evidence limits are stated (A).

**Deferred with a reason (§9):**
- the entitlement axes and the episode reducer (F);
- the progressive fallback;
- history retry;
- account-wide backoff.

**Rejected:** "profile local transform cost before changing it" (E4). The TTFF breakdown already profiled it on the Pixel:
- 130 `v8-run` lines, `stdinKb` 3783-3790 on every one;
- a pre-V8 phase of 99 ms and a solve of 118 ms;
- flat across 2 and 30 challenges.

The memo keeps a fail-closed cross-check anyway.
