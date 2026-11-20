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
- **What can be bookmarked.** Only folder playlists, as in Rockbox, but saved
  playlist files (`.m3u`) aren't supported yet, so they can't be bookmarked.
- **Shuffled bookmarks.** Rockbox stores its shuffle seed and rebuilds the
  same order. SlopBox stores the track's place in natural order; loading
  reshuffles with that track first.
- **Bookmark details.** The byte offset is written as 0, and SlopBox resumes
  by time. Pitch and speed are written as 100% and ignored when loading,
  because there's no pitch screen yet.
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
- **Not implemented yet.** Create Playlist (from a folder) in the catalog
  menu, Search in Playlist, Reset Playlist Directory, and the playlist
  viewer's per-track context menu. The viewer offers Remove and Move instead.
  Move is "tap a track, then tap where it goes" rather than Rockbox's
  move-cursor mode.
- **Playlist names.** You type a name in a text field rather than Rockbox's
  on-screen keyboard. The suggested name is the playlist file's name, the
  folder's name or "PlaylistN", following Rockbox's rules.
- **Playing time.** Durations come from each track's tags (read by Rockbox's
  metadata parser). Rockbox uses the lengths it already has in its playlist
  buffer.
- **Queued tracks in the viewer.** They aren't drawn with Rockbox's separate
  queued icon.

## Database and tags

- **Database.** The database is SlopBox's own (`ui/db`), not Rockbox's
  `tagcache`. Tags are read with Rockbox's metadata parser.
- **Track numbers.** Rockbox reports "no track number" as -1; the database
  stores 0.

## Interface

- **Theme engine.** There is none. SlopBox has a native cabbiev2-style
  interface instead of Rockbox's skins.
