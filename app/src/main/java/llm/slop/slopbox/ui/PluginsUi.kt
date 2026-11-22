/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/plugins/random_folder_advance_config.c - Copyright (C) 2006 Jonathan Gordon
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
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.media3.session.MediaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import llm.slop.slopbox.PlaylistFiles
import llm.slop.slopbox.SettingsStore
import llm.slop.slopbox.SlopPlayer
import llm.slop.slopbox.plugins.RandomFolderAdvance

private fun str(ctx: Context, id: String) = SettingsStore.get(ctx).catalog.str(id)
private fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()

/** The plugins SlopBox has, by Rockbox's plugin folder (apps, demos) and file name. */
object PluginList {
    class Plugin(val name: String, val screen: Screen)
    val categories: List<Pair<String, List<Plugin>>> = listOf(
        "apps" to listOf(
            Plugin("alarmclock", Screen.PluginAlarm),
            Plugin("lastfm_scrobbler", Screen.PluginScrobbler),
            Plugin("lrcplayer", Screen.PluginLrc),
            Plugin("random_folder_advance_config", Screen.PluginRfa),
        ),
    )
    val all: List<Plugin> get() = categories.flatMap { it.second }
    /** Which plugin a plugin screen shows (Rockbox runs one plugin at a time). */
    var running by mutableStateOf<String?>(null)
}

/**
 * Rockbox's open_plugin entries: the plugin the WPS hotkey ("wps") and the
 * context menu items ("context") run for Open Plugin, chosen when that action
 * is set (open_plugin_browse). Kept in SlopBox's preferences, not open_plugin.dat.
 */
object OpenPlugin {
    const val WPS = "wps"
    const val CONTEXT = "context"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("open_plugin", Context.MODE_PRIVATE)
    fun get(ctx: Context, key: String) = prefs(ctx).getString(key, null)?.let { n -> PluginList.all.firstOrNull { it.name == n } }
    fun set(ctx: Context, key: String, name: String) = prefs(ctx).edit().putString(key, name).apply()

    /** open_plugin_run(): start the chosen plugin; false if none was chosen. */
    fun run(ctx: Context, key: String, nav: Nav): Boolean {
        val p = get(ctx, key) ?: return false
        PluginList.running = p.name
        nav.push(p.screen)
        return true
    }
}

/** open_plugin_browse(): pick the plugin Open Plugin runs. */
@Composable
fun OpenPluginPicker(key: String, titleBar: Boolean = true, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val cur = OpenPlugin.get(ctx, key)
    BackHandler { onDone() }
    Column(Modifier.fillMaxSize()) {
        if (titleBar) TitleBar(str(ctx, "LANG_OPEN_PLUGIN"), onBack = onDone)
        RockList(Modifier.fillMaxSize()) {
            items(PluginList.all) { p ->
                RockRow(p.name, icon = G.Cursor, selected = p == cur) { OpenPlugin.set(ctx, key, p.name); onDone() }
            }
        }
    }
}

/**
 * Rockbox's Plugins browser (the rocks folder): its category folders, then
 * the plugins in one, by file name. Picking one runs it.
 */
@Composable
fun PluginsScreen(nav: Nav) {
    val ctx = LocalContext.current
    var category by remember { mutableStateOf<Int?>(null) }
    BackHandler(enabled = category != null) { category = null }
    Column(Modifier.fillMaxSize()) {
        val cat = category
        if (cat == null) {
            TitleBar(str(ctx, "LANG_PLUGINS"), onBack = { nav.pop() })
            RockList(Modifier.fillMaxSize()) {
                itemsIndexed(PluginList.categories) { i, (name, _) ->
                    RockRow(name, icon = G.TangoFolder) { category = i }
                }
            }
        } else {
            val (name, plugins) = PluginList.categories[cat]
            TitleBar(name, onBack = { category = null })
            RockList(Modifier.fillMaxSize()) {
                items(plugins) { p ->
                    RockRow(p.name, icon = G.Cursor) { PluginList.running = p.name; nav.push(p.screen) }
                }
            }
        }
    }
}

// ---- random_folder_advance_config ----------------------------------------------

/**
 * Rockbox's Random Folder Advance plugin: Play Shuffled, Scan Folders,
 * Edit, Export to Text File, Import from Text File, Quit.
 */
