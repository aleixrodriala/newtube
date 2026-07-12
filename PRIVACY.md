# Privacy Policy for NewTube

**Last updated:** July 12, 2026

This Privacy Policy applies to **NewTube**, an open-source YouTube client for
Android phones (package ID `io.github.aleixrodriala.arc`). NewTube is an
independent, unofficial fork of SmartTube. It is not affiliated with Google,
YouTube, or SmartTube's developer.

## 1. Summary

NewTube has **no backend of its own** and its developer collects **no personal
data**. The app talks only to YouTube/Google, a small set of community services
you can disable, and (for update checks) GitHub. There are no analytics, no
trackers, no advertising, and no crash reporting.

## 2. Data the developer collects

**None.** NewTube has no developer-controlled server, no account system of its
own, and no telemetry or analytics frameworks (no Firebase, no Crashlytics, no
Sentry, no ad SDKs). The developer does not receive, store, or have access to
any information about you or your usage.

## 3. YouTube / Google

- **Sign-in is optional.** You can use NewTube fully signed out. If you choose to
  sign in, NewTube uses Google's official OAuth 2.0 **device-code flow** — you
  authorize the app on Google's own page; your password is never seen by, or
  shared with, the developer.
- **Tokens stay on your device.** Authentication tokens are stored only in the
  app's local storage on your device. They are never transmitted to, or stored
  by, the developer.
- **Direct connection.** Video streams, search, and account data are fetched
  directly from YouTube/Google servers to your device. Your use of YouTube's
  services through NewTube is also subject to Google's and YouTube's own terms
  and privacy policies.

## 4. Community services (optional, per-feature)

When the corresponding feature is enabled, NewTube sends **only the ID of the
video you are viewing** to these community-run services to retrieve crowd-sourced
data. These requests are read-only and contain no account tokens, names, emails,
or other personal identifiers:

- **SponsorBlock** (`sponsor.ajay.app`) — sponsor-segment timestamps.
- **DeArrow** (`sponsor.ajay.app`, `dearrow-thumb.ajay.app`) — de-clickbait
  titles and thumbnails.
- **Return YouTube Dislike** (`returnyoutubedislikeapi.com`) — estimated dislike
  counts.

Each of these can be turned off in Settings. They are operated by their
respective projects under their own privacy policies, not by NewTube.

## 5. Update checks

Unless you installed NewTube from a store that manages updates for you (such as
F-Droid or IzzyOnDroid), the app periodically checks NewTube's **GitHub
Releases** for a newer version. This is a normal web request to `github.com`;
like any web request it exposes your IP address to GitHub, but it sends no
account data or personal identifiers, and no data goes to the developer. You can
avoid these checks by installing and updating through F-Droid/IzzyOnDroid.

## 6. No profiling, no advertising, no monetization

NewTube does not profile you, does not use analytics or automated
decision-making about you, and shows no ads. NewTube is a free, non-commercial
project: it does **not** solicit donations or payments and contains no in-app
purchases.

## 7. Your rights (GDPR / CCPA)

Because NewTube stores and transmits no personal data to the developer, the
developer holds no identifiable records associated with you, and therefore has
nothing to export or delete on request. Data held by Google/YouTube or the
community services above is governed by their respective policies.

## 8. Contact

For questions about this policy or NewTube's privacy design, please open an issue
on the project's GitHub repository.
