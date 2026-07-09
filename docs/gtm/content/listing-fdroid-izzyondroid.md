# NewTube — F-Droid / IzzyOnDroid store metadata

Fastlane-style metadata for the **IzzyOnDroid** repo (fast path) and an
**F-Droid RFP** (slow path, high trust). Written to F-Droid's house style:
factual, no superlatives, no hype. F-Droid editors reject "best / amazing /
unbreakable" copy, so none of it appears here. Placeholder URLs (`{{...}}`)
are filled by one `sed` once the repo is public.

Both stores read the standard Triple-T / Fastlane tree below from the repo.
IzzyOnDroid scans it directly; F-Droid reuses the same text in its
`fdroiddata` YAML.

---

## 1. Fastlane metadata tree (commit to repo root)

```
fastlane/metadata/android/en-US/
├── title.txt                    # app name
├── short_description.txt        # <= 80 chars
├── full_description.txt         # <= 4000 chars, factual
├── changelogs/
│   └── <versionCode>.txt        # one file per release, e.g. 10000.txt
└── images/
    ├── icon.png                 # 512×512, PNG, no alpha issues
    ├── featureGraphic.png       # 1024×500 (optional, IzzyOnDroid shows it)
    └── phoneScreenshots/
        ├── 1.png … 8.png        # real emulator/device shots, not mockups
```

Notes that trip people up:
- English default lives in `en-US`. Add `de-DE`, `es-ES`, etc. later — same
  tree per locale; don't invent translations you can't maintain.
- Screenshots must be **real** (F-Droid/IzzyOnDroid reject rendered marketing
  mockups). Home grid + player watch page + SponsorBlock skip + sign-in screen
  make the strongest set.
- Changelog filename is the numeric `versionCode`, not the version name.

---

## 2. `title.txt`

```
NewTube
```

## 3. `short_description.txt`  (78 / 80 chars)

```
Ad-free YouTube client for phones/tablets, forked from SmartTube (Android TV).
```

## 4. `full_description.txt`

```
NewTube is a free, open-source, ad-free YouTube client for Android phones and
tablets. It is a touch-first fork of SmartTube (the ad-free YouTube app for
Android TV, MIT-licensed, by yuliskov), rebuilt around a phone-native UI
instead of a D-pad one. It is not a NewPipe fork.

Video and audio stream directly from YouTube's internal InnerTube API — the
same approach SmartTube and NewPipe use. There is no ad-blocker to break, no
root, no Google Play Services requirement, no in-app purchases, and no
analytics or tracking.

Features:
* Lean two-column grid home; fast player with double-tap seek, previous/next,
  and swipe-to-dismiss
* Up to 4K/HDR, codec and quality selection, playback speed, styled subtitles,
  audio-track selection
* SponsorBlock (auto-skip with segment markers) and DeArrow (de-clickbait
  thumbnails and titles)
* Background playback with lock-screen and media controls; Picture-in-Picture
* Like/Dislike with real Return YouTube Dislike data, Subscribe, expandable
  description, related/up-next
* Comments with replies; read-only live chat
* Search with voice input; channels, playlists, subscriptions, watch history
* Overflow menu: repeat, shuffle, video zoom, background/screen-off audio,
  save-to-playlist, queue, playback stats
* Shorts is a section you can unpin or hide

Sign-in is optional. NewTube works fully signed-out with local watch history.
If you want subscriptions, history and likes to sync, you can sign in with your
Google account using YouTube's device-code (TV) flow — no microG and no Google
Play Services required.

About the NonFreeNet flag: NewTube talks to YouTube's servers, which are a
proprietary, non-free network service. That is why it carries F-Droid's
NonFreeNet anti-feature. The app itself is MIT-licensed and contains no ads,
no tracking and no non-free code of its own.

NewTube is not affiliated with, sponsored by, or endorsed by Google LLC.
"YouTube" is a trademark of Google LLC.

Built on SmartTube by yuliskov (https://github.com/yuliskov/SmartTube), MIT.
Source and releases: {{REPO_URL}}
Website: {{SITE_URL}}
```

## 5. `changelogs/<versionCode>.txt`  (example: `changelogs/10000.txt`)

```
First public release.
- Phone and tablet touch UI built on the SmartTube (Android TV) engine
- InnerTube-direct playback, up to 4K/HDR, codec/quality/speed selection
- SponsorBlock auto-skip and DeArrow
- Background playback, lock-screen controls, Picture-in-Picture
- Optional Google device-code sign-in (subs/history/likes sync); works
  fully signed-out
- Return YouTube Dislike, comments, read-only live chat, voice search
```

Keep each changelog terse and factual — one bullet per user-visible change.
IzzyOnDroid surfaces this file verbatim on the app page for that build.

---

## 6. Categories

