# NewTube → Rename: Naming Research & Shortlist

**Product:** Open-source (GPLv3), minimalist, touch-first YouTube client for Android
phones/tablets. Fork of SmartTube, rebuilt with a clean native phone UI. Ad-free,
no tracking, free, no IAP. SponsorBlock, DeArrow, Return YouTube Dislike, 4K/HDR,
background playback, PiP, subscriptions/history, optional Google sign-in.
Working name **"NewTube"** — want something better.

**Vibe:** minimalist, clean, calm, fast, native, distraction-free, ad-free,
freedom, lightweight — *"your videos, your way."*

> **Method / caveat.** All availability + risk signals below are **best-effort, not a
> legal trademark clearance.** How each was inferred:
> - **Domain available (likely):** `.app`/`.com` returns **NXDOMAIN** (DNS
>   `getaddrinfo ENOTFOUND`) = unregistered, or **redirects to a
>   registrar/broker for-sale page** (GoDaddy/Atom/Grails/fortune.domains) = registered
>   but parked & purchasable.
> - **Domain taken:** resolves to a **real live product**.
> - **GitHub:** `gh api users/<name>` → HTTP 200 = handle taken, 404 = free.
> - **Play Store:** `play.google.com/store/search?q=<name>&c=apps`.
> - **Trademark/collision:** web search for famous products/companies; risk graded
>   low / med / high with reason. Not legal advice.
> - Date of checks: 2026-07-01.

---

## 1. Longlist — 20 candidates

### (a) Tube / pipe / play family
| # | Name | Rationale / vibe |
|---|------|------------------|
| 1 | **Untube** | "Un-tube" yourself — cut the clutter/ads; freedom angle; sits naturally beside NewPipe/LibreTube/Tubular in the FOSS niche. Rebellious, clean. |
| 2 | **ClearTube** | Clear + clean + tube; states the value prop (declutter). Descriptive, calm. |
| 3 | Streamly | Friendly "-ly" on stream; approachable, soft. |
| 4 | PlayLeaf | Play + leaf; light, eco, calm minimalism. |
| 5 | Puretube | Pure, ad-free tube; wholesome. |

### (b) Minimalist / clean / lite / calm
| # | Name | Rationale / vibe |
|---|------|------------------|
| 6 | **Wisp** | Faint, light, wispy — lightweight & minimal; ethereal, calm. |
| 7 | **Klar** | German for "clear"; crisp, distinctive minimalism. |
| 8 | Lucid | Clear, transparent, effortless; premium-minimal. |
| 9 | **Plume** | Feather-light; calm, elegant, weightless. |
| 10 | **Glide** | Smooth playback; frictionless, fast. |
| 11 | Breeze | Effortless, calm, easy. |

### (c) Invented / brandable coinages
| # | Name | Rationale / vibe |
|---|------|------------------|
| 12 | **Vireo** | A small songbird; contains "vi(deo)"; distinctive real word, brandable, birdsong/lightness iconography. |
| 13 | Miru | Japanese 見る "to watch/see" — perfect meaning; short, warm. |
| 14 | **Novi** | Latin *novus* + *via* = "new way"; fresh, short, brandable. |
| 15 | Volo | Latin "I fly"; freedom, motion, short. |
| 16 | Vidly | Playful video coinage; friendly. |
| 17 | Veo | Spanish "I see/I watch" — ideal meaning; ultra-short. |

### (d) Freedom / control themes
| # | Name | Rationale / vibe |
|---|------|------------------|
| 18 | Roam | Freedom to wander your videos; open, unbound. |
| 19 | Freely | Free — no ads, no tracking; plain-spoken. |
| 20 | Unbound | No restrictions; liberation. |

---

## 2. Availability + risk findings (finalists checked)

