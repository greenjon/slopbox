# 9. Files and advanced topics

## Where SlopBox keeps things

SlopBox uses two places.

**Your music folder**, the folder you chose, which you can reach from a
computer or a file manager:

| File | What |
|---|---|
| `Playlists/` | The Playlist Catalogue (`.m3u8` files) |
| `database_changelog.txt` | Runtime data and ratings, from *Export Modifications* and the Track Rater |
| `.scrobbler.log` | From the Last.fm Logger plugin |
| `folder_advance_list.txt`, `folder_advance_dir.txt` | Random Folder Advance's text list and scan filter |
| `.cfg` files | Wherever you saved them with *Manage Settings* |

**SlopBox's own app storage**, which Android keeps private to the app:
`config.cfg` (your settings), the database, the resume state, bookmarks
(`bookmarks/`, `most-recent.bmark`), the playback log, `shortcuts.txt`,
Random Folder Advance's `folder_advance_list.dat`, and each plugin's
settings (`lastfm_scrobbler.cfg`, `lrcplayer.cfg`, `track_rater.cfg`).
Uninstalling SlopBox deletes these. To keep your settings, save them with
*Manage Settings › Save .cfg File* first, and your ratings and play counts
with *Export Modifications*.

## Settings files

SlopBox saves settings in Rockbox's `.cfg` format: plain text, one setting
per line, written `name: value`, with lines starting with `#` ignored.

```
# .cfg file created by SlopBox (settings from Rockbox 3a299f5dea)
volume: -6
crossfeed: meier
eq enabled: on
statusbar: bottom
foreground color: cccccc
selector type: bar (gradient)
```

- **Names and values** are Rockbox's, so a file from a Rockbox player loads
  in SlopBox and the other way round. Settings one side doesn't know are
  skipped.
- **Colours** are six hex digits, `rrggbb`, as Rockbox writes them.
- **SlopBox's own settings** are written as `slopbox …` (for example
  `slopbox flat style: on`), which Rockbox ignores.
- `config.cfg` holds only the settings you've changed from their defaults.
  *Save .cfg File* writes every setting, and *Save Sound Settings* and *Save
  Theme Settings* only those.

A **theme** is just a `.cfg` file with theme settings in it. To make your
own, set the colours, Line Selector Type and Line Separator the way you
like, use *Save Theme Settings*, then load it later with *Browse Theme
Files › Other theme file…* (or tap it in Files). For example, the bundled
Ocean theme is:

```
foreground color: d6e4f5
background color: 0a1a33
line selector start color: 7fb8ff
line selector end color: 2a62b8
line selector text color: 000000
list separator color: 4a6a96
list separator height: auto
selector type: bar (gradient)
```

## Defaults that differ from Rockbox

SlopBox starts with a few defaults of its own:

- *Status Bar* is Bottom (the mini-player at the bottom), where Rockbox's
  is Top.
- The colours are cabbiev2's, and *Line Separator* is Auto, as in the
  theme Rockbox installs by default.

A `.cfg` file without these lines therefore gives SlopBox's defaults.

## Custom database menus

Rockbox's database menus come from `tagnavi.config`, which includes
`tagnavi_custom.config` for your own menus. SlopBox reads that file from its
private storage, which release builds give no way to reach yet. Custom
menus are planned.

## Codecs

SlopBox plays what Rockbox plays: MP3 and the other MPEG audio layers,
FLAC, Ogg Vorbis, Opus, Speex, AAC and HE-AAC, ALAC, WAV/AIFF/AU, WavPack,
Monkey's Audio (APE), Musepack, TTA, Shorten, WMA and WMA Pro, AC3, ATRAC3,
Cook/RealAudio, DSD, tracker modules (MOD), and game music formats (NSF,
SPC, VGM, GBS, AY, HES, KSS, SGC, SAP and more). SID files aren't
supported.

## Getting help and reporting problems

Report problems with SlopBox at
https://github.com/greenjon/slopbox/issues, not to the Rockbox project.
Every known difference from Rockbox's behaviour is listed in
[DIFFERENCES.md](../DIFFERENCES.md); check it first when something behaves
differently from a Rockbox player.
