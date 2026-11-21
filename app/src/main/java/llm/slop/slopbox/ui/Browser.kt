/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/tree.c - Copyright (C) 2002 Daniel Stenberg
 *   apps/filetree.c - Copyright (C) 2005 by Björn Stenberg
 *   apps/onplay.c - Copyright (C) 2002 Björn Stenberg
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
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
    /** Bumped after a file operation so the shown folder is listed again. */
    var reload by mutableIntStateOf(0)

    /**
     * Rockbox global_status.browse_last_folder: kept across restarts only
     * with "Always remember last folder" (keep directory).
     */
    fun remember(ctx: Context) {
        val st = llm.slop.slopbox.SettingsStore.get(ctx)
        val prefs = ctx.getSharedPreferences("global_status", Context.MODE_PRIVATE)
        if (st.int("keep directory") != 0) prefs.edit().putString("browse_last_folder", path.lastOrNull()?.id).apply()
        else prefs.edit().remove("browse_last_folder").apply()
    }

    fun open(ctx: Context, tree: Uri?) {
        this.tree = tree
        path.clear()
        cursor.clear()
        scroll.clear()
        if (tree != null) {
            val id = MusicTree.rootId(tree)
            path += Doc(id, rootName(ctx, tree, id), isDir = true)
            // Rockbox root_menu browser(): the last folder if remembered, else
            // "Start Directory" (Set As... › Start Directory).
            val st = llm.slop.slopbox.SettingsStore.get(ctx)
            val last = ctx.getSharedPreferences("global_status", Context.MODE_PRIVATE).getString("browse_last_folder", null)
                ?.takeIf { st.int("keep directory") != 0 }
            val start = st.catalog.byCfg["start directory"]?.let { st.text(it) }
            if (last != null && last != id) descend(last, includeLast = true)
            else if (start != null && start.trim('/').isNotEmpty()) {
                descend(llm.slop.slopbox.PlaylistFiles.docIdOf(tree, start.trimEnd('/')), includeLast = true)
            }
        }
    }

    /** Open the folders from the root down to [id] (and [id] itself if [includeLast]). */
    private fun descend(id: String, includeLast: Boolean): Boolean {
        val root = path.firstOrNull() ?: return false
        val prefix = if (root.id.endsWith(":")) root.id else root.id + "/"
        if (!id.startsWith(prefix)) return false
        val segs = id.removePrefix(prefix).split('/').filter { it.isNotEmpty() }
        val dirs = if (includeLast) segs else segs.dropLast(1)
        while (path.size > 1) path.removeAt(path.lastIndex)
        var cur = prefix.removeSuffix("/")
        for (seg in dirs) {
            cur = if (cur.endsWith(":")) cur + seg else "$cur/$seg"
            path += Doc(cur, seg, isDir = true)
        }
        return true
    }

    /** Open the browser at a folder of the music tree. */
    fun openDir(ctx: Context, tree: Uri, dirId: String): Boolean {
        if (this.tree != tree) open(ctx, tree)
        return dirId == path.firstOrNull()?.id || descend(dirId, includeLast = true)
    }

    /** Rockbox reveal() / "Show in Files": open the file's folder with the file selected. */
    fun reveal(ctx: Context, tree: Uri, fileId: String): Boolean {
        if (this.tree != tree) open(ctx, tree)
        if (!descend(fileId, includeLast = false)) return false
        cursor[path.last().id] = fileId
        scroll.remove(path.last().id)
        return true
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

/** A track for the browser to open at (Rockbox "Follow Playlist"). */
object FollowPlaylist {
    var pending: String? = null
}

@Composable
fun BrowserScreen(controller: MediaController?, nav: Nav, state: BrowserState, pickRoot: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tree = state.tree
    LaunchedEffect(Unit) {
        val id = FollowPlaylist.pending
        FollowPlaylist.pending = null
        if (id != null && tree != null) state.reveal(ctx, tree, id)
    }
    var menu by remember { mutableStateOf<BrowserMenu?>(null) }

    if (tree == null || state.path.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            TitleBar("Files", onBack = { nav.pop() }) { NowPlayingButton(nav) }
            ListNote("Pick the folder that holds your music.")
            RockRow("Choose music folder", icon = G.TangoFolder, selected = true, onClick = pickRoot)
        }
        return
    }

    val dir = state.path.last()
    var entries by remember(dir.id) { mutableStateOf<List<Doc>?>(null) }
    val fv = remember { llm.slop.slopbox.SettingsStore.get(ctx) }
    var fvRev by remember { mutableStateOf(0) }
    DisposableEffect(fv) {
        val l: () -> Unit = { fvRev++ }
        fv.addListener(l)
        onDispose { fv.removeListener(l) }
    }
    val showFiles = fv.int("show files")
    LaunchedEffect(tree, dir.id, state.reload, fvRev) {
        // Show Files (Rockbox's dirfilter); dot files only with All.
        val all = MusicTree.list(ctx, tree, dir.id, hidden = showFiles == 0).filter { FileView.visible(it, showFiles) }
        // Rockbox "Max Entries in File Browser": the rest are left out, with "Dir Buffer is Full!".
        val max = fv.int("max files in dir")
        if (all.size > max) android.widget.Toast.makeText(ctx, fv.catalog.str("LANG_SHOWDIR_BUFFER_FULL"),
            android.widget.Toast.LENGTH_SHORT).show()
        entries = all.take(max)
        state.remember(ctx)
    }
    var viewing by remember { mutableStateOf<Doc?>(null) }
    BackHandler(enabled = viewing != null) { viewing = null }
    viewing?.let { v -> FileViewer(tree, v, nav) { viewing = null }; return }

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
                val d = m.doc
                TreeContextMenu(
                    tree, dir, d, TreeKind.FILES, nav,
                    onClose = { changed -> menu = null; if (changed) state.reload++ },
                    onView = {
                        // Rockbox view_playlist(): the playlist viewer on that file.
                        menu = null
                        PlaylistsView.viewFile = d
                        nav.push(Screen.Playlists)
                    },
                    onPlayingNext = { menu = BrowserMenu.PlayingNext(d) },
                )
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
                tree, dir,
                onClose = { changed -> menu = null; if (changed) state.reload++ },
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
            FileView.title(state.path, fv),
            onBack = { if (state.path.size > 1) state.path.removeAt(state.path.lastIndex) else nav.pop() },
            titleContent = if (fv.int("show path in browser") == 0) null else ({
                Breadcrumbs(state.path, full = fv.int("show path in browser") == 2) { k ->
                    while (state.path.size > k + 1) state.path.removeAt(state.path.lastIndex)
                }
            }),
        ) {
            BarButton(G.ListC) { menu = BrowserMenu.ForFolder }
            NowPlayingButton(nav)
        }
        val list = entries
        when {
            list == null -> ListNote("Loading…")
            list.isEmpty() -> ListNote(fv.catalog.str("LANG_NO_FILES"))
            else -> RockList(Modifier.fillMaxSize(), state = listState, label = { i -> list.getOrNull(i)?.let { initial(FileView.displayName(it, fv)) } }) {
                items(list, key = { it.id }) { e ->
                    val sel = state.cursor[dir.id]?.let { it == e.id } ?: (e.id == playingId)
                    RockRow(
                        FileView.displayName(e, fv),
                        icon = when {
                            e.isDir -> G.TangoFolder
                            e.isPlaylist -> G.ListC
                            e.isTrack -> G.TangoNote
                            FileView.supported(e) -> G.Config
                            else -> null
                        },
                        selected = sel,
                        onLongClick = {
                            state.cursor[dir.id] = e.id
                            menu = BrowserMenu.ForItem(e)
                        },
                    ) {
                        state.cursor[dir.id] = e.id
                        if (e.isDir) {
                            state.path += e
                        } else if (!e.isTrack && !e.isPlaylist) {
                            // Rockbox ft_enter() for the other known types; unknown ones do nothing.
                            viewing = e
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

/**
 * The menu for the folder being shown (SlopBox's; Rockbox reaches these from
 * any item's context menu): Playing Next on the folder, Paste and Create
 * Directory here, and changing the music folder.
 */
@Composable
private fun BrowserMenuScreen(
    tree: Uri,
    dir: Doc,
    onClose: (changed: Boolean) -> Unit,
    onChangeRoot: () -> Unit,
    onPlayingNext: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cat = llm.slop.slopbox.SettingsStore.get(ctx).catalog
    var naming by remember { mutableStateOf(false) }
    var overwriteAsk by remember { mutableStateOf(false) }
    fun failed(op: String) = android.widget.Toast.makeText(ctx, cat.str(op) + " " + cat.str("LANG_FAILED"),
        android.widget.Toast.LENGTH_SHORT).show()
    fun paste(overwrite: Boolean) = scope.launch {
        if (!llm.slop.slopbox.FileOps.paste(ctx, tree, dir.id, overwrite)) failed("LANG_PASTE")
        onClose(true)
    }
    if (naming) {
        NameEntry(cat.str("LANG_CREATE_DIR"), "") { n ->
            naming = false
            if (n != null) {
                if (llm.slop.slopbox.FileOps.mkdir(ctx, tree, dir.id, n) == null) failed("LANG_CREATE_DIR")
                onClose(true)
            }
        }
        return
    }
    if (overwriteAsk) {
        YesNo(cat.str("LANG_REALLY_OVERWRITE")) { yes -> overwriteAsk = false; if (yes) paste(true) else onClose(false) }
        return
    }
    val clip = llm.slop.slopbox.FileOps.Clipboard.doc
    Column(Modifier.fillMaxSize()) {
        TitleBar(dir.name, onBack = { onClose(false) })
        RockRow(cat.str("LANG_PLAYING_NEXT"), icon = G.Cursor, onClick = onPlayingNext)
        if (clip != null) RockRow(cat.str("LANG_PASTE"), icon = G.Cursor) {
            scope.launch {
                val exists = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    llm.slop.slopbox.FileOps.exists(ctx, tree, dir.id, clip.name)
                }
                if (exists && llm.slop.slopbox.FileOps.Clipboard.parentId != dir.id) overwriteAsk = true else paste(false)
            }
        }
        RockRow(cat.str("LANG_CREATE_DIR"), icon = G.Cursor) { naming = true }
        RockRow("Change music folder", icon = G.TangoFolder, onClick = onChangeRoot)
    }
}

/**
 * Rockbox ft_enter() for SlopBox's other known types: a .cfg loads its
 * settings, a .cue shows its tracks, .lrc/.lrc8/.snc open the LRC Player,
 * a .bmark lists its bookmarks. Anything else does nothing, as in Rockbox
 * when no viewer plugin knows the type.
 */
@Composable
private fun FileViewer(tree: Uri, doc: Doc, nav: Nav, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val st = llm.slop.slopbox.SettingsStore.get(ctx)
    val ext = doc.name.substringAfterLast('.', "").lowercase()
    val bytes = remember(doc.id) {
        runCatching { ctx.contentResolver.openInputStream(MusicTree.docUri(tree, doc.id))!!.use { it.readBytes() } }.getOrNull()
    }
    androidx.compose.runtime.LaunchedEffect(doc.id) {
        when (ext) {
            "cfg" -> {
                if (bytes != null) {
                    st.loadCfg(String(bytes, Charsets.UTF_8))
                    android.widget.Toast.makeText(ctx, st.catalog.str("LANG_SETTINGS_LOADED"), android.widget.Toast.LENGTH_SHORT).show()
                }
                onBack()
            }
            "lrc", "lrc8", "snc" -> { onBack(); PluginList.running = "lrcplayer"; nav.push(Screen.PluginLrc) }
            "cue", "bmark" -> {}
            else -> onBack()
        }
    }
    when (ext) {
        // display_cuesheet_content(): title, performer, then each track.
        "cue" -> {
            val cue = bytes?.let { llm.slop.slopbox.Cuesheet.parse(it, 1, null) }
            Column(Modifier.fillMaxSize()) {
                TitleBar(doc.name, onBack = onBack)
                if (cue == null) { ListNote(st.catalog.str("LANG_FAILED")); return@Column }
                RockList(Modifier.fillMaxSize()) {
                    item { RockRow("Title: ${cue.title}") {} }
                    item { RockRow("Artist: ${cue.performer}") {} }
                    items(cue.tracks.size) { i ->
                        val t = cue.tracks[i]
                        RockRow("%02d. %s - %s".format(i + 1, t.performer, t.title)) {}
                    }
                }
            }
        }
        // bookmark_load(): the file's bookmarks; picking one plays it.
        "bmark" -> {
            val lines = bytes?.let { String(it, Charsets.UTF_8).lines().filter { l -> l.startsWith(">") || l.firstOrNull()?.isDigit() == true } }.orEmpty()
            Column(Modifier.fillMaxSize()) {
                TitleBar(doc.name.substringBeforeLast('.'), onBack = onBack)
                if (lines.isEmpty()) ListNote(st.catalog.str("LANG_BOOKMARK_LOAD_EMPTY"))
                RockList(Modifier.fillMaxSize()) {
                    items(lines) { l ->
                        val bm = llm.slop.slopbox.Bookmarks.parse(l)
                        RockRow(bm?.title ?: l, sub = bm?.let { "%d:%02d, %d".format(it.elapsedMs / 60000, (it.elapsedMs / 1000) % 60, it.index + 1) }) {
                            llm.slop.slopbox.SlopPlayer.current?.playBookmark(l)
                            onBack(); nav.toNowPlaying()
                        }
                    }
                }
            }
        }
    }
}

/**
 * The Files title (Show Path). Full Path: every folder from the music folder
 * down, each tappable to go back up to it, scrolled so the current one shows.
 * Current Directory Only: the folder's name; tapping it lists the folders
 * above to jump to.
 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.Breadcrumbs(path: List<Doc>, full: Boolean, goTo: (Int) -> Unit) {
    if (full) {
        val scroll = androidx.compose.foundation.rememberScrollState()
        LaunchedEffect(path.size) { scroll.scrollTo(scroll.maxValue) }
        androidx.compose.foundation.layout.Row(
            Modifier.weight(1f).horizontalScroll(scroll),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            path.forEachIndexed { k, d ->
                if (k > 0) BasicText(" / ", style = Cabbie.body.copy(color = Cabbie.Dim))
                val last = k == path.lastIndex
                BasicText(
                    d.name,
                    Modifier.then(if (last) Modifier else Modifier.clickable { goTo(k) }).padding(vertical = 12.dp),
                    style = Cabbie.body.copy(color = if (last) Cabbie.Text else Cabbie.Dim),
                    maxLines = 1,
                )
            }
        }
    } else {
        var open by remember { mutableStateOf(false) }
        val name = path.lastOrNull()?.name.orEmpty()
        Box(Modifier.weight(1f)) {
            BasicText(
                if (path.size > 1) "$name ▾" else name,
                Modifier.then(if (path.size > 1) Modifier.clickable { open = true } else Modifier).padding(vertical = 12.dp),
                style = Cabbie.body, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (open) androidx.compose.ui.window.Popup(
                // With Navigation at Bottom the list opens upward.
                alignment = if (Cabbie.palette.bottomNav) androidx.compose.ui.Alignment.BottomStart else androidx.compose.ui.Alignment.TopStart,
                onDismissRequest = { open = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true),
                offset = androidx.compose.ui.unit.IntOffset(0, with(androidx.compose.ui.platform.LocalDensity.current) {
                    (if (Cabbie.palette.bottomNav) (-48).dp else 48.dp).roundToPx() }),
            ) {
                androidx.compose.foundation.layout.Column(
                    Modifier.width(280.dp).background(Cabbie.backdrop)
                        .border(1.dp, Cabbie.Separator, androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
                ) {
                    // Nearest folder first.
                    for (k in path.lastIndex - 1 downTo 0) {
                        RockRow(path[k].name, icon = G.TangoFolder) { open = false; goTo(k) }
                    }
                }
            }
        }
    }
}
