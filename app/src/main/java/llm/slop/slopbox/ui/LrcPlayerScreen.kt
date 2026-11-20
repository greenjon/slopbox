/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/plugins/lrcplayer.c - Copyright (C) 2008-2009 Teruaki Kawashima
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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.session.MediaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import llm.slop.slopbox.FileOps
import llm.slop.slopbox.PlaybackMemory
import llm.slop.slopbox.PlaylistFiles
import llm.slop.slopbox.SettingsStore
import llm.slop.slopbox.plugins.Lrc
import java.io.File

/** lrcplayer's preferences, kept in lrcplayer.cfg in Rockbox's configfile format. */
class LrcPrefs(private val file: File) {
    var wrap = true; var wipe = true; var activeOneLine = false; var autoscroll = true
    var align = 1; var displayTitle = true; var displayTime = true; var backlightOn = false
    var lrcDirectory = "/Lyrics"; var encoding = -1; var readId3 = true

    init {
        runCatching {
            for (line in file.readLines()) {
                val c = line.indexOf(':').takeIf { it > 0 } ?: continue
                val k = line.substring(0, c).trim(); val v = line.substring(c + 1).trim()
                fun b() = v == "yes" || v == "1" || v == "on"
                when (k) {
                    "wrap" -> wrap = b(); "wipe" -> wipe = b(); "active one line" -> activeOneLine = b()
                    "autoscroll" -> autoscroll = b(); "align" -> align = v.toIntOrNull()?.coerceIn(0, 2) ?: 1
                    "display title" -> displayTitle = b(); "display time" -> displayTime = b()
                    "backlight on" -> backlightOn = b(); "lrc directory" -> lrcDirectory = v
                    "encoding" -> encoding = v.toIntOrNull() ?: -1; "read id3" -> readId3 = b()
                }
            }
        }
    }

    fun save() {
        fun b(x: Boolean) = if (x) "yes" else "no"
        runCatching {
            file.writeText(buildString {
                append("wrap: ${b(wrap)}\nwipe: ${b(wipe)}\nactive one line: ${b(activeOneLine)}\n")
                append("autoscroll: ${b(autoscroll)}\nalign: $align\n")
                append("display title: ${b(displayTitle)}\ndisplay time: ${b(displayTime)}\nbacklight on: ${b(backlightOn)}\n")
                append("lrc directory: $lrcDirectory\nencoding: $encoding\nread id3: ${b(readId3)}\n")
            })
        }
    }
}

