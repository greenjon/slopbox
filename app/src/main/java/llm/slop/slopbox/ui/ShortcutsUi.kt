package llm.slop.slopbox.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import llm.slop.slopbox.FileOps
import llm.slop.slopbox.PlaylistFiles
import llm.slop.slopbox.PowerMgmt
import llm.slop.slopbox.SettingsStore
import llm.slop.slopbox.Shortcuts
import llm.slop.slopbox.Shortcuts.Type
import llm.slop.slopbox.SlopPlayer

private fun str(ctx: Context, id: String) = SettingsStore.get(ctx).catalog.str(id)
private fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()

/** Rockbox's sleep timer text for "time" shortcuts (format_sleeptimer()). */
private fun sleepText(ctx: Context, minutes: Int) =
    if (minutes > 0) "%d:%02d".format(minutes / 60, minutes % 60) else str(ctx, "LANG_OFF")

/** Rockbox shortcut_menu_get_name(). */
private fun shortcutName(ctx: Context, sc: Shortcuts.Shortcut): String {
    val store = SettingsStore.get(ctx)
    val setting = store.catalog.byCfg[sc.data]
    return when {
        sc.type == Type.SETTING && sc.name.isEmpty() -> setting?.title ?: sc.data
        sc.type == Type.SETTING_APPLY && sc.name.isEmpty() -> "${setting?.title ?: sc.data} (${sc.value})"
        sc.type == Type.SEPARATOR -> sc.name
        sc.type == Type.TIME -> when {
            sc.sleepTimeout != null && sc.sleepTimeout < 0 -> {
                val sec = PowerMgmt.sleepTimerSeconds
                val minutes = if (sec > 0) (sec + 10) / 60 else store.int("sleeptimer duration")
                "%s (%s)".format(str(ctx, if (sec > 0) "LANG_SLEEP_TIMER_CANCEL_CURRENT" else "LANG_SLEEP_TIMER_START_CURRENT"),
                    sleepText(ctx, minutes))
            }
            sc.name.isNotEmpty() -> sc.name
            sc.sleepTimeout == null -> str(ctx, "LANG_CURRENT_TIME")
            else -> "%s (%s)".format(str(ctx, "LANG_SLEEP_TIMER"), sleepText(ctx, sc.sleepTimeout))
        }
        (sc.type == Type.SHUTDOWN || sc.type == Type.REBOOT) && sc.name.isEmpty() -> sc.type.text
        sc.type == Type.BROWSER && sc.name.isEmpty() && sc.data.isNotEmpty() ->
            "${sc.data.trimEnd('/').substringAfterLast('/')} (${sc.data})"
        else -> sc.name.ifEmpty { sc.data }
    }
}

/** Rockbox shortcut_menu_get_icon(), in SlopBox's glyphs. */
private fun shortcutIcon(sc: Shortcuts.Shortcut): G? = when (sc.type) {
    Type.SETTING, Type.SETTING_APPLY, Type.SHUTDOWN, Type.REBOOT -> G.Config
    Type.BROWSER -> if (sc.data.endsWith("/")) G.TangoFolder else G.TangoNote
    Type.FILE -> if (PlaylistFiles.isPlaylist(sc.data)) G.ListC else G.TangoNote
    Type.PLAYLISTMENU -> G.ListC
    Type.DEBUGITEM, Type.TIME -> G.Cursor
    Type.SEPARATOR -> null
}

/** What the shortcut menu asks the app to do next. */
object ShortcutView {
    /** A setting to open on its own (SHORTCUT_SETTING: do_setting_screen()). */
    var setting by mutableStateOf<String?>(null)
}

/**
 * Rockbox do_shortcut_menu(): the shortcuts, each run when picked; a long
 * press deletes one (after "Delete?"). With none, "No files" and back.
 */
