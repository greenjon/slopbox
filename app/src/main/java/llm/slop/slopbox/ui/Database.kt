package llm.slop.slopbox.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import llm.slop.slopbox.ui.db.Level
import llm.slop.slopbox.ui.db.Query
import llm.slop.slopbox.ui.db.TagDb
import llm.slop.slopbox.ui.db.TagNavi
import llm.slop.slopbox.ui.db.Track

/** Where the database browser is; kept while other screens are on top. */
class DatabaseState {
    sealed interface Node {
        data class InMenu(val menu: TagNavi.Menu) : Node
        data class InLevel(val level: Level) : Node
        /** Asking for the "" values of an entry's clauses, one at a time. */
        data class Ask(val entry: TagNavi.Entry.Chain, val clauses: List<TagNavi.Clause>, val answers: Map<TagNavi.Clause, String>) : Node
    }

    var navi by mutableStateOf<TagNavi?>(null)
        private set
    val stack = mutableStateListOf<Node>()
    /** Last line picked per list, for the selector bar. */
    val cursor = HashMap<String, Int>()

    fun load(ctx: Context) {
        if (navi != null) return
        val text = runCatching { ctx.assets.open("rockbox/tagnavi.config").bufferedReader().readText() }.getOrDefault("")
        // %include "/.rockbox/tagnavi_custom.config": looked for in the app's files folder.
        val n = TagNavi.parse(text) { path ->
            java.io.File(ctx.filesDir, path.substringAfterLast('/')).takeIf { it.isFile }?.readText()
        }
        navi = n
        stack.clear()
        (n.root?.let { n.menu(it) })?.let { stack += Node.InMenu(it) }
    }
}

private fun Track.toItem(ctx: Context): MediaItem? {
    val tree = MusicTree.root(ctx) ?: return null
    val base = MusicTree.item(tree, Doc(id, name, false), Doc(dir, dirName, true))
    return base.buildUpon().setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title.ifEmpty { name.substringBeforeLast('.') })
            .setArtist(artist.ifEmpty { null })
            .setAlbumTitle(album.ifEmpty { null })
            .build()
    ).build()
}

private enum class DbAction { Play, InsertNext, InsertLast }

