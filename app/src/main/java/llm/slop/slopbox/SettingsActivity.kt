package llm.slop.slopbox

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Rockbox's Settings menu, built from Rockbox's own menu tree and settings
 * table (assets/rockbox_settings.json). Works like Rockbox: menus are lists
 * of names; a setting opens a list of its values with the current one marked.
 */
class SettingsActivity : ComponentActivity() {
    private lateinit var store: SettingsStore

    /** What the screen stack holds. */
    private sealed interface Screen {
        val title: String
        data class Menu(val item: RbMenuItem) : Screen { override val title get() = item.text }
        data class Value(
            override val title: String,
            val labels: List<Pair<Int, String>>,
            val current: () -> Int,
            val onPick: (Int) -> Unit,
            val note: String? = null,
        ) : Screen
        data class SimpleEq(override val title: String) : Screen
        data class AdvancedEq(override val title: String) : Screen
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore.get(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Settings() }
            }
        }
    }

    @Composable
    private fun Settings() {
        val stack = remember { mutableStateListOf<Screen>(Screen.Menu(store.catalog.menu)) }
        var revision by remember { mutableIntStateOf(0) }
        var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
        var message by remember { mutableStateOf<String?>(null) }
        var saveMode by remember { mutableIntStateOf(SettingsStore.SAVE_ALL) }

        DisposableEffect(Unit) {
            val l: () -> Unit = { revision++ }
            store.addListener(l)
            onDispose { store.removeListener(l) }
        }
        BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

        val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val ok = runCatching {
                contentResolver.openOutputStream(uri, "wt")!!.use { it.write(store.toCfg(saveMode).toByteArray()) }
            }.isSuccess
            toast(if (ok) store.catalog.str("LANG_SETTINGS_SAVED") else "Couldn't save")
        }
        val loader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val text = runCatching { contentResolver.openInputStream(uri)!!.bufferedReader().readText() }.getOrNull()
            if (text == null) toast("Couldn't read that file")
            else {
                store.loadCfg(text)
                toast(store.catalog.str("LANG_SETTINGS_LOADED"))
            }
        }

        fun runFunction(item: RbMenuItem) {
            val s = store.catalog
            when (item.function) {
                "eq_do_simple_menu" -> stack += Screen.SimpleEq(item.text)
                "eq_do_advanced_menu" -> stack += Screen.AdvancedEq(item.text)
                "eq_reset_defaults" -> { store.resetEq(); toast(item.text) }
                "reset_settings" -> confirm = s.str("LANG_ARE_YOU_SURE") to { store.resetAll() }
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
                else -> message = "${item.text}: not available in SlopBox yet."
            }
        }

        fun openSetting(item: RbMenuItem) {
            val s = store.catalog.settings.getOrNull(item.setting) ?: return
            val note = if (store.isLive(s)) null else "Not used by SlopBox yet; saved with your settings."
            if (s.cfg == "volume limit") {
                // Rockbox shows the volume range for the limit.
                val vol = store.catalog.byCfg["volume"]!!
                stack += Screen.Value(item.text, vol.labels,
                    { store.text(s).trim().toIntOrNull() ?: 0 }, { store.setText(s, it.toString()) }, note)
                return
            }
            if (s.labels.isEmpty()) {
                message = "${item.text}: this kind of setting isn't editable in SlopBox yet."
                return
            }
            stack += Screen.Value(item.text, s.labels, { store.int(s) }, { store.set(s, it) }, note)
        }

        val screen = stack.last()
        Column(Modifier.fillMaxSize()) {
            TitleBar(screen.title, canGoBack = stack.size > 1) { stack.removeAt(stack.lastIndex) }
            @Suppress("UNUSED_EXPRESSION") revision // re-read values when settings change
            when (screen) {
                is Screen.Menu -> MenuList(screen.item, onOpen = { item ->
                    when {
                        item.isMenu -> stack += Screen.Menu(item)
                        item.isSetting -> openSetting(item)
                        else -> runFunction(item)
                    }
                })
                is Screen.Value -> ValueList(screen, revision) { stack.removeAt(stack.lastIndex) }
                is Screen.SimpleEq -> SimpleEq(revision) { band ->
                    val title = store.catalog.str("LANG_EQUALIZER_GAIN_ITEM").format(store.eqBand(band).cutoff)
                    stack += Screen.Value(title, GAIN_LABELS, { store.eqBand(band).gain },
                        { store.setEqBand(band, store.eqBand(band).copy(gain = it)) })
                }
                is Screen.AdvancedEq -> AdvancedEq(revision) { band, field ->
                    stack += eqValueScreen(band, field)
                }
            }
        }

        confirm?.let { (text, action) ->
            AlertDialog(
                onDismissRequest = { confirm = null },
                title = { Text(text) },
                confirmButton = { TextButton(onClick = { action(); confirm = null }) { Text(store.catalog.str("LANG_SET_BOOL_YES")) } },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text(store.catalog.str("LANG_SET_BOOL_NO")) } },
            )
        }
        message?.let {
            AlertDialog(
                onDismissRequest = { message = null },
                text = { Text(it) },
                confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            )
        }
    }

    // ---- Screens -----------------------------------------------------------------

    @Composable
    private fun TitleBar(title: String, canGoBack: Boolean, onBack: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canGoBack) {
                Text("‹", fontSize = 26.sp, modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp))
            }
            Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    }

    @Composable
    private fun ListRow(text: String, trailing: String? = null, selected: Boolean = false, dim: Boolean = false,
                    indent: Int = 0, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth()
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                .clickable(onClick = onClick)
                .padding(start = (16 + indent * 20).dp, end = 16.dp, top = 14.dp, bottom = 14.dp)
                .alpha(if (dim) 0.5f else 1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, Modifier.weight(1f), fontSize = 16.sp)
            if (trailing != null) Text(trailing, fontSize = 16.sp)
        }
        HorizontalDivider(thickness = 0.5.dp)
    }

    @Composable
    private fun MenuList(menu: RbMenuItem, onOpen: (RbMenuItem) -> Unit) {
        LazyColumn {
            itemsIndexed(menu.items) { _, item ->
                val setting = store.catalog.settings.getOrNull(item.setting)
                val dim = item.isSetting && setting != null && !store.isLive(setting)
                ListRow(item.text, trailing = if (item.isMenu) "›" else null, dim = dim) { onOpen(item) }
            }
        }
    }

    @Composable
    private fun ValueList(screen: Screen.Value, revision: Int, done: () -> Unit) {
        val current = remember(revision) { screen.current() }
        val start = screen.labels.indexOfFirst { it.first == current }.coerceAtLeast(0)
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = (start - 3).coerceAtLeast(0))
        Column {
            screen.note?.let {
                Text(it, Modifier.padding(16.dp).alpha(0.7f), fontSize = 13.sp)
            }
            LazyColumn(state = listState) {
                itemsIndexed(screen.labels) { _, (value, label) ->
                    ListRow(label.trim(), selected = value == current) {
                        screen.onPick(value)
                        done()
                    }
                }
            }
        }
    }

    @Composable
    private fun SimpleEq(revision: Int, onBand: (Int) -> Unit) {
        val fmt = store.catalog.str("LANG_EQUALIZER_GAIN_ITEM")
        LazyColumn {
            itemsIndexed((0 until SettingsStore.EQ_BANDS).toList(), key = { i, _ -> "$i-$revision" }) { _, band ->
                val b = store.eqBand(band)
                ListRow(fmt.format(b.cutoff), trailing = dbFormat(b.gain)) { onBand(band) }
            }
        }
    }

    /** Rockbox's advanced EQ: bands that expand into cutoff, Q, gain and type. */
    @Composable
    private fun AdvancedEq(revision: Int, onField: (Int, Int) -> Unit) {
        var expanded by remember { mutableIntStateOf(-1) }
        val c = store.catalog
        LazyColumn {
            for (band in 0 until SettingsStore.EQ_BANDS) {
                item(key = "b$band") {
                    ListRow(c.str("LANG_EQUALIZER_BAND").format(band), trailing = if (expanded == band) "⌄" else "›") {
                        expanded = if (expanded == band) -1 else band
                    }
                }
                if (expanded == band) {
                    val b = store.eqBand(band)
                    @Suppress("UNUSED_EXPRESSION") revision
                    val fields = listOf(
                        (if (b.type == 1) c.str("LANG_EQUALIZER_BAND_CENTER") else c.str("LANG_EQUALIZER_BAND_CUTOFF")) to "${b.cutoff} Hz",
                        c.str("LANG_EQUALIZER_BAND_Q") to qFormat(b.q),
                        c.str("LANG_GAIN") to dbFormat(b.gain),
                        c.str("LANG_EQUALIZER_FILTER_TYPE") to typeLabels()[b.type].second,
                    )
                    fields.forEachIndexed { f, (name, value) ->
                        item(key = "b$band-$f") { ListRow(name, trailing = value, indent = 1) { onField(band, f) } }
                    }
                }
            }
        }
    }

    private fun eqValueScreen(band: Int, field: Int): Screen.Value {
        val c = store.catalog
        val b = store.eqBand(band)
        fun update(f: (EqBand) -> EqBand) = store.setEqBand(band, f(store.eqBand(band)))
        return when (field) {
            0 -> Screen.Value(
                if (b.type == 1) c.str("LANG_EQUALIZER_BAND_CENTER") else c.str("LANG_EQUALIZER_BAND_CUTOFF"),
                CUTOFF_LABELS, { store.eqBand(band).cutoff }, { v -> update { it.copy(cutoff = v) } })
            1 -> Screen.Value(c.str("LANG_EQUALIZER_BAND_Q"), Q_LABELS,
                { store.eqBand(band).q }, { v -> update { it.copy(q = v) } })
            2 -> Screen.Value(c.str("LANG_GAIN"), GAIN_LABELS,
                { store.eqBand(band).gain }, { v -> update { it.copy(gain = v) } })
            else -> Screen.Value(c.str("LANG_EQUALIZER_FILTER_TYPE"), typeLabels(),
                { store.eqBand(band).type }, { v -> update { it.copy(type = v) } })
        }
    }

    private fun typeLabels() = listOf(
        0 to store.catalog.str("LANG_EQUALIZER_BAND_LOW_SHELF"),
        1 to store.catalog.str("LANG_EQUALIZER_BAND_PEAK"),
        2 to store.catalog.str("LANG_EQUALIZER_BAND_HIGH_SHELF"),
    )

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        // Ranges and formats from Rockbox apps/menus/eq_menu.[ch].
        /** db_format() */
        fun dbFormat(v: Int): String {
            val a = kotlin.math.abs(v)
            return "${if (v < 0) "-" else ""}${a / 10}.${a % 10} dB"
        }
        /** eq_q_format() */
        fun qFormat(v: Int) = "${v / 10}.${v % 10}"

        val GAIN_LABELS = (-240..240).map { it to dbFormat(it) }
        val Q_LABELS = (1..64).map { it to qFormat(it) }
        val CUTOFF_LABELS = (20..22040 step 10).map { it to "$it Hz" }
    }
}
