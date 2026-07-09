# NewTube launch content — posting index

This folder holds the finished, per-channel launch copy. Each file is written for **one**
venue and is deliberately distinct in structure, examples, and phrasing (the anti-slop rule —
a rival got roasted on r/fossdroid for reading AI-generated, so nothing here shares boilerplate).

> **Before posting anything: fill the URL tokens.** Every file uses literal placeholders because
> the repo isn't pushed yet. Once it's live, find-and-replace across this folder:
> - `{{REPO_URL}}` → `https://github.com/OWNER/newtube` (real owner/name)
> - `{{RELEASES_URL}}` → `https://github.com/OWNER/newtube/releases/latest`
> - `{{SITE_URL}}` → `https://newtube.app`
>
> Sanity check after replacing: `grep -rn "{{" docs/gtm/content/` should return nothing.
> Never link a bare `.apk` where a store/Release is expected — always the **Releases page**.

Sequence and timing below come straight from
[`../engagement-plan.md`](../engagement-plan.md) §4 (timeline) and §3 (channel rules). Post in
this order: **stores first** (so every later post links a real store/Release, not an APK), then
**FOSS-native audiences**, then broader/showcase/evergreen venues.

## Posting schedule

| When | File | Channel | Priority | Rule reminders (obey per venue) |
|------|------|---------|----------|---------------------------------|
| **Week 0 — pre-launch (do first)** | [`listing-fdroid-izzyondroid.md`](listing-fdroid-izzyondroid.md) | IzzyOnDroid inclusion **+** F-Droid RFP | P0 / P2 | FOSS/MIT ✓. Tag a GitHub Release with the APK attached; put Fastlane metadata (icon, screenshots, short+full description) in the repo. F-Droid RFP needs a reproducible build eventually — open it early (long review lead). This gating step gives every later post a store link + a trust anchor. |
| **Week 1 — launch day** | [`reddit-fossdroid.md`](reddit-fossdroid.md) | r/fossdroid | **P0** | State the license (MIT) + a free download link. **Real screenshots embedded in the post** (not deferred). Lead with "not a NewPipe fork — it's the SmartTube engine." Disclose we're the makers. No AI-slop, no memes. |
| **Week 1 — launch day, ~7–9am ET weekday** | [`show-hn.md`](show-hn.md) | Hacker News — Show HN | P1 | Real, usable thing; link the repo/Release; no marketing fluff. Founder camps the comments all day. Be candid about pre-1.0 limitations. |
| **Week 1 — launch day** | [`lemmy.md`](lemmy.md) | Lemmy: `!android` primary → native cross-post to `!fossdroid` / `!opensource` / `!degoogle` / `!privacy` | P2 | Disclose maker in the first line. Link the GitHub Release. Reworded, **not identical** to the r/fossdroid post. **No first-party `youtube.com` links** in `!degoogle`. Attach 3–4 fresh screenshots. |
| **Week 1–2 — direct replies** | *(no file — drafts live in [`../engagement-plan.md`](../engagement-plan.md) §2)* | GitHub/Reddit open threads (SmartTube #4821/#5113, r/degoogle "which client", etc.) | P1→P3 | Hand-write **every** reply to that person's exact words; never reuse wording. Disclose maker each time. One reply then step back — no necro-spam. |
| **Week 2 — showcase venues** | [`reddit-degoogle.md`](reddit-degoogle.md) | r/degoogle — pinned **Degoogle Showcase megathread** (Part A) + organic replies (Part B) | P0 organic / P1 megathread | Promo goes **only** in the Showcase megathread (standalone posts get removed). Disclose maker. **No first-party YouTube links.** Link the GitHub Release. Lead with no-account → optional-login → no-tracking. |
| **Week 2 — showcase venues** | [`reddit-androidapps.md`](reddit-androidapps.md) | r/droidappshowcase (reaches r/androidapps ~562k) | P1 | r/androidapps **bans** self-promo — this is the routed venue. `[Dev]` flair (required). Approved sources only (GitHub/F-Droid), **no raw APK links**. Upload the screenshot gallery. Disclose maker in line 1. |
| **Week 2 — showcase venues** | [`xda-thread.md`](xda-thread.md) | XDA — Android Apps forum | P2 | Long-lived `[APP]` thread + an ongoing changelog thread; post per release. Good long-tail SEO. Tag is `[5.0+]` (stmobile minSdk 21). Attach real screenshots. |
| **Ongoing — evergreen / opportunistic** | [`article-seo.md`](article-seo.md) | SEO blog on `{{SITE_URL}}/blog`; syndicate to Dev.to / Medium | — | Self-canonical on the site; point any syndicated copy's `<link rel="canonical">` back to the site version so ranking signal accrues to the primary. Publish around launch, keep it live evergreen. |
| **Ongoing — P3** | [`listing-alternativeto.md`](listing-alternativeto.md) | AlternativeTo.net | P3 | Community listing; mark as an alternative to NewPipe / SmartTube / ReVanced / LibreTube / YouTube. Keep it factual — mods remove hype/unverifiable claims. Where the form wants a download, use the Releases page. |
| **Ongoing — P3, when polished** | [`product-hunt.md`](product-hunt.md) | Product Hunt | P3 | One launch/day; best window **Tue–Thu ~00:01 PT**. Ship with a demo GIF + real screenshots (not the CSS mockups). Post the maker's first comment the second it goes live. Rally 8–10 real early supporters. Don't lead the whole campaign here. |

## Rules that apply to *every* file

Pulled from [`../engagement-plan.md`](../engagement-plan.md) §1 — non-negotiable:

1. **Always disclose we're the makers** — every post, every reply. No "I found this cool app."
2. **Never post identical copy** across venues. Each file is already distinct; keep it that way if you edit.
3. **Don't badmouth** NewPipe / LibreTube / ReVanced / Grayjay / SkyTube — acknowledge them fairly.
4. **Link the GitHub Release, not a bare APK**, wherever a store/download is expected.
5. **Lead with the differentiator:** SmartTube-engine lineage (not a NewPipe fork) + real Google
   login without microG + touch-first phone UI. Screenshots up top where the venue allows.
6. **Stay honest on the trust angle:** MIT, open-source, **built in CI from tagged source and
   signed** today — reproducible builds are **in progress**, not shipped. Do not claim otherwise.
   Never say "unbreakable"/"invincible" — "resilient, fast to update" is the honest framing.

## Not yet drafted (tracked in the plan)

- **r/opensource** `Promotional`-flaired engineering-story post (P2, Week 2) — no file yet; brief
  in [`../engagement-plan.md`](../engagement-plan.md) §3.
- **Direct-reply drafts** for the §2 open-thread targets live inline in the engagement plan, not
  as files here — hand-write each fresh at posting time.
