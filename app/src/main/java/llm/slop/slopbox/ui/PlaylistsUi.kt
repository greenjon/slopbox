/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/playlist_catalog.c - Copyright (C) 2006 Sebastian Henriksen, Hardeep Sidhu
 *   apps/playlist_viewer.c - Copyright (C) 2003 Hardeep Sidhu
 *   apps/menus/playlist_menu.c - Copyright (C) 2007 Jonathan Gordon
 *   apps/tree.c - Copyright (C) 2002 Daniel Stenberg
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

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import llm.slop.slopbox.PlaybackMemory
import llm.slop.slopbox.PlaylistFiles
import llm.slop.slopbox.RockboxTagFile
import llm.slop.slopbox.SettingsStore
import llm.slop.slopbox.SlopPlayer

/** What the playlist screens act on. */
object PlaylistsView {
    /** Tracks for "Add to Playlist...": storage paths. */
    var addPaths by mutableStateOf<List<String>>(emptyList())
    var addTitle by mutableStateOf("")
    /** A playlist file to open in the viewer when the Playlists screen opens (Rockbox view_playlist()). */
    var viewFile by mutableStateOf<Doc?>(null)
}

private fun str(ctx: android.content.Context, id: String) = SettingsStore.get(ctx).catalog.str(id)
private fun toast(ctx: android.content.Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()

/** Rockbox's Playlist Catalog: the playlists in the catalog folder. */
@Composable
fun CatalogScreen(controller: MediaController?, nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tree = MusicTree.root(ctx)
    var rev by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<List<Doc>?>(null) }
    var options by remember { mutableStateOf(false) }
    // Opened from a file's "View" (Rockbox view_playlist()): straight to that playlist.
    val external = remember { PlaylistsView.viewFile.also { PlaylistsView.viewFile = null } }
    var viewing by remember { mutableStateOf(external) }
    var menuFor by remember { mutableStateOf<Doc?>(null) }
    var playingNextFor by remember { mutableStateOf<Doc?>(null) }
    var saving by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(tree, rev) {
        list = if (tree == null) emptyList() else withContext(Dispatchers.IO) { PlaylistFiles.catalog(ctx, tree).map { it.first } }
    }
    BackHandler(enabled = options || viewing != null || menuFor != null || playingNextFor != null || saving || creating) {
        if (external != null && viewing == external) nav.pop()
        options = false; viewing = null; menuFor = null; playingNextFor = null; saving = false; creating = false
    }
    if (saving) { SavePlaylistScreen(onDone = { saving = false; rev++ }); return }
    if (creating) { CreatePlaylistScreen(onDone = { creating = false; rev++ }); return }
    viewing?.let { doc ->
        if (tree != null) PlaylistFileScreen(controller, nav, tree, doc) { if (doc == external) nav.pop() else viewing = null }
        return
    }
    playingNextFor?.let { doc ->
        PlayingNextMenu(doc.name.substringBeforeLast('.'), folderLike = true, recursable = false, onBack = { playingNextFor = null }) { choice, _ ->
            playingNextFor = null
            scope.launch {
                val items = PlaylistFiles.load(ctx, tree!!, doc.id, doc.name)
                SlopPlayer.current?.addToPlaylist(items, choice.position, choice.queue, choice.replace)
                if (choice.replace) nav.toNowPlaying()
            }
        }
        return
    }
    menuFor?.let { doc ->
        val dirId = PlaylistFiles.catalogDir(ctx, tree!!, create = false) ?: doc.id.substringBeforeLast('/')
        TreeContextMenu(tree, Doc(dirId, PlaylistFiles.CATALOG_DIR, true), doc, TreeKind.CATALOG, nav,
            onClose = { changed -> menuFor = null; if (changed) rev++ },
            onView = { menuFor = null; viewing = doc },
            onPlayingNext = { menuFor = null; playingNextFor = doc })
        return
    }
    if (options) {
        // Rockbox playlist_options: View Current Playlist, Save Current Playlist,
        // Create Playlist, Reset Playlist Catalogue Directory.
        Column(Modifier.fillMaxSize()) {
            TitleBar(str(ctx, "LANG_PLAYLISTS"), onBack = { options = false })
            RockRow(str(ctx, "LANG_VIEW_DYNAMIC_PLAYLIST"), icon = G.Cursor) { options = false; nav.push(Screen.Playlist) }
            RockRow(str(ctx, "LANG_SAVE_DYNAMIC_PLAYLIST"), icon = G.Cursor, enabled = (controller?.mediaItemCount ?: 0) > 0) {
                options = false; saving = true
            }
            RockRow(str(ctx, "LANG_CREATE_PLAYLIST"), icon = G.Cursor, enabled = tree != null) { options = false; creating = true }
            RockRow(str(ctx, "LANG_RESET_PLAYLISTCAT_DIR"), icon = G.TangoFolder) {
                val st = SettingsStore.get(ctx)
                st.catalog.byCfg["playlist catalog directory"]?.let { st.setText(it, it.defaultText ?: "") }
                toast(ctx, str(ctx, "LANG_RESET_DONE_CLEAR"))
                options = false; rev++
            }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        TitleBar(str(ctx, "LANG_PLAYLISTS"), onBack = { nav.pop() }) { BarButton(G.ListC) { options = true } }
        when {
            tree == null -> ListNote("Choose the music folder first.")
            list == null -> ListNote("Loading…")
            list!!.isEmpty() -> {
                ListNote(str(ctx, "LANG_CATALOG_NO_PLAYLISTS"))
                if (!PlaylistFiles.writable(ctx, tree)) ListNote("To save playlists, choose the music folder again (Settings › General Settings › Database › Select directories to scan).")
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list!!) { d ->
                    RockRow(d.name.substringBeforeLast('.'), icon = G.ListC, onLongClick = { menuFor = d }) { viewing = d }
                }
            }
        }
    }
}

