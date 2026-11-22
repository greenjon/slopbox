/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/onplay.c - Copyright (C) 2002 Björn Stenberg
 *   apps/screens.c - Copyright (C) 2002 Björn Stenberg
 *   apps/playlist_viewer.c - Copyright (C) 2003 Hardeep Sidhu
 *   apps/plugins/properties.c - Copyright (C) 2006 Peter D'Hoye
 *   apps/cuesheet.c - Copyright (C) 2007 Nicolas Pennequin, Jonathan Gordon
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

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import llm.slop.slopbox.Bookmarks
import llm.slop.slopbox.Engine
import llm.slop.slopbox.FileOps
import llm.slop.slopbox.PlaybackMemory
import llm.slop.slopbox.PlaybackService
import llm.slop.slopbox.PlaylistFiles
import llm.slop.slopbox.RockboxTagFile
import llm.slop.slopbox.SettingsStore
import llm.slop.slopbox.SlopPlayer
import java.util.Calendar
import kotlin.math.log10

private fun str(ctx: Context, id: String) = SettingsStore.get(ctx).catalog.str(id)
private fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()

// ---- Shared pieces ----------------------------------------------------------------

/** Rockbox gui_syncyesno_run(): a question, its message lines, Yes / No. */
@Composable
fun YesNo(title: String, lines: List<String> = emptyList(), onAnswer: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    BackHandler { onAnswer(false) }
    Column(Modifier.fillMaxSize()) {
        TitleBar(title, onBack = { onAnswer(false) })
        lines.forEach { ListNote(it) }
        RockRow(str(ctx, "LANG_SET_BOOL_YES"), selected = true) { onAnswer(true) }
        RockRow(str(ctx, "LANG_SET_BOOL_NO")) { onAnswer(false) }
    }
}

/** Rockbox kbd_input(): edit a name. [onDone] gets null when cancelled. */
@Composable
fun NameEntry(title: String, initial: String, onDone: (String?) -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(initial) }
    BackHandler { onDone(null) }
    Column(Modifier.fillMaxSize()) {
        TitleBar(title, onBack = { onDone(null) })
        BasicTextField(name, { name = it }, singleLine = true, textStyle = Cabbie.body,
            cursorBrush = SolidColor(Cabbie.Text), modifier = Modifier.fillMaxWidth().padding(16.dp))
        RockRow("OK", icon = G.Cursor, selected = true) { onDone(name.trim().ifEmpty { null }) }
    }
}

// ---- Hotkeys and the WPS context items (Rockbox onplay.c hotkey_items[]) ----------

/** Rockbox's hotkey actions, in hotkey_items[] order. [code] is the enum value in onplay.h. */
enum class Hotkey(val code: Int, val lang: String, val wps: Boolean, val tree: Boolean) {
    OFF(0, "LANG_OFF", true, true),
    VIEW_PLAYLIST(1, "LANG_VIEW_DYNAMIC_PLAYLIST", true, false),
    SHOW_TRACK_INFO(4, "LANG_MENU_SHOW_ID3_INFO", true, false),
    PITCHSCREEN(5, "LANG_PITCH", true, false),
    OPEN_WITH(6, "LANG_ONPLAY_OPEN_WITH", true, true),
    DELETE(7, "LANG_DELETE", true, true),
    INSERT(10, "LANG_ADD", false, true),
    INSERT_SHUFFLED(11, "LANG_ADD_SHUFFLED", false, true),
    PLUGIN(9, "LANG_OPEN_PLUGIN", true, false),
    BOOKMARK(8, "LANG_BOOKMARK_MENU_CREATE", true, false),
    BOOKMARK_LIST(12, "LANG_BOOKMARK_MENU_LIST", true, false),
    PROPERTIES(2, "LANG_PROPERTIES", false, true),
    SHOW_IN_FILES(14, "LANG_SHOW_IN_FILES", true, false),
    CONTEXT_MENU(0x3E, "LANG_ONPLAY_MENU_TITLE", true, true),
    ALBUMART(13, "LANG_VIEW_ALBUMART", true, false),
    PICTUREFLOW(3, "LANG_ONPLAY_PICTUREFLOW", false, true),
}

/**
 * Rockbox's "context_wps" setting: five WPS context items (item 0 is the WPS
 * hotkey on hotkey targets), stored as their English names
 * (wps_context_menu_load_from_cfg / _write_to_cfg).
 */
object WpsContext {
    const val ITEMS = 5
    val DEFAULT = listOf(Hotkey.VIEW_PLAYLIST, Hotkey.SHOW_TRACK_INFO, Hotkey.SHOW_IN_FILES,
        Hotkey.PITCHSCREEN, Hotkey.ALBUMART)

    private fun setting(store: SettingsStore) = store.catalog.byCfg["context_wps"]