@Composable
fun DatabaseScreen(controller: MediaController?, nav: Nav, state: DatabaseState, browser: BrowserState) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        TagDb.load(ctx)
        state.load(ctx)
    }
    val navi = state.navi
    var menu by remember { mutableStateOf<Pair<String, List<Track>>?>(null) }
    var playingNext by remember { mutableStateOf(false) }
    var dbMenu by remember { mutableStateOf(false) }

    BackHandler(enabled = menu == null && !dbMenu && state.stack.size > 1) { state.stack.removeAt(state.stack.lastIndex) }
    BackHandler(enabled = menu != null || dbMenu) { if (playingNext) playingNext = false else menu = null; dbMenu = false }

    fun act(a: DbAction, tracks: List<Track>) {
        val c = controller ?: return
        val items = tracks.mapNotNull { it.toItem(ctx) }
        when (a) {
            DbAction.Play -> { Queue.play(c, items, 0); nav.toNowPlaying() }
            DbAction.InsertNext -> Queue.insertNext(c, items)
            DbAction.InsertLast -> Queue.insertLast(c, items)
        }
    }

    // Full-screen menus, as in Rockbox: the context menu (tree_onplay_menu in
    // CONTEXT_ID3DB: Playing Next, Add to Playlist, Show Track Info, Show in Files).
    menu?.let { (title, tracks) ->
        if (playingNext) {
            PlayingNextMenu(title, folderLike = tracks.size > 1, recursable = false, onBack = { playingNext = false }) { choice, _ ->
                menu = null; playingNext = false
                val items = tracks.mapNotNull { it.toItem(ctx) }
                val p = llm.slop.slopbox.SlopPlayer.current
                if (p != null) p.addToPlaylist(items, choice.position, choice.queue, choice.replace)
                else controller?.let { Queue.play(it, items, 0) }
                if (choice.replace) nav.toNowPlaying()
            }
            return
        }
        val cat = llm.slop.slopbox.SettingsStore.get(ctx).catalog
        val single = tracks.singleOrNull()
        Column(Modifier.fillMaxSize()) {
            TitleBar(title, onBack = { menu = null })
            RockRow(cat.str("LANG_PLAYING_NEXT"), icon = G.Cursor) { playingNext = true }
            RockRow(cat.str("LANG_ADD_TO_PL"), icon = G.Cursor) {
                PlaylistsView.addPaths = tracks.mapNotNull { it.toItem(ctx)?.requestMetadata?.mediaUri }
                    .map { llm.slop.slopbox.PlaybackMemory.pathOf(it) }
                PlaylistsView.addTitle = title
                menu = null
                nav.push(Screen.AddToPlaylist)
            }
            if (single != null) {
                RockRow(cat.str("LANG_MENU_SHOW_ID3_INFO"), icon = G.Cursor) {
                    TrackInfoView.item = single.toItem(ctx)
                    menu = null
                    nav.push(Screen.TrackInfo)
                }
                RockRow(cat.str("LANG_SHOW_IN_FILES"), icon = G.Cursor) {
                    val tree = MusicTree.root(ctx)
                    menu = null
                    if (tree != null && browser.reveal(ctx, tree, single.id)) nav.toFiles()
                    else android.widget.Toast.makeText(ctx, cat.str("LANG_FILE_NOT_FOUND"), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        return
    }
    if (dbMenu) {
        DatabaseSettings(onBack = { dbMenu = false }, nav = nav)
        return
    }

    val node = state.stack.lastOrNull()
    val title = when (node) {
        is DatabaseState.Node.InMenu -> node.menu.title
        is DatabaseState.Node.InLevel -> node.level.title
        is DatabaseState.Node.Ask -> node.entry.label
        null -> "Database"
    }
    Column(Modifier.fillMaxSize()) {
        TitleBar(title, onBack = { if (state.stack.size > 1) state.stack.removeAt(state.stack.lastIndex) else nav.pop() }) {
            BarButton(G.ListC) { dbMenu = true }
            BarButton(G.Play) { nav.toNowPlaying() }
        }
        TagDb.progress?.let { ListNote(it) }
        when {
            navi == null || !TagDb.loaded -> ListNote("Loading…")
            node == null -> ListNote("No database menus found.")
            TagDb.tracks.isEmpty() && !TagDb.scanning -> EmptyDatabase(nav)
            node is DatabaseState.Node.Ask -> AskScreen(node) { answers ->
                state.stack.removeAt(state.stack.lastIndex)
                open(ctx, controller, nav, state, node.entry, answers) { act(DbAction.Play, it) }
            }
            node is DatabaseState.Node.InMenu -> {
                val entries = node.menu.entries.filter { it !is TagNavi.Entry.Sub || navi.menu(it.menuId) != null }
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(entries) { i, e ->
                        RockRow(e.label, icon = G.TangoFolder, selected = state.cursor[node.menu.id] == i) {
                            state.cursor[node.menu.id] = i
                            when (e) {
                                is TagNavi.Entry.Sub -> navi.menu(e.menuId)?.let { state.stack += DatabaseState.Node.InMenu(it) }
                                is TagNavi.Entry.Chain -> {
                                    val asks = e.steps.flatMap { it.cond?.clauses.orEmpty() }.filter { it.asksUser }
                                    if (asks.isNotEmpty()) state.stack += DatabaseState.Node.Ask(e, asks, emptyMap())
                                    else open(ctx, controller, nav, state, e, emptyMap()) { act(DbAction.Play, it) }
                                }
                            }
                        }
                    }
                }
            }
            node is DatabaseState.Node.InLevel -> LevelList(controller, node.level, state, navi, onPlayed = { nav.toNowPlaying() }) { t, tracks -> menu = t to tracks }
        }
    }
}

private fun playing(controller: MediaController?): Track? {
    val id = controller?.currentMediaItem?.mediaId ?: return null
    return TagDb.tracks.firstOrNull { it.id == id }
}

private fun open(
    ctx: Context, controller: MediaController?, nav: Nav, state: DatabaseState,
    entry: TagNavi.Entry.Chain, inputs: Map<TagNavi.Clause, String>, play: (List<Track>) -> Unit,
) {
    val level = Level(entry, 0, emptyList(), inputs, entry.label)
    if (entry.shuffle) {
        // "Shuffle Songs": everything the entry matches, shuffled, playing now.
        val q = Query(state.navi ?: return, TagDb.tracks, TagDb.commits, playing(controller))
        play(q.allTracks(level).shuffled())
        return
    }
    state.stack += DatabaseState.Node.InLevel(level)
}

@Composable
private fun LevelList(
    controller: MediaController?,
    level: Level,
    state: DatabaseState,
    navi: TagNavi,
    onPlayed: () -> Unit,
    onMenu: (String, List<Track>) -> Unit,
) {
    val ctx = LocalContext.current
    val tracks = TagDb.tracks
    val playingTrack = playing(controller)
    val statsRev = TagDb.statsRevision
    val query = remember(tracks, navi, playingTrack?.id, statsRev) { Query(navi, tracks, TagDb.commits, playingTrack) }
    val key = level.entry.label + "/" + level.step + "/" + level.picks.joinToString("\u0001")

    if (level.isTrackList) {
        val lines by produceState<List<Pair<String, Track>>?>(null, query, key) {
            value = withContext(Dispatchers.Default) { query.trackLines(level) }
        }
        val l = lines
        when {
            l == null -> ListNote("Loading…")
            l.isEmpty() -> ListNote("No tracks.")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(l) { i, (label, t) ->
                    RockRow(
                        label, icon = if (t.id == playingTrack?.id) G.Cursor else G.TangoNote,
                        selected = state.cursor[key] == i,
                        onLongClick = { state.cursor[key] = i; onMenu(label, listOf(t)) },
                    ) {
                        state.cursor[key] = i
                        val c = controller ?: return@RockRow
                        // Rockbox: picking a track plays the whole list from there.
                        Queue.play(c, l.mapNotNull { it.second.toItem(ctx) }, i)
                        onPlayed()
                    }
                }
            }
        }
        return
    }

    val values by produceState<List<String>?>(null, query, key) {
        value = withContext(Dispatchers.Default) { query.values(level) }
    }
    val v = values ?: run { ListNote("Loading…"); return }
    fun next(value: String?) = Level(
        level.entry, level.step + 1, level.picks + value, level.inputs, value ?: level.title,
    )
    LazyColumn(Modifier.fillMaxSize()) {
        // tagtree.c puts these first in every non-track list.
        item {
            RockRow("[All tracks]", icon = G.TangoNote, selected = state.cursor[key] == -1,
                onLongClick = { onMenu(level.title, query.allTracks(next(null))) }) {
                state.cursor[key] = -1
                val last = level.entry.steps.lastIndex
                state.stack += DatabaseState.Node.InLevel(
                    Level(level.entry, last, level.picks + List(last - level.picks.size) { null }, level.inputs, level.title)
                )
            }
        }
        item {
            RockRow("[Random]", icon = G.TangoNote, selected = state.cursor[key] == -2) {
                state.cursor[key] = -2
                v.randomOrNull()?.let { state.stack += DatabaseState.Node.InLevel(next(it)) }
            }
        }
        itemsIndexed(v) { i, value ->
            RockRow(
                value, icon = G.TangoFolder, selected = state.cursor[key] == i,
                onLongClick = { state.cursor[key] = i; onMenu(value, query.allTracks(next(value))) },
            ) {
                state.cursor[key] = i
                state.stack += DatabaseState.Node.InLevel(next(value))
            }
        }
    }
}

@Composable
private fun AskScreen(node: DatabaseState.Node.Ask, onDone: (Map<TagNavi.Clause, String>) -> Unit) {
    var answers by remember(node) { mutableStateOf(node.answers) }
    val current = node.clauses.firstOrNull { it !in answers } ?: return
    var text by remember(current) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(current) { runCatching { focus.requestFocus() } }
    val opWord = when (current.op) {
        "~" -> "contains"; "^" -> "begins with"; "$" -> "ends with"
        ">" -> "greater than"; ">=" -> "from"; "<" -> "less than"; "<=" -> "up to"
        else -> "is"
    }
    fun submit() {
        val next = answers + (current to text.trim())
        if (node.clauses.all { it in next }) onDone(next) else answers = next
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        BasicText("${current.tag.replaceFirstChar { it.uppercase() }} $opWord:", style = Cabbie.body)
        BasicTextField(
            text, { text = it },
            Modifier.fillMaxWidth().padding(vertical = 10.dp)
                .background(Cabbie.Track, RoundedCornerShape(6.dp)).padding(12.dp).focusRequester(focus),
            textStyle = Cabbie.body,
            cursorBrush = SolidColor(Cabbie.SelTop),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (current.tag in TagNavi.NUMERIC) KeyboardType.Number else KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        RockRow("OK", icon = G.Cursor, selected = true) { submit() }
    }
}

@Composable
private fun EmptyDatabase(nav: Nav) {
    val ctx = LocalContext.current
    ListNote("The database is empty.")
    val tree = MusicTree.root(ctx)
    if (tree == null) {
        RockRow("Choose music folder first", icon = G.TangoFolder) { nav.toFiles() }
    } else {
        RockRow("Initialize Now", icon = G.Cursor, selected = true) { TagDb.scan(ctx, tree, full = true) }
    }
}

/** Settings › Database, Rockbox style. */
@Composable
fun DatabaseSettings(onBack: () -> Unit, nav: Nav) {
    val ctx = LocalContext.current
    val tree = MusicTree.root(ctx)
    Column(Modifier.fillMaxSize()) {
        TitleBar("Database", onBack = onBack)
        TagDb.progress?.let { ListNote(it) }
        if (tree == null) {
            RockRow("Choose music folder first", icon = G.TangoFolder) { nav.toFiles() }
        } else {
            RockRow("Initialize Now", sub = "Re-read every file", icon = G.Cursor, enabled = !TagDb.scanning) {
                TagDb.scan(ctx, tree, full = true)
            }
            RockRow("Update Now", sub = "Read new and changed files", icon = G.Cursor, enabled = !TagDb.scanning) {
                TagDb.scan(ctx, tree, full = false)
            }
        }
        ListNote("${TagDb.tracks.size} tracks in the database.")
    }
}
