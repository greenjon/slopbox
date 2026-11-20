/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/playlist.c - Copyright (C) 2002 by wavey@wavey.org
 * and the other Rockbox contributors (third_party/rockbox/docs/CREDITS).
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package llm.slop.slopbox

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import llm.slop.slopbox.ui.Doc
import llm.slop.slopbox.ui.MusicTree

/**
 * Rockbox "Auto-Change Directory" (apps/playlist.c get_next_dir()): find the
 * next folder, in file browser order, that has music in it -- or the previous
 * one, or a random one from the Random Folder Advance list -- and its tracks.
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
        // Rockbox: "Random" picks from folder_advance_list.dat (the Random
        // Folder Advance plugin's list); without one, or if nothing in it has
        // music, it goes on to the next folder as usual.
        val randomPick = if (random) {
            val root = MusicTree.rootId(tree)
            llm.slop.slopbox.plugins.RandomFolderAdvance.pick(ctx, tree,
                llm.slop.slopbox.PlaylistFiles.pathOf(root)) { id -> folders.any { it.first.id == id && it.second.isNotEmpty() } }
                ?.let { path -> folders.firstOrNull { it.first.id == llm.slop.slopbox.PlaylistFiles.docIdOf(tree, path) } }
        } else null
        val pick: Pair<Doc, List<Doc>> = if (randomPick != null) randomPick else {
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
