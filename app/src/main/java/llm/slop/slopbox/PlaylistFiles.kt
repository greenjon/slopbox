/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/playlist.c - Copyright (C) 2002 by wavey@wavey.org
 *   apps/playlist_catalog.c - Copyright (C) 2006 Sebastian Henriksen, Hardeep Sidhu
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
import android.provider.DocumentsContract
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import llm.slop.slopbox.ui.Doc
import llm.slop.slopbox.ui.MusicTree

/**
 * Rockbox playlist files (.m3u/.m3u8, apps/playlist.c) and the Playlist
 * Catalog (apps/playlist_catalog.c), inside the chosen music folder.
 */
object PlaylistFiles {
    /** Request extra: the Rockbox playlist name (the file's path) a track came from. */
    const val EXTRA_PLAYLIST = "slopbox.playlist"
    /** Rockbox's PLAYLIST_CATALOG_DEFAULT_DIR, under the music folder. */
    const val CATALOG_DIR = "Playlists"

    fun isPlaylist(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext == "m3u" || ext == "m3u8"
    }

    private fun volume(tree: Uri) = DocumentsContract.getTreeDocumentId(tree).substringBefore(':')

    /** "/Music/x.m3u8" for a document id like "primary:Music/x.m3u8". */
    fun pathOf(docId: String) = "/" + docId.substringAfter(':').trim('/')

    fun docIdOf(tree: Uri, path: String) = volume(tree) + ":" + path.trim('/')

