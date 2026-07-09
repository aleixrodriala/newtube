# GitHub Demand Signals — "NewTube" (SmartTube for phones)

Research date: 2026-07-01. Method: `gh` CLI (issues/repos/GraphQL) + WebFetch of
github.com pages. Every quote, URL, count and date below was pulled live and
verified — nothing is inferred or fabricated. Reaction counts are exact at time
of capture. All target repos: `yuliskov/SmartTube` (30.8k★, the TV app we fork),
plus NewPipe / LibreTube / Grayjay / ReVanced and competitor forks.

---

## Verdict: how strong is the demand?

**Moderate-to-strong, and qualitatively excellent — but modest in raw GitHub
vote counts.** Weigh it like this:

- **Recurring over 5+ years.** People have asked SmartTube for a phone/touch/
  tablet UI repeatedly from 2020 (#236) through 2026 (#5113). It is not a
  one-off; it is a persistent, restated wish.
- **Vote counts are modest, and here's why.** The single strongest issue has 12
  upvotes; the oldest has 13 reactions. That looks small, but it is *suppressed*:
  the maintainer's hard "No" (SmartTube's README literally lists "Not supported
  on phones and tablets" as a limitation) means most such issues are closed as
  not-planned or auto-closed as stale, so they never accumulate votes. Users are
  told to go use NewPipe/LibreTube/Grayjay/ReVanced instead.
- **The decisive signal is structural, not a vote count:** a *native iOS clone*,
  `milika/SmartTubeIOS`, explicitly "Inspired by the original SmartTube Android
  app," hit **196 stars, 17 forks and 71 open issues in ~2 months** (created
  2026-04-25, active daily). Someone built exactly our product for Apple and it
  is pulling an engaged userbase — which both proves the appetite and shows the
  **Android-phone niche is still unfilled**.
- **The demand is well-articulated and feature-matched to us.** Users don't just
  want "a YouTube app on my phone" — they want *SmartTube specifically*, and they
  reject the alternatives for the exact reasons that are our differentiators
  (real Google login, polish, the SmartTube engine): "I know the alternatives, i
  don't want the alternatives." / "not real login like SmartTube (which is much
  easier)."

**Bottom line:** demand is real, durable, and precisely shaped like our product.
It is not a viral groundswell on GitHub vote-counts alone, but the combination of
(a) a 5-year drumbeat of requests, (b) an actively-maintained iOS twin with a
fast-growing following, and (c) users explicitly rejecting NewPipe/ReVanced in
favor of "SmartTube but on my phone" is a strong green light. Be honest in GTM
copy: the loudest proof is the iOS clone's traction, not a giant upvote pile.

---

## Ranked demand signals

### Tier 1 — direct "SmartTube on my phone" requests with engagement

**1. SmartTube #3441 — "Q: Mobile App UI"** · closed (auto-stale) · 2024-04-17 ·
**12 👍 (12 total reactions)** · 4 comments
https://github.com/yuliskov/SmartTube/issues/3441
> "smarttube is hands down my favourite youtube client. in fact i do like it so
> much, i'd love to use it on my phone and tablet too. newpipe is nice but is
> (purposefully) separated from all Google stuff, so no history or casting;
> revanced is a giant hack requiring one to run an extra microg instance… and
> gets bricked by Google regularly. smarttube just works brilliantly…"
> — @xtools-at (OP)

Comments pile on:
> "I'm surprised to see such a lack of interest from the community for a
> phone/tablet version of this app that works amazingly on my smart TV."
> — @stymbhrdwj
> "I add myself to the group of SmartTube happy users in tablet/phones… still my
> favorite YT client around." — @WolfganP

This is the single best thread: high upvotes, our exact positioning (SmartTube's
Google-login + polish vs. NewPipe's no-login and ReVanced's fragility).

**2. SmartTube #236 — "Add touch screen controls and screen scaling."** · closed ·
2020-12-05 · **11 👍 / 13 total reactions** · 18 comments
https://github.com/yuliskov/SmartTube/issues/236
> "I would like to see support for devices without a control panel, with a touch
> screen. On tablets and smartphones." — @UNOTEHNIKS
> "…you need either root, or dances with Google services. I have never met such a
> problem-free and convenient client. Therefore, I really want to see it on my
> devices." — @UNOTEHNIKS (+4 👍)
> "Now that Vanced is ending their development, it would be nice to get this more
> finger friendly for mobile phones…" — @rsunde (+2 👍)

The oldest and most-reacted phone request. Shows the wish predates and outlasts
YouTube Vanced.

**3. NewPipe #10791 — "Integrate with SmartTube"** · open · 2024-01-27 ·
**11 👍** · 28 comments
https://github.com/TeamNewPipe/NewPipe/issues/10791
Cross-community proof the two userbases overlap. Primarily about pairing/casting
SmartTube(TV)↔NewPipe(phone), so it's adjacent rather than a straight "give me
SmartTube on my phone," but 11 upvotes in the NewPipe repo shows NewPipe users
actively want SmartTube in their phone workflow.

### Tier 2 — clear individual requests (low/zero votes, but explicit)

**4. SmartTube #4821 — "Mobile optimized UI"** · open · 2025-08-06 · 3 comments
https://github.com/yuliskov/SmartTube/issues/4821
> "Just wondering if you could also offer a UI which is suitable to be used on
> mobile phones. The current UI is more optimized for large screens." — @boustanihani
OP after being pointed to alternatives:
> "Grayjay, Newpipe and Libretube only support importing stuff, but not real
> login like SmartTube (which is much easier)… SmartTube is working really great,
> the only feature I am missing is mobile devices." — @boustanihani
Community reply confirms the gap: *"It's on the README, mobile UI is not
supported."* — this is the canonical "maintainer says no" that suppresses votes.