@Composable
fun RandomFolderAdvanceScreen(controller: MediaController?, nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SettingsStore.get(ctx) }
    var busy by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var confirmErase by remember { mutableStateOf(false) }
    val tree = MusicTree.root(ctx)

    suspend fun ensureList(): List<String>? {
        if (tree == null) return null
        if (!RandomFolderAdvance.exists(ctx)) withContext(Dispatchers.IO) {
            RandomFolderAdvance.scan(ctx, tree) { n -> busy = "Folders: $n" }
        }
        return RandomFolderAdvance.load(ctx)
    }

    /** play_shuffled(): every listed folder's tracks, folders in random order, from the top. */
    fun playShuffled() = scope.launch {
        val t = tree ?: return@launch
        busy = "Scanning..."
        val dirs = ensureList()
        if (dirs.isNullOrEmpty()) { busy = null; toast(ctx, "No dirs in list file: ${RandomFolderAdvance.LIST_DAT}"); return@launch }
        val order = dirs.indices.toMutableList()
        for (i in order.indices.reversed()) {
            val c = kotlin.random.Random.nextInt(i + 1)
            order[c] = order[i].also { order[i] = order[c] }
        }
        val items = withContext(Dispatchers.IO) {
            order.flatMapIndexed { n, i ->
                busy = "${str(ctx, "LANG_WAIT")} (${n + 1}/${dirs.size})"
                val id = PlaylistFiles.docIdOf(t, dirs[i])
                val dir = Doc(id, dirs[i].substringAfterLast('/'), true)
                MusicTree.list(ctx, t, id).filter { it.isTrack }.map { MusicTree.item(t, it, dir) }
            }
        }
        busy = null
        val c = controller ?: return@launch
        if (items.isEmpty()) return@launch
        Queue.play(c, items, 0)
        SlopPlayer.current?.markModified()
        nav.toNowPlaying()
    }

    if (busy != null) {
        Column(Modifier.fillMaxSize()) { TitleBar("Random Folder Advance", onBack = null); ListNote(busy!!) }
        return
    }
    if (confirmErase) {
        // warn_on_pl_erase()
        YesNo(str(ctx, "LANG_WARN_ERASEDYNPLAYLIST_PROMPT")) { yes -> confirmErase = false; if (yes) playShuffled() }
        return
    }
    if (editing) {
        RfaEdit(onDone = { editing = false })
        return
    }
    Column(Modifier.fillMaxSize()) {
        TitleBar("Random Folder Advance", onBack = { nav.pop() })
        if (tree == null) { ListNote("Choose the music folder first."); return@Column }
        RockRow("Play Shuffled", icon = G.Cursor) {
            val p = SlopPlayer.current
            if (p != null && p.playlistModified && store.int("warn when erasing dynamic playlist") != 0) confirmErase = true
            else playShuffled()
        }
        RockRow("Scan Folders", icon = G.Cursor) {
            scope.launch {
                busy = "Scanning..."
                withContext(Dispatchers.IO) { RandomFolderAdvance.scan(ctx, tree) { n -> busy = "Folders: $n" } }
                busy = null
                toast(ctx, "Done")
            }
        }
        RockRow("Edit", icon = G.Cursor) {
            scope.launch {
                busy = "Scanning..."
                ensureList()
                busy = null
                editing = true
            }
        }
        RockRow("Export to Text File", icon = G.Cursor) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { RandomFolderAdvance.export(ctx, tree) }
                toast(ctx, if (ok) "Done" else "No dirs in list file: ${RandomFolderAdvance.LIST_DAT}")
            }
        }
        RockRow("Import from Text File", icon = G.Cursor) {
            scope.launch {
                val n = withContext(Dispatchers.IO) { RandomFolderAdvance.import(ctx, tree) }
                toast(ctx, if (n >= 0) "Done" else "failed to open: ${RandomFolderAdvance.LIST_TXT}")
            }
        }
        RockRow("Quit", icon = G.Cursor) { nav.pop() }
    }
}

/**
 * The plugin's Edit list: tap removes a folder; a long press offers Remove
 * Folder or Remove Folder Tree; leaving after a change asks Save / Ignore.
 */
@Composable
private fun RfaEdit(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val dirs = remember { mutableStateListOf<String>().apply { addAll(RandomFolderAdvance.load(ctx).orEmpty()) } }
    val removed = remember { mutableStateListOf<Boolean>().apply { repeat(dirs.size) { add(false) } } }
    var dirty by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Int?>(null) }
    var askSave by remember { mutableStateOf(false) }
    fun disable(i: Int): Boolean { if (removed[i]) return false; removed[i] = true; dirty = true; return true }
    BackHandler(enabled = menuFor == null && !askSave) { if (dirty) askSave = true else onDone() }
    BackHandler(enabled = menuFor != null || askSave) { menuFor = null; askSave = false }

    if (askSave) {
        Column(Modifier.fillMaxSize()) {
            TitleBar("Save Changes?", onBack = { askSave = false })
            RockRow("Save", icon = G.Cursor, selected = true) {
                RandomFolderAdvance.save(ctx, dirs.filterIndexed { i, _ -> !removed[i] })
                onDone()
            }
            RockRow("Ignore Changes", icon = G.Cursor) { onDone() }
        }
        return
    }
    menuFor?.let { i ->
        Column(Modifier.fillMaxSize()) {
            TitleBar("Remove", onBack = { menuFor = null })
            RockRow("Remove Folder", icon = G.Cursor) { disable(i); menuFor = null }
            RockRow("Remove Folder Tree", icon = G.Cursor) {
                val p = dirs[i]
                for (j in dirs.indices) if (dirs[j].startsWith(p)) disable(j)
                menuFor = null
            }
        }
        return
    }
    val count = removed.count { !it }
    Column(Modifier.fillMaxSize()) {
        TitleBar(if (count == 1) "1 Folder" else "$count Folders", onBack = { if (dirty) askSave = true else onDone() })
        RockList(Modifier.fillMaxSize()) {
            itemsIndexed(dirs) { i, d ->
                // A removed folder shows as a blank line, as in the plugin.
                RockRow(if (removed[i]) " " else d, onLongClick = { menuFor = i }) { disable(i) }
            }
        }
    }
}
