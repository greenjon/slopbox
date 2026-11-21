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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The colours Rockbox's Theme Settings give: foreground, background, line
 * selector start / end / text, list separator colour and height, and the
 * line selector type. Read from the settings store by [Cabbie.apply].
 */
data class Palette(
    val fg: Color = Color(0xFFCCCCCC),
    val bg: Color = Color.Black,
    val selStart: Color = Color(0xFFFDE89E),
    val selEnd: Color = Color(0xFFB58C00),
    val selText: Color = Color.Black,
    val separator: Color = Color(0xFF808080),
    /** Rockbox "list separator height": -1 auto, 0 off, else pixels. */
    val separatorPx: Int = -1,
    /** Rockbox "selector type": 0 pointer, 1 bar (inverse), 2 bar (solid colour), 3 bar (gradient). */
    val selectorType: Int = 3,
    /** SlopBox Flat Style: no gloss, solid controls. */
    val flat: Boolean = false,
    /** SlopBox Text Size, as a scale. */
    val textScale: Float = 1f,
    /** SlopBox Navigation at Bottom. */
    val bottomNav: Boolean = false,
) {
    /** Text and background far enough apart to read (WCAG's 3:1, for large text). */
    val readable get() = contrast(fg, bg) >= 3f

    companion object {
        val CABBIEV2 = Palette()

        /** WCAG contrast ratio, 1 to 21. */
        fun contrast(a: Color, b: Color): Float {
            val la = a.luminance(); val lb = b.luminance()
            return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
        }

        fun from(store: llm.slop.slopbox.SettingsStore): Palette {
            fun rgb(cfg: String, d: Color) = store.catalog.byCfg[cfg]?.let { Color(0xFF000000.toInt() or store.int(it)) } ?: d
            val d = CABBIEV2
            return Palette(
                fg = rgb("foreground color", d.fg),
                bg = rgb("background color", d.bg),
                selStart = rgb("line selector start color", d.selStart),
                selEnd = rgb("line selector end color", d.selEnd),
                selText = rgb("line selector text color", d.selText),
                separator = rgb("list separator color", d.separator),
                separatorPx = store.catalog.byCfg["list separator height"]?.let { store.int(it) } ?: d.separatorPx,
                selectorType = store.catalog.byCfg["selector type"]?.let { store.int(it) } ?: d.selectorType,
                flat = store.int(llm.slop.slopbox.SlopBoxSettings.FLAT) != 0,
                textScale = llm.slop.slopbox.SlopBoxSettings.TEXT_SCALES
                    .getOrElse(store.int(llm.slop.slopbox.SlopBoxSettings.TEXT_SIZE)) { 1f },
                bottomNav = store.int(llm.slop.slopbox.SlopBoxSettings.BOTTOM_NAV) != 0,
            )
        }
    }
}

/**
 * Colours and shapes after Rockbox's cabbiev2 theme (wps/WPSLIST, wps/cabbiev2/,
 * backdrops/cabbiev2.*): grey-to-black backdrop, light grey text, gold
 * gradient line selector, glossy gold controls. Redrawn, not copied bitmaps,
 * so everything scales. The colours follow Rockbox's Theme Settings
 * ([Palette]); they are snapshot state, so screens redraw when they change.
 */
object Cabbie {
    /** The colours the settings give. */
    var chosen by mutableStateOf(Palette.CABBIEV2)
        private set

    /**
     * Set while the colour settings are on screen: if the chosen colours are
     * hard to read, those screens draw in cabbiev2's, so a bad choice can
     * always be undone.
     */
    var safeMode by mutableStateOf(false)

    /** Set by the While Playing Screen with Album Art Colours on: the art's colour, or null. */
    var accent by mutableStateOf<Color?>(null)

    /** The colours to draw with. */
    val palette: Palette get() = chosen.let { if (safeMode && !it.readable) Palette.CABBIEV2 else it }.let { p ->
        val a = accent ?: return@let p
        p.copy(
            selStart = lerp(a, Color.White, 0.12f), selEnd = lerp(a, Color.Black, 0.35f),
            selText = if (a.luminance() > 0.35f) Color.Black else Color.White,
        )
    }

    /** Re-read the theme colours from the settings. */
    fun apply(store: llm.slop.slopbox.SettingsStore) {
        val p = Palette.from(store)
        if (p != chosen) chosen = p
    }

