# NewTube — landing / download page

Static, single-page **download** site for **NewTube** (the SmartTube-for-phones
fork). No build step, no framework, no tracking: plain semantic HTML + one CSS
file + one small vanilla JS file (`defer`-loaded, progressive-enhancement only —
the page is fully usable with JavaScript disabled). Just serve the folder.

The final site lives at the root of this directory. The `candidates/` folder
holds the two exploratory designs it was assembled from; it is **not** part of
the deploy — you can delete it or leave it, but don't upload it.

```
index.html            # the page (semantic HTML, SEO tags, JSON-LD)
styles.css            # one stylesheet; change --accent in :root to re-theme
main.js               # sticky nav, scroll-reveal, FAQ accordion, mobile menu (defer)
sitemap.xml
robots.txt
assets/
  favicon.svg         # SVG app-glyph favicon (modern browsers)
  apple-touch-icon.png# 180×180 PNG icon (iOS Safari ignores SVG — this is required)
  logo.svg            # standalone NewTube wordmark (if needed separately)
  og-image.png        # 1200×630 social card actually referenced by the meta tags
  og-image.svg        # 1200×630 social card — SVG source of the above
  og-template.html    # 1200×630 standalone HTML — re-render og-image.png from this
```

---

## 1. Fill the three placeholder tokens (required before deploy)

Every URL that depends on the real repo/domain is written as a **literal token**
so it can be `sed`-filled at release time. There are exactly **three** — these
are the only intentional "dead links" in the source.

| Token              | Fill with                                            | Used for                                                    |
|--------------------|------------------------------------------------------|-------------------------------------------------------------|
| `{{REPO_URL}}`     | `https://github.com/OWNER/newtube`                   | GitHub repo, ★ Star, MIT LICENSE link, footer               |
| `{{RELEASES_URL}}` | `https://github.com/OWNER/newtube/releases/latest`   | primary APK **Download** CTA + JSON-LD `downloadUrl`/`installUrl` |
| `{{DOMAIN}}`       | `https://newtube.app`                                | canonical, OG/Twitter URLs + image, sitemap, robots         |

(`OWNER` is itself a stand-in — replace with the real GitHub org/user.)

### The exact one-liner

Run from this directory. Keep `RELEASES` **derived from** `REPO` so they never
drift; token order doesn't matter (the three strings are distinct):

```bash
REPO='https://github.com/OWNER/newtube'
RELEASES="$REPO/releases/latest"
DOMAIN='https://newtube.app'

grep -rlZ '{{' index.html styles.css main.js sitemap.xml robots.txt assets \
  | xargs -0 sed -i \
    -e "s#{{RELEASES_URL}}#${RELEASES}#g" \
    -e "s#{{REPO_URL}}#${REPO}#g" \
    -e "s#{{DOMAIN}}#${DOMAIN}#g"
```

Verify nothing is left:

```bash
grep -rn '{{' index.html styles.css main.js sitemap.xml robots.txt assets \
  && echo "TOKENS REMAIN" || echo "all filled"
```

> macOS/BSD `sed`: use `sed -i ''` (empty backup arg) instead of GNU `sed -i`.

---

## 2. Render the social card PNG (`assets/og-image.png`)

The meta tags reference `assets/og-image.png` (1200×630). A pre-rendered copy is
already committed, but regenerate it whenever you edit `og-template.html`
(e.g. after changing `--accent`). Render from the standalone HTML — it matches
the site exactly and is font-robust:

```bash
# a) Playwright (downloads a headless Chromium on first run)
npx playwright screenshot --viewport-size=1200,630 \
  assets/og-template.html assets/og-image.png

# b) any system / cached Chromium (no npm needed)
chrome --headless --window-size=1200,630 --default-background-color=00000000 \
  --screenshot=assets/og-image.png assets/og-template.html

# c) rasterize the SVG source instead
rsvg-convert -w 1200 -h 630 assets/og-image.svg -o assets/og-image.png
```

**App icon:** `assets/apple-touch-icon.png` (180×180) is a real raster PNG on
purpose — iOS Safari silently ignores an SVG `apple-touch-icon`. If you re-skin
the accent, regenerate it to match (any 180×180 export of the favicon glyph on a
`#0B0B0D` field works; iOS applies its own corner mask).

---

## 3. Deploy (any free static host)

It's just static files — upload the folder (minus `candidates/` and this README
if you prefer). Point the host at the directory that contains `index.html`.

### GitHub Pages (free)
1. Push the filled site to a repo (e.g. the `website/` subfolder, or its own repo/branch).
2. Repo **Settings → Pages → Build and deployment → Source: Deploy from a branch**.
3. Pick the branch and folder (`/root` or `/docs`), Save. Live at
   `https://OWNER.github.io/REPO/` — or add a custom domain (`{{DOMAIN}}`) and a
   `CNAME` file, then enable **Enforce HTTPS**.

### Netlify (free)
- Drag-and-drop the folder at <https://app.netlify.com/drop>, **or** connect the
  repo with **Publish directory = `website`** and **Build command = (none)**.
  Add your custom domain under **Domain settings**.

### Cloudflare Pages (free)
- **Create a project → Connect to Git** (or **Direct Upload**). Framework preset
  **None**, **Build command** empty, **Build output directory = `website`** (or `/`).
  Add the custom domain under the project's **Custom domains** tab.

All three serve `sitemap.xml`/`robots.txt` from the root automatically. After the
first deploy, submit `{{DOMAIN}}/sitemap.xml` in Google Search Console.

---

## 4. Local preview

```bash
python3 -m http.server 8080   # then open http://localhost:8080
```

---

## Notes

- **It's a download page, not a waitlist.** The primary CTA links to
  `{{RELEASES_URL}}` (the latest GitHub release APK). No email capture.
- **Accuracy:** copy reflects the real, shipped feature set. No fabricated
  ratings — the `SoftwareApplication` JSON-LD deliberately omits
  `aggregateRating`. The comparison table is footnoted and marked
  "typical default configs as of 2026."
- **Credit + legal (keep these):** the footer keeps the SmartTube attribution
  ("fork of SmartTube, MIT, © yuliskov — please support upstream", linked) and
  the "not affiliated with Google or YouTube / YouTube is a trademark of Google
  LLC" notice, plus the MIT License link.
- **Theming is one knob:** `--accent` in `styles.css :root` drives every red on
  the page (all other reds derive from it via `color-mix`). The only hard-coded
  brand reds live in the four standalone assets — `favicon.svg`, `logo.svg`,
  `og-image.svg`, `og-template.html` — update those by hand if you change hue.
  ⚠️ `--on-accent` (`#12060a`, the near-black text that sits *on* the accent in
  buttons/chips) assumes a **red-ish** accent; if you shift `--accent` toward a
  light hue (yellow/cyan), re-check its contrast and switch it to `#fff`.
- **Accessibility:** semantic landmarks, one `<h1>`, visible focus, ≥44px tap
  targets, AA-contrast text (with a `prefers-contrast: more` boost), a
  keyboard-operable FAQ accordion, and `prefers-reduced-motion` handling.
- **Performance:** system-font stack (no web-font request to block the LCP text
  H1), no framework, one small deferred JS file, sized media (no layout shift).
