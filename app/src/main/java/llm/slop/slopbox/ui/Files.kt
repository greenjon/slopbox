/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/filetree.c - Copyright (C) 2005 by Björn Stenberg
 *   apps/recorder/albumart.c - Copyright (C) 2007 Nicolas Pennequin
 * and the other Rockbox contributors (third_party/rockbox/docs/CREDITS).
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package llm.slop.slopbox.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import llm.slop.slopbox.RockboxTagFile
import java.io.File

/** One entry in a folder of the music tree. */
data class Doc(
    val id: String,
    val name: String,
    val isDir: Boolean,
    /** Last modified (ms), for "Sort ... By Date". */
    val modified: Long = 0,
    /** The provider says it's audio (beyond what the extension says). */
    val audioMime: Boolean = false,
) {
    /** A playlist file (.m3u/.m3u8): listed in the browser, not played as a track. */
    val isPlaylist: Boolean get() = !isDir && llm.slop.slopbox.PlaylistFiles.isPlaylist(name)
    /** A track the codecs can play. */
    val isTrack: Boolean get() = !isDir && !isPlaylist && (audioMime || MusicTree.isAudio(name, ""))
}

/**
 * The music folder the user picked (Storage Access Framework tree), browsed
 * like Rockbox's file browser.
 */
object MusicTree {
    private const val PREFS = "ui"
    private const val KEY_ROOT = "musicRoot"

    fun root(ctx: Context): Uri? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ROOT, null)?.let(Uri::parse)
            ?.takeIf { uri -> ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission } }

    fun setRoot(ctx: Context, tree: Uri) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ROOT, tree.toString()).apply()
    }

    fun rootId(tree: Uri): String = DocumentsContract.getTreeDocumentId(tree)

    fun docUri(tree: Uri, id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    /**
     * Rockbox ft_load(): everything in a folder, sorted by the File View
     * settings (folders first). Dot files are left out unless [hidden]
     * (Rockbox shows them only with Show Files: All). Callers filter for
     * what they want (tracks, or the browser's Show Files).
     */
    suspend fun list(ctx: Context, tree: Uri, dirId: String, hidden: Boolean = false): List<Doc> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Doc>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        try {
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (!hidden && name.startsWith(".")) continue
                    val mime = c.getString(2) ?: ""
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    out += Doc(c.getString(0), name, isDir, c.getLong(3), !isDir && mime.startsWith("audio/"))
                }
            }
        } catch (e: Exception) {
            Log.w("MusicTree", "list $dirId failed", e)
        }
        out.sortWith(FileView.comparator(llm.slop.slopbox.SettingsStore.get(ctx)))
        out
    }

    /** Every playable file under [dir], depth first in browser order. */
    /** [limit]: Rockbox's "Max Playlist Size" unless given. */
    suspend fun walk(ctx: Context, tree: Uri, dir: Doc,
                     limit: Int = llm.slop.slopbox.SettingsStore.get(ctx).int("max files in playlist")): List<Pair<Doc, Doc>> {
        val out = mutableListOf<Pair<Doc, Doc>>() // file to its folder
        suspend fun go(d: Doc) {
            for (e in list(ctx, tree, d.id)) {
                if (out.size >= limit) return
                if (e.isDir) go(e) else if (e.isTrack) out += e to d
            }
        }
        go(dir)
        return out
    }

    fun item(tree: Uri, file: Doc, dir: Doc): MediaItem {
        val uri = docUri(tree, file.id)
        return MediaItem.Builder()
            .setMediaId(file.id)
            .setUri(uri)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(uri)
                    .setExtras(bundleOf(EXTRA_TREE to tree.toString(), EXTRA_DIR to dir.id, EXTRA_DIR_NAME to dir.name))
                    .build()
            )
            .setMediaMetadata(MediaMetadata.Builder().setTitle(file.name.substringBeforeLast('.')).build())
            .build()
    }

    const val EXTRA_TREE = "slopbox.tree"
    const val EXTRA_DIR = "slopbox.dir"
    const val EXTRA_DIR_NAME = "slopbox.dirName"

    // What the engine's codecs can open, by extension (Rockbox identifies by extension too).
    private val AUDIO_EXT = setOf(
        "mp1", "mp2", "mp3", "mpa", "flac", "ogg", "oga", "opus", "spx", "m4a", "m4b", "mp4", "aac",
        "alac", "wav", "w64", "wv", "ape", "mac", "mpc", "aif", "aiff", "aifc", "wma", "wmv", "asf",
        "tta", "shn", "au", "snd", "a52", "ac3", "adx", "oma", "aa3", "at3", "rm", "ra", "rmvb",
        "vox", "smaf", "mmf", "vtx", "ay", "gbs", "hes", "kss", "nsf", "nsfe", "sap", "spc", "vgm", "vgz",
        "mod", "cmc", "cm3", "cmr", "cms", "dmc", "dlt", "mpt", "mpd", "rmt", "tmc", "tm8", "tm2", "mpu",
    )

    fun isAudio(name: String, mime: String): Boolean =
        mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in AUDIO_EXT
}

