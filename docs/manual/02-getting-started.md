# 2. Getting started

## What you need

- An Android phone or tablet running Android 9 or later. The app is built
  for arm64 (nearly every phone) and x86_64.
- Music files on the phone's storage or SD card, in folders. SlopBox plays
  what it finds in the folder you give it; it doesn't use Android's media
  library.

## Installing

SlopBox isn't on Google Play. Download the APK from the
[Releases page](https://github.com/greenjon/slopbox/releases) (the file
called `slopbox-v….apk`), open it on the phone, and allow your browser or
file manager to install apps when Android asks.

**Updating:** install the new APK over the old one. Your settings,
playlists, bookmarks and database are kept. Every release is signed with
the same key, so Android accepts it as an update. A build you made yourself
is signed with a different key; Android won't install it over a release (or
the other way round) until the old one is uninstalled, and uninstalling
deletes SlopBox's data.

## First start

The first time SlopBox opens, the main menu is shown and nothing is playing.
Android 13 and later ask whether SlopBox may post notifications. Allow it if
you want the playback notification with its controls.

### Choosing your music folder

SlopBox can only see the folder you give it, because Android doesn't let
apps read your storage freely. Open **Files** from the main menu and tap
**Choose music folder**. Android's folder picker opens. Go to the folder
that holds your music, for example `Music` on the SD card, tap **Use this
folder**, then **Allow**.

That folder becomes the top of SlopBox's file browser, the place the
database scans, and the home of the `Playlists` folder (the Playlist
Catalogue) and the files plugins write, such as `.scrobbler.log` and
`database_changelog.txt`. Choose a folder that holds all your music, since
SlopBox can't see anything outside it.

To change it later: *Settings › General Settings › Database › Select
directories to scan*, or the menu button in the file browser's title bar ›
*Change Music Folder*.

### Playing something

Open **Files**, go into a folder and tap a track. SlopBox plays that folder
starting from the track you tapped, and opens the While Playing Screen. Like
Rockbox, playing a file replaces the current playlist with the files in its
folder.

### Building the database

To browse by artist, album, genre and so on, SlopBox needs to read your
tags. Open **Database** from the main menu and tap **Initialize Now**. The
first scan reads every file. It shows its progress, keeps going if you leave
the screen, and carries on where it stopped if SlopBox is closed. A library
of about 5,600 tracks on an SD card takes a few minutes. After that, use
*Update Now* (or turn on *Auto Update*) to pick up new files. See
[The database](06-database.md).

## Playing in the background

Music keeps playing when you leave the app or turn the screen off. Android
shows a notification with the track and its controls, and the same controls
appear on the lock screen. Headset buttons, Bluetooth controls and car
stereos work through Android's media controls. Playback pauses when
headphones are unplugged, and pauses or ducks for calls and other apps'
sounds.

To stop completely, pause and swipe the notification away, or hold the play
button on the While Playing Screen (stop).

## Opening files from other apps

File managers can open an audio file with SlopBox (*Open with › SlopBox*).
The track plays straight away. Android's permission for such a file lasts
only until it's closed, so these tracks may be skipped when SlopBox restarts
and resumes.
