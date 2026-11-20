/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/filetree.c - Copyright (C) 2005 by Björn Stenberg
 *   apps/tree.c - Copyright (C) 2002 Daniel Stenberg
 *   apps/filetypes.c - Copyright (C) 2007 Jonathan Gordon
 *   firmware/common/strnatcmp.c - Copyright (C) 2000, 2004 by Martin Pool
 * and the other Rockbox contributors (third_party/rockbox/docs/CREDITS).
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * FileView.strnatcmp() is an altered Kotlin port of strnatcmp.c (as changed
 * for Rockbox), whose notice follows:
 *
 *   strnatcmp.c -- Perform 'natural order' comparisons of strings in C.
 *   Copyright (C) 2000, 2004 by Martin Pool <mbp sourcefrog net>
 *
 *   This software is provided 'as-is', without any express or implied
 *   warranty.  In no event will the authors be held liable for any damages
 *   arising from the use of this software.
 *
 *   Permission is granted to anyone to use this software for any purpose,
 *   including commercial applications, and to alter it and redistribute it
 *   freely, subject to the following restrictions:
 *
 *   1. The origin of this software must not be misrepresented; you must not
 *      claim that you wrote the original software. If you use this software
 *      in a product, an acknowledgment in the product documentation would be
 *      appreciated but is not required.
 *   2. Altered source versions must be plainly marked as such, and must not be
 *      misrepresented as being the original software.
 *   3. This notice may not be removed or altered from any source distribution.
 */
package llm.slop.slopbox.ui

import llm.slop.slopbox.SettingsStore

/**
 * Rockbox's General Settings › File View rules for the file browser:
 * sorting (ft_load() / compare()), Show Files (the dirfilter), Show Filename
 * Extensions (ext_stripit()) and Show Path (the browser title).
 */
object FileView {
    /** Rockbox's inbuilt file types besides audio and playlists (filetypes.c), plus viewers SlopBox has. */
    private val SUPPORTED_OTHER = setOf("cfg", "cue", "bmark", "lrc", "lrc8", "snc")

    /** filetype_supported(): a type Rockbox knows what to do with. */
    fun supported(d: Doc): Boolean = d.isDir || d.isTrack || d.isPlaylist ||
        d.name.substringAfterLast('.', "").lowercase() in SUPPORTED_OTHER

    /** Show Files: 0 All, 1 Supported, 2 Music, 3 Playlists. */
    fun visible(d: Doc, showFiles: Int): Boolean {
        if (d.isDir) return true
        return when (showFiles) {
            0 -> true
            2 -> d.isTrack || d.isPlaylist
            3 -> d.isPlaylist
            else -> supported(d)
        }
    }

    /** ext_stripit(): whether the extension is hidden. */
    fun stripExt(d: Doc, store: SettingsStore): Boolean {
        if (d.isDir) return false
        return when (store.int("show filename exts")) {
            0 -> true
            1 -> false
            2 -> supported(d)
            else -> store.int("show files") != 0
        }
    }

    fun displayName(d: Doc, store: SettingsStore): String =
        if (stripExt(d, store) && d.name.contains('.')) d.name.substringBeforeLast('.') else d.name

    /**
     * compare(): folders first; folders by "Sort Directories", files by "Sort
     * Files" (alphabetical, by date, by newest date, by type), names compared
     * per "Sort Case Sensitive" and "Interpret numbers when sorting".
     */
    fun comparator(store: SettingsStore, sortFilesOverride: Int? = null): Comparator<Doc> {
        val caseSensitive = store.int("sort case") != 0
        val natural = store.int("sort interpret number") == 1
        val sortDir = store.int("sort dirs")
        val sortFile = sortFilesOverride ?: store.int("sort files")
        val names = Comparator<String> { a, b ->
            if (natural) strnatcmp(a, b, !caseSensitive)
            else if (caseSensitive) a.compareTo(b) else a.compareTo(b, ignoreCase = true)
        }
        return Comparator { a, b ->
            if (a.isDir != b.isDir) return@Comparator if (a.isDir) -1 else 1
            val criteria = if (a.isDir) sortDir else sortFile
            when (criteria) {
                3 -> {   // SORT_TYPE: by type, unknown types last, then alphabetical
                    val t = typeRank(a).compareTo(typeRank(b))
                    if (t != 0) t else names.compare(a.name, b.name)
                }
                1, 2 -> {
                    val c = a.modified.compareTo(b.modified)
                    if (c != 0) (if (criteria == 2) -c else c) else names.compare(a.name, b.name)
                }
                else -> names.compare(a.name, b.name)
            }
        }
    }