/**
 * Rockbox playlist_viewer_ex() on a playlist file: its tracks; picking one
 * plays the playlist from there. The track menu edits this playlist (not the
 * one playing), and leaving after a change asks "Save Changes?".
 */
@Composable
private fun PlaylistFileScreen(controller: MediaController?, nav: Nav, tree: Uri, doc: Doc, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    var modified by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Int?>(null) }
    var moving by remember { mutableStateOf<Int?>(null) }
    var askSave by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    LaunchedEffect(doc.id) { items = PlaylistFiles.load(ctx, tree, doc.id, doc.name) }
    fun paths() = items.orEmpty().mapNotNull { it.requestMetadata.mediaUri }.map { PlaybackMemory.pathOf(it) }
    fun saveTo(name: String): Boolean {
        val base = doc.name.substringBeforeLast('.')
        val target = if (name == base) MusicTree.docUri(tree, doc.id) to doc.name
            else PlaylistFiles.create(ctx, tree, name)?.let { it.first to "$name.m3u8" }
        return target != null && PlaylistFiles.write(ctx, target.first, target.second, paths())
    }
    fun leave() { if (modified) askSave = true else onBack() }
    BackHandler(enabled = menuFor == null && !naming && !askSave) { if (moving != null) moving = null else leave() }
    BackHandler(enabled = menuFor != null) { menuFor = null }

    if (askSave) {
        YesNo(str(ctx, "LANG_SAVE_CHANGES")) { yes ->
            askSave = false
            if (yes && !saveTo(doc.name.substringBeforeLast('.'))) toast(ctx, str(ctx, "LANG_SAVE") + " " + str(ctx, "LANG_FAILED"))
            onBack()
        }
        return
    }
    if (naming) {
        // Rockbox save_playlist_screen(): suggests the playlist's own name.
        NameEntry(str(ctx, "LANG_SAVE_DYNAMIC_PLAYLIST"), doc.name.substringBeforeLast('.')) { n ->
            naming = false
            if (n != null) {
                if (saveTo(n)) modified = false
                else toast(ctx, str(ctx, "LANG_SAVE") + " " + str(ctx, "LANG_FAILED"))
            }
        }
        return
    }
    menuFor?.let { i ->
        val l = items ?: return
        val item = l.getOrNull(i) ?: run { menuFor = null; return }
        ViewerTrackMenu(item, nav,
            onBack = { menuFor = null },
            onRemove = { items = l.filterIndexed { j, _ -> j != i }; modified = true; menuFor = null },
            onMove = { moving = i; menuFor = null },
            onShuffle = { items = l.shuffled(); modified = true; menuFor = null },
            onSave = { menuFor = null; naming = true },
        )
        return
    }
    Column(Modifier.fillMaxSize()) {
        TitleBar(if (moving != null) str(ctx, "LANG_MOVE") else doc.name.substringBeforeLast('.'),
            onBack = { if (moving != null) moving = null else leave() })
        val l = items
        if (l == null) ListNote("Loading…") else LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(l) { i, item ->
                RockRow(trackLabel(ctx, item, i), icon = G.TangoNote, selected = i == moving,
                    onLongClick = { menuFor = i }) {
                    val m = moving
                    if (m != null) {
                        items = l.toMutableList().apply { add(i, removeAt(m)) }
                        modified = true
                        moving = null
                    } else {
                        controller?.let { Queue.play(it, l, i) }
                        nav.toNowPlaying()
                    }
                }
            }
        }
    }
}

