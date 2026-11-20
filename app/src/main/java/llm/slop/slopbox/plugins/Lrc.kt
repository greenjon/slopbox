package llm.slop.slopbox.plugins

import java.nio.charset.Charset

/**
 * Lyrics as Rockbox's lrcplayer plugin reads them (apps/plugins/lrcplayer.c):
 * .lrc / .lrc8 (time tags, several per line, and <word> time tags), .snc,
 * plain .txt, and ID3v2 SYLT / USLT frames.
 */
object Lrc {
    enum class Type(val ext: String?) { LRC(".lrc"), LRC8(".lrc8"), SNC(".snc"), TXT(".txt"), ID3_SYLT(null), ID3_USLT(null) }

    /** One word (or the whole line, for unsynced lyrics); [timeStart] -1 = the line's start. */
    class Word(val timeStart: Long, val text: String)

    class Line(var timeStart: Long, val oldTimeStart: Long, val words: List<Word>) {
        val text: String get() = words.joinToString("") { it.text }
    }

    class Lyrics(
        val type: Type,
        val lines: List<Line>,
        val title: String? = null,
        val artist: String? = null,
        /** [offset:] in ms, added to every time (get_time_start()). */
        val offset: Long = 0,
    ) {
        /** Unsynced lyrics don't wipe (lrc_main()). */
        val synced: Boolean get() = type != Type.TXT && type != Type.ID3_USLT

        /** get_time_start(): with the offset, never below 0; past the end = length + 20 ms. */
        fun timeOf(i: Int, length: Long): Long {
            val l = lines.getOrNull(i) ?: return length + 20
            return (l.timeStart + offset).coerceAtLeast(0)
        }

        fun wordTime(t: Long): Long = (t + offset).coerceAtLeast(0)
    }

    // ---- .lrc ----------------------------------------------------------------

    private class TagResult(val time: Long, val title: String? = null, val artist: String? = null, val offset: Long? = null)

    /**
     * get_time_value(): "mm:ss", "mm:ss.xx", "mm:ss.xxx" (also ':' before the
     * fraction) -> ms; with [readIdTags], ti:, ar: and offset: too (-1).
     * -10 for anything else.
     */
    private fun timeValue(tag: String, readIdTags: Boolean): TagResult {
        if (readIdTags) {
            if (tag.startsWith("ti:")) return TagResult(-1, title = tag.substring(3))
            if (tag.startsWith("ar:")) return TagResult(-1, artist = tag.substring(3))
            if (tag.startsWith("offset:")) return TagResult(-1, offset = atoi(tag.substring(7)).toLong())
        }
        var i = 0
        fun digits(): Pair<Int, Int> {
            val s = i
            while (i < tag.length && tag[i].isDigit()) i++
            return (if (i > s) tag.substring(s, i).toInt() else 0) to (i - s)
        }
        val (mm, ml) = digits()
        if (ml < 1 || ml > 2 || i >= tag.length || tag[i] != ':') return TagResult(-10)
        var time = mm * 60000L
        i++
        val (ss, sl) = digits()
        if (sl != 2 || (i < tag.length && tag[i] != '.' && tag[i] != ':')) return TagResult(-10)
        time += ss * 1000L
        if (i < tag.length) {
            i++
            val (ms, msl) = digits()
            if (msl < 2 || msl > 3 || i != tag.length) return TagResult(-10)
            time += if (msl == 3) ms.toLong() else ms * 10L
        }
        return TagResult(time)
    }

    /** C atoi(): optional sign, digits, stop at anything else. */
    private fun atoi(s: String): Int {
        val t = s.trimStart()
        var i = 0
        var neg = false
        if (i < t.length && (t[i] == '-' || t[i] == '+')) { neg = t[i] == '-'; i++ }
        var v = 0
        while (i < t.length && t[i].isDigit()) { v = v * 10 + (t[i] - '0'); i++ }
        return if (neg) -v else v
    }

