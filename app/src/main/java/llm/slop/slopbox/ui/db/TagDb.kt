package llm.slop.slopbox.ui.db

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import llm.slop.slopbox.RockboxTagFile
import llm.slop.slopbox.ui.MusicTree
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** One file in the database: Rockbox's tagcache entry, give or take the runtime stats. */
class Track(
    val id: String,          // document id
    val dir: String,         // folder document id
    val dirName: String,
    val path: String,        // "/Artist/Album/01 x.flac" from the music root (Rockbox "filename")
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String,
    val composer: String,
    val year: Int,
    val trackNum: Int,
    val discNum: Int,
    val lengthMs: Long,
    val bitrate: Int,
    val modified: Long,
    val size: Long,
    val commitId: Int,       // scan that added it
) {
    val name: String get() = path.substringAfterLast('/')
}

/**
 * The tag database, after Rockbox's: built by scanning the music folder
 * ("Initialize Now"), refreshed incrementally ("Update Now"), kept in a file.
 *
 * Tags come from Rockbox's own metadata parser (Engine.readTags), the same
 * one playback uses. A database built before that switch is re-read by
 * "Initialize Now".
 */
object TagDb {
    private const val FILE = "tagdb.bin"
    private const val VERSION = 1

    var tracks by mutableStateOf<List<Track>>(emptyList())
        private set
    /** Number of scans so far (Rockbox's commit id). */
    var commits by mutableIntStateOf(0)
        private set
    var loaded by mutableStateOf(false)
        private set
    /** Non-null while a scan runs: "Scanning… 120 files". */
    var progress by mutableStateOf<String?>(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null

    fun load(ctx: Context) {
        if (loaded) return
        scope.launch {
            val f = File(ctx.filesDir, FILE)
            if (f.exists()) runCatching { read(f) }.onFailure { Log.w(TAG, "read db failed", it) }
            loaded = true
        }
    }

    val scanning: Boolean get() = scanJob?.isActive == true

    /** [full]: forget everything and re-read every file (Initialize Now). */
    fun scan(ctx: Context, tree: Uri, full: Boolean) {
        if (scanning) return
        val app = ctx.applicationContext
        scanJob = scope.launch {
            progress = "Scanning…"
            try {
                val old = if (full) emptyMap() else tracks.associateBy { it.id }
                val commit = commits + 1
                val out = ArrayList<Track>(old.size)
                var seen = 0
                suspend fun walk(dirId: String, dirName: String, path: String) {
                    for (e in children(app, tree, dirId)) {
                        if (!isActive) return
                        if (e.isDir) {
                            walk(e.id, e.name, "$path/${e.name}")
                            continue
                        }
                        seen++
                        if (seen % 10 == 0) progress = "Scanning… $seen files"
                        val prev = old[e.id]
                        out += if (prev != null && prev.modified == e.modified && prev.size == e.size) prev
                        else readTags(app, tree, e, dirId, dirName, "$path/${e.name}", prev?.commitId ?: commit)
                    }
                }
                val rootId = MusicTree.rootId(tree)
                walk(rootId, "", "")
                if (!isActive) return@launch
                tracks = out
                commits = commit
                write(File(app.filesDir, FILE))
            } catch (e: Exception) {
                Log.w(TAG, "scan failed", e)
            } finally {
                progress = null
            }
        }
    }

    private class Child(val id: String, val name: String, val isDir: Boolean, val modified: Long, val size: Long)

    private fun children(ctx: Context, tree: Uri, dirId: String): List<Child> {
        val out = mutableListOf<Child>()
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        try {
            ctx.contentResolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId), cols, null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    val mime = c.getString(2) ?: ""
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    if (!isDir && !MusicTree.isAudio(name, mime)) continue
                    out += Child(c.getString(0), name, isDir, c.getLong(3), c.getLong(4))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "list $dirId failed", e)
        }
        out.sortWith(compareBy<Child>({ !it.isDir }, { it.name.lowercase() }))
        return out
    }

    /** Tags via Rockbox's own parser (the same one playback uses). */
    private fun readTags(
        ctx: Context, tree: Uri, e: Child, dirId: String, dirName: String, path: String, commit: Int,
    ): Track {
        val t = RockboxTagFile.read(ctx, MusicTree.docUri(tree, e.id), name = e.name)?.tags
            // Untagged or unreadable: still listed, by file name (as Rockbox does).
            ?: return Track(e.id, dirId, dirName, path, "", "", "", "", "", "", 0, 0, 0, 0, 0, e.modified, e.size, commit)
        return Track(
            id = e.id, dir = dirId, dirName = dirName, path = path,
            title = t.title.trim(), artist = t.artist.trim(), album = t.album.trim(),
            albumArtist = t.albumArtist.trim(), genre = t.genre.trim(), composer = t.composer.trim(),
            // Rockbox uses -1 for "none"; the database uses 0.
            year = t.yearNum.coerceAtLeast(0), trackNum = t.trackNum.coerceAtLeast(0),
            discNum = t.discNum.coerceAtLeast(0),
            lengthMs = t.lengthMs, bitrate = t.bitrate,
            modified = e.modified, size = e.size, commitId = commit,
        )
    }

    private fun write(f: File) {
        val tmp = File(f.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { o ->
            o.writeInt(VERSION)
            o.writeInt(commits)
            o.writeInt(tracks.size)
            for (t in tracks) {
                for (s in listOf(t.id, t.dir, t.dirName, t.path, t.title, t.artist, t.album, t.albumArtist, t.genre, t.composer)) {
                    o.writeUTF(s.take(16000))
                }
                o.writeInt(t.year); o.writeInt(t.trackNum); o.writeInt(t.discNum)
                o.writeLong(t.lengthMs); o.writeInt(t.bitrate)
                o.writeLong(t.modified); o.writeLong(t.size); o.writeInt(t.commitId)
            }
        }
        tmp.renameTo(f)
    }

    private fun read(f: File) {
        DataInputStream(f.inputStream().buffered()).use { i ->
            if (i.readInt() != VERSION) return
            val c = i.readInt()
            val n = i.readInt()
            val list = ArrayList<Track>(n)
            repeat(n) {
                val s = Array(10) { i.readUTF() }
                list += Track(
                    s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], s[8], s[9],
                    i.readInt(), i.readInt(), i.readInt(), i.readLong(), i.readInt(),
                    i.readLong(), i.readLong(), i.readInt(),
                )
            }
            commits = c
            tracks = list
        }
    }

    suspend fun clear(ctx: Context) = withContext(Dispatchers.IO) {
        scanJob?.cancel()
        File(ctx.filesDir, FILE).delete()
        tracks = emptyList()
        commits = 0
    }

    private const val TAG = "TagDb"
}