/** Rockbox playlist viewer line, per "Show Indices" and "Track Display". */
fun trackLabel(ctx: android.content.Context, item: MediaItem, index: Int, tags: llm.slop.slopbox.Engine.RockboxTags? = null): String {
    val store = SettingsStore.get(ctx)
    val path = item.requestMetadata.mediaUri?.let { PlaybackMemory.pathOf(it) } ?: item.mediaId
    val name = path.substringAfterLast('/').substringBeforeLast('.')
    val md = item.mediaMetadata
    val title = tags?.title?.ifEmpty { null } ?: md.title?.toString()
    val text = when (store.int("playlist viewer track display")) {
        1 -> path                                                         // Full Path
        2 -> listOfNotNull(title ?: name, (tags?.album ?: md.albumTitle?.toString())?.ifEmpty { null }).joinToString(" - ")
        3 -> title ?: name                                                // Title from ID3 tags
        else -> name                                                      // Track Name Only
    }
    return if (store.int("playlist viewer indices") != 0) "${index + 1}. $text" else text
}

/** Rockbox save_playlist_screen(): suggest a name, then save into the catalog. */
@Composable
fun SavePlaylistScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val tree = MusicTree.root(ctx)
    val player = SlopPlayer.current
    val current = player?.bookmarkPlaylist
    val suggested = remember {
        when {
            current == null || current == "/" -> {
                val existing = tree?.let { PlaylistFiles.catalog(ctx, it).map { p -> p.first.name } } ?: emptyList()
                var n = 1
                while ("Playlist$n.m3u8" in existing) n++
                "Playlist$n"
            }
            current.endsWith("/") -> current.trimEnd('/').substringAfterLast('/')
            else -> current.substringAfterLast('/').substringBeforeLast('.')
        }
    }
    var name by remember { mutableStateOf(suggested) }
    Column(Modifier.fillMaxSize()) {
        TitleBar(str(ctx, "LANG_SAVE_DYNAMIC_PLAYLIST"), onBack = onDone)
        BasicTextField(name, { name = it }, singleLine = true, textStyle = Cabbie.body,
            cursorBrush = SolidColor(Cabbie.Text), modifier = Modifier.fillMaxWidth().padding(16.dp))
        RockRow(str(ctx, "LANG_SAVE"), icon = G.Cursor, selected = true) {
            if (tree == null || player == null) { onDone(); return@RockRow }
            val existing = PlaylistFiles.catalog(ctx, tree).firstOrNull { it.first.name.substringBeforeLast('.') == name.trim() }?.first
            val target = existing?.let { MusicTree.docUri(tree, it.id) to it.id } ?: PlaylistFiles.create(ctx, tree, name.trim())
            if (target == null) {
                toast(ctx, "Can't save: choose the music folder again to allow writing.")
            } else {
                val fileName = existing?.name ?: "${name.trim()}.m3u8"
                val paths = player.playlistPaths()
                if (PlaylistFiles.write(ctx, target.first, fileName, paths)) {
                    player.namePlaylist(PlaylistFiles.pathOf(target.second))
                    toast(ctx, str(ctx, "LANG_PLAYLIST_SAVE_COUNT").replace("%d", paths.size.toString()).replace("%s", ""))
                }
            }
            onDone()
        }
    }
}

/**
 * Rockbox create_playlist() (tree.c): every track in the music folder, in
 * browser order, into a new catalog playlist named by the user.
 */
