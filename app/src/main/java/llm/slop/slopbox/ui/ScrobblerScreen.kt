/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/plugins/lastfm_scrobbler.c - Copyright (C) 2024 William Wilgus
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

import android.content.Context
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import llm.slop.slopbox.FileOps
import llm.slop.slopbox.PlaylistFiles
import llm.slop.slopbox.RockboxTagFile
import llm.slop.slopbox.SettingsStore
import llm.slop.slopbox.plugins.Scrobbler
import java.io.File

private fun str(ctx: Context, id: String) = SettingsStore.get(ctx).catalog.str(id)
private fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()

/** The playback logs, oldest rotation last as the plugin reads them: playback.log, playback_0001.log, ... */
private fun playbackLogs(ctx: Context): List<File> {
    val out = mutableListOf<File>()
    File(ctx.filesDir, "playback.log").takeIf { it.exists() }?.let { out += it }
    var i = 1
    while (i < 9999) {
        val f = File(ctx.filesDir, "playback_%04d.log".format(i))
        if (!f.exists()) break
        out += f; i++
    }
    return out
}

/** sbl_export(): playback logs -> .scrobbler.log (appended) in the music folder. */
private fun export(ctx: Context, cfg: Scrobbler.Config): String {
    val tree = MusicTree.root(ctx) ?: return "Choose the music folder first."
    val logs = playbackLogs(ctx)
    val parsed = Scrobbler.parse(logs.map { runCatching { it.readText() }.getOrDefault("") }, cfg)
    // playback_old.log: what the plugin parsed, kept for reference as Rockbox does.
    runCatching {
        File(ctx.filesDir, "playback_old.log").writeText(
            "#Parsed Playback log tags: '#' comment, '!' too short, '@' duplicate\n" + parsed.lines.joinToString("") { "$it\n" })
    }
    if (cfg.deleteLog) logs.forEach { it.delete() }
    if (parsed.keep.isEmpty() && parsed.lines.none { !it.startsWith("#") }) return "0 ${str(ctx, "LANG_TRACKS")}"
    val sb = StringBuilder()
    var saved = 0
    var missing = 0
    for (e in parsed.keep) {
        val tags = if (cfg.tracknfo == 2) null else runCatching {
            RockboxTagFile.read(ctx, MusicTree.docUri(tree, PlaylistFiles.docIdOf(tree, e.path)), e.path.substringAfterLast('/'))?.tags
        }.getOrNull()?.let {
            Scrobbler.Tags(it.artist.ifEmpty { null }, it.albumArtist.ifEmpty { null }, it.album.ifEmpty { null },
                it.title.ifEmpty { null }, it.trackNum, it.musicBrainzTrackId.ifEmpty { null })
        }
        val (line, noMeta) = Scrobbler.line(e, tags, cfg)
        if (noMeta) missing++
        if (line == null) continue
        sb.append(line); saved++
    }
    val ok = runCatching {
        val root = MusicTree.rootId(tree)
        val existing = FileOps.children(ctx, tree, root).firstOrNull { it.name == SCROBBLER_LOG }
        val text = (if (existing == null) Scrobbler.header("SlopBox ${appVersion(ctx)}") else "") + sb
        val uri = existing?.let { MusicTree.docUri(tree, it.id) }
            ?: DocumentsContract.createDocument(ctx.contentResolver, MusicTree.docUri(tree, root), "application/octet-stream", SCROBBLER_LOG)!!
        ctx.contentResolver.openOutputStream(uri, if (existing == null) "w" else "wa")!!.use { it.write(text.toByteArray()) }
    }.isSuccess
    if (!ok) return "Scrobbler Error opening: $SCROBBLER_LOG"
    val what = "$missing " + str(ctx, if (cfg.tracknfo == 1) "LANG_ID3_NO_INFO" else "LANG_DISPLAY_TRACK_NAME_ONLY")
    return str(ctx, "LANG_PLAYLIST_SAVE_COUNT").replace("%d", saved.toString()).replace("%s", what)
}

private fun appVersion(ctx: Context) =
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""

/** HOME_DIR "/.scrobbler.log", here at the top of the music folder. */
private const val SCROBBLER_LOG = ".scrobbler.log"

/**
 * Rockbox's Last.fm Logger plugin: its settings menu, View log, Revert to
 * Default Settings, Cancel and Export. Asks to turn on playback logging first.
 */
