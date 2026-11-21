# SlopBox UI: touch-first and themeable

Status: planned 2026-11-21. Done: steps 1, 2, 5, 6, 7, 10, 11; step 9 started (SlopBox menu with Now Playing Gestures).

## Goal

SlopBox's screens follow cabbiev2, a skin made for players with buttons. On a phone that has two costs: things the
buttons used to do are now slow or hidden, and the look is fixed. This plan makes SlopBox easier to use by touch and
lets people choose its colours and layout, while keeping Rockbox's settings, menus and `.cfg` files the source of truth.

Rules for every step:

- Use the Rockbox setting when one exists (theme colours, `selector type`, `scrollbar`, `statusbar`, `wps`, `font`)
  rather than inventing a SlopBox one. New settings only where Rockbox has nothing.
- Every behaviour that differs from Rockbox goes in `docs/DIFFERENCES.md` in the same change.
- Default look stays cabbiev2. Anyone who changes nothing sees the same app, minus the popup problem (step 1).

## Phase 1: everyday friction (small, separate changes)

### 1. Now Playing popup no longer hides the track

Now: any touch anywhere on the WPS (`NowPlaying.kt`, the `awaitEachGesture` watcher) replaces the album / title /
artist lines with the Browse / Quick Screen / Context Menu popup for 3 s, including taps on shuffle, repeat and seek.

Change: the popup opens only from a tap on the track-lines area or empty space, not from the control row, progress
bar or volume. A tap inside the popup or 3 s idle closes it. Option to keep it: a setting is not needed; this is a
fix, not a preference.

### 2. Mini-player on list screens

A 56 dp bar at the bottom of every screen except Now Playing, shown while something is loaded: thin progress line on
top, art thumbnail, title / artist, play-pause. Tap opens Now Playing (`nav.toNowPlaying()`); swipe up does too.
Replaces the ▶ `BarButton` in title bars. Lives in `App.kt` around the `when (nav.top)` so screens don't change.

Rockbox ties this to its own status bar: `statusbar` (Off / Top / Bottom) chooses where it sits, Off hides it.

### 3. Haptics

`LocalHapticFeedback` on: long-press (context menus), each repeat of a held seek, Quick Screen value steps, EQ
−/+ steps, and the bar crossing a cue / A-B marker while scrubbing. One switch in a SlopBox section (see step 9);
default on.

### 4. Breadcrumb title in Files

The Files title bar shows the path as segments; tapping one pops `state.path` back to it. Long titles scroll
horizontally, last segment always visible. Respects Show Path (Off = last folder name only, no breadcrumb).

## Phase 2: theme from Rockbox's own settings

### 5. Palette as state

Turn the `Cabbie` object (`ui/Theme.kt`, used in 16 files) into a `Palette` data class provided through a
`CompositionLocal`, built from the settings store and rebuilt on change. Default values are today's colours, so
nothing looks different yet. Mechanical: `Cabbie.Text` → `P.text` and so on.

Mapped settings (all already in `rockbox_settings.json`, stored but unused):

| Setting | Drives |
|---|---|
| `foreground color` | text; dim text derived (60 % toward background) |
| `background color` | backdrop bottom; backdrop top derived (lighter) when `backdrop` is the default |
| `line selector start color` / `end color` / `text color` | list selector, plus the gold controls (pills, progress fill) |
| `list separator color` / `height` | row separators (Auto = 1 dp, Off = none) |
| `selector type` | Pointer (arrow, no bar), Bar (Inverse), Bar (Solid Colour), Bar (Gradient) |
| `show icons` | already wired |

Controls (progress bar, header pill, A-B buttons) use the selector colours so a theme recolours everything in one go.

### 6. Colour picker

The colour settings are "plain" in the catalog, so they have no menu yet (listed in DIFFERENCES.md). Port Rockbox's
colour menu (Theme Settings › Colours: Foreground, Background, Line Selector Primary / Secondary / Text, Separator)
and `gui/color_picker.c`: R/G/B sliders with a live swatch and Reset to default. Touch version: sliders plus
−/+ steppers like the EQ screen.