@Composable
private fun CreatePlaylistScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tree = MusicTree.root(ctx) ?: run { onDone(); return }
    var working by remember { mutableStateOf(false) }
    if (working) {
        Column(Modifier.fillMaxSize()) { TitleBar(str(ctx, "LANG_CREATE_PLAYLIST"), onBack = null); ListNote(str(ctx, "LANG_WAIT")) }
        return
    }
    NameEntry(str(ctx, "LANG_CATALOG_ADD_TO_NEW"), "") { name ->
        if (name == null) { onDone(); return@NameEntry }
        working = true
        scope.launch {
            val root = Doc(MusicTree.rootId(tree), "/", true)
            val paths = MusicTree.walk(ctx, tree, root, limit = Int.MAX_VALUE).map { (f, _) -> PlaylistFiles.pathOf(f.id) }
            val t = withContext(Dispatchers.IO) { PlaylistFiles.create(ctx, tree, name) }
            val ok = t != null && withContext(Dispatchers.IO) { PlaylistFiles.write(ctx, t.first, "$name.m3u8", paths) }
            toast(ctx, if (ok) str(ctx, "LANG_PLAYLIST_INSERT_COUNT").replace("%d", paths.size.toString()).replace("%s", "")
                else "Can't write the playlist: choose the music folder again to allow writing.")
            onDone()
        }
    }
}

/** Rockbox "Add to Playlist...": Add to Existing Playlist / Add to New Playlist. */
@Composable
fun AddToPlaylistScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tree = MusicTree.root(ctx)
    var picking by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(PlaylistsView.addTitle) }
    BackHandler(enabled = picking || naming) { picking = false; naming = false }
    val paths = PlaylistsView.addPaths
    fun done(ok: Boolean) {
        toast(ctx, if (ok) str(ctx, "LANG_PLAYLIST_INSERT_COUNT").replace("%d", paths.size.toString()).replace("%s", "")
            else "Can't write the playlist: choose the music folder again to allow writing.")
        nav.pop()
    }
    Column(Modifier.fillMaxSize()) {
        when {
            tree == null -> { TitleBar(str(ctx, "LANG_ADD_TO_PL"), onBack = { nav.pop() }); ListNote("Choose the music folder first.") }
            picking -> {
                TitleBar(str(ctx, "LANG_ADD_TO_EXISTING_PL"), onBack = { picking = false })
                val list = remember { PlaylistFiles.catalog(ctx, tree).map { it.first } }
                if (list.isEmpty()) ListNote(str(ctx, "LANG_CATALOG_NO_PLAYLISTS"))
                LazyColumn(Modifier.fillMaxSize()) {
                    items(list) { d ->
                        RockRow(d.name.substringBeforeLast('.'), icon = G.ListC) {
                            scope.launch { done(PlaylistFiles.append(ctx, tree, d.id, d.name, paths)) }
                        }
                    }
                }
            }
            naming -> {
                TitleBar(str(ctx, "LANG_CATALOG_ADD_TO_NEW"), onBack = { naming = false })
                BasicTextField(name, { name = it }, singleLine = true, textStyle = Cabbie.body,
                    cursorBrush = SolidColor(Cabbie.Text), modifier = Modifier.fillMaxWidth().padding(16.dp))
                RockRow(str(ctx, "LANG_SAVE"), icon = G.Cursor, selected = true) {
                    val t = PlaylistFiles.create(ctx, tree, name.trim().ifEmpty { "Playlist" })
                    done(t != null && PlaylistFiles.write(ctx, t.first, "${name.trim()}.m3u8", paths))
                }
            }
            else -> {
                TitleBar(str(ctx, "LANG_ADD_TO_PL"), onBack = { nav.pop() })
                RockRow(str(ctx, "LANG_ADD_TO_EXISTING_PL"), icon = G.Cursor) { picking = true }
                RockRow(str(ctx, "LANG_CATALOG_ADD_TO_NEW"), icon = G.Cursor) { naming = true }
            }
        }
    }
}

