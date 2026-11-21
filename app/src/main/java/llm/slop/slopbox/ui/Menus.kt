/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/gui/quickscreen.c - Copyright (C) 2008 by Jonathan Gordon
 *   apps/playlist_viewer.c - Copyright (C) 2003 Hardeep Sidhu
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.width
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.pointerInput
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
    // SlopBox: swipe a line left to remove it (with Undo); drag its ≡ handle to move it.
    var undo by remember { mutableStateOf<Pair<Int, MediaItem>?>(null) }
    LaunchedEffect(undo) { if (undo != null) { delay(5000); undo = null } }
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragDy by remember { mutableStateOf(0f) }
    var rowPx by remember { mutableIntStateOf(1) }
    val haptics = rememberHaptics()
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        TitleBar(if (moving != null) store.catalog.str("LANG_MOVE") else "Playlist (${items.size})",
            onBack = { if (moving != null) moving = null else nav.pop() })
        if (items.isEmpty()) ListNote("The playlist is empty.")
        RockList(Modifier.fillMaxSize(), state = listState) {
            itemsIndexed(items, key = { i, it -> "${it.mediaId}#$i#$version" }) { i, item ->
                var dx by remember { mutableStateOf(0f) }
                val lifted = dragFrom == i
                Box(
                    Modifier
                        .onSizeChanged { rowPx = maxOf(it.height, 1) }
                        .zIndex(if (lifted) 1f else 0f)
                        .graphicsLayer {
                            translationX = dx
                            translationY = if (lifted) dragDy else 0f
                            alpha = if (dx < 0) (1f + dx / size.width).coerceIn(0.3f, 1f) else 1f
                        }
                        .pointerInput(i, version) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (dx < -size.width * 0.35f && controller != null && moving == null) {
                                        haptics.press()
                                        undo = i to item
                                        controller.removeMediaItem(i)
                                        version++
                                    }
                                    dx = 0f
                                },
                                onDragCancel = { dx = 0f },
                            ) { ch, d -> ch.consume(); dx = (dx + d).coerceAtMost(0f) }
                        },
                ) {
                    RockRow(
                        trackLabel(ctx, item, i),
                        icon = if (!showIcons) null else if (i == current) G.Cursor else G.TangoNote,
                        selected = lifted || (if (moving != null) i == moving else i == current),
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
                    // The move handle.
                    Box(
                        Modifier.align(Alignment.CenterEnd).width(52.dp).fillMaxHeight()
                            .pointerInput(i, version) {
                                detectDragGestures(
                                    onDragStart = { dragFrom = i; dragDy = 0f; haptics.press() },
                                    onDragEnd = {
                                        val from = dragFrom
                                        if (from != null && controller != null) {
                                            val to = (from + (dragDy / rowPx).roundToInt()).coerceIn(0, items.lastIndex)
                                            if (to != from) { controller.moveMediaItem(from, to); version++ }
                                        }
                                        dragFrom = null; dragDy = 0f
                                    },
                                    onDragCancel = { dragFrom = null; dragDy = 0f },
                                ) { ch, d ->
                                    ch.consume()
                                    val before = (dragDy / rowPx).roundToInt()
                                    dragDy += d.y
                                    if ((dragDy / rowPx).roundToInt() != before) haptics.tick()
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText("≡", style = Cabbie.body.copy(fontSize = 26.sp,
                            color = if (lifted || i == current) Cabbie.selectorText else Cabbie.Dim))
                    }
                }
            }
        }
    }
    undo?.let { (at, item) ->
        Row(
            Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth().height(52.dp)
                .background(Cabbie.Panel, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText("Removed ${name(item)}", Modifier.weight(1f), style = Cabbie.body, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            BasicText("UNDO", Modifier.clickable {
                controller?.addMediaItem(at.coerceAtMost(controller.mediaItemCount), item)
                undo = null
                version++
            }.padding(8.dp), style = Cabbie.body.copy(color = Cabbie.Gold, fontWeight = FontWeight.Bold))
        }
    }
    }
}

