# [APP][5.0+] NewTube — SmartTube for your Android phone (open-source, MIT)

*Posted by the NewTube devs. Full disclosure up front: we make this. Not affiliated with Google, YouTube, or the upstream SmartTube project — this is a fork, credited below.*

---

Short version for the people who scroll: **NewTube is SmartTube with a real phone UI.** If you've ever sideloaded SmartTube onto a handset and put up with the 10-foot TV layout because nothing else keeps your actual Google login, this is the thread for you.

We didn't start from NewPipe. NewTube is built on **SmartTube's InnerTube engine** — the same thing that pulls streams straight from YouTube's internal API — and we bolted a touch-first, phone-native front end on top of it. So you get SmartTube's playback reliability and its real device-code Google sign-in, but with a seekbar you can actually drag, a two-column grid that fits a 6-inch screen, and gestures instead of a D-pad.

## Why bother when there are a dozen YouTube clients?

Because every one of them makes you trade something away, and NewTube's whole reason to exist is to not make that trade:

- **ReVanced** patches Google's own proprietary APK. Great when it works; it's also a moving target that breaks on YouTube-side changes and carries ban/DMCA baggage. NewTube ships as its own clean-room app — nothing to re-patch every time Google ships an update.
- **NewPipe / Tubular** are excellent and we respect them, but they can't log in to a YouTube account. If you want your real subscriptions, watch history and Likes synced, you're stuck.
- **LibreTube** leans on public Piped/Invidious instances — when those go down or get rate-limited, you feel it.
- **SmartTube** itself is Android-TV-only by design. The maintainer has said (repeatedly, in closed issues) it isn't going to serve phones.

NewTube's lane: **the SmartTube experience, on a phone, with your real account still attached — and optional.** Signed out, it works fine on local history. Sign in with the device-code flow (the "enter this code on youtube.com/activate" one — **no microG, no Google Play Services dependency**) and your subs/history/likes sync.

## Features

**Playback**
- Streams via YouTube's internal InnerTube API — no ad-blocker in the pipeline, so there's no filter list to break. No ads, no root, no tracking, no IAP.
- Up to **4K / HDR**, with codec selection (AV1/VP9/etc.), variable playback speed, **styled subtitles**, and audio-track selection.
- Touch controls done properly: double-tap ±10s seek, prev/next, swipe-down to dismiss the player.
- Overflow menu: repeat, shuffle, video zoom/aspect, **background playback**, screen-off audio, playback stats, rotate lock, save-to-playlist, queue.
- **Picture-in-Picture** and full media/lock-screen controls.

**On the watch page**
- Title, views/date, expandable description, related/up-next.
- **Like / Dislike with real Return YouTube Dislike** numbers, Share, Subscribe.
- Comments (with replies + likes) and **live chat**.

**Content & privacy**
- **SponsorBlock** — auto-skip segments plus visible markers on the seekbar.
- **DeArrow** — de-clickbaits titles/thumbnails.
- Search with voice input. Channels, playlists, subscriptions, history.
- **Shorts is a section you can unpin/hide** if you never want to see it again.

**Home**
- Lean two-column grid, bottom nav, and a drawer to every section. It's deliberately minimalist and quick — no bloat, fast cold start.

## Screenshots

*(attach in the XDA post — placeholders here)*

- `home-grid.png` — two-column home feed, dark theme
- `player-4k.png` — watch page with quality selector open (2160p/HDR)
- `sponsorblock.png` — seekbar markers + auto-skip toast
- `overflow.png` — the player overflow sheet (background/PiP/queue/etc.)
- `signin-devicecode.png` — device-code sign-in screen (no microG)
- `subtitles.png` — styled subtitle rendering

## Requirements

- Android **5.0 (API 21) or newer**. Phone or tablet.
- ~40 MB free. No root. No Google Play Services / microG required.
- ARM (arm64-v8a / armeabi-v7a) and x86 builds attached to each release.

## Download

Grab the APK from **GitHub Releases** (this is the canonical source; do not install a NewTube APK from a mirror you don't trust):

- **Latest release:** {{RELEASES_URL}}
- **Landing page / install guide:** {{SITE_URL}}

**Install:** download the APK → allow "install from this source" for your browser/file manager when prompted → open. Sign-in is optional and can be done any time from Settings.

### A word on trust (verify the build)

Given what happened to SmartTube's official APK in December 2025 (it was compromised with malware), we're not asking anyone to take "trust me" for an answer. Every release is:

- **Built from public source** you can read and compile yourself.
- **Signed with a stable key** — the signature won't change release to release, so your installer will refuse a swapped-out APK.
- Published with a **SHA-256** in the release notes. Verify before you install:

  ```
  sha256sum NewTube-<version>-arm64-v8a.apk
  ```

  and compare against the hash on the release page.

## Source & license

- **Source:** {{REPO_URL}}
- **License: MIT.** Do what you like with it; keep the notice.
- **Built on SmartTube** by **@yuliskov** — huge credit, none of this exists without that engine: https://github.com/yuliskov/SmartTube
- "YouTube" is a trademark of Google LLC. NewTube is not affiliated with, endorsed by, or sponsored by Google/YouTube.

## FAQ

**Is this just a reskinned NewPipe?**
No. Different lineage entirely — it's a fork of SmartTube's InnerTube engine, not NewPipe's extractor. The whole point was to keep SmartTube-style real Google login, which NewPipe doesn't do.

**Do I need root or Xposed?**
No, and no. Plain APK install. Nothing patched, nothing hooked.

**Does it need microG or Play Services to log in?**
No. Sign-in is the device-code flow (you type a short code at youtube.com/activate). Works on de-Googled ROMs. It's also entirely optional — signed-out mode works on local history.

**Will it get YouTube-broken like ad-blockers do?**
It's resilient, not magic — we're not going to say "unbreakable." It talks to YouTube's own InnerTube API rather than filtering a web page, so there's no filter list to rot; when YouTube changes the API we update the app. F-Droid packaging is planned; IzzyOnDroid listing in progress.

**Can I hide Shorts?**
Yes — unpin/hide the Shorts section and it's gone.

**Battery / data — background audio?**
Yes: background playback, screen-off audio, and PiP are all supported, with lock-screen media controls.

**Tablet support?**
Yes. The grid and player scale up; it's not letterboxed.

## Changelog

*Maintained per release. Newest on top. Full notes + APKs on the Releases page: {{RELEASES_URL}}*

**v1.0.0 — initial public release**
- First stable build. Full feature set above.
- Device-code Google sign-in (microG-free), SponsorBlock, DeArrow, PiP, background/screen-off audio, 4K/HDR, RYD.
- arm64-v8a / armeabi-v7a / x86 splits + universal APK, all with published SHA-256.

*(Reply or open an issue with bugs — include device, Android version, and the video ID if it's a playback problem. We read them.)*
