# NewTube — Post-Launch Community Engagement Plan

**What NewTube is:** a free, MIT-licensed, ad-free, touch-first YouTube client for
Android *phones* — "SmartTube, but for your Android phone." Ships open-source on
GitHub Releases.

**Provenance of this plan:** synthesized entirely from two verified research files —
no fresh web research, nothing invented:
- `docs/gtm/research/demand-github.md`
- `docs/gtm/research/demand-communities.md`

Every thread, quote, and URL below is copied from those files. Counts drift; they are
live-as-of the 2026-07-01 research date.

---

## 1. Rules of engagement (non-negotiable — read before you post anything)

These are hard constraints. If a post would violate one, don't post it.

1. **Always disclose we're the makers.** Every reply, every thread, every store
   listing states plainly that we built NewTube. No sockpuppeting, no "I found this
   cool app" framing. r/degoogle and r/fossdroid *require* affiliation disclosure;
   we do it everywhere regardless.
2. **Follow each community's self-promo rules exactly** (they differ per channel —
   see §3): r/degoogle project promo goes in the pinned **Degoogle Showcase
   megathread**; r/androidapps *bans* self-promo and routes it to
   **r/droidappshowcase**; r/opensource requires the **`Promotional` flair** + a
   repo with a LICENSE; r/fossdroid requires stating the source license and a free
   distribution link. Check the sub's rules the day you post — rules change (r/andro-
   idapps' ban was announced 2026-03-12).