    val Text get() = palette.fg
    // cabbiev2's dim grey (8C8C8C) is 69 % of the way from black to CCCCCC.
    val Dim get() = lerp(palette.bg, palette.fg, 0.69f)
    val SelTop get() = palette.selStart
    val SelBottom get() = palette.selEnd
    val SelText get() = palette.selText
    val Separator get() = palette.separator
    // The WPS header text colour (%Vf(0F0B00)).
    val HeaderText = Color(0xFF0F0B00)

    // The glossy controls follow the line selector; cabbiev2's own golds when it's the default.
    private val cabbieGold get() = palette.selStart == Palette.CABBIEV2.selStart && palette.selEnd == Palette.CABBIEV2.selEnd
    val GoldLight get() = if (cabbieGold) Color(0xFFFFEF8A) else lerp(palette.selStart, Color.White, 0.15f)
    val Gold get() = if (cabbieGold) Color(0xFFF0C400) else lerp(palette.selStart, palette.selEnd, 0.5f)
    val GoldDark get() = if (cabbieGold) Color(0xFFB08600) else palette.selEnd
    // cabbiev2's greys (505050, 2E2E2E, 6A6A6A, the popup's 4A4A4A) as steps from background to foreground.
    val Track get() = lerp(palette.bg, palette.fg, 0.39f)
    val TrackDark get() = lerp(palette.bg, palette.fg, 0.225f)
    /** Panels drawn over the backdrop: the mini-player, the WPS popup. */
    val Panel get() = lerp(palette.bg, palette.fg, 0.36f)
    /** Whether the background is light, for the system bar icons. */
    val isLight get() = palette.bg.luminance() > 0.5f

    // Tango palette, for the list icons (cabbiev2 uses the Tango iconset).
    val TangoBlueLight = Color(0xFF9DBEE6)
    val TangoBlue = Color(0xFF3465A4)
    val TangoGreenLight = Color(0xFFB5EE6A)
    val TangoGreen = Color(0xFF4E9A06)

    // cabbiev2's backdrop: 5C5C5C at the top to the background colour (black) at the bottom.
    val backdrop get() = Brush.verticalGradient(
        0f to lerp(palette.bg, Color.White, 0.36f),
        0.5f to lerp(palette.bg, Color.White, 0.14f),
        1f to palette.bg,
    )
    /** The line selector for [Palette.selectorType]; null for Pointer (no bar). */
    val selector: Brush? get() = when (palette.selectorType) {
        0 -> null
        1 -> SolidColor(palette.fg)
        2 -> SolidColor(palette.selStart)
        else -> Brush.verticalGradient(listOf(SelTop, SelBottom))
    }
    /** Text on the selector bar: Inverse uses the background colour. */
    val selectorText get() = if (palette.selectorType == 1) palette.bg else palette.selText
    val gold: Brush get() = if (palette.flat) SolidColor(Gold) else Brush.verticalGradient(listOf(GoldLight, Gold, GoldDark))
    val track: Brush get() = if (palette.flat) SolidColor(Track) else Brush.verticalGradient(listOf(lerp(palette.bg, palette.fg, 0.52f), Track, TrackDark))

    val font = FontFamily.SansSerif
    /** SlopBox Text Size. */
    val scale get() = palette.textScale
    val body get() = TextStyle(fontFamily = font, fontSize = 18.sp * scale, color = Text)
    val small get() = TextStyle(fontFamily = font, fontSize = 15.sp * scale, color = Text)
    val header get() = TextStyle(fontFamily = font, fontSize = 17.sp * scale, fontWeight = FontWeight.Bold, color = HeaderText)
}

/** Full-screen cabbiev2 backdrop. */
@Composable
fun Backdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(Cabbie.backdrop), content = content)
}

/**
 * A glossy pill (the WPS header bar, the progress bar): gradient body, a darker
 * rim and a soft highlight across the top half.
 */
fun Modifier.glossyPill(body: Brush, rim: Color): Modifier = drawBehind {
    val r = CornerRadius(size.height / 2, size.height / 2)
    drawRoundRect(body, cornerRadius = r)
    drawRoundRect(rim, cornerRadius = r, style = Stroke(1.dp.toPx()))
    if (Cabbie.palette.flat) return@drawBehind
    val inset = 2.dp.toPx()
    val hh = (size.height / 2 - inset).coerceAtLeast(0f)
    drawRoundRect(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.08f)),
            startY = inset, endY = inset + hh,
        ),
        topLeft = Offset(inset * 2, inset),
        size = Size(size.width - inset * 4, hh),
        cornerRadius = CornerRadius(hh / 2, hh / 2),
    )
}
