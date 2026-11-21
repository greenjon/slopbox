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

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import llm.slop.slopbox.SettingsStore

/** Screens that already show what's playing, so they get no mini-player. */
private val SHOWS_TRACK = setOf(Screen.NowPlaying, Screen.AlbumArt, Screen.PluginLrc, Screen.PluginRater)

/** Where Rockbox's Status Bar setting puts the mini-player: 0 off, 1 top, 2 bottom. */
@Composable
fun statusBarPlace(): Int {
    val ctx = LocalContext.current
    val store = remember { SettingsStore.get(ctx) }
    var place by remember { mutableIntStateOf(store.int("statusbar")) }
    DisposableEffect(store) {
        val l: () -> Unit = { place = store.int("statusbar") }
        store.addListener(l)
        onDispose { store.removeListener(l) }
    }
    return place
}

/** The ▶ title-bar button, only needed when the Status Bar (mini-player) is off. */
@Composable
fun NowPlayingButton(nav: Nav) {
    if (statusBarPlace() == 0) BarButton(G.Play) { nav.toNowPlaying() }
}

private data class Mini(
    val item: MediaItem? = null,
    val title: String = "",
    val artist: String = "",
    val fraction: Float = 0f,
    val playing: Boolean = false,
    val ended: Boolean = false,
)

/**
 * A strip on the list screens while something is loaded, standing in for a
 * player's WPS button: progress line, art, title and artist, play/pause.
 * Tap or swipe toward the screen opens the While Playing Screen; play/pause
 * hold stops, as on the WPS.
 */
@Composable
fun MiniPlayer(controller: MediaController?, nav: Nav, atTop: Boolean) {
    if (controller == null || nav.top in SHOWS_TRACK) return
    val ctx = LocalContext.current
    var m by remember { mutableStateOf(Mini()) }
    LaunchedEffect(controller) {
        while (true) {
            val c = controller
            val dur = c.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            m = if (c.mediaItemCount == 0) Mini() else Mini(
                item = c.currentMediaItem,
                title = c.mediaMetadata.title?.toString().orEmpty(),
                artist = c.mediaMetadata.artist?.toString().orEmpty(),
                fraction = if (dur != null) (c.currentPosition.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                playing = c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_READY),
                ended = c.playbackState == Player.STATE_ENDED || c.playbackState == Player.STATE_IDLE,
            )
            delay(250)
        }
    }
    val item = m.item ?: return
    var tags by remember { mutableStateOf(Tags()) }
    LaunchedEffect(item.mediaId) { tags = TagReader.read(ctx, item, withArt = true) }
    val title = tags.title.ifEmpty { m.title }.ifEmpty { item.mediaId.substringAfterLast('/') }
    val artist = m.artist.ifEmpty { tags.artist }.ifEmpty { tags.albumArtist }

    val line = @Composable {
        Box(Modifier.fillMaxWidth().height(3.dp).background(Cabbie.TrackDark)) {
            Box(Modifier.fillMaxWidth(m.fraction).fillMaxHeight().background(Cabbie.gold))
        }
    }
    val sep = @Composable { Box(Modifier.fillMaxWidth().height(1.dp).background(Cabbie.Separator.copy(alpha = 0.5f))) }
    Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f))) {
        if (atTop) sep() else line()
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .pointerInput(atTop) {
                    detectTapGestures { nav.toNowPlaying() }
                }
                .pointerInput(atTop) {
                    // Swipe away from the bar's edge, into the screen.
                    var moved = 0f
                    detectVerticalDragGestures(
                        onDragStart = { moved = 0f },
                        onDragEnd = { if (if (atTop) moved > 40.dp.toPx() else moved < -40.dp.toPx()) nav.toNowPlaying() },
                    ) { ch, dy -> ch.consume(); moved += dy }
                }
                .padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val art = tags.art
            if (art != null) {
                Image(art, contentDescription = "Album art", Modifier.size(44.dp).clip(RoundedCornerShape(3.dp)),
                    contentScale = ContentScale.Crop)
            } else {
                Glyph(G.TangoNote, Modifier.size(36.dp))
            }
            Column(Modifier.weight(1f)) {
                BasicText(title, style = Cabbie.body.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (artist.isNotEmpty()) BasicText(artist, style = Cabbie.small.copy(color = Cabbie.Dim),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(
                Modifier.width(60.dp).fillMaxHeight().pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { if (controller.isPlaying || controller.playWhenReady) controller.pause() else controller.play() },
                        onLongPress = { controller.pause(); controller.seekTo(0) },
                    )
                },
                contentAlignment = Alignment.Center,
            ) {
                Glyph(when {
                    m.ended -> G.Stop
                    m.playing -> G.Pause
                    else -> G.Play
                }, Modifier.size(32.dp))
            }
        }
        if (atTop) line() else sep()
    }
}
