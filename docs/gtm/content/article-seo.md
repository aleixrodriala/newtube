---
title: "SmartTube for Android phones: the ad-free YouTube client, finally on mobile."
meta_description: "SmartTube is Android TV only. NewTube brings the same ad-free, no-root YouTube experience to your phone: SponsorBlock, background play, 4K, open source."
canonical: "Self-referential. Set <link rel=\"canonical\"> to the article's own permalink on {{SITE_URL}} (e.g. {{SITE_URL}}/blog/smarttube-for-phone). If this is syndicated to Dev.to / Medium / a subreddit, point THEIR canonical back to the {{SITE_URL}} version so the primary copy accrues the ranking signal."
tags: [smarttube for phone, ad-free youtube android, no root youtube, newpipe alternative, sponsorblock android]
---

# SmartTube for Android phones: the ad-free YouTube client, finally on mobile

If you have an Android TV, a Shield, or an old box wired to your living-room set, there's a good chance you already run SmartTube. It's the ad-free YouTube player everyone quietly recommends: no ads, SponsorBlock, 4K, and it just keeps working. So the obvious question, the one people type into Google constantly, is: **where's the phone version?**

The short answer from SmartTube's own team is blunt. Their FAQ asks "Is there a Smartphone version of SmartTube?" and answers "**NO**" — touch input isn't supported, and it never will be. SmartTube is built for a D-pad and a remote. Try to use it with a thumb and you'll feel it fighting you.

That's the gap we set out to fill. **Full disclosure: I'm one of the people building NewTube**, a free, open-source (MIT) fork of SmartTube rebuilt from the ground up for touch. Same proven engine, a phone-native interface, and none of the "how do I move this cursor with my finger" awkwardness. This article is honest about what it is, what it isn't, and how it stacks up against the other options — because there are good ones, and pretending otherwise would insult your intelligence.

## Wait — isn't there already a SmartTube mobile version?

No, and that's not an oversight on their part. It's a design decision. SmartTube's UI is a "10-foot interface": big rows, focus highlights, everything reachable by pressing arrow keys from across a room. That's exactly wrong for a 6-inch screen you hold six inches from your face.

People keep searching for "SmartTube for phone," "SmartTube mobile apk," "SmartTube for Samsung phone" anyway, because the *thing they actually want* — ad-free YouTube with SponsorBlock and background play — has nothing to do with the TV. NewTube is that: SmartTube's InnerTube engine, wearing a phone-shaped body. A lean two-column grid on the home screen, bottom navigation, double-tap-to-seek, swipe-to-dismiss the player. Thumbs, not arrow keys.

## Ad-free YouTube on Android — no root, no ad-blocker, no Premium

Here's the part that trips people up, so let me be precise about the mechanism. NewTube doesn't "block" ads. There's nothing to block. It talks to YouTube's internal **InnerTube API** directly — the same private API the official apps use — and requests the video streams without ever loading the ad machinery. This is the identical approach NewPipe and SmartTube take.

What that buys you:

- **No root.** You install a normal APK. Your phone stays stock.
- **No ad-blocker to maintain**, no VPN, no filter lists that break every few weeks.
- **No tracking, no accounts required, no in-app purchases.** It's free because it's a community project, not because you're the product.

It's not magic and I won't pretend it is. YouTube changes its internals, and every client in this category — ours included — occasionally has to catch up. Anyone selling you "unbreakable" is lying. What matters is a fast update channel, and that we inherit SmartTube's fast-moving engine.

## So how is this different from NewPipe, LibreTube, or ReVanced?

These are all genuinely good projects, and I'm not going to trash them. But they each ask you to give something up, and NewTube exists because of the specific thing *we* didn't want to give up.

**NewPipe** (and forks like Tubular) is the classic FOSS choice — clean, no Google, works fully de-Googled. The catch: **you can't log into your real YouTube account.** Subscriptions are import/export only. If you have ten years of subs and a Watch Later a mile long, that's a hard sell. NewPipe has also spent much of 2025–26 fighting YouTube's SABR rollout, which capped the whole extractor family near 360p for a while.

**LibreTube** has arguably the nicest UI of the bunch, but it was built around Piped servers — and public Piped instances have basically collapsed, so v31 dropped them and switched to local extraction. Its account sync rode on Piped accounts, not Google.

