# AlternativeTo.net — NewTube submission

Copy-paste fields for the "Add application" / "Suggest an app" form at
alternativeto.net. Maker-disclosed. Keep it factual — AlternativeTo mods
remove hype and unverifiable claims.

---

## App name
NewTube

## Tagline (one line, ~70 chars)
SmartTube, but for your Android phone — open-source, ad-free YouTube.

## Category
Video Streaming / YouTube clients

## Platforms
Android (phones and tablets)

## License
Open Source — MIT

## Pricing
Free (no ads, no in-app purchases, no subscription)

## Official links
- Website: {{SITE_URL}}
- Source & downloads (GitHub Releases): {{REPO_URL}}

---

## Description (paste into the Description box)

NewTube is a free, open-source YouTube client for Android **phones and
tablets**. It's a touch-first fork of SmartTube — the well-known ad-free
YouTube app for Android TV (MIT, © yuliskov) — rebuilt around a thumb, not a
remote. SmartTube's own FAQ answers "is there a smartphone version?" with a
flat "no," so this fills that gap rather than competing with it.

It streams straight from YouTube's internal InnerTube API (the same approach
SmartTube and the NewPipe family use), so there's **no ad-blocker, no root,
and no tracking** — ads simply aren't in the stream. It is not a NewPipe
fork; the engine lineage is SmartTube's.

The one thing that sets it apart: **optional Google sign-in** via device code
(the TV-style "enter this code" flow, no microG required). Sign in and your
real subscriptions, watch history and likes sync; stay signed out and
everything still works locally. Among open-source phone clients that's rare —
most are import-only or depend on public Piped/Invidious servers.

Not affiliated with Google or YouTube. "YouTube" is a trademark of Google LLC.
Disclosure: posted by the NewTube developers.

## Features (add as feature tags / bullet list)

- No ads (InnerTube-direct, no ad-blocker, no root)
- No tracking, no accounts required
- Optional Google sign-in (device-code) — syncs subs / history / likes
- SponsorBlock (auto-skip + timeline markers)
- DeArrow (de-clickbait titles/thumbnails)
- Return YouTube Dislike (real dislike counts)
- Background playback + lock-screen / media controls
- Picture-in-Picture
- Up to 4K / HDR, codec + quality selection
- Playback speed, styled subtitles, audio-track selection
- Touch player: double-tap ±10s seek, prev/next, swipe-to-dismiss
- Overflow: repeat, shuffle, video zoom, screen-off, queue, save-to-playlist
- Comments (with replies) + live chat
- Search with voice
- Channels, playlists, subscriptions, watch history
- Shorts as a section you can hide / unpin
- Lean 2-column video grid home; fast player
- Signed release builds from GitHub (reproducible builds in progress)
- Free & open source (MIT)

## Mark as an alternative to (tags)

- YouTube
- SmartTube
- NewPipe
- LibreTube
- ReVanced

## Suggested "likes" / comparison notes (optional, for the app page)

A couple of honest positioning notes for anyone comparing options — meant as
context, not knocks on good projects:

- **vs SmartTube:** same InnerTube engine and feature set, but a phone-native
  touch UI instead of a D-pad TV interface. SmartTube stays the better pick on
  an actual TV.
- **vs NewPipe / LibreTube:** those are solid, lighter, and don't touch your
  Google account at all — a real plus if that's what you want. NewTube's angle
  is the opposite: keep your real account (optional) and sync it, plus
  SponsorBlock/DeArrow/RYD in the box.
- **vs ReVanced:** ReVanced patches Google's own app and keeps real login via
  GmsCore; NewTube is a separate open app (no patching official APKs), so no
  per-update repatching.

Not "better than everything" — just the touch-first, open, real-login corner
of the map that was empty.

---

## Notes on links (do not paste)

- Where the form expects a download, use the GitHub **Releases** page
  ({{REPO_URL}}/releases/latest), not a bare `.apk` URL.
- Fill URL tokens once the repo is public: {{REPO_URL}} → github.com/OWNER/newtube,
  {{SITE_URL}} → newtube.app.
- SmartTube upstream credit: https://github.com/yuliskov/SmartTube
