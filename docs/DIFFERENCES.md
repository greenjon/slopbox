# Where SlopBox differs from Rockbox

SlopBox aims to behave like Rockbox. This file records every known place where
it doesn't, whether it's a shortcut, an Android limitation or something not
built yet. Each entry says what Rockbox does and what SlopBox does instead.
Anything new goes here in the same change that introduces it.

## Playback engine

- **Sound-menu low latency.** Rockbox lowers its buffering while you're in
  the sound menus (`lowlatency_callback`), so EQ and other DSP changes are
  heard at once. SlopBox doesn't. With crossfade on, DSP changes are heard
  only after the audio already buffered has played, which can be up to the
  fade-out delay + duration + 1 s.
- **Buffer size without crossfade.** Rockbox buffers far ahead (file buffer
  plus PCM buffer watermark). SlopBox decodes only 1/4 s ahead when crossfade
  is off, to keep settings changes quick.
- **Seeking while the next track is already decoding.** Rockbox re-buffers
  the current track. SlopBox restarts the current file at the seek position.
- **Output rate.** SlopBox's DSP always outputs 44.1 kHz (the rate Rockbox's
  `warble` configuration fixes) and Android resamples to the device rate.
  Rockbox's hosted builds also use a fixed rate, but hardware targets can
  differ.
- **Volume.** On a player, Rockbox's Volume setting is the device volume. In
  SlopBox it is Rockbox's software volume (an attenuation), applied on top of
  Android's media volume.
- **Track skip beep.** Rockbox mixes the beep on its own mixer channel,
  scaled by master volume, and it can sound while playback is paused. SlopBox
  mixes it into the output after volume and balance, and it isn't heard while
  paused.
- **Fade on Stop/Pause.** SlopBox fades on pause and resume, but Stop is
  immediate. Rockbox also fades on stop.
- **Codecs.** `sid` (cRSID) isn't built, because it uses GCC nested functions
  that the NDK's clang rejects. `ape` is missing on x86_64 only (MMX inline
  assembly); phones are arm64 and have it.
- **Position.** The position shown is interpolated from stamps taken as audio
  is buffered, so it can drift slightly from Rockbox's codec-reported elapsed
  time, especially with timestretch.

## Settings

- **Target.** The settings tree is extracted from Rockbox's hosted app
  (`sdlapp`) configuration. Settings that exist only for other targets, such
  as recording, radio or hardware EQ, are absent.
- **Menu callbacks** (e.g. `lowlatency_callback`, `eq_setting_callback`,
  item-hiding callbacks) aren't run. Every item is always shown.
- **Dimmed items.** These are a SlopBox addition: settings SlopBox doesn't act
  on yet are shown dimmed, but are still editable and saved.
- **Value screens.** In Rockbox you scroll through values and the setting
  changes as you go (live for sound settings), then you confirm or cancel. In
  SlopBox you tap a value, which applies it and goes back.
- **Not editable yet.** Graphical EQ; custom-type settings other than the EQ
  bands and Maximum Volume Limit (e.g. touchscreen calibration, Quick Screen
  items, colours); string and filename settings such as Automatic Resume's
  custom directories.
- **Browse actions.** Themes, fonts, skins and languages aren't available.
  Browse .cfg Files and Browse EQ Presets open Android's file picker. There is
  no bundled EQ preset folder.
- **Stored but unused.** Most General and Theme settings are saved and
  written to `.cfg` files but don't change anything yet.
- **`.cfg` header.** Saved files say they were created by SlopBox, and give
  the Rockbox revision the settings were taken from.

## Playback settings

- **Shuffle.** Tracks added while shuffled are appended to the remembered
  natural order. Turning shuffle off therefore puts them at the end, not where
  they were inserted.
- **Repeat from the player buttons.** The notification, lock screen and
  playing-screen buttons cycle Off/All/One (Media3's modes). Shuffle and A-B
  are set from the Settings menu.
- **A-B repeat.** There are no playing-screen buttons yet. The A, B and reset
  actions exist as session commands (`PlaybackService.CMD_AB_*`). The B marker
  is checked every 50 ms against Rockbox's 200 ms window.
- **Party Mode.** Rockbox's playing screen also refuses Play while paused.
  SlopBox lets a paused player resume, and locks pause, stop, seek and skip
  only while music plays. Playing from the database queues tracks last;
  Rockbox refuses with a "Party Mode" message.
- **Single Mode "Playlist".** Only an automatic folder change counts as a new
  playlist.
- **Auto-Change Directory.** Android grants only the chosen music folder, so
  folder changes always stay inside it. "Constrain Auto-Change" therefore has
  no further effect. "Random" picks from a folder list built on the fly;
  Rockbox needs `folder_advance_list.dat` from a plugin. A "folder playlist"
  means every track came from the same folder (Rockbox's `dirplay` flag).
- **Next on the last track.** With repeat off, Android's media framework
  doesn't pass Next through when there's no next item. So Next on the last
  track does nothing: no "no more" beep, no folder change, and no Skip Length
  hop. Rockbox handles all three.
- **FF/RW.** The acceleration follows Rockbox's `ffwd_rew()`, but steps
  repeat every 150 ms of holding (the UI's rate), not at Rockbox's button
  repeat rate.
- **Cuesheet support** isn't implemented.
- **Automatic Resume.** Resume points are kept per file in SlopBox. Rockbox
  keeps them in the database (`tagcache`), so there only database tracks get
  them. "In custom directories only" behaves like "No", because there's no
  directory list setting yet.
- **Resume.** The saved playlist is restored when the app starts, not at
  boot. Tracks opened with "Open with" from another app may be unreadable
  after a restart, because Android's temporary permission expires; they are
  skipped.
- **Start Screen.** If music is already playing when the app opens, it shows
  the playing screen whatever the setting says. Recent Bookmarks and Open
  Plugin open the Main Menu.
- **Playback log.** The log is the app's `files/playback.log`, not
  `/.rockbox/playback.log`. Paths are storage paths ("/Music/…") for library
  files, and full paths for app-local files.

## Database and tags

- **Database.** The database is SlopBox's own (`ui/db`), not Rockbox's
  `tagcache`. Tags are read with Rockbox's metadata parser.
- **Track numbers.** Rockbox reports "no track number" as -1; the database
  stores 0.

## Interface

- **Theme engine.** There is none. SlopBox has a native cabbiev2-style
  interface instead of Rockbox's skins.
