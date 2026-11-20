package llm.slop.slopbox.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import llm.slop.slopbox.Engine

// ---- Playlist viewer -------------------------------------------------------

@Composable
fun PlaylistScreen(controller: MediaController?, nav: Nav) {
    var version by remember { mutableIntStateOf(0) }
    var current by remember { mutableIntStateOf(controller?.currentMediaItemIndex ?: 0) }
    var menuFor by remember { mutableStateOf<Int?>(null) }
    // Rockbox playlist viewer Move: pick a track, then where it goes.
    var moving by remember { mutableStateOf<Int?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val store = remember { llm.slop.slopbox.SettingsStore.get(ctx) }
    val showIcons = store.int("playlist viewer icons") != 0
    val items: List<MediaItem> = remember(controller, version) {
        controller?.let { c -> (0 until c.mediaItemCount).map { c.getMediaItemAt(it) } } ?: emptyList()
    }
    LaunchedEffect(controller) {
        while (controller != null) {
            current = controller.currentMediaItemIndex
            delay(500)
        }
    }

    BackHandler(enabled = menuFor != null) { menuFor = null }
    menuFor?.let { i ->
        Column(Modifier.fillMaxSize()) {
            TitleBar(name(items.getOrNull(i)), onBack = { menuFor = null })
            RockRow("Play", icon = G.Cursor) {
                controller?.run { seekTo(i, 0); play() }
                menuFor = null
                nav.pop()
            }
            RockRow(store.catalog.str("LANG_REMOVE"), icon = G.TangoNote) {
                controller?.removeMediaItem(i)
                menuFor = null
                version++
            }
            RockRow(store.catalog.str("LANG_MOVE"), icon = G.TangoNote) {
                moving = i
                menuFor = null
            }
        }
        return
    }

    val listState = rememberLazyListState((current - 3).coerceAtLeast(0))
    Column(Modifier.fillMaxSize()) {
        TitleBar(if (moving != null) store.catalog.str("LANG_MOVE") else "Playlist (${items.size})",
            onBack = { if (moving != null) moving = null else nav.pop() })
        if (items.isEmpty()) ListNote("The playlist is empty.")
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            itemsIndexed(items) { i, item ->
                RockRow(
                    trackLabel(ctx, item, i),
                    icon = if (!showIcons) null else if (i == current) G.Cursor else G.TangoNote,
                    selected = if (moving != null) i == moving else i == current,
                    onLongClick = { menuFor = i },
                ) {
                    val m = moving
                    if (m != null) {
                        controller?.moveMediaItem(m, i)
                        moving = null
                        version++
                    } else {
                        controller?.run { seekTo(i, 0); play() }
                        nav.pop()
                    }
                }
            }
        }
    }
}

private fun name(item: MediaItem?): String =
    item?.mediaMetadata?.title?.toString()?.takeIf { it.isNotEmpty() } ?: item?.mediaId ?: ""

// ---- Quick Screen ----------------------------------------------------------

/** Rockbox's default Quick Screen: shuffle on the left, repeat on the right; tap to change. */
@Composable
fun QuickScreen(controller: MediaController?, nav: Nav) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(300); tick++ } }
    val c = controller
    tick // re-read controller state as it changes
    val canShuffle = c?.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE) == true
    val canRepeat = c?.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE) == true
    Column(Modifier.fillMaxSize()) {
        TitleBar("Quick Screen", onBack = { nav.pop() })
        Row(
            Modifier.fillMaxWidth().weight(1f).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QsItem(
                "Shuffle",
                if (!canShuffle) "not yet" else if (c!!.shuffleModeEnabled) "On" else "Off",
                G.Shuffle,
                lit = canShuffle && c!!.shuffleModeEnabled,
                enabled = canShuffle,
                modifier = Modifier.weight(1f),
            ) { c?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled; tick++ } }
            val mode = c?.repeatMode ?: Player.REPEAT_MODE_OFF
            QsItem(
                "Repeat",
                if (!canRepeat) "not yet" else when (mode) {
                    Player.REPEAT_MODE_ALL -> "All"
                    Player.REPEAT_MODE_ONE -> "One"
                    else -> "Off"
                },
                when (mode) {
                    Player.REPEAT_MODE_ALL -> G.RepeatAll
                    Player.REPEAT_MODE_ONE -> G.RepeatOne
                    else -> G.RepeatOff
                },
                lit = canRepeat,
                enabled = canRepeat,
                modifier = Modifier.weight(1f),
            ) {
                c?.let {
                    it.repeatMode = when (it.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                    tick++
                }
            }
        }
    }
}

