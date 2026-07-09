# Landing Page — Build Spec

**Name:** NewTube · **License:** MIT · **Type:** real DOWNLOAD page (app is built), NOT a waitlist.
**Positioning (lead everywhere):** *"SmartTube, but for your Android phone."*

Placeholders (documented in `website/README.md`; one `sed` fills them once the repo is pushed):
`{{REPO_URL}}` = `https://github.com/OWNER/newtube` · `{{RELEASES_URL}}` = `{{REPO_URL}}/releases/latest` · `{{DOMAIN}}` = `https://newtube.app`

## Product truth (accurate — do not overclaim)
- Open-source (**MIT**), ad-free YouTube client for Android **phones/tablets**; a touch-first fork of **SmartTube** (ad-free YouTube app for Android **TV**; MIT, © yuliskov). App is BUILT and available.
- **No ads** — streams via YouTube's internal InnerTube API directly (like NewPipe/SmartTube). No ad-blocker, **no root**, no tracking, free, no IAP.
- **Minimalist & fast:** lean 2-column grid home + bottom nav + drawer to every section; smooth fast player.
- **Player:** watch page (title, views/date, Like/Dislike + real Return YouTube Dislike, Share, Subscribe, expandable description, related/up-next). Touch controls, double-tap ±10s, prev/next, swipe-to-dismiss. Overflow: repeat/shuffle/zoom/background/screen-off/stats/rotate/save-to-playlist/queue. Up to **4K/HDR** + codec, speed, **styled subtitles**, audio-track select.
- **SponsorBlock** (auto-skip + markers), **DeArrow** (de-clickbait). **Background playback** + media/lock-screen controls. **Picture-in-Picture**. Comments (+replies/like). Live chat. Search + voice. Channels, playlists, subscriptions, history.
- **Optional Google sign-in** (device-code) → syncs subs/history/likes; also works fully signed-out.
- Not affiliated with Google/YouTube. "YouTube" is a trademark of Google LLC.
- **Differentiation (the one thing):** the only clean, open, touch-first phone app that keeps your **real YouTube account**. Say "resilient/fast-updating," never "unbreakable." Emphasize transparent, reproducible, signed builds.

## Distribution / CTA
- Primary CTA: **"Download for Android"** → `{{RELEASES_URL}}` (APK from GitHub Releases). Secondary: **"View on GitHub" / ★ Star** → `{{REPO_URL}}`.
- Include a short **"How to install"** (download APK → allow install from this source → open; optional sign-in). Good for trust + SEO.
- Small line: "Free · open source (MIT) · APK from GitHub Releases · F-Droid planned."

## SEO (fold in + verify)
- `<title>` ~60: **"NewTube — SmartTube for Android Phones · Ad-Free YouTube"**
- meta desc ~155: *"NewTube is a free, open-source, ad-free YouTube client for Android phones — the SmartTube experience on mobile. SponsorBlock, background play, PiP, 4K, no root."*
- One `<h1>` = the positioning line incl. "SmartTube"+"Android phone"+"ad-free". Logical `<h2>`s using targets: *SmartTube for phone, ad-free youtube app android, minimalist youtube app android, youtube app no ads no root, open source youtube client android, newpipe alternative, sponsorblock youtube app android, youtube background play android*.
- **JSON-LD:** `SoftwareApplication` (name Untube, OS Android, applicationCategory MultimediaApplication, offers price 0 — NO fake ratings) + `FAQPage`.
- **OG + Twitter** tags + `og-image` 1200×630 (create `og-image.svg`; a PNG export is wired later). `sitemap.xml`, `robots.txt` (allow all + sitemap), canonical.
- Fast + accessible: single small CSS, minimal vanilla JS, lazy imgs with width/height (no CLS), system-font stack, alt text, contrast, focus states, tap targets ≥44px, `prefers-reduced-motion`.

## FAQ (FAQPage schema) — answer these
Is there a SmartTube for Android phones? · How to watch YouTube without ads on Android without root? · Is Untube free and open source? · Does it support background play and PiP? · How is Untube different from NewPipe, LibreTube, and ReVanced? · Can I hide/disable Shorts? · Does it need a Google account / does it track me? · Does it support SponsorBlock, 4K/HDR, and Return YouTube Dislike?

## Design
- Dark, sleek, minimalist, premium — matches app (bg ~#0E0E10, surface ~#1A1A1D, white text, secondary ~#A0A0A8). Accent = single CSS var `--accent` (refined red, heritage tie to "tube"; swappable). Big confident type, generous whitespace, subtle scroll/hover motion (respect reduced-motion).
- Wordmark logo "Untube" + small play/tube glyph (SVG). favicon.svg.
- **Phone mockups (CSS/SVG):** Home lean 2-col grid + Player watch page, dark theme, tasteful — these sell the product. Real emulator screenshots swapped in later.
- **Simple:** one page, ~6 tight sections. Target Lighthouse 95+.

## Sections
Nav (sticky) → Hero (H1 + Download/GitHub CTAs + trust chips + mockup) → Value props (6–8 cards) → "SmartTube engine, phone-native" thesis + 3-step how-it-works → Comparison table (Untube vs YouTube app, NewPipe, LibreTube, ReVanced; SmartTube=TV) → Screenshots gallery → How to install → FAQ (accordion) → Final CTA → Footer (MIT + SmartTube credit + upstream link + Google/YouTube disclaimer + repo/license links).

## Guardrails
Accurate claims only. Fair comparison (verify each cell vs `research/competitors.md`; no FUD). Keep MIT + SmartTube credit + non-affiliation disclaimer. Download page, not waitlist. Must feel crafted/specific — NOT generic AI slop.
