# 7. Settings

*Settings* in the main menu is Rockbox's settings menu. Every item, with
Rockbox's names, values and defaults, comes from Rockbox's own settings
table. The [appendix](menu-tree.md) lists the whole tree.

**Changing a setting:** tap it to see its values, then tap one. It takes
effect at once and is saved. Hold a setting for *Reset Setting* and to put
it on the Quick Screen or in Shortcuts (see [The
interface](03-interface.md#context-menus)).

**Dimmed settings** are ones SlopBox doesn't act on yet, mostly for screens
and hardware SlopBox doesn't have (scrolling, peak meter, keyclick, voice,
fonts). They can still be changed and are saved, so a `.cfg` file keeps
them for a Rockbox player.

This chapter goes through the menus, concentrating on what each setting
does on a phone. Rockbox's manual has more detail on the sound processing.

## Sound Settings

Everything here runs through Rockbox's DSP before the sound reaches
Android, so it applies to headphones, speaker and Bluetooth alike.

- **Volume** (−80 to 0 dB): Rockbox's volume, an attenuation applied on
  top of the phone's media volume. 0 dB is full volume. Use the phone's
  volume keys for everyday changes and this to lower SlopBox relative to
  other apps.
- **Maximum Volume Limit**: caps *Volume*.
- **Bass**, **Treble** (−24 to +24 dB): tone controls. 0 dB is flat.
- **Balance**: left/right balance.
- **Channel Configuration**: Stereo, Mono, Custom (uses *Stereo Width*),
  Mono Left, Mono Right, Karaoke (removes sound panned to the centre, often
  the vocals), Swap Left & Right.
- **Stereo Width** (0–250 %): with Channel Configuration set to Custom,
  narrows (below 100 %) or widens (above) the stereo image.
- **Crossfeed**: mixes a little of each channel into the other, as
  loudspeakers do, so headphone listening is less tiring. *Simple (Meier)*
  is a preset; *Custom* uses *Direct Gain*, *Cross Gain*,
  *High-Frequency Attenuation* and *High-Frequency Cutoff*.
- **Equalizer**: a 10-band parametric equalizer.
  - *Enable EQ* turns it on.
  - *Graphical EQ* shows the bands: tap a band, tap its type, gain, cutoff
    or Q, then step it with − and + (−− and ++ for big steps), which repeat
    while held.
  - *Precut* lowers the overall level to leave room for boosts (avoids
    clipping).
  - *Simple EQ Settings* gives just the gains; *Advanced EQ Settings* every
    parameter.
  - *Save EQ Preset* and *Browse EQ Presets* save and load `.cfg` files with
    only the EQ in them.
- **Dithering**: adds a little noise when reducing the bit depth, for
  smoother quiet passages.
- **Haas Surround**: a delay-based stereo widening effect.
- **Perceptual Bass Enhancement**: makes bass more audible on small
  speakers and earbuds.
- **Auditory Fatigue Reduction**: gently tames harsh high frequencies.
- **Timestretch**: lets the pitch screen change speed and pitch separately.
- **Compressor**: evens out loud and quiet passages (*Threshold*,
  *Makeup Gain*, *Ratio*, *Knee*, *Attack Time*, *Release Time*).

Changes to sound settings are heard after the audio already decoded has
played: about a quarter of a second, or longer with crossfade on.

## Playback Settings

- **Shuffle**: plays the playlist in random order. Turning it off returns
  to the original order.
- **Repeat**: Off, All, One, Shuffle (reshuffle and play again at the end),
  A-B (see [A-B repeat](04-while-playing-screen.md#a-b-repeat)).
- **Play Selected First**: with Shuffle on, the track you tapped plays
  first.
- **Fast-Forward/Rewind**: *FF/RW Min Step* (the first step) and *FF/RW
  Accel* (how quickly the steps grow while you hold the button).
- **Fade on Stop/Pause**: fades out on pause and in on resume.
- **Single Mode**: stop when the track, album, album artist, artist,
  composer, work, genre or playlist changes.
- **Party Mode**: new tracks are queued instead of replacing the playlist,
  and pause, stop, seek and skip are locked while music plays.
- **Crossfade**: mixes the end of one track into the start of the next.
  *Enable Crossfade* chooses when (Automatic Track Change Only, Manual
  Track Skip Only, Shuffle, Shuffle or Manual Track Skip, Always), the fade-in and fade-out delays and
  durations shape the fade, and *Fade-Out Mode* chooses crossfading or
  mixing.
- **Replaygain**: evens out loudness between tracks using their ReplayGain
  tags. *Replaygain Type*: Track Gain, Album Gain, Track Gain if Shuffling,
  or Off. *Prevent Clipping* and *Pre-amp* adjust it.
- **Track Skip Beep**: a beep when you skip.
- **Auto-Change Directory**: at the end of a folder, carry on with the
  next folder (or a random one from the Random Folder Advance plugin's
  list).
- **Cuesheet Support**: read cuesheets (embedded or `.cue` files) and treat
  their sub-tracks as tracks.
- **Skip Length**: what next/previous do: skip a track, jump a set time
  inside the track, or skip to the outro.
- **Prevent Track Skipping**: next/previous only work inside the track.
- **Rewind Across Tracks**: holding ⏮ at the start of a track continues
  into the previous one.
- **Rewind Before Resume**, **Rewind on Pause**: go back a few seconds when
  resuming.
- **Album Art**: Off, Prefer Embedded, or Prefer Image File (a picture in
  the folder such as `cover.jpg` or `folder.jpg`).
- **Logging**: writes a playback log (used by the Last.fm Logger plugin).

## General Settings

- **While Playing Screen**: the *WPS Hotkey* (held **C** button) and the
  four *Set Context Item* entries added to the WPS context menu. *Reset
  Settings* puts them back.
- **Playlists**: *Sort Playlists* in the catalogue, *Playlist Viewer
  Settings* (icons, numbers, what each line shows), *Recursively Insert
  Directories*, and *Current Playlist* (warn before erasing, keep the
  current track when replacing, show the shuffled and queue options).
- **File View**: sorting, *Show Files*, *Show Filename Extensions*,
  *Follow Playlist*, *Show Path*, *Always remember last folder*, *Start
  File Browser at /* and the *File Browser Hotkey*. See [The file
  browser](03-interface.md#the-file-browser-files).
- **Database**: *Auto Update*, *Initialize Now*, *Update Now*, *Gather
  Runtime Data*, *Export/Import Modifications* and *Select directories to
  scan* (which chooses your music folder). See [The
  database](06-database.md).
- **Display**: only *Default Codepage* (for tags without a text encoding) is
  used; the rest is for player screens.
- **System**: *Limits* (*Max Entries in File Browser*, *Max Playlist Size*)
  and *Use Shortcuts Menu Instead of Quick Screen*.
- **Startup/Shutdown**:
  - *Start Screen*: what SlopBox opens on: Previous Screen, Main Menu,
    Files, Database, Resume Playback or Settings. Recent Bookmarks and Open
    Plugin open the main menu. If music is already playing, SlopBox opens
    the playing screen.
  - *Idle Poweroff*: stops playback and closes the playback service after
    this long paused or stopped (not while charging).
  - *Sleep Timer*: starts or cancels the sleep timer, which stops playback
    after *Default Sleep Timer Duration*. *Start Sleep Timer on Boot* and
    *Restart Sleep Timer on Keypress* work as in Rockbox; a touch on
    SlopBox or any player control counts as a key press.
  - *Show Shutdown Message*.
- **Bookmarking**: see [Bookmarks](05-playlists-and-bookmarks.md#bookmarks).
- **Automatic Resume**: see
  [Automatic Resume](05-playlists-and-bookmarks.md#automatic-resume).
- **Language**, **Voice**: not available yet.

## Theme Settings

Rockbox's themes are skins and fonts drawn for a player's screen. SlopBox
draws its own screens, so here Theme Settings set its colours and layout.

- **Browse Theme Files**: SlopBox's bundled themes. Tap one to load it;
  the one in use is highlighted, and the list stays open so you can try
  several.

  | Theme | Look |
  |---|---|
  | Cabbie | Rockbox's cabbiev2 colours: light grey on black, gold bar (the default) |
  | Ocean | pale blue on navy, blue bar |
  | OLED Black | grey on pure black, flat amber bar |
  | Light | dark text on off-white, gold bar |
  | High Contrast | white on black, solid yellow bar, thicker separators |

  *Other theme file…* loads a theme `.cfg` of your own, such as one saved
  with *Manage Settings › Save Theme Settings*.
- **While Playing Screen**: the playing screen's layout: Cabbie, Big Art,
  Large, Minimal or Rater. See [Layouts](04-while-playing-screen.md#layouts).
- **Show Icons**: icons in front of list lines.
- **Status-/Scrollbar**: *Scroll Bar* (Off, Left, Right), *Scroll Bar
  Width*, and *Status Bar*, which places the mini-player (Off, Top, Bottom).
- **Line Selector Type**: how the chosen line is marked: *Pointer* (an
  arrow), *Bar (Inverse)* (a bar in the text colour), *Bar (Solid Colour)*,
  or *Bar (Gradient Colour)* (the default).
- **Line Separator**: the lines between list rows: Auto, Off, or a
  thickness.
- **Colours**: the colour picker for each colour (below).
- *Font*, *Base Skin* and *Clear Backdrop* aren't available.

### Colours

*Theme Settings › Colours* has Rockbox's colour items: *Line Selector
Colours* (*Primary*, *Secondary* and *Text Colour*: the bar's gradient and
the text on it), *Line Separator Colour*, *Background Colour* and
*Foreground Colour* (the text). Each shows its current value in hex.

Tap one for the colour picker: red, green and blue sliders with − and +
buttons (hold to repeat), the hex value, and a sample list drawn in the new
colour. **OK** keeps it and **Cancel** leaves the colour as it was. As in
Rockbox, the foreground and background can't be set to the same colour.

The rest of SlopBox's look follows these colours: the background gradient,
dim text, bars and panels come from the foreground and background, and the
progress bar, buttons and lit icons take the line selector colours.

**Reset Colours** returns to the Cabbie colours.

If you choose a text colour too close to the background to read, the
screens on the way back (Settings, Theme Settings, Colours, the picker and
the theme list) are drawn in the Cabbie colours so you can fix it. The
picker warns before you save such a colour.

### SlopBox

The last item in Theme Settings holds SlopBox's own settings, which Rockbox
doesn't have:

- **Now Playing Gestures**: each gesture on the playing screen can be
  turned off. See [Touch and
  gestures](04-while-playing-screen.md#touch-and-gestures).
- **Haptic Feedback**: short vibrations for long-presses, gestures and held
  buttons. On by default. Android's own *Touch vibration* setting must be
  on too.
- **Album Art Colours on While Playing Screen**: the playing screen takes
  its bar and button colours from the album art. Off by default.
- **Flat Style**: drops the gloss from bars, buttons and icons.
- **Text Size**: Small, Normal, Large or Huge. Lines get taller with the
  text.
- **Navigation at Bottom**: moves each screen's title bar to the bottom of
  the screen.
- **Rater: Skip Rated Tracks**, **Rater: Next Track After Rating**: the
  [Rater layout](04-while-playing-screen.md#the-rater-layout)'s two
  settings, also in its context menu. On by default.

## Time & Date

Not available: the phone keeps the time.

## Main Menu

Chooses which items the main menu shows and in what order. See [The main
menu](03-interface.md#the-main-menu).

## Manage Settings

SlopBox saves your settings as you change them, in Rockbox's `config.cfg`
format. *Manage Settings* works with `.cfg` files you keep yourself:

- **Browse .cfg Files**: load a `.cfg` file. Only the settings it contains
  change. You can also tap a `.cfg` file in the file browser.
- **Save .cfg File**: every setting.
- **Save Sound Settings**, **Save Theme Settings**: only those settings.
- **Reset Settings**: every setting back to its default (asks first).

Files saved here can be loaded by a Rockbox player, and Rockbox's `.cfg`
files load in SlopBox. Settings one side doesn't have are ignored. Android's
file picker chooses where to save and what to load. See [Advanced
topics](09-advanced.md#settings-files) for the format.
