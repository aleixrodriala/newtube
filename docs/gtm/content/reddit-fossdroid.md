**Title:** NewTube — open-source (MIT) YouTube client for phones: SmartTube's engine, not a NewPipe fork, with real Google login and no microG

---

Two things up front, because this sub has (rightly) seen a wave of low-effort YouTube reskins: **NewTube is a port of SmartTube's InnerTube engine to a touch-first phone UI — it is not a NewPipe/Tubular fork and shares no code with one.** And it does **real Google sign-in via the device-code flow — no microG, no Play Services** — so your actual subs/history/likes sync, or you run it fully signed-out. Same engine SmartTube (the Android TV app) has been hardening against YouTube's changes for years; I just rebuilt the front end for thumbs instead of a remote.

I'm the dev. Disclosing that plainly — this is a launch post, not a "hey I found this cool app."

**License / download (r/fossdroid rules):** MIT, free, no ads, no IAP, no tracking, no root. Source + APK straight from GitHub Releases — no third-party mirror:
- Source: {{REPO_URL}}
- Release APK: {{RELEASES_URL}}
- Site: {{SITE_URL}}

**On the "another AI-generated clone" worry:** fair, and I'd rather you check than take my word. The commit history is hand-written, and every release is built in CI from tagged source and signed with a stable key, so you can read the code, see which commit a build came from, and confirm the signature doesn't change under you. That transparency mattered a lot more to me after SmartTube's *official* APK got hit with malware last December; "trust me, sideload this" isn't good enough. Reproducible builds — where you rebuild and byte-match the APK — are the piece I'm still wiring up; I'll say so plainly when they land rather than claim it early.

**Why it exists:** SmartTube is great, but it's built for a 10-foot TV UI and a D-pad. Sideload it on a phone and it technically works — I did it for months — but the focus-based navigation and TV-sized fonts fight your thumbs the whole time. Nothing on phones fills that exact slot: NewPipe and LibreTube are solid projects I respect, they just deliberately don't do a logged-in Google account; ReVanced patches Google's own app. NewTube is the "SmartTube, but native on my phone, still signed into my real account" option that people kept asking SmartTube's maintainer for (he's said no to a phone port — issues #3441, #236).

**What's actually in it:**
- Lean 2-column grid home, bottom nav, fast player — no algorithmic clutter
- Player: Like/Dislike (real Return YouTube Dislike numbers), Subscribe, description, comments + replies, related/up-next
- Touch controls: double-tap ±10s, prev/next, swipe-to-dismiss; overflow menu for repeat/shuffle/zoom/screen-off/queue/save-to-playlist
- Up to 4K/HDR with codec pick, playback speed, styled subtitles, audio-track select
- **SponsorBlock** (auto-skip + timeline markers) and **DeArrow** (de-clickbait titles/thumbs)
- Background playback + lock-screen controls, Picture-in-Picture, live chat, search + voice
- Channels, playlists, subscriptions, history; Shorts is its own section you can unpin/hide

**Screenshots (attached to this post — real emulator captures, not mockups):**
1. `home-grid.png` — the lean two-column touch home feed, dark theme
2. `player-watch.png` — the watch page: Like/Dislike with real RYD numbers, description, up-next
3. `sponsorblock.png` — a sponsor segment auto-skipping, with the marker on the seekbar
4. `signin-devicecode.png` — the device-code sign-in screen (no microG)

The touch player is the part a screenshot actually sells, so it leads the gallery — more shots are in the README and on the release page.

It's a first public release, so it's not perfect — if playback caps at low res or a gesture feels off on your device, open an issue and I'll dig in. Roasting the code is also welcome; that's kind of the point of shipping it here.

*NewTube is not affiliated with Google or SmartTube's maintainer. "YouTube" is a trademark of Google LLC. Built on SmartTube by @yuliskov (MIT) — https://github.com/yuliskov/SmartTube.*
