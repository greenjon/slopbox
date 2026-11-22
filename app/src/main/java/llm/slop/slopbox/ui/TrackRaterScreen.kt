/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import llm.slop.slopbox.PlaylistFiles
import llm.slop.slopbox.SlopPlayer
import llm.slop.slopbox.ui.db.TagDb
import java.io.File

/**
 * track_rater: SlopBox's own plugin (Rockbox has none like it) for a listening
 * purge. One tap gives the playing track 1 to 5 stars and moves on; jump
 * buttons hear a track in steps. Stars go into the database as Rockbox ratings
 * (stars × 2, the 0-10 scale of Set Song Rating), and database_changelog.txt
 * is exported after each rating so a laptop can read them off the card.
 */
class RaterPrefs(private val file: File) {
    var skipRated = true; var advance = true; var jumpSec = 14; var order = 0

    init {
        runCatching {
            for (line in file.readLines()) {
                val c = line.indexOf(':').takeIf { it > 0 } ?: continue
                val k = line.substring(0, c).trim(); val v = line.substring(c + 1).trim()
                fun b() = v == "yes" || v == "1" || v == "on"
                when (k) {
                    "skip rated" -> skipRated = b(); "next after rating" -> advance = b()
                    "jump" -> jumpSec = v.toIntOrNull()?.coerceIn(1, 120) ?: 14
                    "order" -> order = v.toIntOrNull()?.coerceIn(0, ORDERS.lastIndex) ?: 0
                }
            }
        }
    }

    fun save() {
        fun b(x: Boolean) = if (x) "yes" else "no"
        runCatching {
            file.writeText("skip rated: ${b(skipRated)}\nnext after rating: ${b(advance)}\njump: $jumpSec\norder: $order\n")
        }
    }

    companion object {
        val ORDERS = listOf("Playlist Order", "Shuffle", "Artist", "Album", "Year", "Title")
        val JUMPS = listOf(5, 10, 14, 15, 20, 30)
        fun of(ctx: Context) = RaterPrefs(File(ctx.filesDir, "track_rater.cfg"))
    }
}

/** The changelog export, a moment after the last rating, outliving the screen. */
private object RaterExport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    var failed by mutableStateOf(false)

    fun schedule(ctx: Context) {
        val app = ctx.applicationContext
        job?.cancel()
        job = scope.launch {
            delay(1500)
            failed = !TagDb.exportChangelog(app)
        }
    }
}

private fun stars(rating: Int) = (rating + 1) / 2

private val StarColors = listOf(Color(0xFFC62828), Color(0xFFE65100), Color(0xFFF9A825), Color(0xFF7CB342), Color(0xFF2E7D32))
private val StarWords = listOf("remove", "", "revisit", "", "keep")