**ReVanced** is the one that *does* keep your real account — but by patching Google's proprietary YouTube APK and running microG. That means install friction, patches that break on YouTube updates, and a documented risk of account warnings. It works; it's just a lot of moving parts.

**NewTube's angle:** it inherits SmartTube's **device-code Google sign-in** — the "go to a URL, type a code" flow — so your subs, history, and likes sync to your actual account, **with no microG and no patched Google app.** And sign-in is optional; leave it off and everything still works signed-out. As far as I can tell, no other clean, open, touch-first phone client offers real optional Google login. That's the whole reason this project exists.

One more thing worth being upfront about: SmartTube's *official* Android TV APK was compromised in a December 2025 supply-chain incident (a malicious native library slipped into a few releases before being caught and fixed). It's a fair thing to be wary of. Our answer is to ship **transparent, signed** releases straight from GitHub — built in CI from tagged source, with reproducible builds on the way — so you can read what you're installing rather than take it on faith.

## SponsorBlock, DeArrow, and the rest of the power features

Because NewTube is a real fork of SmartTube rather than a from-scratch reimplementation, the feature list arrives fully grown instead of as a two-year roadmap:

- **SponsorBlock** — auto-skips sponsor segments and shows markers on the seek bar. This alone is worth the install.
- **DeArrow** — swaps clickbait titles and thumbnails for crowdsourced sane ones.
- **Return YouTube Dislike** — the real dislike count is back on the watch page, next to a working Like/Dislike and Subscribe.
- **Up to 4K/HDR** with codec selection, playback-speed control, **styled subtitles**, and audio-track selection for multi-language videos.
- The watch page has the stuff you actually use: expandable description, related/up-next, **comments** (with replies and likes), and **live chat**.
- An overflow menu with repeat, shuffle, video zoom, screen-off audio, stats, a queue, and save-to-playlist.
- **Search with voice**, plus full channels, playlists, subscriptions, and history.

## Background play and picture-in-picture, without paying for Premium

Two features people specifically hunt for — "youtube background play android free" and "youtube pip android" — are just on by default. Lock your screen and audio keeps going, with proper media/lock-screen controls. Pop out a floating **picture-in-picture** window and keep watching while you text. On the official app both of these are gated behind Premium. Here they're table stakes.

## A minimalist YouTube — with Shorts you can hide

If YouTube's own app feels like it's yelling at you, this is the calmer version. The home screen is a plain grid of videos — no autoplaying feed of stuff you didn't ask for. And **Shorts is a section you can unpin or hide entirely.** If you never want to see a Short again, that's two taps away.

## How to install NewTube

It's a GitHub Releases APK — where a store link would normally go, we send you to the signed release, not a random APK mirror.

1. Open the [latest release]({{RELEASES_URL}}) on your phone and download the APK.
2. Tap it. Android will ask permission to install from this source — allow it for your browser/files app.
3. Open NewTube. **Optionally** sign in with the device code to sync your account, or skip it and browse signed-out.

Free, open source (MIT), APK from GitHub Releases. F-Droid is planned.

## FAQ

**Is there a SmartTube version for Android phones?**
Not from the SmartTube team — their FAQ explicitly says no, because it's built for TV remotes. NewTube is an independent, open-source fork that ports the same engine to a touch-first phone UI.

**Do I need root or a rooted phone?**
No. It's a normal APK, no root, no ad-blocker, no VPN.

**Is it really free and open source?**
Yes — MIT licensed, no ads, no tracking, no in-app purchases. Source and builds are on [GitHub]({{REPO_URL}}).

**Does it need a Google account?**
No. Sign-in is optional (device-code, no microG). Signed in, your subs/history/likes sync; signed out, everything still works.

**Does it support SponsorBlock, 4K/HDR, and Return YouTube Dislike?**
All three, plus DeArrow, background play, and picture-in-picture.

**Is it affiliated with Google or YouTube?**
No. NewTube is not affiliated with, endorsed by, or sponsored by Google. "YouTube" is a trademark of Google LLC.

---

*NewTube stands on the shoulders of [SmartTube](https://github.com/yuliskov/SmartTube) by @yuliskov (MIT) — genuinely, go star it. Grab NewTube at [{{SITE_URL}}]({{SITE_URL}}) or straight from [GitHub]({{REPO_URL}}).*
