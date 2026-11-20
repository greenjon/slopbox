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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import llm.slop.slopbox.SettingsStore

/** Who maintains SlopBox, shown on the About screen and in the README. */
const val MAINTAINER = "greenjon"
const val SOURCE_URL = "https://github.com/greenjon/slopbox"

/** The main menu's System menu (Rockbox root_menu.c "system_menu"); more to come. */
@Composable
fun SystemMenu(nav: Nav) {
    val ctx = LocalContext.current
    var page by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = page != null) { page = null }
    when (page) {
        "about" -> { AboutScreen { page = null }; return }
        "credits" -> { TextFileScreen("Rockbox Credits", "rockbox/CREDITS") { page = null }; return }
        "license" -> { TextFileScreen("License", "rockbox/LICENSE") { page = null }; return }
    }
    Column(Modifier.fillMaxSize()) {
        TitleBar(SettingsStore.get(ctx).catalog.str("LANG_SYSTEM"), onBack = { nav.pop() })
        RockRow("About SlopBox", icon = G.Cursor) { page = "about" }
        RockRow("Rockbox Credits", icon = G.Cursor) { page = "credits" }
        RockRow("License", icon = G.Cursor) { page = "license" }
    }
}

/**
 * About SlopBox: what it is, that it's a derivative of Rockbox and not part
 * of the Rockbox project, who maintains it, and its licence.
 */
@Composable
private fun AboutScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
    }
    val rev = remember { SettingsStore.get(ctx).catalog.rockboxRevision }
    val paragraphs = listOf(
        "SlopBox $version",
        "An Android music player built on Rockbox's own codecs, sound processing and settings.",
        "SlopBox is a derivative work (a fork) of Rockbox, the open source firmware for digital " +
            "audio players (www.rockbox.org). It is not made, endorsed or supported by the Rockbox " +
            "project; please don't report SlopBox problems to them.",
        "Maintainer: $MAINTAINER\nSource code: $SOURCE_URL",
        "Built on Rockbox source revision $rev. Rockbox is copyright its many contributors " +
            "(see Rockbox Credits); their copyright notices are kept in the source code.",
        "Look: after the cabbiev2 theme by Johannes Voggenthaler, Apoo Maha, Marc Guay, Alex " +
            "Vanderpol, Jerry Lange, Keith Perri, Mark Fawcus and Marianne Arnold, based on Cabbie " +
            "by Yohann Misquitta; icons after the Tango icon set.",
        "SlopBox is free software: you can redistribute it and/or modify it under the terms of the " +
            "GNU General Public License as published by the Free Software Foundation, either " +
            "version 2 of the License, or (at your option) any later version. It comes with " +
            "ABSOLUTELY NO WARRANTY. See License.",
    )
    Column(Modifier.fillMaxSize()) {
        TitleBar("About SlopBox", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize()) {
            items(paragraphs) { p ->
                BasicText(p, Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), style = Cabbie.body)
            }
        }
    }
}

/** A text file from the assets, line by line. */
@Composable
private fun TextFileScreen(title: String, asset: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val lines = remember(asset) {
        runCatching { ctx.assets.open(asset).bufferedReader().readLines() }.getOrDefault(listOf("($asset is missing)"))
    }
    Column(Modifier.fillMaxSize()) {
        TitleBar(title, onBack = onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
            items(lines) { l -> BasicText(l, style = Cabbie.small) }
        }
    }
}
