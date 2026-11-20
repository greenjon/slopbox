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
    data class PlayingNext(val doc: Doc) : BrowserMenu
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
            TitleBar("Files", onBack = { nav.pop() }) { BarButton(G.Play) { nav.toNowPlaying() } }
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

    // Rockbox ft_play(): playing a file plays its folder from there, after
    // warn_on_pl_erase() if that would replace a changed playlist.
    var confirmPlay by remember { mutableStateOf<(() -> Unit)?>(null) }
    BackHandler(enabled = confirmPlay != null) { confirmPlay = null }
    confirmPlay?.let { action ->
        val store = remember { llm.slop.slopbox.SettingsStore.get(ctx) }
        Column(Modifier.fillMaxSize()) {
            TitleBar(store.catalog.str("LANG_WARN_ERASEDYNPLAYLIST_PROMPT"), onBack = { confirmPlay = null })
            RockRow(store.catalog.str("LANG_SET_BOOL_YES"), selected = true) { confirmPlay = null; action() }
            RockRow(store.catalog.str("LANG_SET_BOOL_NO")) { confirmPlay = null }
        }
        return
    }

    // Context menus take over the screen, as in Rockbox.
    menu?.let { m ->
        when (m) {
            is BrowserMenu.ForItem -> {
                // Rockbox's context menu for a file or folder.
                val d = m.doc
                val cat = llm.slop.slopbox.SettingsStore.get(ctx).catalog
                Column(Modifier.fillMaxSize()) {
                    TitleBar(if (d.isDir) d.name else d.name.substringBeforeLast('.'), onBack = { menu = null })
                    RockRow(cat.str("LANG_PLAYING_NEXT"), icon = G.Cursor) { menu = BrowserMenu.PlayingNext(d) }
                    RockRow(cat.str("LANG_ADD_TO_PL"), icon = G.Cursor) {
                        menu = null
                        scope.launch {
                            val items = when {
                                d.isPlaylist -> llm.slop.slopbox.PlaylistFiles.load(ctx, tree, d.id, d.name)
                                d.isDir -> MusicTree.walk(ctx, tree, d).map { (f, fd) -> MusicTree.item(tree, f, fd) }
                                else -> listOf(MusicTree.item(tree, d, dir))
                            }
                            PlaylistsView.addPaths = items.mapNotNull { it.requestMetadata.mediaUri }
                                .map { llm.slop.slopbox.PlaybackMemory.pathOf(it) }
                            PlaylistsView.addTitle = d.name.substringBeforeLast('.')
                            nav.push(Screen.AddToPlaylist)
                        }
                    }
                }
            }
            is BrowserMenu.PlayingNext -> {
                val d = m.doc
                PlayingNextMenu(
                    title = if (d.isDir) d.name else d.name.substringBeforeLast('.'),
                    folderLike = d.isDir || d.isPlaylist, recursable = d.isDir,
                    onBack = { menu = null },
                ) { choice, recurse ->
                    menu = null
                    scope.launch {
                        val items = when {
                            d.isPlaylist -> llm.slop.slopbox.PlaylistFiles.load(ctx, tree, d.id, d.name)
                            !d.isDir -> listOf(MusicTree.item(tree, d, dir))
                            recurse -> MusicTree.walk(ctx, tree, d).map { (f, fd) -> MusicTree.item(tree, f, fd) }
                            else -> MusicTree.list(ctx, tree, d.id).filter { it.isTrack }.map { MusicTree.item(tree, it, d) }
                        }
                        val p = llm.slop.slopbox.SlopPlayer.current
                        if (p != null) p.addToPlaylist(items, choice.position, choice.queue, choice.replace)
                        else controller?.let { Queue.play(it, items, 0) }
                        if (choice.replace) nav.toNowPlaying()
                    }
                }
            }
            BrowserMenu.ForFolder -> BrowserMenuScreen(
                dir,
                onClose = { menu = null },
                onChangeRoot = { menu = null; pickRoot() },
                onPlayingNext = { menu = BrowserMenu.PlayingNext(dir) },
            )
        }
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
            onBack = { if (state.path.size > 1) state.path.removeAt(state.path.lastIndex) else nav.pop() },
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
                        icon = if (e.isDir) G.TangoFolder else if (e.isPlaylist) G.ListC else G.TangoNote,
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
                            val files = list.filter { it.isTrack }
                            val play = {
                                if (e.isPlaylist) {
                                    // Rockbox ft_play() on a playlist file: play it from the top.
                                    scope.launch {
                                        val items = llm.slop.slopbox.PlaylistFiles.load(ctx, tree, e.id, e.name)
                                        if (items.isNotEmpty()) { Queue.play(c, items, 0); nav.toNowPlaying() }
                                    }
                                } else {
                                    Queue.play(c, files.map { MusicTree.item(tree, it, dir) }, files.indexOf(e))
                                    nav.toNowPlaying()
                                }
                                Unit
                            }
                            val p = llm.slop.slopbox.SlopPlayer.current
                            val store = llm.slop.slopbox.SettingsStore.get(ctx)
                            if (p != null && p.playlistModified && store.int("warn when erasing dynamic playlist") != 0 &&
                                store.int("party mode") == 0) confirmPlay = play else play()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserMenuScreen(
    dir: Doc,
    onClose: () -> Unit,
    onChangeRoot: () -> Unit,
    onPlayingNext: () -> Unit,
) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        TitleBar(dir.name, onBack = onClose)
        RockRow(llm.slop.slopbox.SettingsStore.get(ctx).catalog.str("LANG_PLAYING_NEXT"), icon = G.Cursor, onClick = onPlayingNext)
        RockRow("Change music folder", icon = G.TangoFolder, onClick = onChangeRoot)
    }
}