@Composable
fun TrackRaterScreen(controller: MediaController?, nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { RaterPrefs.of(ctx) }
    var rev by remember { mutableIntStateOf(0) }           // prefs changed
    var menu by remember { mutableStateOf<String?>(null) } // null, "menu", "playlists", "order", "jump"
    val playlistPath = remember { androidx.compose.runtime.mutableStateListOf<Doc>() }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val undo = remember { ArrayDeque<Pair<String, Int>>() }
    var undoCount by remember { mutableIntStateOf(0) }

    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var index by remember { mutableIntStateOf(-1) }
    var count by remember { mutableIntStateOf(0) }
    var id by remember { mutableStateOf<String?>(null) }
    var item by remember { mutableStateOf<MediaItem?>(null) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(controller) {
        while (true) {
            controller?.let { c ->
                pos = c.currentPosition
                dur = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
                playing = c.playWhenReady && c.playbackState != Player.STATE_ENDED
                count = c.mediaItemCount
                index = if (count > 0) c.currentMediaItemIndex else -1
                val it = c.currentMediaItem
                if (it?.mediaId != id) { id = it?.mediaId; item = it; message = null }
            }
            delay(200)
        }
    }
    @Suppress("UNUSED_VARIABLE") val statsRev = TagDb.statsRevision   // redraw when a rating changes
    fun ratingOf(mediaId: String) = TagDb.statsOf(mediaId).rating
    fun rated(mediaId: String) = ratingOf(mediaId) > 0

    /** The next queue entry to rate after [from], skipping rated ones if asked; -1 at the end. */
    fun nextIndex(c: MediaController, from: Int): Int {
        var j = from + 1
        while (j < c.mediaItemCount && prefs.skipRated && rated(c.getMediaItemAt(j).mediaId)) j++
        return if (j < c.mediaItemCount) j else -1
    }

    fun next() {
        val c = controller ?: return
        val j = nextIndex(c, c.currentMediaItemIndex)
        if (j < 0) { message = if (prefs.skipRated) "No unrated tracks after this one" else "End of the playlist"; return }
        c.seekTo(j, 0); c.play()
    }

    fun previous() {
        val c = controller ?: return
        if (c.currentMediaItemIndex <= 0) { message = "Start of the playlist"; return }
        c.seekTo(c.currentMediaItemIndex - 1, 0); c.play()
    }

    fun jump(dir: Int) {
        val c = controller ?: return
        val d = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
        c.seekTo((c.currentPosition + dir * prefs.jumpSec * 1000L).coerceIn(0, d))
    }

    fun rate(n: Int) {
        val c = controller ?: return
        val mid = c.currentMediaItem?.mediaId ?: return
        if (TagDb.track(mid) == null) { message = "Not in the database: Database › Update Now"; return }
        undo.addLast(mid to ratingOf(mid)); if (undo.size > 50) undo.removeFirst(); undoCount = undo.size
        TagDb.setRating(ctx, mid, n * 2)
        RaterExport.schedule(ctx)
        if (!prefs.advance) return
        val j = nextIndex(c, c.currentMediaItemIndex)
        if (j < 0) { c.pause(); message = "That was the last one to rate in this playlist" }
        else { c.seekTo(j, 0); c.play() }
    }

    fun undoLast() {
        val c = controller ?: return
        val (mid, prev) = undo.removeLastOrNull() ?: return
        undoCount = undo.size
        TagDb.setRating(ctx, mid, prev)
        RaterExport.schedule(ctx)
        val i = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == mid }
        if (i != null && i != c.currentMediaItemIndex) { c.seekTo(i, 0); c.play() }
    }

    /** The last playlist Play Playlist loaded, in its file's order (for Order › Playlist Order). */
    var loaded by remember { mutableStateOf<List<MediaItem>?>(null) }

    /** Turn the player's Shuffle off (it would reshuffle a new queue); true if it was on. */
    fun shuffleOff(): Boolean {
        val store = llm.slop.slopbox.SettingsStore.get(ctx)
        val shuffle = store.catalog.byCfg["shuffle"] ?: return false
        if (store.int(shuffle) == 0) return false
        store.set(shuffle, 0)
        return true
    }

    /** Order changed: sort the queue that's playing now, keeping the current track and its place. */
    fun resortQueue() = scope.launch {
        val c = controller ?: return@launch
        val n = c.mediaItemCount
        if (n < 2) return@launch
        val now = (0 until n).map { c.getMediaItemAt(it) }
        val ids = now.map { it.mediaId }.toSet()
        val base = if (RaterPrefs.ORDERS[prefs.order] == "Playlist Order")
            loaded?.filter { it.mediaId in ids }?.takeIf { it.size == n } ?: return@launch
        else now
        val list = withContext(Dispatchers.Default) { sorted(base, prefs.order) }
        val currentId = c.currentMediaItem?.mediaId
        val at = list.indexOfFirst { it.mediaId == currentId }.coerceAtLeast(0)
        val pos = c.currentPosition
        val wasPlaying = c.playWhenReady
        val wasShuffled = shuffleOff()
        c.setMediaItems(list, at, pos)
        c.prepare()
        c.playWhenReady = wasPlaying
        SlopPlayer.current?.markModified()
        message = "Queue sorted: ${RaterPrefs.ORDERS[prefs.order]}" + if (wasShuffled) " (Shuffle turned off)" else ""
    }

    /** Play a catalog playlist in the chosen order, without its rated tracks if Skip Rated is on. */
    fun playPlaylist(doc: Doc) = scope.launch {
        val c = controller ?: return@launch
        val tree = MusicTree.root(ctx) ?: return@launch
        busy = str(ctx, "LANG_WAIT")
        var items = PlaylistFiles.load(ctx, tree, doc.id, doc.name)
        val total = items.size
        if (prefs.skipRated) items = items.filter { !rated(it.mediaId) }
        loaded = items
        items = withContext(Dispatchers.Default) { sorted(items, prefs.order) }
        busy = null
        if (items.isEmpty()) { message = if (total == 0) "Empty playlist" else "Every track in it is rated"; return@launch }
        // The player's Shuffle would reshuffle the new playlist and undo the chosen order
        // (the rater has its own Shuffle order), so it goes off first.
        val wasShuffled = shuffleOff()
        Queue.play(c, items, 0)
        SlopPlayer.current?.markModified()
        message = "${doc.name.substringBeforeLast('.')}: ${items.size} of $total to rate" +
            if (wasShuffled) " (Shuffle turned off)" else ""
    }

    BackHandler(enabled = menu != null) { menu = null; prefs.save(); rev++ }
    if (busy != null) {
        Column(Modifier.fillMaxSize()) { TitleBar("Track Rater", onBack = null); ListNote(busy!!) }
        return
    }
    when (val m = menu) {
        null -> {}
        "menu" -> {
            fun yn(b: Boolean) = if (b) "Yes" else "No"
            Column(Modifier.fillMaxSize()) {
                TitleBar("Track Rater", onBack = { menu = null; prefs.save(); rev++ })
                RockList(Modifier.fillMaxSize()) {
                    item { RockRow("Play Playlist", icon = G.ListQ) { menu = "playlists" } }
                    item { RockRow("Order", trailing = RaterPrefs.ORDERS[prefs.order]) { menu = "order" } }
                    item { RockRow("Skip Rated Tracks", trailing = yn(prefs.skipRated)) { prefs.skipRated = !prefs.skipRated; rev++ } }
                    item { RockRow("Next Track After Rating", trailing = yn(prefs.advance)) { prefs.advance = !prefs.advance; rev++ } }
                    item { RockRow("Jump", trailing = "${prefs.jumpSec} s") { menu = "jump" } }
                    item { RockRow("Quit", icon = G.Cursor) { prefs.save(); nav.pop() } }
                }
            }
            return
        }
        "playlists" -> {
            CatalogList(str(ctx, "LANG_PLAYLISTS"), playlistPath, onBack = { menu = "menu" },
                empty = { ListNote(str(ctx, "LANG_NO_FILES")) }) { _, d -> menu = null; prefs.save(); playPlaylist(d) }
            return
        }
        "order", "jump" -> {
            val labels = if (m == "order") RaterPrefs.ORDERS else RaterPrefs.JUMPS.map { "$it s" }
            val cur = if (m == "order") prefs.order else RaterPrefs.JUMPS.indexOf(prefs.jumpSec)
            Column(Modifier.fillMaxSize()) {
                TitleBar(if (m == "order") "Order" else "Jump", onBack = { menu = "menu" })
                RockList(Modifier.fillMaxSize()) {
                    items(labels.indices.toList()) { i ->
                        RockRow(labels[i], selected = i == cur) {
                            val reorder = m == "order" && i != prefs.order
                            if (m == "order") prefs.order = i else prefs.jumpSec = RaterPrefs.JUMPS[i]
                            prefs.save(); rev++; menu = "menu"
                            // A new order applies to what's playing now, not only the next Play Playlist.
                            if (reorder) resortQueue()
                        }
                    }
                }
            }
            return
        }
    }

    // ---- the rating screen ----
    val mid = id
    val t = mid?.let { TagDb.track(it) }
    val md = item?.mediaMetadata
    val rating = mid?.let { ratingOf(it) } ?: 0
    val inDb = t != null
    Column(Modifier.fillMaxSize()) {
        TitleBar("Track Rater", onBack = { prefs.save(); nav.pop() }) { BarButton(G.Config) { menu = "menu" } }
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val unrated = remember(count, statsRev, rev) {
                controller?.let { c -> (0 until c.mediaItemCount).count { !rated(c.getMediaItemAt(it).mediaId) } } ?: 0
            }
            BasicText(if (index >= 0) "${index + 1} / $count · $unrated unrated" else "Nothing playing: menu › Play Playlist",
                style = Cabbie.small.copy(color = Cabbie.Dim), modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.weight(1f))
            if (mid != null) {
                val artist = t?.artist?.ifEmpty { null } ?: md?.artist?.toString() ?: ""
                val title = t?.title?.ifEmpty { null } ?: md?.title?.toString() ?: ""
                val album = t?.album?.ifEmpty { null } ?: md?.albumTitle?.toString() ?: ""
                BasicText(artist.ifEmpty { "Unknown artist" }, style = Cabbie.body.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                BasicText(title, style = Cabbie.body.copy(fontSize = 20.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                BasicText(listOf(album, t?.year?.takeIf { it > 0 }?.toString() ?: "").filter { it.isNotEmpty() }.joinToString(" · "),
                    style = Cabbie.small.copy(color = Cabbie.Dim), maxLines = 2, overflow = TextOverflow.Ellipsis)
                BasicText(if (rating > 0) "★".repeat(stars(rating)) + "☆".repeat(5 - stars(rating)) else "unrated",
                    style = Cabbie.body.copy(color = if (rating > 0) Cabbie.Gold else Cabbie.Dim, fontSize = 22.sp))
            }
            val note = when {
                RaterExport.failed -> "Couldn't write ${TagDb.CHANGELOG} to the music folder"
                mid != null && !inDb -> "Not in the database, so it can't be rated: Database › Update Now"
                else -> message
            }
            note?.let { BasicText(it, style = Cabbie.small.copy(color = Cabbie.Gold)) }
            Spacer(Modifier.weight(1f))
            GoldBar(scrub ?: if (dur > 0) pos.toFloat() / dur else 0f, Modifier.fillMaxWidth().height(22.dp)) { f, done ->
                if (done) { controller?.seekTo((f * dur).toLong()); scrub = null } else scrub = f
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                BasicText(clock(pos), style = Cabbie.small.copy(color = Cabbie.Dim))
                BasicText(clock(dur), style = Cabbie.small.copy(color = Cabbie.Dim))
            }
        }
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Key("− ${prefs.jumpSec}s", 60) { jump(-1) }
                Key(if (playing) "❚❚" else "▶", 60) {
                    controller?.let { c ->
                        if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
                        if (c.playWhenReady) c.pause() else c.play()
                    }
                }
                Key("+ ${prefs.jumpSec}s", 60) { jump(1) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Key("⏮ Track", 44, small = true) { previous() }
                Key("Undo", 44, small = true, enabled = undoCount > 0) { undoLast() }
                Key("Track ⏭", 44, small = true) { next() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (n in 1..5) {
                    Key("$n", 84, color = StarColors[n - 1], sub = StarWords[n - 1], enabled = inDb,
                        selected = stars(rating) == n) { rate(n) }
                }
            }
        }
    }
}

/** A plugin button: grey by default, or a coloured rating key. */
@Composable
private fun RowScope.Key(
    text: String, height: Int, small: Boolean = false, color: Color? = null, sub: String = "",
    enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> Color(0xFF2A2A2A)
        color != null -> color
        else -> Color(0xFF3C3C3C)
    }
    Box(
        Modifier.weight(1f).height(height.dp).clip(RoundedCornerShape(10.dp)).background(bg)
            .then(if (selected) Modifier.background(Color.White.copy(alpha = 0.18f)) else Modifier)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(text, style = Cabbie.body.copy(
                color = if (enabled) Color.White else Cabbie.Dim, fontSize = if (small) 15.sp else if (color != null) 26.sp else 19.sp,
                fontWeight = if (color != null) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center), maxLines = 1)
            if (sub.isNotEmpty()) BasicText(sub, style = Cabbie.small.copy(color = if (enabled) Color.White else Cabbie.Dim, fontSize = 11.sp),
                maxLines = 1)
        }
    }
}

/** Sort a playlist for rating: stable, so ties keep the playlist's order. */
private fun sorted(items: List<MediaItem>, order: Int): List<MediaItem> {
    fun t(i: MediaItem) = TagDb.track(i.mediaId)
    fun s(v: String?) = v?.lowercase() ?: ""
    return when (RaterPrefs.ORDERS[order]) {
        "Shuffle" -> items.shuffled()
        "Artist" -> items.sortedWith(compareBy({ s(t(it)?.artist ?: it.mediaMetadata.artist?.toString()) }, { s(t(it)?.album) }))
        "Album" -> items.sortedWith(compareBy({ s(t(it)?.album ?: it.mediaMetadata.albumTitle?.toString()) }, { t(it)?.trackNum ?: 0 }))
        "Year" -> items.sortedBy { t(it)?.year?.takeIf { y -> y > 0 } ?: 9999 }
        "Title" -> items.sortedBy { s(t(it)?.title ?: it.mediaMetadata.title?.toString()) }
        else -> items
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private fun str(ctx: Context, id: String) = llm.slop.slopbox.SettingsStore.get(ctx).catalog.str(id)
