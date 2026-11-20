package llm.slop.slopbox

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Tags and embedded album art read with Rockbox's own metadata parser
 * (Engine.readTags), so the database and the WPS see exactly what Rockbox
 * would. Safe to call from any thread.
 */
object RockboxTagFile {
    class Result(val tags: Engine.RockboxTags, val art: ByteArray?)

    /** [name] (the file name) picks the parser by extension, as in Rockbox; looked up if null. */
    fun read(ctx: Context, uri: Uri, name: String? = null, withArt: Boolean = false): Result? {
        val pfd = try {
            if (uri.scheme == "file") ParcelFileDescriptor.open(File(uri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
            else ctx.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            Log.w(TAG, "open $uri failed", e)
            null
        } ?: return null
        return pfd.use {
            val fileName = name ?: displayName(ctx, uri)
            val tags = Engine.readTags(it.fd, fileName) ?: return null
            val art = if (withArt && tags.artType != 0 && tags.artSize > 0) readArt(it, tags) else null
            Result(tags, art)
        }
    }

    private fun displayName(ctx: Context, uri: Uri): String =
        if (uri.scheme == "file") File(uri.path!!).name
        else runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: "unknown"

    private fun readArt(pfd: ParcelFileDescriptor, t: Engine.RockboxTags): ByteArray? = try {
        val ch = FileInputStream(pfd.fileDescriptor).channel
        val buf = ByteBuffer.allocate(t.artSize)
        var pos = t.artPos
        while (buf.hasRemaining()) {
            val n = ch.read(buf, pos)
            if (n <= 0) break
            pos += n
        }
        var bytes = buf.array().copyOf(buf.position())
        if (t.artFlags and 1 != 0) bytes = unsync(bytes)
        // Vorbis: Rockbox points at the base64 of the image data itself.
        if (t.artFlags and 2 != 0) bytes = Base64.decode(bytes, Base64.DEFAULT)
        bytes.takeIf { looksLikeImage(it) }
    } catch (e: Exception) {
        Log.w(TAG, "album art read failed", e)
        null
    }

    /** ID3v2 unsynchronisation: FF 00 -> FF. */
    private fun unsync(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1] == 0.toByte()) i++
            i++
        }
        return out.toByteArray()
    }

    private fun looksLikeImage(b: ByteArray) = b.size > 4 && (
        (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) ||               // JPEG
        (b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()) ||           // PNG
        (b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte()))         // BMP

    private const val TAG = "RockboxTagFile"
}
