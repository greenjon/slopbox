# 8. Plugins

Rockbox plugins are small programs that run on the player. SlopBox can't
run Rockbox's plugins, so the music-related ones are rewritten for SlopBox,
keeping their menus, settings and files. *Plugins* in the main menu lists
them under **apps**. Plugins that aren't about music (games, demos, image
viewers) aren't ported.

A plugin can also be opened with one press: set the *WPS Hotkey* (or a
context item) to *Open Plugin* in *General Settings › While Playing Screen*
and choose the plugin. Holding **C** on the playing screen's pop-up then
opens it.

## Alarm Clock (`alarmclock`)

Resumes paused music at a set time. Play the track you want to wake up to,
then open Alarm Clock: playback pauses. The screen works like Rockbox's
touchscreen keymap, in thirds:

- tap the **top** third to raise the selected number, the **bottom** third
  to lower it,
- tap the **left or right** third to switch between hours and minutes,
- tap the **middle** to set the alarm.

The time is on a 24-hour clock; a time already past today means tomorrow.
The screen then counts down and stays on while it waits, and the music
resumes at the set time. Leave the Alarm Clock screen open until then.

## Last.fm Logger (`lastfm_scrobbler`)

Turns SlopBox's playback log into a `.scrobbler.log` file, the format
scrobbling tools read to submit your listening history to Last.fm,
Libre.fm or ListenBrainz.

*Playback Settings › Logging* must be on to record what you play; the
plugin offers to turn it on. Its menu:

- **Remove duplicates**: Off; Resume Playback (back-to-back plays of a
  track count once, keeping the longest); All (each track once, keeping the
  longest play).
- **Delete playback log** after reading it (recommended, since exports are
  appended).
- **Threshold**: how much of a track must play for it to count as listened.
- **Minimum elapsed**: plays shorter than this aren't logged.
- **Track Info**: All, Skip if Missing (drop tracks without tags), or Track
  Name Only.
- **View log**, **Revert to Default**, **Cancel**, **Export**.

The `.scrobbler.log` file is written at the top of your music folder.
Copy it to a computer and upload it with your scrobbling tool.

## LRC Player (`lrcplayer`)

Shows synchronised lyrics for the playing track, highlighting each line (or
word) as it's sung. Open it from Plugins, or by tapping an `.lrc` file.

**Lyrics files:** `.lrc`, `.lrc8` (UTF-8), `.snc` and `.txt` files, and the
SYLT/USLT lyrics tags in MP3 files. For a track
`/Music/Artist/Album/Title.mp3`, it looks for `Title.lrc` (and the other
extensions) in the track's folder and its parent folders, in a `Lyrics`
subfolder of each, and then in the same places under the *Lrc Directory*,
all inside your music folder.

**Supported `.lrc` tags:** `[ti:title]`, `[ar:artist]`, `[offset:ms]`, line
time tags `[mm:ss]`, `[mm:ss.xx]` or `[mm:ss.xxx]` (several per line
allowed), and word time tags `<mm:ss.xx>`.

**Settings** (from its menu): *Theme Settings* (*Display Title*, *Display
Time*, *Backlight Always On*), *Display Settings* (*Wrap*, *Wipe*,
*Alignment*: Left, Centre, Right, *Activate Only Current Line*), *Lyrics
Settings* (*Encoding*, *Read ID3 tag*, *Lrc Directory*) and *Time Offset*
to shift the lyrics earlier or later.

## Random Folder Advance (`random_folder_advance_config`)

Sets up the list of folders that *Playback Settings › Auto-Change Directory
› Random* picks from, and can play your folders in random order:

- **Play Shuffled**: plays the listed folders in random order, each
  folder's tracks in their normal order.
- **Scan Folders**: makes the list from every folder in your music folder.
  To limit it, put a `folder_advance_dir.txt` at the top of your music
  folder listing the folders to scan, one per line; a line starting with
  `-` excludes a folder.
- **Edit**: the list. Tap a folder to remove it; hold it for *Remove
  Folder* or *Remove Folder Tree* (the folder and every folder inside it).
  Leaving asks whether to save.
- **Export to Text File**, **Import from Text File**:
  `folder_advance_list.txt` at the top of your music folder, to edit the
  list on a computer.