    /** FILE_ATTR_* order: playlists, audio, then the others; unknown types after all known ones. */
    private fun typeRank(d: Doc): Int = when {
        d.isPlaylist -> 1
        d.isTrack -> 2
        supported(d) -> 3 + SUPPORTED_OTHER.indexOf(d.name.substringAfterLast('.', "").lowercase())
        else -> Int.MAX_VALUE
    }

    /**
     * Rockbox strnatcmp0() (natural order, Martin Pool's algorithm as Rockbox
     * changed it): runs of digits compare as numbers, leading zeros as
     * fractions, '.' sorts before other characters.
     */
    fun strnatcmp(a0: String, b0: String, ignoreCase: Boolean): Int {
        val a = a0.toByteArray(Charsets.UTF_8)
        val b = b0.toByteArray(Charsets.UTF_8)
        fun at(s: ByteArray, i: Int) = if (i < s.size) s[i].toInt() and 0xff else 0
        fun digit(c: Int) = c in '0'.code..'9'.code
        fun compareRight(ai0: Int, bi0: Int): Int {
            var bias = 0
            var ai = ai0; var bi = bi0
            while (true) {
                val ca = at(a, ai); val cb = at(b, bi)
                if (!digit(ca) && !digit(cb)) return bias
                if (!digit(ca)) return -1
                if (!digit(cb)) return 1
                if (ca < cb) { if (bias == 0) bias = -1 }
                else if (ca > cb) { if (bias == 0) bias = 1 }
                ai++; bi++
            }
        }
        fun compareLeft(ai0: Int, bi0: Int): Int {
            var ai = ai0; var bi = bi0
            while (true) {
                val ca = at(a, ai); val cb = at(b, bi)
                if (!digit(ca) && !digit(cb)) return 0
                if (!digit(ca)) return -1
                if (!digit(cb)) return 1
                if (ca < cb) return -1
                if (ca > cb) return 1
                ai++; bi++
            }
        }
        var ai = 0; var bi = 0
        while (true) {
            var ca = at(a, ai); var cb = at(b, bi)
            if (digit(ca) && digit(cb)) {
                val fractional = ca == '0'.code || cb == '0'.code
                val r = if (fractional) compareLeft(ai, bi) else compareRight(ai, bi)
                if (r != 0) return r
            }
            if (ca == 0 && cb == 0) {
                return if (ignoreCase) a0.compareTo(b0, ignoreCase = true).coerceIn(-1, 1) else a0.compareTo(b0).coerceIn(-1, 1)
            }
            if (ca == '.'.code) ca = 1
            if (cb == '.'.code) cb = 1
            if (ignoreCase) {
                if (ca in 'A'.code..'Z'.code) ca += 32
                if (cb in 'A'.code..'Z'.code) cb += 32
            }
            if (ca < cb) return -1
            if (ca > cb) return 1
            ai++; bi++
        }
    }

    /** Show Path: 0 Off ("Files"), 1 the folder's name, 2 the full path. */
    fun title(path: List<Doc>, store: SettingsStore): String {
        val dir = path.lastOrNull() ?: return "Files"
        return when (store.int("show path in browser")) {
            0 -> store.catalog.str("LANG_DIR_BROWSER")
            2 -> llm.slop.slopbox.PlaylistFiles.pathOf(dir.id)
            else -> dir.name
        }
    }
}
