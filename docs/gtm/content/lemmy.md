# Lemmy launch post — NewTube

**Target communities** (post to the single best-fit community, then use Lemmy's
native *cross-post* feature to the rest so it stays one thread instead of N
duplicate posts):

- `!android@lemmy.world` — primary launch home.
- `!fossdroid@lemmy.world` — FOSS-Android crowd; MIT + free download link stated.
- `!opensource@lemmy.ml` / `!opensource@programming.dev` — frame as the port/engineering story.
- `!degoogle@lemmy.ml` — disclose we're the makers; **do not** link first-party `youtube.com`.
- `!privacy@lemmy.world` — only if it genuinely fits; lead the trust/no-tracking angle.

**Posting notes (obey):** disclose maker status in the first line. Link the
GitHub **Release**, never a raw `.apk`. This copy leads on the *trust /
reproducible-build / honest-privacy* angle — deliberately different wording and
structure from the r/fossdroid post (which leads on "not a NewPipe fork" +
screenshots). Attach 3–4 fresh screenshots; do not reuse a canned block.

---

**Title:**
NewTube — SmartTube's ad-free YouTube engine, ported to Android phones. Open source (MIT), real login without microG, signed builds (working toward reproducible)

**Body:**

Disclosure up front: I'm one of the people who built this, so read it as "here's
our thing," not a neutral recommendation.

Short version: NewTube is an ad-free YouTube client for Android **phones and
tablets**. It's a touch-first fork of SmartTube — the ad-free app a lot of you
already run on an Android TV / Shield / box — rebuilt with a native phone UI
instead of the D-pad one. MIT, shipped through GitHub Releases, no Play Store, no
in-app purchases.

Why bother making another one, and why I think it earns a look on an instance
that's (fairly) tired of NewPipe reskins:

**It isn't a NewPipe fork. It's SmartTube's engine.** That distinction has one
concrete payoff: it does real Google sign-in via the device-code flow (the
"enter this code at youtube.com/activate" one), so your actual subscriptions,
watch history and likes sync — no microG, no GmsCore, no patching Google's APK.
Sign-in is optional and off by default; signed-out it just keeps local history.
NewPipe and Tubular are import-only, LibreTube's sync rode on Piped accounts (and
public Piped instances basically went dark), Grayjay keeps follows local. If what
you actually miss is "my real account, on a clean open app," that's the specific
hole this fills. If you'd rather never sign in, it works fully without one.

**On trust — this is really why I'm posting it *here*.** Some of you saw the
threads: for a stretch in December 2025, SmartTube's *official* APK was handing out
a tampered build — someone had folded an injected native payload into the download
before upstream noticed and pulled it. Our answer to that isn't "trust me." It's:
build in the open, tag every release on GitHub, sign each one with a stable key, and
work toward reproducible builds so you can verify the APK matches the source instead
of taking a stranger's word.
Forking something MIT and keeping it transparent is the entire point.

**What's in it:** SponsorBlock (auto-skip + on-seekbar markers), DeArrow
(de-clickbaits titles/thumbnails), up to 4K/HDR with codec + audio-track
selection, playback speed, styled subtitles, background play + lock-screen
controls, PiP, comments and live chat, search + voice, and the usual
channels / playlists / subscriptions / history. Shorts is its own section you can
unpin or hide entirely. The player has double-tap seek, prev/next, swipe-to-
dismiss, and an overflow with repeat / shuffle / zoom / screen-off / queue /
save-to-playlist.

**The honest privacy scope,** because this is Lemmy and you'd rightly ask:
it streams straight from YouTube's internal InnerTube API — same approach as
NewPipe and SmartTube — so there's no ad-blocker to break and no third-party
analytics SDK baked into the app. But it is not an anonymity tool. YouTube still
sees your IP like any client, and if you sign in, that's genuinely your Google
account. No ads, no tracking *by us*, no root — that's the real, bounded claim,
and I'd rather state it plainly than oversell it.

**Two caveats I won't bury:** (1) any client that talks straight to YouTube's
private API is only ever one server-side change away from a break — when YouTube
reworks something, every app in this lane (SmartTube included) has to notice and
push a fix. SmartTube's record of keeping pace is strong and NewTube runs on that
same engine, so I'd call it quick-to-patch — but never "break-proof," and I won't
pretend otherwise.
(2) Real sign-in carries the same account-restriction risk every third-party
login client does; keep it optional, and if you lean on it hard, a secondary
account is a reasonable hedge.

Genuine respect for NewPipe, LibreTube, Tubular and ReVanced — each solves a real
corner of this and I'm not here to dunk on any of them. NewTube just happens to
land on the one intersection none of them cover at once: a touch phone UI +
optional real login + genuinely open + a direct backend.

- Release (APK + notes): {{RELEASES_URL}}
- Source: {{REPO_URL}}
- Site: {{SITE_URL}}

Built on SmartTube by @yuliskov (https://github.com/yuliskov/SmartTube) — huge
thanks; NewTube wouldn't exist without that engine. Not affiliated with Google or
YouTube; "YouTube" is a trademark of Google LLC.

I'll camp the comments — happy to get into the sign-in flow, the
SponsorBlock/DeArrow behaviour, or how the build pipeline is set up.
