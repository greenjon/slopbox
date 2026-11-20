/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/plugins/random_folder_advance_config.c - Copyright (C) 2006 Jonathan Gordon
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
package llm.slop.slopbox.plugins

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import llm.slop.slopbox.FileOps
import llm.slop.slopbox.PlaylistFiles
import llm.slop.slopbox.ui.MusicTree
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Rockbox's random_folder_advance_config plugin and the list it keeps,
 * folder_advance_list.dat: a native-endian int count, then one 260-byte
 * (MAX_PATH) zero-padded path per folder. Auto-Change Directory "Random"
 * picks from it (playlist.c get_next_dir()).
 */
object RandomFolderAdvance {
    const val LIST_DAT = "folder_advance_list.dat"
    /** In the music folder, where SlopBox keeps files you can edit. */
    const val LIST_TXT = "folder_advance_list.txt"
    const val CUSTOM_DIRS = "folder_advance_dir.txt"
    private const val MAX_PATH = 260

    private fun datFile(ctx: Context) = File(ctx.filesDir, LIST_DAT)

    fun exists(ctx: Context) = datFile(ctx).exists()

    /** load_dirs(): the folders in the list. */
    fun load(ctx: Context): List<String>? {
        val bytes = runCatching { datFile(ctx).readBytes() }.getOrNull() ?: return null
        if (bytes.size < 4) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = b.int
        val out = ArrayList<String>(count.coerceAtLeast(0))
        for (i in 0 until count) {
            val off = 4 + i * MAX_PATH
            if (off + MAX_PATH > bytes.size) break
            var end = off
            while (end < off + MAX_PATH && bytes[end] != 0.toByte()) end++
            out += String(bytes, off, end - off, Charsets.UTF_8)
        }
        return out
    }

    /** save_dirs(): write the list (paths too long for MAX_PATH are dropped). */
    fun save(ctx: Context, dirs: List<String>) {
        val ok = dirs.map { it.toByteArray(Charsets.UTF_8) }.filter { it.size < MAX_PATH }
        val b = ByteBuffer.allocate(4 + ok.size * MAX_PATH).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(ok.size)
        for (p in ok) { b.put(p); b.put(ByteArray(MAX_PATH - p.size)) }
        datFile(ctx).writeBytes(b.array())
    }

    /** A text file at the top of the music folder: its document id, if there. */
    private fun rootFile(ctx: Context, tree: Uri, name: String): String? =
        FileOps.children(ctx, tree, MusicTree.rootId(tree)).firstOrNull { it.name == name }?.id

    private fun readRootText(ctx: Context, tree: Uri, name: String): String? {
        val id = rootFile(ctx, tree, name) ?: return null
        return runCatching {
            ctx.contentResolver.openInputStream(MusicTree.docUri(tree, id))!!.bufferedReader().readText()
        }.getOrNull()
    }

    /**
     * generate(): every folder under the music folder, depth first in
     * directory order -- or, when folder_advance_dir.txt exists, under the
     * folders it lists, leaving out the ones given as "-/path".
     * [progress] gets the folder count as it grows. Returns the count.
     */
    fun scan(ctx: Context, tree: Uri, progress: (Int) -> Unit = {}): Int {
        val out = ArrayList<String>()
        val custom = readRootText(ctx, tree, CUSTOM_DIRS)?.lines()
        val ignored = custom.orEmpty().filter { it.startsWith("-/") }.map { it.substring(1).trimEnd('/') }.toSet()
        fun traverse(dirId: String, path: String) {
            for (c in FileOps.children(ctx, tree, dirId)) {
                if (!c.isDir || c.name.startsWith(".")) continue
                val p = "$path/${c.name}"
                if (p in ignored) continue
                out += p
                if (out.size % 20 == 0) progress(out.size)
                traverse(c.id, p)
            }
        }
        if (custom == null) {
            traverse(MusicTree.rootId(tree), PlaylistFiles.pathOf(MusicTree.rootId(tree)).trimEnd('/'))
        } else {
            for (raw in custom) {
                if (raw.isEmpty() || raw.startsWith("-/")) continue
                val p = "/" + raw.trimStart('/').trimEnd('/')
                val id = PlaylistFiles.docIdOf(tree, p)
                if (FileOps.info(ctx, tree, id)?.isDir != true) continue
                if (p !in ignored) out += p
                traverse(id, p)
            }
        }
        save(ctx, out)
        return out.size
    }

    /** export(): the list as text, one folder per line, in the music folder. */
    fun export(ctx: Context, tree: Uri): Boolean {
        val dirs = load(ctx) ?: return false
        if (dirs.isEmpty()) return false
        return runCatching {
            val root = MusicTree.rootId(tree)
            val uri = rootFile(ctx, tree, LIST_TXT)?.let { MusicTree.docUri(tree, it) }
                ?: DocumentsContract.createDocument(ctx.contentResolver, MusicTree.docUri(tree, root), "text/plain", LIST_TXT)!!
            ctx.contentResolver.openOutputStream(uri, "wt")!!.use { o -> o.write(dirs.joinToString("") { "$it\n" }.toByteArray()) }
        }.isSuccess
    }

    /** import(): folder_advance_list.txt back into the list; the count, or -1. */
    fun import(ctx: Context, tree: Uri): Int {
        val text = readRootText(ctx, tree, LIST_TXT) ?: return -1
        val dirs = text.lines().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
        if (dirs.isNotEmpty()) save(ctx, dirs)
        return dirs.size
    }

    /** Rockbox get_next_dir() random: a listed folder (inside [basePath]) with music, or null. */
    fun pick(ctx: Context, tree: Uri, basePath: String, hasMusic: (String) -> Boolean): String? {
        val dirs = load(ctx) ?: return null
        if (dirs.isEmpty()) return null
        var tries = dirs.size * 4L
        val base = basePath.trimEnd('/')
        while (tries-- > 0) {
            val d = dirs[kotlin.random.Random.nextInt(dirs.size)]
            if ((base.isEmpty() || d.startsWith(base)) && hasMusic(PlaylistFiles.docIdOf(tree, d))) return d
        }
        return null
    }
}
