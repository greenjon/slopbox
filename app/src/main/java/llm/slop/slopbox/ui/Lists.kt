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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The bar above a list: back button, title, optional actions on the right. */
@Composable
fun TitleBar(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    Column {
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
            BasicText(
                title,
                Modifier.weight(1f),
                style = Cabbie.body.copy(color = Cabbie.Text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            actions()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Cabbie.Separator))
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
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val bar = (selected || pressed) && enabled
    val color = when {
        !enabled || (dim && !bar) -> Cabbie.Dim
        bar -> Cabbie.SelText
        else -> Cabbie.Text
    }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (bar) Modifier.background(Cabbie.selector) else Modifier)
                .combinedClickable(
                    interactionSource = source,
                    indication = null,
                    enabled = enabled,
                    onLongClick = onLongClick,
                    onClick = onClick,
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) Glyph(icon, Modifier.size(32.dp))
            Column(Modifier.weight(1f)) {
                BasicText(text, style = Cabbie.body.copy(color = color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub != null) {
                    BasicText(
                        sub,
                        style = Cabbie.small.copy(color = if (bar) Cabbie.SelText else Cabbie.Dim),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) BasicText(trailing, style = Cabbie.body.copy(color = color), maxLines = 1)
        }
        // "list separator height..+&touchscreen: auto"
        Box(Modifier.fillMaxWidth().height(1.dp).background(Cabbie.Separator.copy(alpha = 0.5f)))
    }
}

/** Centered grey message for empty or loading lists. */
@Composable
fun ListNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        BasicText(text, style = Cabbie.small.copy(color = Cabbie.Dim))
    }
}
