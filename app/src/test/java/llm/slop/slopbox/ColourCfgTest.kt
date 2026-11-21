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

/** Colour settings in .cfg files as Rockbox writes them (cfg_to_string) and reads them (hex_to_rgb). */
class ColourCfgTest {
    // "foreground color" as extracted: F_T_UINT | F_RGB | ...
    private val fg = RbSetting(49, "foreground color", 8388641, "foreground color", "plain",
        0xCCCCCC, null, emptyList(), null, null)

    @Test fun writesSixLowercaseHexDigits() {
        assertEquals("cccccc", fg.toCfg(0xCCCCCC))
        assertEquals("00000a", fg.toCfg(0x00000A))
        assertEquals("fde89e", fg.toCfg(0xFDE89E))
    }

    @Test fun readsHexEitherCase() {
        assertEquals(0xCCCCCC, fg.fromCfg("CCCCCC"))
        assertEquals(0xB58C00, fg.fromCfg(" b58c00 "))
    }

    @Test fun rejectsOtherText() {
        assertNull(fg.fromCfg("13553614"))
        assertNull(fg.fromCfg("ccc"))
        assertNull(fg.fromCfg("zzzzzz"))
    }
}
