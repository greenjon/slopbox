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
package llm.slop.slopbox.ui

import android.content.Context
import llm.slop.slopbox.SettingsStore

/**
 * The theme .cfg files shipped in assets/themes, listed by Browse Theme Files
 * (Rockbox lists /.rockbox/themes). "02 Ocean.cfg" shows as "Ocean"; the
 * number only sets the order.
 */
object BundledThemes {
    class Theme(val name: String, val text: String) {
        /** name: value pairs, comments and blank lines left out. */
        val values: List<Pair<String, String>> = text.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && ':' in it }
            .map { it.substringBefore(':').trim() to it.substringAfter(':').trim() }.toList()

        /** Whether the settings already hold every value this theme sets. */
        fun matches(store: SettingsStore): Boolean = values.all { (cfg, v) ->
            val s = store.catalog.byCfg[cfg] ?: return@all true
            val want = s.fromCfg(v) ?: return@all false
            store.int(s) == want
        }
    }

    fun list(ctx: Context): List<Theme> = (ctx.assets.list("themes") ?: emptyArray())
        .filter { it.endsWith(".cfg") }.sorted()
        .map { f ->
            Theme(f.removeSuffix(".cfg").replaceFirst(Regex("^\\d+ "), ""),
                ctx.assets.open("themes/$f").bufferedReader().use { it.readText() })
        }
}