    fun items(store: SettingsStore): List<Hotkey> {
        val s = setting(store) ?: return DEFAULT
        val text = store.text(s)
        if (text.isBlank()) return DEFAULT
        val out = MutableList(ITEMS) { Hotkey.OFF }
        text.split(',').take(ITEMS).forEachIndexed { i, raw ->
            val t = raw.trim()
            if (t.length > 1) {
                // Rockbox matches the start of each English name, last table entry first.
                Hotkey.entries.reversed().firstOrNull { store.catalog.str(it.lang).startsWith(t, ignoreCase = true) }
                    ?.let { out[i] = it }
            }
        }
        return out
    }

    /** Rockbox hotkey_menu_do_setting(): set one item; the same action elsewhere is cleared. */
    fun set(store: SettingsStore, item: Int, hk: Hotkey) {
        val l = items(store).toMutableList()
        l[item] = hk
        for (i in 1 until ITEMS) if (i != item && l[i] == hk) l[i] = Hotkey.OFF
        save(store, l)
    }

    fun reset(store: SettingsStore) = save(store, DEFAULT)

    private fun save(store: SettingsStore, l: List<Hotkey>) {
        val s = setting(store) ?: return
        store.setText(s, if (l == DEFAULT) "" else l.joinToString("") { store.catalog.str(it.lang) + ", " })
    }

    /** Rockbox wps_context_menu_get_name(): "WPS Hotkey [x]" / "Set Context Item n [x]". */
    fun label(store: SettingsStore, item: Int): String {
        val cur = store.catalog.str(items(store)[item].lang)
        return if (item == 0) "${store.catalog.str("LANG_HOTKEY_WPS")} [$cur]"
        else "${store.catalog.str("LANG_SET_CONTEXT_ITEM")} $item [$cur]"
    }
}

/** Rockbox hotkey_run_menu(): pick a WPS action ([execute]: run it; else choose it for a setting). */
@Composable
fun HotkeyList(title: String, execute: Boolean, current: Hotkey?, onBack: () -> Unit, onPick: (Hotkey) -> Unit) {
    val ctx = LocalContext.current
    val list = Hotkey.entries.filter { it.wps && !(execute && (it == Hotkey.OFF || it == Hotkey.CONTEXT_MENU)) }
    Column(Modifier.fillMaxSize()) {
        TitleBar(title, onBack = onBack)
        RockList(Modifier.fillMaxSize()) {
            items(list) { hk -> RockRow(str(ctx, hk.lang), selected = hk == current) { onPick(hk) } }
        }
    }
}

// ---- The WPS context menu ---------------------------------------------------------

/** What the track info screen shows: null = the playing track. */
object TrackInfoView {
    var item by mutableStateOf<MediaItem?>(null)
}

/** The playing file: its document id, tree and parent folder. */
private class PlayingFile(val tree: Uri, val id: String, val dirId: String?, val uri: Uri, val name: String)

private fun playingFile(controller: MediaController?): PlayingFile? {
    val item = controller?.currentMediaItem ?: return null
    val uri = item.requestMetadata.mediaUri ?: return null
    val ex = item.requestMetadata.extras
    val tree = ex?.getString(MusicTree.EXTRA_TREE)?.let(Uri::parse) ?: return null
    return PlayingFile(tree, item.mediaId, ex.getString(MusicTree.EXTRA_DIR), uri,
        PlaybackMemory.pathOf(uri).substringAfterLast('/'))
}