### 7. Theme presets

Theme `.cfg` files bundled in assets and offered under Theme Settings › Browse Theme Files (now an Android picker):
Cabbie (default), Rockbox Blue, OLED Black, Light, High Contrast. Loading one is a normal `.cfg` load, so user-made
themes saved with Save Theme Settings work the same way.

### 8. Flat style and text size

- Flat vs glossy: a new SlopBox setting; Flat skips the highlight in `glossyPill` and draws solid fills.
- Text size: Rockbox's `font` names a bitmap font file with a pixel height (`15-Adobe-Helvetica`). Offer a short
  list (Small / Normal / Large / Huge, written as font names) and scale `body` / `small` / row height from it.

### 9. SlopBox section in Theme Settings

One place for settings Rockbox lacks: Flat style, Haptics, Album art accent (step 13), Navigation at bottom
(step 14), Now Playing gestures (step 11). Saved in `config.cfg` with a `slopbox ` prefix so Rockbox ignores them.

## Phase 3: lists and Now Playing

### 10. Fast scroll

When `scrollbar` is Left or Right, lists over one screen get a scrollbar of `scrollbar width` on that side; dragging
it scrolls, with a bubble showing the first letter (Files, Database, Playlist Catalogue) or the track number
(Current Playlist). Shared composable in `Lists.kt` around `LazyColumn`.

### 11. Now Playing gestures

On the art (or the track lines when there is no art): swipe left / right = next / previous, swipe up = playlist,
double-tap = play / pause, long-press = Context Menu (long-press on C stays the WPS hotkey). Vertical drag on the
right edge = volume, showing the existing volume bar. Each can be turned off (step 9); swipes on by default.

### 12. Playlist viewer editing

Current Playlist: drag handle to reorder (Rockbox's Move), swipe a row left to remove with an Undo snackbar.
The context-menu routes stay.

### 13. Now Playing layouts

Rockbox's `wps` setting names the skin. Offer built-in layouts as skin names under Theme Settings › Browse While
Playing Screen Files:

- `cabbiev2`: today's screen (default)
- `slop-bigart`: edge-to-edge art, track info over a gradient at the bottom, optional blurred-art backdrop
- `slop-large`: no art, huge title and 72 dp buttons, for the car or the gym
- `slop-minimal`: text and progress only

Shared pieces (progress bar, control row, popup, gestures) move out of `NowPlayingScreen` so each layout is a short
arrangement. Real `.wps` skin parsing is out of scope.

Also: tap the elapsed / remaining times to swap them, codec line shown in the art layouts, a thicker bar while
dragging. Album art accent (step 9 setting): take the selector colours from the art (androidx `palette`) on the
Now Playing screen only.

## Phase 4: beyond the app screen

### 14. Navigation at bottom

Setting from step 9: the title bar (back, title, actions) moves to the bottom, above the mini-player, for
one-handed use.

### 15. Landscape and tablets

Drop `screenOrientation="portrait"`. Lists stay as they are; Now Playing gets a two-column layout (art left, info
and controls right) for every layout in step 13. Quick Screen and the EQ check their layouts at 800×480 landscape.

### 16. Widget and Quick Settings tile

Home-screen widget (art, title, prev / play / next) and a Quick Settings tile (play / pause, long-press opens
SlopBox), both talking to the existing media session.

## Order

1 → 2 → 5 → 6 → 7 → 10 → 11, then the rest in any order. Each step is its own commit with its DIFFERENCES.md entry
and is tested on the emulator; anything touching playback state is also checked on the phone without touching its
library or playlist.

## Decisions (2026-11-21)

- Swipe gestures are on by default.
- Album art accent recolours Now Playing only.
- The widget and Quick Settings tile ship in 1.1.