    /** parse_lrc_line() over a whole file. */
    fun parseLrc(text: String, type: Type = Type.LRC): Lyrics {
        val lines = mutableListOf<Line>()
        var title: String? = null
        var artist: String? = null
        var offset = 0L
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r')
            val times = mutableListOf<Long>()
            var str = 0
            while (str < line.length && line[str] == '[') {
                val end = line.indexOf(']', str)
                if (end < 0) break
                val r = timeValue(line.substring(str + 1, end), times.isEmpty())
                r.title?.let { title = it }
                r.artist?.let { artist = it }
                r.offset?.let { offset = it }
                if (r.time < 0) break
                times += (r.time / 10) * 10
                str = end + 1
            }
            if (times.isEmpty()) continue
            // <word time tags>
            val words = mutableListOf<Word>()
            var rest = line.substring(str)
            var timeStart = -1L
            var pos = 0
            while (pos < rest.length) {
                val ts = rest.indexOf('<', pos)
                if (ts < 0) break
                val te = rest.indexOf('>', ts)
                if (te < 0) break
                val r = timeValue(rest.substring(ts + 1, te), false)
                if (r.time < 0) { pos = ts + 1; continue }
                val word = rest.substring(0, ts)
                if (word.isNotEmpty() || timeStart != -1L) words += Word(timeStart, word)
                rest = rest.substring(te + 1)
                pos = 0
                timeStart = r.time
            }
            words += Word(timeStart, rest)
            for (t in times) lines += Line(t, t, words)
        }
        return Lyrics(type, sort(lines), title, artist, offset)
    }

    /** sort_lrcs(): by time, keeping file order for equal times. */
    private fun sort(lines: List<Line>): List<Line> =
        lines.filter { it.words.isNotEmpty() }.sortedBy { it.timeStart }

    // ---- .snc ----------------------------------------------------------------

    /**
     * parse_snc_line(): "\xa2\xe2hhmmssxx\xa2\xd0" starts a timed block; the
     * text lines after it (and any text after the tag) belong to it.
     */
    fun parseSnc(bytes: ByteArray, charset: Charset): Lyrics {
        val lines = mutableListOf<Line>()
        var cur: Pair<Long, StringBuilder>? = null
        fun flush() { cur?.let { (t, sb) -> lines += Line(t, t, listOf(Word(-1, sb.toString()))) } }
        for (raw in splitLines(bytes)) {
            if (raw.size >= 12 && raw[0] == 0xa2.toByte() && raw[1] == 0xe2.toByte() &&
                raw[10] == 0xa2.toByte() && raw[11] == 0xd0.toByte()) {
                fun two(i: Int) = (raw[i] - '0'.code.toByte()) * 10 + (raw[i + 1] - '0'.code.toByte())
                flush()
                val t = two(2) * 3600000L + two(4) * 60000L + two(6) * 1000L + two(8) * 10L
                val sb = StringBuilder()
                if (raw.size > 12) sb.append(String(raw, 12, raw.size - 12, charset)).append('\n')
                cur = t to sb
            } else if (cur != null) {
                cur!!.second.append(String(raw, charset)).append('\n')
            }
        }
        flush()
        return Lyrics(Type.SNC, sort(lines))
    }

    private fun splitLines(b: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var s = 0
        for (i in b.indices) if (b[i] == '\n'.code.toByte()) {
            var e = i
            if (e > s && b[e - 1] == '\r'.code.toByte()) e--
            out += b.copyOfRange(s, e); s = i + 1
        }
        if (s < b.size) out += b.copyOfRange(s, b.size)
        return out
    }

    // ---- .txt ----------------------------------------------------------------

    /** parse_txt_line(): one untimed line each (times are set from the track length). */
    fun parseTxt(text: String): Lyrics =
        Lyrics(Type.TXT, text.split('\n').map { Line(0, -1, listOf(Word(-1, it.trimEnd('\r')))) })

    /**
     * init_time_tag() for unsynced lyrics: spread the lines over the track
     * so they scroll at a steady speed.
     */
    fun spread(l: Lyrics, lengthMs: Long): Lyrics {
        if (l.synced || l.lines.isEmpty()) return l
        val n = l.lines.size
        l.lines.forEachIndexed { i, line ->
            val t = i * lengthMs / n
            line.timeStart = t - t % 10
        }
        return l
    }

    // ---- file loading ------------------------------------------------------

    /** load_lrc_file(): BOMs (UTF-8, UTF-16LE/BE) win; .lrc8 is UTF-8; else [charset]. */
    fun load(bytes: ByteArray, type: Type, charset: Charset): Lyrics {
        var cs = if (type == Type.LRC8) Charsets.UTF_8 else charset
        var start = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            cs = Charsets.UTF_8; start = 3
        } else if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            cs = Charsets.UTF_16LE; start = 2
        } else if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            cs = Charsets.UTF_16BE; start = 2
        }
        val body = bytes.copyOfRange(start, bytes.size)
        return when (type) {
            Type.LRC, Type.LRC8 -> parseLrc(String(body, cs), type)
            Type.SNC -> if (cs == Charsets.UTF_16LE || cs == Charsets.UTF_16BE)
                parseSnc(String(body, cs).toByteArray(Charsets.UTF_8), Charsets.UTF_8) else parseSnc(body, cs)
            else -> parseTxt(String(body, cs))
        }
    }

    // ---- ID3v2 SYLT / USLT ----------------------------------------------------

    /**
     * parse_id3v2(): the first SYLT or USLT frame of an MP3's ID3v2 tag
     * (v2.2, 2.3, 2.4; unsynchronisation handled), as lyrics.
     */
    fun fromId3(bytes: ByteArray, defaultCharset: Charset): Lyrics? {
        if (bytes.size < 10 || bytes[0] != 'I'.code.toByte() || bytes[1] != 'D'.code.toByte() || bytes[2] != '3'.code.toByte()) return null
        val version = bytes[3].toInt()
        val flags = bytes[5].toInt()
        fun syncsafe(i: Int) = ((bytes[i].toInt() and 0x7f) shl 21) or ((bytes[i + 1].toInt() and 0x7f) shl 14) or
            ((bytes[i + 2].toInt() and 0x7f) shl 7) or (bytes[i + 3].toInt() and 0x7f)
        val size = syncsafe(6)
        var tag = bytes.copyOfRange(10, minOf(bytes.size, 10 + size))
        val globalUnsync = flags and 0x80 != 0
        if (globalUnsync && version <= 3) tag = unsync(tag)
        var p = 0
        if (flags and 0x40 != 0 && version >= 3) {        // extended header
            val ext = if (version == 3) ((tag[0].toInt() and 0xff) shl 24 or ((tag[1].toInt() and 0xff) shl 16) or
                ((tag[2].toInt() and 0xff) shl 8) or (tag[3].toInt() and 0xff)) + 4
                else (((tag[0].toInt() and 0x7f) shl 21) or ((tag[1].toInt() and 0x7f) shl 14) or
                ((tag[2].toInt() and 0x7f) shl 7) or (tag[3].toInt() and 0x7f))
            p += ext
        }
        val idLen = if (version == 2) 3 else 4
        val hdrLen = if (version == 2) 6 else 10
        while (p + hdrLen <= tag.size) {
            val id = String(tag, p, idLen, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val fsize = when (version) {
                2 -> ((tag[p + 3].toInt() and 0xff) shl 16) or ((tag[p + 4].toInt() and 0xff) shl 8) or (tag[p + 5].toInt() and 0xff)
                3 -> ((tag[p + 4].toInt() and 0xff) shl 24) or ((tag[p + 5].toInt() and 0xff) shl 16) or
                    ((tag[p + 6].toInt() and 0xff) shl 8) or (tag[p + 7].toInt() and 0xff)
                else -> ((tag[p + 4].toInt() and 0x7f) shl 21) or ((tag[p + 5].toInt() and 0x7f) shl 14) or
                    ((tag[p + 6].toInt() and 0x7f) shl 7) or (tag[p + 7].toInt() and 0x7f)
            }
            val fflags = if (version >= 3) ((tag[p + 8].toInt() and 0xff) shl 8) or (tag[p + 9].toInt() and 0xff) else 0
            val start = p + hdrLen
            val end = minOf(tag.size, start + fsize)
            if (fsize <= 0 || start >= tag.size) break
            val isSylt = id == "SYLT" || id == "SLT"
            val isUslt = id == "USLT" || id == "ULT"
            if (isSylt || isUslt) {
                var data = tag.copyOfRange(start, end)
                if (version >= 4) {
                    if (fflags and 0x0001 != 0 && data.size >= 4) data = data.copyOfRange(4, data.size)  // data length
                    if (fflags and 0x0002 != 0 || globalUnsync) data = unsync(data)
                }
                return frame(data, isSylt, defaultCharset)
            }
            p = end
        }
        return null
    }

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

    /** The SYLT/USLT frame body: encoding, language, (SYLT: format, type), descriptor, text. */
    private fun frame(d: ByteArray, sylt: Boolean, defaultCharset: Charset): Lyrics? {
        if (d.isEmpty()) return null
        val enc = d[0].toInt()
        var p = if (sylt) 6 else 4
        val wide = enc == 1 || enc == 2
        val cs: Charset = when (enc) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> defaultCharset
        }
        // Skip the content descriptor (terminated by 0 or 00 00).
        p = skipString(d, p, wide)
        val lines = mutableListOf<Line>()
        if (sylt) {
            var bomCs = cs
            while (p < d.size) {
                val e = findTerm(d, p, wide)
                var text = decode(d, p, e, bomCs)
                if (enc == 1 && p + 1 < d.size) {
                    if (d[p] == 0xFF.toByte() && d[p + 1] == 0xFE.toByte()) bomCs = Charsets.UTF_16LE
                    else if (d[p] == 0xFE.toByte() && d[p + 1] == 0xFF.toByte()) bomCs = Charsets.UTF_16BE
                }
                p = e + (if (wide) 2 else 1)
                if (p + 4 > d.size) break
                val t = ((d[p].toLong() and 0xff) shl 24) or ((d[p + 1].toLong() and 0xff) shl 16) or
                    ((d[p + 2].toLong() and 0xff) shl 8) or (d[p + 3].toLong() and 0xff)
                p += 4
                if (text.startsWith("\n")) text = text.substring(1)
                lines += Line(t, t, listOf(Word(-1, text.trimStart('﻿'))))
            }
            return Lyrics(Type.ID3_SYLT, sort(lines))
        }
        val text = decode(d, p, d.size, cs).trimEnd('\u0000')
        text.split("\r\n", "\n", "\r").forEach { lines += Line(0, -1, listOf(Word(-1, it.trimStart('﻿')))) }
        return Lyrics(Type.ID3_USLT, lines)
    }

    private fun findTerm(d: ByteArray, from: Int, wide: Boolean): Int {
        var i = from
        if (wide) {
            while (i + 1 < d.size && !(d[i] == 0.toByte() && d[i + 1] == 0.toByte())) i += 2
            return minOf(i, d.size)
        }
        while (i < d.size && d[i] != 0.toByte()) i++
        return i
    }

    private fun skipString(d: ByteArray, from: Int, wide: Boolean): Int =
        findTerm(d, from, wide) + if (wide) 2 else 1

    private fun decode(d: ByteArray, from: Int, to: Int, cs: Charset): String =
        if (from >= to || from >= d.size) "" else String(d, from, minOf(to, d.size) - from, cs)

    /** Rockbox's codepages, in the "Default Codepage" setting's order, as Java charsets. */
    val CODEPAGES = listOf("ISO-8859-1", "ISO-8859-7", "ISO-8859-8", "windows-1251", "x-iso-8859-11", "windows-1256",
        "ISO-8859-9", "ISO-8859-2", "windows-1250", "windows-1252", "Shift_JIS", "GB2312", "EUC-KR", "Big5", "UTF-8")

    fun charsetFor(codepage: Int): Charset =
        runCatching { Charset.forName(CODEPAGES[codepage]) }.getOrDefault(Charsets.ISO_8859_1)
}
