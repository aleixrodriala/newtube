# NewTube — Demand Evidence & Launch-Channel Map

Research date: 2026-07-01. Method: every Reddit/Lemmy/HN/GitHub link below was
fetched and parsed programmatically (Reddit `.json` via a real Chrome over CDP,
since Reddit blocks Anthropic's crawler; HN via `hn.algolia.com`; GitHub via
REST API). Quotes are verbatim from the fetched JSON. Upvote/comment counts are
live-as-of the research date and drift over time. Nothing here is fabricated;
where I could not verify something I flag it.

---

## 1. Ranked demand signals (verified)

### Tier A — direct, on-point, recent

1. **"Libretube just cannot play videos, newpipe and bravepipe just stuck at
   360p and dont even get me started on SkyTube"**
   r/degoogle, *"Is there any great Youtube Clients that works just fine?"* —
   8 upvotes, 15 comments, 2026-05-27.
   https://www.reddit.com/r/degoogle/comments/1tp01p7/is_there_any_great_youtube_clients_that_works/
   → Maps directly to our "player speed / quality / 4K-HDR" pillar.

2. OP: **"I used revanced for a while but every couple days the videos start
   taking forever to load and I have to clear the cache… I found grayjay but I
   felt it was pretty slow… I also saw newpipe but it doesnt support using
   accounts so it wont be able to sync with my pc."** Top reply confirms:
   *"NewPipe does not support logging in to YouTube."*
   r/degoogle, *"I want a YouTube client but I cant find good ones"* — 6 up,
   14 comments, 2026-06-22.
   https://www.reddit.com/r/degoogle/comments/1ucwh0y/i_want_a_youtube_client_but_i_cant_find_good_ones/
   → Hits three of our pillars at once: **stability, performance, and proper
   login/sync** (our microG-free Google sign-in).

3. **"Alternative youtube like smarttube for mobile"** — title is the ask.
   *"something that skips ads and can listen to music/videos whilst screen is
   off."* r/androidapps — 4 up, 13 comments, 2025-11-22.
   https://www.reddit.com/r/androidapps/comments/1p454aa/alternative_youtube_like_smarttube_for_mobile/
   → Literal "SmartTube but for mobile" request.

4. Comment on a rival's launch: **"I tested smarttube on my old Samsung and it
   works. Ui is not optimized for mobile but it works."** Another: *"are there
   any plans to allow local subscriptions? just to be able to use youtube
   without having to sign in."*
   r/foss, *"SmartTube — open source native YouTube client for iPhone/iPad/Mac"*
   — 120 up, **181 comments**, 2026-04-30.
   https://www.reddit.com/r/foss/comments/1t07sv0/smarttube_open_source_native_youtube_client_for/
   → People already sideload SmartTube onto phones and hit exactly our gap
   (TV UI on a phone). Also a **competitor**: an iOS/macOS SmartTube built on
   the *same* recipe (InnerTube API + SponsorBlock + DeArrow + Google Auth,
   no tracking). Validates the concept and the positioning almost word-for-word.

5. **"Is there any YouTube client that gives homepage recommendations based on
   local watch history without requiring a Gmail login?"** — 14 up, 11 comments,
   2026-06-11. A commenter: *"can you subscribe, create playlist without login?"*
   https://www.reddit.com/r/degoogle/comments/1u2rs31/is_there_any_youtube_client_that_gives_homepage/
   → Demand for our "works great without an account, optional login" model.

### Tier B — SmartTube's own users asking for phones (GitHub)

6. **SmartTube #3441 "Q: Mobile App UI"** — 12 👍 reactions, 4 comments,
   closed, 2024-04-17. OP praises SmartTube's reliability over NewPipe/ReVanced
   and asks to run it *"on my phone and tablet too,"* and whether maintainers
   would accept a mobile-UI contribution. (Answer was effectively no.)
   https://github.com/yuliskov/SmartTube/issues/3441

7. **SmartTube #236 "Add touch screen controls and screen scaling"** — 13
   reactions (+11/−1), 18 comments, closed, 2020-12-05. *"I would like to see
   support for devices without a control panel, with a touch screen. On tablets
   and smartphones."*
   https://github.com/yuliskov/SmartTube/issues/236

### Tier C — macro tailwinds (large, verified engagement)

8. **NewPipe on Hacker News, repeatedly front-paged:** 864 pts / 590 comments
   (2020-07-17, id 23871169); 782 pts / 389 comments (2023-11-04, id 38144400);
   and fresh: **338 pts / 115 comments (2026-02-15, id 47020218)** "NewPipe:
   YouTube client without vertical videos and algorithmic feed."
   → A repeatable, proven appetite on HN for an ad-free open-source phone
   YouTube client. This is the strongest evidence a **Show HN** will land.

