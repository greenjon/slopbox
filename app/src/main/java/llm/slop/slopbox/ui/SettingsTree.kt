/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/menu.c - Copyright (C) 2002 Robert E. Hak
 *   apps/menus/settings_menu.c - Copyright (C) 2007 Jonathan Gordon
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

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import llm.slop.slopbox.EqBand
import llm.slop.slopbox.RbMenuItem
import llm.slop.slopbox.SettingsStore
import llm.slop.slopbox.ui.db.TagDb

/**
 * Rockbox's Settings menu, built from Rockbox's own menu tree and settings
 * table (assets/rockbox_settings.json, made by settings-gen/). Works like
 * Rockbox: menus are lists of names; a setting opens a list of its values with
 * the current one selected.
 *
 * [startAt] opens a submenu directly, by its Rockbox title (e.g. "Sound Settings").
 */
@Composable
fun SettingsTreeScreen(nav: Nav, pickRoot: () -> Unit, startAt: String? = null) {
    val ctx = LocalContext.current
    val store = remember { SettingsStore.get(ctx) }
    val cat = store.catalog
    val stack = remember {
        val root = cat.menu
        // The path from the root to the submenu titled [startAt] (searched depth first).
        fun find(m: RbMenuItem, name: String): List<RbMenuItem>? {
            for (c in m.items) if (c.isMenu) {
                if (c.text == name) return listOf(c)
                find(c, name)?.let { return listOf(c) + it }
            }
            return null
        }
        val path = startAt?.let { find(root, it) }
        if (path == null) mutableStateListOf<Page>(Page.Menu(root))
        else mutableStateListOf<Page>(Page.Menu(path.last()))
    }
    // A "setting" shortcut opens just that setting (Rockbox do_setting_screen()).
    val single = remember { ShortcutView.setting.also { ShortcutView.setting = null } }
    var settingMenu by remember { mutableStateOf<RbMenuItem?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var saveMode by remember { mutableIntStateOf(SettingsStore.SAVE_ALL) }
    // Ticks once a second so live labels (the sleep timer) stay current.
    var clock by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(1000); clock++ }
    }

    DisposableEffect(store) {
        val l: () -> Unit = { revision++ }
        store.addListener(l)
        onDispose { store.removeListener(l) }
    }
    fun back() {
        if (single != null && stack.size <= 2) nav.pop()
        else if (stack.size > 1) stack.removeAt(stack.lastIndex) else nav.pop()
    }
    BackHandler(enabled = stack.size > 1 || confirm != null || message != null || settingMenu != null) {
        when {
            settingMenu != null -> settingMenu = null
            confirm != null -> confirm = null
            message != null -> message = null
            else -> back()
        }
    }
    fun toast(t: String) = Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show()

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(store.toCfg(saveMode).toByteArray()) }
        }.isSuccess
        toast(if (ok) cat.str("LANG_SETTINGS_SAVED") else "Couldn't save")
    }
    val loader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText() }.getOrNull()
        if (text == null) toast("Couldn't read that file")
        else { store.loadCfg(text); toast(cat.str("LANG_SETTINGS_LOADED")) }
    }

    fun exportChangelog() {
        if (MusicTree.root(ctx) == null) { message = "Choose the music folder first."; return }
        toast(cat.str("LANG_CREATING"))
        if (!TagDb.exportChangelog(ctx)) toast(cat.str("LANG_FAILED"))
    }

    fun importChangelog() {
        val tree = MusicTree.root(ctx) ?: run { message = "Choose the music folder first."; return }
        val f = llm.slop.slopbox.FileOps.children(ctx, tree, MusicTree.rootId(tree)).firstOrNull { it.name == CHANGELOG }
        val text = f?.let { runCatching {
            ctx.contentResolver.openInputStream(MusicTree.docUri(tree, it.id))!!.bufferedReader().readText()
        }.getOrNull() }
        if (text == null) { toast(cat.str("LANG_FAILED")); return }
        TagDb.importChangelog(ctx, text)
    }

    fun runFunction(item: RbMenuItem) {
        val tree = MusicTree.root(ctx)
        when (item.function) {
            "eq_do_simple_menu" -> stack += Page.SimpleEq(item.text)
            "eq_do_advanced_menu" -> stack += Page.AdvancedEq(item.text)
            "eq_menu_graphical" -> stack += Page.GraphicalEq(item.text)
            "main_menu_config" -> stack += Page.MainMenuCfg(item.text)
            "eq_reset_defaults" -> { store.resetEq(); toast(item.text) }
            // Theme Settings › Colours (theme_menu.c)
            "set_color_func" -> ThemeColours.index(item.param).let { stack += Page.Colour(item.text, it) }
            "reset_color" -> { ThemeColours.reset(store); toast(item.text) }
            "reset_settings" -> confirm = cat.str("LANG_ARE_YOU_SURE") to { store.resetAll() }
            "eq_save_preset" -> { saveMode = SettingsStore.SAVE_EQ; saver.launch("eq.cfg") }
            "write_settings_file" -> {
                saveMode = when (item.param) {
                    "?2" -> SettingsStore.SAVE_THEME
                    "?3" -> SettingsStore.SAVE_SOUND
                    else -> SettingsStore.SAVE_ALL
                }
                saver.launch(when (saveMode) {
                    SettingsStore.SAVE_SOUND -> "sound.cfg"
                    SettingsStore.SAVE_THEME -> "theme.cfg"
                    else -> "config.cfg"
                })
            }
            "browse_folder" -> when (item.param) {
                "config", "eqs" -> loader.launch(arrayOf("*/*"))
                "themes" -> stack += Page.Themes(item.text)
                "wps" -> stack += Page.Layouts(item.text)
                else -> message = "${item.text}: not available in SlopBox yet."
            }
            // General Settings › Database
            "tagcache_rebuild_with_splash", "tagcache_update_with_splash" ->
                if (tree == null) message = "Choose the music folder first (Select directories to scan)."
                else {
                    TagDb.scan(ctx, tree, full = item.function == "tagcache_rebuild_with_splash")
                    toast(cat.str("LANG_TAGCACHE_FORCE_UPDATE_SPLASH"))
                }
            "dirs_to_scan" -> pickRoot()
            null -> when (item.text) {
                // Database › Export / Import Modifications (tagtree_export / tagtree_import):
                // Rockbox's database_changelog.txt, kept in the music folder.
                cat.str("LANG_TAGCACHE_EXPORT") -> exportChangelog()
                cat.str("LANG_TAGCACHE_IMPORT") -> importChangelog()
                else -> message = "${item.text}: not available in SlopBox yet."
            }
            "toggle_sleeptimer" -> llm.slop.slopbox.PowerMgmt.toggleSleepTimer()
            // General Settings › While Playing Screen
            "reset_wps_items" -> { WpsContext.reset(store); toast(item.text) }
            // General Settings › File View: "Start File Browser at /"
            "clear_start_directory" -> {
                cat.byCfg["start directory"]?.let { store.setText(it, "/") }
                toast(cat.str("LANG_RESET_DONE_CLEAR"))
            }
            else -> message = "${item.text}: not available in SlopBox yet."
        }
    }

    /** Rockbox wps_context_menu_do_setting(): the "While Playing Screen" items. */
    fun contextItem(item: RbMenuItem): Int? =
        if (item.textFn == "wps_context_menu_get_name") item.param?.removePrefix("?")?.toIntOrNull() ?: 0 else null

    fun openSetting(item: RbMenuItem) {
        val s = cat.settings.getOrNull(item.setting) ?: return
        val note = if (store.isLive(s)) null else "Not used by SlopBox yet; saved with your settings."
        if (s.cfg == "volume limit") {
            // Rockbox shows the volume range for the limit.
            val vol = cat.byCfg["volume"]!!
            stack += Page.Value(item.text, vol.labels,
                { store.text(s).trim().toIntOrNull() ?: 0 }, { store.setText(s, it.toString()) }, note)
            return
        }
        if (s.labels.isEmpty()) {
            message = "${item.text}: this kind of setting isn't editable in SlopBox yet."
            return
        }
        stack += Page.Value(item.text, s.labels, { store.int(s) }, { v ->
            val old = store.int(s)
            store.set(s, v)
            // Rockbox autoresume_nexttrack_callback(): "Custom" opens the folder
            // picker; if nothing is saved there, the old choice comes back.
            if (s.cfg == "autoresume next track" && v == 2) {
                stack += Page.FolderSel(cat.str("LANG_AUTORESUME"), "autoresume next track paths", s, old)
            }
            // Rockbox sleeptimer_duration_cb(): a running timer restarts with the new length.
            if (s.cfg == "sleeptimer duration" && llm.slop.slopbox.PowerMgmt.sleepTimerSeconds > 0)
                llm.slop.slopbox.PowerMgmt.setSleepTimer(v)
        }, note)
    }

    fun eqValuePage(band: Int, field: Int): Page.Value {
        fun update(f: (EqBand) -> EqBand) = store.setEqBand(band, f(store.eqBand(band)))
        val b = store.eqBand(band)
        return when (field) {
            0 -> Page.Value(
                cat.str(if (b.type == 1) "LANG_EQUALIZER_BAND_CENTER" else "LANG_EQUALIZER_BAND_CUTOFF"),
                CUTOFF_LABELS, { store.eqBand(band).cutoff }, { v -> update { it.copy(cutoff = v) } })
            1 -> Page.Value(cat.str("LANG_EQUALIZER_BAND_Q"), Q_LABELS,
                { store.eqBand(band).q }, { v -> update { it.copy(q = v) } })
            2 -> Page.Value(cat.str("LANG_GAIN"), GAIN_LABELS,
                { store.eqBand(band).gain }, { v -> update { it.copy(gain = v) } })
            else -> Page.Value(cat.str("LANG_EQUALIZER_FILTER_TYPE"), typeLabels(store),
                { store.eqBand(band).type }, { v -> update { it.copy(type = v) } })
        }
    }

    if (single != null) androidx.compose.runtime.LaunchedEffect(Unit) {
        cat.byCfg[single]?.let { st -> openSetting(RbMenuItem(RbMenuItem.MT_SETTING, st.title, st.index, null, null, emptyList())) }
            ?: nav.pop()
    }

    // Rockbox do_menu() ACTION_STD_CONTEXT on a setting: Reset Setting, the
    // four Quickscreen positions, Add Current to Shortcuts, Add to Shortcuts
    // (only Reset Setting for settings that can't go on the quickscreen).
    settingMenu?.let { item ->
        val st = cat.settings.getOrNull(item.setting)
        if (st == null) { settingMenu = null; return }
        Column(Modifier.fillMaxSize()) {
            TitleBar(cat.str("LANG_ONPLAY_MENU_TITLE"), onBack = { settingMenu = null })
            RockRow(cat.str("LANG_RESET_SETTING"), icon = G.Cursor) { store.reset(st); settingMenu = null }
            if (st.quickscreenable) {
                for ((lang, cfg) in listOf("LANG_TOP_QS_ITEM" to "qs top", "LANG_LEFT_QS_ITEM" to "qs left",
                    "LANG_BOTTOM_QS_ITEM" to "qs bottom", "LANG_RIGHT_QS_ITEM" to "qs right")) {
                    RockRow(cat.str(lang), icon = G.Cursor) {
                        cat.byCfg[cfg]?.let { store.setText(it, st.cfg ?: "-") }
                        settingMenu = null
                    }
                }
                RockRow(cat.str("LANG_ADD_CURRENT_TO_FAVES"), icon = G.Cursor) {
                    llm.slop.slopbox.Shortcuts(ctx).add(llm.slop.slopbox.Shortcuts.Shortcut(
                        llm.slop.slopbox.Shortcuts.Type.SETTING_APPLY, data = st.cfg ?: "", value = store.cfgText(st)))
                    settingMenu = null
                }
                RockRow(cat.str("LANG_ADD_TO_FAVES"), icon = G.Cursor) {
                    llm.slop.slopbox.Shortcuts(ctx).add(llm.slop.slopbox.Shortcuts.Shortcut(
                        llm.slop.slopbox.Shortcuts.Type.SETTING, data = st.cfg ?: ""))
                    settingMenu = null
                }
            }
        }
        return
    }

    val page = stack.last()
    // The way to the colour settings (Theme Settings, Colours, Line Selector
    // Colours, the picker) stays readable whatever colours are chosen.
    fun leadsToColours(item: RbMenuItem, depth: Int): Boolean =
        item.function in COLOUR_FUNCTIONS || (depth > 0 && item.items.any { leadsToColours(it, depth - 1) })
    val colourPage = page is Page.Colour || page is Page.Themes ||
        (page is Page.Menu && page.item.items.any { leadsToColours(it, 2) })
    androidx.compose.runtime.SideEffect { Cabbie.safeMode = colourPage }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { Cabbie.safeMode = false } }
    Column(Modifier.fillMaxSize()) {
        // The folder picker has its own title bar (its back asks to save).
        if (page !is Page.FolderSel && page !is Page.MainMenuCfg) TitleBar(if (page is Page.ContextItem) cat.str("LANG_ONPLAY_MENU_TITLE") else page.title, onBack = ::back)
        when (page) {
            is Page.Menu -> RockList(Modifier.fillMaxSize()) {
                items(page.item.items) { item ->
                    val setting = cat.settings.getOrNull(item.setting)
                    val ctxItem = contextItem(item)
                    RockRow(
                        when {
                            item.function == "toggle_sleeptimer" -> sleepTimerLabel(cat, store, clock)
                            ctxItem != null -> { @Suppress("UNUSED_EXPRESSION") revision; WpsContext.label(store, ctxItem) }
                            else -> item.text
                        },
                        icon = if (item.isMenu) G.Config else G.Cursor,
                        trailing = if (item.function == "set_color_func") {
                            @Suppress("UNUSED_EXPRESSION") revision
                            "%06X".format(ThemeColours.get(store, ThemeColours.index(item.param)))
                        } else null,
                        dim = item.isSetting && setting != null && !store.isLive(setting),
                        onLongClick = if (item.isSetting && setting != null) ({ settingMenu = item }) else null,
                    ) {
                        when {
                            item.isMenu -> stack += Page.Menu(item)
                            item.isSetting -> openSetting(item)
                            ctxItem != null -> stack += Page.ContextItem(ctxItem)
                            else -> runFunction(item)
                        }
                    }
                }
            }
            is Page.Value -> {
                val current = remember(revision, page) { page.current() }
                val start = page.labels.indexOfFirst { it.first == current }.coerceAtLeast(0)
                val state = rememberLazyListState((start - 3).coerceAtLeast(0))
                page.note?.let { ListNote(it) }
                RockList(Modifier.fillMaxSize(), state = state) {
                    items(page.labels) { (value, label) ->
                        RockRow(label.trim(), selected = value == current) {
                            page.onPick(value)
                            // A pick may open another page (the folder picker); then drop just this one.
                            if (stack.last() === page) back() else stack.remove(page)
                        }
                    }
                }
            }
            is Page.ContextItem -> {
                // Rockbox hotkey_run_menu(HOTKEY_FLAG_WPS, execute = false): pick the action.
                val cur = WpsContext.items(store)[page.item]
                var pickPlugin by remember { mutableStateOf(false) }
                // hotkey_menu_do_setting(): Open Plugin asks which plugin (open_plugin_browse).
                if (pickPlugin) OpenPluginPicker(if (page.item == 0) OpenPlugin.WPS else OpenPlugin.CONTEXT, titleBar = false) { back() }
                else RockList(Modifier.fillMaxSize()) {
                    items(Hotkey.entries.filter { it.wps }) { hk ->
                        RockRow(cat.str(hk.lang), selected = hk == cur) {
                            WpsContext.set(store, page.item, hk)
                            if (hk == Hotkey.PLUGIN) pickPlugin = true else back()
                        }
                    }
                }
            }
            is Page.GraphicalEq -> GraphicalEq(store)
            is Page.Colour -> ColorPicker(store, page.which) { back() }
            is Page.Layouts -> {
                // Rockbox lists .wps files; SlopBox has built-in layouts under skin names.
                @Suppress("UNUSED_EXPRESSION") revision
                val cur = WpsLayout.of(store)
                RockList(Modifier.fillMaxSize()) {
                    items(WpsLayout.entries) { l ->
                        RockRow(l.title, icon = G.Config, sub = l.about, selected = l == cur) {
                            cat.byCfg["wps"]?.let { store.setText(it, l.skin) }
                        }
                    }
                }
            }
            is Page.Themes -> {
                val themes = remember { BundledThemes.list(ctx) }
                val current = remember(revision) { themes.map { it.matches(store) } }
                RockList(Modifier.fillMaxSize()) {
                    itemsIndexed(themes) { i, t ->
                        RockRow(t.name, icon = G.Config, selected = current[i]) {
                            store.loadCfg(t.text)
                            toast(cat.str("LANG_SETTINGS_LOADED"))
                        }
                    }
                    // Theme files of your own, as saved by Save Theme Settings.
                    item { RockRow("Other theme file…", icon = G.TangoFolder) { loader.launch(arrayOf("*/*")) } }
                }
            }
            is Page.MainMenuCfg -> MainMenuConfig { stack.removeAt(stack.lastIndex) }
            is Page.FolderSel -> {
                val target = cat.byCfg[page.cfg]
                FolderSelect(page.title, target?.let { store.text(it) } ?: "") { result ->
                    if (result != null && target != null) store.setText(target, result)
                    else store.set(page.revert, page.revertTo)
                    stack.removeAt(stack.lastIndex)
                }
            }
            is Page.SimpleEq -> RockList(Modifier.fillMaxSize()) {
                @Suppress("UNUSED_EXPRESSION") revision
                items((0 until SettingsStore.EQ_BANDS).toList()) { band ->
                    val b = store.eqBand(band)
                    val title = cat.str("LANG_EQUALIZER_GAIN_ITEM").format(b.cutoff)
                    RockRow(title, trailing = dbFormat(b.gain)) {
                        stack += Page.Value(title, GAIN_LABELS, { store.eqBand(band).gain },
                            { v -> store.setEqBand(band, store.eqBand(band).copy(gain = v)) })
                    }
                }
            }
            is Page.AdvancedEq -> {
                var expanded by remember { mutableIntStateOf(-1) }
                RockList(Modifier.fillMaxSize()) {
                    @Suppress("UNUSED_EXPRESSION") revision
                    for (band in 0 until SettingsStore.EQ_BANDS) {
                        item(key = "b$band") {
                            RockRow(cat.str("LANG_EQUALIZER_BAND").format(band), icon = G.Config,
                                selected = expanded == band) {
                                expanded = if (expanded == band) -1 else band
                            }
                        }
                        if (expanded == band) {
                            val b = store.eqBand(band)
                            val fields = listOf(
                                cat.str(if (b.type == 1) "LANG_EQUALIZER_BAND_CENTER" else "LANG_EQUALIZER_BAND_CUTOFF") to "${b.cutoff} Hz",
                                cat.str("LANG_EQUALIZER_BAND_Q") to qFormat(b.q),
                                cat.str("LANG_GAIN") to dbFormat(b.gain),
                                cat.str("LANG_EQUALIZER_FILTER_TYPE") to typeLabels(store)[b.type].second,
                            )
                            fields.forEachIndexed { f, (name, value) ->
                                item(key = "b$band-$f-$revision") {
                                    RockRow("    $name", trailing = value) { stack += eqValuePage(band, f) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    confirm?.let { (text, action) ->
        Dialog(text, listOf(cat.str("LANG_SET_BOOL_YES") to { action(); confirm = null },
            cat.str("LANG_SET_BOOL_NO") to { confirm = null }))
    }
    message?.let { Dialog(it, listOf("OK" to { message = null })) }
}

/** Rockbox string_sleeptimer(): "Start Sleep Timer (0:30)" / "Cancel Sleep Timer (0:12)". */
private fun sleepTimerLabel(cat: llm.slop.slopbox.RbCatalog, store: SettingsStore, @Suppress("UNUSED_PARAMETER") clock: Int): String {
    val sec = llm.slop.slopbox.PowerMgmt.sleepTimerSeconds
    val minutes = if (sec > 0) (sec + 10) / 60 else store.int("sleeptimer duration")
    val time = if (minutes > 0) "%d:%02d".format(minutes / 60, minutes % 60) else cat.str("LANG_OFF")
    return "%s (%s)".format(cat.str(if (sec > 0) "LANG_SLEEP_TIMER_CANCEL_CURRENT" else "LANG_SLEEP_TIMER_START_CURRENT"), time)
}

private val COLOUR_FUNCTIONS = setOf("set_color_func", "reset_color")

private sealed interface Page {
    val title: String
    data class Menu(val item: RbMenuItem) : Page { override val title get() = item.text }
    data class Value(
        override val title: String,
        val labels: List<Pair<Int, String>>,
        val current: () -> Int,
        val onPick: (Int) -> Unit,
        val note: String? = null,
    ) : Page
    data class SimpleEq(override val title: String) : Page
    data class ContextItem(val item: Int) : Page { override val title get() = "" }
    data class GraphicalEq(override val title: String) : Page
    /** A theme colour's picker; [which] is Rockbox's COLOR_* index. */
    data class Colour(override val title: String, val which: Int) : Page
    data class Themes(override val title: String) : Page
    data class Layouts(override val title: String) : Page
    data class MainMenuCfg(override val title: String) : Page
    /** db_folder_select for a path-list setting; [revert] restores [revertTo] if nothing is saved. */
    data class FolderSel(override val title: String, val cfg: String, val revert: llm.slop.slopbox.RbSetting, val revertTo: Int) : Page
    data class AdvancedEq(override val title: String) : Page
}

/** A Rockbox-style yes/no or message screen drawn over the list. */
@Composable
private fun Dialog(text: String, buttons: List<Pair<String, () -> Unit>>) {
    androidx.compose.ui.window.Dialog(onDismissRequest = { buttons.last().second() }) {
        Column(Modifier.background(Cabbie.backdrop)) {
            ListNote(text)
            buttons.forEachIndexed { i, (label, action) ->
                RockRow(label, selected = i == 0, onClick = action)
            }
        }
    }
}

private fun typeLabels(store: SettingsStore) = listOf(
    0 to store.catalog.str("LANG_EQUALIZER_BAND_LOW_SHELF"),
    1 to store.catalog.str("LANG_EQUALIZER_BAND_PEAK"),
    2 to store.catalog.str("LANG_EQUALIZER_BAND_HIGH_SHELF"),
)

// Ranges and formats from Rockbox apps/menus/eq_menu.[ch].
/** db_format() */
private fun dbFormat(v: Int): String {
    val a = kotlin.math.abs(v)
    return "${if (v < 0) "-" else ""}${a / 10}.${a % 10} dB"
}
/** eq_q_format() */
private fun qFormat(v: Int) = "${v / 10}.${v % 10}"

private val GAIN_LABELS = (-240..240).map { it to dbFormat(it) }
private val Q_LABELS = (1..64).map { it to qFormat(it) }
private val CUTOFF_LABELS = (20..22040 step 10).map { it to "$it Hz" }

/** Rockbox TAGCACHE_FILE_CHANGELOG. */
private const val CHANGELOG = TagDb.CHANGELOG
