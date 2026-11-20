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
package llm.slop.slopbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Cuesheet parsing and skipping, checked against Rockbox apps/cuesheet.c behaviour. */
class CuesheetTest {
    private val sheet = """
        PERFORMER "Sheet Artist"
        TITLE "The Mix"
        FILE "mix.flac" WAVE
          TRACK 01 AUDIO
            TITLE "One"
            INDEX 01 00:00:00
          TRACK 02 AUDIO
            TITLE "Two"
            PERFORMER "Guest"
            INDEX 00 00:09:50
            INDEX 01 00:10:00
          TRACK 03 AUDIO
            TITLE "Three"
            INDEX 01 00:20:37
    """.trimIndent()

    private val cue = Cuesheet.parse(sheet.toByteArray(Charsets.ISO_8859_1), 1, null)!!

    @Test fun parsesSheetAndTracks() {
        assertEquals("The Mix", cue.title)
        assertEquals(3, cue.tracks.size)
        assertEquals(listOf("One", "Two", "Three"), cue.tracks.map { it.title })
        // Missing performer comes from the sheet; INDEX 00 is ignored.
        assertEquals(listOf("Sheet Artist", "Guest", "Sheet Artist"), cue.tracks.map { it.performer })
        // parse_cue_index(): a frame counts 13 ms.
        assertEquals(listOf(0L, 10_000L, 20_000L + 37 * 13), cue.tracks.map { it.offsetMs })
    }

    @Test fun currentTrack() {
        assertEquals(0, cue.currentTrack(9_999))
        assertEquals(0, cue.currentTrack(10_000))   // strictly after the start, as in Rockbox
        assertEquals(1, cue.currentTrack(10_001))
        assertEquals(2, cue.currentTrack(25_000))
    }

    @Test fun skipRules() {
        // Next: the next cue track; on the last one, leave the cuesheet.
        assertEquals(10_000L, cue.skip(1, 5_000))
        assertNull(cue.skip(1, 25_000))
        // Previous: start of this cue track, or the previous one within 3 s.
        assertEquals(10_000L, cue.skip(-1, 15_000))
        assertEquals(0L, cue.skip(-1, 11_000))
        assertEquals(0L, cue.skip(-1, 1_000))
    }

    @Test fun utf8Bom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "TRACK 01 AUDIO\n TITLE \"Ünï\"\n INDEX 01 01:00:00\n".toByteArray(Charsets.UTF_8)
        val c = Cuesheet.parse(bytes, 1, null)!!
        assertEquals("Ünï", c.tracks[0].title)
        assertEquals(60_000L, c.tracks[0].offsetMs)
    }

    @Test fun badIndexIsZero() {
        val c = Cuesheet.parse("TRACK 01 AUDIO\nINDEX 01 00:61:00\n".toByteArray(), 1, null)!!
        assertEquals(0L, c.tracks[0].offsetMs)   // seconds > 59: Rockbox bails to 0
    }
}