/** lrcplayer find_lrc_file() / read_id3() for one track. */
private suspend fun findLyrics(ctx: Context, uri: Uri, title: String?, prefs: LrcPrefs): Lrc.Lyrics? = withContext(Dispatchers.IO) {
    val tree = MusicTree.root(ctx)
    val path = PlaybackMemory.pathOf(uri)
    val cs = if (prefs.encoding < 0) Lrc.charsetFor(SettingsStore.get(ctx).int("default codepage")) else Lrc.charsetFor(prefs.encoding)
    if (tree != null && path.startsWith("/")) {
        val rootPath = PlaylistFiles.pathOf(MusicTree.rootId(tree)).trimEnd('/')
        val base = path.substringAfterLast('/').substringBeforeLast('.')
        // fix_path_part(): characters a file name can't have.
        val names = listOfNotNull(base, title?.takeIf { it.isNotEmpty() && it != base }?.replace(Regex("[/\\\\:*?\"<>|]"), "_"))
        val cache = HashMap<String, List<FileOps.Child>?>()
        fun childrenOf(dirPath: String): List<FileOps.Child>? = cache.getOrPut(dirPath) {
            if (dirPath != rootPath && !dirPath.startsWith("$rootPath/")) return@getOrPut null
            val id = if (dirPath == rootPath) MusicTree.rootId(tree) else PlaylistFiles.docIdOf(tree, dirPath)
            FileOps.children(ctx, tree, id).takeIf { it.isNotEmpty() || FileOps.info(ctx, tree, id)?.isDir == true }
        }
        // find_lrc_file_helper(): the track's folder, then each parent (and in
        // each, the "Lrc Directory" subfolder); then the same under "Lrc Directory".
        fun helper(baseDir: String): Pair<FileOps.Child, Lrc.Type>? {
            var dir = path.substringBeforeLast('/')
            while (true) {
                val candidates = mutableListOf(baseDir + dir)
                if (prefs.lrcDirectory.startsWith("/")) candidates += dir + prefs.lrcDirectory.trimEnd('/')
                for (d in candidates) {
                    val kids = childrenOf(d.ifEmpty { "/" }.trimEnd('/').ifEmpty { "" }) ?: continue
                    for (type in listOf(Lrc.Type.LRC, Lrc.Type.LRC8, Lrc.Type.SNC, Lrc.Type.TXT)) {
                        for (n in names) kids.firstOrNull { !it.isDir && it.name == n + type.ext }?.let { return it to type }
                    }
                }
                if (dir.isEmpty()) break
                dir = dir.substringBeforeLast('/', "")
            }
            return null
        }
        val found = helper("") ?: if (prefs.lrcDirectory.startsWith("/")) helper(prefs.lrcDirectory.trimEnd('/')) else null
        if (found != null) {
            val bytes = runCatching {
                ctx.contentResolver.openInputStream(MusicTree.docUri(tree, found.first.id))!!.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null) return@withContext Lrc.load(bytes, found.second, cs)
        }
    }
    // read_id3(): only MP3 files.
    if (!prefs.readId3) return@withContext null
    val ext = path.substringAfterLast('.', "").lowercase()
    if (ext !in setOf("mp3", "mp2", "mp1", "mpa")) return@withContext null
    val head = runCatching {
        ctx.contentResolver.openInputStream(uri)!!.use { s ->
            val h = ByteArray(10); if (s.read(h) < 10) return@use null
            val size = ((h[6].toInt() and 0x7f) shl 21) or ((h[7].toInt() and 0x7f) shl 14) or ((h[8].toInt() and 0x7f) shl 7) or (h[9].toInt() and 0x7f)
            val body = ByteArray(size.coerceAtMost(4 shl 20))
            var n = 0
            while (n < body.size) { val r = s.read(body, n, body.size - n); if (r <= 0) break; n += r }
            h + body.copyOf(n)
        }
    }.getOrNull() ?: return@withContext null
    Lrc.fromId3(head, cs)
}

/**
 * Rockbox's lrcplayer plugin: synced lyrics for the playing track, the
 * current line centred and wiped word by word. Touch follows Rockbox's
 * touchscreen keymap: centre play/pause, left/right previous/next (hold to
 * seek), top/bottom middle volume (or scroll, with Automatic Scrolling off),
 * top left the plugin's menu, top right leaves.
 */