@Composable
private fun QsItem(label: String, value: String, g: G, lit: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .height(180.dp)
            .background(Cabbie.track, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        BasicText(label, style = Cabbie.body)
        Glyph(g, Modifier.size(56.dp), lit = lit)
        BasicText(value, style = Cabbie.body.copy(color = if (enabled) Cabbie.SelTop else Cabbie.Dim))
    }
}

// ---- WPS context menu ------------------------------------------------------

@Composable
fun WpsContextMenu(nav: Nav) {
    Column(Modifier.fillMaxSize()) {
        TitleBar("Context Menu", onBack = { nav.pop() })
        val cat = llm.slop.slopbox.SettingsStore.get(androidx.compose.ui.platform.LocalContext.current).catalog
        RockRow(cat.str("LANG_CURRENT_PLAYLIST"), icon = G.TangoNote) { nav.pop(); nav.push(Screen.CurrentPlaylist) }
        RockRow(cat.str("LANG_ADD_TO_PL"), icon = G.TangoNote) {
            val p = llm.slop.slopbox.SlopPlayer.current
            val uri = p?.currentMediaItem?.requestMetadata?.mediaUri
            if (uri != null) {
                PlaylistsView.addPaths = listOf(llm.slop.slopbox.PlaybackMemory.pathOf(uri))
                PlaylistsView.addTitle = p.currentMediaItem?.mediaMetadata?.title?.toString() ?: ""
                nav.pop(); nav.push(Screen.AddToPlaylist)
            }
        }
        RockRow(llm.slop.slopbox.SettingsStore.get(androidx.compose.ui.platform.LocalContext.current).catalog.str("LANG_BOOKMARK_MENU"),
            icon = G.TangoNote) { nav.pop(); nav.push(Screen.Bookmarks) }
        RockRow("Sound Settings", icon = G.TangoNote) { nav.pop(); nav.push(Screen.Sound) }
        RockRow("Show Track Info", icon = G.TangoNote) { nav.pop(); nav.push(Screen.TrackInfo) }
    }
}

// ---- Track info ------------------------------------------------------------

@Composable
fun TrackInfoScreen(controller: MediaController?, nav: Nav) {
    val ctx = LocalContext.current
    val item = controller?.currentMediaItem
    var tags by remember { mutableStateOf<Tags?>(null) }
    LaunchedEffect(item?.requestMetadata?.mediaUri) { tags = item?.let { TagReader.read(ctx, it, withArt = false) } }
    val info = remember { Engine.trackInfo() }
    val md = controller?.mediaMetadata
    val t = tags ?: Tags()
    val dur = controller?.duration?.takeIf { it != C.TIME_UNSET } ?: info.lengthMs
    val rows = listOf(
        "Title" to (t.title.ifEmpty { md?.title?.toString().orEmpty() }),
        "Artist" to (t.artist.ifEmpty { md?.artist?.toString().orEmpty() }),
        "Album" to (t.album.ifEmpty { md?.albumTitle?.toString().orEmpty() }),
        "Album Artist" to t.albumArtist,
        "Track Number" to t.trackNo,
        "Year" to t.year,
        "Genre" to t.genre,
        "Length" to if (dur > 0) "%d:%02d".format(dur / 60000, (dur / 1000) % 60) else "",
        "Bitrate" to if (info.bitrate > 0) "${info.bitrate} kbit/s" else "",
        "Frequency" to if (info.frequency > 0) "${info.frequency} Hz" else "",
        "Codec" to info.codec,
        "File" to (item?.requestMetadata?.mediaUri?.lastPathSegment?.substringAfterLast('/') ?: ""),
    )
    Column(Modifier.fillMaxSize()) {
        TitleBar("Track Info", onBack = { nav.pop() })
        if (item == null) ListNote("Nothing is playing.")
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows.filter { it.second.isNotEmpty() }) { (label, value) ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    BasicText("$label:", style = Cabbie.small.copy(color = Cabbie.Dim))
                    BasicText(value, style = Cabbie.body)
                }
            }
        }
    }
}
