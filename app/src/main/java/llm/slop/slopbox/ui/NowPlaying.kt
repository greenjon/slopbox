package llm.slop.slopbox.ui

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import llm.slop.slopbox.Engine

/** What the WPS shows, polled from the controller (the session has no push for position). */
private data class Snap(
    val count: Int = 0,
    val index: Int = 0,
    val item: MediaItem? = null,
    val next: MediaItem? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val ended: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val canShuffle: Boolean = false,
    val canRepeat: Boolean = false,
    val codecLine: String = "",
    val volume: Float = 0f,
)

private fun snap(c: MediaController, ctx: Context): Snap {
    val count = c.mediaItemCount
    val index = c.currentMediaItemIndex
    val md = c.mediaMetadata
    val info = Engine.trackInfo()
    val am = ctx.getSystemService(AudioManager::class.java)
    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    return Snap(
        count = count,
        index = index,
        item = c.currentMediaItem,
        next = if (index + 1 < count) c.getMediaItemAt(index + 1) else null,
        title = md.title?.toString().orEmpty(),
        artist = md.artist?.toString().orEmpty(),
        album = md.albumTitle?.toString().orEmpty(),
        positionMs = c.currentPosition,
        durationMs = c.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
        playing = c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_READY),
        ended = c.playbackState == Player.STATE_ENDED || c.playbackState == Player.STATE_IDLE,
        shuffle = c.shuffleModeEnabled,
        repeat = c.repeatMode,
        canShuffle = c.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE),
        canRepeat = c.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE),
        // %?fv<(vbr) |>%fb kbit/s %fc
        codecLine = if (info.codec.isNotEmpty()) "${info.bitrate} kbit/s ${info.codec}" else "",
        volume = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max,
    )
}

private const val POPUP_MS = 3000L   // cabbiev2: touch shows Browse / Quick Screen / Context Menu
private const val VOLUME_MS = 2000L  // cabbiev2: %?mv(2.0) / %?Tl(small_vol,2.0)

/**
 * The While Playing Screen, after cabbiev2's 480x800 touch WPS:
 * gold NOW PLAYING bar, album art, track lines, progress bar and times,
 * then rewind/ff on the left and volume/shuffle/repeat/play on the right.
 *
 * Touch, as in cabbiev2: art or track lines open the playlist; any touch shows
 * the Browse / Quick Screen / Context Menu popup for a moment; the volume icon
 * (or a volume key) shows a volume bar; the progress bar seeks; play/pause
 * (hold: stop); |<< >>| skip (hold: rewind / fast forward).
 */