3. **Never post identical copy across places.** The rival **LegionTube** got roasted
   on r/fossdroid as AI slop — *"another YouTube client, lemme guess? Half the commits
   are from AI?"* and *"Looks directly like a fork of newpipe. There's already 100
   copies exactly like this"*
   (https://www.reddit.com/r/fossdroid/comments/1t11gsm/). Every reply is written
   fresh, by hand, addressing that specific person's specific words. No template
   fills. Anything that reads AI-generated kills us on day one.
4. **Don't badmouth NewPipe / LibreTube / ReVanced / Grayjay / SkyTube.** They are
   respected, and their users are our audience. State our *differentiators* factually
   (real Google login without microG; SmartTube-engine lineage, not a NewPipe fork;
   player performance; touch-first UI) without disparaging. Where a user is already
   frustrated with an incumbent, empathize — don't pile on.
5. **Don't aggressively necro-bump years-old threads.** Closed/old threads (SmartTube
   #236 from 2020, #3441 from 2024) get **at most one** courteous "the thing you
   asked for now exists" note — and only where it genuinely helps the original asker.
   Prioritize *open* threads. Never derail a bug thread; never mass-post.
6. **Link the GitHub Release, not a bare APK, where a store listing is expected.**
   Reddit rules ban raw-APK links; r/droidappshowcase allows "approved sources only
   (GitHub/F-Droid/official stores), no raw APK links." So we link the **GitHub
   Releases page** (or the IzzyOnDroid listing once live) — never a direct `.apk` URL.
   This is *why* IzzyOnDroid must land first (see §4).
7. **Lead with what makes us different, every time.** SmartTube-engine lineage (NOT a
   NewPipe fork), real Google login without microG, measured player performance,
   polished native touch UI, and — given the Dec 2025 SmartTube malware incident — a
   clean-room, open-source, reproducibly-built binary. Screenshots up top.

---

## 2. Direct-reply targets (prioritized, with tailored human drafts)

Priority: **1** = open thread, active asker, our exact edge → reply first.
**2** = high-value but closed/older, one courteous revival. **3** = adjacent/lower.

Every draft is 2–4 sentences, discloses we made it, addresses that person's specific
ask, and links the release. **Do not reuse wording between drafts.**

| P | Thread (URL) | What they asked | Moment / how to respond | Tailored draft reply |
|---|---|---|---|---|
| **1** | SmartTube **#4821** (open) — https://github.com/yuliskov/SmartTube/issues/4821 | @boustanihani wants a mobile-optimized SmartTube UI; explicitly says alternatives "only support importing stuff, but not real login like SmartTube (which is much easier)." | Reply once, on-topic, in the open thread. Hit the *real-login* point directly — it's his stated reason. | "@boustanihani — we built exactly this: NewTube, an open-source (MIT) touch-first SmartTube for phones, and it keeps the real Google login you called out (no microG, no 'import only'). It's the SmartTube engine with a native phone UI, not another NewPipe fork. First release + screenshots here: [GitHub Releases] — would genuinely value your feedback since you named the exact gap." |
| **1** | SmartTube **#5113** (open) — https://github.com/yuliskov/SmartTube/issues/5113 | @Unamelable runs SmartTube on an Android tablet, says it's the best client "with almost full functionality" but has "very little touchscreen control for rewinding." | Reply once; lead with the *touch seek/rewind* fix — that's the one thing blocking them. | "@Unamelable — since you're already running SmartTube on a tablet and only the touch controls hurt, you might like NewTube (what we've been building): the same engine with a proper touch seekbar and gesture rewind for phones/tablets. Open-source, MIT, ad-free — first build's here: [GitHub Releases]. If the seek behavior still feels off on your device, tell us and we'll fix it." |
| **1** | `milika/SmartTubeIOS` **Discussions** — https://github.com/milika/SmartTubeIOS | Its Android users repeatedly ask "when Android?" under the iOS clone (196★, active daily). | Post in a **Discussion**, never on bug issues. Frame as complementary ("the Android side of the same idea"), not a rival. | "Fan of what this project's doing for iOS. For the folks here asking about Android — we've been building NewTube, an open-source (MIT) SmartTube-style client for Android phones: real Google login, SponsorBlock/DeArrow, ad-free. Not affiliated with this repo, just filling the Android slot. First release: [GitHub Releases]." |
| **1** | r/androidapps — https://www.reddit.com/r/androidapps/comments/1p454aa/alternative_youtube_like_smarttube_for_mobile/ | Title *is* the ask: "Alternative youtube like smarttube for mobile" — wants ad-skip + background/screen-off playback. | Reply organically. **Note:** r/androidapps bans self-promo — reply only if the mod-permitted; otherwise route the launch to r/droidappshowcase and answer here only as a disclosed dev comment if allowed. | "Full disclosure, I'm one of the people who made this, so grain of salt — but this is literally the thing you're describing. NewTube: SmartTube for phones, ad-free, background + screen-off audio, open-source (MIT). Not a NewPipe reskin, it's the SmartTube engine. [GitHub Releases]. Happy to answer anything about how the background playback works." |
| **1** | r/degoogle — https://www.reddit.com/r/degoogle/comments/1ucwh0y/i_want_a_youtube_client_but_i_cant_find_good_ones/ | OP: ReVanced needs cache-clearing every few days, Grayjay "pretty slow," NewPipe can't log in / sync with PC. | Answer organically in the live thread, disclosed. Hit all three pains: stability, speed, real login/sync. | "Dev disclosure up front: I work on NewTube. It targets the exact three things you hit — it uses YouTube's own InnerTube API (no ad-blocker to break, so no periodic cache-clear), it's tuned for a fast player, and it does real Google sign-in without microG so your account/history actually sync. Open-source, MIT: [GitHub Releases]. Curious whether it holds up better than ReVanced did for you." |
| **1** | r/degoogle — https://www.reddit.com/r/degoogle/comments/1u2rs31/is_there_any_youtube_client_that_gives_homepage/ | Wants homepage recs from *local* watch history without a Gmail login; a commenter asks about subscribing/playlists without login. | Organic, disclosed reply. Lead with the **optional-login / works-without-account** model. | "I help build NewTube, so flagging that — it's designed around exactly this: works great with no account (local history), and login is optional if you later want server-side subs/playlists. Open-source, MIT, no tracking: [GitHub Releases]. It's a SmartTube-lineage engine, not a NewPipe fork, if that matters to you." |
| **2** | r/degoogle — https://www.reddit.com/r/degoogle/comments/1tp01p7/is_there_any_great_youtube_clients_that_works/ | OP frustrated: LibreTube can't play, NewPipe/BravePipe "stuck at 360p," SkyTube bad. | Organic reply focused on the **quality/4K-HDR** pillar; don't dunk on the others. | "Disclosure: I'm on the NewTube team. The 360p/playback issues you're describing are the specific thing we optimized for — it pulls proper high-res streams (up to 4K/HDR where the video has it) via YouTube's own API. Open-source, MIT, ad-free: [GitHub Releases]. If it caps out at low res on your device I'd want to know." |
| **2** | SmartTube **#3441** (closed, highest-voted, 12👍) — https://github.com/yuliskov/SmartTube/issues/3441 | @xtools-at: loves SmartTube, wants it "on my phone and tablet too"; rejects NewPipe (no login/casting) and ReVanced (fragile). @stymbhrdwj, @WolfganP piled on. | **One** courteous revival note on this closed thread — these three are ideal early adopters. Don't spam; add the news and step back. | "Reviving this once because it's the best-argued version of the ask (@xtools-at @stymbhrdwj @WolfganP): the phone SmartTube you wanted now exists as NewTube — open-source (MIT), real Google login + casting, none of the ReVanced fragility. First release + screenshots: [GitHub Releases]. Not affiliated with the upstream maintainer; just built the phone version the community kept requesting." |
| **2** | NewPipe **#10791** (open, 11👍) — https://github.com/TeamNewPipe/NewPipe/issues/10791 | Overlapping audience wanting SmartTube in their phone workflow (pairing/casting). | This is NewPipe's *own* repo — tread lightly, no NewPipe-bashing. Frame as a companion/alt that keeps Google login, only if it stays on-topic. | "Adjacent to this thread rather than a fix for it — for anyone here who specifically wants Google login/history on the phone side, we've built NewTube (open-source, MIT), a SmartTube-lineage phone client. Huge respect for NewPipe; this is just the login-based complement some of you are describing. [GitHub Releases]." |
| **2** | r/foss — https://www.reddit.com/r/foss/comments/1t07sv0/smarttube_open_source_native_youtube_client_for/ | 181-comment thread on the iOS SmartTube; commenters note "smarttube on my old Samsung… UI is not optimized for mobile but it works," and ask about local subscriptions without sign-in. | Reply to those specific comments (the "works but UI not optimized" one, and the local-subs one) — not a top-level ad in someone else's launch. | "Replying to the folks who said SmartTube 'works but the UI isn't optimized' on a phone — that's exactly the gap we built NewTube for (Android side): SmartTube engine, native touch UI, optional login so local subs work without a Gmail. Open-source, MIT: [GitHub Releases]. Not the iOS project — the Android counterpart." |
| **3** | SmartTube **#4427** (closed not-planned) — https://github.com/yuliskov/SmartTube/issues/4427 | @BourgeoisDirk: "I know the alternatives, i don't want the alternatives.. I wish for this App but as a Phone version." | One respectful closed-thread note addressed to the asker. | "@BourgeoisDirk — you said you didn't want the alternatives, you wanted *this* as a phone app. That now exists: NewTube, open-source (MIT), the SmartTube experience on a phone rather than a NewPipe/ReVanced substitute. [GitHub Releases]." |
| **3** | SmartTube **#4308** (closed) — https://github.com/yuliskov/SmartTube/issues/4308 | @maojianyou installed SmartTube on a phone; "framework, fonts, and interface don't automatically adapt." | One courteous note; lead with the adaptive/touch UI. | "@maojianyou — the non-adapting fonts/interface you ran into is the exact problem NewTube solves: a native phone layout on the SmartTube engine. Open-source, MIT: [GitHub Releases]. Would love to hear if it adapts cleanly on your device." |
| **3** | SmartTube **#236** (closed, oldest, 2020) — https://github.com/yuliskov/SmartTube/issues/236 | @UNOTEHNIKS, @rsunde asked for touch/phone support back in the Vanced era. | Historical anchor. A single brief nod honoring the original ask — do not bump repeatedly. | "The oldest version of this request (2020) deserves a nod: touch-screen SmartTube for phones finally exists as NewTube, open-source and MIT. Thanks to @UNOTEHNIKS and @rsunde for calling it years early. [GitHub Releases]." |

---

## 3. Channel launch playbook

For each channel: the exact format, the rules to obey, and the **one line to lead with there.**
Rules and sizes are verbatim gist from `demand-communities.md` §2.

### IzzyOnDroid (store) — **do this first**
- **Format/rules:** Inclusion request; app must be FOSS (MIT ✓); tag **GitHub Releases
  with the APK attached**; put **Fastlane metadata** (short + full description, icon,
  screenshots) in the repo; updates picked up ~24h.
  Docs: https://izzyondroid.org/docs/general/AppInclusionPolicy/
- **Lead with:** (this is a store listing, not a pitch) short description —
  *"Open-source, ad-free, touch-first YouTube client for Android phones. Real Google
  login without microG. SmartTube-lineage engine."*

### r/fossdroid (98,757) — **P0 launch post**
- **Format/rules:** Purpose *is* promoting FOSS Android apps. **Must state source
  license (MIT ✓)** and include a free distribution link. **No AI-written posts. No
  memes.** Include real screenshots and a "why this isn't just another NewPipe fork"
  section (LegionTube got roasted here — see §1 rule 3).
- **Lead with:** *"NewTube — open-source (MIT), touch-first YouTube client: no ads,
  real login without microG, fast player. Not a NewPipe fork — it's the SmartTube
  engine, ported to phones."*

### r/degoogle (503,916) — **P0 organic / P1 megathread**
- **Format/rules:** Project promo **must go in the pinned "Degoogle Showcase
  megathread."** Devs must **disclose affiliation. No first-party YouTube links.**
  Disclose AI content. Bigger win: **organically answer** the live "which YouTube
  client" threads (the three P1/P2 r/degoogle targets in §2).
- **Lead with (megathread):** *"NewTube: works great with no account (local history),
  optional real Google login — no microG, no tracking, open-source."*

### Hacker News — Show HN — **P1, launch day**
- **Format/rules:** Real, usable thing; link to the project/GitHub; no marketing
  fluff; **founder answers in comments.** Post **~7–9am ET on a weekday.** NewPipe
  precedent proves the appetite: **864 / 782 / 338 pts** across three front-pagings.
- **Lead with:** *"Show HN: NewTube – open-source ad-free YouTube client for Android
  phones."* In the body: InnerTube (no ad-blocker to break), SponsorBlock/DeArrow,
  **reproducible build** (nods to the Dec 2025 SmartTube-malware trust vacuum).

### r/droidappshowcase (9,317) → reaches r/androidapps (562k) — **P1**
- **Format/rules:** The **designated venue for all r/androidapps app promo** (main sub
  *bans* self-promo, announced 2026-03-12). Approved sources only (GitHub/F-Droid/
  official stores); **no raw APK links.** Use **[Dev] flair**, screenshots, store/
  GitHub link.
- **Lead with:** *"[Dev] NewTube — SmartTube, but for your Android phone. Ad-free,
  open-source, background/screen-off audio."*

### XDA — Android Apps forum — **P2**
- **Format/rules:** Create a long-lived **"[APP] NewTube" thread** in the apps
  subforum; maintain an ongoing **changelog thread**, post per release. SmartTube's
  own XDA thread shows the audience is here. Good long-tail SEO.
- **Lead with:** *"[APP][MIT] NewTube — touch-first YouTube for phones, SmartTube
  engine lineage. Changelog inside."*

### r/opensource (364,741) — **P2**
- **Format/rules:** Self-promo allowed *"to a degree"*; use the **`Promotional`
  flair**; linked repo **must have a LICENSE** (MIT ✓); no karma-farming. Frame around
  the *engineering story*, not the pitch.
- **Lead with:** *"NewTube — how we ported the SmartTube (Android TV) engine to a
  native touch phone client, kept real Google login, and build it reproducibly. MIT."*

### F-Droid RFP (store) — **P2, open early (long lead)**
- **Format/rules:** Submit an **RFP (Request For Packaging)** issue; app **must build
  reproducibly from source**; slower review, high trust once in. Open in parallel with
  IzzyOnDroid.
- **Lead with:** *"RFP: NewTube — MIT, builds reproducibly from source (anti-malware
  trust signal), no non-free deps."*

