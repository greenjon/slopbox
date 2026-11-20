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
  bands, Maximum Volume Limit, the While Playing Screen items and the
  Quick Screen items (e.g. touchscreen calibration, colours); string and
  filename settings such as Automatic Resume's custom directories.
- **Browse actions.** Themes, fonts, skins and languages aren't available.
  Browse .cfg Files and Browse EQ Presets open Android's file picker. There is
  no bundled EQ preset folder.
- **Stored but unused.** Most General and Theme settings are saved and
  written to `.cfg` files but don't change anything yet.
- **`.cfg` header.** Saved files say they were created by SlopBox, and give
  the Rockbox revision the settings were taken from.

- **File View.** Sorting, Show Files, Show Filename Extensions, Show Path,
  Follow Playlist and Always Remember Last Folder work as in Rockbox.
  "Supported" files are the ones SlopBox can do something with: audio,
  playlists, `.cfg` (loads the settings), `.cue` (shows its tracks), `.bmark`
  (lists its bookmarks) and `.lrc`/`.lrc8`/`.snc` (open the LRC Player).
  Other files, such as images and text, do nothing when opened, since their
  Rockbox viewer plugins aren't ported. Sort By Type orders playlists, then
  audio, then the other known types, then unknown ones.

## Playback settings

- **Shuffle.** Tracks added while shuffled are appended to the remembered
  natural order. Turning shuffle off therefore puts them at the end, not where
  they were inserted.
- **Repeat from the player buttons.** The playing screen's repeat button
  steps through all five Rockbox modes (Off, All, One, Shuffle, A-B), as
  cabbiev2's does. The notification and lock screen know only Media3's
  Off/All/One.
- **A-B repeat.** Rockbox sets A and B with hardware buttons; cabbiev2's
  touch screen has none for it. In SlopBox, with Repeat set to A-B, a row of
  A, B and A-B ✕ (clear) buttons appears above the controls. The markers are
  drawn on the progress bar as Rockbox does. The B marker is checked every
  50 ms against Rockbox's 200 ms window.
- **Party Mode.** Rockbox's playing screen also refuses Play while paused.
  SlopBox lets a paused player resume, and locks pause, stop, seek and skip
  only while music plays. Playing from the database queues tracks last;
  Rockbox refuses with a "Party Mode" message.
- **Single Mode "Playlist".** Only an automatic folder change counts as a new
  playlist.
