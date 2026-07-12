# Third-party notices & attributions

NewTube bundles or depends on third-party software and data. This file collects
the required attributions and license notices. NewTube itself is MIT-licensed
(see [`LICENSE`](LICENSE)).

## Community data services

NewTube can query the following community services. Their **data** is licensed
separately from their code, and NewTube uses it under those terms:

- **SponsorBlock** — sponsor-segment data © SponsorBlock and its contributors,
  licensed under **CC BY-NC-SA 4.0**
  (https://creativecommons.org/licenses/by-nc-sa/4.0/).
  API: https://sponsor.ajay.app · Project: https://github.com/ajayyy/SponsorBlock
  NewTube uses this data unmodified to skip segments; it does not redistribute
  the database. NewTube is a non-commercial, free, open-source project.
- **DeArrow** — title/thumbnail branding data © DeArrow and its contributors,
  licensed under **CC BY-NC-SA 4.0**.
  API: https://sponsor.ajay.app/api/branding · Project: https://dearrow.ajay.app
  Used unmodified and non-commercially.
- **Return YouTube Dislike** — dislike-count data via
  https://returnyoutubedislikeapi.com (https://returnyoutubedislike.com).
  Used read-only, per the project's public API.

> These services are operated by their respective projects and are **not**
> affiliated with NewTube. NewTube's non-commercial status is a condition of
> using the SponsorBlock/DeArrow data (the NC term); do not add advertising,
> paid tiers, or other commercial monetization without re-evaluating these
> licenses.

## Bundled / vendored code

- **DoubleTapPlayerView** (`doubletapplayerview-media3/`) — © Vincent Kammerer,
  **MIT** (https://github.com/vkay94/DoubleTapPlayerView). NewTube vendors a
  media3-rebased fork. See [`doubletapplayerview-media3/LICENSE`](doubletapplayerview-media3/LICENSE).
- **Slidr / SlidableActivity** (`slidableactivity/`) — © r0adkll, **Apache-2.0**.
  See [`slidableactivity/LICENSE.md`](slidableactivity/LICENSE.md).
- **filepicker-lib** (`filepicker-lib/`) — **Apache-2.0**.
  See [`filepicker-lib/LICENSE`](filepicker-lib/LICENSE).

## Submodules

- **MediaServiceCore** and **SharedModules** — © yuliskov, **MIT** (same license
  as SmartTube). NewTube uses its own forks of these repositories.
  - SharedModules bundles **j2v8** (© EclipseSource, **Eclipse Public License
    v1.0**) and **Apache Commons IO** (**Apache-2.0**); their notices are
    preserved inside those directories.

## Upstream

NewTube is an independent, unofficial fork of **SmartTube** (© yuliskov, MIT —
https://github.com/yuliskov/SmartTube). Not affiliated with or endorsed by
SmartTube's developer, Google, or YouTube. "YouTube" and "Android" are
trademarks of Google LLC.
