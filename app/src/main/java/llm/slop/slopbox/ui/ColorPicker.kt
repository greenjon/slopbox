/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/gui/color_picker.c - Copyright (C) Jonathan Gordon
 *   apps/menus/theme_menu.c - Copyright (C) 2007 Jonathan Gordon
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

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import llm.slop.slopbox.SettingsStore

/**
 * Rockbox theme_menu.c colors[]: the colour settings, in its COLOR_* order,
 * with the title each one's picker shows.
 */
object ThemeColours {
    const val FG = 0
    const val BG = 1
    const val SEP = 5
    val CFG = listOf("foreground color", "background color", "line selector start color",
        "line selector end color", "line selector text color", "list separator color")
    val LANG = listOf("LANG_FOREGROUND_COLOR", "LANG_BACKGROUND_COLOR", "LANG_SELECTOR_START_COLOR",
        "LANG_SELECTOR_END_COLOR", "LANG_SELECTOR_TEXT_COLOR", "LANG_LIST_SEPARATOR_COLOR")

    /** The menu item's parameter ("?1", or none for the foreground) as a COLOR_* index. */
    fun index(param: String?): Int = param?.removePrefix("?")?.toIntOrNull()?.takeIf { it in CFG.indices } ?: FG

    fun get(store: SettingsStore, c: Int): Int = store.int(CFG[c]) and 0xFFFFFF

    /**
     * Rockbox reset_color(): every theme colour back to its default. (Rockbox
     * uses the LCD defaults; SlopBox's defaults are cabbiev2's colours.)
     */
    fun reset(store: SettingsStore) {
        for (cfg in CFG) store.catalog.byCfg[cfg]?.let { store.reset(it) }
    }

    /** Rockbox set_color_func(): the colour this one may not take (fg and bg must differ), or null. */
    fun banned(store: SettingsStore, c: Int): Int? = when (c) {
        BG -> get(store, FG)
        FG, SEP -> get(store, BG)
        else -> null
    }
}

/**
 * Rockbox's colour picker (gui/color_picker.c set_color()): red, green and
 * blue sliders over the colour, a preview, OK and Cancel. Touch: drag or tap
 * a slider, or step it with −/+ (repeat while held). Components run 0–255
 * rather than the LCD's 5/6/5 bits. OK refuses the [ThemeColours.banned]
 * colour with "Invalid colour", as Rockbox does.
 */
@Composable
fun ColorPicker(store: SettingsStore, which: Int, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val cat = store.catalog
    var rgb by remember(which) { mutableIntStateOf(ThemeColours.get(store, which)) }
    fun comp(i: Int) = (rgb shr (16 - 8 * i)) and 0xFF
    fun setComp(i: Int, v: Int) {
        val shift = 16 - 8 * i
        rgb = (rgb and (0xFF shl shift).inv()) or (v.coerceIn(0, 255) shl shift)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Preview(which, rgb)
        BasicText(
            // LANG_COLOR_RGB_VALUE: "RGB: %02X%02X%02X"
            cat.str("LANG_COLOR_RGB_VALUE").replace("%02X%02X%02X", "%06X".format(rgb)),
            Modifier.fillMaxWidth(),
            style = Cabbie.body.copy(textAlign = TextAlign.Center, fontFamily = FontFamily.Monospace),
        )
        // LANG_COLOR_RGB_LABELS: "RGB", one letter per slider.
        val letters = cat.str("LANG_COLOR_RGB_LABELS").padEnd(3, ' ')
        for (i in 0..2) {
            val c = comp(i)
            val shift = 16 - 8 * i
            val lo = Color(0xFF000000.toInt() or (rgb and (0xFF shl shift).inv()))
            val hi = Color(0xFF000000.toInt() or rgb or (0xFF shl shift))
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(letters[i].toString(), Modifier.width(20.dp), style = Cabbie.body.copy(fontWeight = FontWeight.Bold))
                RepeatButton("−", Modifier.width(44.dp)) { setComp(i, comp(i) - 1) }
                Slider(c / 255f, lo, hi, Modifier.weight(1f).fillMaxHeight()) { setComp(i, (it * 255 + 0.5f).toInt()) }
                RepeatButton("+", Modifier.width(44.dp)) { setComp(i, comp(i) + 1) }
                BasicText("%3d".format(c), Modifier.width(40.dp),
                    style = Cabbie.small.copy(textAlign = TextAlign.End, fontFamily = FontFamily.Monospace))
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PickerButton("Cancel", Modifier.weight(1f)) { onDone() }
            PickerButton("OK", Modifier.weight(1f)) {
                if (ThemeColours.banned(store, which) == rgb) {
                    Toast.makeText(ctx, cat.str("LANG_COLOR_UNACCEPTABLE"), Toast.LENGTH_SHORT).show()
                } else {
                    cat.byCfg[ThemeColours.CFG[which]]?.let { if (store.int(it) != rgb) store.set(it, rgb) }
                    onDone()
                }
            }
        }
    }
}