/** Android's stand-in for Rockbox's viewer plugins: hand the file to another app. */
fun openWith(ctx: Context, uri: Uri, name: String) {
    val mime = ctx.contentResolver.getType(uri) ?: "*/*"
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(Intent.createChooser(i, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { toast(ctx, str(ctx, "LANG_ONPLAY_OPEN_WITH") + " " + str(ctx, "LANG_FAILED")) }
}

/**
 * Rockbox wps_onplay_menu: Current Playlist, Add to Playlist, Sound Settings,
 * Playback Settings, (Set Rating), Bookmarks, Browse Cuesheet, then the four
 * context items from "context_wps" (item 0 is the WPS hotkey).
 */
@Composable
fun WpsContextMenu(controller: MediaController?, nav: Nav, browser: BrowserState) {
    val ctx = LocalContext.current
    val store = remember { SettingsStore.get(ctx) }
    var chooser by remember { mutableStateOf(false) }
    var pitchMenu by remember { mutableStateOf(false) }
    var rating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<PlayingFile?>(null) }
    // Rockbox: with pitch or speed changed, the item reads "Pitch 110.0% / 105.0%"
    // and opens a submenu (pitch_menu: Pitch, Reset Setting).
    val pitchChanged = llm.slop.slopbox.Pitch.pitch != llm.slop.slopbox.Pitch.P100 ||
        llm.slop.slopbox.Pitch.stretch != llm.slop.slopbox.Pitch.P100

    fun run(hk: Hotkey, fromHotkey: Boolean = false) {
        when (hk) {
            Hotkey.VIEW_PLAYLIST -> { nav.pop(); nav.push(Screen.Playlist) }
            Hotkey.SHOW_TRACK_INFO -> { TrackInfoView.item = null; nav.pop(); nav.push(Screen.TrackInfo) }
            Hotkey.PITCHSCREEN -> if (pitchChanged) pitchMenu = true else { nav.pop(); nav.push(Screen.Pitch) }
            Hotkey.OPEN_WITH -> playingFile(controller)?.let { openWith(ctx, it.uri, it.name) }
            Hotkey.DELETE -> confirmDelete = playingFile(controller)
            Hotkey.PLUGIN -> {
                // hotkey_wps_run_plugin(): the WPS hotkey's plugin, or the context menu's.
                val key = if (fromHotkey) OpenPlugin.WPS else OpenPlugin.CONTEXT
                nav.pop()
                if (!OpenPlugin.run(ctx, key, nav)) toast(ctx, str(ctx, "LANG_OPEN_PLUGIN") + ": " + str(ctx, "LANG_NO_FILES"))
            }
            Hotkey.BOOKMARK -> {
                controller?.sendCustomCommand(SessionCommand(PlaybackService.CMD_BOOKMARK_CREATE, Bundle.EMPTY), Bundle.EMPTY)
                nav.pop()
            }
            Hotkey.BOOKMARK_LIST -> {
                val pl = SlopPlayer.current?.bookmarkPlaylist
                if (pl == null || Bookmarks(ctx).list(pl).isEmpty()) toast(ctx, str(ctx, "LANG_BOOKMARK_LOAD_EMPTY"))
                else { BookmarkView.playlist = pl; nav.pop(); nav.push(Screen.BookmarkList) }
            }
            Hotkey.SHOW_IN_FILES -> {
                val f = playingFile(controller)
                if (f == null || FileOps.info(ctx, f.tree, f.id) == null) toast(ctx, str(ctx, "LANG_FILE_NOT_FOUND"))
                else if (browser.reveal(ctx, f.tree, f.id)) nav.toFiles()
                else toast(ctx, str(ctx, "LANG_FILE_NOT_FOUND"))
            }
            Hotkey.CONTEXT_MENU -> chooser = true
            Hotkey.ALBUMART -> { nav.pop(); nav.push(Screen.AlbumArt) }
            else -> {}
        }
    }

    confirmDelete?.let { f ->
        YesNo(f.name, listOf(str(ctx, "LANG_REALLY_DELETE"), PlaybackMemory.pathOf(f.uri))) { yes ->
            confirmDelete = null
            if (yes) {
                if (FileOps.delete(ctx, f.tree, f.id)) { browser.reload++; nav.pop() }
                else toast(ctx, str(ctx, "LANG_DELETE") + " " + str(ctx, "LANG_FAILED"))
            }
        }
        return
    }
    // Rockbox set_rating_inline(): Set Rating, 0 to 10, for a track in the database.
    val playingId = controller?.currentMediaItem?.mediaId
    if (rating && playingId != null) {
        BackHandler { rating = false }
        val cur = llm.slop.slopbox.ui.db.TagDb.statsOf(playingId).rating
        Column(Modifier.fillMaxSize()) {
            TitleBar(str(ctx, "LANG_MENU_SET_RATING"), onBack = { rating = false })
            RockList(Modifier.fillMaxSize(), state = rememberLazyListState(cur.coerceAtMost(6))) {
                items((0..10).toList()) { v ->
                    RockRow(v.toString(), selected = v == cur) {
                        llm.slop.slopbox.ui.db.TagDb.setRating(ctx, playingId, v)
                        rating = false; nav.pop()
                    }
                }
            }
        }
        return
    }
    if (pitchMenu) {
        BackHandler { pitchMenu = false }
        Column(Modifier.fillMaxSize()) {
            TitleBar(str(ctx, "LANG_PITCH"), onBack = { pitchMenu = false })
            RockRow(str(ctx, "LANG_PITCH"), icon = G.Cursor) { pitchMenu = false; nav.pop(); nav.push(Screen.Pitch) }
            RockRow(str(ctx, "LANG_RESET_SETTING"), icon = G.Cursor) { llm.slop.slopbox.Pitch.reset(); pitchMenu = false; nav.pop() }
        }
        return
    }
    if (chooser) {
        HotkeyList(str(ctx, "LANG_ONPLAY_MENU_TITLE"), execute = true, current = null, onBack = { chooser = false }) {
            chooser = false; run(it)
        }
        return
    }

    // Rockbox gui/wps.c ACTION_WPS_HOTKEY: run context item 0 straight away.
    LaunchedEffect(Unit) {
        if (!WpsHotkey.pending) return@LaunchedEffect
        WpsHotkey.pending = false
        when (val hk = WpsContext.items(store)[0]) {
            Hotkey.OFF -> nav.pop()
            Hotkey.PITCHSCREEN -> { nav.pop(); nav.push(Screen.Pitch) }
            Hotkey.DELETE, Hotkey.CONTEXT_MENU -> run(hk, fromHotkey = true)
            else -> {
                val top = nav.top
                run(hk, fromHotkey = true)
                if (nav.top == top && nav.top == Screen.ContextMenu) nav.pop()
            }
        }
    }
    if (WpsHotkey.pending) return

    val cue = SlopPlayer.current?.cuesheet
    Column(Modifier.fillMaxSize()) {
        TitleBar(str(ctx, "LANG_ONPLAY_MENU_TITLE"), onBack = { nav.pop() })
        RockList(Modifier.fillMaxSize()) {
            item { RockRow(str(ctx, "LANG_CURRENT_PLAYLIST"), icon = G.ListC) { nav.pop(); nav.push(Screen.CurrentPlaylist) } }
            item {
                RockRow(str(ctx, "LANG_ADD_TO_PL"), icon = G.ListC) {
                    val p = SlopPlayer.current
                    val uri = p?.currentMediaItem?.requestMetadata?.mediaUri ?: return@RockRow
                    PlaylistsView.addPaths = listOf(PlaybackMemory.pathOf(uri))
                    PlaylistsView.addTitle = p.currentMediaItem?.mediaMetadata?.title?.toString() ?: ""
                    nav.pop(); nav.push(Screen.AddToPlaylist)
                }
            }
            item { RockRow(str(ctx, "LANG_SOUND_SETTINGS"), icon = G.Config) { nav.pop(); SettingsView.open(nav, "Sound Settings") } }
            item { RockRow(str(ctx, "LANG_PLAYBACK"), icon = G.Config) { nav.pop(); SettingsView.open(nav, "Playback Settings") } }
            // ratingitem_callback(): only with "Gather Runtime Data" on and the track in the database.
            if (store.int("gather runtime data") != 0 && playingId != null && llm.slop.slopbox.ui.db.TagDb.contains(playingId)) item {
                RockRow(str(ctx, "LANG_MENU_SET_RATING"), icon = G.Cursor) { rating = true }
            }
            // SlopBox: the Rater layout's settings, while it's the While Playing Screen.
            if (Rater.active(store)) {
                for ((cfg, label) in listOf(llm.slop.slopbox.SlopBoxSettings.RATER_SKIP_RATED to "Skip Rated Tracks",
                    llm.slop.slopbox.SlopBoxSettings.RATER_NEXT to "Next Track After Rating")) item {
                    var on by remember { mutableStateOf(store.int(cfg) != 0) }
                    RockRow(label, icon = G.Config, trailing = if (on) "On" else "Off") {
                        store.catalog.byCfg[cfg]?.let { store.set(it, if (on) 0 else 1) }
                        on = store.int(cfg) != 0
                    }
                }
            }
            item { RockRow(str(ctx, "LANG_BOOKMARK_MENU"), icon = G.Config) { nav.pop(); nav.push(Screen.Bookmarks) } }
            if (cue != null) item { RockRow(str(ctx, "LANG_BROWSE_CUESHEET"), icon = G.Cursor) { nav.pop(); nav.push(Screen.Cuesheet) } }
            val ctxItems = WpsContext.items(store)
            for (i in 1 until WpsContext.ITEMS) {
                val hk = ctxItems[i]
                if (hk == Hotkey.OFF) continue
                val label = if (hk == Hotkey.PITCHSCREEN && pitchChanged) {
                    val p = llm.slop.slopbox.Pitch
                    "${str(ctx, hk.lang)} ${p.percent(p.pitch)} / ${p.percent(p.getSpeed(p.pitch, p.stretch))}"
                } else str(ctx, hk.lang)
                item { RockRow(label, icon = if (hk == Hotkey.PITCHSCREEN && pitchChanged) G.Config else G.Cursor) { run(hk) } }
            }
        }
    }
}

/** Opening the Settings tree at a submenu (Rockbox menus reached from context menus). */
object SettingsView {
    var startAt by mutableStateOf<String?>(null)
    fun open(nav: Nav, title: String) { startAt = title; nav.push(Screen.SettingsAt) }
}

// ---- Rockbox browse_id3(): Show Track Info --------------------------------------------

/** One line pair of Rockbox's track info screen: "[Header]" then the value. */
private fun id3Rows(ctx: Context, t: Engine.RockboxTags?, path: String, plPos: Pair<Int, Int>?, plName: String?,
                    lengthMs: Long, bitrate: Int, freq: Long, codec: String, size: Long, modified: Long?): List<Pair<String, String>> {
    fun h(id: String) = "[" + str(ctx, id) + "]"
    fun gain(f: Long): String {
        if (f == 0L) return ""
        val db = 20 * log10(f.toDouble() / (1L shl 24))
        return "%+.2f dB".format(db)
    }
    val rows = mutableListOf<Pair<String, String>>()
    fun add(id: String, v: String?) { if (!v.isNullOrEmpty()) rows += h(id) to v }
    add("LANG_ID3_TITLE", t?.title)
    add("LANG_ID3_ARTIST", t?.artist)
    add("LANG_ID3_COMPOSER", t?.composer)
    add("LANG_ID3_ALBUM", t?.album)
    add("LANG_ID3_ALBUMARTIST", t?.albumArtist)
    add("LANG_ID3_GROUPING", t?.grouping)
    add("LANG_ID3_DISCNUM", t?.disc?.ifEmpty { null } ?: t?.discNum?.takeIf { it > 0 }?.toString())
    add("LANG_ID3_TRACKNUM", t?.track?.ifEmpty { null } ?: t?.trackNum?.takeIf { it > 0 }?.toString())
    add("LANG_ID3_COMMENT", t?.comment)
    add("LANG_ID3_GENRE", t?.genre)
    add("LANG_ID3_YEAR", t?.year?.ifEmpty { null } ?: t?.yearNum?.takeIf { it > 0 }?.toString())
    if (lengthMs > 0) {
        val s = lengthMs / 1000
        add("LANG_ID3_LENGTH", if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60))
    }
    plPos?.let { (i, n) -> add("LANG_ID3_PLAYLIST", "$i/$n" + (if (SlopPlayer.current?.playlistModified == true) "* " else "  ") + (plName ?: "")) }
    add("LANG_FORMAT", codec)
    if (bitrate > 0) add("LANG_ID3_BITRATE", "$bitrate kbps")
    if (freq > 0) add("LANG_ID3_FREQUENCY", "$freq Hz")
    add("LANG_ID3_TRACK_GAIN", t?.let { gain(it.trackGain) })
    add("LANG_ALBUM_GAIN", t?.let { gain(it.albumGain) })
    if (size > 0) add("LANG_FILESIZE", FileOps.humanSize(size, listOf("B", "KB", "MB", "GB")))
    add("LANG_ID3_PATH", path)
    modified?.takeIf { it > 0 }?.let {
        val c = Calendar.getInstance().apply { timeInMillis = it }
        add("LANG_DATE", "%04d/%02d/%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)))
        add("LANG_TIME", "%02d:%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND)))
    }
    return rows
}

/**
 * Rockbox browse_id3() (apps/screens.c): every tag, the playlist position,
 * format, bitrate, gains, size and path. For the playing track (from the WPS)
 * or a file (from the browser, which adds its date and time).
 */
@Composable
fun TrackInfoScreen(controller: MediaController?, nav: Nav) {
    val ctx = LocalContext.current
    val fileItem = TrackInfoView.item
    val item = fileItem ?: controller?.currentMediaItem
    var rows by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    LaunchedEffect(item?.mediaId) {
        val uri = item?.requestMetadata?.mediaUri
        if (item == null || uri == null) { rows = emptyList(); return@LaunchedEffect }
        val tags = withContext(Dispatchers.IO) { RockboxTagFile.read(ctx, uri)?.tags }
        val tree = item.requestMetadata.extras?.getString(MusicTree.EXTRA_TREE)?.let(Uri::parse)
        val info = withContext(Dispatchers.IO) { tree?.let { FileOps.info(ctx, it, item.mediaId) } }
        val path = PlaybackMemory.pathOf(uri)
        rows = if (fileItem == null) {
            val live = Engine.trackInfo()
            val dur = controller?.duration?.takeIf { it != C.TIME_UNSET } ?: live.lengthMs
            val n = controller?.mediaItemCount ?: 0
            id3Rows(ctx, tags, path, if (n > 0) (controller!!.currentMediaItemIndex + 1) to n else null,
                SlopPlayer.current?.bookmarkPlaylist, dur, live.bitrate.takeIf { it > 0 } ?: tags?.bitrate ?: 0,
                live.frequency.toLong().takeIf { it > 0 } ?: tags?.frequency ?: 0, tags?.codec ?: live.codec,
                info?.size ?: 0, null)
        } else {
            id3Rows(ctx, tags, path, null, null, tags?.lengthMs ?: 0, tags?.bitrate ?: 0, tags?.frequency ?: 0,
                tags?.codec ?: "", info?.size ?: 0, info?.modified)
        }
    }
    Column(Modifier.fillMaxSize()) {
        TitleBar(str(ctx, "LANG_MENU_SHOW_ID3_INFO"), onBack = { TrackInfoView.item = null; nav.pop() })
        val r = rows
        when {
            r == null -> ListNote("Loading…")
            r.isEmpty() -> ListNote(str(ctx, "LANG_ID3_NO_INFO"))
            else -> RockList(Modifier.fillMaxSize()) {
                items(r) { (label, value) ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        BasicText(label, style = Cabbie.small.copy(color = Cabbie.Dim))
                        BasicText(value, style = Cabbie.body)
                    }
                }
            }
        }
    }
}

// ---- View Album Art, Browse Cuesheet --------------------------------------------------

/** Rockbox view_album_art(): the playing track's art, full screen. */
@Composable
fun AlbumArtScreen(controller: MediaController?, nav: Nav) {
    val ctx = LocalContext.current
    val item = controller?.currentMediaItem
    var art by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(item?.mediaId) {
        art = item?.let { TagReader.read(ctx, it, withArt = true).art }
        loaded = true
    }
    Column(Modifier.fillMaxSize()) {
        TitleBar(str(ctx, "LANG_VIEW_ALBUMART"), onBack = { nav.pop() })
        val a = art
        if (a != null) Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
            Image(a, contentDescription = "Album art", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else if (loaded) ListNote(str(ctx, "LANG_NO_ALBUMART_FOUND"))
    }
}

/** Rockbox browse_cuesheet(): "NN. performer" / title per track; picking one jumps there. */
@Composable
fun CuesheetScreen(controller: MediaController?, nav: Nav) {
    val p = SlopPlayer.current
    val cue = p?.cuesheet
    if (cue == null) { nav.pop(); return }
    val current = cue.currentTrack(controller?.currentPosition ?: 0)
    val state = rememberLazyListState((current - 2).coerceAtLeast(0))
    Column(Modifier.fillMaxSize()) {
        TitleBar("${cue.performer}: ${cue.title}", onBack = { nav.pop() })
        RockList(Modifier.fillMaxSize(), state = state) {
            itemsIndexed(cue.tracks) { i, t ->
                RockRow("%02d. %s".format(i + 1, t.performer), selected = i == current) {
                    p.seekToCue(t.offsetMs); nav.pop()
                }
                RockRow(t.title, selected = i == current) { p.seekToCue(t.offsetMs); nav.pop() }
            }
        }
    }
}

// ---- The file browser's context menu (Rockbox tree_onplay_menu) -------------------

/** Where a browser context menu was opened: the file browser, or the Playlist Catalog. */
enum class TreeKind { FILES, CATALOG }

/**
 * Rockbox tree_onplay_menu with clipboard_callback()'s rules: View (playlist),
 * Playing Next, Add to Playlist, Rename, Cut, Copy, Paste, Delete / Delete
 * Directory, Open With, Create Directory, Properties or Show Track Info,
 * Set As (folders), File View, Sort Playlists (catalog).
 */
@Composable
fun TreeContextMenu(
    tree: Uri,
    dir: Doc,
    doc: Doc,
    kind: TreeKind,
    nav: Nav,
    onClose: (changed: Boolean) -> Unit,
    onView: () -> Unit,
    onPlayingNext: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SettingsStore.get(ctx) }
    var page by remember { mutableStateOf("menu") }
    var overwriteAsk by remember { mutableStateOf(false) }
    val catalog = kind == TreeKind.CATALOG

    fun fail(op: String) = toast(ctx, str(ctx, op) + " " + str(ctx, "LANG_FAILED"))
    fun paste(overwrite: Boolean) = scope.launch {
        val ok = FileOps.paste(ctx, tree, dir.id, overwrite)
        if (!ok) fail("LANG_PASTE")
        onClose(true)
    }

    BackHandler(enabled = page != "menu") { page = "menu" }
    when (page) {
        "rename" -> {
            // Rockbox hides the extension when renaming in the Playlists list.
            val base = if (catalog) doc.name.substringBeforeLast('.') else doc.name
            NameEntry(str(ctx, "LANG_RENAME"), base) { n ->
                if (n == null) { page = "menu"; return@NameEntry }
                val newName = if (catalog) n + "." + doc.name.substringAfterLast('.') else n
                if (FileOps.rename(ctx, tree, dir.id, doc, newName) == null) fail("LANG_RENAME")
                onClose(true)
            }
            return
        }
        "delete" -> {
            YesNo(doc.name, listOf(str(ctx, "LANG_REALLY_DELETE"), PlaylistFiles.pathOf(doc.id))) { yes ->
                if (!yes) { page = "menu"; return@YesNo }
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { FileOps.delete(ctx, tree, doc.id) }
                    if (!ok) fail("LANG_DELETE")
                    if (FileOps.Clipboard.doc?.id == doc.id) FileOps.Clipboard.clear()
                    onClose(true)
                }
            }
            return
        }
        "mkdir" -> {
            NameEntry(str(ctx, "LANG_CREATE_DIR"), "") { n ->
                if (n == null) { page = "menu"; return@NameEntry }
                if (FileOps.mkdir(ctx, tree, dir.id, n) == null) fail("LANG_CREATE_DIR")
                onClose(true)
            }
            return
        }
        "properties" -> { PropertiesScreen(tree, doc) { page = "menu" }; return }
        "setas" -> {
            Column(Modifier.fillMaxSize()) {
                TitleBar(str(ctx, "LANG_SET_AS"), onBack = { page = "menu" })
                RockRow(str(ctx, "LANG_PLAYLIST_DIR"), icon = G.ListC) {
                    store.catalog.byCfg["playlist catalog directory"]?.let { store.setText(it, PlaylistFiles.pathOf(doc.id)) }
                    onClose(false)
                }
                RockRow(str(ctx, "LANG_START_DIR"), icon = G.TangoFolder) {
                    store.catalog.byCfg["start directory"]?.let { store.setText(it, PlaylistFiles.pathOf(doc.id) + "/") }
                    onClose(false)
                }
            }
            return
        }
    }
    if (overwriteAsk) {
        YesNo(str(ctx, "LANG_REALLY_OVERWRITE")) { yes ->
            overwriteAsk = false
            if (yes) paste(true) else { toast(ctx, str(ctx, "LANG_CANCEL")); onClose(false) }
        }
        return
    }

    val clip = FileOps.Clipboard.doc
    Column(Modifier.fillMaxSize()) {
        TitleBar(if (doc.isDir) doc.name else doc.name.substringBeforeLast('.'), onBack = { onClose(false) })
        RockList(Modifier.fillMaxSize()) {
            if (doc.isPlaylist) item { RockRow(str(ctx, "LANG_VIEW"), icon = G.ListC, onClick = onView) }
            item { RockRow(str(ctx, "LANG_PLAYING_NEXT"), icon = G.Cursor, onClick = onPlayingNext) }
            item {
                RockRow(str(ctx, "LANG_ADD_TO_PL"), icon = G.Cursor) {
                    scope.launch {
                        val items = when {
                            doc.isPlaylist -> PlaylistFiles.load(ctx, tree, doc.id, doc.name)
                            doc.isDir -> MusicTree.walk(ctx, tree, doc).map { (f, fd) -> MusicTree.item(tree, f, fd) }
                            else -> listOf(MusicTree.item(tree, doc, dir))
                        }
                        PlaylistsView.addPaths = items.mapNotNull { it.requestMetadata.mediaUri }.map { PlaybackMemory.pathOf(it) }
                        PlaylistsView.addTitle = doc.name.substringBeforeLast('.')
                        onClose(false)
                        nav.push(Screen.AddToPlaylist)
                    }
                }
            }
            item { RockRow(str(ctx, "LANG_RENAME"), icon = G.Cursor) { page = "rename" } }
            if (!catalog) {
                item { RockRow(str(ctx, "LANG_CUT"), icon = G.Cursor) { FileOps.Clipboard.clip(doc, dir.id, true); onClose(false) } }
                item { RockRow(str(ctx, "LANG_COPY"), icon = G.Cursor) { FileOps.Clipboard.clip(doc, dir.id, false); onClose(false) } }
            }
            if (clip != null) item {
                RockRow(str(ctx, "LANG_PASTE"), icon = G.Cursor) {
                    scope.launch {
                        val exists = withContext(Dispatchers.IO) { FileOps.exists(ctx, tree, dir.id, clip.name) }
                        if (exists && FileOps.Clipboard.parentId != dir.id) overwriteAsk = true else paste(false)
                    }
                }
            }
            item { RockRow(str(ctx, if (doc.isDir) "LANG_DELETE_DIR" else "LANG_DELETE"), icon = G.Cursor) { page = "delete" } }
            if (!doc.isDir && !catalog) item {
                RockRow(str(ctx, "LANG_ONPLAY_OPEN_WITH"), icon = G.Cursor) {
                    openWith(ctx, MusicTree.docUri(tree, doc.id), doc.name); onClose(false)
                }
            }
            if (!catalog) item { RockRow(str(ctx, "LANG_CREATE_DIR"), icon = G.Cursor) { page = "mkdir" } }
            if (doc.isTrack) item {
                RockRow(str(ctx, "LANG_MENU_SHOW_ID3_INFO"), icon = G.Cursor) {
                    TrackInfoView.item = MusicTree.item(tree, doc, dir)
                    onClose(false)
                    nav.push(Screen.TrackInfo)
                }
            } else item { RockRow(str(ctx, "LANG_PROPERTIES"), icon = G.Cursor) { page = "properties" } }
            // onplay_add_to_shortcuts(): a "browse" shortcut to this file or folder.
            item {
                RockRow(str(ctx, "LANG_ADD_TO_FAVES"), icon = G.Cursor) {
                    llm.slop.slopbox.Shortcuts(ctx).add(llm.slop.slopbox.Shortcuts.Shortcut(
                        llm.slop.slopbox.Shortcuts.Type.BROWSER,
                        data = PlaylistFiles.pathOf(doc.id) + if (doc.isDir) "/" else ""))
                    onClose(false)
                }
            }
            if (doc.isDir) item { RockRow(str(ctx, "LANG_SET_AS"), icon = G.Config) { page = "setas" } }
            item { RockRow(str(ctx, "LANG_FILE"), icon = G.Config) { onClose(false); SettingsView.open(nav, "File View") } }
            if (catalog) item {
                RockRow(store.catalog.byCfg["sort playlists"]?.title ?: "Sort Playlists", icon = G.Config) {
                    onClose(false); SettingsView.open(nav, "Playlists")
                }
            }
        }
    }
}

