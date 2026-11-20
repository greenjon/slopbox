package llm.slop.slopbox

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import llm.slop.slopbox.ui.Doc
import llm.slop.slopbox.ui.MusicTree

/**
 * Rockbox's file operations (apps/fileop.c) on the music folder, through the
 * Storage Access Framework: rename, delete, cut/copy/paste, create directory,
 * and the numbers the Properties plugin shows (apps/plugins/properties.c).
 */
object FileOps {
    /** Rockbox's clipboard (onplay.c): one file or folder, cut or copied. */
    object Clipboard {
        @Volatile var doc: Doc? = null
        @Volatile var parentId: String? = null
        @Volatile var cut = false
        fun clip(d: Doc, parent: String, isCut: Boolean) { doc = d; parentId = parent; cut = isCut }
        fun clear() { doc = null; parentId = null }
    }

    /** A file or folder in [parentId]: id, name, folder?, size, modified. */
    class Child(val id: String, val name: String, val isDir: Boolean, val size: Long, val modified: Long)

    fun children(ctx: Context, tree: Uri, parentId: String): List<Child> {
        val out = mutableListOf<Child>()
        runCatching {
            ctx.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId), arrayOf(
                Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                while (c.moveToNext()) out += Child(c.getString(0), c.getString(1) ?: "",
                    c.getString(2) == Document.MIME_TYPE_DIR, c.getLong(3), c.getLong(4))
            }
        }
        return out
    }

    fun info(ctx: Context, tree: Uri, id: String): Child? = runCatching {
        ctx.contentResolver.query(MusicTree.docUri(tree, id), arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            if (c.moveToFirst()) Child(c.getString(0), c.getString(1) ?: "",
                c.getString(2) == Document.MIME_TYPE_DIR, c.getLong(3), c.getLong(4)) else null
        }
    }.getOrNull()

    fun exists(ctx: Context, tree: Uri, parentId: String, name: String) =
        children(ctx, tree, parentId).any { it.name == name }

    /** Rockbox rename_file(): null if it failed (or the name is taken). */
    fun rename(ctx: Context, tree: Uri, parentId: String, doc: Doc, newName: String): String? {
        if (newName == doc.name) return doc.id
        if (exists(ctx, tree, parentId, newName)) return null          // FORC_PATH_EXISTS
        return runCatching {
            DocumentsContract.renameDocument(ctx.contentResolver, MusicTree.docUri(tree, doc.id), newName)
        }.getOrNull()?.let { DocumentsContract.getDocumentId(it) }
    }

    /** Rockbox delete_fileobject(): a file, or a folder and everything in it. */
    fun delete(ctx: Context, tree: Uri, id: String): Boolean = runCatching {
        DocumentsContract.deleteDocument(ctx.contentResolver, MusicTree.docUri(tree, id))
    }.getOrDefault(false)

    /** Rockbox create_dir(): the new folder's id, or null. */
    fun mkdir(ctx: Context, tree: Uri, parentId: String, name: String): String? {
        if (exists(ctx, tree, parentId, name)) return null
        return runCatching {
            DocumentsContract.createDocument(ctx.contentResolver, MusicTree.docUri(tree, parentId), Document.MIME_TYPE_DIR, name)
        }.getOrNull()?.let { DocumentsContract.getDocumentId(it) }
    }

    /**
     * Rockbox copy_move_fileobject(): paste the clipboard into [destId]. Moving
     * tries a plain move first, then copy + delete, as Rockbox tries rename
     * first. [overwrite]: replace something of the same name (asked first).
     */
    suspend fun paste(ctx: Context, tree: Uri, destId: String, overwrite: Boolean): Boolean = withContext(Dispatchers.IO) {
        val src = Clipboard.doc ?: return@withContext false
        val srcParent = Clipboard.parentId ?: return@withContext false
        if (srcParent == destId) return@withContext true                                  // FORC_NOOP
        if (src.isDir && (destId == src.id || destId.startsWith(src.id.trimEnd('/') + "/"))) return@withContext false
        children(ctx, tree, destId).firstOrNull { it.name == src.name }?.let {
            if (!overwrite || !delete(ctx, tree, it.id)) return@withContext false
        }
        val ok = if (Clipboard.cut) {
            val moved = runCatching {
                DocumentsContract.moveDocument(ctx.contentResolver, MusicTree.docUri(tree, src.id),
                    MusicTree.docUri(tree, srcParent), MusicTree.docUri(tree, destId))
            }.getOrNull() != null
            moved || (copy(ctx, tree, src.id, src.name, src.isDir, destId) && delete(ctx, tree, src.id))
        } else copy(ctx, tree, src.id, src.name, src.isDir, destId)
        if (ok) Clipboard.clear()
        ok
    }

    private fun copy(ctx: Context, tree: Uri, id: String, name: String, isDir: Boolean, destId: String): Boolean {
        val r = ctx.contentResolver
        if (isDir) {
            val newDir = mkdir(ctx, tree, destId, name) ?: return false
            return children(ctx, tree, id).all { copy(ctx, tree, it.id, it.name, it.isDir, newDir) }
        }
        val mime = r.getType(MusicTree.docUri(tree, id)) ?: "application/octet-stream"
        val out = runCatching { DocumentsContract.createDocument(r, MusicTree.docUri(tree, destId), mime, name) }
            .getOrNull() ?: return false
        return runCatching {
            r.openInputStream(MusicTree.docUri(tree, id))!!.use { i -> r.openOutputStream(out, "w")!!.use { o -> i.copyTo(o) } }
            true
        }.getOrDefault(false)
    }

    /** Rockbox collect_dir_stats(): subfolders, files, bytes and audio files under a folder. */
    class DirStats(var dirs: Int = 0, var files: Int = 0, var bytes: Long = 0, var audio: Int = 0)

    suspend fun dirStats(ctx: Context, tree: Uri, id: String): DirStats = withContext(Dispatchers.IO) {
        val s = DirStats()
        fun go(dir: String) {
            for (c in children(ctx, tree, dir)) {
                if (c.isDir) { s.dirs++; go(c.id) }
                else {
                    s.files++; s.bytes += c.size
                    if (MusicTree.isAudio(c.name, "")) s.audio++
                }
            }
        }
        go(id)
        s
    }

    /** Rockbox human_size(): "123 KiB", switching unit at the 10K boundary. */
    fun humanSize(bytes: Long, units: List<String>): String {
        var n = bytes
        var i = 0
        while (i < units.size - 1 && n >= 10 * 1024) { n = n shr 10; i++ }
        return "$n ${units[i]}"
    }
}
