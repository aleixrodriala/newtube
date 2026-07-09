# NewTube — Go-to-Market package

Everything for launching **NewTube** — *"SmartTube, but for your Android phone"* — a free,
open-source (MIT), ad-free YouTube client for Android phones, distributed via **GitHub Releases**.
Built by forking SmartTube (Android-TV, MIT © yuliskov) and rebuilding the UI for touch.

> **Status:** app is feature-complete in testing; **not yet pushed to GitHub / released.** The
> landing page and all launch copy are ready and use placeholder URL tokens until the repo is live.

## What's here

| Path | What it is |
|------|------------|
| [`../../website/`](../../website/) | **The landing page** — deployable static download site (index.html, styles.css, main.js, assets, sitemap, robots). |
| [`landing-preview/`](landing-preview/) | Rendered PNG previews (`desktop.png`, `mobile.png`, `og-image.png`). |
| [`landing-build-spec.md`](landing-build-spec.md) | The brief the page was built to (product truth, exact copy, SEO, guardrails). |
| [`landing-candidates/`](landing-candidates/) | The two design variants (a/b) the final page was synthesized from. Archived, not deployed. |
| [`content/`](content/) | **10 launch assets** + [`content/README.md`](content/README.md) posting index (schedule + per-venue rules). |
| [`engagement-plan.md`](engagement-plan.md) | Rules of engagement, the vetted direct-reply targets, channel playbook, week-by-week timeline. |
| [`research/`](research/) | The evidence base: demand (GitHub + communities), competitors, SEO keywords, naming. |

## Decisions (locked)
- **Name:** NewTube (kept; "Untube" considered). The positioning — not the name — is the lever.
- **Positioning:** *"SmartTube, but for your Android phone."* Doubles as the SEO wedge (**"SmartTube for phone"** = real demand, no competing product).
- **License:** MIT. **Distribution:** GitHub Releases only (open source; no download-bandwidth cost). F-Droid/IzzyOnDroid planned.
- **Differentiator:** the only clean, open, touch-first phone app that keeps your **real YouTube account** (optional sign-in).

## Why this will land (from the research)
- **Demand is real & unoccupied:** a native *iOS* SmartTube clone hit **196★ in ~2 months**; multiple SmartTube issues (#3441, #236, #4821, #5113) + Reddit threads beg for a phone version; **no Android-phone SmartTube fork exists**. SmartTube's own FAQ says "no phone version."
- **SEO wedge:** own *"SmartTube for phone/mobile"* first; easy secondary wins *"minimalist youtube app android," "youtube app no ads no root."*
- **Guardrails baked into every asset:** never overclaim (reproducible builds are *in progress*, not shipped; never "unbreakable"); don't read as AI slop (a rival got roasted for it); disclose maker; obey each community's promo rules; credit SmartTube; not affiliated with Google/YouTube.

## Go-live checklist (owner actions)
1. **Push the repo public** on GitHub → gives the real `REPO_URL`.
2. **Fill URL tokens:** `sed` across `website/` and `docs/gtm/content/` to replace
   `{{REPO_URL}}`, `{{RELEASES_URL}}`, `{{SITE_URL}}` (steps in [`../../website/README.md`](../../website/README.md) and [`content/README.md`](content/README.md)). Verify: `grep -rn "{{" website docs/gtm/content` returns nothing.
3. **Cut a GitHub Release** with the APK so the Download button works. Add Fastlane metadata for IzzyOnDroid.
4. **Deploy `website/`** (GitHub Pages / Netlify / Cloudflare Pages — all free static).
5. **Swap in real screenshots** (optional; currently tasteful CSS/SVG mockups) — see below.
6. **Launch in order** per [`content/README.md`](content/README.md): stores → r/fossdroid + Show HN + Lemmy → direct replies → showcase venues → evergreen article.

## Known follow-ups
- **Real app screenshots** for the landing + store listings (emulator capture) — not blocking the first draft.
- **App-side branding** already says NewTube; if the brand ever changes it's a single find/replace.