/**
 * Rockbox's properties plugin: [Path], [Subdirs], [Files], [Size] (and the
 * audio file count) for a folder; [Path], [Filename], [Size], [Date], [Time]
 * for a file.
 */
@Composable
fun PropertiesScreen(tree: Uri, doc: Doc, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val units = listOf("LANG_BYTE", "LANG_KIBIBYTE", "LANG_MEBIBYTE", "LANG_GIBIBYTE").map { str(ctx, it) }
    var rows by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    LaunchedEffect(doc.id) {
        val path = PlaylistFiles.pathOf(doc.id)
        rows = if (doc.isDir) {
            val s = FileOps.dirStats(ctx, tree, doc.id)
            listOfNotNull(
                str(ctx, "LANG_PROPERTIES_PATH") to path,
                str(ctx, "LANG_PROPERTIES_SUBDIRS") to s.dirs.toString(),
                str(ctx, "LANG_PROPERTIES_FILES") to s.files.toString(),
                str(ctx, "LANG_PROPERTIES_SIZE") to FileOps.humanSize(s.bytes, units),
                if (s.audio > 0) "[" + str(ctx, "LANG_MENU_SHOW_ID3_INFO") + "]" to s.audio.toString() else null,
            )
        } else {
            val i = withContext(Dispatchers.IO) { FileOps.info(ctx, tree, doc.id) }
            val c = Calendar.getInstance().apply { timeInMillis = i?.modified ?: 0 }
            listOf(
                str(ctx, "LANG_PROPERTIES_PATH") to path.substringBeforeLast('/') + "/",
                str(ctx, "LANG_PROPERTIES_FILENAME") to doc.name,
                str(ctx, "LANG_PROPERTIES_SIZE") to FileOps.humanSize(i?.size ?: 0, units),
                str(ctx, "LANG_PROPERTIES_DATE") to "%04d/%02d/%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)),
                str(ctx, "LANG_PROPERTIES_TIME") to "%02d:%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND)),
            )
        }
    }
    Column(Modifier.fillMaxSize()) {
        TitleBar(str(ctx, if (doc.isDir) "LANG_PROPERTIES_DIRECTORY_PROPERTIES" else "LANG_PROPERTIES_FILE_PROPERTIES"), onBack = onBack)
        val r = rows
        if (r == null) ListNote(str(ctx, "LANG_WAIT")) else RockList(Modifier.fillMaxSize()) {
            items(r) { (label, value) ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    BasicText(label, style = Cabbie.small.copy(color = Cabbie.Dim))
                    BasicText(value, style = Cabbie.body)
                }
            }
        }
    }
}

