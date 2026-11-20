/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/bookmark.c - Copyright (C) 2003 by Benjamin Metzler
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
import java.io.File

/**
 * Rockbox bookmarks (apps/bookmark.c): one bookmark file per playlist, newest
 * first, plus the most-recent list (at most 10). Lines use Rockbox's format:
 *
 *   >flags;index;offset;seed;elapsed;repeat;shuffle;pitch;speed;/Music/Album/;01 Track.flac
 *
 * Files live in the app's storage (filesDir/bookmarks/), not beside the music
 * as Rockbox's "<folder>.bmark" -- Android grants SlopBox read-only access to
 * the music folder. See docs/DIFFERENCES.md.
 */
class Bookmarks(context: Context) {
    data class Bookmark(
        val index: Int,
        val elapsedMs: Long,
        val seed: Int,
        val repeat: Int,
        val shuffle: Boolean,
        /** Rockbox's playlist name: a folder playlist is its path with a trailing "/". */
        val playlist: String,
        val file: String,
        val line: String,
        /** Rockbox's optional pitch and speed (timestretch) tokens, 10000 = 100%. */
        val pitch: Int? = null,
        val speed: Int? = null,
    ) {
        /** Rockbox get_bookmark_info(): "track name" and "time, number[ (Shuffle)]". */
        val title: String get() = file.substringBeforeLast('.')
    }

    private val dir = File(context.filesDir, "bookmarks").apply { mkdirs() }
    private val recentFile = File(context.filesDir, "most-recent.bmark")

    /** Rockbox generate_bookmark_file_name(), mapped into our folder. */
    fun fileFor(playlist: String): File {
        val name = if (playlist == "/") "root_dir" else playlist.trimEnd('/').replace("/", "%2F")
        return File(dir, "$name.bmark")
    }

    fun exists(playlist: String) = fileFor(playlist).exists()

    fun list(playlist: String): List<Bookmark> = read(fileFor(playlist))

    fun recent(): List<Bookmark> = read(recentFile)

    fun hasRecent() = recentFile.exists() && recent().isNotEmpty()

    private fun read(f: File): List<Bookmark> =
        if (!f.exists()) emptyList() else f.readLines().mapNotNull { parse(it) }

    /** Rockbox write_bookmark(): to the playlist's file and/or the recent list. */
    fun write(b: String, toFile: Boolean, toRecent: Boolean, recentMode: Int): Boolean {
        val bm = parse(b) ?: return false
        if (toRecent && recentMode != 0) add(recentFile, bm, recent = true, recentMode = recentMode)
        if (toFile) add(fileFor(bm.playlist), bm, recent = false, recentMode = 0)
        return true
    }

    /** Rockbox add_bookmark(): newest first; the recent list keeps 10 and, per
     *  "Maintain a List of Recent Bookmarks", one per playlist or per track. */
    private fun add(f: File, bm: Bookmark, recent: Boolean, recentMode: Int) {
        val old = read(f).filter { o ->
            when {
                !recent -> true
                recentMode == RECENT_ONE_PER_PLAYLIST -> o.playlist != bm.playlist
                recentMode == RECENT_ONE_PER_TRACK -> !(o.playlist == bm.playlist && o.file == bm.file)
                else -> true
            }
        }
        val lines = (listOf(bm) + old).let { if (recent) it.take(MAX_BOOKMARKS) else it }.map { it.line }
        val tmp = File(f.path + ".tmp")
        tmp.writeText(lines.joinToString("\n", postfix = "\n"))
        tmp.renameTo(f)
    }

    /** Rockbox delete_bookmark(). */
    fun delete(playlist: String?, bm: Bookmark) {
        val f = if (playlist == null) recentFile else fileFor(playlist)
        val left = read(f).filter { it.line != bm.line }
        if (left.isEmpty()) f.delete() else f.writeText(left.joinToString("\n", postfix = "\n") { it.line })
    }

    companion object {
        const val MAX_BOOKMARKS = 10
        const val RECENT_ONE_PER_PLAYLIST = 2
        const val RECENT_ONE_PER_TRACK = 3
        private const val BM_PITCH = 1
        private const val BM_SPEED = 2
        /** Rockbox PITCH_SPEED_100 (100%). */
        const val SPEED_100 = 10_000L

        /** Rockbox create_bookmark(). Offset is 0: SlopBox resumes by time. */
        fun format(index: Int, elapsedMs: Long, seed: Int, repeat: Int, shuffle: Boolean,
                   pitch: Long, speed: Long, playlist: String, file: String): String =
            ">${BM_PITCH or BM_SPEED};$index;0;$seed;$elapsedMs;$repeat;${if (shuffle) 1 else 0};" +
                "$pitch;$speed;$playlist;$file"

        /** Rockbox parse_bookmark() (new ">" format and the old one). */
        fun parse(line: String): Bookmark? {
            var s = line.trim()
            if (s.isEmpty()) return null
            var flags = 0
            val newFormat = s.startsWith(">")
            val tokens = s.removePrefix(">").split(';')
            var i = 0
            fun next() = tokens.getOrNull(i++)
            if (newFormat) flags = next()?.toIntOrNull() ?: return null
            val index = next()?.toIntOrNull() ?: return null
            next() // offset
            val seed = next()?.toIntOrNull() ?: 0
            if (!newFormat) next() // deprecated first_index
            val elapsed = next()?.toLongOrNull() ?: return null
            val repeat = next()?.toIntOrNull() ?: 0
            val shuffle = (next()?.toIntOrNull() ?: 0) != 0
            val pitch = if (flags and BM_PITCH != 0) next()?.toIntOrNull() else null
            val speed = if (flags and BM_SPEED != 0) next()?.toIntOrNull() else null
            val rest = tokens.drop(i)
            if (rest.size < 2) return null
            val playlist = rest.dropLast(1).joinToString(";")
            val file = rest.last().substringAfterLast('/')
            return Bookmark(index, elapsed, seed, repeat, shuffle, playlist, file, line.trim(), pitch, speed)
        }

        /** "/Music/Album/" for a folder document id like "primary:Music/Album". */
        fun playlistName(dirDocId: String): String {
            val path = dirDocId.substringAfter(':').trim('/')
            return if (path.isEmpty()) "/" else "/$path/"
        }

        /** The folder document id back from a playlist name, on the tree's volume. */
        fun dirDocId(tree: Uri, playlist: String): String {
            val volume = android.provider.DocumentsContract.getTreeDocumentId(tree).substringBefore(':')
            return "$volume:" + playlist.trim('/')
        }
    }
}