private fun name(item: MediaItem?): String =
    item?.mediaMetadata?.title?.toString()?.takeIf { it.isNotEmpty() } ?: item?.mediaId ?: ""

// ---- Quick Screen ----------------------------------------------------------

/** Rockbox's default Quick Screen: shuffle on the left, repeat on the right; tap to change. */
/**
 * Rockbox's quick screen (apps/gui/quickscreen.c): the settings chosen as
 * "qs top", "qs left", "qs right" and "qs bottom" (default: Shuffle left,
 * Repeat right). With its touchscreen rule: a tap in the top, left, right or
 * bottom third changes that item (top and right to the next value, left and
 * bottom to the previous); a tap anywhere else leaves.
 */
@Composable
fun QuickScreen(@Suppress("UNUSED_PARAMETER") controller: MediaController?, nav: Nav) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val store = remember { llm.slop.slopbox.SettingsStore.get(ctx) }
    var rev by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(store) {
        val l: () -> Unit = { rev++ }
        store.addListener(l)
        onDispose { store.removeListener(l) }
    }
    fun item(cfg: String, default: String): llm.slop.slopbox.RbSetting? {
        val qs = store.catalog.byCfg[cfg] ?: return null
        val name = store.text(qs).trim().ifEmpty { default }
        if (name == "-") return null
        return store.catalog.byCfg[name]?.takeIf { it.quickscreenable }
    }
    @Suppress("UNUSED_EXPRESSION") rev
    val top = item("qs top", "-")
    val left = item("qs left", "shuffle")
    val right = item("qs right", "repeat")
    val bottom = item("qs bottom", "-")

    // Rockbox option_select_next_val(): step through the values, wrapping
    // unless the setting says F_NO_WRAP.
    val haptics = rememberHaptics()
    fun step(st: llm.slop.slopbox.RbSetting?, previous: Boolean) {
        st ?: return
        val labels = st.labels
        val i = labels.indexOfFirst { it.first == store.int(st) }.coerceAtLeast(0)
        val noWrap = st.flags and 0x1000L != 0L
        val j = if (previous) (if (i == 0) (if (noWrap) 0 else labels.lastIndex) else i - 1)
            else (if (i == labels.lastIndex) (if (noWrap) i else 0) else i + 1)
        if (labels[j].first != store.int(st)) haptics.tick()
        store.set(st, labels[j].first)
    }

    Column(Modifier.fillMaxSize()) {
        TitleBar("Quick Screen", onBack = { nav.pop() })
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()
            .pointerInput(top, left, right, bottom) {
                detectTapGestures { o ->
                    val w = size.width; val h = size.height
                    val col = when { o.x < w / 3f -> 0; o.x > 2 * w / 3f -> 2; else -> 1 }
                    val row = when { o.y < h / 3f -> 0; o.y > 2 * h / 3f -> 2; else -> 1 }
                    when {
                        row == 0 && col == 1 -> step(top, false)
                        row == 2 && col == 1 -> step(bottom, true)
                        row == 1 && col == 0 -> step(left, true)
                        row == 1 && col == 2 -> step(right, false)
                        else -> nav.pop()
                    }
                }
            }) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { QsItem(top, top?.let { it.label(store.int(it)).trim() }) }
                Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { QsItem(left, left?.let { it.label(store.int(it)).trim() }) }
                    Box(Modifier.weight(1f))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { QsItem(right, right?.let { it.label(store.int(it)).trim() }) }
                }
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { QsItem(bottom, bottom?.let { it.label(store.int(it)).trim() }) }
            }
        }
    }
}

/** One quick screen item: the setting's name and its value. */
@Composable
private fun QsItem(st: llm.slop.slopbox.RbSetting?, value: String?) {
    st ?: return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(st.title, style = Cabbie.body.copy(textAlign = TextAlign.Center))
        BasicText(value ?: "", style = Cabbie.body.copy(color = Cabbie.SelTop, textAlign = TextAlign.Center))
    }
}