// ---- The playlist viewer's context menu (Rockbox playlist_viewer.c context_menu()) ----

/**
 * Rockbox's playlist viewer menu for one track: Playing Next..., Add to
 * Playlist..., Remove, Move, Show Track Info, Shuffle, Save and Playlist
 * Viewer Settings. Shuffle asks first (yesno_pop_confirm).
 */
@Composable
fun ViewerTrackMenu(
    item: MediaItem,
    nav: Nav,
    onBack: () -> Unit,
    onRemove: () -> Unit,
    onMove: () -> Unit,
    onShuffle: () -> Unit,
    onSave: () -> Unit,
) {
    val ctx = LocalContext.current
    var page by remember { mutableStateOf("menu") }
    BackHandler(enabled = page != "menu") { page = "menu" }
    val path = item.requestMetadata.mediaUri?.let { PlaybackMemory.pathOf(it) } ?: item.mediaId
    val title = path.substringAfterLast('/').substringBeforeLast('.')
    when (page) {
        "next" -> {
            PlayingNextMenu(title, folderLike = false, recursable = false, onBack = { page = "menu" }) { choice, _ ->
                SlopPlayer.current?.addToPlaylist(listOf(item), choice.position, choice.queue, choice.replace)
                onBack()
                if (choice.replace) nav.toNowPlaying()
            }
            return
        }
        "shuffle" -> {
            YesNo(str(ctx, "LANG_SHUFFLE"), listOf(str(ctx, "LANG_ARE_YOU_SURE"))) { yes ->
                if (yes) onShuffle() else page = "menu"
            }
            return
        }
    }
    Column(Modifier.fillMaxSize()) {
        TitleBar(str(ctx, "LANG_PLAYLIST"), onBack = onBack)
        RockList(Modifier.fillMaxSize()) {
            item { RockRow(str(ctx, "LANG_PLAYING_NEXT"), icon = G.Cursor) { page = "next" } }
            item {
                RockRow(str(ctx, "LANG_ADD_TO_PL"), icon = G.Cursor) {
                    PlaylistsView.addPaths = listOf(path)
                    PlaylistsView.addTitle = title
                    onBack()
                    nav.push(Screen.AddToPlaylist)
                }
            }
            item { RockRow(str(ctx, "LANG_REMOVE"), icon = G.Cursor, onClick = onRemove) }
            item { RockRow(str(ctx, "LANG_MOVE"), icon = G.Cursor, onClick = onMove) }
            item {
                RockRow(str(ctx, "LANG_MENU_SHOW_ID3_INFO"), icon = G.Cursor) {
                    TrackInfoView.item = item
                    onBack()
                    nav.push(Screen.TrackInfo)
                }
            }
            item { RockRow(str(ctx, "LANG_SHUFFLE"), icon = G.Cursor) { page = "shuffle" } }
            item { RockRow(str(ctx, "LANG_SAVE"), icon = G.Cursor, onClick = onSave) }
            item {
                RockRow(str(ctx, "LANG_PLAYLISTVIEWER_SETTINGS"), icon = G.Config) {
                    onBack()
                    SettingsView.open(nav, "Playlist Viewer Settings")
                }
            }
        }
    }
}

/** The WPS hotkey (context_wps item 0) waiting to run when the context menu screen opens. */
object WpsHotkey {
    var pending = false
}
