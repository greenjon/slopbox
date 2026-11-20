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
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else nav.pop() }
    BackHandler(enabled = stack.size > 1 || confirm != null || message != null) {
        when {
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

    fun runFunction(item: RbMenuItem) {
        val tree = MusicTree.root(ctx)
        when (item.function) {
            "eq_do_simple_menu" -> stack += Page.SimpleEq(item.text)
            "eq_do_advanced_menu" -> stack += Page.AdvancedEq(item.text)
            "eq_reset_defaults" -> { store.resetEq(); toast(item.text) }
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
                else -> message = "${item.text}: not available in SlopBox yet."
            }
            // General Settings › Database
            "tagcache_rebuild_with_splash", "tagcache_update_with_splash" ->
                if (tree == null) message = "Choose the music folder first (Select directories to scan)."
                else {
                    TagDb.scan(ctx, tree, full = item.function == "tagcache_rebuild_with_splash")
                    toast(item.text)
                }
            "dirs_to_scan" -> pickRoot()
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
            store.set(s, v)
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

    val page = stack.last()
    Column(Modifier.fillMaxSize()) {
        TitleBar(if (page is Page.ContextItem) cat.str("LANG_ONPLAY_MENU_TITLE") else page.title, onBack = ::back)
        when (page) {
            is Page.Menu -> LazyColumn(Modifier.fillMaxSize()) {
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
                        dim = item.isSetting && setting != null && !store.isLive(setting),
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
                LazyColumn(Modifier.fillMaxSize(), state = state) {
                    items(page.labels) { (value, label) ->
                        RockRow(label.trim(), selected = value == current) {
                            page.onPick(value)
                            back()
                        }
                    }
                }
            }
            is Page.ContextItem -> {
                // Rockbox hotkey_run_menu(HOTKEY_FLAG_WPS, execute = false): pick the action.
                val cur = WpsContext.items(store)[page.item]
                LazyColumn(Modifier.fillMaxSize()) {
                    items(Hotkey.entries.filter { it.wps }) { hk ->
                        RockRow(cat.str(hk.lang), selected = hk == cur) { WpsContext.set(store, page.item, hk); back() }
                    }
                }
            }
            is Page.SimpleEq -> LazyColumn(Modifier.fillMaxSize()) {
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
                LazyColumn(Modifier.fillMaxSize()) {
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
