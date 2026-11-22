# 1. Introduction

## What SlopBox is

Rockbox is free firmware for portable music players. It replaces the
player's own software and is known for its sound quality, its many codecs,
and for having a setting for nearly everything.

SlopBox brings that to an Android phone. It isn't an emulator, and it isn't
Rockbox's old Android port. It is an Android app that runs Rockbox's own code
for the parts that matter to the music:

- **Playback:** Rockbox's codecs (MP3, FLAC, Vorbis, Opus, AAC/ALAC, WavPack,
  Musepack, APE, tracker modules and many more) and its sound processing:
  equalizer, crossfeed, ReplayGain, crossfade, pitch and tempo, compressor,
  surround and the rest of Sound Settings. Gapless playback works as in
  Rockbox.
- **Settings:** the whole Settings menu, built from Rockbox's own settings
  table, with Rockbox's names, ranges and defaults. Settings are saved in
  Rockbox's `config.cfg` format, and `.cfg` files load in either.
- **The database:** Rockbox's `tagnavi.config` menus (Artist, Album, Genre,
  Year…), with play counts, ratings and the other runtime data.
- **Playlists and bookmarks:** Rockbox's Playing Next menu, `.m3u`/`.m3u8`
  files, the Playlist Catalogue, bookmarks, cuesheets and A-B repeat.
- **Plugins:** music plugins ported from Rockbox (Random Folder Advance, LRC
  Player, Last.fm Logger, Alarm Clock), plus SlopBox's own Rater layout for
  rating tracks.

The parts that belong to Android are Android's own. The screens are drawn by
SlopBox for a touchscreen, in the style of Rockbox's cabbiev2 theme.
Playback runs as an Android media service, so music keeps playing with the
screen off, appears in the notification and on the lock screen, and answers
headset and Bluetooth buttons.

## What it isn't

SlopBox doesn't load Rockbox skins (`.wps`, `.sbs`), fonts or languages,
and it has no voice menus yet. It has no recording, radio or games. Its own
layouts and themes take the place of skins (see
[Settings](07-settings.md#theme-settings)). Settings that SlopBox doesn't act
on yet are still in the menu, dimmed, and are saved with your settings. The
[Settings menu appendix](menu-tree.md) marks which they are.

## About this manual

A few conventions:

- **Tap** means a short touch, **hold** or **long-press** a touch held for
  about half a second, and **swipe** a quick drag.
- Menu paths are written with ›, for example *Settings › Theme Settings ›
  Colours*.
- Setting and menu names are in *italics* or **bold**, written as SlopBox
  shows them, which is how Rockbox writes them (British spelling included:
  *Colours*).
- **WPS** (While Playing Screen) is Rockbox's name for the playing screen.
  The main menu calls it *Now Playing*.

Readers who know Rockbox can skim chapters 3 to 7 for the touch controls.
The settings themselves behave as they do in Rockbox.
