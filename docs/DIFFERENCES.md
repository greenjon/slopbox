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
- **Volume.** Volume is Android's media volume, as in Rockbox's own Android
  port: the Volume setting, the Quick Screen item, the volume keys, the
  volume bar and the right-edge drag all move it, in Android's steps
  (shown as "7 / 15") rather than in dB. Rockbox's software volume stays at
  0 dB. Volume isn't written to or read from `.cfg` files, and it has no
  Reset Setting (Reset Settings leaves it alone).
- **Maximum Volume Limit.** Dropped: Android's media volume can't be capped,
  since the phone's volume keys go past any limit. It isn't in Sound
  Settings and isn't saved or loaded.
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
- **Graphical EQ.** The same screen, ranges and steps as Rockbox's. Touch
  is direct: tap a band, tap its type, gain, cutoff or Q to edit that, and
  step with −/+ (−−/++ for big steps), which repeat while held. Rockbox's
  touchscreen keymap for this screen is a 3x3 grid instead.
- **Not editable yet.** Custom-type settings other than the EQ
  bands, the While Playing Screen items and the
  Quick Screen items (e.g. touchscreen calibration).
- **Automatic Resume custom folders.** "In custom directories only" opens a
  port of Rockbox's db_folder_select plugin over the music folder, and the
  list is matched as Rockbox's autoresumable() does.
- **Browse actions.** Fonts, skins and languages aren't available.
  Browse .cfg Files and Browse EQ Presets open Android's file picker. There is
  no bundled EQ preset folder. Browse Theme Files lists SlopBox's own themes
  (Cabbie, Ocean, OLED Black, Light, High Contrast; colours, Line Selector
  Type and Line Separator only) rather than Rockbox's, marks the one in use,
  and stays open so several can be tried; "Other theme file…" opens
  Android's file picker for themes of your own.
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