@Composable
fun ShortcutsScreen(controller: MediaController?, nav: Nav, browser: BrowserState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SettingsStore.get(ctx) }
    val shortcuts = remember { Shortcuts(ctx) }
    var rev by remember { mutableIntStateOf(0) }
    var clock by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); clock++ } }
    // verify_shortcut(): settings must exist here.
    val list = remember(rev) {
        shortcuts.load().withIndex().filter { (_, sc) ->
            (sc.type != Type.SETTING && sc.type != Type.SETTING_APPLY) || store.catalog.byCfg[sc.data] != null
        }
    }
    var confirmDelete by remember { mutableStateOf<Int?>(null) }
    var playingNext by remember { mutableStateOf<Doc?>(null) }

    LaunchedEffect(list.isEmpty()) {
        if (list.isEmpty()) { toast(ctx, str(ctx, "LANG_NO_FILES")); nav.pop() }
    }
    BackHandler(enabled = confirmDelete != null || playingNext != null) { confirmDelete = null; playingNext = null }

    confirmDelete?.let { i ->
        // confirm_delete_yesno("", str(LANG_SHORTCUTS))
        YesNo(str(ctx, "LANG_SHORTCUTS"), listOf(str(ctx, "LANG_REALLY_DELETE"))) { yes ->
            confirmDelete = null
            if (yes) { shortcuts.remove(i); rev++ }
        }
        return
    }
    playingNext?.let { d ->
        val tree = MusicTree.root(ctx) ?: run { playingNext = null; return }
        PlayingNextMenu(if (d.isDir) d.name else d.name.substringBeforeLast('.'), folderLike = d.isDir || d.isPlaylist,
            recursable = d.isDir, onBack = { playingNext = null }) { choice, recurse ->
            playingNext = null
            scope.launch {
                val items = when {
                    d.isPlaylist -> PlaylistFiles.load(ctx, tree, d.id, d.name)
                    !d.isDir -> listOf(MusicTree.item(tree, d, Doc(d.id.substringBeforeLast('/'), "", true)))
                    recurse -> MusicTree.walk(ctx, tree, d).map { (f, fd) -> MusicTree.item(tree, f, fd) }
                    else -> MusicTree.list(ctx, tree, d.id).filter { it.isTrack }.map { MusicTree.item(tree, it, d) }
                }
                val p = SlopPlayer.current
                if (p != null) p.addToPlaylist(items, choice.position, choice.queue, choice.replace)
                else controller?.let { Queue.play(it, items, 0) }
                if (choice.replace) nav.toNowPlaying()
            }
        }
        return
    }

    /** The document for a storage path in the music folder, if it exists. */
    fun docFor(path: String): Doc? {
        val tree = MusicTree.root(ctx) ?: return null
        val id = PlaylistFiles.docIdOf(tree, path.trimEnd('/'))
        val info = FileOps.info(ctx, tree, id) ?: return null
        return Doc(id, info.name, info.isDir)
    }

    fun run(sc: Shortcuts.Shortcut) {
        val tree = MusicTree.root(ctx)
        when (sc.type) {
            Type.PLAYLISTMENU -> docFor(sc.data)?.let { playingNext = it } ?: toast(ctx, str(ctx, "LANG_NO_FILES"))
            Type.FILE -> {
                val d = docFor(sc.data)
                if (d == null || tree == null) { toast(ctx, str(ctx, "LANG_NO_FILES")); return }
                // BROWSE_RUNFILE: open the file as the browser would (play it).
                scope.launch {
                    val c = controller ?: return@launch
                    if (d.isPlaylist) {
                        Queue.play(c, PlaylistFiles.load(ctx, tree, d.id, d.name), 0)
                    } else if (d.isDir) {
                        if (browser.openDir(ctx, tree, d.id)) nav.toFiles()
                        return@launch
                    } else {
                        val dir = Doc(d.id.substringBeforeLast('/'), "", true)
                        val files = MusicTree.list(ctx, tree, dir.id).filter { it.isTrack }
                        Queue.play(c, files.map { MusicTree.item(tree, it, dir) }, files.indexOfFirst { it.id == d.id }.coerceAtLeast(0))
                    }
                    nav.toNowPlaying()
                }
            }
            Type.BROWSER -> {
                val d = docFor(sc.data)
                if (d == null || tree == null) { toast(ctx, str(ctx, "LANG_NO_FILES")); return }
                // A folder opens in the browser; a file opens its folder with it selected.
                val ok = if (d.isDir) browser.openDir(ctx, tree, d.id) else browser.reveal(ctx, tree, d.id)
                if (ok) nav.toFiles() else toast(ctx, str(ctx, "LANG_NO_FILES"))
            }
            Type.SETTING_APPLY -> store.loadCfg("${sc.data}: ${sc.value}")
            Type.SETTING -> { ShortcutView.setting = sc.data; nav.push(Screen.SettingsAt) }
            Type.DEBUGITEM -> toast(ctx, "SlopBox has no debug screens.")
            Type.SHUTDOWN -> PowerMgmt.powerOffNow()
            Type.REBOOT -> toast(ctx, "SlopBox can't reboot the phone.")
            Type.TIME -> when {
                sc.sleepTimeout == null -> {}       // "talk": SlopBox has no voice
                sc.sleepTimeout < 0 -> PowerMgmt.toggleSleepTimer()
                else -> {
                    PowerMgmt.setSleepTimer(sc.sleepTimeout)
                    toast(ctx, "%s (%s)".format(str(ctx, "LANG_SLEEP_TIMER"), sleepText(ctx, sc.sleepTimeout)))
                }
            }
            Type.SEPARATOR -> {}
        }
        clock++
    }

    Column(Modifier.fillMaxSize()) {
        TitleBar(str(ctx, "LANG_SHORTCUTS"), onBack = { nav.pop() })
        @Suppress("UNUSED_EXPRESSION") clock
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(list) { _, (index, sc) ->
                RockRow(shortcutName(ctx, sc), icon = if (store.int("show icons") != 0) shortcutIcon(sc) else null,
                    onLongClick = { confirmDelete = index }) { run(sc) }
            }
        }
    }
}
