/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The bar above a list: back button, title, optional actions on the right. */
@Composable
fun TitleBar(
    title: String,
    onBack: (() -> Unit)?,
    /** Drawn instead of [title] when given (e.g. the Files breadcrumbs); fills the middle. */
    titleContent: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    // SlopBox Navigation at Bottom: hand the bar to the app, which draws it at the bottom.
    if (Cabbie.palette.bottomNav) {
        val token = remember { Any() }
        val bar by androidx.compose.runtime.rememberUpdatedState<@Composable () -> Unit>({
            Column {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Cabbie.Separator))
                TitleRow(title, onBack, titleContent, actions)
            }
        })
        androidx.compose.runtime.SideEffect { BottomNav.show(token) { bar() } }
        androidx.compose.runtime.DisposableEffect(token) { onDispose { BottomNav.hide(token) } }
        return
    }
    Column {
        TitleRow(title, onBack, titleContent, actions)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Cabbie.Separator))
    }
}

/** Where a screen's title bar goes with Navigation at Bottom; [App] draws [bar]. */
object BottomNav {
    var bar by androidx.compose.runtime.mutableStateOf<(@Composable () -> Unit)?>(null)
        private set
    private var owner: Any? = null
    fun show(token: Any, content: @Composable () -> Unit) {
        if (owner === token && bar != null) return
        owner = token; bar = content
    }
    fun hide(token: Any) { if (owner === token) { owner = null; bar = null } }
}

@Composable
private fun TitleRow(
    title: String,
    onBack: (() -> Unit)?,
    titleContent: (@Composable RowScope.() -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Box(Modifier.size(48.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                    Glyph(G.Back, Modifier.size(22.dp))
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            if (titleContent != null) titleContent() else BasicText(
                title,
                Modifier.weight(1f),
                style = Cabbie.body.copy(color = Cabbie.Text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            actions()
        }
}

/** A square touch target holding a glyph, for title bar actions. */
@Composable
fun BarButton(g: G, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Glyph(g, Modifier.size(26.dp))
    }
}

/**
 * One list line: icon, text, optional second line. Selected (or pressed) lines
 * get the cabbiev2 gold gradient bar with black text.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RockRow(
    text: String,
    icon: G? = null,
    sub: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    /** Greyed but still clickable (e.g. a setting SlopBox doesn't act on yet). */
    dim: Boolean = false,
    /** Shown at the right, e.g. a value. */
    trailing: String? = null,
    onLongClick: (() -> Unit)? = null,
    /** Show the icon even with Show Icons off (Rockbox's folder picker does). */
    forceIcon: Boolean = false,
    onClick: () -> Unit,
) {
    // Rockbox Theme Settings > Show Icons: list icons on or off everywhere.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val showIcons = forceIcon || llm.slop.slopbox.SettingsStore.get(ctx).int("show icons") != 0
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val haptics = rememberHaptics()
    val hit = (selected || pressed) && enabled
    // Rockbox Line Selector Type: a bar (inverse, solid or gradient), or Pointer (a cursor, no bar).
    val selBrush = Cabbie.selector
    val bar = hit && selBrush != null
    val pointer = hit && selBrush == null
    val color = when {
        !enabled || (dim && !bar) -> Cabbie.Dim
        bar -> Cabbie.selectorText
        else -> Cabbie.Text
    }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp * Cabbie.scale.coerceAtLeast(1f))
                .then(if (bar) Modifier.background(selBrush!!) else Modifier)
                .combinedClickable(
                    interactionSource = source,
                    indication = null,
                    enabled = enabled,
                    onLongClick = onLongClick?.let { l -> { haptics.press(); l() } },
                    onClick = onClick,
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selBrush == null) Box(Modifier.size(width = 18.dp, height = 24.dp)) {
                if (pointer) Glyph(G.Cursor, Modifier.matchParentSize())
            }
            if (icon != null && showIcons) Glyph(icon, Modifier.size(32.dp * Cabbie.scale.coerceAtLeast(1f)))
            Column(Modifier.weight(1f)) {
                BasicText(text, style = Cabbie.body.copy(color = color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub != null) {
                    BasicText(
                        sub,
                        style = Cabbie.small.copy(color = if (bar) Cabbie.selectorText else Cabbie.Dim),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) BasicText(trailing, style = Cabbie.body.copy(color = color), maxLines = 1)
        }
        // Rockbox Line Separator: Auto ("list separator height..+&touchscreen: auto") is 1 dp, Off none.
        val px = Cabbie.palette.separatorPx
        if (px != 0) {
            val h = if (px < 0) 1.dp else with(androidx.compose.ui.platform.LocalDensity.current) { px.toDp() }
            Box(Modifier.fillMaxWidth().height(h).background(Cabbie.Separator.copy(alpha = if (px < 0) 0.5f else 1f)))
        }
    }
}

/** Centered grey message for empty or loading lists. */
@Composable
fun ListNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        BasicText(text, style = Cabbie.small.copy(color = Cabbie.Dim))
    }
}
