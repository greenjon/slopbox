/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/menus/main_menu.c - Copyright (C) 2007 Jonathan Gordon
 * and the other Rockbox contributors (third_party/rockbox/docs/CREDITS).
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

/** The main menu's System menu (Rockbox root_menu.c "system_menu", menus/main_menu.c info_menu). */
@Composable
fun SystemMenu(nav: Nav) {
    val ctx = LocalContext.current
    var page by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = page != null) { page = null }
    val cat = SettingsStore.get(ctx).catalog
    when (page) {
        "info" -> { InfoScreen { page = null }; return }
        "about" -> { AboutScreen { page = null }; return }
        "credits" -> { TextFileScreen(cat.str("LANG_CREDITS"), listOf("rockbox/CREDITS")) { page = null }; return }
        "runtime" -> { RunningTimeScreen { page = null }; return }
        // show_legal(): COPYING, then LICENSES.
        "legal" -> { TextFileScreen(cat.str("LANG_LEGAL_NOTICES"), listOf("rockbox/LICENSE", "rockbox/LICENSES")) { page = null }; return }
    }
    // Rockbox's info_menu: Rockbox Info (here SlopBox Info), Credits, Running
    // Time, Legal Notices; plus About SlopBox. No debug screens.
    Column(Modifier.fillMaxSize()) {
        TitleBar(cat.str("LANG_SYSTEM"), onBack = { nav.pop() })
        RockRow("SlopBox Info", icon = G.Cursor) { page = "info" }
        RockRow(cat.str("LANG_CREDITS"), icon = G.Cursor) { page = "credits" }
        RockRow(cat.str("LANG_RUNNING_TIME"), icon = G.Cursor) { page = "runtime" }
        RockRow(cat.str("LANG_LEGAL_NOTICES"), icon = G.Cursor) { page = "legal" }
        RockRow("About SlopBox", icon = G.Cursor) { page = "about" }
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
            "(see Credits); their copyright notices are kept in the source code.",
        "Look: after the cabbiev2 theme by Johannes Voggenthaler, Apoo Maha, Marc Guay, Alex " +
            "Vanderpol, Jerry Lange, Keith Perri, Mark Fawcus and Marianne Arnold, based on Cabbie " +
            "by Yohann Misquitta; icons after the Tango icon set.",
        "SlopBox is free software: you can redistribute it and/or modify it under the terms of the " +
            "GNU General Public License as published by the Free Software Foundation, either " +
            "version 2 of the License, or (at your option) any later version. It comes with " +
            "ABSOLUTELY NO WARRANTY. See Legal Notices.",
    )
    Column(Modifier.fillMaxSize()) {
        TitleBar("About SlopBox", onBack = onBack)
        RockList(Modifier.fillMaxSize()) {
            items(paragraphs) { p ->
                BasicText(p, Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), style = Cabbie.body)
            }
        }
    }
}

/** Text files from the assets, one after another, line by line. */
@Composable
private fun TextFileScreen(title: String, assets: List<String>, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val lines = remember(assets) {
        assets.flatMap { a ->
            runCatching { ctx.assets.open(a).bufferedReader().readLines() }.getOrDefault(listOf("($a is missing)")) + ""
        }
    }
    Column(Modifier.fillMaxSize()) {
        TitleBar(title, onBack = onBack)
        RockList(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
            items(lines) { l -> BasicText(l, style = Cabbie.small) }
        }
    }
}

/**
 * Rockbox show_info() ("Rockbox Info", here "SlopBox Info"): battery, the
 * music folder's full path, the version, date and time, and free / total
 * space on each storage volume. Refreshed every few seconds; tap to rescan.
 */