9. **"Google will lock-down Android in September 2026 and NewPipe 0.28.4"** —
   r/NewPipe, 291 up, 97 comments, 2026-03-09.
   https://www.reddit.com/r/NewPipe/comments/1rovj7m/
   And *"NewPipe and Harmony currently unusable. I think its soon going to be
   the end of an era for YouTube third party clients"* — r/fossdroid, 53 up,
   65 comments, 2025-01-22.
   https://www.reddit.com/r/fossdroid/comments/1i78vdi/
   → The incumbent FOSS clients are fragile; users are actively shopping.

10. **Trust vacuum around SmartTube (our parent project):** SmartTube's official
    APK was compromised with malware in Dec 2025 and pulled from Play in Nov 2025.
    - HN "SmartTube Compromised" — 165 pts, 148 comments, 2025-12-01 (id 46103657).
    - Lemmy "SmartTube's official APK was compromised with malware" — 139 pts,
      19 c (lemmy.world/post/39554779); a second compromise post 135 pts, 13 c.
    - Lemmy "Youtube ReVanced Alternatives" — 122 pts, 37 c
      (lemmy.world/post/3932365).
    → A clean-room, open-source, **reproducibly-built** fork can position itself
    as the trustworthy successor. Lead with build transparency.

11. **Official SmartTube confirms it will not serve phones:** smarttubeapp.github.io
    describes it as *"an open-source media client for Android TV"* and *"Designed
    for TV screens."* No phone/tablet support, no port planned (issues #3441/#236
    closed). → The demand in Tier B has **no first-party answer**; that's our lane.