@Composable
fun ScrobblerScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SettingsStore.get(ctx) }
    val cfgFile = remember { File(ctx.filesDir, "lastfm_scrobbler.cfg") }
    val cfg = remember {
        Scrobbler.Config.load(cfgFile) ?: Scrobbler.Config().also {
            it.save(cfgFile); toast(ctx, str(ctx, "LANG_REVERT_TO_DEFAULT_SETTINGS"))
        }
    }
    val saved = remember { cfg.copy() }
    var askLog by remember { mutableStateOf(store.int("play log") == 0) }
    var page by remember { mutableStateOf("menu") }
    var rev by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val hasLog = remember(rev) { playbackLogs(ctx).isNotEmpty() }
    BackHandler(enabled = page != "menu") { page = "menu" }

    if (askLog) {
        YesNo("LastFm", listOf("Playback logging required", "Enable?")) { yes ->
            if (yes) store.catalog.byCfg["play log"]?.let { store.set(it, 1) }
            toast(ctx, "Playback Settings: Logging: " + if (yes) "Enabled" else "Disabled")
            askLog = false
        }
        return
    }
    if (busy) { Column(Modifier.fillMaxSize()) { TitleBar(str(ctx, "LANG_AUDIOSCROBBLER"), onBack = null); ListNote(str(ctx, "LANG_WAIT")) }; return }

    fun saveCfg() { cfg.save(cfgFile); saved.apply { savepct = cfg.savepct; minms = cfg.minms; tracknfo = cfg.tracknfo; removeDup = cfg.removeDup; deleteLog = cfg.deleteLog } }
    fun options(title: String, names: List<String>, cur: Int, set: (Int) -> Unit) {
        page = "opt:$title"
        pending = Triple(names, cur, set)
    }
    @Suppress("UNUSED_EXPRESSION") rev
    when {
        page.startsWith("opt:") -> {
            val (names, cur, set) = pending ?: return
            Column(Modifier.fillMaxSize()) {
                TitleBar(page.removePrefix("opt:"), onBack = { page = "menu" })
                RockList(Modifier.fillMaxSize()) {
                    items(names.indices.toList()) { i -> RockRow(names[i], selected = i == cur) { set(i); page = "menu"; rev++ } }
                }
            }
            return
        }
        page == "confirmSave" || page == "confirmView" -> {
            YesNo(str(ctx, "LANG_SAVE_CHANGES")) { yes ->
                val next = if (page == "confirmView") "log" else "quit"
                if (yes) saveCfg()
                if (next == "quit") nav.pop() else page = "log"
            }
            return
        }
        page == "revert" -> {
            YesNo(str(ctx, "LANG_AUDIOSCROBBLER"), listOf(str(ctx, "LANG_REVERT_TO_DEFAULT_SETTINGS"))) { yes ->
                if (yes) {
                    val d = Scrobbler.Config()
                    cfg.savepct = d.savepct; cfg.minms = d.minms; cfg.tracknfo = d.tracknfo; cfg.removeDup = d.removeDup; cfg.deleteLog = d.deleteLog
                    toast(ctx, str(ctx, "LANG_REVERT_TO_DEFAULT_SETTINGS"))
                }
                page = "menu"
            }
            return
        }
        page == "log" -> {
            // lastfm_scrobbler_viewer -scrobbler_view_pbl: the playback log.
            val lines = remember { playbackLogs(ctx).flatMap { runCatching { it.readLines() }.getOrDefault(emptyList()) } }
            Column(Modifier.fillMaxSize()) {
                TitleBar(str(ctx, "LANG_VIEWLOG"), onBack = { page = "menu" })
                if (lines.isEmpty()) ListNote(str(ctx, "LANG_NO_FILES"))
                RockList(Modifier.fillMaxSize()) {
                    items(lines) { l ->
                        val e = Scrobbler.parseEntry(l)
                        RockRow(if (e == null) l else "${e.path.substringAfterLast('/')}  ${e.elapsed / 1000}/${e.length / 1000}s")
                            {}
                    }
                }
            }
            return
        }
    }
    Column(Modifier.fillMaxSize()) {
        TitleBar(str(ctx, "LANG_AUDIOSCROBBLER"), onBack = { if (cfg != saved) page = "confirmSave" else nav.pop() })
        RockList(Modifier.fillMaxSize()) {
            item { RockRow("Remove duplicates", trailing = listOf(str(ctx, "LANG_OFF"), str(ctx, "LANG_RESUME_PLAYBACK"), str(ctx, "LANG_ALL"))[cfg.removeDup]) {
                options("Remove log duplicates", listOf(str(ctx, "LANG_OFF"), str(ctx, "LANG_RESUME_PLAYBACK"), str(ctx, "LANG_ALL")), cfg.removeDup) { cfg.removeDup = it } } }
            item { RockRow("Delete playback log", trailing = if (cfg.deleteLog) "Yes" else "No") { cfg.deleteLog = !cfg.deleteLog; rev++ } }
            item { RockRow(str(ctx, "LANG_COMPRESSOR_THRESHOLD"), trailing = "${cfg.savepct}%") {
                options(str(ctx, "LANG_COMPRESSOR_THRESHOLD"), (0..100 step 10).map { "$it%" }, cfg.savepct / 10) { cfg.savepct = it * 10 } } }
            item { RockRow("Minimum elapsed", trailing = "${cfg.minms} ms") {
                val values = (Scrobbler.MIN_ELAPSED_MS..10000 step 100).toList()
                options("Minimum Elapsed", values.map { "$it ms" }, values.indexOf(cfg.minms).coerceAtLeast(0)) { cfg.minms = values[it] } } }
            item { RockRow(str(ctx, "LANG_TRACK_INFO"), trailing = listOf(str(ctx, "LANG_ALL"), "Skip if Missing", str(ctx, "LANG_DISPLAY_TRACK_NAME_ONLY"))[cfg.tracknfo]) {
                options(str(ctx, "LANG_TRACK_INFO"), listOf(str(ctx, "LANG_ALL"), "Skip if Missing", str(ctx, "LANG_DISPLAY_TRACK_NAME_ONLY")), cfg.tracknfo) { cfg.tracknfo = it } } }
            item { RockRow(str(ctx, "LANG_VIEWLOG"), icon = G.Cursor) { if (cfg != saved) page = "confirmView" else page = "log" } }
            item { RockRow(str(ctx, "LANG_REVERT_TO_DEFAULT_SETTINGS"), icon = G.Cursor) { page = "revert" } }
            item { RockRow(str(ctx, "LANG_CANCEL_0"), icon = G.Cursor) { if (cfg != saved) page = "confirmSave" else nav.pop() } }
            if (hasLog) item {
                RockRow(str(ctx, "LANG_EXPORT"), icon = G.Cursor) {
                    saveCfg()
                    busy = true
                    scope.launch {
                        val msg = withContext(Dispatchers.IO) { export(ctx, cfg) }
                        busy = false
                        toast(ctx, msg)
                        nav.pop()
                    }
                }
            }
        }
    }
}

private var pending: Triple<List<String>, Int, (Int) -> Unit>? = null