| Name | `.app` | `.com` | GitHub handle | Play Store (exact/near) | Notable brands / collisions | TM risk |
|------|--------|--------|---------------|-------------------------|-----------------------------|---------|
| **Untube** | **NXDOMAIN — likely free** | For-sale (redirects to Atom.com listing) | `untube` taken (200) → use org e.g. `untube-app` | **No app named UnTube** (Play returns YouTube) | Only small OSS tools: sleepytaco/UnTube (playlist mgr), tomfriart/untube (self-host DL), jameskitt616/UnTube (archived FOSS frontend). None prominent, none a phone app. | **Low–Med** (tube-family caution like all peers; minor OSS namesakes) |
| **Vireo** | **NXDOMAIN — likely free** | Premium for-sale (Grails broker, $$$) | `vireo` taken (200) → use org/`getvireo` | "Vireo – Processed Food Scanner" (Eight, 10K+) — same name, unrelated category | **Vireo Video** (YouTube *marketing agency*, Vancouver); **Vireo Growth** (publicly-traded cannabis); Vireo Software (security); Vireo Systems (supplements) | **Med** (many unrelated Vireos incl. a YouTube-adjacent agency; SERP contested; no video *app*) |
| **ClearTube** | **NXDOMAIN — likely free** | Not clearly live (403/empty) | `cleartube` taken; a **ClearTube org** exists | Closest is **CleanTube – No Ad Videos** (S&G Apps, **1M+** installs) — confusably similar | **peterxjang/ClearTube** (tvOS/Apple TV YouTube client — direct same-space); multiple OSS YT-declutter userscripts/extensions | **Med–High** (existing same-space ClearTube + near-clash with 1M-install CleanTube) |
| **Novi** | For-sale (GoDaddy parked) | **Owned by Meta/Facebook** (cert altnames = facebook.com) | `novi` taken (200) | — | **Meta "Novi"** crypto wallet (2021–22, discontinued but famous, Meta owns novi.com + mark); Novi Survey; Novi AMS | **Med–High** (Meta trademark footprint) |
| **Wisp** | For-sale (GoDaddy parked) | (not fetched — parked signal on `.app`) | `wisp` taken (200) | **Swamped:** Wisp telehealth (hellowisp), Wisp social, Wisp sleep, Wisp anon chat, Wisp photo vault, games | wisp.gg (game hosting), gleam-wisp web framework, WISP RFID, Wisp CMS | **Med–High** (extreme app-name crowding → SEO/brand dilution) |
| Miru | For-sale (fortune.domains) | 403 | `miru` taken | miru official, Miru Movies, Miru Reader, etc. | **DIRECT same-space clash:** miru-project/miru-app (FOSS Android video/comics app), ThaUnknown/miru → Hayase (anime streamer) | **High** (multiple OSS Android video apps literally named Miru) — **eliminated** |
| Volo | Live product (redirects to app.getvolo.com) | getvolo.com live | `volo` taken | — | **Volo** (getvolo.com) live product; Volo city sports app | **Med–High** — eliminated |
| Klar | For-sale (GoDaddy) | live fintech | `klar` taken | — | **Klar** Mexican neobank; **Klarna** (ticker KLAR); Klar analytics; Klar marketing | **Med–High** (crowded fintech) — eliminated |
| Vidly | Premium for-sale ($6,499) | redirects to vid.ly | — | Vidly Video Manager & Player; **Vidly.tv** (Pakistani OTT) | Vid.ly (video platform), Vidly AI generator, Viddly | **High** (direct video-space clashes) — eliminated |
| Wren | **Live SaaS** (hub.wren.app) | wren.co live (carbon startup) | `wren` taken | Wren Kitchens; Wren audio | wren.co (carbon offset), Wren Kitchens (UK), wren-lang, WrenAI (OSS) | **High** — eliminated |
| Plume / Glide | — | Plume WiFi (plume.com); Glide Apps (glideapps.com) | taken | — | Plume (smart-WiFi, large); Glide (no-code platform, large) | **High** — eliminated |
| Veo | — | — | — | — | **Google Veo** (video-generation AI); Veo scooters; Veo sports cam | **High** (Google-owned mark, in video) — eliminated |

---

## 3. Scoring (1–5; higher = better)

| Name | Memorable | Brandable | Relevance | TM safety | Availability | SEO | **Total** |
|------|:---------:|:---------:|:---------:|:---------:|:------------:|:---:|:---------:|
| **Untube** | 4 | 3 | 5 | 3 | **5** | 4 | **24** |
| **Vireo** | 4 | **5** | 3 | 3 | 3 | 3 | **21** |
| **ClearTube** | 4 | 3 | 5 | 2 | 3 | 2 | **19** |
| **Novi** | 4 | 4 | 2 | 2 | 3 | 3 | **18** |
| **Wisp** | 4 | 4 | 2 | 2 | 3 | 2 | **17** |

