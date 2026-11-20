package llm.slop.slopbox.plugins

import java.io.File

/**
 * Rockbox's lastfm_scrobbler plugin (apps/plugins/lastfm_scrobbler.c): turns
 * the playback log (playback.log, playback_0001.log, ...) into an
 * Audioscrobbler 1.1 .scrobbler.log, with its duplicate removal and
 * thresholds. The pure parts are here; reading tags and files is passed in.
 */
object Scrobbler {
    const val SCROBBLER_VERSION = "1.1"
    const val ITEM_HDR = "#ARTIST\t#ALBUM\t#TITLE\t#TRACKNUM\t#LENGTH\t#RATING\t#TIMESTAMP\t#MUSICBRAINZ_TRACKID\n"
    const val UNTAGGED = "<UNTAGGED>"
    const val MIN_ELAPSED_MS = 500

    /** gConfig, with config_set_defaults(). */
    class Config(
        var savepct: Int = 50,
        var minms: Int = MIN_ELAPSED_MS,
        /** 0 Save All, 1 Skip if Missing, 2 Filename Only */
        var tracknfo: Int = 0,
        /** 0 Save All, 1 No Resume Duplicates, 2 No Duplicates */
        var removeDup: Int = 0,
        var deleteLog: Boolean = true,
    ) {
        fun copy() = Config(savepct, minms, tracknfo, removeDup, deleteLog)
        override fun equals(other: Any?) = other is Config && other.savepct == savepct && other.minms == minms &&
            other.tracknfo == tracknfo && other.removeDup == removeDup && other.deleteLog == deleteLog
        override fun hashCode() = savepct

        companion object {
            val TRACKNFO = listOf("Save All", "Skip if Missing", "Filename Only")
            val DUPS = listOf("Save All", "No Resume Duplicates", "No Duplicates")

            /** configfile_load(): "name: value" lines. */
            fun load(f: File): Config? {
                val text = runCatching { f.readText() }.getOrNull() ?: return null
                val c = Config()
                for (line in text.lines()) {
                    val i = line.indexOf(':').takeIf { it > 0 } ?: continue
                    val k = line.substring(0, i).trim(); val v = line.substring(i + 1).trim()
                    when (k) {
                        "TrackInfo" -> TRACKNFO.indexOf(v).takeIf { it >= 0 }?.let { c.tracknfo = it }
                        "RemoveDupes" -> DUPS.indexOf(v).takeIf { it >= 0 }?.let { c.removeDup = it }
                        "DeleteLog" -> c.deleteLog = v == "yes"
                        "SavePct" -> v.toIntOrNull()?.let { c.savepct = it.coerceIn(0, 100) }
                        "MinMs" -> v.toIntOrNull()?.let { c.minms = it.coerceIn(MIN_ELAPSED_MS, 10000) }
                    }
                }
                return c
            }
        }

        fun save(f: File) = runCatching {
            f.writeText("TrackInfo: ${TRACKNFO[tracknfo]}\nRemoveDupes: ${DUPS[removeDup]}\n" +
                "DeleteLog: ${if (deleteLog) "yes" else "no"}\nSavePct: $savepct\nMinMs: $minms\n")
        }.isSuccess
    }

    class Entry(val timestamp: Long, val elapsed: Long, val length: Long, val path: String)

    /** C-ish atoul over leading junk, as pbl_parse_atoul_wlen(). */
    private fun atoul(s: String): Long {
        var v = 0L
        for (c in s) {
            val d = c - '0'
            if (d in 0..9) v = v * 10 + d else if (v != 0L) break
        }
        return v
    }

    /** pbl_parse_entry(): "timestamp:elapsed:length:path"; null if invalid. */
    fun parseEntry(line: String): Entry? {
        val a = line.indexOf(':'); if (a < 0) return null
        val b = line.indexOf(':', a + 1); if (b < 0) return null
        val c = line.indexOf(':', b + 1); if (c < 0 || c + 1 >= line.length) return null
        val e = Entry(atoul(line.substring(0, a)), atoul(line.substring(a + 1, b)), atoul(line.substring(b + 1, c)), line.substring(c + 1))
        if (e.length == 0L || e.elapsed > e.length) return null
        return e
    }