@Composable
fun NowPlayingScreen(controller: MediaController?, nav: Nav) {
    val ctx = LocalContext.current
    var s by remember { mutableStateOf(Snap()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastTouch by remember { mutableLongStateOf(0L) }
    var volumeAt by remember { mutableLongStateOf(0L) }
    /** Position being dragged or fast-forwarded to; sought on release. */
    var preview by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(controller) {
        var lastVol = -1f
        while (controller != null) {
            val n = snap(controller, ctx)
            if (lastVol >= 0f && n.volume != lastVol) volumeAt = System.currentTimeMillis()
            lastVol = n.volume
            s = n
            now = System.currentTimeMillis()
            delay(200)
        }
    }

    var tags by remember { mutableStateOf(Tags()) }
    var nextTags by remember { mutableStateOf(Tags()) }
    val curUri = s.item?.requestMetadata?.mediaUri
    val nextUri = s.next?.requestMetadata?.mediaUri
    LaunchedEffect(curUri) { tags = s.item?.let { TagReader.read(ctx, it, withArt = true) } ?: Tags() }
    LaunchedEffect(nextUri) { nextTags = s.next?.let { TagReader.read(ctx, it, withArt = false) } ?: Tags() }

    val dirName = s.item?.requestMetadata?.extras?.getString(MusicTree.EXTRA_DIR_NAME).orEmpty()
    val album = s.album.ifEmpty { tags.album }.ifEmpty { dirName }
    val title = tags.title.ifEmpty { s.title }
    val artist = s.artist.ifEmpty { tags.artist }.ifEmpty { tags.albumArtist }
    val nextArtist = nextTags.artist.ifEmpty { nextTags.albumArtist }
    val nextTitle = nextTags.title.ifEmpty { s.next?.mediaMetadata?.title?.toString().orEmpty() }
    val art = tags.art

    val showVolume = now - volumeAt < VOLUME_MS
    val showPopup = !showVolume && now - lastTouch < POPUP_MS

    Column(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Watch every touch without taking it (cabbiev2's %Tl).
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    lastTouch = System.currentTimeMillis()
                    now = lastTouch
                }
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(30.dp).glossyPill(Cabbie.gold, Cabbie.GoldDark),
            contentAlignment = Alignment.Center,
        ) { BasicText("NOW PLAYING", style = Cabbie.header) }

        if (s.count == 0) {
            Box(
                Modifier.weight(1f).fillMaxWidth().clickable { nav.toBrowser() },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    "Nothing is playing.\nTap to browse your music.",
                    style = Cabbie.body.copy(color = Cabbie.Dim, textAlign = TextAlign.Center),
                )
            }
            return@Column
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val artSize: Dp = min(maxWidth * 0.68f, maxHeight * 0.48f)
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(14.dp))
                val toPlaylist = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                ) { nav.push(Screen.Playlist) }
                if (art != null) {
                    Image(
                        art, contentDescription = "Album art",
                        modifier = Modifier.align(Alignment.CenterHorizontally).size(artSize)
                            .clip(RoundedCornerShape(4.dp)).then(toPlaylist),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.height(12.dp))
                } else {
                    // No art: the upper lines sit where the art would be, and open the playlist too.
                    Column(toPlaylist.fillMaxWidth().padding(vertical = 8.dp)) {
                        Line(album, scroll = true)
                        Line(title, scroll = true, big = true)
                        Line(artist, scroll = true)
                        Line(tags.year)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        showVolume -> VolumeBar(ctx, s.volume) { volumeAt = System.currentTimeMillis() }
                        showPopup -> PopupMenu(nav)
                        art != null -> Column(Modifier.fillMaxSize()) {
                            Line(album, scroll = true)
                            Line(title, scroll = true, big = true)
                            Line(artist, scroll = true)
                            Spacer(Modifier.weight(1f))
                            Line(
                                if (s.next != null) "Next: ${listOf(nextArtist, nextTitle).filter { it.isNotEmpty() }.joinToString(" - ")}" else "",
                                scroll = true, dim = true,
                            )
                        }
                        else -> Column(Modifier.fillMaxSize()) {
                            Line(tags.genre)
                            Line(s.codecLine)
                            Spacer(Modifier.weight(1f))
                            if (s.next != null) {
                                Line(nextArtist, dim = true)
                                Line("Next Track:", dim = true)
                                Line(nextTitle, dim = true)
                            }
                        }
                    }
                }
            }
        }

        // Progress bar, then "elapsed   3 of 12   remaining".
        val shown = preview ?: s.positionMs
        val dur = s.durationMs
        GoldBar(
            fraction = if (dur > 0) shown.toFloat() / dur else 0f,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(22.dp),
        ) { f, done ->
            if (dur <= 0) return@GoldBar
            val ms = (f * dur).toLong()
            if (done) {
                controller?.seekTo(ms)
                preview = null
            } else preview = ms
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(fmt(shown), Modifier.weight(1f), style = Cabbie.small)
            BasicText("${s.index + 1} of ${s.count}", Modifier.weight(1f), style = Cabbie.small.copy(textAlign = TextAlign.Center))
            BasicText(
                if (dur > 0) fmt((dur - shown).coerceAtLeast(0)) else "",
                Modifier.weight(1f), style = Cabbie.small.copy(textAlign = TextAlign.End),
            )
        }

        // Bottom row.
        var seeking by remember { mutableStateOf<G?>(null) }
        var ffStep by remember { mutableStateOf(1000L) }
        val settings = remember { llm.slop.slopbox.SettingsStore.get(ctx) }
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((g, dir) in listOf(G.Prev to -1, G.Next to 1)) {
                SeekButton(
                    g, Modifier.weight(1f),
                    onTap = { if (dir < 0) controller?.seekToPrevious() else controller?.seekToNext() },
                    onHold = { _ ->
                        seeking = g
                        // Rockbox gui/wps.c ffwd_rew(): start at "FF/RW Min Step", grow by
                        // step >> (accel + 3) each repeat, capped at 3% of what's left.
                        val base = preview ?: s.positionMs
                        if (preview == null) {
                            ffStep = 1000L * settings.int("scan min step")
                        } else {
                            val room = if (dir > 0) (if (dur > 0) dur - base else Long.MAX_VALUE / 4) else base
                            val maxStep = maxOf(room * 3 / 100, 500L)
                            if (ffStep > maxStep) ffStep = maxStep
                        }
                        preview = (base + dir * ffStep).coerceIn(0, if (dur > 0) dur else Long.MAX_VALUE)
                        ffStep += ffStep shr (settings.int("seek acceleration") + 3)
                    },
                    onRelease = {
                        seeking = null
                        preview?.let { controller?.seekTo(it) }
                        preview = null
                    },
                )
            }
            Spacer(Modifier.weight(0.5f))
            CtrlButton(Modifier.weight(1f), onTap = { volumeAt = System.currentTimeMillis() }) {
                Glyph(G.Volume, Modifier.size(36.dp), level = s.volume)
            }
            CtrlButton(Modifier.weight(1f), enabled = s.canShuffle, onTap = {
                controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
            }) { Glyph(G.Shuffle, Modifier.size(36.dp), lit = s.canShuffle && s.shuffle) }
            CtrlButton(Modifier.weight(1f), enabled = s.canRepeat, onTap = {
                controller?.let {
                    it.repeatMode = when (it.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                }
            }) {
                val g = when {
                    !s.canRepeat -> G.RepeatOff
                    s.repeat == Player.REPEAT_MODE_ONE -> G.RepeatOne
                    s.repeat == Player.REPEAT_MODE_ALL -> G.RepeatAll
                    else -> G.RepeatOff
                }
                Glyph(g, Modifier.size(36.dp))
            }
            CtrlButton(
                Modifier.weight(1f),
                onTap = { controller?.let { if (it.isPlaying || it.playWhenReady) it.pause() else it.play() } },
                onLongPress = { controller?.let { it.pause(); it.seekTo(0) } },
            ) {
                // cabbiev2 shows what's happening: pause bars while playing, play when paused.
                val g = seeking ?: when {
                    s.ended -> G.Stop
                    s.playing -> G.Pause
                    else -> G.Play
                }
                Glyph(g, Modifier.size(40.dp))
            }
        }
    }
}

