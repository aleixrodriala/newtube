# r/degoogle — launch content

Two deliverables:
- **Part A** — a comment for the pinned **Degoogle Showcase megathread** (r/degoogle
  routes all project promo there; standalone launch posts get removed).
- **Part B** — a reusable **organic-reply template** for the live "which YouTube
  client?" threads, with two worked examples against real threads.

Channel rules obeyed (from `docs/gtm/research/demand-communities.md` §2 &
`engagement-plan.md` §3): promo goes in the Showcase megathread; disclose we're the
makers; **no first-party YouTube links**; link the GitHub Release, never a raw `.apk`.
For this audience specifically, lead with **no account required → optional login →
no tracking**.

---

## Part A — Degoogle Showcase megathread comment

**NewTube — ad-free YouTube on Android phones that runs with *no* Google account (and optional real login if you want it). Open-source, MIT.**

Disclosure first, because this sub rightly asks for it: I'm one of the people building this, so read it as a dev showing their own work, not a neutral rec.

The one-liner is "SmartTube, but for your phone." NewTube is a touch-first fork of SmartTube — the ad-free Android *TV* client by @yuliskov — with a native phone/tablet UI on top. SmartTube never shipped for phones and the maintainer has said it won't; that missing lane is the whole reason this exists.

Why it might actually fit r/degoogle, concretely:

- **Zero Google account needed.** Fresh install, no login, no Play Services, no microG. You still get search, channels, a home feed, and watch history — the feed is built from your *local* history on-device. (I keep seeing threads here like ["homepage recommendations from local watch history without a Gmail login"](https://www.reddit.com/r/degoogle/comments/1u2rs31/is_there_any_youtube_client_that_gives_homepage/) — that specific ask is the default state of the app, not a setting you hunt for.)
- **Login is optional and device-code.** If you *do* want your real subs/likes/history synced, you sign in the way you would on a TV: the app shows a code, you type it into the activation page in any browser. The app never sees your password, and there's no microG shim in the middle. Or you never do this and lose nothing.
- **No ads, no ad-blocker to break.** It talks to YouTube's internal InnerTube API directly (same idea as NewPipe/SmartTube). Ads aren't filtered out, they're never requested. No root.
- **No tracking, no analytics, no crash phone-home, no IAP, no account on *our* side.** MIT-licensed; the code is all in the repo.

Player side, briefly: SponsorBlock (auto-skip + segment markers), DeArrow (de-clickbait titles/thumbnails), background + lock-screen playback, PiP, up to 4K/HDR, styled subtitles, audio-track picker, speed control. Shorts is a section you can unpin and hide entirely.

One trust note this crowd will care about: SmartTube's official APK was hit with malware in Dec 2025. Our answer is boring on purpose — clean-room, open-source, and every release is built in CI from tagged source and signed with a stable key, so you can read exactly what's in it instead of trusting my word. Reproducible builds (rebuild it and byte-match the APK) are what we're working toward next; I'd rather name that as the goal than pretend it already ships. That's the bar we're trying to clear, not a marketing line.

Fair comparison, no dunking (all of these are good, and some are why I started caring about this):
- **NewPipe / Tubular** — excellent, but can't log into a real account by design.
- **LibreTube** — clean UI, but it leans on public Piped/Invidious instances, which rate-limit or go down.
- **ReVanced** — patches Google's *own* YouTube app; powerful, but it's the proprietary app underneath, so it's an update/ban treadmill.
- **SmartTube** — the engine we're built on, but Android TV only.

NewTube's niche is deliberately narrow: keep your real account *if you want it*, or run fully accountless, on a phone, open-source, no microG. I'm not claiming it's unbreakable — nothing that talks to YouTube is — just that it's built to update fast and stay honest.

- APK (GitHub Releases): {{RELEASES_URL}}
- Source + MIT license: {{REPO_URL}}
- Screenshots / landing: {{SITE_URL}}
- Built on SmartTube by @yuliskov: https://github.com/yuliskov/SmartTube

Not affiliated with Google or YouTube; "YouTube" is a trademark of Google LLC. Happy to take the uncomfortable questions too — including "what still talks to Google's servers," because the answer isn't zero and I'd rather be straight about it.

---

## Part B — Organic-reply template (live "which YouTube client?" threads)

This is **scaffolding, not copy-paste.** The anti-slop rule from `engagement-plan.md` §1
is the whole game here: LegionTube got roasted on r/fossdroid for reading AI-generated.
Rewrite every reply in your own words and answer *that person's* specific complaint. If
two replies share a paragraph, we've already lost.

**Guardrails (obey per reply):**
- Open with the dev disclosure. Every time.
- Don't badmouth NewPipe / LibreTube / ReVanced / Grayjay. Empathize with whatever broke for them.
- Link the GitHub Release, never a raw `.apk`. No first-party YouTube links (sub rule).
- One reply, on-topic, then step back. No follow-up spam.

**Four-beat skeleton (~3–5 sentences total):**
1. **Disclose** — "Dev disclosure: I work on NewTube, grain of salt."
2. **Mirror their pain** — quote the exact thing they're stuck on.
3. **One differentiator that solves *that*** — pick one, don't recite the feature list.
4. **Link + a real question** — asking for their edge case beats pitching.

### Worked example 1 — ["I want a YouTube client but I can't find good ones"](https://www.reddit.com/r/degoogle/comments/1ucwh0y/i_want_a_youtube_client_but_i_cant_find_good_ones/) (ReVanced needs cache-clearing / Grayjay slow / NewPipe can't log in)

> Dev disclosure up front — I help build NewTube, so weigh that. Your three pain points are basically its brief: it pulls streams from YouTube's own InnerTube API instead of ad-blocking, so there's no filter cache to wipe every few days; the player is the part we've sunk the most time into; and it does real Google sign-in without microG, so subs/history actually sync (or skip login entirely and it runs on local history). MIT, no tracking: {{RELEASES_URL}}. Genuinely want to know whether it lasts longer than ReVanced did for you — if it starts crawling, send me the video and I'll dig in.

### Worked example 2 — ["homepage recommendations based on local watch history without a Gmail login"](https://www.reddit.com/r/degoogle/comments/1u2rs31/is_there_any_youtube_client_that_gives_homepage/) (+ commenter asking about subscribing/playlists without login)

> Disclosure: I'm on the NewTube team. This is close to the app's out-of-the-box state — no account, no Play Services, and the home feed is built from your *local* watch history, so you get recs without a Gmail attached. To the person asking about subscribing/creating playlists without login: those two genuinely need a sign-in (they live on your YouTube account server-side), but the login is optional and device-code, so you only opt in for the account features and nothing else phones home. Open-source, MIT: {{REPO_URL}}.

Note that example 2 does **not** promise account features without login — being straight
about the limits is what keeps this from reading like a shill.