- **Default Codepage.** Tags without an encoding are read through Rockbox's
  own codepage tables, made at build time by Rockbox's `tools/codepages` and
  kept in SlopBox's storage rather than `/.rockbox/codepages`.

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
  only while music plays. Playing from the database or a playlist file
  queues the tracks last; Rockbox refuses with a "Party Mode" message (and
  does nothing for a track picked in a playlist file's viewer).
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
  Rockbox uses the Default Codepage setting.
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
- **Minimum Stars and Play Unrated Tracks.** SlopBox additions at the end
  of Playback Settings (and Minimum Stars in the WPS context menu). Next,
  previous and automatic track changes pass over playlist entries rated
  below the minimum (stars are Rockbox ratings ÷ 2, rounded up), and over
  unrated ones with Play Unrated off. The playlist isn't changed, and a
  track picked from a list still plays. The playing screen shows the filter
  under "3 of 12", and its *Next:* line skips filtered tracks. The Rater
  layout's Skip Rated Tracks takes precedence while it's on (previous then
  isn't filtered, so you can go back and rate a track again).
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
- **Opening a playlist file.** Rockbox's file browser plays a playlist file
  as soon as you pick it, while its Playlist Catalogue opens the same file in
  the playlist viewer. SlopBox opens it in the viewer from both places, with
  a Play row at the top that plays it from the start (checking for a
  bookmark, as Rockbox's file browser does, unless the list has unsaved
  edits). Why: a playlist is like a folder, and tapping a folder shows its
  contents, so a playlist file now does the same, and it behaves the same
  wherever you open it. Rockbox's split looks like two code paths that grew
  apart rather than a design choice. Playing Next › Play, and a file
  shortcut, still play it straight away.
- **Catalogue subfolders.** Rockbox's catalogue lists only the playlists in
  the catalog folder itself; its filter hides folders. SlopBox lists
  subfolders too (folders first, by name), and you can open them in the
  catalogue and Add to Existing Playlist.
  Why: grouping many playlists into folders is useful, and showing folders
  doesn't stop the list from being playlists only. Folders in the catalogue
  have no context menu; make, rename or delete them in the file browser.
- **Catalogue and bookmarks.** Rockbox checks for a bookmark when you pick a
  playlist in the catalogue, before the viewer opens, and the next visit
  reopens the playlist you last played from. SlopBox opens the viewer
  without checking (Play checks) and always starts at the catalogue list.
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
- **Sort Playlist.** A SlopBox addition, in the playlist viewer's track menu
  and in WPS context menu › Current Playlist. Rockbox can only put a
  playlist back in its file order (unshuffle); SlopBox sorts it by Artist,
  Album Artist, Album, Title, Year or File Path, using the database's tags
  (or the file's, for tracks not in it). The playing track keeps playing
  where it lands, queued tracks still waiting stay right after it, shuffle
  goes off, and the sorted order becomes the natural order that unshuffling
  returns to. A bookmark made afterwards still names the folder or playlist
  file, so loading it plays that in its own order, starting at the
  bookmarked track.
  Why: Rockbox keeps the playlist on disk to fit small players, where
  sorting by tags would be slow; an Android phone has the memory and the
  database to do it at once.

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
- **Open Plugin.** Choosing it for the WPS hotkey or a context item asks
  which plugin, as Rockbox's open_plugin_browse does, but from SlopBox's
  plugin list rather than a file browser over `.rock` files. The choice is
  kept in SlopBox's preferences, not `open_plugin.dat`.
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
- **Scans.** As in Rockbox, the old database stays in use until a scan
  finishes. A scan saves its progress every 250 newly read files; if
  SlopBox is closed mid-scan, the scan carries on at the next start without
  reading those files again. "Auto Update" updates the database each time
  SlopBox starts (Rockbox: at boot).
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
- **Theme colours.** Foreground, background, line selector start / end /
  text colour, list separator colour, Line Separator and Line Selector Type
  work and are read from `.cfg` files as Rockbox writes them (`rrggbb`).
  The defaults are cabbiev2's colours and its touchscreen Line Separator
  (Auto), the theme Rockbox installs by default, not the values compiled
  into Rockbox's settings table. With no
  bitmaps to recolour, SlopBox derives the rest: the backdrop runs from a
  lighter shade of the background at the top to the background, dim text
  and the greys of bars and panels are mixes of foreground and background,
  and the glossy controls and lit icons take the line selector colours.
  Line Selector Type Pointer draws a cursor in front of the line; Bar
  (Inverse) uses the foreground colour for the bar and the background
  colour for its text. The system bar icons turn dark on a light
  background.
- **Colour picker.** Theme Settings › Colours has Rockbox's items and
  picker: red, green and blue sliders, refusing a foreground equal to the
  background ("Invalid colour") as Rockbox does. Components run 0–255
  instead of the LCD's 5/6/5 bits. Touch: drag or tap a slider or step it
  with −/+ (repeat while held), then OK or Cancel. The picker shows a sample
  list in the new colour, and the Colours menu shows each colour's hex value.
  Reset Colours returns to cabbiev2's colours, SlopBox's defaults, rather
  than the LCD defaults. When the chosen text and background are too close
  to read (under WCAG's 3:1), the screens on the way to the colours
  (Settings, Theme Settings, Colours, the picker, Browse Theme Files) draw
  in cabbiev2's colours so the choice can be undone; the picker warns
  before such a colour is saved.
- **Playing screen popup.** cabbiev2 shows its Browse / Quick Screen /
  Context Menu popup on any touch, over the track lines. SlopBox shows it
  only for a tap on the track lines or the space around the art, so using
  the progress bar, the buttons or a gesture doesn't hide what's playing.
- **Playing screen gestures.** SlopBox adds gestures over the art and
  track lines, each switchable in Theme Settings › SlopBox › Now Playing
  Gestures (all on by default): swipe left / right for the next / previous
  track, swipe up for the playlist, double-tap to play or pause,
  long-press for the Context Menu, and a vertical drag along the right
  edge for the volume. With Double-Tap on, a single tap waits a moment to
  be sure it isn't a double one.
- **SlopBox settings.** Settings Rockbox doesn't have sit in Theme Settings
  › SlopBox and are saved in `config.cfg` under `slopbox ...` names, which
  Rockbox ignores.
- **Haptic feedback.** In place of a player's button clicks, SlopBox
  vibrates briefly on long-presses, gestures, each step of a held seek or
  −/+ button, Quick Screen changes, edge volume steps and each new letter
  while dragging the scroll bar (Theme Settings › SlopBox › Haptic
  Feedback, on by default; Android's own touch vibration setting applies
  too).
- **While Playing Screen layouts.** SlopBox doesn't read `.wps` skins.
  Theme Settings › While Playing Screen picks one of SlopBox's own layouts,
  saved in the `wps` setting under a skin name: Cabbie (`cabbiev2`, the
  default), Big Art (`slop-bigart`: the art across the screen with the
  track over it, on a blurred copy of the art on Android 12 and later),
  Large (`slop-large`: no art, big type and buttons), Minimal
  (`slop-minimal`: no art, only skip and play/pause) and Rater
  (`slop-rater`, below). On every layout the time left can be tapped to show the track's length instead, the progress
  bar thickens while it's dragged, and the Cabbie layout shows the codec
  line under the artist when there is art. Theme Settings › SlopBox ›
  Album Art Colours on While Playing Screen (off by default) takes the
  line selector colours, and so the bars and buttons, from the art while
  that screen is showing.
- **Rater layout** (`slop-rater`). SlopBox's own, for rating a big
  collection quickly; a Rockbox skin can show a rating (`%rr`) but has no
  way to set one. It is Minimal with the track's stars under it and one key
  per star (1 to 5), stored as the Rockbox rating stars × 2, the same value
  Set Song Rating sets, plus Undo. Each rating exports
  `database_changelog.txt` (Export Modifications) to the music folder, so
  the ratings reach the card without a trip to the Database menu. Tracks
  must be in the database to be rated. While it's the While Playing Screen
  the context menu has two items Rockbox doesn't, also in Theme Settings ›
  SlopBox, both on by default: Skip Rated Tracks (the player passes over
  rated tracks when it changes track, whether a track ends, Next is
  pressed or a hop runs past the end) and Next Track After Rating. On this
  layout a swipe across the track always changes track (to the next unrated one
  with Skip Rated on), while the buttons keep Rockbox's skip rules, so with
  Skip Length set they hop through the track. It replaces the Track Rater
  plugin of 1.1.0: its Play Playlist is the Playlist Catalogue or the file
  browser, and its Order is Shuffle or the database's views. Sorting the
  queue that's playing by artist, album, year or title went with it.
- **Look.** Theme Settings › SlopBox › Flat Style (off by default) drops
  the gloss from bars, buttons and icons. Text Size (Small, Normal, Large,
  Huge) scales the type and line heights; it's SlopBox's own setting, and
  Rockbox's Font setting is still unused.
- **Navigation at Bottom.** Theme Settings › SlopBox › Navigation at Bottom
  (off by default) moves each screen's title bar (back, title, buttons) to
  the bottom, above the mini-player, for one-handed use.
- **Playlist viewer editing.** Besides Rockbox's context menu (Remove,
  Move), a line can be swiped left to remove it, with Undo for a few
  seconds, and dragged by its ≡ handle to move it.
- **Rotation.** Rockbox's screens have one fixed size. SlopBox follows the
  phone's rotation; in landscape the While Playing Screen puts the art and
  track on the left and the bar and controls on the right.
- **File browser title.** With Show Path set to Full Path, each folder in
  the path can be tapped to go back up to it. With Current Directory Only,
  tapping the folder's name lists the folders above it to jump to.
- **Status Bar.** Rockbox's status bar shows battery, volume and play
  state icons. SlopBox has Android's for that, and uses the Status Bar
  setting to place a mini-player instead: a strip with a progress line,
  album art, title, artist and play/pause (hold: stop). Tapping it, or
  swiping from it into the screen, opens the While Playing Screen. It is
  hidden on screens that already show the track (While Playing Screen,
  album art, LRC Player). The default is Bottom (Rockbox's is
  Top), so a `.cfg` without a Status Bar line gives Bottom. The Files and
  Database title bars' ▶ button appears only with the Status Bar off.
- **Scroll bar.** Scroll Bar (Off, Left, Right) and Scroll Bar Width work
  on every list, with the width in dp (at least 4) rather than pixels. On
  a touchscreen the thumb can be dragged; a touch must start on the thumb,
  so taps elsewhere along the edge still reach the list. While dragging, a
  bubble shows the first letter of the top line in Files, the Database's
  value lists and the Playlist Catalogue (digits as #), and the line
  number elsewhere. Rockbox's scroll bar only shows position.
- **Main menu.** It has Rockbox's order and Settings > Main Menu
  customisation (a port of the main_menu_config plugin), but only the items
  SlopBox has: no Recording or FM Radio. SlopBox doesn't use the Rockbox name
  or logo for itself.
- **System menu.** Rockbox's Rockbox Info, Credits, Running Time and Legal
  Notices, plus About SlopBox; no Debug (Keep Out) menu, since its screens
  are about player hardware. Rockbox Info is "SlopBox Info": battery, the
  music folder, version (with the Rockbox revision), date, time and free
  space on internal storage and the SD card. It has no buffer line (SlopBox
  decodes as it plays rather than filling a big buffer) and no battery time
  estimate. Running Time counts while SlopBox's playback service is running,
  not while the phone is on; it resets when the charger is unplugged, as in
  Rockbox.
- **Quick Screen.** It shows Rockbox's four configurable items and uses
  Rockbox's touchscreen rule (a tap in the top, left, right or bottom third
  changes that item; anywhere else leaves), drawn as text rather than with
  Rockbox's arrows.
- **Setting context menu.** A long press on a setting gives Rockbox's menu.
  A long press on a submenu just opens it.
- **Shortcuts.** `shortcuts.txt` is kept in SlopBox's own storage, not
  `/.rockbox/`. Its format is Rockbox's. "debug" shortcuts and "reboot" only
  say they aren't available, "shutdown" closes SlopBox as Idle Poweroff does,
  "talk" time shortcuts do nothing (no voice), and the `icon:` number is kept
  but SlopBox picks the icon by type. A "browse" shortcut to a file opens its
  folder with the file selected; a "file" shortcut plays it.