/** Tags the WPS shows that the engine doesn't pass on (year, genre), plus album art. */
data class Tags(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val year: String = "",
    val genre: String = "",
    val trackNo: String = "",
    val art: ImageBitmap? = null,
)

object TagReader {
    private val artCache = HashMap<String, ImageBitmap?>()

    /** Tags and embedded art via Rockbox's own parser; folder images as Rockbox looks for them. */
    suspend fun read(ctx: Context, item: MediaItem, withArt: Boolean): Tags = withContext(Dispatchers.IO) {
        val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri ?: return@withContext Tags()
        // Rockbox "Album Art": Off, Prefer Embedded, Prefer Image File.
        val artMode = if (withArt) llm.slop.slopbox.SettingsStore.get(ctx).int("album art") else 0
        val r = RockboxTagFile.read(ctx, uri, withArt = artMode != 0)
        val art = when (artMode) {
            0 -> null
            2 -> folderArt(ctx, item, uri) ?: r?.art?.let { decode(it) }
            else -> r?.art?.let { decode(it) } ?: folderArt(ctx, item, uri)
        }
        val t = r?.tags ?: return@withContext Tags(art = art)
        Tags(
            title = t.title.trim(),
            artist = t.artist.trim(),
            album = t.album.trim(),
            albumArtist = t.albumArtist.trim(),
            year = if (t.yearNum > 0) t.yearNum.toString() else t.year.trim(),
            genre = t.genre.trim(),
            trackNo = t.track.trim().ifEmpty { if (t.trackNum > 0) t.trackNum.toString() else "" },
            art = art,
        )
    }

    // Rockbox looks for cover/folder images next to the track as well.
    private val ART_NAMES = listOf("cover.jpg", "cover.jpeg", "cover.png", "folder.jpg", "folder.jpeg",
        "folder.png", "front.jpg", "front.png", "albumart.jpg", "albumartsmall.jpg")

    private fun folderArt(ctx: Context, item: MediaItem, uri: Uri): ImageBitmap? {
        val extras = item.requestMetadata.extras
        val tree = extras?.getString(MusicTree.EXTRA_TREE)?.let(Uri::parse)
        val dir = extras?.getString(MusicTree.EXTRA_DIR)
        val key = when {
            tree != null && dir != null -> "$tree|$dir"
            uri.scheme == "file" -> File(uri.path!!).parent ?: return null
            else -> return null
        }
        synchronized(artCache) { if (key in artCache) return artCache[key] }
        val found: ImageBitmap? = try {
            if (tree != null && dir != null) {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir)
                val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val byName = HashMap<String, String>()
                ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) byName[c.getString(1).lowercase()] = c.getString(0)
                }
                ART_NAMES.firstNotNullOfOrNull { byName[it] }?.let { id ->
                    ctx.contentResolver.openInputStream(MusicTree.docUri(tree, id))?.use { decode(it.readBytes()) }
                }
            } else {
                val d = File(key)
                d.listFiles()?.firstOrNull { it.name.lowercase() in ART_NAMES }?.readBytes()?.let { decode(it) }
            }
        } catch (e: Exception) {
            null
        }
        synchronized(artCache) { artCache[key] = found }
        return found
    }

    private fun decode(bytes: ByteArray, maxPx: Int = 900): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        val bmp: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return bmp.asImageBitmap()
    }
}
