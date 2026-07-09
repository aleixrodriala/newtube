# r/droidappshowcase — [Dev] showcase post

**Target sub:** r/droidappshowcase (the required promo venue for r/androidapps, ~562k)
**Flair:** `[Dev]` (required — this is a first-party app post)
**Format:** Image gallery post (upload the screenshots listed below) + this text as the body.
**Link rule obeyed:** links point to the GitHub **Releases** page and the site — **no raw `.apk` URL** (r/droidappshowcase only allows approved sources: GitHub/F-Droid/official stores).
**Maker disclosure:** stated in the first line.

---

## Title

**[Dev] NewTube — basically SmartTube, but built for your phone. Ad-free YouTube client, open source, no root.**

---

## Body

Hey r/droidappshowcase — I'm one of the devs, so fair warning, this is my own app. Posting it here because self-promo isn't allowed in r/androidapps and this is where it's supposed to go.

Quick origin story: I've run **SmartTube** on my TV for years and it's fantastic — ad-free, SponsorBlock, no nonsense. But SmartTube is built for a remote and a D-pad, and every time I sideloaded it onto my phone I spent 20 seconds fighting a TV cursor with my thumb just to scrub a video. The maintainer (@yuliskov) has said flat-out there's no phone version coming, and none of the other phone apps quite scratched the same itch. So we took SmartTube's engine — the actual playback/streaming part, which is the hard, well-tested bit — and put a proper touch-first phone UI on top of it. That's **NewTube**.

**The one thing that makes it different:** it can stay signed into your *real* YouTube account (optional). Your subscriptions, history and likes actually sync, the same as the official app. Most of the good ad-free phone apps make you give that up — they're local-only or import-only. NewTube keeps it, and it does the login with a device code (the "enter this code on youtube.com/activate" flow), so no weird workarounds and no root. You can also just... not sign in, and it works fine as a plain ad-free viewer.

### What it does

- **No ads.** It talks to YouTube's own internal API directly (the same approach NewPipe/SmartTube use) — there's no ad-blocker bolted on, nothing to keep patching. No root, no tracking, free, no in-app purchases.
- **Fast, clean home.** Lean 2-column video grid, bottom nav + a drawer to everything (subs, playlists, history, channels). Shorts is its own section you can unpin/hide if you never want to see it.
- **Real player.** Watch page with title/views, Like/Dislike (with actual Return YouTube Dislike counts), Subscribe, expandable description, and related/up-next. Double-tap to seek ±10s, prev/next, swipe down to dismiss.
- **Quality that isn't capped.** Up to 4K/HDR where the video has it, plus codec choice, playback speed, styled subtitles, and audio-track selection.
- **SponsorBlock** (auto-skips sponsor segments, shows markers on the seekbar) and **DeArrow** (swaps clickbait titles/thumbnails for community ones).
- **Background + lock-screen playback**, **Picture-in-Picture**, screen-off audio, repeat/shuffle, video zoom, a queue, and save-to-playlist from the overflow menu.
- **Comments** (with replies) and **live chat**, search with **voice**, plus full channels/playlists/subscriptions/history.

### Screenshots (in the gallery above)

1. Home — the 2-column grid
2. Watch page — Like/Dislike + RYD, description, related
3. Quality picker showing 4K/HDR + audio track
4. SponsorBlock segment auto-skipping mid-video
5. Background playback / lock-screen controls

### Honest status

This is the **first public release** — it's genuinely usable day-to-day (it's my main YouTube app now), but it's pre-1.0 and there are rough edges: comments can be flaky on some videos, and casting is code-based rather than a one-tap Chromecast button yet. Not on the Play Store — you sideload the APK from GitHub Releases. It's open source (**MIT**) so you can read exactly what it does; builds are signed and we're setting up reproducible builds too (relevant given SmartTube's official APK got hit with a bad update late last year — being able to verify the binary matters here).

Not affiliated with Google or YouTube in any way; "YouTube" is a trademark of Google LLC. And huge credit to SmartTube / @yuliskov — this is their engine, we just built the phone half.

**Download (GitHub Releases):** {{RELEASES_URL}}
**Source + how it works:** {{REPO_URL}}
**Landing page / install guide:** {{SITE_URL}}
**SmartTube (the upstream project this is built on):** https://github.com/yuliskov/SmartTube

Happy to answer anything — especially how the background playback and the account sign-in work under the hood. And if it caps out at low resolution or the seek feels off on your specific phone, tell me the model and I'll dig in.
