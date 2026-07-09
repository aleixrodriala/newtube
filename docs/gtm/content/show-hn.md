# Show HN submission — NewTube

**Title (79 chars):**

Show HN: NewTube – SmartTube (ad-free YouTube for TV), ported to Android phones

---

**Body:**

I built NewTube, and I should say up front that I'm the developer, so treat this as a "here's my thing" post.

Short version: SmartTube is a well-loved ad-free YouTube client for Android TV, but its FAQ answers "is there a phone version?" with a flat "NO" — it's D-pad/remote only, touch input isn't supported. I'd been sideloading it onto my phone anyway and fighting the Leanback focus UI with my thumb, which is exactly as bad as it sounds. So I forked it and rebuilt the front end for touch.

It's a fork of SmartTube's actual codebase (MIT), not a NewPipe fork. That distinction matters because it means I inherited SmartTube's InnerTube-direct extraction layer and its whole feature set — SponsorBlock, DeArrow, Return YouTube Dislike, up to 4K/HDR, background/PiP, and device-code Google sign-in — rather than reimplementing any of it. The work was almost entirely on the presentation side: I ripped out the Leanback browse fragments and D-pad navigation and wrote a 2-column touch grid home with bottom nav and a drawer, and a phone player with gesture controls (double-tap ±10s, swipe-to-dismiss, prev/next). The media pipeline underneath is SmartTube's.

The reason I bothered, beyond scratching my own itch: on Android phones every FOSS option makes you give something up. NewPipe and its forks are great but can't log into a real Google account (subs/history are import-only). LibreTube leaned on public Piped instances, which have mostly died. ReVanced keeps your real account but does it by patching Google's proprietary APK with microG, which comes with per-update breakage and some account-ban risk. NewTube's one real differentiator is that sign-in is optional device-code auth that syncs your real subs/history/likes without microG — and it also works fully signed-out with local history if you'd rather not log in. I'm not claiming it's better than any of those projects; they're all solid and solve different constraints. This just fills a specific gap.

Honest status and limitations, because HN will (correctly) push on these:

- It's pre-1.0. I've tested on a handful of phones plus the emulator; I fully expect device-specific breakage.
- InnerTube extraction is a cat-and-mouse game. YouTube's SABR rollout capped the whole NewPipe family at 360p for months last year. SmartTube weathered it better, and I inherit that engine, but nothing here is immune — the honest framing is "resilient and fast to update," not invincible. A quick update channel matters more than any single feature.
- Signing in carries a small account-restriction risk (true of every real-login client). It's off by default and I say so in the app.
- Comments can be flaky (inherited from upstream), there are no downloads yet, and casting is code-based rather than one-tap.

On trust: SmartTube's *official* APK was hit by a supply-chain compromise in late 2025 (a malicious native lib in a few releases, fixed upstream shortly after). That made me want NewTube's builds to be verifiable — they're built in CI from tagged source and signed, and reproducible builds are the next thing on my list so you can confirm the APK matches the source. No trackers, no analytics, no ads, no IAP, MIT.

Install is a GitHub Releases APK for now (F-Droid RFP is open, IzzyOnDroid pending). Not affiliated with Google or YouTube; "YouTube" is Google's trademark. Huge thanks to @yuliskov and the SmartTube project (https://github.com/yuliskov/SmartTube) — this is their engine with a different face.

I'd genuinely value feedback on the touch player and the sign-in flow specifically, and bug reports from devices I don't own. Repo, code, and releases:

{{REPO_URL}}
