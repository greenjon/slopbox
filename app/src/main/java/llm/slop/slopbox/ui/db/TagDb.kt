/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/tagcache.c - Copyright (C) 2005 by Miika Pekkarinen
 *   apps/tagtree.c - Copyright (C) 2005 by Miika Pekkarinen
 * and the other Rockbox contributors (third_party/rockbox/docs/CREDITS).
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
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
 * Rockbox's runtime statistics for one database entry (tagcache's numeric
 * tags playcount, rating, playtime, lastplayed). [dirty] is Rockbox's
 * FLAG_DIRTYNUM: changed since the database was built, so exported.
 */
class Stats(
    var playcount: Int = 0,
    var rating: Int = 0,
    var playtime: Long = 0,
    var lastplayed: Long = 0,
    var dirty: Boolean = false,
)

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
    private const val VERSION = 2

    var tracks by mutableStateOf<List<Track>>(emptyList())
        private set
    /** Number of scans so far (Rockbox's commit id). */
    var commits by mutableIntStateOf(0)
        private set
    var loaded by mutableStateOf(false)
        private set
    /** Runtime statistics by document id (Rockbox keeps them in the master index). */
    private val stats = HashMap<String, Stats>()
    /** Rockbox's tagcache serial: what "lastplayed" counts in. */
    private var serial = 0L
    /** Bumped when statistics change, so database lists are worked out again. */
    var statsRevision by mutableIntStateOf(0)
        private set

    /** A copy of a track's statistics (zeros when it has none). */
    fun statsOf(id: String): Stats = synchronized(stats) {
        stats[id]?.let { Stats(it.playcount, it.rating, it.playtime, it.lastplayed, it.dirty) } ?: Stats()
    }

    fun contains(id: String) = tracks.any { it.id == id }

    /** Rockbox LANG_BUILDING_DATABASE, without the button hint. */
    private fun building(n: Int) = "Building database... $n found"

    /** Non-null while a scan runs: "Building database... 120 found". */
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
    private var scanFull = false

    /**
     * Rockbox tagcache_rebuild() / tagcache_update(): a scan in the
     * background. An update while one runs is already covered; Initialize
     * while an update runs starts again as a full rebuild (Rockbox queues it).
     */
    fun scan(ctx: Context, tree: Uri, full: Boolean) {
        val running = scanJob
        if (running?.isActive == true) {
            if (!full || scanFull) return
            running.cancel()
        }
        val app = ctx.applicationContext
        scanFull = full
        scanJob = scope.launch {
            running?.join()
            progress = building(0)
            try {
                // Rockbox "Initialize Now" starts a new database (statistics too);
                // "Update Now" keeps what it can.
                val old = if (full) emptyMap() else tracks.associateBy { it.id }
                val commit = if (full) 1 else commits + 1
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
                        if (seen % 10 == 0) progress = building(seen)
                        val prev = old[e.id]
                        out += if (prev != null && prev.modified == e.modified && prev.size == e.size) prev
                        else readTags(app, tree, e, dirId, dirName, "$path/${e.name}", prev?.commitId ?: commit)
                    }
                }
                val rootId = MusicTree.rootId(tree)
                walk(rootId, "", "")
                if (!isActive) return@launch
                synchronized(stats) {
                    if (full) { stats.clear(); serial = 0 }
                    else resurrect(old, out)
                    stats.keys.retainAll(out.mapTo(HashSet()) { it.id })
                }
                tracks = out
                commits = commit
                write(File(app.filesDir, FILE))
                statsRevision++
            } catch (e: Exception) {
                Log.w(TAG, "scan failed", e)
            } finally {
                progress = null
            }
        }
    }

    /**
     * Rockbox build_index(): statistics of files that disappeared go to new
     * entries that match them: same length, and the same file name or the
     * same artist, album and title. Each old entry is used once.
     */
    private fun resurrect(old: Map<String, Track>, out: MutableList<Track>) {
        val seen = out.mapTo(HashSet()) { it.id }
        val deleted = old.values.filter { it.id !in seen && stats.containsKey(it.id) }.toMutableList()
        if (deleted.isEmpty()) return
        for ((i, t) in out.withIndex()) {
            if (t.id in old) continue
            val m = deleted.firstOrNull { d ->
                d.lengthMs == t.lengthMs &&
                    (d.path == t.path || (d.artist == t.artist && d.album == t.album && d.title == t.title))
            } ?: continue
            deleted.remove(m)
            stats[m.id]?.let { stats[t.id] = it }
            out[i] = t.copyWithCommit(m.commitId)
        }
    }

    private fun Track.copyWithCommit(c: Int) = Track(id, dir, dirName, path, title, artist, album, albumArtist,
        genre, composer, year, trackNum, discNum, lengthMs, bitrate, modified, size, c)

    // ---- Runtime data (Rockbox tagtree.c tagtree_track_finish_event()) ----------

    /**
     * A track stopped playing. With "Gather Runtime Data" on and the track in
     * the database: unless it never played or was left within 15 s by hand,
     * one more play, the time played (all but the last 15 s, up to its length)
     * and the new last-played serial.
     */
    fun trackFinished(ctx: Context, id: String, elapsedMs: Long, lengthMs: Long, autoSkip: Boolean) {
        if (!loaded) return
        if (llm.slop.slopbox.SettingsStore.get(ctx).int("gather runtime data") == 0) return
        if (elapsedMs == 0L || (elapsedMs < 15_000 && !autoSkip)) return
        if (!contains(id)) return
        synchronized(stats) {
            val st = stats.getOrPut(id) { Stats() }
            st.playcount++
            st.playtime += minOf(lengthMs, elapsedMs + 15_000)
            st.lastplayed = serial++
            st.dirty = true
        }
        changed(ctx)
    }

    /** Rockbox set_rating_inline(): 0 to 10. */
    fun setRating(ctx: Context, id: String, rating: Int) {
        if (!contains(id)) return
        synchronized(stats) {
            val st = stats.getOrPut(id) { Stats() }
            st.rating = rating.coerceIn(0, 10)
            st.dirty = true
        }
        changed(ctx)
    }

    private fun changed(ctx: Context) {
        statsRevision++
        val app = ctx.applicationContext
        scope.launch { runCatching { write(File(app.filesDir, FILE)) } }
    }

    /**
     * Rockbox tagcache_create_changelog() ("Export Modifications"): every
     * entry with changed statistics, one line each, all tags as name="value".
     */
    fun changelog(ctx: Context): String {
        val sb = StringBuilder("## Changelog version 1\n")
        val memory = llm.slop.slopbox.PlaybackMemory(ctx)
        val tree = MusicTree.root(ctx)
        val volumePath = { id: String -> "/" + id.substringAfter(':') }
        synchronized(stats) {
            for (t in tracks) {
                val st = stats[t.id] ?: continue
                if (!st.dirty) continue
                fun tag(n: String, v: String) {
                    sb.append(n).append("=\"")
                    for (c in v) when (c) {
                        '"', '\\' -> sb.append('\\').append(c)
                        '\n' -> sb.append("\\n")
                        else -> sb.append(c)
                    }
                    sb.append("\" ")
                }
                val lastElapsed = tree?.let { memory.resumePoint(MusicTree.docUri(it, t.id).toString()) } ?: 0L
                tag("artist", t.artist); tag("album", t.album); tag("genre", t.genre); tag("title", t.title)
                tag("filename", volumePath(t.id)); tag("composer", t.composer); tag("comment", "")
                tag("albumartist", t.albumArtist); tag("grouping", ""); tag("year", t.year.toString())
                tag("discnumber", t.discNum.toString()); tag("tracknumber", t.trackNum.toString())
                tag("canonicalartist", t.artist.ifEmpty { t.albumArtist }); tag("bitrate", t.bitrate.toString())
                tag("length", t.lengthMs.toString()); tag("playcount", st.playcount.toString())
                tag("rating", st.rating.toString()); tag("playtime", st.playtime.toString())
                tag("lastplayed", st.lastplayed.toString()); tag("commitid", t.commitId.toString())
                tag("mtime", fatTime(t.modified).toString()); tag("lastelapsed", lastElapsed.toString())
                tag("lastoffset", "0")
                sb.append('\n')
            }
        }
        return sb.toString()
    }

    /** FAT date/time (Rockbox's mtime): date in the high 16 bits, time in the low. */
    private fun fatTime(ms: Long): Long {
        if (ms <= 0) return 0
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        val y = c.get(java.util.Calendar.YEAR) - 1980
        if (y < 0) return 0
        val date = (y shl 9) or ((c.get(java.util.Calendar.MONTH) + 1) shl 5) or c.get(java.util.Calendar.DAY_OF_MONTH)
        val time = (c.get(java.util.Calendar.HOUR_OF_DAY) shl 11) or (c.get(java.util.Calendar.MINUTE) shl 5) or
            (c.get(java.util.Calendar.SECOND) / 2)
        return (date.toLong() shl 16) or time.toLong()
    }

    /**
     * Rockbox tagcache_import_changelog() ("Import Modifications"): for each
     * line whose filename is in the database and not changed here yet, take
     * its playcount, rating, playtime, lastplayed, commitid and lastelapsed.
     * Returns how many entries were updated.
     */
    fun importChangelog(ctx: Context, text: String): Int {
        val byPath = tracks.associateBy { "/" + it.id.substringAfter(':') }
        val memory = llm.slop.slopbox.PlaybackMemory(ctx)
        val tree = MusicTree.root(ctx)
        var n = 0
        val newCommits = HashMap<String, Int>()
        synchronized(stats) {
            for (line in text.lineSequence()) {
                if (line.startsWith("#") || line.isBlank()) continue
                val tags = parseChangelogLine(line)
                val t = byPath[tags["filename"] ?: continue] ?: continue
                val st = stats.getOrPut(t.id) { Stats() }
                if (st.dirty) continue
                st.dirty = true
                fun num(k: String) = tags[k]?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
                num("playcount")?.let { st.playcount = it.toInt() }
                num("rating")?.let { st.rating = it.toInt() }
                num("playtime")?.let { st.playtime = it }
                num("lastplayed")?.let { st.lastplayed = it; if (it >= serial) serial = it + 1 }
                num("commitid")?.let { newCommits[t.id] = it.toInt() }
                num("lastelapsed")?.let { e -> tree?.let { memory.setResumePoint(MusicTree.docUri(it, t.id).toString(), e) } }
                n++
            }
        }
        if (newCommits.isNotEmpty()) {
            tracks = tracks.map { t -> newCommits[t.id]?.let { t.copyWithCommit(it) } ?: t }
            val maxCommit = newCommits.values.max()
            if (maxCommit >= commits) commits = maxCommit + 1
        }
        changed(ctx)
        return n
    }

    /** Rockbox read_tag(): name="value" pairs, with \" \\ and \n escapes. */
    fun parseChangelogLine(line: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        while (i < line.length) {
            while (i < line.length && line[i] == ' ') i++
            if (i >= line.length) break
            val start = i
            while (i < line.length && line[i] != '=' && line[i] != ' ') i++
            val name = line.substring(start, i).lowercase()
            while (i < line.length && line[i] != '"') i++
            if (i >= line.length - 1) break
            i++
            val v = StringBuilder()
            while (i < line.length) {
                val c = line[i]
                if (c == '\\' && i + 1 < line.length) {
                    v.append(if (line[i + 1] == 'n') '\n' else line[i + 1]); i += 2; continue
                }
                i++
                if (c == '"') break
                v.append(c)
            }
            out[name] = v.toString()
        }
        return out
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

    @Synchronized
    private fun write(f: File) {
        val tmp = File(f.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { o ->
            o.writeInt(VERSION)
            o.writeInt(commits)
            o.writeLong(serial)
            o.writeInt(tracks.size)
            for (t in tracks) {
                for (s in listOf(t.id, t.dir, t.dirName, t.path, t.title, t.artist, t.album, t.albumArtist, t.genre, t.composer)) {
                    o.writeUTF(s.take(16000))
                }
                o.writeInt(t.year); o.writeInt(t.trackNum); o.writeInt(t.discNum)
                o.writeLong(t.lengthMs); o.writeInt(t.bitrate)
                o.writeLong(t.modified); o.writeLong(t.size); o.writeInt(t.commitId)
                val st = synchronized(stats) { stats[t.id] } ?: Stats()
                o.writeInt(st.playcount); o.writeInt(st.rating); o.writeLong(st.playtime)
                o.writeLong(st.lastplayed); o.writeBoolean(st.dirty)
            }
        }
        tmp.renameTo(f)
    }

    private fun read(f: File) {
        DataInputStream(f.inputStream().buffered()).use { i ->
            val version = i.readInt()
            if (version != 1 && version != VERSION) return
            val c = i.readInt()
            val ser = if (version >= 2) i.readLong() else 0L
            val n = i.readInt()
            val list = ArrayList<Track>(n)
            val st = HashMap<String, Stats>()
            repeat(n) {
                val s = Array(10) { i.readUTF() }
                val t = Track(
                    s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], s[8], s[9],
                    i.readInt(), i.readInt(), i.readInt(), i.readLong(), i.readInt(),
                    i.readLong(), i.readLong(), i.readInt(),
                )
                list += t
                if (version >= 2) {
                    val x = Stats(i.readInt(), i.readInt(), i.readLong(), i.readLong(), i.readBoolean())
                    if (x.playcount != 0 || x.rating != 0 || x.playtime != 0L || x.dirty) st[t.id] = x
                }
            }
            synchronized(stats) { stats.clear(); stats.putAll(st); serial = ser }
            commits = c
            tracks = list
        }
    }

    suspend fun clear(ctx: Context) = withContext(Dispatchers.IO) {
        scanJob?.cancel()
        File(ctx.filesDir, FILE).delete()
        tracks = emptyList()
        commits = 0
        synchronized(stats) { stats.clear(); serial = 0 }
    }

    private const val TAG = "TagDb"
}