- **Auto-Change Directory.** Android grants only the chosen music folder, so
  folder changes always stay inside it. "Constrain Auto-Change" therefore has
  no further effect. "Random" picks from `folder_advance_list.dat`, made by
  the Random Folder Advance plugin, as in Rockbox; without it, it moves to
  the next folder. A "folder playlist"
  means every track came from the same folder (Rockbox's `dirplay` flag).
- **FF/RW.** The acceleration follows Rockbox's `ffwd_rew()`, but steps
  repeat every 150 ms of holding (the UI's rate), not at Rockbox's button
  repeat rate.
- **Cuesheets.** Rockbox also looks in `/.rockbox/cue/`. SlopBox doesn't,
  and checks only the embedded cuesheet or chapters, `<name>.cue` beside the
  file, and `<file>.cue`. Cuesheets without a BOM are read as ISO-8859-1;
  Rockbox uses the Default Codepage setting. The playing screen doesn't draw
  Rockbox's cue markers on the progress bar, and there is no "View Cuesheet"
  screen yet.
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

## General settings

- **Power off.** Rockbox's sleep timer and Idle Poweroff turn the player off.
  SlopBox does what the clean shutdown does to playback: stop (with the stop
  fade), save the resume point and end the playback service, which removes
  the notification. The app's screen, if open, stays.
- **On the charger.** Rockbox checks for USB and the charger. SlopBox asks
  Android whether the phone is charging (USB charging counts).
- **Key presses.** For the sleep timer and Idle Poweroff, any player command
  (screen, notification, headset, lock screen) and any touch on SlopBox's
  screens counts as a key press. Touches in other apps don't.
- **Show Shutdown Message** shows a toast ("Shutting down...").
- **Bookmark files.** Rockbox writes `<folder>.bmark` beside the music and
  `/.rockbox/most-recent.bmark`. SlopBox keeps the same lines in its own
  storage (`files/bookmarks/`, `files/most-recent.bmark`), because Android
  grants it read-only access to the music folder. `bookmark.ignore` and
  `bookmark.unignore` files aren't checked.
- **What can be bookmarked.** Folder playlists and playlist files, as in
  Rockbox. A playlist built by inserting tracks is bookmarked by its folder
  only when all its tracks share one.
- **Shuffled bookmarks.** Rockbox stores its shuffle seed and rebuilds the
  same order. SlopBox stores the track's place in natural order; loading
  reshuffles with that track first.
- **Bookmark details.** The byte offset is written as 0, and SlopBox resumes
  by time. Pitch and speed are saved and restored as in Rockbox.
- **Bookmark list actions.** Rockbox offers Resume or Delete from a context
  menu. In SlopBox you tap to resume and hold to delete.
- **"Ask" prompts.** They appear only when SlopBox's screen is connected to
  the player. Stopping from the notification or a headset doesn't ask.

## Playlists

- **Play Last after a mid-playlist start.** When a playlist was started
  partway through, Rockbox rotates it and "Play Last" inserts at
  `first_index`, which is the end of the rotated playlist. SlopBox doesn't
  rotate, so "Play Last" always appends to the end of the list.
- **Catalog folder.** The Playlist Catalog is `Playlists/` inside the chosen
  music folder, not `/Playlists` at the root of the storage. Android only
  grants SlopBox the folder you picked. Absolute paths in playlist files are
  still storage paths (`/Music/...`), as in Rockbox.
- **Write access.** Saving or adding to a playlist needs write permission for
  the music folder. A folder picked before playlists existed was granted
  read-only, so you have to pick it again (General Settings › Database ›
  Select directories to scan).
- **Saving with queued tracks.** Rockbox asks "Remove Queued Tracks?". SlopBox
  doesn't ask: it saves without the queued tracks and leaves them in the
  current playlist, which is what Rockbox does when you answer No.
- **Playlist viewer.** A long press opens Rockbox's track menu (Playing
  Next, Add to Playlist, Remove, Move, Show Track Info, Shuffle, Save,
  Playlist Viewer Settings) without PictureFlow. Move is "tap a track, then
  tap where it goes" rather than Rockbox's move-cursor mode. Shuffling a
  playlist file in the viewer uses Android's random order rather than
  Rockbox's seeded one.
- **Catalog folder from Set As.** "Set As... › Playlist Directory" works only
  for a folder inside the music folder.
- **Create Playlist.** It collects every track in the music folder, not from
  `/`.
- **Search in Playlist.** The number found is shown above the results rather
  than in a splash, and there's no limit on the number of results.
- **Playlist names.** You type a name in a text field rather than Rockbox's
  on-screen keyboard. The suggested name is the playlist file's name, the
  folder's name or "PlaylistN", following Rockbox's rules.
- **Playing time.** Durations come from each track's tags (read by Rockbox's
  metadata parser). Rockbox uses the lengths it already has in its playlist
  buffer.
- **Queued tracks in the viewer.** They aren't drawn with Rockbox's separate
  queued icon.

## Context menus and files

- **WPS hotkey.** The first "context_wps" item is the WPS hotkey. Rockbox's
  touchscreen keymap fires it with a tap in the bottom-right cell of its 3x3
  grid, but cabbiev2's touch regions replace that grid and leave it unbound.
  In SlopBox a long press on the "C" button of the playing screen's pop-up
  runs it.
- **Set Song Rating.** You pick 0 to 10 from a list rather than Rockbox's
  number spinner.
- **Open With.** Rockbox lists its viewer plugins. SlopBox hands the file to
  another Android app through the system chooser.
- **Open Plugin.** SlopBox has no plugins, so this item only says so.
- **Not offered.** PictureFlow, Set as Backdrop, and
  Set As › Database Directory or Recording Directory. Rockbox's Show in Files
  is offered from the playing screen and the database, as in Rockbox.
- **Folder menu.** The menu button in the file browser's title bar is
  SlopBox's own. It offers Playing Next on the folder, Paste, Create
  Directory and Change Music Folder, so an empty folder can still be pasted
  into. Rockbox shows Paste and Create Directory only in an item's context
  menu (SlopBox does too).
- **File operations.** They go through Android's storage framework. Moving
  tries a direct move first and then copy and delete, as Rockbox tries rename
  first. There's no "Copying..." or "Deleting..." progress and no way to
  cancel. A failure shows "Paste Failed" (and so on) without Rockbox's error
  number.
- **Names.** Rename and Create Directory use a text field rather than
  Rockbox's on-screen keyboard.
- **Properties.** Selecting a line doesn't open it full screen. For a folder,
  the "[Show Track Info]" line (the number of audio files) doesn't open
  Rockbox's combined track info.
- **Track info.** Fields can't be opened full screen. ReplayGain is shown in
  dB worked out from the gain Rockbox's parser stores, so the last digit can
  differ from the tag's text.