---

## 4. Ranked shortlist + taglines

### 🥇 #1 — Untube  *(recommended)*
> **Tagline: "Untube your videos."** (alt: *"Cut the clutter. Keep the video."*)

Best overall fit for a budget-conscious, discovery-driven FOSS project. `untube.app`
is unregistered (likely free), **no Play Store app owns the name**, and only a few
low-profile OSS tools share it. Sits naturally in the NewPipe / LibreTube / Tubular
family so the audience instantly gets "ad-free YouTube alternative" (SEO win), while
the **"un-"** gives it a distinct, rebellious de-clutter/de-track identity that beats
the generic "New-" in NewTube. Caveats: tube-family names carry the same mild YouTube
trademark caution as every peer; `untube.com` and the bare `github.com/untube` handle
are taken (register an **org** like `untube-app` / `get-untube`, ship on
`untube.app`). Overall risk **Low–Med**, availability **High**.

### 🥈 #2 — Vireo  *(pick this if a distinctive standalone brand matters more than discovery)*
> **Tagline: "A lighter way to watch."** (alt: *"Small, swift, ad-free."*)

The most **brandable** option: a real word (a small songbird), elegant, contains
"vi(deo)", with ready iconography. `vireo.app` is unregistered (likely free). Downsides:
`vireo.com` is a **premium broker listing** (expensive), the `vireo` GitHub handle is
taken, and the name is **contested on the SERP** — notably **"Vireo Video," an
established YouTube-marketing agency**, plus publicly-traded **Vireo Growth** (cannabis)
and a same-name Play Store food-scanner. No *video app* collision, but you won't fully
own search results. Risk **Med**.

### 🥉 #3 — ClearTube
> **Tagline: "See clearly. Watch freely."**

Communicates the value prop (clear/clean tube) and `cleartube.app` looks free — but it's
**crowded**: an existing tvOS YouTube client (peterxjang/ClearTube), several OSS
declutter projects, a ClearTube GitHub org, and dangerous proximity to **CleanTube**
(1M+ installs). Usable but confusable. Risk **Med–High**.

### #4 — Novi
> **Tagline: "A new way to watch."**  (Latin *novus via* = "new way")

Short, clean, brandable, and the etymology is a perfect fit. But **Meta owns
`novi.com` and the "Novi" mark** (its discontinued crypto wallet) — a real trademark
shadow. No inherent video tie. Risk **Med–High**.

### #5 — Wisp
> **Tagline: "Light, calm video."**

Distinctive, and the lightweight/ethereal vibe fits perfectly. But the Play Store is
**swamped with "Wisp" apps** (telehealth, social, sleep, chat, games) — heavy brand
dilution and an SEO uphill battle. No video tie. Risk **Med–High**.

---

## 5. Recommendation

**Go with Untube.** For an open-source YouTube client that competes on F-Droid/GitHub
and in the NewPipe/LibreTube neighborhood, the winning name must (a) instantly signal
the category, (b) be cheaply and cleanly *ownable* across domain + GitHub + Play, and
(c) carry a bit of attitude. Untube does all three: `untube.app` is free, no Play app
owns it, it slots into the family for discoverability, and "un-" reframes the product
as *liberation from* the ad-laden tube — squarely on the freedom/ad-free/distraction-free
brief. It is also a clear upgrade over the interchangeable, forgettable "NewTube."

**If leadership prioritizes a distinctive, ownable brand over category-discovery,
choose Vireo instead** — but budget for a premium `.com` and accept a contested SERP
(the "Vireo Video" agency in particular).

**Suggested applicationId:** keep it flexible — `app.untube` / `com.untube.app`
(or `app.vireo` if Vireo).

*Reminder: these are best-effort signals as of 2026-07-01, not a legal trademark
clearance. Before committing, run a proper USPTO/EUIPO knockout search and confirm
domain registrability at a registrar.*
