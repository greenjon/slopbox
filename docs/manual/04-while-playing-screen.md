# 4. The While Playing Screen

The While Playing Screen (WPS) shows what's playing. Open it with *Now
Playing* in the main menu, or the mini-player at the bottom of any list.

## The Cabbie layout

The default layout follows Rockbox's cabbiev2 theme:

- the **NOW PLAYING** bar
- the album art, then the album, title and artist (and the codec and bit
  rate). Without art, the track lines move up and the genre and codec lines
  fill the space.
- the next track, at the bottom of the track area
- the **progress bar**, then the elapsed time, the track's place in the
  playlist ("3 of 12") and the time left
- the **controls**: rewind/previous, fast forward/next, volume, shuffle,
  repeat and play/pause

Other layouts are described [below](#layouts).

## Controls

| Control | Tap | Hold |
|---|---|---|
| ⏮ | Previous track (or the start of this one, per *Rewind Across Tracks*) | Rewind, speeding up as you hold |
| ⏭ | Next track | Fast forward, speeding up as you hold |
| 🔊 | Shows the volume bar for a moment | |
| Shuffle | Turns shuffle on or off | |
| Repeat | Steps through Off, All, One, Shuffle and A-B | |
| ▶ / ❚❚ | Play or pause | Stop |
| Progress bar | Jumps there | Drag to seek; the bar thickens while you drag |
| Time left | Switches between the time left and the track's length | |

Rewind and fast forward follow Rockbox's *FF/RW Min Step* and *FF/RW Accel*
settings (*Playback Settings › Fast-Forward/Rewind*). With the phone's
volume keys, the volume bar appears too. SlopBox's *Volume* setting is
Rockbox's software volume, applied on top of the phone's media volume.

## Touch and gestures

**Tap the album art** to open the current playlist, as in cabbiev2.

**Tap the track lines or the space around the art** for the pop-up, which
holds three buttons for a few seconds:

- **Folder:** the file browser (at the playing track, if *Follow Playlist*
  is on) or the browser you started from.
- **Q:** the Quick Screen (or Shortcuts; see [Quick
  Screen](03-interface.md#quick-screen)).
- **C:** the WPS context menu (below). Holding **C** runs the *WPS
  Hotkey*.

**Gestures** work over the art and the track lines. Each can be turned off
in *Settings › Theme Settings › SlopBox › Now Playing Gestures*; all are on
by default.

| Gesture | Does |
|---|---|
| Swipe left | Next track |
| Swipe right | Previous track |
| Swipe up | The current playlist |
| Double-tap | Play or pause |
| Long-press | The context menu |
| Drag up or down along the right edge | Volume (the volume bar shows) |

With *Double-Tap* on, a single tap waits a moment to be sure it isn't a
double one, so the pop-up and the playlist open a little later. Turn
Double-Tap off if that bothers you.

## The context menu

The **C** button (or a long-press) opens the WPS context menu:

- **Current Playlist:** view, save, reshuffle, and the playlist's playing
  time
- **Add to Playlist…**
- **Sound Settings**, **Playback Settings**
- **Bookmarks:** create a bookmark, or list this playlist's bookmarks
- **Set Song Rating** (with *Gather Runtime Data* on)
- **Show Track Info:** every tag Rockbox's parser read, plus codec, bit
  rate, ReplayGain and file details
- **Show in Files:** opens the file browser at the track
- **Pitch:** the pitch screen (below)
- **Browse Cuesheet** (when the track has a cuesheet)
- **View Album Art**
- **Delete** the file (asks first)
- the items set in *General Settings › While Playing Screen* (*Set Context
  Item 1–4*): for example Open Plugin, Show Track Info, Delete

**The WPS Hotkey** (*General Settings › While Playing Screen › WPS Hotkey*)
is run by holding the **C** button. Set it to *Open Plugin* and choose a
plugin, such as the Track Rater, to open it with one press.

## A-B repeat

Set *Repeat* to **A-B** (tap the repeat button until it shows A-B). A row
of three buttons appears above the controls:

- **A:** sets the start point to where playback is now.
- **B:** sets the end point and jumps back to A. From then on, the part
  between A and B loops.
- **A-B ✕:** clears both points.

The points are marked on the progress bar with arrows, as in Rockbox.

## Cuesheets

When a track has a cuesheet (embedded, or a `.cue` file beside it; see
*Playback Settings › Cuesheet Support*), its sub-tracks are marked on the
progress bar, next/previous move between them, and *Browse Cuesheet* in
the WPS context menu lists them so you can jump to one.

## The pitch screen

*Pitch* in the context menu opens Rockbox's pitch screen, with the touch
layout of Rockbox's pitch screen plugin. It changes the playback speed and
pitch: by rate (both together, like a turntable), by semitones, or, with
*Sound Settings › Timestretch* on, speed and pitch separately. The cells
step the value; hold one to repeat. The setting is kept when SlopBox
restarts, as Rockbox does.

## Layouts

*Settings › Theme Settings › While Playing Screen* chooses the layout. It's
saved in Rockbox's `wps` setting, under the names in brackets.

- **Cabbie** (`cabbiev2`): the screen described above. The default.
- **Big Art** (`slop-bigart`): the album art as big as fits, with the title,
  artist and album over its bottom edge, on a blurred copy of the art
  (Android 12 and later). Tracks without art fall back to large type.
- **Large** (`slop-large`): no art; very large type and bigger buttons, for
  the car or the gym.
- **Minimal** (`slop-minimal`): no art; the track lines, the progress bar,
  skip and play/pause only.

The gestures, the pop-up and the controls work the same way in every
layout. When the phone is turned sideways, every layout puts the art and
track on the left and the progress bar and controls on the right.

**Album Art Colours** (*Theme Settings › SlopBox*, off by default) take the
bar and button colours from the album art while the playing screen is
showing, and go back to your theme's colours everywhere else.
