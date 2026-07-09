# NewTube — Product Hunt launch kit

*Channel notes (from `docs/gtm/engagement-plan.md` §3 + `research/demand-communities.md`):
Product Hunt is **P3 — launch when polished**, not the tip of the spear. This audience is
broad and less FOSS-native than r/fossdroid or HN, so the tone here is upbeat and demo-led,
but every claim stays honest. One launch per product per day; best window **Tue–Thu, 00:01 PT**.
Ship with a **demo GIF** and line up a handful of real early supporters before the clock flips.
We disclose we're the makers in the first comment. Do not badmouth NewPipe / LibreTube / ReVanced.*

---

## Product name

**NewTube**

(subtitle/one-liner used under the name: *Ad-free YouTube for Android phones — open source*)

## Tagline (58 chars, under the 60-char PH cap)

**SmartTube, but for your Android phone — ad-free, open**

*Alt if you want the app name to carry more weight in search:*
`Open-source, ad-free YouTube client for Android phones` (54 chars)

## Description (2–3 sentences)

NewTube is a free, open-source (MIT) YouTube app for Android phones and tablets — it's a
touch-first port of SmartTube, the beloved ad-free client that until now only ran on Android
TV. You get no ads, SponsorBlock, DeArrow, background play, PiP, up to 4K/HDR, and — the part
nobody else nails — your **real** YouTube account via optional Google sign-in (no microG, no
root), or it works completely signed-out. Streams come straight from YouTube's own InnerTube
API, and every build is open-source and signed, published on GitHub Releases (with
reproducible builds on the way).

---

## Maker's first comment (post this the second the launch goes live)

> Hey Product Hunt — I'm one of the makers, so full disclosure up front. 👋
>
> This started as pure personal frustration. I love SmartTube on my living-room TV, so one
> night I sideloaded it onto my Pixel expecting magic — and got a UI built for a D-pad: focus
> rings everywhere, buttons the size of a grain of rice, nothing you could actually *tap*. It
> worked, technically, and it was miserable. Meanwhile the phone options each made me give up
> something: NewPipe and Tubular are great but can't log into my account; LibreTube leans on
> public Piped/Invidious instances; ReVanced patches Google's own app, which I didn't want to
> babysit. (Genuine respect to all three — they're the reason this space exists.) Nothing let
> me keep my real subscriptions and history *and* have a clean, touch-native app.
>
> So we kept SmartTube's proven InnerTube engine — this is **not** a NewPipe fork — and rebuilt
> the whole front end for thumbs: a lean two-column home grid, double-tap to seek, swipe-to-
> dismiss the player, PiP, background + screen-off audio, SponsorBlock auto-skip, DeArrow, real
> Return YouTube Dislike, comments, live chat, voice search. Sign in with the Google device-code
> flow if you want your subs/likes/history to sync, or don't — it's genuinely fine signed-out.
>
> One thing I care about a lot: after SmartTube's official APK got hit with malware in Dec 2025,
> "just trust the APK" isn't good enough. So NewTube is MIT, every release is built in CI from
> tagged source and signed with a stable key, and you can read exactly what's in it. Reproducible
> builds — where you rebuild and byte-match the APK yourself — are the piece I'm still wiring up;
> I'll shout about it the day it lands rather than claim it early.
>
> It's real and installable today from GitHub Releases: {{RELEASES_URL}} — source at
> {{REPO_URL}}, and there's a plain-English landing page at {{SITE_URL}}.
>
> What I'd love from you: **install it and tell me what breaks.** Especially — how does the
> player feel on *your* phone? Any device where a stream caps at low res or the seek gesture
> feels off? Which section would you reach for that's missing? I'll be in the comments all day.
> And huge thanks to @yuliskov, whose SmartTube (https://github.com/yuliskov/SmartTube) this
> stands on. NewTube isn't affiliated with Google or YouTube; "YouTube" is a trademark of
> Google LLC.

---

## Topics / tags

Pick up to 3 primary PH topics (first two are the strongest fit):

- **Android** — core platform, best conversion here
- **Open Source** — MIT; the crowd that shows up for these threads is our crowd
- **Privacy** — no ads, no tracking, no account required

Secondary / if PH offers more slots or for the "also in" field:
`Video Streaming` · `GitHub` · `Video Players`

Hashtags for the cross-post to X/Mastodon when the launch goes live:
`#OpenSource #Android #YouTube #FOSS #Privacy #SponsorBlock`

---

## Gallery / asset checklist

PH gallery images are **1270×760** (min), thumbnail **240×240**, first slide is what stops the
scroll. Order matters — lead with motion, then the money shots, then proof.

1. **Slide 1 — demo GIF (make this new; highest priority).** 10–15s screen capture from the
   Android emulator, exported at 1270×760. Show, in order: tap a card on the home grid → player
   opens → double-tap-right to seek +10s → a SponsorBlock segment auto-skips (with the yellow
   marker visible) → press home, audio keeps playing with lock-screen controls. This single GIF
   sells more than any bullet list. (Emulator-verify commands are in the NewTube port memory
   notes.)

2. **Slide 2 — Home screen (real emulator screenshot).** The lean two-column video grid with
   bottom nav. Reference the landing mockup for framing:
   `website/index.html` → the `.phone-home` / `.phone-static` home mockup. **Use a real capture,
   not the stylized CSS mockup** — the landing page itself flags those as illustrations
   ("Actual screenshots swap in at release"), and PH viewers reward the real thing.

3. **Slide 3 — Player / watch page (real emulator screenshot).** Title, views/date, Like +
   Dislike (real RYD number), Share, Save, Subscribe, and the up-next list. Same framing as the
   landing `.phone` player mockup in `website/index.html`.

4. **Slide 4 — Quality + player overflow.** Capture the quality sheet showing **up to 4K/HDR**
   plus the overflow menu (repeat / shuffle / zoom / background / screen-off / save-to-playlist)
   — proof of depth in one frame.

5. **Slide 5 — Differentiator card (light graphic).** A simple dark slide, on brand
   (bg `#0E0E10`, accent red `--accent`), with the one honest claim rivals can't all make:
   *"The only clean, open, touch-first phone app that keeps your real YouTube account."* Small
   footnote: `MIT · open-source, signed builds · no ads · no tracking`.

6. **Thumbnail (240×240).** Export from `website/assets/logo.svg` (or the app launcher icon) on
   the dark background. `website/assets/apple-touch-icon.png` is already the right shape if you
   need it fast.

7. **Social / OG share image.** Reuse `website/assets/og-image.png` (1200×630, the
   "SmartTube, but for your Android phone." hero) for the launch tweet/Mastodon/Lemmy cross-post
   card — it's already made. It's the wrong aspect ratio for a PH gallery slide, so don't use it
   there; keep it for the share links.

**Do NOT** attach a raw `.apk` anywhere; the "Get it" / links point to **GitHub Releases**
(`{{RELEASES_URL}}`), the repo (`{{REPO_URL}}`), and the landing page (`{{SITE_URL}}`).

---

### Pre-launch checklist (day before)
- [ ] Demo GIF rendered and under PH's file-size limit; looks sharp at 1270×760.
- [ ] Real emulator screenshots captured (home, player, quality/overflow) — no stylized mockups.
- [ ] Placeholders (`{{REPO_URL}}`, `{{RELEASES_URL}}`, `{{SITE_URL}}`) filled with live URLs.
- [ ] Maker's first comment pasted and ready to post at 00:01 PT.
- [ ] 8–10 early supporters pinged (personally, not spammed) to visit on launch morning.
- [ ] Repo LICENSE (MIT) visible; latest signed Release tagged with the APK attached.