    /**
     * pbl_copyloop() + pbl_remove_duplicates(): the parsed lines (' ' kept,
     * '!' too short, '@' duplicate, '#' comment) as the plugin's
     * playback_old.log has them, and the entries to scrobble.
     */
    class Parsed(val lines: List<String>, val keep: List<Entry>)

    fun parse(logs: List<String>, cfg: Config): Parsed {
        val marks = mutableListOf<Char>()
        val entries = mutableListOf<Entry?>()
        val text = mutableListOf<String>()
        for (log in logs) for (raw in log.lines()) {
            if (raw.isEmpty()) continue
            if (raw.startsWith("#")) { marks += '#'; entries += null; text += raw; continue }
            val e = parseEntry(raw) ?: continue
            marks += if (e.elapsed < cfg.minms) '!' else ' '
            entries += e
            text += "${e.timestamp}:${e.elapsed}:${e.length}:${e.path}"
        }
        if (cfg.removeDup > 0) {
            // Line numbers count only ' ' entries, as pbl_cull_duplicates() does.
            for (i in entries.indices) {
                if (marks[i] != ' ') continue
                val cur = entries[i]!!
                var lineNum = 0
                var curIsDup = false
                val curLine = (0..i).count { marks[it] == ' ' || marks[it] == '@' }
                lineNum = curLine
                for (j in i + 1 until entries.size) {
                    if (marks[j] != ' ') continue
                    lineNum++
                    val cmp = entries[j]!!
                    if (cur.length != cmp.length || cur.path != cmp.path) continue
                    val b2b = cfg.removeDup == 2 || curLine + 1 == lineNum
                    if (b2b || (cur.timestamp <= cmp.timestamp + cur.length && cmp.timestamp <= cur.timestamp + cur.length)) {
                        if (cur.elapsed >= cmp.elapsed) marks[j] = '@'
                        else { curIsDup = true; break }
                    }
                }
                if (curIsDup) marks[i] = '@'
            }
        }
        return Parsed(text.mapIndexed { i, t -> if (marks[i] == '#') t else marks[i] + t },
            entries.filterIndexed { i, e -> e != null && marks[i] == ' ' }.map { it!! })
    }

    /** What sbl_create_entry() needs from a track's tags. */
    class Tags(val artist: String?, val albumArtist: String?, val album: String?, val title: String?,
               val trackNum: Int, val mbTrackId: String?)

    /** sbl_create_entry(): one log line, or null to skip (Skip if Missing). */
    fun line(e: Entry, tags: Tags?, cfg: Config): Pair<String?, Boolean> {
        val name = e.path.substringAfterLast('/')
        val rating = if (e.elapsed >= e.length / 100 * cfg.savepct) 'L' else 'S'
        fun clean(s: String?, alt: String) = if (s.isNullOrEmpty()) alt else s.replace('\t', ' ')
        if (cfg.tracknfo == 2 || tags == null) {
            if (cfg.tracknfo == 1) return null to true
            return "$UNTAGGED\t\t$name\t-1\t${e.length / 1000}\t$rating\t${e.timestamp}\t\n" to true
        }
        val track = if (tags.trackNum < -1) -1 else tags.trackNum
        val artist = tags.artist ?: tags.albumArtist
        return ("${clean(artist, UNTAGGED)}\t${clean(tags.album, "")}\t${clean(tags.title, name)}\t" +
            "$track\t${e.length / 1000}\t$rating\t${e.timestamp}\t${clean(tags.mbTrackId, "")}\n") to false
    }

    /** sbl_check_or_open(): the header of a new .scrobbler.log. */
    fun header(client: String) = "#AUDIOSCROBBLER/$SCROBBLER_VERSION\n#TZ/UNKNOWN\n#CLIENT/$client\n$ITEM_HDR"
}
