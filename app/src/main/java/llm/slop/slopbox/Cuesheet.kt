/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/cuesheet.c - Copyright (C) 2007 Nicolas Pennequin, Jonathan Gordon
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
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.File
import java.nio.charset.Charset

/**
 * Rockbox cuesheets (apps/cuesheet.c): tracks inside one audio file, from an
 * embedded cuesheet or chapters (read by Rockbox's metadata code in the
 * engine) or a .cue file found the way search_for_cuesheet() looks.
 */
class Cuesheet(
    val title: String,
    val performer: String,
    val songwriter: String,
    val tracks: List<Track>,
) {
    class Track(val offsetMs: Long, val title: String, val performer: String, val songwriter: String)

    /** Rockbox cue_find_current_track(). */
    fun currentTrack(posMs: Long): Int {
        var i = 0
        while (i < tracks.size - 1 && tracks[i + 1].offsetMs < posMs) i++
        return i
    }

    /**
     * Rockbox curr_cuesheet_skip(): the position to seek to, or null to leave
     * the cuesheet (Next on its last track).
     */
    fun skip(direction: Int, posMs: Long): Long? {
        var track = currentTrack(posMs)
        if (direction >= 0 && track == tracks.size - 1) return null
        if (!(direction <= 0 && track == 0)) {
            if (direction == 1 || posMs - tracks[track].offsetMs < SKIP_THRESH_MS) track += direction
        }
        return tracks[track].offsetMs
    }

    companion object {
        private const val MAX_TRACKS = 99
        private const val MAX_STRING = 80 * 3
        private const val SKIP_THRESH_MS = 3000L

        /**
         * Rockbox look_for_cuesheet_file(): embedded first, then
         * "<name>.cue" beside the file, then "<file name>.cue".
         * (Rockbox's /.rockbox/cue/ folder isn't searched; see DIFFERENCES.)
         */
        fun find(ctx: Context, uri: Uri, fileName: String, tree: Uri?, dirId: String?): Cuesheet? {
            embedded(ctx, uri, fileName)?.let { return it }
            val base = fileName.substringBeforeLast('.', fileName)
            val candidates = listOf("$base.cue", "$fileName.cue")
            if (uri.scheme == "file") {
                val dir = File(uri.path!!).parentFile ?: return null
                for (n in candidates) {
                    val f = File(dir, n)
                    if (f.exists()) return parse(f.readBytes(), 1, null)
                }
                return null
            }
            if (tree == null || dirId == null) return null
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
            val byName = HashMap<String, String>()
            runCatching {
                ctx.contentResolver.query(children, arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                    while (c.moveToNext()) byName[c.getString(1)] = c.getString(0)
                }
            }
            for (n in candidates) {
                val id = byName[n] ?: continue
                val bytes = runCatching {
                    ctx.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))
                        ?.use { it.readBytes() }
                }.getOrNull() ?: continue
                return parse(bytes, 1, null)
            }
            return null
        }

        private fun embedded(ctx: Context, uri: Uri, name: String): Cuesheet? {
            val pfd = runCatching {
                if (uri.scheme == "file") ParcelFileDescriptor.open(File(uri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
                else ctx.contentResolver.openFileDescriptor(uri, "r")
            }.getOrNull() ?: return null
            return pfd.use { p ->
                val r = Engine.readEmbeddedCue(p.fd, name) ?: return@use null
                if (r[0] == "chapters") {
                    val tracks = (1 until r.size step 2).map { i ->
                        Track(r[i].toLongOrNull() ?: 0, r[i + 1], "", "")
                    }
                    Cuesheet("", "", "", tracks)
                } else {
                    val pos = r[1].toLong()
                    val size = r[2].toInt()
                    val enc = r[3].toInt()
                    val bytes = java.io.FileInputStream(p.fileDescriptor).channel.let { ch ->
                        val buf = java.nio.ByteBuffer.allocate(size)
                        ch.read(buf, pos)
                        buf.array().copyOf(buf.position())
                    }
                    parse(bytes, enc, null)
                }
            }
        }

        /** Rockbox parse_cuesheet(). [encoding]: 1 ISO-8859-1, 2 UTF-8, 3/4 UTF-16 LE/BE. */
        fun parse(bytes: ByteArray, encoding: Int, @Suppress("UNUSED_PARAMETER") unused: Any?): Cuesheet? {
            var enc = encoding
            var start = 0
            // A Unicode BOM overrides the encoding.
            if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
                enc = 2; start = 3
            } else if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                enc = 3; start = 2
            } else if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                enc = 4; start = 2
            }
            val charset: Charset = when (enc) {
                2 -> Charsets.UTF_8
                3 -> Charsets.UTF_16LE
                4 -> Charsets.UTF_16BE
                else -> Charsets.ISO_8859_1
            }
            val text = String(bytes, start, bytes.size - start, charset)

            var title = ""; var performer = ""; var songwriter = ""
            class T(var offset: Long = 0, var title: String = "", var performer: String = "", var songwriter: String = "")
            val tracks = mutableListOf<T>()
            for (raw in text.lineSequence()) {
                if (tracks.size >= MAX_TRACKS && !raw.trimStart().startsWith("INDEX 01")) break
                val s = raw.trimStart()
                when {
                    s.startsWith("TRACK") -> tracks += T()
                    s.startsWith("INDEX 01") -> tracks.lastOrNull()?.offset = parseIndex(s)
                    s.startsWith("TITLE") || s.startsWith("PERFORMER") || s.startsWith("SONGWRITER") -> {
                        val v = getString(s) ?: break
                        val cur = tracks.lastOrNull()
                        when {
                            s.startsWith("TITLE") -> if (cur == null) title = v else cur.title = v
                            s.startsWith("PERFORMER") -> if (cur == null) performer = v else cur.performer = v
                            else -> if (cur == null) songwriter = v else cur.songwriter = v
                        }
                    }
                    s.startsWith("FILE") -> if (getString(s) == null) break
                }
            }
            if (tracks.isEmpty()) return null
            // Tracks without performer/songwriter get the cuesheet's.
            return Cuesheet(title, performer, songwriter, tracks.map {
                Track(it.offset, it.title, it.performer.ifEmpty { performer }, it.songwriter.ifEmpty { songwriter })
            })
        }

        /** Rockbox get_string(): the quoted value, or what follows the first space. */
        private fun getString(line: String): String? {
            val q = line.indexOf('"')
            val startAt = if (q >= 0) q else line.indexOf(' ').takeIf { it >= 0 } ?: return null
            val rest = line.substring(startAt + 1)
            val end = rest.indexOf('"')
            return (if (end >= 0) rest.substring(0, end) else rest).take(MAX_STRING)
        }

        /** Rockbox parse_cue_index(): "INDEX 01 MM:SS:FF" -> ms (a frame counts 13 ms). */
        private fun parseIndex(line: String): Long {
            val mult = longArrayOf(60_000, 1000, 13)
            val max = longArrayOf(30000, 59, 74)
            var field = -1
            var offset = 0L
            var i = 0
            while (i < line.length) {
                if (!line[i].isDigit()) { i++; continue }
                var value = 0L
                while (i < line.length && line[i].isDigit()) {
                    value = value * 10 + (line[i] - '0')
                    if (field >= 0 && value > max[field]) return 0
                    i++
                }
                if (field in 0..2) {
                    while (i < line.length && line[i] != ':') i++
                    if (i < line.length || field == 2) offset += mult[field] * value
                } else if (field > 2) break
                field++
            }
            return offset
        }
    }
}