@Composable
private fun InfoScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cat = SettingsStore.get(ctx).catalog
    var tick by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(5000); tick++ } }
    @Suppress("UNUSED_EXPRESSION") tick
    val lines = remember(tick) {
        val out = mutableListOf<String>()
        // INFO_BATTERY
        val bm = ctx.getSystemService(android.os.BatteryManager::class.java)
        val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        out += when {
            bm?.isCharging == true -> "Battery: charging ($level%)"
            level >= 0 -> "Battery: $level%"
            else -> "Battery n/a"
        }
        // INFO_ROOT_DIR
        out += cat.str("LANG_DISPLAY_FULL_PATH")
        out += MusicTree.root(ctx)?.let { llm.slop.slopbox.PlaylistFiles.pathOf(MusicTree.rootId(it)) } ?: "-"
        // INFO_VERSION
        out += cat.str("LANG_VERSION")
        val v = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
        out += "SlopBox $v (Rockbox ${cat.rockboxRevision})"
        // INFO_DATE, INFO_TIME
        val c = java.util.Calendar.getInstance()
        val months = listOf("JANUARY", "FEBRUARY", "MARCH", "APRIL", "MAY", "JUNE", "JULY", "AUGUST",
            "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER")
        out += "${cat.str("LANG_MONTH_" + months[c.get(java.util.Calendar.MONTH)])} ${c.get(java.util.Calendar.DAY_OF_MONTH)} ${c.get(java.util.Calendar.YEAR)}"
        out += "%02d:%02d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), c.get(java.util.Calendar.SECOND))
        // INFO_DISK: internal storage and any removable card.
        val roots = ctx.getExternalFilesDirs(null).filterNotNull().map { f ->
            var r = f
            while (r.parentFile != null && !r.name.equals("Android", ignoreCase = true)) r = r.parentFile!!
            r.parentFile ?: f
        }
        for ((i, r) in roots.withIndex()) {
            runCatching {
                val st = android.os.StatFs(r.path)
                val units = listOf("KiB", "MiB", "GiB")
                fun dyn(kib: Long): String { var v2 = kib.toDouble(); var u = 0; while (v2 >= 1024 && u < 2) { v2 /= 1024; u++ }; return "%.1f%s".format(v2, units[u]) }
                val name = if (i == 0) cat.str("LANG_DISK_NAME_INTERNAL") else "SD:"
                out += "$name ${dyn(st.availableBytes / 1024)}/${dyn(st.totalBytes / 1024)}"
            }
        }
        out
    }
    Column(Modifier.fillMaxSize()) {
        TitleBar("SlopBox Info", onBack = onBack)
        RockList(Modifier.fillMaxSize()) {
            items(lines) { l -> RockRow(l) { tick++ } }
        }
    }
}

/**
 * Rockbox view_runtime(): Running Time (since the charger was last
 * unplugged) and Top Time; tap one to clear it ("Clear Time?").
 */
@Composable
private fun RunningTimeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cat = SettingsStore.get(ctx).catalog
    var tick by remember { mutableStateOf(0) }
    var clear by remember { mutableStateOf<Boolean?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); tick++ } }
    BackHandler(enabled = clear != null) { clear = null }
    clear?.let { top ->
        YesNo(cat.str(if (top) "LANG_TOP_TIME" else "LANG_RUNNING_TIME"), listOf(cat.str("LANG_CLEAR_TIME"))) { yes ->
            if (yes) llm.slop.slopbox.PowerMgmt.clearRuntime(top)
            clear = null
        }
        return
    }
    @Suppress("UNUSED_EXPRESSION") tick
    // format_time_auto(UNIT_SEC)
    fun fmt(s: Long) = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    Column(Modifier.fillMaxSize()) {
        TitleBar(cat.str("LANG_RUNNING_TIME"), onBack = onBack)
        RockRow(cat.str("LANG_RUNNING_TIME"), sub = fmt(llm.slop.slopbox.PowerMgmt.runtime)) { clear = false }
        RockRow(cat.str("LANG_TOP_TIME"), sub = fmt(llm.slop.slopbox.PowerMgmt.topRuntime)) { clear = true }
    }
}
