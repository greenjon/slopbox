# Rating tracks in SlopBox

Status: plan, nothing built. Written 2026-11-20.

## Goal

Fold the Track Rater app (`~/AndroidStudioProjects/track-rater`) into SlopBox without pulling SlopBox away from
Rockbox. Updating the `third_party/rockbox` submodule should stay what it is now: regenerate the settings JSON, review
the diff.

Track Rater is a one-screen app for a listening purge. It plays the card's music, you give each track 1, 3 or 5 stars
(remove, revisit, keep), and the laptop's `~/projects/music-catalog/catalog.py` merges those ratings with Mixxx's and
later deletes the 1-star files. It reads a `queue.json` the laptop writes to the card and appends to
`catalog/ratings.tsv` on the card.

## Most of it is already Rockbox

| Track Rater feature | Rockbox equivalent | Work in SlopBox |
|---|---|---|
| Star ratings | The database keeps a `rating` (0–10) per track. With **Gather Runtime Data** on, the While Playing Screen context menu has **Set Song Rating** (`apps/onplay.c`, `set_rating_inline`) | Core feature, upstream behaviour. Stars map to rating × 2: 1★ = 2, 3★ = 6, 5★ = 10 |
| Ratings file on the card | **Export Modifications** writes `database_changelog.txt`: a `## Changelog version 1` line, then one line per track of `tag="value"` pairs (`filename`, `rating`, `playcount`, `lastplayed`, …). **Import Modifications** reads it back (`tagcache_create_changelog`, `parse_changelog_line` in `apps/tagcache.c`) | Core feature. Replaces `ratings.tsv` as the file the laptop reads |
| Listening passes | `catalog.py update` already writes ranked passes as `.m3u8` playlists, which Rockbox plays from the Playlist Catalogue | None |
| Unrated only, keep and revisit lists | `tagnavi` queries can filter on `rating` and `playcount`. `ui/db/TagNavi.kt` already parses the `rating` tag; `ui/db/Query.kt` returns 0 for runtime stats for now | Feed real runtime stats to `Query.kt`; ship a user `tagnavi` file with Unrated / 1★ / Revisit / Keep views |
| −14 s / +14 s and headset jumps | **Skip Length** (already implemented through `play_hop()`): next and previous jump inside the track | None. Use 15 s for rating sessions (32 beats at 140 BPM is 13.7 s) |
| One-tap rating screen | Nothing. Rockbox's Set Song Rating is a 0–10 number picker | The only genuinely new piece: a plugin (below) |

## The new piece: a rater plugin

Rockbox puts features that are not part of the player under **Plugins** in the main menu. SlopBox does the same:

- A `plugins/rater` package the core never imports, opened from a Plugins entry in the main menu.
- It also opens from the While Playing Screen the Rockbox way: the **Hotkey** setting has an **Open Plugin** choice
  (`HOTKEY_PLUGIN`, `hotkey_wps_run_plugin` in `apps/onplay.c`), and touchscreen skins can bind a region to
  `hotkey`. No new WPS button is needed.
- The screen: big **1 remove / 3 revisit / 5 keep** buttons, Undo, a pass picker and sort order, and moving to the
  next track after each rating.
- It reaches the player only through a narrow plugin API, in the spirit of Rockbox's `plugin_api`: current track,
  play a playlist, seek, next, set rating. Set rating is the same call the core Set Song Rating uses, so plugin
  ratings land in the same database and the same export.
- Its own options (last pass, sort order) live in its own config file, like Rockbox plugins' `.cfg` files. Never in
  `config.cfg` and never in `rockbox_settings.json`.

## Rules

1. Nothing in `third_party/rockbox` changes, and nothing is added to the generated settings or menus.
2. Core gets only features Rockbox has: runtime data, Set Song Rating, Export/Import Modifications, rating in
   `tagnavi`. Same names, same behaviour, so a Rockbox user recognises them.
3. Anything about the purge itself (passes, 1 = remove, the laptop scripts) lives in the plugin or in
   `music-catalog`.
4. The card is a one-way handoff: the laptop writes playlists, SlopBox writes the export. SlopBox never deletes
   files; deleting stays with `catalog.py reject`.

## Order

1. **Runtime data and Set Song Rating in core.** Persist runtime stats next to `tagdb.bin` in `filesDir`, honour
   the Gather Runtime Data setting, add Set Song Rating to the While Playing Screen context menu. Rating already
   works from here, just slowly.
2. **Export Modifications** to `database_changelog.txt` on the card, plus **Import Modifications**. In
   `music-catalog`, `catalog.py ratings` reads the changelog next to Mixxx and `ratings.tsv` (rating ÷ 2, rounded up,
   gives stars). That change belongs to the catalog repo, not here.
3. **Real runtime stats in `Query.kt`** and the user `tagnavi` views.
4. **The rater plugin.** Then retire the standalone Track Rater app.

Steps 1–3 are pure Rockbox. If Set Song Rating plus Skip Length turns out fast enough in a real listening session,
step 4 may not be needed.

## Open questions

- Where on the card the export goes. Rockbox writes it next to its database; SlopBox keeps its database in
  `filesDir`, which the laptop can't see. Probably a fixed card path such as `<card>/catalog/`, chosen once.
- How the changelog's `filename` (an absolute path) is matched on the laptop. `catalog.py` already matches by the
  path below `140/`, which should work unchanged.
- Whether the Mixxx write-back in `catalog.py` should also write the merged ratings into a changelog for SlopBox to
  import, so all three places agree. Rockbox's import skips tracks already changed on the device, which is the
  right conflict rule for that direction.
