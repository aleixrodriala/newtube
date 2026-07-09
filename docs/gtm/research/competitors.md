# Competitive Landscape & Positioning — NewTube (working name)

_Research date: 2026-07-01. Product: open-source (GPLv3), ad-free, touch-first YouTube client for Android phones/tablets — a fork of SmartTube's engine with a clean native phone UI and optional Google device-code sign-in._

All non-obvious claims are cited inline. Sources are primary where possible (GitHub repos/issues, F-Droid pages, official docs/release notes); news/secondary sources are used for incidents and pricing.

---

## Executive summary

Three structural facts define the market:

1. **Backend architecture is the real battleground, and 2025–26 was brutal for two of the three approaches.**
   - **InnerTube-direct / on-device extraction** (SmartTube, NewPipe family, FreeTube's default, SkyTube, Grayjay): most resilient, but even this camp got hit — YouTube's **SABR** rollout knocked the entire NewPipe family down to **360p-only** for months ([NewPipe #12126](https://github.com/TeamNewPipe/NewPipe/issues/12126), [#13320](https://github.com/TeamNewPipe/NewPipe/issues/13320)).
   - **Instance-dependent** (Piped → LibreTube historically; Invidious → Clipious, FreeTube optional): **collapsed.** Public Piped instances died — LibreTube **removed public-instance support** in v31 and defaulted to local extraction ([LibreTube #7335](https://github.com/libre-tube/LibreTube/issues/7335)). Invidious is described as "nearing end of life" after Google crackdowns ([techrights, 2024-10](https://techrights.org/n/2024/10/03/Invidious_Seems_to_be_Nearing_End_of_Life_After_Repeated_Crackd.shtml)).
   - **Patch-the-official-APK** (ReVanced / RVX / Morphe): works and keeps real Google login, but carries install friction, per-update breakage, GmsCore/microG headaches, account-ban risk, and now **inter-project DMCA warfare** ([github/dmca 2026-03-12](https://github.com/github/dmca/blob/master/2026/03/2026-03-12-morpheapp.md)).

2. **No FOSS phone client offers real Google-account sign-in with subs/history/likes sync.** Every one is local-only, import-only, or tied to a dying Piped/Invidious account. The only ways to keep your *real* YouTube account today are ReVanced (proprietary patched app + microG) or SmartTube (**TV-only**). This is the largest open gap.

3. **SmartTube — the engine we fork — is explicitly, deliberately TV/D-pad only.** Its FAQ answers "Is there a Smartphone version of SmartTube?" with a flat **"NO,"** and states "touch input is not supported" ([smarttube.app/faq](https://smarttube.app/faq/)). The wedge we occupy is officially unoccupied by our own upstream.

**Our thesis is validated**, with three honesty caveats (see [Thesis validation](#thesis-validation--challenge)).

---

## Master comparison table

| App | Platform | Truly FOSS? | Backend | Real Google login + sync | SponsorBlock | DeArrow | RYD | 4K/HDR | Bg/PiP | On Play? | Health (latest) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **NewTube (us)** | **Phone/tablet, touch** | **Yes (GPLv3)** | **InnerTube-direct (SmartTube engine)** | **Yes (device-code, optional)** | ✓ | ✓ | ✓ | ✓ (to 4K/HDR) | ✓/✓ | TBD | Pre-launch |
| SmartTube | Android **TV**, D-pad | Yes (**MIT**) | InnerTube-direct | Yes (device-code) | ✓ | ✓ | ✓ | ✓ (to 8K/HDR) | ✓/✓ | No | Active, v31.94 ~Jun 2026 |
| NewPipe | Android phone | Yes (GPLv3) | NewPipeExtractor (direct) | **No** (import only) | ✗ (forks) | ✗ | ✗ | limited¹ | ✓/✓ | No | Active, v0.28.8 Jun 2026 |
| LibreTube | Android phone | Yes (GPLv3) | Piped → **now local** | No (Piped acct, broken) | ✓ | ✓ | ✓ | limited¹ | ✓/✓ | No | Active, v31.4 May 2026 |
| Tubular | Android phone | Yes (GPLv3) | NewPipeExtractor (direct) | No (planned) | ✓ | ✗ (req.) | ✓ | limited¹ | ✓/✓ | No | Active, v0.28.4 Mar 2026 |
| PipePipe | Android phone | Yes (GPLv3) | PipePipeExtractor (direct) | No (cookie=streams only) | ✓ | ✗ | ✓ | ✓ | ✓/~ | No | Active, ~v5.2 Jun 2026 |
| Grayjay | Android + Desktop | **No** (Source-First) | Own plugins (direct) | No (local follows) | ~ (Sponsorskip) | ✗ | ✗ | ✓ | ✓/✓ | No | Very active (FUTO) |
| ReVanced | Android (patched YT) | Tooling yes; **app is Google's** | Patches official APK | **Yes (via GmsCore)** | ✓ | ✗ | ✓ | ✓ | ✓/✓ | No | Active but under DMCA/legal churn |
| ReVanced Extended | Android (patched YT) | Same as ReVanced | Patches official APK | Yes (GmsCore) | ✓ | ✗ | ✓ | ✓ | ✓/✓ | No | **Discontinued 2025-12-31 → Morphe** |
| SkyTube | Android phone | Yes (GPLv3)² | NewPipeExtractor (direct) | No (import only) | ✓ | ✗ | ✗ | limited¹ | ✗ adv. | F-Droid | Active, v2.999 May 2026 |
| Clipious | Android + TV | Yes (NonFreeNet flag) | **Invidious instances** | No (Invidious acct) | ✓ | ✓ | ✓ | inst.-dep | ✓/✓ | No | App active v1.22 (backend dying) |
| Seal | Android | Yes (GPLv3) | yt-dlp (**downloader**) | N/A (no viewer) | flags only | ✗ | ✗ | download | N/A | No | Stable stale (v1.13.1) |
| FreeTube | **Desktop only** | Yes (AGPLv3) | Local API (direct) + Invidious opt. | No (local/import) | ✓ | ✓ | ✓ | ✓ | ✓/✓ (mini) | N/A | Active beta v0.24.1 Jun 2026 |
| Official YouTube | All | No | Official | Yes | ✗ | ✗ | ✗ | ✓ | Premium-gated | Yes | N/A |
| YouTube Premium | All | No ($15.99/mo US) | Official | Yes | ✗ | ✗ | ✗ | ✓ | ✓ | Yes | N/A |

¹ "limited" = NewPipe-family clients were capped near **360p** by SABR in 2025–26 and are still restoring higher resolutions via client-impersonation workarounds.
² SkyTube vanilla is GPLv3/FOSS; the **"Extra"** flavor adds closed-source official-player + Chromecast and ships off-F-Droid.

---

## Per-app notes (with cited weaknesses)

### SmartTube — our upstream engine
- **What/platform:** Ad-free media player for Android **TV**/Google TV/Shield/boxes (and pre-Oct-2025 Fire TV). **Not touch/phone** — remote/D-pad only; FAQ explicitly says no smartphone version and "touch input is not supported" ([faq](https://smarttube.app/faq/)).
- **FOSS/license:** **MIT** (permissive — verified in repo LICENSE). ⚠️ Correction to brief: not GPLv3. This *helps* us — MIT lets us fork, restyle for touch, and relicense our fork as GPLv3 with only attribution. Repo `yuliskov/SmartTube`, ~30.8k stars, GitHub-only APK (never Play).
- **Backend:** Direct to YouTube InnerTube/Google endpoints — **no Piped/Invidious** ([PRIVACY.md](https://github.com/yuliskov/SmartTube/blob/master/PRIVACY.md)). This is the resilience story we inherit.
- **Features (verified):** SponsorBlock, **DeArrow**, **Return YouTube Dislike**, up to 8K/60fps/HDR, codec + quality selection, Auto Frame Rate, playback speed, subtitles, audio-track selection, background play, PiP, read-only live chat, casting, **Google device-code / QR sign-in** that syncs subs/history/playlists/likes/recommendations, Drive backup, multi-account.
- **Weaknesses:** TV-only UX (our whole opening); comments "unstable"; no downloads; casting is code-based. **Trust incident:** Nov–Dec 2025 supply-chain compromise — malicious `libalphasdk.so` in official builds v30.43–30.51 harvested device data before being fixed in v30.55+ ([BleepingComputer](https://www.bleepingcomputer.com/news/security/smarttube-youtube-app-for-android-tv-breached-to-push-malicious-update/), [gHacks](https://www.ghacks.net/2025/12/01/smarttube-app-was-infected-by-malware-heres-what-happened/)). A clean, transparently-built/signed fork is a marketable trust angle.
- **Health:** Very active (~v31.94, Jun 2026, 1000+ releases); bus factor ≈ one dev.

### NewPipe
- Original libre Android front-end (GPLv3, F-Droid/APK, not Play). Own **NewPipeExtractor**, no API key, no login — works de-Googled ([F-Droid](https://f-droid.org/en/packages/org.schabi.newpipe/)).
- **No Google login / no sync** — requested since 2017, repeatedly declined ([#679](https://github.com/TeamNewPipe/NewPipe/issues/679), [#5325](https://github.com/TeamNewPipe/NewPipe/issues/5325)); subscriptions are import/export only, and import itself breaks ([#13442](https://github.com/TeamNewPipe/NewPipe/issues/13442)).
- **Biggest 2025–26 complaint:** SABR **360p cap** — mass frustration, long open issues, temporary hotfixes ([#12248](https://github.com/TeamNewPipe/NewPipe/issues/12248), [#13577](https://github.com/TeamNewPipe/NewPipe/issues/13577)). Ships **no** SponsorBlock/RYD/DeArrow (by design — forks add them). Active (v0.28.8, Jun 2026).

### LibreTube
- Modern-Material Android front-end (GPLv3). Built to route everything through **Piped** for privacy — inherited instance downtime/rate-limiting. That model **collapsed**: v31 "removes support for using public Piped instances… none working in a while" and defaults to local extraction ([#7335](https://github.com/libre-tube/LibreTube/issues/7335), [#7349](https://github.com/libre-tube/LibreTube/discussions/7349)).
- Sync is via an **optional Piped account** (not Google) — now hard to use since public instances died; switching instances desyncs data ([#7723](https://github.com/libre-tube/LibreTube/issues/7723)). Nicest UI of the family, ships **DeArrow**. New "Full Local Mode" has rough edges (empty feeds, [#6942](https://github.com/libre-tube/LibreTube/issues/6942)). Active (v31.4, May 2026).

### Tubular
- NewPipe fork that merges **SponsorBlock + RYD** ([repo](https://github.com/polymorphicshade/Tubular)). Identical NewPipeExtractor backend → inherits every NewPipe fix **and** breakage (incl. SABR). **No login/sync** (cookie import only "planned" in README). **DeArrow** long-requested but not listed as shipped ([#170](https://github.com/polymorphicshade/Tubular/issues/170)). Active (v0.28.4, Mar 2026).

### PipePipe
- Hard NewPipe fork, multi-service (YouTube/BiliBili/NicoNico), own **PipePipeExtractor** ([repo](https://github.com/InfinityLoop1308/PipePipe)). Most feature-rich of the family (SponsorBlock + RYD + danmaku/live-chat overlay + sleep timer + gesture seek). **"Login" is cookie import used ONLY to fetch playback streams** — not account sync. Community notes occasional crashes/PiP hiccups. Active (~v5.2, Jun 2026).

### Grayjay (FUTO / Rossmann)
- Multi-platform "follow creators, not platforms" aggregator (Android + Desktop; no iOS). Plugin backends scrape each platform directly.
- **NOT truly FOSS** — FUTO's **"Source First" license** is source-available with a perpetual **non-commercial** restriction and no time-delayed conversion; the repo license reads `NOASSERTION` ([isitreallyfoss](https://isitreallyfoss.com/projects/grayjay/), [FUTO statement](https://www.futo.org/about/futo-statement-on-opensource/)). Rossmann's early "open source" marketing drew "openwashing" criticism ([hiphish](https://hiphish.github.io/blog/2023/10/18/grayjay-is-not-open-source/)). Plugins are GPL, app shell is not.
- **Login optional; subscriptions stored locally** — "follow creators locally," with optional real-account import ([faq](https://grayjay.app/faq.html)). Free with optional **one-time** payment.
- **Weaknesses:** YouTube actively blocks it (Jan 2025 "OP67:1" playback blocks needing a stable-channel fix, [#2351](https://github.com/futo-org/grayjay-android/issues/2351)); users report **YouTube account restrictions when downloading** ([#2777](https://github.com/futo-org/grayjay-android/issues/2777)); only "Sponsorskip," not full SponsorBlock ([grayspon fork](https://github.com/pantsufan/grayspon)); "not a finished, polished product" per reviewers. Best-funded of the bunch, very active.

### ReVanced / ReVanced Extended
- **ReVanced patches the official YouTube APK.** Tooling (`revanced-patcher`, `revanced-patches`) is GPLv3, but **the app you run is Google's proprietary YouTube** — no pre-modified APK is shipped; you supply and patch the exact recommended version ([Gizmochina](https://www.gizmochina.com/2026/03/29/revanced-dmca-takedown-github/)).
- **Real Google login works** via **GmsCore/microG** on non-root ([GrapheneOS forum](https://discuss.grapheneos.org/d/21547)). This is the one thing the FOSS phone clients lack — but at the cost of install friction, recurring login breakage ([GmsCore #2142](https://github.com/microg/GmsCore/issues/2142)), per-update patch breakage, and account-ban risk (~18% got warnings in a cited survey; secondary account recommended, [revanced.net/faq](https://revanced.net/faq)).
- **Ecosystem churn:** **RVX discontinued 2025-12-31** in protest ([#3334](https://github.com/inotia00/ReVanced_Extended/issues/3334)) → successor **Morphe** (Jan 2026) → **Morphe filed a March 2026 DMCA** against ReVanced over a GPLv3 §7b attribution dispute; GitHub blocked the repo (HTTP 451), development continued on a GitLab mirror ([DMCA](https://github.com/github/dmca/blob/master/2026/03/2026-03-12-morpheapp.md)). Users get stranded when maintainers quit — a structural weakness of the patch model.

### SkyTube
- Ad-free Android client (GPLv3, F-Droid). **NewPipeExtractor** backend (direct, not Invidious) → same breakage exposure ([#345](https://github.com/SkyTubeTeam/SkyTube/issues/345); F-Droid breakage threads). Strong content filtering (channel black/whitelist, hide low-view/high-dislike). **No Google login** (import only). No advertised background/PiP/DeArrow. Vanilla lacks Chromecast (Extra-only, closed-source). **Not abandoned** — v2.999, May 2026.

### Clipious
- Flutter Android/TV **front-end for Invidious** — does no extraction itself, **fully instance-dependent** ([repo](https://github.com/lamarios/clipious)). Feature-rich (SponsorBlock, DeArrow, RYD, background, downloads). **Crux weakness:** Invidious is under sustained YouTube blocking → recurring "video can't be played / not loading" issues ([#96](https://github.com/lamarios/clipious/issues/96), [#41](https://github.com/lamarios/clipious/issues/41)); practical advice is "self-host for reliability." Login = **Invidious account**, not Google. App healthy (v1.22, Sep 2025); backend ecosystem is the risk.

### Seal
- Material-You **downloader** built on yt-dlp (GPLv3). **Not a viewer** — no streaming, browsing, feed, or subscriptions. Complementary, not competitive, on the watching axis. yt-dlp breakage requires updates; **stable line is stale** (v1.13.1 while v2.0 sat in long alpha), spawning a "Seal Plus" fork for faster updates. No account model.

### FreeTube
- Privacy-focused **desktop-only** client (Win/Mac/Linux, AGPLv3). **Local API (direct) by default**, "quicker than Invidious and less likely to fail due to IP blocks," Invidious optional ([docs](https://docs.freetubeapp.io/usage/local-api/)). Ships SponsorBlock + DeArrow + RYD + PiP. **No Google login** — local subs / Takeout import only. **Decisive gap: no official mobile** (only an unofficial, lagging community Android port). Still breaks on YouTube changes ([#7885](https://github.com/FreeTubeApp/FreeTube/issues/7885)). Active beta (v0.24.1, Jun 2026).

### Official YouTube app + Premium (baselines)
- Official app: heavy/escalating ads (30s unskippable CTV ads Mar 2026; reported 90s ads Apr 2026), full account tracking, aggressive adblock crackdown since 2023 ([TechCrunch](https://techcrunch.com/2023/11/01/youtube-is-now-cracking-down-on-ad-blockers-globally/), [webpronews](https://www.webpronews.com/youtubes-90-second-unskippable-ads-a-bug-a-backlash-and-a-billion-dollar-ad-machine-under-scrutiny/)). No SponsorBlock/DeArrow/RYD.
- **Premium:** US **$15.99/mo** individual / $26.99 family / $8.99 student; unlocks ad-free + background + downloads + YT Music. **Even paying users get no SponsorBlock/DeArrow/RYD** — we beat Premium on both price ($0) and these features.

---

## Differentiation matrix — the four axes that matter

| | Phone-native **touch** UI | **Real Google** login + sync (optional) | **Truly FOSS** (OSI) | **Resilient direct** backend (no dying instances, no patching) | Full power features (SB/DeArrow/RYD/4K/bg/PiP) |
|---|---|---|---|---|---|
| **NewTube (us)** | ✅ | ✅ | ✅ (GPLv3) | ✅ | ✅ |
| SmartTube | ❌ (TV/D-pad) | ✅ | ✅ (MIT) | ✅ | ✅ |
| NewPipe | ✅ | ❌ | ✅ | ⚠️ (SABR-hit) | ❌ (no SB/RYD/DeArrow) |
| LibreTube | ✅ | ❌ (Piped acct) | ✅ | ⚠️ (post-Piped) | ✅ (no login) |
| Tubular | ✅ | ❌ | ✅ | ⚠️ | ⚠️ (no DeArrow) |
| PipePipe | ✅ | ❌ (cookie=streams) | ✅ | ⚠️ | ✅ (no DeArrow) |
| Grayjay | ✅ | ❌ (local follows) | ❌ (Source-First) | ⚠️ (YT blocks it) | ⚠️ (Sponsorskip only) |
| ReVanced/Morphe | ✅ (it's Google's UI) | ✅ (GmsCore) | ❌ (app is Google's) | ❌ (patches official app) | ✅ (no DeArrow) |
| SkyTube | ✅ (dated) | ❌ | ✅ | ⚠️ | ❌ |
| Clipious | ✅ | ❌ (Invidious acct) | ✅ | ❌ (Invidious dying) | ✅ |
| FreeTube | ❌ (desktop) | ❌ | ✅ | ✅ (local API) | ✅ |

**Only NewTube and SmartTube hit all five columns — and SmartTube can't be used with a thumb.** No competitor combines a **touch-first phone UI + real optional Google sign-in + true FOSS + a resilient direct backend + the full power-feature set**. That intersection is empty today.

---

## The gap we fill

Every FOSS phone client forces a painful trade:
- Want your **real YouTube account** (subs/history/likes that actually sync)? Your only options are **ReVanced** (Google's proprietary app + microG + install friction + ban risk + DMCA-era instability) or **SmartTube** (**can't run on a phone**).
- Want a **clean FOSS phone app**? Then you give up real account sync (NewPipe/Tubular/SkyTube = import-only; LibreTube/Clipious = dying Piped/Invidious accounts; Grayjay = local follows and **not FOSS**).
- Want **reliability**? Instance-based clients (Clipious, old LibreTube) are broken by Invidious/Piped death; the NewPipe family got SABR-capped to 360p; the patch camp breaks every YouTube update.

**NewTube dissolves the trade-off:** SmartTube's battle-tested, InnerTube-direct, feature-complete engine (which already ships device-code Google sign-in, SponsorBlock, DeArrow, RYD, 4K/HDR, background/PiP) — lifted onto a purpose-built, minimalist, touch-first phone UI, kept genuinely open (GPLv3), with optional sign-in so it also works fully signed-out.

---

## Positioning statements & taglines

**Sharpest single insight:** *We're the only clean, open, touch-first phone app that lets you keep your real YouTube account.* Today keeping your real account means either the proprietary patched app (ReVanced + microG, ban risk, breakage) or a TV-only app (SmartTube). Everyone else makes you abandon your subscriptions and history to a local-only or dead-instance world.

**Candidate taglines:**

1. **"All the power of SmartTube — now in your pocket, ad-free."**
   _Rationale:_ Leverages SmartTube's ~30.8k-star reputation and instant feature credibility; the "in your pocket" flips its single most-cited limitation (TV-only) into our headline. Best for the audience that already knows/loves SmartTube on their TV.

2. **"Sign in, or don't. No ads either way."**
   _Rationale:_ Leads with our uniquely-unmatched feature — **optional real Google sign-in** — while the second half claims the whole category's table stakes (ad-free). Differentiates in two beats against both ReVanced (login but not clean/FOSS) and NewPipe/Grayjay (clean but no real login).

3. **"Open, ad-free YouTube for your phone — SponsorBlock, 4K, background play, and your real account. No patching, no dead servers, no tracking."**
   _Rationale:_ The explicit "no patching, no dead servers" jabs directly at the two rival architectures' documented 2025–26 failures (ReVanced churn, Piped/Invidious collapse). Longer; ideal as a landing-page subhead under a shorter hero line.

_Recommended pairing:_ hero = **#1** (memorable, credibility-borrowing), subhead = **#3** (specific, jabs the alternatives), with **#2** as the sign-in feature callout.

---

## Thesis validation & challenge

**Thesis:** _"SmartTube's battle-tested InnerTube engine + full feature set, now with a clean phone-native minimalist UI + real Google account sign-in."_

**Validated:**
- SmartTube's engine is genuinely InnerTube-direct, feature-complete (SB + DeArrow + RYD + 8K/HDR + background/PiP + device-code Google sign-in), **MIT-licensed** (free to fork/restyle/relicense as GPLv3), and actively maintained.
- SmartTube is **explicitly, permanently TV-only** — the maintainer answers "smartphone version?" with "NO." Our wedge is unoccupied even by our own upstream.
- **No FOSS phone competitor offers real Google login+sync** — confirmed across NewPipe, Tubular, LibreTube, PipePipe, SkyTube, Clipious, FreeTube, Grayjay. This is a category-defining differentiator.

**Challenges / honesty caveats to bake into messaging:**
1. **"Battle-tested" ≠ unbreakable.** Every InnerTube-direct client faces YouTube's cat-and-mouse — SABR hit the NewPipe family; Grayjay gets OP67 blocks. SmartTube weathers these well but isn't immune. Message a **"resilient, fast-updating engine,"** not invincibility, and invest in a rapid update channel.
2. **Real Google sign-in carries account-restriction risk.** Grayjay users report restrictions on download; ~18% of ReVanced users got warnings. Keep sign-in **optional**, default to signed-out, and be transparent (consider recommending a secondary account for heavy use).
3. **"Battle-tested SmartTube" now carries a trust asterisk** after the Dec 2025 malware incident. Turn it into an advantage: ship **reproducible, transparently-signed builds** and say so — trust is a live, ownable differentiator in this category.
