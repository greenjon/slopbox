/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/gui/wps.c - Copyright (C) 2002 Jerome Kuptz
 *   cabbiev2 theme by Johannes Voggenthaler, Apoo Maha, Marc Guay, Alex Vanderpol,
 *   Jerry Lange, Keith Perri, Mark Fawcus and Marianne Arnold, based on Cabbie
 *   by Yohann Misquitta (Rockbox wps/cabbiev2); icons after the Tango icon set
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
import android.media.AudioManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.width
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
    /** Rockbox's repeat setting: 0 off, 1 all, 2 one, 3 shuffle, 4 A-B. */
    val rbRepeat: Int = 0,
    val abA: Long = -1,
    val abB: Long = -1,
    /** Cuesheet track starts after the first (cue_draw_markers()). */
    val cueMarks: List<Long> = emptyList(),
    /** SlopBox rating filter note ("★4+"), or null when it's off. */
    val filter: String? = null,
)

private fun snap(c: MediaController, ctx: Context): Snap {
    val count = c.mediaItemCount
    val index = c.currentMediaItemIndex
    val md = c.mediaMetadata
    val info = Engine.trackInfo()
    val am = ctx.getSystemService(AudioManager::class.java)
    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val store = llm.slop.slopbox.SettingsStore.get(ctx)
    // Next: the one the rating filter lets through.
    var nextIdx = index + 1
    while (nextIdx < count && !RatingFilter.passes(store, c.getMediaItemAt(nextIdx).mediaId)) nextIdx++
    return Snap(
        count = count,
        index = index,
        item = c.currentMediaItem,
        next = if (nextIdx < count) c.getMediaItemAt(nextIdx) else null,
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
        rbRepeat = store.int("repeat"),
        abA = llm.slop.slopbox.SlopPlayer.current?.abMarkers?.first ?: -1,
        abB = llm.slop.slopbox.SlopPlayer.current?.abMarkers?.second ?: -1,
        cueMarks = llm.slop.slopbox.SlopPlayer.current?.cuesheet?.tracks?.drop(1)?.map { it.offsetMs } ?: emptyList(),
        filter = RatingFilter.label(store),
    )
}

private const val POPUP_MS = 3000L   // cabbiev2: touch shows Browse / Quick Screen / Context Menu
private const val VOLUME_MS = 2000L  // cabbiev2: %?mv(2.0) / %?Tl(small_vol,2.0)

/**
 * The While Playing Screen, after cabbiev2's 480x800 touch WPS:
 * gold NOW PLAYING bar, album art, track lines, progress bar and times,
 * then rewind/ff on the left and volume/shuffle/repeat/play on the right.
 *
 * Touch, as in cabbiev2: art or track lines open the playlist; a touch on the
 * art or track lines (not the bar or controls) shows the Browse / Quick Screen /
 * Context Menu popup for a moment; the volume icon
 * (or a volume key) shows a volume bar; the progress bar seeks; play/pause
 * (hold: stop); |<< >>| skip (hold: rewind / fast forward).
 */