### AlternativeTo.net — **P3**
- **Format/rules:** Community-driven listings; add the product, then list it **as an
  alternative to NewPipe, SmartTube, ReVanced, LibreTube, YouTube.** Captures
  comparison-shopping search traffic.
- **Lead with:** *"NewTube — open-source, ad-free SmartTube alternative for Android
  phones."*

### Product Hunt — **P3, when polished**
- **Format/rules:** Free launch page; **one launch/day**; typical best window **Tue–Thu
  ~00:01 PT**; include a demo GIF; rally early supporters. Lower FOSS/privacy fit —
  don't lead the campaign here.
- **Lead with:** *"NewTube — ad-free YouTube for your Android phone, free & open-source."*

### Lemmy (`!android`, `!degoogle`, `!privacy`, `!opensource` @ lemmy.world /
programming.dev) — **P2**
- **Format/rules:** Generally promo-tolerant **with disclosure**; FOSS/degoogle-native.
  **Cross-post the fossdroid launch** (reworded, not identical); join the active
  "SmartTube compromised / ReVanced alternatives" threads (139 / 135 / 122 pts).
- **Lead with:** *"After SmartTube's APK was compromised — a clean-room, open-source,
  reproducibly-built phone YouTube client: NewTube (MIT)."*

### (Avoid direct promo) r/revancedapp (354,998), r/privacy (1.64M), r/Android (3.17M), r/NewPipe (32,308), r/GrayJay
- **r/revancedapp:** organic mentions only when a user asks for a more stable
  alternative; **do not cold-drop a launch post.**
