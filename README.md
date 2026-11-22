# SlopBox

An Android music player with Rockbox's "more is more" approach: every
setting exposed, nothing hidden for your own good.

**SlopBox is a derivative work (a fork) of [Rockbox](https://www.rockbox.org/),
the open source firmware for digital audio players. It is not made, endorsed
or supported by the Rockbox project; please report SlopBox problems here, not
to them.** It is maintained by [greenjon](https://github.com/greenjon).

SlopBox is a Kotlin app built on Rockbox's own codec and DSP library
(`lib/rbcodec`), compiled with the Android NDK. The Rockbox source is a git
submodule in `third_party/rockbox` and is used unmodified, with all its
copyright files and notices. SlopBox source files that port or derive from
Rockbox code name the Rockbox files and their copyright holders in their
headers; Rockbox's full contributor list is `third_party/rockbox/docs/CREDITS`
and is shown in the app (System › Credits). The interface follows
Rockbox's cabbiev2 theme by Johannes Voggenthaler, Apoo Maha, Marc Guay, Alex
Vanderpol, Jerry Lange, Keith Perri, Mark Fawcus and Marianne Arnold (based on
Cabbie by Yohann Misquitta).

Much of it is written with AI help, hence the name.

## Status

Version 1.1.0. Download the APK from
[Releases](https://github.com/greenjon/slopbox/releases) (Android 9 or later,
arm64 or x86_64).

What's in it: Rockbox's codecs and DSP, gapless playback and crossfade;
Rockbox's whole Settings menu, saved as `config.cfg`; a file browser, the
database (Rockbox's `tagnavi.config` menus, with runtime data and ratings),
playlists, bookmarks, cuesheets, A-B repeat, the sleep timer, the Quick
Screen, Shortcuts, a customisable main menu and the System menu; and the
music plugins Random Folder Advance, LRC Player, Last.fm Logger and Alarm
Clock. The interface is built for touch: a
mini-player on every list, gestures on the playing screen, a draggable scroll
bar, five playing-screen layouts (one of them, Rater, for rating tracks a tap at a time), and Rockbox's theme colours with a colour
picker and bundled themes. Playback runs in a Media3 session service, so it keeps playing with
the screen off, shows a media notification, answers
headset/Bluetooth/lock-screen buttons, handles audio focus and pauses when
headphones are unplugged.

Tested on a Unihertz Jelly Star (Android 13, arm64) with a library of about
5,600 tracks on an SD card. Plans for 1.2 (voice, languages, more music
plugins, faster scans) are in [docs/ROADMAP.md](docs/ROADMAP.md).

The [SlopBox Manual](docs/manual/README.md) explains how to use it.
Known differences from Rockbox are listed in [docs/DIFFERENCES.md](docs/DIFFERENCES.md).

## Settings, straight from Rockbox

SlopBox's Settings menu is Rockbox's. `settings-gen/` compiles Rockbox's own
`apps/settings_list.c`, the Settings menus in `apps/menus/` and the value
formatter (`option_get_valuestring()`) for Rockbox's hosted app target, walks
the real tables and writes `app/src/main/assets/rockbox_settings.json`: every
menu, setting, default, range, option and the exact text Rockbox shows for
each value. Regenerate it after updating the submodule:

```sh
settings-gen/gen_settings.sh
```

Settings are stored as a Rockbox-format `config.cfg`, and Manage Settings can
save and load `.cfg` files that Rockbox itself reads. Sound settings reach the
DSP the way Rockbox's `settings_apply()` does; bass, treble, balance, channels
and stereo width go through Rockbox's unmodified `firmware/sound.c`. Settings
SlopBox doesn't act on yet are shown dimmed and still saved. Playback
Settings follow Rockbox's rules too: shuffle and Play Selected First reorder
the playlist like `playlist_randomise()`, Repeat (All/One/Shuffle) steers
gapless playback, Next/Previous go through `play_hop()` (Skip Length, Skip to
Outro, Prevent Track Skipping, Rewind Across Tracks), and pausing honours
Rewind on Pause and Fade on Stop/Pause. Crossfade works like Rockbox's
`pcmbuf.c`: decoded audio queues in a PCM buffer (`engine/sb_pcmbuf.c`) that,
with crossfade on, runs the fade-out delay + duration + 1 s ahead; at a track
change the buffered tail is faded out and the new track mixed in, with all six
modes, both delays and durations, and Mix mode. Also in step with Rockbox:
Single Mode, Party Mode, A-B repeat (with A and B buttons on the playing
screen), Track Skip Beep, Auto-Change Directory, Logging, Album
Art, FF/RW acceleration, playlist resume with Rewind Before Resume and Start
Screen, Automatic Resume, cuesheets (embedded, chapters and .cue files),
bookmarks, the sleep timer and Idle Poweroff. Playlists work like Rockbox's
`playlist.c` and `playlist_catalog.c`: the Playing Next menu (Play Next, Add,
Play Last, the shuffled and queue variants, per the Current Playlist
settings), queued tracks that drop out once played, `.m3u`/`.m3u8` files
(relative paths and Windows-style paths included), the Playlist Catalog in
`Playlists/`, Save Current Playlist, Add to Playlist, Create Playlist, Search
in Playlist, and the playlist viewer. The context menus are `onplay.c`'s: the
playing screen's menu with its configurable items (While Playing Screen
settings), and the file browser's Rename, Cut, Copy, Paste, Delete, Create
Directory, Properties, Show Track Info and Set As. The pitch screen is a port
of Rockbox's pitch screen plugin, with rate, semitone and timestretch modes,
and its arithmetic is checked against Rockbox's C in the unit tests. With
Gather Runtime Data on, the database keeps Rockbox's play counts, ratings,
play time, last played and autoscore, which fill the Playback History and
User Rating menus of Rockbox's `tagnavi.config`; Export and Import
Modifications use Rockbox's `database_changelog.txt` format. The Shortcuts
menu reads and writes Rockbox's `shortcuts.txt` format (browse, file,
playlist menu, setting, apply, time, separator), fed by "Add to Shortcuts" in
the file browser and a setting's long-press menu, which also sets the four
Quick Screen items. Plugins are ported one at a time in Kotlin: so far
`random_folder_advance_config` (with Rockbox's `folder_advance_list.dat`)
`lrcplayer` (.lrc/.lrc8/.snc/.txt and ID3 SYLT/USLT lyrics, word wipe) and
`lastfm_scrobbler` (playback log to an Audioscrobbler `.scrobbler.log`) and
`alarmclock`. Rating tracks is the Rater playing-screen layout (`slop-rater`:
one tap per star, then the next unrated track; the ratings go to
`database_changelog.txt` on the card).

## Layout

| Path | What |
|---|---|
| `third_party/rockbox` | Rockbox, unmodified (git submodule) |
| `engine/` | Playback engine (derived from Rockbox's `warble` test player), PCM buffer with crossfade, settings bridge |
| `settings-gen/` | Extracts Rockbox's settings and menus into JSON |
| `host/` | Linux test driver (`sb_cli`): decode to WAV with any DSP setting |
| `android-native/build_rockbox.sh` | Builds Rockbox's codecs/libraries with the NDK through Rockbox's own `configure` |
| `app/` | The Android app (Kotlin + Compose, JNI, AAudio output) |

## Building

```sh
git submodule update --init --depth 1
make -C host rockbox && make -C host     # optional: host test driver
android-native/build_rockbox.sh          # Rockbox for arm64-v8a + x86_64
./gradlew assembleDebug                  # needs JDK 17 and NDK 29.0.14206865
```

Releases are built and signed locally with `scripts/release.sh 1.0.0`,
which needs the release key in `key.properties` (not in the repository).
GitHub Actions builds the codecs, runs the tests and builds a debug APK on
every push.

Codecs ship in the APK as `lib/<abi>/libsb_<codec>.so` and are loaded with
`dlopen`, the same way Rockbox's hosted builds load `.codec` files.

### Known gaps

- `sid` (cRSID) uses GCC nested functions, which the NDK's clang rejects.
- `ape` on x86_64 only: its MMX inline assembly doesn't build with clang
  (phones are arm64, which is fine).

## License

GPL-2.0-or-later, the same as Rockbox. SlopBox is not and will never be
sold.

**To the Rockbox project:** if you ever want to adopt, fork, rename or merge
any of this as Rockbox, you are more than welcome and have my full support.
The app's name lives in one place, so a fork can rename it quickly.

Contributions are accepted under GPL-2.0-or-later with a `Signed-off-by` line
([Developer Certificate of Origin](https://developercertificate.org/)). There
is no contributor license agreement (CLA).