F-Droid and IzzyOnDroid share a fixed category vocabulary (not Play's). Use:

- **Multimedia** (primary)
- **Internet** (secondary)

There is no "Video Players" category on F-Droid; don't copy a Play-store
category in.

---

## 7. Anti-features (declare these honestly)

F-Droid *will* attach anti-features whether or not we self-declare, so declare
them up front — it reads as good faith and speeds review.

| Label | Applies? | Why |
|---|---|---|
| **NonFreeNet** | **Yes** | The app depends entirely on YouTube's servers, a non-free network service. This is the one that unavoidably applies (same label NewPipe/LibreTube carry). |
| NonFreeDep | Must verify | Only if the shipped build links a proprietary library. See the build audit in §9 — for F-Droid we must strip Google Cast SDK / Firebase / GMS if SmartTube's tree pulls any in. |
| NonFreeAssets | No | Icon, fonts and bundled assets are our own / open. |
| Tracking | No | No analytics, no crash reporters, no ad IDs. |
| Ads | No | None. |
| UpstreamNonFree | No | Upstream (SmartTube) is MIT and fully free. |

State plainly in the RFP: *the app has no ads and no tracking; the only reason
for an anti-feature is that it necessarily connects to YouTube.*

---

## 8. IzzyOnDroid inclusion steps (do this first — fast path)

Policy: https://izzyondroid.org/docs/general/AppInclusionPolicy/

1. **License check** — repo has a top-level `LICENSE` (MIT). FOSS is the hard
   requirement; MIT passes.
2. **Add the Fastlane tree** from §1 to the repo (title, short/full
   description, 512px icon, screenshots, changelogs).
3. **Tag a GitHub Release with the signed APK attached.** IzzyOnDroid pins the
   APK's **signing certificate on first inclusion** — every later release must
   be signed with the *same* key or updates stop. Decide and back up the
   release key now.
4. **Open an inclusion request** issue at the IzzyOnDroid repo tracker
   (`gitlab.com/IzzyOnDroid/repo`) with the GitHub repo URL and the release
   tag. Keep the requested description matching `short_description.txt`.
5. **Auto-updates:** once accepted, new tagged releases with an attached APK
   are picked up automatically, typically within ~24h. No re-request per
   release.
6. **Reproducible-build badge (recommended):** enable a reproducible build so
   IzzyOnDroid can show the "built reproducibly" checkmark. Given SmartTube's
   Dec 2025 official-APK supply-chain compromise, a verifiable build is the
   single most valuable trust signal we can ship — call it out.

Why first: it gives every launch post a real **store link** instead of a raw
`.apk` (which most subreddits ban), before we touch Reddit/HN/Lemmy.

---

## 9. F-Droid RFP requirements (open early, in parallel — slow path)

Submit an **RFP (Request For Packaging)** issue: `gitlab.com/fdroid/rfp`.

1. **License:** OSI-approved FOSS. MIT ✓ (LICENSE in repo root). Include the
   SPDX id `MIT` in the RFP.
2. **Builds from source on F-Droid's server** — the strict gate. No proprietary
   Gradle deps, no prebuilt/binary blobs, no Google Play Services.
3. **Build audit (do before filing):** SmartTube's tree can pull in Google
   **Cast SDK**, **Firebase/Crashlytics**, or other GMS pieces. Any of those
   fail F-Droid and can also block IzzyOnDroid. Gate them behind a FOSS product
   flavor (or remove them) and point the F-Droid `Builds:` entry at that
   flavor. ExoPlayer/Media3 itself is fine (Apache-2.0).
4. **Declare anti-features** in the metadata: `AntiFeatures: [NonFreeNet]`
   (plus `NonFreeDep` only if §7 audit forces it).
5. **Reproducible builds:** strongly encouraged. If F-Droid's build reproduces
   our signed release APK byte-for-byte, they can publish **our** signed binary
   under our key. Lead the RFP with this — it's the anti-malware trust angle.
6. **Metadata:** the `fdroiddata` YAML reuses the Fastlane text above — summary
   (= short description), description (= full description), `Categories:
   [Multimedia, Internet]`, `RepoType: git`, `Builds:` per release.
7. **Expect a slow review** and open it as early as possible; that's why it
   runs parallel to IzzyOnDroid rather than gating launch.
8. **Honesty caveat for the thread:** F-Droid has historically been cautious
   about YouTube clients over Google's ToS. NewPipe's inclusion sets precedent,
   so this is workable — but be ready for that discussion and don't oversell.

---

## 10. Reusable non-metadata strings (footer / listing boilerplate)

- **Credit line:** "Built on SmartTube by yuliskov —
  https://github.com/yuliskov/SmartTube (MIT)."
- **License:** MIT.
- **Disclaimer:** "Not affiliated with Google or YouTube. 'YouTube' is a
  trademark of Google LLC."
- **Links:** Source {{REPO_URL}} · Latest release {{RELEASES_URL}} ·
  Website {{SITE_URL}}
```