- **r/privacy / r/Android:** strict; small-app self-promo disallowed / weekly-thread-
  only. Post only if a genuinely relevant discussion appears.
- **r/NewPipe:** fork/competitor promo unwelcome — monitor for organic openings only.
- **r/SmartTube:** private (403), unusable.

---

## 4. Sequenced timeline

Rationale threads through one constraint: **posts must link a store/Release, not a raw
APK** (rule 6), and **we must differentiate hard on day one** or get LegionTube'd
(rule 3). So stores land before posts, and FOSS-native audiences come before the
broad ones.

### Week 0 — Pre-launch (stores + assets)
- **Submit IzzyOnDroid inclusion** (P0) — tag a GitHub Release with the APK attached +
  Fastlane metadata (icon, screenshots, descriptions). ~24h pickup. This is the
  gating step: everything downstream links this store, not an APK.
- **Open the F-Droid RFP** (P2) — long review lead, so start now; advertise reproducible
  build.
- Prepare screenshots, a "why this is different (not a NewPipe fork)" section, and a
  short demo GIF. Ensure repo has LICENSE (MIT) visible.
- *Why first:* Reddit bans raw-APK links; IzzyOnDroid gives every later post a real
  store link and a trust anchor after the SmartTube malware incident.

### Week 1 — Launch day (FOSS-native, differentiated)
- **r/fossdroid launch post** (P0) — license stated, screenshots, differentiation up
  top.
