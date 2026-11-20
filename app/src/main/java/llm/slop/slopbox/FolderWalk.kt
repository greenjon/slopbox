package llm.slop.slopbox

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import llm.slop.slopbox.ui.Doc
import llm.slop.slopbox.ui.MusicTree

/**
 * Rockbox "Auto-Change Directory" (apps/playlist.c get_next_dir()): find the
 * next folder, in file browser order, that has music in it -- or the previous
 * one, or a random one -- and its tracks.
 *
 * The walk is limited to the chosen music folder (Android only grants that
 * tree), which is what Rockbox's "Constrain Auto-Change" does with the start
 * directory; see docs/DIFFERENCES.md.
 */
object FolderWalk {
    suspend fun find(
        ctx: Context, tree: Uri, currentDir: String, direction: Int, random: Boolean,
    ): Pair<Doc, List<MediaItem>>? {
        // Every folder, depth first in browser order, with its own tracks.
        val folders = mutableListOf<Pair<Doc, List<Doc>>>()
        suspend fun walk(dir: Doc) {
            val children = MusicTree.list(ctx, tree, dir.id)
            folders += dir to children.filter { it.isTrack }
            for (c in children) if (c.isDir) walk(c)
        }
        walk(Doc(MusicTree.rootId(tree), "/", true))

        val withMusic = folders.filter { it.second.isNotEmpty() }
        if (withMusic.isEmpty()) return null
        val pick: Pair<Doc, List<Doc>> = if (random) {
            withMusic.filter { it.first.id != currentDir }.randomOrNull() ?: return null
        } else {
            val at = folders.indexOfFirst { it.first.id == currentDir }
            if (at < 0) return null
            // Search onwards (wrapping) for a folder that has tracks.
            val n = folders.size
            (1 until n).map { folders[((at + direction * it) % n + n) % n] }
                .firstOrNull { it.second.isNotEmpty() } ?: return null
        }
        val (dir, files) = pick
        return dir to files.map { MusicTree.item(tree, it, dir) }
    }
}