@Composable
fun LrcPlayerScreen(controller: MediaController?, nav: Nav) {
    val ctx = LocalContext.current
    val store = remember { SettingsStore.get(ctx) }
    val prefs = remember { LrcPrefs(File(ctx.filesDir, "lrcplayer.cfg")) }
    var rev by remember { mutableStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var lyrics by remember { mutableStateOf<Lrc.Lyrics?>(null) }
    var loading by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var length by remember { mutableLongStateOf(1L) }
    var scroll by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var seekTo by remember { mutableStateOf<Long?>(null) }
    var offsetAdj by remember { mutableLongStateOf(0L) }
    val item = controller?.currentMediaItem
    val uri = item?.requestMetadata?.mediaUri

    // Backlight Always On
    val view = LocalView.current
    DisposableEffect(prefs.backlightOn, rev) {
        view.keepScreenOn = prefs.backlightOn
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(uri, rev) {
        lyrics = null; offsetAdj = 0
        if (uri == null) return@LaunchedEffect
        loading = true
        lyrics = findLyrics(ctx, uri, item.mediaMetadata.title?.toString(), prefs)
        loading = false
    }
    LaunchedEffect(controller) {
        while (true) {
            val c = controller
            if (c != null) {
                playing = c.playbackState != androidx.media3.common.Player.STATE_IDLE &&
                    c.playbackState != androidx.media3.common.Player.STATE_ENDED && c.mediaItemCount > 0
                val d = c.duration
                length = if (d == C.TIME_UNSET || d <= 0) 1 else d
                elapsed = (seekTo ?: c.currentPosition).coerceIn(0, length)
            }
            delay(100)
        }
    }
    BackHandler(enabled = menu) { menu = false; prefs.save(); rev++ }
    if (menu) { LrcMenu(prefs, onOffset = { offsetAdj += it }, offset = offsetAdj, onQuit = { prefs.save(); nav.pop() }) { menu = false; prefs.save(); rev++ }; return }

    val lrc = lyrics?.let { l -> if (!l.synced) Lrc.spread(l, length) else l }
    val wipe = prefs.wipe && lrc?.synced == true
    val time = if (prefs.autoscroll) elapsed else scroll

    fun timeOf(i: Int): Long = lrc?.let { it.timeOf(i, length) + offsetAdj } ?: 0
    // display_lrcs(): the line being sung.
    var center = 0
    if (lrc != null) while (center + 1 < lrc.lines.size && timeOf(center + 1) <= time) center++

    val active = Cabbie.Text
    val inactive = Cabbie.Dim
    fun lineText(i: Int): AnnotatedString {
        val l = lrc!!.lines[i]
        val start = timeOf(i)
        val end = timeOf(i + 1)
        val activeLine = (start <= time && end > time) || !prefs.activeOneLine
        return buildAnnotatedString {
            val words = l.words
            for ((w, word) in words.withIndex()) {
                val ws = if (word.timeStart == -1L) start else lrc.wordTime(word.timeStart) + offsetAdj
                val we = if (w + 1 < words.size) (words[w + 1].timeStart.let { if (it == -1L) start else lrc.wordTime(it) + offsetAdj }) else end
                val litChars = when {
                    ws > time || !activeLine -> 0
                    !wipe || we <= time -> word.text.length
                    else -> ((time - ws) * word.text.length / (we - ws).coerceAtLeast(1)).toInt()
                }
                withStyle(SpanStyle(color = active)) { append(word.text.take(litChars)) }
                withStyle(SpanStyle(color = if (prefs.autoscroll) inactive else active)) { append(word.text.drop(litChars)) }
            }
        }
    }

    val listState = rememberLazyListState()
    // The line being sung sits in the middle (display_lrcs(): y = (nblines-1)/2).
    LaunchedEffect(center, lrc) {
        if (lrc != null) listState.animateScrollToItem(center)
    }

    Column(Modifier.fillMaxSize().pointerInput(controller, prefs.autoscroll) {
        // keymap-touchscreen.c button_context_wps: a 3x3 grid.
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown()
                val w = size.width; val h = size.height
                val col = when { down.position.x < w / 3f -> 0; down.position.x > 2 * w / 3f -> 2; else -> 1 }
                val row = when { down.position.y < h / 3f -> 0; down.position.y > 2 * h / 3f -> 2; else -> 1 }
                var held = false
                val c = controller
                val job = launch {
                    delay(500)
                    if (row == 1 && col != 1 && c != null) {
                        // ACTION_WPS_SEEKBACK / SEEKFWD while held, as lrcplayer's ff_rewind.
                        held = true
                        var pos = c.currentPosition
                        var step = 1000L * store.int("scan min step").coerceAtLeast(1)
                        while (true) {
                            val limit = maxOf(500L, (if (col == 2) length - pos else pos) * 3 / 100)
                            if (step > limit) step = limit
                            pos = (pos + if (col == 2) step else -step).coerceIn(0, length - 100)
                            seekTo = pos
                            step += step shr (store.int("seek acceleration") + 3)
                            delay(100)
                        }
                    }
                }
                do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                job.cancel()
                seekTo?.let { c?.seekTo(it); seekTo = null }
                if (held) return@awaitEachGesture
                when (row to col) {
                    1 to 1 -> c?.let { if (it.isPlaying) it.pause() else { it.prepare(); it.play() } }
                    1 to 0 -> c?.let { if (it.currentPosition < 3000) it.seekToPrevious() else it.seekTo(0) }
                    1 to 2 -> c?.seekToNext()
                    0 to 1 -> if (!prefs.autoscroll) scroll = (scroll + 3000).coerceAtMost(length)
                        else store.catalog.byCfg["volume"]?.let { store.set(it, (store.int(it) + 1).coerceAtMost(0)) }
                    2 to 1 -> if (!prefs.autoscroll) scroll = (scroll - 3000).coerceAtLeast(0)
                        else store.catalog.byCfg["volume"]?.let { store.set(it, (store.int(it) - 1).coerceAtLeast(-80)) }
                    0 to 0 -> menu = true
                    0 to 2 -> { prefs.save(); nav.pop() }
                }
            }
        }
    }) {
        @Suppress("UNUSED_EXPRESSION") rev
        // display_state(): title/artist, then the time and a bar.
        val md = controller?.mediaMetadata
        if (prefs.displayTitle && playing) {
            val title = lrc?.title ?: md?.title?.toString()
            val artist = lrc?.artist ?: md?.artist?.toString()
            BasicText(when {
                title != null && artist != null -> "$title/$artist"
                title != null -> title
                uri != null -> PlaybackMemory.pathOf(uri).substringAfterLast('/')
                else -> "(no info)"
            }, Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), style = Cabbie.body, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        if (prefs.displayTime && playing) {
            BasicText("%d:%02d/%d:%02d".format(elapsed / 60000, (elapsed / 1000) % 60, length / 60000, (length / 1000) % 60),
                Modifier.padding(horizontal = 8.dp), style = Cabbie.small)
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).height(6.dp).border(1.dp, Cabbie.Dim)) {
                Box(Modifier.fillMaxWidth((elapsed.toFloat() / length).coerceIn(0f, 1f)).fillMaxHeight().background(Cabbie.Text))
            }
        }
        val state = when {
            !playing -> "Audio Stopped"
            loading -> "Loading lrc"
            lrc != null && lrc.lines.isEmpty() -> "No lyrics"
            else -> null
        }
        val align = when (prefs.align) { 0 -> TextAlign.Start; 2 -> TextAlign.End; else -> TextAlign.Center }
        Box(Modifier.fillMaxSize()) {
            if (state != null) {
                BasicText(state, Modifier.fillMaxWidth().align(androidx.compose.ui.Alignment.Center),
                    style = Cabbie.body.copy(textAlign = align))
            } else if (lrc != null) androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val half = maxHeight / 2 - 14.dp
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), state = listState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(top = half, bottom = half),
                    userScrollEnabled = false) {
                    itemsIndexed(lrc.lines) { i, l ->
                        if (l.text.isEmpty()) {
                            // An empty line: a bar fills during a long gap (wipe, >= 3.5 s).
                            val s = timeOf(i); val e = timeOf(i + 1)
                            if (wipe && s <= time && e > time && e - s >= 3500) {
                                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(8.dp).border(1.dp, Cabbie.Text)) {
                                    Box(Modifier.fillMaxWidth(((time - s).toFloat() / (e - s)).coerceIn(0f, 1f)).fillMaxHeight().background(Cabbie.Text))
                                }
                            } else Box(Modifier.height(24.dp))
                        } else {
                            BasicText(lineText(i), Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                style = Cabbie.body.copy(textAlign = align), softWrap = prefs.wrap,
                                maxLines = if (prefs.wrap) Int.MAX_VALUE else 1, overflow = TextOverflow.Clip)
                        }
                    }
                    if (prefs.autoscroll) item { BasicText("[end]", style = Cabbie.body) }
                }
            }
        }
    }
}