/** Rockbox WPS context menu › Current Playlist. */
@Composable
fun CurrentPlaylistMenu(controller: MediaController?, nav: Nav) {
    val ctx = LocalContext.current
    var saving by remember { mutableStateOf(false) }
    var timing by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var confirmShuffle by remember { mutableStateOf(false) }
    BackHandler(enabled = saving || timing || searching) { saving = false; timing = false; searching = false }
    if (saving) { SavePlaylistScreen(onDone = { saving = false }); return }
    if (timing) { PlayingTimeScreen(controller) { timing = false }; return }
    if (searching) { SearchPlaylistScreen(controller, nav) { searching = false }; return }
    if (confirmShuffle) {
        // Rockbox shuffle_playlist(): yesno_pop_confirm(LANG_SHUFFLE).
        YesNo(str(ctx, "LANG_SHUFFLE"), listOf(str(ctx, "LANG_ARE_YOU_SURE"))) { yes ->
            confirmShuffle = false
            if (yes) { SlopPlayer.current?.reshuffle(); nav.pop() }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        TitleBar(str(ctx, "LANG_CURRENT_PLAYLIST"), onBack = { nav.pop() })
        RockRow(str(ctx, "LANG_VIEW_DYNAMIC_PLAYLIST"), icon = G.Cursor) { nav.pop(); nav.push(Screen.Playlist) }
        RockRow(str(ctx, "LANG_SEARCH_IN_PLAYLIST"), icon = G.Cursor) { searching = true }
        RockRow(str(ctx, "LANG_SAVE_DYNAMIC_PLAYLIST"), icon = G.Cursor) { saving = true }
        RockRow(str(ctx, "LANG_SHUFFLE_PLAYLIST"), icon = G.Cursor) { confirmShuffle = true }
        RockRow(str(ctx, "LANG_PLAYING_TIME"), icon = G.Cursor) { timing = true }
    }
}

/**
 * Rockbox search_playlist(): case-insensitive match on the file name (or the
 * whole path when the viewer shows full paths); picking a result plays it.
 */
@Composable
private fun SearchPlaylistScreen(controller: MediaController?, nav: Nav, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf<String?>(null) }
    val q = query
    if (q == null) {
        NameEntry(str(ctx, "LANG_SEARCH_IN_PLAYLIST"), "") { s -> if (s == null) onBack() else query = s }
        return
    }
    val c = controller
    val fullPath = SettingsStore.get(ctx).int("playlist viewer track display") == 1
    val found = remember(q) {
        if (c == null) emptyList() else (0 until c.mediaItemCount).filter { i ->
            val path = c.getMediaItemAt(i).requestMetadata.mediaUri?.let { PlaybackMemory.pathOf(it) } ?: ""
            val name = if (fullPath) path else path.substringAfterLast('/')
            name.contains(q, ignoreCase = true)
        }
    }
    Column(Modifier.fillMaxSize()) {
        TitleBar(str(ctx, "LANG_SEARCH_RESULTS"), onBack = onBack)
        ListNote(str(ctx, "LANG_PLAYLIST_SEARCH_MSG").replace("%d", found.size.toString()).replace(" (%s)", ""))
        LazyColumn(Modifier.fillMaxSize()) {
            items(found) { i ->
                val item = c!!.getMediaItemAt(i)
                val path = item.requestMetadata.mediaUri?.let { PlaybackMemory.pathOf(it) } ?: item.mediaId
                RockRow(if (fullPath) path else path.substringAfterLast('/').substringBeforeLast('.'), icon = G.TangoNote) {
                    c.seekTo(i, 0); c.play()
                    nav.toNowPlaying()
                }
            }
        }
    }
}

/** Rockbox playing_time(): elapsed and total time of the playlist. */
@Composable
private fun PlayingTimeScreen(controller: MediaController?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var lines by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) {
        val c = controller ?: return@LaunchedEffect
        val uris = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).requestMetadata.mediaUri }
        val cur = c.currentMediaItemIndex
        val pos = c.currentPosition
        val lengths = withContext(Dispatchers.IO) {
            uris.map { u -> u?.let { RockboxTagFile.read(ctx, it)?.tags?.lengthMs } ?: 0L }
        }
        val total = lengths.sum()
        val elapsed = lengths.take(cur).sum() + pos
        fun t(ms: Long): String { val s = ms / 1000; return "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) }
        lines = listOf(
            "Elapsed: ${t(elapsed)} / ${t(total)}",
            "Remaining: ${t((total - elapsed).coerceAtLeast(0))}",
            "Track ${cur + 1} of ${uris.size}",
        )
    }
    Column(Modifier.fillMaxSize()) {
        TitleBar(str(ctx, "LANG_PLAYING_TIME"), onBack = onBack)
        val l = lines
        if (l == null) ListNote("Loading…") else l.forEach { ListNote(it) }
    }
}