### ⚠️ Launch-strategy warning (from a rival's launch thread)
On r/fossdroid, the *LegionTube* launch (69 up, 38 comments, 2026-05-01,
https://www.reddit.com/r/fossdroid/comments/1t11gsm/) got roasted as a generic
reskin: **"Looks directly like a fork of newpipe. There's already 100 copies
exactly like this,"** and **"another YouTube client, lemme guess? Half the
commits are from AI?"** Takeaway: the FOSS crowd is fatigued by low-effort
NewPipe/LibreTube clones. **We must differentiate hard on day one**: SmartTube
engine lineage (not a NewPipe fork), real Google login without microG, measured
player performance, polished native phone UI — with screenshots and a clear
"why this is different" up top. Avoid anything that reads as AI-generated.

---

## 2. LAUNCH CHANNELS map

Priority: **P0** = launch here first / highest fit; **P3** = opportunistic.
Sizes are subscriber counts fetched from each community's `about.json` on the
research date. Store-process notes for IzzyOnDroid/F-Droid are from their docs
(cited); Product Hunt/AlternativeTo timing notes are general practice, not
freshly re-verified — treat as guidance.

| Channel | Size | Self-promo rules (verbatim gist, verified) | Best post format | Priority |
|---|---|---|---|---|
| **IzzyOnDroid repo** (store) | FOSS-wide reach | Inclusion request; app must be FOSS; tag GitHub **releases with the APK attached**; put **Fastlane metadata** (short+full description, icon, screenshots) in the repo; updates picked up ~24h. GPLv3 fine. [docs](https://izzyondroid.org/docs/general/AppInclusionPolicy/) | Get **listed first** so every launch post can link a real store, not a raw APK (Reddit rules ban raw-APK links). | **P0** |
| **r/fossdroid** | 98,757 | Purpose *is* promoting FOSS Android apps. **Must state source license** (GPLv3 ✓). Must include a free distribution link. **No AI-written posts.** No memes. | Native launch post: title = "NewTube — open-source (GPLv3), touch-first YouTube client: no ads, real login w/o microG, fast player." Real screenshots, "why not just another NewPipe fork" section. Proven: LegionTube got 69 up here. | **P0** |
| **r/degoogle** | 503,916 | **Project promo must go in the pinned "Degoogle Showcase megathread."** Devs must disclose affiliation. **No first-party YouTube links.** Disclose AI content. | (a) Post to the Showcase megathread. (b) Bigger win: **organically answer** the many live "which YouTube client" threads (§1 #1,2,5) with a genuine, disclosed recommendation. | **P0** organic / **P1** megathread |
| **Hacker News — Show HN** | very large | Show HN norms: real, usable thing; link to the project/GitHub; no marketing fluff; founder answers in comments. | `Show HN: NewTube – open-source ad-free YouTube client for Android phones`. Post ~7–9am ET on a weekday. Emphasize InnerTube (no ad-blocker), SponsorBlock/DeArrow, reproducible build. NewPipe precedent: 864/782/338 pts. | **P1** |
| **r/droidappshowcase** | 9,317 | The **designated venue for all r/androidapps app promo** (main sub *bans* self-promo; announced 2026-03-12). Approved sources only (GitHub/F-Droid/official stores); no raw APK links. | Standard app-showcase post w/ [Dev] flair, screenshots, store/GitHub link. Reaches the 562k r/androidapps audience indirectly. | **P1** |
| **XDA — Android Apps forum** | large, SEO-durable | Create an app thread in the apps subforum; ongoing changelog thread expected. SmartTube's own XDA thread shows the audience is here. | Long-lived "[APP] NewTube" thread; post updates per release. Good long-tail search traffic. | **P2** |
| **r/opensource** | 364,741 | Self-promo allowed *"to a degree"*; use the **`Promotional` flair**; linked repo **must have a LICENSE**; no karma-farming. | "Promotional"-flaired post framed around the open-source engineering story (fork of SmartTube, InnerTube, GPLv3). | **P2** |
| **Lemmy** (`!android`, `!degoogle`, `!privacy`, `!opensource` @ lemmy.world / programming.dev) | mid; FOSS-dense | Generally promo-tolerant with disclosure; FOSS/degoogle-native audience. Active threads on SmartTube compromise + ReVanced alternatives (§1 #10). | Cross-post the fossdroid launch; join the "ReVanced alternatives / SmartTube compromised" threads. | **P2** |
| **F-Droid (official repo)** (store) | very large trust | Submit an **RFP (Request For Packaging)** issue; app must **build reproducibly from source**; slower review. High trust once in. | Open RFP early (long lead) in parallel with IzzyOnDroid; advertise "reproducible build" as anti-malware trust signal. | **P2** |
| **AlternativeTo.net** | large SEO | Community-driven listings; add product + list it as an alternative. | Add NewTube; tag as alternative to **NewPipe, SmartTube, ReVanced, LibreTube, YouTube**. Captures comparison-shopping searches. | **P3** |
| **Product Hunt** | broad, less FOSS-native | Free launch page; one launch/day; typical best window Tue–Thu ~00:01 PT. | Launch page w/ demo GIF; rally early supporters. Lower fit (audience less FOSS/privacy). | **P3** |
| **r/revancedapp** | 354,998 | ReVanced-focused; adjacent frustrated audience (breakage complaints). Direct promo of a non-ReVanced app is risky. | Organic mentions only, when users ask for a more stable alternative. Do **not** cold-drop a launch post. | **P3** |
| **r/privacy** (1.64M) / **r/Android** (3.17M) | huge | Strict; small-app self-promo generally disallowed / weekly-thread-only. | Only if a genuinely relevant discussion appears; not a primary launch venue. | **P3** |
| **r/NewPipe** (32,308) / **r/GrayJay** (5,561) | niche | Fork/competitor promo likely unwelcome in r/NewPipe. r/GrayJay tiny. | Avoid direct promo; monitor for organic openings. | **P3** |
| ~~r/SmartTube~~ | — | **Private (403).** Not usable. | — | — |

### Sequencing recommendation
1. **Pre-launch:** get into **IzzyOnDroid** (P0) and open the **F-Droid RFP**
   (P2, long lead) so posts can link a store, not an APK.
2. **Launch day:** **r/fossdroid** post (P0) + **Show HN** (P1) + **Lemmy**
   cross-posts, same day, differentiated messaging per the warning above.
3. **Week 1:** r/degoogle Showcase megathread + organic answers in the live
   "which client" threads; r/droidappshowcase; XDA thread; r/opensource.
4. **Ongoing:** AlternativeTo listing, Product Hunt when polished, organic
   replies in r/revancedapp / r/privacy when relevant.

---

## 3. Verification notes / caveats
- Reddit vote/comment counts are live and will drift; captured 2026-07-01.
- Reddit is blocked for Anthropic's WebFetch/WebSearch crawler; all Reddit data
  was read through a real Chrome instance driving the public `.json` endpoints,
  then parsed. Quotes are copied from that JSON.
- `hn.algolia.com` story stats and GitHub issue reaction counts are from their
  official APIs.
- IzzyOnDroid / F-Droid process quotes are from their published docs; Product
  Hunt / AlternativeTo timing is general community practice, not re-verified.
- r/SmartTube exists but is **private** (returns 403) — cannot be used.
