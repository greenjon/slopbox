package llm.slop.slopbox

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What playback remembers between runs, as Rockbox does:
 * - the current playlist and position (Rockbox's playlist control file and
 *   global_status resume info), so playback can be resumed;
 * - per-track resume points for Automatic Resume (Rockbox keeps these in the
 *   database as lastelapsed/lastoffset);
 * - the playback log (Rockbox's /.rockbox/playback.log).
 */
class PlaybackMemory(private val context: Context) {

    // ---- Playlist resume ----------------------------------------------------

    class Saved(
        val items: List<MediaItem>,
        /** Natural (unshuffled) order, as indices into [items]. */
        val original: List<Int>,
        val shuffled: Boolean,
        val index: Int,
        val positionMs: Long,
    )

    private val playlistFile = File(context.filesDir, "resume_playlist.json")

    fun savePlaylist(items: List<MediaItem>, original: List<Int>, shuffled: Boolean, index: Int, positionMs: Long) {
        try {
            val arr = JSONArray()
            for (item in items) {
                val o = JSONObject()
                o.put("id", item.mediaId)
                (item.localConfiguration?.uri ?: item.requestMetadata.mediaUri)?.let { o.put("uri", it.toString()) }
                item.mediaMetadata.title?.let { o.put("title", it.toString()) }
                item.mediaMetadata.artist?.let { o.put("artist", it.toString()) }
                item.requestMetadata.extras?.let { b ->
                    val ex = JSONObject()
                    for (k in b.keySet()) b.getString(k)?.let { ex.put(k, it) }
                    o.put("extras", ex)
                }
                arr.put(o)
            }
            val root = JSONObject()
                .put("items", arr)
                .put("original", JSONArray(original))
                .put("shuffled", shuffled)
                .put("index", index)
                .put("position", positionMs)
            val tmp = File(playlistFile.path + ".tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(playlistFile)
        } catch (e: Exception) {
            Log.w(TAG, "can't save resume info", e)
        }
    }

    fun loadPlaylist(): Saved? = try {
        if (!playlistFile.exists()) null else {
            val root = JSONObject(playlistFile.readText())
            val arr = root.getJSONArray("items")
            val items = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val uri = o.optString("uri").takeIf { it.isNotEmpty() }?.let(Uri::parse)
                val extras = o.optJSONObject("extras")?.let { ex ->
                    Bundle().apply { ex.keys().forEach { k -> putString(k, ex.getString(k)) } }
                }
                MediaItem.Builder()
                    .setMediaId(o.optString("id"))
                    .setUri(uri)
                    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).setExtras(extras).build())
                    .setMediaMetadata(MediaMetadata.Builder()
                        .setTitle(o.optString("title").takeIf { it.isNotEmpty() })
                        .setArtist(o.optString("artist").takeIf { it.isNotEmpty() })
                        .build())
                    .build()
            }
            val orig = root.optJSONArray("original")?.let { a -> (0 until a.length()).map { a.getInt(it) } }
                ?: items.indices.toList()
            if (items.isEmpty()) null
            else Saved(items, orig, root.optBoolean("shuffled"), root.optInt("index"), root.optLong("position"))
        }
    } catch (e: Exception) {
        Log.w(TAG, "can't read resume info", e)
        null
    }

    fun clearPlaylist() { playlistFile.delete() }

    // ---- Automatic Resume (per track) ---------------------------------------

    private val points = context.getSharedPreferences("autoresume", Context.MODE_PRIVATE)

    fun resumePoint(key: String): Long = points.getLong(key, 0L)

    fun setResumePoint(key: String, ms: Long) {
        if (ms > 0) points.edit().putLong(key, ms).apply() else points.edit().remove(key).apply()
    }

    // ---- Playback log ---------------------------------------------------------

    private val logFile = File(context.filesDir, "playback.log")

    /** Rockbox allocate_playback_log(): rotate past 512 KB, mark the start. */
    fun startLog() {
        try {
            if (logFile.exists() && logFile.length() > LOG_MAX_BYTES) {
                var n = 1
                while (File(context.filesDir, "playback_%04d.log".format(n)).exists()) n++
                logFile.renameTo(File(context.filesDir, "playback_%04d.log".format(n)))
            }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd' Time 'HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            logFile.appendText("# Started Ver. SlopBox $stamp\n")
        } catch (e: Exception) {
            Log.w(TAG, "playback log", e)
        }
    }

    /** Rockbox add_playbacklog(): "timestamp:elapsed:length:path" for tracks played > 0.5 s. */
    fun log(path: String, elapsedMs: Long, lengthMs: Long) {
        if (elapsedMs <= LOG_MIN_ELAPSED_MS) return
        try {
            logFile.appendText("${System.currentTimeMillis() / 1000}:$elapsedMs:$lengthMs:$path\n")
        } catch (e: Exception) {
            Log.w(TAG, "playback log", e)
        }
    }

    companion object {
        private const val TAG = "PlaybackMemory"
        private const val LOG_MAX_BYTES = 511L shl 10
        private const val LOG_MIN_ELAPSED_MS = 500L

        /** A Rockbox-style path for a track: "/Music/Album/01.flac" for storage URIs. */
        fun pathOf(uri: Uri): String {
            if (uri.scheme == "file") return uri.path ?: uri.toString()
            val doc = runCatching { android.provider.DocumentsContract.getDocumentId(uri) }.getOrNull()
                ?: return uri.toString()
            return "/" + doc.substringAfter(':')
        }
    }
}