@Composable
private fun ColumnScope.Line(text: String, scroll: Boolean = false, big: Boolean = false, dim: Boolean = false) {
    if (text.isEmpty() && !scroll) return
    BasicText(
        text,
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
        style = (if (big) Cabbie.body.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold) else Cabbie.body)
            .copy(textAlign = TextAlign.Center, color = if (dim) Cabbie.Dim else Cabbie.Text),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** cabbiev2's popup: Browse, Quick Screen, Context Menu. */
@Composable
private fun PopupMenu(nav: Nav) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .drawBehind {
                    val r = CornerRadius(10.dp.toPx())
                    drawRoundRect(Color(0xFF4A4A4A), cornerRadius = r)
                }
                .border(3.dp, Color(0xFFBDBDBD), RoundedCornerShape(10.dp))
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PopupButton(G.Folder, null, Modifier.weight(1.2f)) { nav.toBrowser() }
            // Rockbox "Use Shortcuts Menu Instead of Quick Screen".
            PopupButton(G.ListQ, "Q", Modifier.weight(1f)) {
                nav.push(if (llm.slop.slopbox.SettingsStore.get(ctx).int("shortcuts instead of quickscreen") != 0)
                    Screen.Shortcuts else Screen.QuickScreen)
            }
            // Long press: the WPS hotkey (General Settings › While Playing Screen › WPS Hotkey).
            PopupButton(G.ListC, "C", Modifier.weight(1f), onLongClick = {
                WpsHotkey.pending = true
                nav.push(Screen.ContextMenu)
            }) { nav.push(Screen.ContextMenu) }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PopupButton(g: G, letter: String?, modifier: Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Box(modifier.fillMaxHeight().combinedClickable(onLongClick = onLongClick, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxHeight(0.85f).aspectRatio(1f)) {
            Glyph(g, Modifier.fillMaxSize())
            if (letter != null) {
                BasicText(
                    letter,
                    Modifier.align(Alignment.BottomEnd),
                    style = Cabbie.body.copy(
                        fontSize = 44.sp, fontWeight = FontWeight.Black, color = Color.Black,
                        shadow = androidx.compose.ui.graphics.Shadow(Cabbie.Gold, blurRadius = 6f),
                    ),
                )
            }
        }
    }
}

@Composable
private fun VolumeBar(ctx: Context, volume: Float, onTouch: () -> Unit) {
    val am = remember { ctx.getSystemService(AudioManager::class.java) }
    val max = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        BasicText(
            "Volume ${(volume * max).toInt()} / $max",
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            style = Cabbie.body.copy(textAlign = TextAlign.Center),
        )
        GoldBar(volume, Modifier.fillMaxWidth().height(44.dp)) { f, _ ->
            onTouch()
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (f * max + 0.5f).toInt().coerceIn(0, max), 0)
        }
    }
}