- **Show HN** (P1) — same day, ~7–9am ET weekday; founder camps the comments. NewPipe's
  864/782/338-pt precedent says HN will engage.
- **Lemmy cross-posts** (P2) — reworded (not identical) into `!android`/`!degoogle`/
  `!privacy`/`!opensource`; join the SmartTube-compromise / ReVanced-alternatives
  threads.
- *Why now:* these audiences are FOSS-fluent, actively shopping (incumbents fragile),
  and most likely to reward a clean-room open-source successor — and to punish
  low-effort clones, so we lead with the differentiation we prepared in Week 0.

### Week 1–2 — Direct replies (the §2 table)
- Work the **Priority-1 open threads first** (SmartTube #4821, #5113; SmartTubeIOS
  Discussions; the two r/degoogle "which client" threads; r/androidapps title-ask),
  each with its own hand-written draft.
- Then the **Priority-2** items (one courteous revival of #3441; NewPipe #10791 lightly;
  the r/foss comment replies; the r/degoogle 360p thread).
- Space them out; never batch identical copy; disclose maker status every time.

### Week 2 — Broader FOSS + showcase venues
- **r/degoogle Showcase megathread** post (P1) + continue organic answers.
- **r/droidappshowcase** [Dev] post (P1) — reaches the 562k r/androidapps audience
  within the rules.
- **XDA "[APP] NewTube" thread** (P2) — long-tail SEO, ongoing changelog.
- **r/opensource** `Promotional`-flaired engineering-story post (P2).

### Ongoing — opportunistic + Priority-3 revivals
- **AlternativeTo** listing (P3) — tag as alternative to NewPipe/SmartTube/ReVanced/
  LibreTube/YouTube.
- **Product Hunt** (P3) once polished — Tue–Thu ~00:01 PT, demo GIF.
- **Priority-3 closed-thread nods** (SmartTube #4427, #4308, #236) — one note each,
  no bumping.
- **Organic-only monitoring** of r/revancedapp / r/privacy / r/NewPipe — reply only
  when a user genuinely asks for a more stable/login-capable alternative.

---

### One-page recap
Get IzzyOnDroid live first (so links point at a store, not an APK) → launch day on
r/fossdroid + Show HN + Lemmy with hard, hand-written differentiation (SmartTube
engine, real login without microG, reproducible build) → work the open GitHub/Reddit
asks with individually tailored replies → broaden to showcase/SEO venues → keep
opportunistic organic mentions. Disclose we're the makers everywhere; never reuse copy;
never badmouth the incumbents; never necro-spam.