@Composable
fun NowPlayingScreen(controller: MediaController?, nav: Nav) {
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
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
    LaunchedEffect(curUri) {
        Rater.message = null
        tags = s.item?.let { TagReader.read(ctx, it, withArt = true) } ?: Tags()
    }
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

    // Rockbox's While Playing Screen setting picks the layout (Theme Settings › While Playing Screen).
    val store = remember { llm.slop.slopbox.SettingsStore.get(ctx) }
    val layout = WpsLayout.of(store)
    // SlopBox Album Art Colours: the line selector colours (and so the controls) from the art, here only.
    val accentOn = store.int(llm.slop.slopbox.SlopBoxSettings.ART_ACCENT) != 0
    LaunchedEffect(art, accentOn) {
        Cabbie.accent = if (accentOn && art != null)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ArtAccent.of(art) } else null
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { Cabbie.accent = null } }
    val overlay: (@Composable () -> Unit)? = when {
        showVolume -> ({ VolumeBar(ctx, s.volume) { volumeAt = System.currentTimeMillis() } })
        showPopup -> ({ PopupMenu(nav) })
        else -> null
    }
    var showTotal by remember { mutableStateOf(false) }
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE

    Box(Modifier.fillMaxSize()) {
    if (layout == WpsLayout.BIG_ART && art != null) BlurredArt(art)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        if (layout == WpsLayout.CABBIE) Box(
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

        val middle: @Composable (Modifier) -> Unit = { mod ->
        // cabbiev2 shows the popup on any touch (%Tl). Here only a tap on the track lines
        // or empty space does: not the controls or bar, and not a swipe, double-tap or
        // long-press (the gestures work over this whole area; a tap on the art opens the playlist).
        BoxWithConstraints(
            mod.artGestures(controller, nav, ctx, haptics) {
                lastTouch = System.currentTimeMillis()
                now = lastTouch
            },
        ) {
            val toPlaylistAlt = Modifier.artGestures(controller, nav, ctx, haptics)
            val nextLine = if (s.next != null) "Next: ${listOf(nextArtist, nextTitle).filter { it.isNotEmpty() }.joinToString(" - ")}" else ""
            if (layout != WpsLayout.CABBIE) {
                AltMiddle(layout, art, album, title, artist, s.codecLine, nextLine, toPlaylistAlt, overlay, s.item?.mediaId)
            } else {
            val artSize: Dp = if (landscape) min(maxWidth * 0.8f, maxHeight * 0.62f) else min(maxWidth * 0.68f, maxHeight * 0.48f)
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(14.dp))
                // cabbiev2: a tap opens the playlist. SlopBox adds gestures (Theme Settings ›
                // SlopBox › Now Playing Gestures), each of which can be turned off.
                val toPlaylist = Modifier.artGestures(controller, nav, ctx, haptics)
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
                            Line(s.codecLine, dim = true)
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
            // SlopBox: a vertical drag along the right edge sets the volume (shows the volume bar).
            if (!showPopup && llm.slop.slopbox.SettingsStore.get(ctx).int(llm.slop.slopbox.SlopBoxSettings.EDGE_VOLUME) != 0) {
                EdgeVolume(ctx, Modifier.align(Alignment.CenterEnd).width(36.dp).fillMaxHeight()) {
                    volumeAt = System.currentTimeMillis()
                    now = volumeAt
                }
            }
        }

        }
        val controls: @Composable ColumnScope.() -> Unit = {
        // Progress bar, then "elapsed   3 of 12   remaining".
        val shown = preview ?: s.positionMs
        val dur = s.durationMs
        GoldBar(
            fraction = if (dur > 0) shown.toFloat() / dur else 0f,
            // A little thicker while being dragged (or fast-forwarded).
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(if (preview != null) 30.dp else 22.dp),
            marks = if (dur > 0) BarMarks(dur, if (s.rbRepeat == 4) s.abA else -1, if (s.rbRepeat == 4) s.abB else -1, s.cueMarks) else null,
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
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText("${s.index + 1} of ${s.count}", style = Cabbie.small.copy(textAlign = TextAlign.Center))
                // SlopBox: Minimum Stars is passing over tracks.
                s.filter?.let { BasicText(it, style = Cabbie.small.copy(color = Cabbie.Dim, textAlign = TextAlign.Center), maxLines = 1) }
            }
            // SlopBox: tap to switch between time left and the track's length.
            BasicText(
                if (dur <= 0) "" else if (showTotal) fmt(dur) else fmt((dur - shown).coerceAtLeast(0)),
                Modifier.weight(1f).clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                ) { showTotal = !showTotal },
                style = Cabbie.small.copy(textAlign = TextAlign.End),
            )
        }

        // A-B repeat (Repeat: A-B): set A, set B (and go back to A), clear --
        // Rockbox's ACTION_WPS_ABSETA / ABSETB / ABRESET.
        if (s.rbRepeat == 4) {
            Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((label, cmd) in listOf("A" to llm.slop.slopbox.PlaybackService.CMD_AB_SET_A,
                    "B" to llm.slop.slopbox.PlaybackService.CMD_AB_SET_B,
                    "A-B ✕" to llm.slop.slopbox.PlaybackService.CMD_AB_RESET)) {
                    Box(Modifier.weight(1f).fillMaxHeight().glossyPill(Cabbie.gold, Cabbie.GoldDark)
                        .clickable { controller?.sendCustomCommand(androidx.media3.session.SessionCommand(cmd, android.os.Bundle.EMPTY), android.os.Bundle.EMPTY) },
                        contentAlignment = Alignment.Center) {
                        BasicText(label, style = Cabbie.body.copy(color = Color.Black, fontWeight = FontWeight.Bold))
                    }
                }
            }
        }

        if (layout == WpsLayout.RATER) RaterKeys(controller, s.item?.mediaId)

        // Bottom row.
        var seeking by remember { mutableStateOf<G?>(null) }
        var ffStep by remember { mutableStateOf(1000L) }
        val settings = remember { llm.slop.slopbox.SettingsStore.get(ctx) }
        val big = layout == WpsLayout.LARGE
        val glyph = if (big) 52.dp else 36.dp
        Row(Modifier.fillMaxWidth().height(if (big) 92.dp else 64.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((g, dir) in listOf(G.Prev to -1, G.Next to 1)) {
                SeekButton(
                    g, Modifier.weight(1f), glyph,
                    onTap = { if (dir < 0) controller?.seekToPrevious() else controller?.seekToNext() },
                    onHold = { _ ->
                        haptics.tick()
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
            // Minimal and Rater: only skip and play/pause.
            if (layout != WpsLayout.MINIMAL && layout != WpsLayout.RATER) {
            CtrlButton(Modifier.weight(1f), onTap = { volumeAt = System.currentTimeMillis() }) {
                Glyph(G.Volume, Modifier.size(glyph), level = s.volume)
            }
            CtrlButton(Modifier.weight(1f), enabled = s.canShuffle, onTap = {
                controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
            }) { Glyph(G.Shuffle, Modifier.size(glyph), lit = s.canShuffle && s.shuffle) }
            // cabbiev2 "repmode": Rockbox ACTION_TOUCH_REPMODE steps the Repeat
            // setting through Off, All, One, Shuffle, A-B.
            CtrlButton(Modifier.weight(1f), onTap = {
                val st = llm.slop.slopbox.SettingsStore.get(ctx)
                st.catalog.byCfg["repeat"]?.let { r -> st.set(r, (st.int(r) + 1) % 5) }
            }) {
                val g = when (s.rbRepeat) {
                    1 -> G.RepeatAll
                    2 -> G.RepeatOne
                    3 -> G.RepeatShuffle
                    4 -> G.RepeatAB
                    else -> G.RepeatOff
                }
                Glyph(g, Modifier.size(glyph))
            }
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
                Glyph(g, Modifier.size(glyph + 4.dp))
            }
        }
        }
        // Landscape: the art and track on the left, the bar and controls on the right.
        if (landscape) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                middle(Modifier.weight(1f).fillMaxHeight())
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) { controls() }
            }
        } else {
            middle(Modifier.weight(1f).fillMaxWidth())
            controls()
        }
    }
    }
}

