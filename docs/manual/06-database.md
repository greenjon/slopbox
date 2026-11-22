# 6. The database

The database lets you browse your music by its tags instead of by folder:
by artist, album, genre, year and so on. Its menus are Rockbox's own,
read from Rockbox's `tagnavi.config`, and the tags are read with Rockbox's
metadata parser, so the menus match what a Rockbox player shows.

## Building it

The database starts empty. Open **Database** from the main menu and tap
**Initialize Now** (also in *Settings › General Settings › Database*).
SlopBox reads every audio file in your music folder. The scan:

- shows its progress at the top of the Database screen,
- keeps running if you leave the screen,
- saves as it goes, so if SlopBox is closed partway it carries on at the
  next start without reading the same files again,
- leaves the old database in use until it finishes.

Afterwards, **Update Now** reads only new and changed files and drops
missing ones. Turn on **Auto Update** to update the database each time
SlopBox starts. **Initialize Now** starts again from nothing and loses the
runtime data (below); Update Now keeps it.

## Browsing

The Database menu is Rockbox's:

- **Album Artist**, **Artist**, **Album**, **Genre**, **Year**,
  **Composer**: each opens a list of values, then albums, then tracks.
- **First Letter**: album artists, artists, albums or tracks by their first letter.
- **Tracks by**, **Search**: ask for a value (type it), then show the
  matches.
- **Shuffle Songs**: every track, shuffled.
- **User Rating**, **Recently Added**, **Playback History** (most played,
  recently played, never played, favourite artists and albums, forgotten
  favourites and more), **Same as currently played
  track**.

At the top of every list of values are **[All tracks]** (everything in the
list) and **[Random]** (a random value). Tap a track to play the list it's
in from there. Hold anything for its context menu, including *Playing
Next…* to add a whole artist or album to the current playlist, and *Show in
Files*.

Tags without a text encoding are read with *General Settings › Display ›
Default Codepage*.

## Runtime data and ratings

With **Gather Runtime Data** on (*General Settings › Database*), the
database keeps Rockbox's statistics for each track:

- **play count**: a play counts after 15 seconds,
- **rating**: 0 to 10,
- **play time**, **last played**,
- **autoscore**: how much of the track you usually listen to.

These fill the *Playback History* and *User Rating* menus. Set a track's
rating with **Set Song Rating** in its context menu (WPS or browser), or
quickly, a star at a time, with the [Track Rater](08-plugins.md#track-rater-track_rater)
plugin.

If a file moves or is renamed, *Update Now* finds it again by its file name
and tags and keeps its statistics, as Rockbox does.

## Export and Import Modifications

*Export Modifications* writes the runtime data to `database_changelog.txt`
at the top of your music folder, in Rockbox's format: one line per track
with its file name, rating, play count, last played and so on. *Import
Modifications* reads such a file back.

Use them to carry ratings and play counts between SlopBox and a Rockbox
player (the paths match when the music sits at the same path on both, such
as `/Music/...`), or for your own scripts to read. The Track Rater writes
this file after every rating.

## Your own menus

Rockbox's `tagnavi.config` includes a `tagnavi_custom.config` for menus of
your own (the **Custom menu** item). SlopBox looks for that file in its
private app storage, which release builds give no way to reach yet, so the
Custom menu is empty for now.
