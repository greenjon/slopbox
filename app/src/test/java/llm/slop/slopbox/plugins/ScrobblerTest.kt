/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package llm.slop.slopbox.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Last.fm Logger's log handling, after Rockbox's lastfm_scrobbler.c. */
class ScrobblerTest {
    private val log = """
        # Started Ver. SlopBox 20261120 Time 120000
        1000:180000:180000:/Music/a.mp3
        2000:300:200000:/Music/short.mp3
        3000:500000:200000:/Music/bad.mp3
        junk
        4000:60000:180000:/Music/a.mp3
        900000:180000:180000:/Music/a.mp3
    """.trimIndent()

    @Test fun parseAndMarks() {
        val p = Scrobbler.parse(listOf(log), Scrobbler.Config())
        // Comments kept, invalid lines dropped, too-short marked '!'.
        assertEquals("# Started Ver. SlopBox 20261120 Time 120000", p.lines[0])
        assertEquals(" 1000:180000:180000:/Music/a.mp3", p.lines[1])
        assertEquals("!2000:300:200000:/Music/short.mp3", p.lines[2])
        assertEquals(listOf(1000L, 4000L, 900000L), p.keep.map { it.timestamp })
    }

    @Test fun noResumeDuplicates() {
        // The 4000 play is the next entry after 1000 (back to back): a resume, keep the longer.
        val p = Scrobbler.parse(listOf(log), Scrobbler.Config(removeDup = 1))
        assertEquals(listOf(1000L, 900000L), p.keep.map { it.timestamp })
        assertEquals("@4000:60000:180000:/Music/a.mp3", p.lines[3])
    }

    @Test fun noDuplicates() {
        val p = Scrobbler.parse(listOf(log), Scrobbler.Config(removeDup = 2))
        assertEquals(1, p.keep.size)
    }

    @Test fun lines() {
        val e = Scrobbler.Entry(1000, 100000, 180000, "/Music/a.mp3")
        val tags = Scrobbler.Tags("Art\tist", null, "Alb", "Title", 3, "mbid")
        assertEquals("Art ist\tAlb\tTitle\t3\t180\tL\t1000\tmbid\n" to false, Scrobbler.line(e, tags, Scrobbler.Config()))
        // Under 50%: skipped.
        val s = Scrobbler.Entry(1000, 80000, 180000, "/Music/a.mp3")
        assertEquals('S', Scrobbler.line(s, tags, Scrobbler.Config()).first!!.split('\t')[5][0])
        // No tags: <UNTAGGED>, file name as title; Skip if Missing drops it.
        assertEquals("<UNTAGGED>\t\ta.mp3\t-1\t180\tL\t1000\t\n" to true, Scrobbler.line(e, null, Scrobbler.Config()))
        assertNull(Scrobbler.line(e, null, Scrobbler.Config(tracknfo = 1)).first)
    }
}