@Composable
internal fun ColumnScope.Line(text: String, scroll: Boolean = false, big: Boolean = false, dim: Boolean = false) {
    if (text.isEmpty() && !scroll) return
    BasicText(
        text,
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
        style = (if (big) Cabbie.body.copy(fontSize = 20.sp * Cabbie.scale, fontWeight = FontWeight.Bold) else Cabbie.body)
            .copy(textAlign = TextAlign.Center, color = if (dim) Cabbie.Dim else Cabbie.Text),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** cabbiev2's popup: Browse, Quick Screen, Context Menu. */
@Composable
internal fun PopupMenu(nav: Nav) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .drawBehind {
                    val r = CornerRadius(10.dp.toPx())
                    drawRoundRect(Cabbie.Panel, cornerRadius = r)
                }
                .border(3.dp, androidx.compose.ui.graphics.lerp(Cabbie.palette.bg, Cabbie.Text, 0.93f), RoundedCornerShape(10.dp))
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Rockbox "Follow Playlist" (browse_current): the file browser opens at the playing track.
            PopupButton(G.Folder, null, Modifier.weight(1.2f)) {
                val id = llm.slop.slopbox.SlopPlayer.current?.currentMediaItem?.mediaId
                if (llm.slop.slopbox.SettingsStore.get(ctx).int("follow playlist") != 0 && id != null) {
                    FollowPlaylist.pending = id
                    nav.toFiles()
                } else nav.toBrowser()
            }
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
internal fun VolumeBar(ctx: Context, volume: Float, onTouch: () -> Unit) {
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

/** What Rockbox draws on the progress bar: A-B markers and cuesheet track marks. */
class BarMarks(val lengthMs: Long, val a: Long, val b: Long, val cues: List<Long>)

/** Grey pill track with a gold fill; tap or drag reports a fraction ([done] on release). */
@Composable
fun GoldBar(fraction: Float, modifier: Modifier, marks: BarMarks? = null, onScrub: ((Float, Boolean) -> Unit)? = null) {
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
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.8f).glossyPill(Cabbie.track, androidx.compose.ui.graphics.lerp(Cabbie.palette.bg, Cabbie.Text, 0.15f)))
        val fr = fraction.coerceIn(0f, 1f)
        if (fr > 0f) {
            Box(Modifier.fillMaxWidth(fr).fillMaxHeight(0.8f).glossyPill(Cabbie.gold, Cabbie.GoldDark))
        }
        if (marks != null && marks.lengthMs > 0) androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            val h = size.height
            val y0 = h * 0.1f
            val bh = h * 0.8f
            fun x(ms: Long) = size.width * (ms.toFloat() / marks.lengthMs).coerceIn(0f, 1f)
            val markColor = Color.White
            // cue_draw_markers(): a line at each cue track start.
            for (c in marks.cues) drawLine(markColor, androidx.compose.ui.geometry.Offset(x(c), y0),
                androidx.compose.ui.geometry.Offset(x(c), y0 + bh), 2f)
            // ab_draw_markers(): arrows into the A-B section, or lines when too close.
            val arrowW = (bh + 1) / 2
            val xa = x(marks.a); val xb = x(marks.b)
            if (marks.a >= 0 && marks.b >= 0 && xb - xa < arrowW * 2) {
                drawLine(markColor, androidx.compose.ui.geometry.Offset(xa, y0), androidx.compose.ui.geometry.Offset(xa, y0 + bh), 3f)
                drawLine(markColor, androidx.compose.ui.geometry.Offset(xb, y0), androidx.compose.ui.geometry.Offset(xb, y0 + bh), 3f)
            } else {
                fun arrow(xp: Float, dir: Int) = androidx.compose.ui.graphics.Path().apply {
                    moveTo(xp, y0); lineTo(xp + dir * arrowW, y0 + bh / 2); lineTo(xp, y0 + bh); close()
                }
                if (marks.a >= 0) drawPath(arrow(xa, 1), markColor)
                if (marks.b >= 0) drawPath(arrow(xb, -1), markColor)
            }
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
private fun SeekButton(g: G, modifier: Modifier, glyph: Dp, onTap: () -> Unit, onHold: (Long) -> Unit, onRelease: () -> Unit) {
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
    ) { Glyph(g, Modifier.size(glyph)) }
}

internal fun fmt(ms: Long): String {
    val t = ms / 1000
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, (t / 60) % 60, t % 60)
    else "%d:%02d".format(t / 60, t % 60)
}