    /**
     * Rockbox add_indices_to_playlist() + format_track_path(): one track per
     * line; blank lines and "#" lines skipped; backslashes become slashes; a
     * drive letter is dropped; relative paths are relative to the playlist.
     * .m3u8 (or a UTF-8 BOM) is UTF-8, anything else ISO-8859-1.
     */
    suspend fun load(ctx: Context, tree: Uri, playlistDocId: String, name: String): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val bytes = runCatching {
                ctx.contentResolver.openInputStream(MusicTree.docUri(tree, playlistDocId))?.use { it.readBytes() }
            }.getOrNull() ?: return@withContext emptyList()
            val bom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
            val utf8 = bom || name.lowercase().endsWith(".m3u8")
            val text = if (utf8) String(bytes, if (bom) 3 else 0, bytes.size - (if (bom) 3 else 0), Charsets.UTF_8)
                else String(bytes, Charsets.ISO_8859_1)
            val plPath = pathOf(playlistDocId)
            val plDir = plPath.substringBeforeLast('/', "")
            text.split('\n', '\r').mapNotNull { raw ->
                var line = raw.trimEnd(' ', '\t')
                if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
                line = line.replace('\\', '/')
                if (line.length >= 2 && line[1] == ':' && line[0].isLetter()) line = line.substring(2)
                val path = normalize(if (line.startsWith("/")) line else "$plDir/$line")
                val dirPath = path.substringBeforeLast('/', "")
                val file = Doc(docIdOf(tree, path), path.substringAfterLast('/'), false)
                val dir = Doc(docIdOf(tree, dirPath), dirPath.substringAfterLast('/'), true)
                val item = MusicTree.item(tree, file, dir)
                item.buildUpon().setRequestMetadata(item.requestMetadata.buildUpon()
                    .setExtras((item.requestMetadata.extras ?: bundleOf()).apply { putString(EXTRA_PLAYLIST, plPath) })
                    .build()).build()
            }
        }

    private fun normalize(path: String): String {
        val out = ArrayDeque<String>()
        for (p in path.split('/')) when (p) {
            "", "." -> {}
            ".." -> out.removeLastOrNull()
            else -> out.addLast(p)
        }
        return "/" + out.joinToString("/")
    }

    /** Rockbox pl_save_playlist(): absolute paths, UTF-8 (BOM only for .m3u), no queued tracks. */
    fun write(ctx: Context, target: Uri, name: String, paths: List<String>): Boolean = runCatching {
        ctx.contentResolver.openOutputStream(target, "wt")!!.use { o ->
            if (!name.lowercase().endsWith(".m3u8")) o.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
            o.write(paths.joinToString("") { "$it\n" }.toByteArray(Charsets.UTF_8))
        }
        true
    }.getOrDefault(false)

    /** Can SlopBox write into the music folder (needed for the catalog)? */
    fun writable(ctx: Context, tree: Uri): Boolean =
        ctx.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

    /**
     * Rockbox "playlist catalog directory" when it was set (Set As... ›
     * Playlist Directory), else the default: Playlists/ in the music folder.
     */
    fun customCatalog(ctx: Context): String? {
        val st = SettingsStore.get(ctx)
        val s = st.catalog.byCfg["playlist catalog directory"] ?: return null
        val v = st.text(s)
        return v.takeIf { it.isNotBlank() && it != s.defaultText && it.trim('/').isNotEmpty() }
    }

    /** The catalog folder's document id, creating it if allowed. */
    fun catalogDir(ctx: Context, tree: Uri, create: Boolean): String? {
        val root = MusicTree.rootId(tree)
        val custom = customCatalog(ctx)
        val id = if (custom != null) docIdOf(tree, custom.trimEnd('/')) else root.trimEnd('/') + "/" + CATALOG_DIR
        val exists = runCatching {
            ctx.contentResolver.query(MusicTree.docUri(tree, id), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null)?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
        if (exists) return id
        if (custom != null || !create || !writable(ctx, tree)) return null
        return runCatching {
            DocumentsContract.createDocument(ctx.contentResolver, MusicTree.docUri(tree, root),
                DocumentsContract.Document.MIME_TYPE_DIR, CATALOG_DIR)
        }.getOrNull()?.let { DocumentsContract.getDocumentId(it) }
    }

    /** Playlists in the catalog folder itself, sorted per "Sort Playlists" (alphabetical / oldest / newest). */
    fun catalog(ctx: Context, tree: Uri): List<Pair<Doc, Long>> {
        val dir = catalogDir(ctx, tree, create = false) ?: return emptyList()
        return entries(ctx, tree, dir).second
    }

    /**
     * One folder of the catalog: its subfolders by name, then its playlists
     * per "Sort Playlists". Rockbox lists only the top folder's playlists;
     * SlopBox lets you file playlists in subfolders (docs/DIFFERENCES.md).
     */
    fun catalogFolder(ctx: Context, tree: Uri, dirId: String): List<Doc> {
        val (dirs, files) = entries(ctx, tree, dirId)
        return dirs + files.map { it.first }
    }

    private fun entries(ctx: Context, tree: Uri, dirId: String): Pair<List<Doc>, List<Pair<Doc, Long>>> {
        val dirs = mutableListOf<Doc>()
        val out = mutableListOf<Pair<Doc, Long>>()
        runCatching {
            ctx.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId), arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(1) ?: continue
                    if (n.startsWith(".")) continue
                    if (c.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR) dirs += Doc(c.getString(0), n, true)
                    else if (isPlaylist(n)) out += Doc(c.getString(0), n, false) to c.getLong(2)
                }
            }
        }
        val files = when (SettingsStore.get(ctx).int("sort playlists")) {
            1 -> out.sortedBy { it.second }
            2 -> out.sortedByDescending { it.second }
            else -> out.sortedBy { it.first.name.lowercase() }
        }
        return dirs.sortedBy { it.name.lowercase() } to files
    }

    /** A new playlist file in the catalog (Rockbox adds .m3u8). Null if it can't. */
    fun create(ctx: Context, tree: Uri, name: String): Pair<Uri, String>? {
        val dir = catalogDir(ctx, tree, create = true) ?: return null
        val file = if (isPlaylist(name)) name else "$name.m3u8"
        val uri = runCatching {
            DocumentsContract.createDocument(ctx.contentResolver, MusicTree.docUri(tree, dir), "audio/x-mpegurl", file)
        }.getOrNull() ?: return null
        return uri to DocumentsContract.getDocumentId(uri)
    }

    /** Rockbox catalog_add_to_a_playlist(): append tracks to a playlist file. */
    suspend fun append(ctx: Context, tree: Uri, docId: String, name: String, paths: List<String>): Boolean =
        withContext(Dispatchers.IO) {
            val existing = load(ctx, tree, docId, name).mapNotNull { it.requestMetadata.mediaUri }
                .map { PlaybackMemory.pathOf(it) }
            write(ctx, MusicTree.docUri(tree, docId), name, existing + paths)
        }
}
