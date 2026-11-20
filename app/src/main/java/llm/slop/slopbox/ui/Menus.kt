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

    var saving by remember { mutableStateOf(false) }
    BackHandler(enabled = menuFor != null || saving) { menuFor = null; saving = false }
    if (saving) { SavePlaylistScreen(onDone = { saving = false }); return }
    menuFor?.let { i ->
        val item = items.getOrNull(i) ?: run { menuFor = null; return }
        ViewerTrackMenu(item, nav,
            onBack = { menuFor = null; version++ },
            onRemove = { controller?.removeMediaItem(i); menuFor = null; version++ },
            onMove = { moving = i; menuFor = null },
            onShuffle = { llm.slop.slopbox.SlopPlayer.current?.reshuffle(); menuFor = null; version++ },
            onSave = { menuFor = null; saving = true },
        )
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
