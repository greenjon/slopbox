package llm.slop.slopbox.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.media3.session.MediaController
import kotlinx.coroutines.launch

/** Where the browser is; kept while other screens are on top of it. */
class BrowserState {
    var tree by mutableStateOf<Uri?>(null)
        private set
    /** Folders from the music root down to the one shown. */
    val path = mutableStateListOf<Doc>()
    /** Last line picked in each folder, so coming back puts the selector on it. */
    val cursor = mutableStateMapOf<String, String>()
    val scroll = HashMap<String, Pair<Int, Int>>()

    fun open(ctx: Context, tree: Uri?) {
        this.tree = tree
        path.clear()
        cursor.clear()
        scroll.clear()
        if (tree != null) {
            val id = MusicTree.rootId(tree)
            path += Doc(id, rootName(ctx, tree, id), isDir = true)
        }
    }

    private fun rootName(ctx: Context, tree: Uri, id: String): String = runCatching {
        ctx.contentResolver.query(
            MusicTree.docUri(tree, id), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: "Music"
}

private sealed interface BrowserMenu {
    data class ForItem(val doc: Doc) : BrowserMenu
    data object ForFolder : BrowserMenu
}

@Composable
fun BrowserScreen(controller: MediaController?, nav: Nav, state: BrowserState, pickRoot: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tree = state.tree
    var menu by remember { mutableStateOf<BrowserMenu?>(null) }

    if (tree == null || state.path.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            TitleBar("Files", onBack = null) { BarButton(G.Play) { nav.toNowPlaying() } }
            ListNote("Pick the folder that holds your music.")
            RockRow("Choose music folder", icon = G.TangoFolder, selected = true, onClick = pickRoot)
        }
        return
    }

    val dir = state.path.last()
    var entries by remember(dir.id) { mutableStateOf<List<Doc>?>(null) }
    LaunchedEffect(tree, dir.id) { entries = MusicTree.list(ctx, tree, dir.id) }

    BackHandler(enabled = menu == null && state.path.size > 1) { state.path.removeAt(state.path.lastIndex) }
    BackHandler(enabled = menu != null) { menu = null }

    // Context menus take over the screen, as in Rockbox.
    menu?.let { m ->
        BrowserMenuScreen(
            m, dir,
            onClose = { menu = null },
            onChangeRoot = { menu = null; pickRoot() },
            onAction = { action, doc ->
                menu = null
                val c = controller ?: return@BrowserMenuScreen
                scope.launch {
                    val items = if (doc.isDir) {
                        MusicTree.walk(ctx, tree, doc).map { (f, d) -> MusicTree.item(tree, f, d) }
                    } else listOf(MusicTree.item(tree, doc, dir))
                    when (action) {
                        Action.Play -> { Queue.play(c, items, 0); nav.toNowPlaying() }
                        Action.InsertNext -> Queue.insertNext(c, items)
                        Action.InsertLast -> Queue.insertLast(c, items)
                    }
                }
            },
        )
        return
    }

    val saved = state.scroll[dir.id] ?: (0 to 0)
    val listState = remember(dir.id) { LazyListState(saved.first, saved.second) }
    DisposableEffect(dir.id) {
        onDispose { state.scroll[dir.id] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
    }
    val playingId = controller?.currentMediaItem?.mediaId

    Column(Modifier.fillMaxSize()) {
        TitleBar(
            dir.name,
            onBack = if (state.path.size > 1) ({ state.path.removeAt(state.path.lastIndex) }) else null,
        ) {
            BarButton(G.ListC) { menu = BrowserMenu.ForFolder }
            BarButton(G.Play) { nav.toNowPlaying() }
        }
        val list = entries
        when {
            list == null -> ListNote("Loading…")
            list.isEmpty() -> ListNote("No music in this folder.")
            else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(list, key = { it.id }) { e ->
                    val sel = state.cursor[dir.id]?.let { it == e.id } ?: (e.id == playingId)
                    RockRow(
                        if (e.isDir) e.name else e.name.substringBeforeLast('.'),
                        icon = if (e.isDir) G.TangoFolder else G.TangoNote,
                        selected = sel,
                        onLongClick = {
                            state.cursor[dir.id] = e.id
                            menu = BrowserMenu.ForItem(e)
                        },
                    ) {
                        state.cursor[dir.id] = e.id
                        if (e.isDir) {
                            state.path += e
                        } else {
                            val c = controller ?: return@RockRow
                            // Rockbox: playing a file plays its folder, starting there.
                            val files = list.filter { !it.isDir }
                            Queue.play(c, files.map { MusicTree.item(tree, it, dir) }, files.indexOf(e))
                            nav.toNowPlaying()
                        }
                    }
                }
            }
        }
    }
}

private enum class Action { Play, InsertNext, InsertLast }

@Composable
private fun BrowserMenuScreen(
    m: BrowserMenu,
    dir: Doc,
    onClose: () -> Unit,
    onChangeRoot: () -> Unit,
    onAction: (Action, Doc) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        when (m) {
            is BrowserMenu.ForItem -> {
                val d = m.doc
                TitleBar(if (d.isDir) d.name else d.name.substringBeforeLast('.'), onBack = onClose)
                RockRow("Play", icon = G.Cursor) { onAction(Action.Play, d) }
                RockRow("Insert next", icon = G.TangoNote) { onAction(Action.InsertNext, d) }
                RockRow("Insert last", icon = G.TangoNote) { onAction(Action.InsertLast, d) }
            }
            BrowserMenu.ForFolder -> {
                TitleBar(dir.name, onBack = onClose)
                RockRow("Play this folder", icon = G.Cursor) { onAction(Action.Play, dir) }
                RockRow("Insert folder last", icon = G.TangoNote) { onAction(Action.InsertLast, dir) }
                RockRow("Change music folder", icon = G.TangoFolder, onClick = onChangeRoot)
            }
        }
    }
}
