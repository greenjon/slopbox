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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import llm.slop.slopbox.SettingsStore
import kotlin.math.roundToInt

/**
 * A list with Rockbox's scroll bar (Theme Settings › Status-/Scrollbar ›
 * Scroll Bar: Off, Left or Right; Scroll Bar Width). On a touchscreen the
 * thumb can be dragged; while it is, a bubble shows [label] for the top line
 * (a letter, say), or the line's number when [label] is null or returns null.
 */
@Composable
fun RockList(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    label: ((Int) -> String?)? = null,
    content: LazyListScope.() -> Unit,
) {
    val ctx = LocalContext.current
    val store = remember { SettingsStore.get(ctx) }
    var side by remember { mutableIntStateOf(store.int("scrollbar")) }
    var width by remember { mutableIntStateOf(store.int("scrollbar width")) }
    DisposableEffect(store) {
        val l: () -> Unit = { side = store.int("scrollbar"); width = store.int("scrollbar width") }
        store.addListener(l)
        onDispose { store.removeListener(l) }
    }
    val labelFor by rememberUpdatedState(label)
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    // Rockbox's width is in pixels of a small screen; here it's dp, drawn at least 4 dp.
    val barW = maxOf(width, 4).dp
    val grabW = maxOf(barW, 32.dp)
    val left = side == 1

    Box(
        modifier.then(if (side == 0) Modifier else Modifier.pointerInput(side, width) {
            // Watch touches first (Initial pass): a press on the thumb's strip takes the
            // gesture; anything else goes on to the list.
            while (true) {
                awaitPointerEventScope {
                    val down = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.pressed && !it.previousPressed }
                        ?: return@awaitPointerEventScope
                    val g = Geometry.of(state, size.height.toFloat(), 40.dp.toPx()) ?: return@awaitPointerEventScope
                    val x = down.position.x
                    val inStrip = if (left) x < grabW.toPx() else x > size.width - grabW.toPx()
                    val slop = 16.dp.toPx()
                    if (!inStrip || down.position.y < g.top - slop || down.position.y > g.top + g.height + slop) return@awaitPointerEventScope
                    down.consume()
                    dragging = true
                    val grabOffset = down.position.y - g.top
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) { ch.consume(); break }
                        ch.consume()
                        val gg = Geometry.of(state, size.height.toFloat(), 40.dp.toPx()) ?: break
                        val room = (size.height - gg.height).coerceAtLeast(1f)
                        val f = ((ch.position.y - grabOffset) / room).coerceIn(0f, 1f)
                        val target = f * gg.scrollRange
                        val item = (target / gg.avg).toInt().coerceIn(0, gg.total - 1)
                        val off = (target - item * gg.avg).roundToInt()
                        scope.launch { state.scrollToItem(item, off) }
                    }
                    dragging = false
                }
            }
        }),
    ) {
        LazyColumn(Modifier.fillMaxSize(), state = state, content = content)
        if (side != 0) {
            val thumb = Cabbie.gold
            val track = Cabbie.Separator.copy(alpha = 0.25f)
            Canvas(Modifier.fillMaxHeight().width(barW).align(if (left) Alignment.TopStart else Alignment.TopEnd)) {
                val g = Geometry.of(state, size.height, 40.dp.toPx()) ?: return@Canvas
                val r = CornerRadius(size.width / 2, size.width / 2)
                drawRoundRect(track, cornerRadius = r)
                drawRoundRect(thumb, Offset(0f, g.top), Size(size.width, g.height), r)
            }
            if (dragging) {
                val first = state.firstVisibleItemIndex
                val text = labelFor?.invoke(first) ?: "${first + 1}"
                // A tick each time the letter changes (not for plain line numbers).
                val haptics = rememberHaptics()
                val lettered = labelFor?.invoke(first) != null
                androidx.compose.runtime.LaunchedEffect(text) { if (lettered) haptics.tick() }
                Bubble(text, state, left, grabW)
            }
        }
    }
}

/** The bubble next to the thumb while it's dragged. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.Bubble(text: String, state: LazyListState, left: Boolean, gap: androidx.compose.ui.unit.Dp) {
    val density = LocalDensity.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = with(density) { maxHeight.toPx() }
        val g = Geometry.of(state, h, with(density) { 40.dp.toPx() }) ?: return@BoxWithConstraints
        val size = 72.dp
        val y = (g.top + g.height / 2 - with(density) { size.toPx() } / 2)
            .coerceIn(0f, (h - with(density) { size.toPx() }).coerceAtLeast(0f))
        val sel = Cabbie.palette
        Box(
            Modifier
                .align(if (left) Alignment.TopStart else Alignment.TopEnd)
                .offset { IntOffset(0, y.roundToInt()) }
                .padding(start = if (left) gap + 8.dp else 0.dp, end = if (left) 0.dp else gap + 8.dp)
                .size(size)
                .drawBehind {
                    drawRoundRect(Cabbie.gold, cornerRadius = CornerRadius(12.dp.toPx()))
                },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text,
                style = Cabbie.body.copy(
                    color = sel.selText, fontSize = if (text.length <= 2) 34.sp else if (text.length <= 4) 22.sp else 15.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                ),
                maxLines = 1,
            )
        }
    }
}

/** Where the thumb sits, estimated from the visible lines' average height. */
private class Geometry(val top: Float, val height: Float, val avg: Float, val total: Int, val scrollRange: Float) {
    companion object {
        fun of(state: LazyListState, viewport: Float, minThumb: Float): Geometry? {
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo
            val total = info.totalItemsCount
            if (visible.isEmpty() || total == 0) return null
            val avg = visible.sumOf { it.size }.toFloat() / visible.size
            val content = avg * total
            val view = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
            if (content <= view + 1f) return null
            val scrolled = state.firstVisibleItemIndex * avg + state.firstVisibleItemScrollOffset
            val scrollRange = content - view
            val height = (viewport * view / content).coerceAtLeast(minThumb).coerceAtMost(viewport)
            val top = (scrolled / scrollRange).coerceIn(0f, 1f) * (viewport - height)
            return Geometry(top, height, avg, total, scrollRange)
        }
    }
}

/** The bubble's letter for a name: its first letter or digit, upper case; digits show as "#". */
fun initial(name: String): String? {
    val c = name.firstOrNull { it.isLetterOrDigit() } ?: return null
    return if (c.isDigit()) "#" else c.uppercaseChar().toString()
}