**5. SmartTube #4427 — "Android Phone & Chrome Extension"** · closed not-planned ·
2025-03-03 · 6 comments
https://github.com/yuliskov/SmartTube/issues/4427
> "Are there any plans of making this App available for Android Phones… SmartTube
> for Android Phone (yes there is ReVanced,.. but it's not SmartTube)." — @BourgeoisDirk
> "I know the alternatives, i don't want the alternatives.. I wish for this App
> but as a Phone version." — @BourgeoisDirk
Closed with: *"Not supported on phones and tablets. Closing this issue."*

**6. SmartTube #4308 — "…Is it possible to support the Android mobile version?"** ·
closed · 2025-01-16
https://github.com/yuliskov/SmartTube/issues/4308
> "I installed it on my phone, and it works fine, but the framework, fonts, and
> interface don't automatically adapt to the mobile experience." — @maojianyou

**7. SmartTube #5113 — "Big feedback after using it on a android tablet pc"** ·
open · 2025-11-21
https://github.com/yuliskov/SmartTube/issues/5113
> "SmartTube is the best app available with almost full functionality… this app
> has very little touchscreen control for rewinding." — @Unamelable
Power-user actively running SmartTube on a tablet, blocked only by touch UX.

**8. SmartTube #645 — "Mouse/Touch Support on video seekbar"** · closed · 2021-06-02
https://github.com/yuliskov/SmartTube/issues/645
> "I am using smarttube on my phone. It's working perfectly fine but there is no
> way I can seek… Touching the screen on seekbar causes video to pause." — @rezpower

**9. SmartTube #1230 — "Add support for phones… (Low resolution devices.)"** ·
closed · 2022-01-18 · 1 👍
https://github.com/yuliskov/SmartTube/issues/1230
> "I've been using SmartTube on my folder phone… it works really well with the
> keypad." — @Dr-Sauce

**10. Supporting/orientation requests (weaker, listed for completeness):**
- #4318 "orientation and landscape" (2025-01-23) https://github.com/yuliskov/SmartTube/issues/4318
- #816 "Why cant use this app on my tablet?" (2021-08-17) https://github.com/yuliskov/SmartTube/issues/816
- #1971 "No portrait mode? This is a must have (for me)" (2022-11-02) https://github.com/yuliskov/SmartTube/issues/1971
- LibreTube #4637 "SmartTube integration > Remote Casting" (casting, low relevance) https://github.com/libre-tube/LibreTube/issues/4637

---

## Competitive / space validation (repos)

**A. `milika/SmartTubeIOS` — the strongest single signal.** ⭐196 · 17 forks ·
71 open issues · created 2026-04-25 · updated daily.
https://github.com/milika/SmartTubeIOS
> "Native Swift/SwiftUI YouTube client for iPhone, iPad, Apple TV & Mac — ad-free,
> SponsorBlock, DeArrow, Google sign-in, up to 8K. Open source." … README:
> "Inspired by the original SmartTube Android app."
This is *our product for Apple*. Its rapid star growth and very active issue
tracker (people filing gesture/background-play/quality bugs = real daily users)
prove appetite for a SmartTube-style native phone client — and it leaves the
**Android-phone slot open for us.** Watch it as both validation and a template.

**B. `SkyTubeTeam/SkyTube` — older open-source phone YouTube client.** ⭐2750 ·
263 open issues · still maintained (pushed 2026-05-24), not archived.
https://github.com/SkyTubeTeam/SkyTube
Proves a long-standing market for a libre phone YouTube app, but it is not
SmartTube-class on features/polish — it's frequently offered as the "alternative"
users say they *don't* want. Space validation, not a direct competitor to our
feature set.

**C. No dedicated "SmartTube for phones" Android fork exists.** Searches for
`SmartTube mobile` / `SmartTube phone` repos returned **zero** real forks; the
`SmartTube` repo search surfaced only the TV app, its legacy build, the iOS clone
above, website repos, and empty/for-name forks. **The Android-phone niche is
genuinely unoccupied** — no abandoned attempt to point to, which cuts both ways
(open field, but no prior traction to inherit).

---

## GitHub engagement targets (post-launch, respectful mentions)

When we launch, these are live threads where a single, non-spammy "we built the
thing you asked for — open-source, GPLv3, here's the repo" reply is on-topic and
welcome. Prioritize open threads and the maintainer-adjacent ones.

| Priority | Thread | Why it fits |
|---|---|---|
| 1 | SmartTube #4821 (open) | OP explicitly still wants it; names our exact edge (real login vs NewPipe). |
| 1 | SmartTube #5113 (open) | Active tablet user blocked only by touch UX — literally our fix. |
| 1 | `milika/SmartTubeIOS` issues/Discussions | Its Android users repeatedly ask "when Android?"; direct audience. Engage in a Discussion, not by spamming bug issues. |
| 2 | SmartTube #3441 (closed) | Highest-upvoted; @xtools-at, @stymbhrdwj, @WolfganP are ideal early adopters — a courteous "this now exists" note revives the best thread. |
| 2 | NewPipe #10791 (open, 11👍) | Overlapping audience; frame as "companion/alternative that keeps Google login." |
| 3 | SmartTube #4427, #4308 (closed) | Requesters (@BourgeoisDirk, @maojianyou) explicitly wanted a phone version; a respectful closed-thread mention is reasonable. |
| 3 | SmartTube #236 (closed, oldest) | Historical anchor; low activity now, but a nod honors the original ask. |

Etiquette: post once, disclose we're the makers, lead with "open-source / no ads
/ no tracking," don't derail bug threads, and never mass-post the same text.
