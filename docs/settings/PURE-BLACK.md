# Pure black (issues #73, #74, 2026-10-19)

Settings › General › **Pure black**, a switch under Theme: "Black instead of dark grey when the
app is dark. Saves battery on OLED screens". It applies whenever the app is dark — Dark, or
System default while the phone is dark (#73 asked for System too) — and is greyed out on Light.
Off by default; nothing changes for anyone who doesn't turn it on.

## What goes black, what doesn't

| | Dark | Dark + pure black |
|---|---|---|
| Page: window, status/navigation band, top bars, bottom nav, Home, watch page, comments panel, Settings, Search, the search box | `#0F0F0F` | `#000000` |
| Raised: bottom sheets (gear menu, pickers, cast, chapters), dialogs, comment composer, the watch page's Comments / Live chat / queue cards, the parent comment on a replies page | `#1E1E1E` | `#121212` |
| Pills, chips, placeholders, popup menus, the settings search bar, the player | unchanged | unchanged |

How others do it: YouTube's own dark theme has no black option (page `#0F0F0F`, a lighter
"raised" colour for sheets and menus). The patched clients' "Pure black" (ReVanced, Morphe:
`BaseThemePatch`) recolour the base AND the raised background to `#000000`, so a sheet over the
black page is only told apart by its handle and the scrim. We keep one step of lift instead: the
page is true black (the OLED win is the page, which is most of the screen), and sheets, dialogs
and cards sit at `#121212` — Material's dark surface — so a sheet still has an edge against the
page and a card still reads as a card, the way the dark theme's `#1E1E1E` does over `#0F0F0F`.
Pills and chips are translucent white, so they keep their contrast on black with no change.

## How

- The page colours are theme attributes (`res/values/attrs_mobile.xml`: `newtubeColorBackground`,
  `…NavigationBar`, `…BackgroundScrim`, `…Field`, `…Surface`, `…Card`, `…CommentParent`).
  `Theme.NewTube` sets them to the light/dark `@color` tokens; `ThemeOverlay.NewTube.PureBlack`
  replaces them. Layouts, drawables, theme items (`windowBackground`, `colorSurface`, the
  sheets' `backgroundTint`, `MobileAlertDialog`) and code (`ThemeMode.color(context, attr)`)
  read the attributes, never the tokens; `MobileThemeTest.layoutsReadThePageColoursThroughTheTheme`
  fails on a layout or drawable that names one of them directly.
- `MobileActivity.onCreate` lays the overlay over the screen's theme before the window exists
  (`ThemeMode.applyPalette`). Every sheet, dialog and popup copies its screen's theme, so they
  inherit it; the comment composer, whose style is a whole app theme, re-applies it.
- A screen's "look" is its night bits plus pure black (`ThemeMode.desiredLook`); flipping the
  switch runs the same `checkTheme` path as the Theme setting: open screens are recreated, the
  player recolours its watch page in place (the video keeps playing). Applies live, no restart.
- In place, the player's theme is **rebuilt** (`ThemeMode.rebuildTheme`), not overlaid again.
  Android 15's `Resources.ThemeKey` de-duplicates a style applied a second time by moving it to
  the end without rehashing, and the drawable cache's keys share those arrays: black → dark →
  black left the watch-page cards on the middle step's `#1E1E1E` (API 35 emulator).
- Not covered: the system launch splash is drawn from the manifest theme before the app runs, so
  it stays `#0F0F0F` for its fraction of a second; the shared TV edit dialogs (new playlist,
  passwords) keep their library `#101010`.

Verified on an API 35 x86_64 emulator: Home, player, gear sheet, comments, Search, Settings in
Dark + pure black; Light and plain Dark sampled at the page, card and sheet pixels give the same
values as before (`#FFFFFF`/`#F2F2F2`, `#0F0F0F`/`#1E1E1E`); the switch on and
off repeatedly with the player open behind Settings; System default following the phone's
dark/light switch both ways with the player open.