/** A list as it would look with the colour being picked: a plain line, a selected line, separators. */
@Composable
private fun Preview(which: Int, rgb: Int) {
    val now = Cabbie.palette
    val c = Color(0xFF000000.toInt() or rgb)
    val p = when (which) {
        0 -> now.copy(fg = c)
        1 -> now.copy(bg = c)
        2 -> now.copy(selStart = c)
        3 -> now.copy(selEnd = c)
        4 -> now.copy(selText = c)
        else -> now.copy(separator = c)
    }
    val bar: Brush? = when (p.selectorType) {
        0 -> null
        1 -> SolidColor(p.fg)
        2 -> SolidColor(p.selStart)
        else -> Brush.verticalGradient(listOf(p.selStart, p.selEnd))
    }
    val selText = if (p.selectorType == 1) p.bg else p.selText
    Column(
        Modifier.fillMaxWidth().background(p.bg, RoundedCornerShape(6.dp))
            .border(1.dp, Cabbie.Separator, RoundedCornerShape(6.dp)).padding(vertical = 6.dp),
    ) {
        @Composable
        fun line(text: String, selected: Boolean) {
            Box(
                Modifier.fillMaxWidth().height(44.dp)
                    .then(if (selected && bar != null) Modifier.background(bar) else Modifier)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicText((if (selected && bar == null) "▶ " else "") + text,
                    style = Cabbie.body.copy(color = if (selected && bar != null) selText else p.fg))
            }
            Box(Modifier.fillMaxWidth().height(if (which == ThemeColours.SEP) 2.dp else 1.dp).background(p.separator))
        }
        line("Files", false)
        line("Database", true)
        line("Playlists", false)
    }
}

/** A drag-or-tap bar from [lo] to [hi] with a thumb at [fraction]. */
@Composable
private fun Slider(fraction: Float, lo: Color, hi: Color, modifier: Modifier, onChange: (Float) -> Unit) {
    val change by rememberUpdatedState(onChange)
    val rim = Cabbie.Text
    Canvas(modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            fun f(x: Float) = (x / size.width).coerceIn(0f, 1f)
            change(f(down.position.x))
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) break
                ch.consume()
                change(f(ch.position.x))
            }
        }
    }) {
        val h = size.height * 0.45f
        val top = (size.height - h) / 2
        val r = CornerRadius(h / 2, h / 2)
        drawRoundRect(Brush.horizontalGradient(listOf(lo, hi)), Offset(0f, top), Size(size.width, h), r)
        drawRoundRect(rim.copy(alpha = 0.6f), Offset(0f, top), Size(size.width, h), r, style = Stroke(1.dp.toPx()))
        val x = fraction.coerceIn(0f, 1f) * size.width
        val tw = 6.dp.toPx()
        drawRoundRect(Color.Black, Offset(x - tw / 2 - 1.dp.toPx(), 2.dp.toPx()),
            Size(tw + 2.dp.toPx(), size.height - 4.dp.toPx()), CornerRadius(3.dp.toPx()))
        drawRoundRect(Color.White, Offset(x - tw / 2, 3.dp.toPx()), Size(tw, size.height - 6.dp.toPx()), CornerRadius(3.dp.toPx()))
    }
}

@Composable
private fun PickerButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxHeight().glossyPill(Cabbie.gold, Cabbie.GoldDark).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        BasicText(label, style = Cabbie.body.copy(color = Cabbie.HeaderText, fontWeight = FontWeight.Bold))
    }
}
