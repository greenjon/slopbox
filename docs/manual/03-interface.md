# 3. The interface

Rockbox players are driven with buttons: up, down, select, back, menu.
SlopBox keeps Rockbox's screens and menus but drives them by touch.

## Touch basics

| Rockbox does this with a button | In SlopBox |
|---|---|
| Move the cursor and select | Tap the line |
| Open the context menu (*Context* button) | Hold the line |
| Go back | Android's Back gesture or button, or the ‹ in the title bar |
| Change a value | Tap the setting, then tap the new value |
| Repeat a step (+/−) | Hold the button; it repeats |
| Jump to the playing screen (*WPS* button) | The mini-player at the bottom of the screen |

The line you last chose stays highlighted with the gold bar (the *line
selector*). When *Haptic Feedback* is on (*Settings › Theme Settings ›
SlopBox*), the phone gives a short buzz on long-presses, gestures and each
step of a held button.

## The main menu

SlopBox opens on Rockbox's main menu:

- **Recent Bookmarks**: shown only when there are some (see
  [Bookmarks](05-playlists-and-bookmarks.md#bookmarks)).
- **Files**: the file browser.
- **Database**: browse by tags (see [The database](06-database.md)).
- **Now Playing** (or **Resume Playback** when nothing is loaded): the While
  Playing Screen. While something is loaded, the line also shows its title.
- **Settings**: Rockbox's settings menu (see [Settings](07-settings.md)).
- **Playlists**: the Playlist Catalogue.
- **Plugins** (see [Plugins](08-plugins.md)).
- **System**: SlopBox Info (version, music folder, storage and battery),
  Credits, Running Time, Legal Notices and About SlopBox.
- **Shortcuts** (see [Shortcuts](#shortcuts)).

The order of these, and which are shown, can be changed in *Settings › Main
Menu*: tap an item to turn it on or off, hold it to move it up or down.
*Settings* is always shown.

## Lists

Every menu and browser is a list.

- **Scroll bar.** On a long list a thin bar on the right shows where you
  are. Drag its thumb to move quickly through the list. While you drag, a
  bubble shows the first letter of the top line in Files, the Database and
  the Playlist Catalogue, and the line number elsewhere. *Settings › Theme
  Settings › Status-/Scrollbar › Scroll Bar* puts it on the left, on the
  right or turns it off; *Scroll Bar Width* sets how thick it is.
- **Icons.** *Theme Settings › Show Icons* turns the icons in front of each
  line on and off.
- **Dimmed lines** in the Settings menu are settings SlopBox doesn't act on
  yet. You can still change them and they're saved.

## The mini-player

While something is loaded, a strip at the bottom of the list screens shows
the track's art, title and artist, a thin progress line and a play/pause
button. Tap the strip, or swipe up from it, to open the While Playing
Screen. Tap play/pause to pause or play, and hold it to stop.

Rockbox's *Status Bar* setting (*Theme Settings › Status-/Scrollbar*)
places it: **Bottom** (the default), **Top**, or **Off**. With it off, the
Files and Database title bars get a ▶ button that opens the playing screen.

It's hidden on screens that already show the track: the While Playing
Screen, the album art viewer, the LRC Player and the Track Rater.

## The title bar

Each screen has a title bar with ‹ (back), the title, and sometimes buttons
on the right. *Theme Settings › SlopBox › Navigation at Bottom* moves it to
the bottom of the screen, just above the mini-player, so it can be reached
with one thumb.

## The file browser (Files)

**Files** shows your music folder as Rockbox's file browser does. Tap a
folder to open it and tap a track to play it. Playing a track plays its
folder from that track, replacing the current playlist (Rockbox asks first
if you've changed the current playlist; see *Warn When Erasing Dynamic
Playlist*).

What happens when you tap other files:

| File | What it does |
|---|---|
| Audio | Plays its folder from there |
| `.m3u`, `.m3u8` | Shows the playlist's tracks: *Play* plays it from the top, a track plays it from there |
| `.cue` | Shows the cuesheet's tracks |
| `.cfg` | Loads the settings in it |
| `.bmark` | Lists its bookmarks |
| `.lrc`, `.lrc8`, `.snc` | Opens the LRC Player |
| Anything else | Nothing (Rockbox's image and text viewers aren't ported) |

**The title** shows where you are, following *General Settings › File View
› Show Path*:

- **Current Directory Only** (the default): the folder's name with a ▾. Tap
  it for a list of the folders above, nearest first, and tap one to jump
  there.
- **Full Path**: every folder from the top down. Tap any of them to go
  back up to it.
- **Off**: just "Files".

**The menu button** in the title bar is SlopBox's own folder menu: Playing
Next on the whole folder, Paste, Create Directory, and Change Music Folder.

The other *File View* settings work as in Rockbox: sorting (case, folders,
files, numbers), *Show Files* (Music, Supported, All…), *Show Filename
Extensions*, *Follow Playlist* (the browser opens at the playing track) and
*Always remember last folder*.

## Context menus

Hold any track, folder or playlist to open its context menu, Rockbox's
`onplay` menu:

- **Playing Next…** (see [Playlists](05-playlists-and-bookmarks.md))
- **Add to Playlist…**
- **Bookmarks** (for a folder or playlist)
- **Rename, Cut, Copy, Paste, Delete, Create Directory** (file operations
  in your music folder)
- **Properties**, **Show Track Info**
- **Open With…** hands the file to another Android app.
- **Set As…** Start Directory or Playlist Directory
- **Add to Shortcuts**

Not every item appears for every kind of file, as in Rockbox.

Holding a **setting** in the Settings menu gives Rockbox's setting menu:
*Reset Setting*, put it on the Quick Screen (top, left, bottom or right),
*Add Current to Shortcuts* (a shortcut that sets this value) and *Add to
Shortcuts* (a shortcut to the setting).

## Quick Screen

The Quick Screen gives four settings at a touch: by default *Shuffle* on the
left and *Repeat* on the right. Open it from the While Playing Screen's
pop-up (the **Q** button). As with Rockbox's touchscreen Quick Screen, tap
the top, left, right or bottom third of the screen to step that setting
(top and right go to the next value, left and bottom to the previous). Tap
the middle to leave.

Any setting that can go on the Quick Screen can be put there by holding it
in the Settings menu. With *General Settings › System › Use Shortcuts Menu
Instead of Quick Screen* on, the **Q** button opens Shortcuts instead.

## Shortcuts

*Shortcuts* (main menu) is Rockbox's shortcuts menu, saved in Rockbox's
`shortcuts.txt` format. Add files and folders with *Add to Shortcuts* in
their context menu, and settings with a long-press in the Settings menu.
Tap a shortcut to use it; hold it to delete it.