/** Grey pill track with a gold fill; tap or drag reports a fraction ([done] on release). */
@Composable
fun GoldBar(fraction: Float, modifier: Modifier, onScrub: ((Float, Boolean) -> Unit)? = null) {
    val scrub by rememberUpdatedState(onScrub)
    Box(
        modifier.then(
            if (onScrub == null) Modifier else Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    fun f(x: Float) = (x / size.width).coerceIn(0f, 1f)
                    scrub?.invoke(f(down.position.x), false)
                    var x = down.position.x
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        x = ch.position.x
                        if (!ch.pressed) break
                        ch.consume()
                        scrub?.invoke(f(x), false)
                    }
                    scrub?.invoke(f(x), true)
                }
            }
        ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.8f).glossyPill(Cabbie.track, Color(0xFF1E1E1E)))
        val fr = fraction.coerceIn(0f, 1f)
        if (fr > 0f) {
            Box(Modifier.fillMaxWidth(fr).fillMaxHeight(0.8f).glossyPill(Cabbie.gold, Cabbie.GoldDark))
        }
    }
}

@Composable
private fun CtrlButton(
    modifier: Modifier,
    enabled: Boolean = true,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLongPress)
    Box(
        modifier.fillMaxHeight().then(
            if (!enabled) Modifier else Modifier.pointerInput(Unit) {
                detectTapGestures(onTap = { tap() }, onLongPress = { long?.invoke() })
            }
        ),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Tap: [onTap]. Hold: [onHold] repeatedly with time held, then [onRelease]. */
@Composable
private fun SeekButton(g: G, modifier: Modifier, onTap: () -> Unit, onHold: (Long) -> Unit, onRelease: () -> Unit) {
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    val release by rememberUpdatedState(onRelease)
    Box(
        modifier.fillMaxHeight().pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown().consume()
                val start = System.currentTimeMillis()
                val upEarly = withTimeoutOrNull(400) { waitForUpOrCancellation(); true }
                if (upEarly != null) {
                    tap()
                    return@awaitEachGesture
                }
                while (true) {
                    hold(System.currentTimeMillis() - start)
                    val up = withTimeoutOrNull(150) { waitForUpOrCancellation(); true }
                    if (up != null) break
                }
                release()
            }
        },
        contentAlignment = Alignment.Center,
    ) { Glyph(g, Modifier.size(36.dp)) }
}

private fun fmt(ms: Long): String {
    val t = ms / 1000
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, (t / 60) % 60, t % 60)
    else "%d:%02d".format(t / 60, t % 60)
}