/** lrc_menu(): Theme, Display and Lyrics Settings, Time Offset, Quit. */
@Composable
private fun LrcMenu(prefs: LrcPrefs, onOffset: (Long) -> Unit, offset: Long, onQuit: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var page by remember { mutableStateOf("main") }
    var rev by remember { mutableStateOf(0) }
    BackHandler(enabled = page != "main") { page = if (page == "align" || page == "encoding") (if (page == "align") "display" else "lyrics") else "main" }
    @Suppress("UNUSED_EXPRESSION") rev
    fun yn(b: Boolean) = if (b) "Yes" else "No"
    fun toggle(f: () -> Unit) { f(); rev++ }
    Column(Modifier.fillMaxSize()) {
        when (page) {
            "main" -> {
                TitleBar("Lrcplayer", onBack = onBack)
                RockRow("Theme Settings", icon = G.Config) { page = "theme" }
                RockRow("Display Settings", icon = G.Config) { page = "display" }
                RockRow("Lyrics Settings", icon = G.Config) { page = "lyrics" }
                RockRow("Time Offset", icon = G.Cursor) { page = "offset" }
                RockRow("Quit", icon = G.Cursor, onClick = onQuit)
            }
            "theme" -> {
                TitleBar("Theme Settings", onBack = { page = "main" })
                RockRow("Display Title", trailing = yn(prefs.displayTitle)) { toggle { prefs.displayTitle = !prefs.displayTitle } }
                RockRow("Display Time", trailing = yn(prefs.displayTime)) { toggle { prefs.displayTime = !prefs.displayTime } }
                RockRow("Backlight Always On", trailing = yn(prefs.backlightOn)) { toggle { prefs.backlightOn = !prefs.backlightOn } }
            }
            "display" -> {
                TitleBar("Display Settings", onBack = { page = "main" })
                RockRow("Wrap", trailing = yn(prefs.wrap)) { toggle { prefs.wrap = !prefs.wrap } }
                RockRow("Wipe", trailing = yn(prefs.wipe)) { toggle { prefs.wipe = !prefs.wipe } }
                RockRow("Alignment", trailing = listOf("Left", "Centre", "Right")[prefs.align]) { page = "align" }
                RockRow("Activate Only Current Line", trailing = yn(prefs.activeOneLine)) { toggle { prefs.activeOneLine = !prefs.activeOneLine } }
                RockRow("Automatic Scrolling", trailing = yn(prefs.autoscroll)) { toggle { prefs.autoscroll = !prefs.autoscroll } }
            }
            "align" -> {
                TitleBar("Alignment", onBack = { page = "display" })
                listOf("Left", "Centre", "Right").forEachIndexed { i, n ->
                    RockRow(n, selected = prefs.align == i) { prefs.align = i; page = "display" }
                }
            }
            "lyrics" -> {
                TitleBar("Lyrics Settings", onBack = { page = "main" })
                val names = SettingsStore.get(ctx).catalog.byCfg["default codepage"]?.labels?.map { it.second } ?: emptyList()
                RockRow("Encoding", trailing = if (prefs.encoding < 0) "Use default codepage" else names.getOrNull(prefs.encoding)) { page = "encoding" }
                RockRow("Read ID3 tag", trailing = yn(prefs.readId3)) { toggle { prefs.readId3 = !prefs.readId3 } }
                RockRow("Lrc Directry", trailing = prefs.lrcDirectory) { page = "dir" }
            }
            "encoding" -> {
                TitleBar("Encoding", onBack = { page = "lyrics" })
                val names = listOf("Use default codepage") +
                    (SettingsStore.get(ctx).catalog.byCfg["default codepage"]?.labels?.map { it.second } ?: emptyList())
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(names) { i, n -> RockRow(n, selected = prefs.encoding + 1 == i) { prefs.encoding = i - 1; page = "lyrics" } }
                }
            }
            "dir" -> NameEntry("Lrc Directry", prefs.lrcDirectory) { v -> if (v != null) prefs.lrcDirectory = v; page = "lyrics" }
            "offset" -> {
                // lrc_set_time("Time Offset"): the offset in seconds, to 0.01 s.
                TitleBar("Time Offset", onBack = { page = "main" })
                ListNote("%+.2f sec".format(offset / 1000.0))
                for ((label, d) in listOf("+1 sec" to 1000L, "+0.1 sec" to 100L, "-0.1 sec" to -100L, "-1 sec" to -1000L)) {
                    RockRow(label, icon = G.Cursor) { onOffset(d) }
                }
            }
        }
    }
}