- **Pitch screen.** It has the touchscreen layout of Rockbox's pitch screen
  plugin. A held cell repeats after 0.5 s and then every 0.1 s, which may
  differ from Rockbox's touchscreen timing. It has no voice. Pitch and speed
  are kept like Rockbox's resume pitch and speed and restored when SlopBox
  starts.
- **Start Directory.** "Set As... › Start Directory" takes effect the next
  time SlopBox starts, which is Rockbox's equivalent of booting.

## Database and tags

- **Database.** The database is SlopBox's own (`ui/db`), not Rockbox's
  `tagcache`. Tags are read with Rockbox's metadata parser.
- **Track numbers.** Rockbox reports "no track number" as -1; the database
  stores 0.
- **Runtime data.** Play count, rating, play time and last played follow
  Rockbox's rules (Gather Runtime Data, the 15 s rule, autoscore, the
  last-played serial, kept on Update Now and lost on Initialize Now). A file
  that moved gets its statistics back as in Rockbox, but SlopBox compares the
  file name, artist, album and title themselves rather than their CRC32
  hashes. The playing screen doesn't show them, because there are no skins.
- **Export and Import Modifications.** `database_changelog.txt` is written
  to and read from the top of the music folder, not Rockbox's database
  directory. Its file names are storage paths (`/Music/...`), so a changelog
  from a Rockbox player matches when the music sits at the same path.
  `comment` and `grouping` are written empty because SlopBox's database
  doesn't keep them, `lastoffset` is 0, and `lastelapsed` comes from
  SlopBox's Automatic Resume points (and goes back into them on import).

## Plugins

- **Plugins are ports.** Rockbox plugins are C programs written against
  Rockbox's plugin API and screen. SlopBox ports them one by one in Kotlin
  (Plugins › apps, …) and keeps their files and formats. Only music-related
  plugins are ported.
- **Random Folder Advance.** `folder_advance_list.dat` is kept in SlopBox's
  own storage in Rockbox's binary format. The text files it exports and
  imports (`folder_advance_list.txt`) and the optional
  `folder_advance_dir.txt` sit at the top of the music folder, not in
  `/.rockbox/`. Scanning covers the music folder rather than the whole disk.
- **LRC Player.** Lyrics files are looked for as Rockbox does (the track's
  folder and its parents, each one's "Lrc Directory" subfolder, then the
  same under "Lrc Directory"), but only inside the music folder. It looks
  straight away rather than waiting up to 3 s for the track's tags. The
  wipe moves a character at a time rather than a pixel at a time, lines
  wrap where Android's text layout breaks them, and the list moves a line
  at a time. The current line is drawn in the text colour and the rest
  dimmed, rather than with "Inactive Colour". UTF-16 lyrics files are read
  but not rewritten as UTF-8. Not ported yet: the Timetag Editor (so the
  Time Offset isn't saved into the file), Playback Control, Show Statusbar,
  Inactive Colour.
- **Last.fm Logger.** `.scrobbler.log` goes at the top of the music folder
  (Rockbox: the top of the player's disk), and the playback logs it reads
  are SlopBox's own. Its `#CLIENT` line names SlopBox and its version. View
  log shows the playback log as a plain list rather than through the
  separate lastfm_scrobbler_viewer plugin.
- **Alarm Clock.** It counts down only while its screen is showing, so it
  keeps the phone's screen on while it waits (a Rockbox player sits in the
  plugin the same way). Touch uses the plugin library's 3x3 grid.

## Interface

- **Theme engine.** There is none. SlopBox has a native cabbiev2-style
  interface instead of Rockbox's skins.
- **Main menu.** It has Rockbox's order, but only the items SlopBox has:
  no Recording or FM Radio. Its System menu so far has About SlopBox,
  Rockbox Credits and License in place of Rockbox Info and the debug
  screens. SlopBox doesn't use the Rockbox name or logo for itself.
- **Quick Screen.** It shows Rockbox's four configurable items and uses
  Rockbox's touchscreen rule (a tap in the top, left, right or bottom third
  changes that item; anywhere else leaves), drawn as text rather than with
  Rockbox's arrows. Volume as an item changes the Volume setting, not
  Android's media volume.
- **Setting context menu.** A long press on a setting gives Rockbox's menu.
  A long press on a submenu just opens it.
- **Shortcuts.** `shortcuts.txt` is kept in SlopBox's own storage, not
  `/.rockbox/`. Its format is Rockbox's. "debug" shortcuts and "reboot" only
  say they aren't available, "shutdown" closes SlopBox as Idle Poweroff does,
  "talk" time shortcuts do nothing (no voice), and the `icon:` number is kept
  but SlopBox picks the icon by type. A "browse" shortcut to a file opens its
  folder with the file selected; a "file" shortcut plays it.
