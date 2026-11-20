package llm.slop.slopbox.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Lyrics parsing as Rockbox's lrcplayer does it. */
class LrcTest {
    @Test fun lrcTagsAndRepeats() {
        val l = Lrc.parseLrc("[ti:Song]\n[ar:Band]\n[offset:-200]\n[00:12.34]Hello\n[00:05.00][00:20.5]Chorus\n[bad]x\nno tag\n[1:02.123]Late")
        assertEquals("Song", l.title)
        assertEquals("Band", l.artist)
        assertEquals(-200L, l.offset)
        // Sorted by time; times rounded down to 10 ms. "[00:20.5]" (one digit) isn't a
        // time tag to Rockbox, so it stops the tags and stays in the text.
        assertEquals(listOf(5000L, 12340L, 62120L), l.lines.map { it.timeStart })
        assertEquals(listOf("[00:20.5]Chorus", "Hello", "Late"), l.lines.map { it.text })
        // get_time_start() adds the offset.
        assertEquals(4800L, l.timeOf(0, 100_000))
    }

    @Test fun repeatedTimeTags() {
        val l = Lrc.parseLrc("[00:05.00][00:20.50]Chorus\n[00:10.00]Verse")
        assertEquals(listOf(5000L, 10000L, 20500L), l.lines.map { it.timeStart })
        assertEquals(listOf("Chorus", "Verse", "Chorus"), l.lines.map { it.text })
    }

    @Test fun wordTags() {
        val l = Lrc.parseLrc("[00:01.00]<00:01.00>One <00:01.50>two <00:02.00>three")
        val w = l.lines.single().words
        assertEquals(listOf("One ", "two ", "three"), w.map { it.text })
        assertEquals(listOf(1000L, 1500L, 2000L), w.map { it.timeStart })
    }

    @Test fun wordTagsWithLeadingText() {
        val l = Lrc.parseLrc("[00:01.00]Lead <00:01.50>rest")
        val w = l.lines.single().words
        assertEquals(listOf("Lead ", "rest"), w.map { it.text })
        assertEquals(listOf(-1L, 1500L), w.map { it.timeStart })
    }

    @Test fun snc() {
        val bytes = byteArrayOf(0xa2.toByte(), 0xe2.toByte()) + "00010203".toByteArray() +
            byteArrayOf(0xa2.toByte(), 0xd0.toByte()) + "\nline a\nline b\n".toByteArray()
        val l = Lrc.parseSnc(bytes, Charsets.UTF_8)
        assertEquals(1 * 60000L + 2000 + 30, l.lines.single().timeStart)
        assertEquals("line a\nline b\n", l.lines.single().text)
    }

    @Test fun txtSpreadsOverTheTrack() {
        val l = Lrc.spread(Lrc.parseTxt("a\nb\nc\nd"), 40_000)
        assertEquals(listOf(0L, 10000L, 20000L, 30000L), l.lines.map { it.timeStart })
    }

    @Test fun bomWinsOverExtension() {
        val l = Lrc.load(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "[00:01.00]Ünï".toByteArray(), Lrc.Type.LRC, Charsets.ISO_8859_1)
        assertEquals("Ünï", l.lines.single().text)
    }

    private fun id3v23(frameId: String, body: ByteArray): ByteArray {
        val frame = frameId.toByteArray() + byteArrayOf(0, 0, (body.size shr 8).toByte(), body.size.toByte(), 0, 0) + body
        val n = frame.size
        val hdr = "ID3".toByteArray() + byteArrayOf(3, 0, 0, ((n shr 21) and 0x7f).toByte(), ((n shr 14) and 0x7f).toByte(),
            ((n shr 7) and 0x7f).toByte(), (n and 0x7f).toByte())
        return hdr + frame
    }

    @Test fun id3Uslt() {
        val body = byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0) + "first\nsecond".toByteArray()
        val l = Lrc.fromId3(id3v23("USLT", body), Charsets.ISO_8859_1)
        assertNotNull(l); l!!
        assertEquals(Lrc.Type.ID3_USLT, l!!.type)
        assertEquals(listOf("first", "second"), l.lines.map { it.text })
    }

    @Test fun id3Sylt() {
        fun t(ms: Int) = byteArrayOf((ms shr 24).toByte(), (ms shr 16).toByte(), (ms shr 8).toByte(), ms.toByte())
        val body = byteArrayOf(0) + "eng".toByteArray() + byteArrayOf(2, 1) + byteArrayOf(0) +
            "Hi".toByteArray() + byteArrayOf(0) + t(1500) + "there".toByteArray() + byteArrayOf(0) + t(3000)
        val l = Lrc.fromId3(id3v23("SYLT", body), Charsets.ISO_8859_1)!!
        assertEquals(Lrc.Type.ID3_SYLT, l.type)
        assertEquals(listOf("Hi", "there"), l.lines.map { it.text })
        assertEquals(listOf(1500L, 3000L), l.lines.map { it.timeStart })
    }
}
