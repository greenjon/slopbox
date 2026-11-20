/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package llm.slop.slopbox.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rockbox's natural sort (strnatcmp.c as changed for Rockbox). */
class FileViewTest {
    private fun sorted(names: List<String>, ignoreCase: Boolean = true) =
        names.sortedWith { a, b -> FileView.strnatcmp(a, b, ignoreCase) }

    @Test fun numbersAsNumbers() {
        assertEquals(listOf("track 2", "track 9", "track 10", "track 100"),
            sorted(listOf("track 10", "track 2", "track 100", "track 9")))
    }

    @Test fun leadingZerosCompareAsFractions() {
        assertEquals(listOf("x 001", "x 01", "x 1"), sorted(listOf("x 1", "x 01", "x 001")).let { it })
    }

    @Test fun dotSortsFirst() {
        // Rockbox's change: "Song.mp3" before "Song (Live).mp3".
        assertEquals(listOf("Song.mp3", "Song (Live).mp3"), sorted(listOf("Song (Live).mp3", "Song.mp3")))
    }

    @Test fun caseHandling() {
        assertTrue(FileView.strnatcmp("abc", "ABD", true) < 0)
        assertTrue(FileView.strnatcmp("abc", "ABD", false) > 0)   // 'a' > 'A' when case-sensitive
    }
}