/**
 * The art's gestures, also on the area around it. Tap: [onTap] (on the art, the
 * playlist, as in cabbiev2; around it, the popup). SlopBox, each switchable in
 * Theme Settings › SlopBox › Now Playing Gestures: swipe left / right for the
 * next / previous track, swipe up for the playlist, double-tap to play or
 * pause, long-press for the Context Menu.
 */
private fun Modifier.artGestures(
    controller: MediaController?, nav: Nav, ctx: Context, haptics: Haptics,
    onTap: () -> Unit = { nav.push(Screen.Playlist) },
): Modifier {
    val store = llm.slop.slopbox.SettingsStore.get(ctx)
    fun on(cfg: String) = store.int(cfg) != 0
    return this
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { onTap() },
                onDoubleTap = if (on(llm.slop.slopbox.SlopBoxSettings.DOUBLE_TAP)) ({
                    haptics.press()
                    controller?.let { if (it.isPlaying || it.playWhenReady) it.pause() else it.play() }
                }) else null,
                onLongPress = if (on(llm.slop.slopbox.SlopBoxSettings.LONG_PRESS)) ({
                    haptics.press()
                    nav.push(Screen.ContextMenu)
                }) else null,
            )
        }
        .pointerInput(Unit) {
            var dx = 0f
            var dy = 0f
            detectDragGestures(
                onDragStart = { dx = 0f; dy = 0f },
                onDragEnd = {
                    val min = 48.dp.toPx()
                    when {
                        kotlin.math.abs(dx) > kotlin.math.abs(dy) && kotlin.math.abs(dx) > min &&
                            on(llm.slop.slopbox.SlopBoxSettings.SWIPE_TRACKS) ->
                            // Swipe left (content moves left) = next, as in most players.
                            {
                                haptics.press()
                                // Rater: a swipe changes track even with Skip Length set (the buttons hop).
                                val rater = Rater.active(store)
                                controller?.let { c ->
                                    when {
                                        rater && dx < 0 -> Rater.next(ctx, c)
                                        rater -> Rater.previous(c)
                                        dx < 0 -> c.seekToNext()
                                        else -> c.seekToPrevious()
                                    }
                                }
                            }
                        dy < -min && kotlin.math.abs(dy) > kotlin.math.abs(dx) &&
                            on(llm.slop.slopbox.SlopBoxSettings.SWIPE_PLAYLIST) -> { haptics.press(); nav.push(Screen.Playlist) }
                    }
                },
            ) { ch, d -> ch.consume(); dx += d.x; dy += d.y }
        }
}

/** A strip where dragging up or down changes the media volume, the full range over the strip's height. */
@Composable
private fun EdgeVolume(ctx: Context, modifier: Modifier, onChange: () -> Unit) {
    val haptics = rememberHaptics()
    val am = remember { ctx.getSystemService(AudioManager::class.java) }
    val changed by rememberUpdatedState(onChange)
    Box(modifier.pointerInput(Unit) {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        var start = 0
        var moved = 0f
        detectVerticalDragGestures(
            onDragStart = { start = am.getStreamVolume(AudioManager.STREAM_MUSIC); moved = 0f; changed() },
        ) { ch, dy ->
            ch.consume()
            moved += dy
            val v = (start - moved / size.height * max).toInt().coerceIn(0, max)
            if (v != am.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                haptics.tick()
            }
            changed()
        }
    })
}
