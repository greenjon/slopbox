# 5. Playlists and bookmarks

## The current playlist

Whatever SlopBox is playing is the *current playlist*, also called the
dynamic playlist. It's saved when SlopBox closes and restored when it
starts, so playback resumes where it was.

### Creating it

- **By playing a track** from the file browser or the database: SlopBox
  builds a playlist of everything listed there and starts at the track you
  tapped. This replaces the current playlist.
- **By playing a playlist file** (`.m3u`, `.m3u8`). Tapping one in the file
  browser or the Playlist Catalogue shows its tracks; tap *Play* at the top
  to play it from the start, or a track to play it from there.
- **With *Playing Next…*** (below).

If you've changed the current playlist (added, moved or removed tracks),
SlopBox asks before replacing it, unless *General Settings › Playlists ›
Current Playlist › Warn When Erasing Dynamic Playlist* is off.

### Choosing what's playing next

*Playing Next…* is in the context menu of tracks, folders and playlists
(hold one). It adds them to the current playlist:

- **Play Next:** right after the playing track.
- **Add:** after the tracks you added last or, if you haven't added any,
  right after the playing track.
- **Play Last:** at the end of the playlist.
- **Add Shuffled:** at random places.
- **Play Last Shuffled:** at the end, in random order.

Or replaces it:

- **Play:** the selection becomes the playlist. With *Keep Current Track
  When Replacing Playlist* on, the playing track finishes first.
- **Play Shuffled:** the same, in random order.

**Queued tracks** are added for one play and drop out of the playlist once
played; they're never saved to a playlist file. The *Queue…* items appear
when *Settings › General Settings › Playlists › Current Playlist › Show
Queue Options* is on, and the shuffled items when *Show Shuffled Adding
Options* is on.

On a folder, *Playing Next…* adds every track in it, and with
*Recursively Insert Directories* on, the tracks in its subfolders too.

## The playlist viewer

Open it by tapping the album art on the While Playing Screen, swiping up
there, or *Current Playlist › View Current Playlist* in the WPS context
menu. The playing track is highlighted.

- **Tap** a track to play it.
- **Swipe a track left** to remove it. *Undo* appears at the bottom for a
  few seconds.
- **Drag a track by its ≡ handle** (on the right) to move it.
- **Hold** a track for its menu: Playing Next, Add to Playlist, Remove,
  Move (tap the track, then tap where it goes), Show Track Info, Shuffle,
  Save, and Playlist Viewer Settings.

What each line shows follows *General Settings › Playlists › Playlist
Viewer Settings*: *Show Icons*, *Show Indices* (the numbers) and *Track
Display*. Track Display is *Track Name Only* by default, which is the file
name without its extension. Choose *Title from ID3 Tags* to see the tags'
titles instead.

The WPS context menu's *Current Playlist* submenu also has *Reshuffle*,
*Save Current Playlist*, *Search in Playlist* and *Playing Time* (the total
length and the time left).

## Saved playlists

### The Playlist Catalogue

*Playlists* in the main menu lists the playlist files in the `Playlists`
folder inside your music folder, with any folders inside it first, so you
can group playlists in subfolders (make them in the file browser). Tap a
folder to open it and Back to leave it. Tap a playlist to see its tracks:
*Play* at the top plays it from the start, and tapping a track plays it from
there. This is the same view you get by tapping a playlist file in the file
browser. Hold a playlist for its context menu
(*Playing Next…*, *View*, *Rename*, *Delete* and the rest). The menu
button in the catalogue's title bar has *Create Playlist* (every track in
the music folder), *Save Current Playlist* and *View Current Playlist*.

Saving a playlist needs permission to write to your music folder. If you
chose the folder in an old version of SlopBox and saving fails, choose it
again in *General Settings › Database › Select directories to scan*.

### Saving the current playlist

*Current Playlist › Save Current Playlist* in the WPS context menu (or the
catalogue's menu) asks for a name and saves the playlist into the catalogue
as an `.m3u8` file. Queued tracks aren't saved.

### Adding to a saved playlist

*Add to Playlist…* in a track's or folder's context menu adds it to a
playlist in the catalogue (including its subfolders), or to a new one,
without touching what's playing.

### Playlist files

SlopBox reads `.m3u` and `.m3u8` files as Rockbox does: one track per line,
lines starting with `#` ignored, paths relative to the playlist's folder or
absolute storage paths such as `/Music/Artist/track.flac`. Windows-style
paths with backslashes and drive letters work. `.m3u8` files (and files
starting with a UTF-8 marker) are read as UTF-8, others as ISO-8859-1. Saved
playlists use absolute paths, so Rockbox players with the music at the same
path can read them.

## Bookmarks

A bookmark remembers a playlist, a track and a position, so you can come
back to an audiobook or a long mix later.

- **Create one:** *Bookmarks › Create Bookmark* in the WPS context menu.
  Folder playlists and playlist files can be bookmarked. A playlist built
  by adding tracks from different folders can't.
- **Automatically:** *General Settings › Bookmarking › Bookmark on Stop*
  creates (or asks to create) a bookmark when you stop. *Update on Stop*
  updates the one you loaded.
- **Use one:** *Recent Bookmarks* in the main menu (with *Maintain a List of
  Recent Bookmarks?* on), *Bookmarks* in a folder's or playlist's context
  menu, or tapping a `.bmark` file. Tap a bookmark to resume it and hold it
  to delete it.
- *Load Last Bookmark* asks whether to resume the last bookmark when you
  play a folder or playlist that has one. Choosing *Play* in a playlist's
  track list checks for a bookmark; tapping a track plays that track.

SlopBox keeps its bookmarks in its own storage, in Rockbox's `.bmark`
format, rather than beside the music.

## Automatic Resume

*General Settings › Automatic Resume* remembers where you stopped in each
file (for example audiobooks and podcasts) and starts there next time.
*Resume on automatic track change* chooses whether that also happens when
playback moves to a track on its own.
