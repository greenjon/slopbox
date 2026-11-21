/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/menus/eq_menu.c - Copyright (C) 2006 Dan Everton
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import llm.slop.slopbox.EqBand
import llm.slop.slopbox.SettingsStore

/**
 * Rockbox's Graphical EQ (eq_menu_graphical()): every band as a line --
 * "PK3:  0.0dB  1000Hz  1.0 Q" -- with a slider for the value being edited
 * and "Edit mode: Gain (dB)" on top. Same ranges and steps as Rockbox.
 * Touch: tap a band to select it; tap its type, gain, cutoff or Q to edit
 * that (Rockbox steps the mode with OK); − and + step, −− and ++ big-step,
 * and repeat while held.
 */
@Composable
fun GraphicalEq(store: SettingsStore) {
    fun str(id: String) = store.catalog.str(id)
    var band by remember { mutableIntStateOf(0) }
    var mode by remember { mutableIntStateOf(GAIN) }
    var rev by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") rev
    val bands = (0 until SettingsStore.EQ_BANDS).map { store.eqBand(it) }

    fun change(steps: Int, fast: Boolean) {
        val b = store.eqBand(band)
        val nb = when (mode) {
            TYPE -> b.copy(type = (b.type + steps.coerceIn(-1, 1)).coerceIn(0, 2))
            GAIN -> b.copy(gain = (b.gain + steps * (if (fast) GAIN_FAST else GAIN_STEP)).coerceIn(GAIN_MIN, GAIN_MAX))
            CUTOFF -> b.copy(cutoff = (b.cutoff + steps * (if (fast) CUTOFF_FAST else CUTOFF_STEP)).coerceIn(CUTOFF_MIN, CUTOFF_MAX))
            else -> b.copy(q = (b.q + steps * (if (fast) Q_FAST else Q_STEP)).coerceIn(Q_MIN, Q_MAX))
        }
        if (nb != b) { store.setEqBand(band, nb); rev++ }
    }

    val modeLine = str("LANG_SYSFONT_EQUALIZER_EDIT_MODE").replaceFirst("%s", when (mode) {
        TYPE -> str("LANG_EQUALIZER_FILTER_TYPE")
        GAIN -> str("LANG_GAIN")
        CUTOFF -> str("LANG_SYSFONT_EQUALIZER_BAND_CUTOFF")
        else -> str("LANG_EQUALIZER_BAND_Q")
    }).replaceFirst("%s", when (mode) { GAIN -> "(dB)"; CUTOFF -> "(Hz)"; else -> "" })

    Column(Modifier.fillMaxSize()) {
        BasicText(modeLine, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = Cabbie.body)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items((0 until SettingsStore.EQ_BANDS).toList()) { i ->
                val b = bands[i]
                val sel = i == band
                Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp)
                    .then(if (sel) Modifier.border(2.dp, Cabbie.Gold) else Modifier)
                    .clickable { band = i }.padding(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        fun field(text: String, m: Int, weight: Float) =
                            @Composable {
                                val on = sel && mode == m
                                BasicText(text, Modifier.weight(weight)
                                    .then(if (on) Modifier.background(Cabbie.Gold) else Modifier)
                                    .clickable { band = i; mode = m }.padding(horizontal = 4.dp, vertical = 2.dp),
                                    style = Cabbie.small.copy(color = if (on) Color.Black else Cabbie.Text,
                                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal))
                            }
                        field(listOf("LS", "PK", "HS")[b.type] + "$i:", TYPE, 0.8f)()
                        field("%s%2d.%ddB".format(if (b.gain < 0) "-" else " ", Math.abs(b.gain) / 10, Math.abs(b.gain) % 10), GAIN, 1.1f)()
                        field("%5dHz".format(b.cutoff), CUTOFF, 1.1f)()
                        field("%d.%d Q".format(b.q / 10, b.q % 10), Q, 0.8f)()
                    }
                    // The slider shows the value being edited, as Rockbox's scrollbar does.
                    val (pos, span) = when (mode) {
                        TYPE -> b.type.toFloat() / 2 to 0.34f
                        CUTOFF -> (b.cutoff - CUTOFF_MIN).toFloat() / (CUTOFF_MAX - CUTOFF_MIN) to 0.02f
                        Q -> (b.q - Q_MIN).toFloat() / (Q_MAX - Q_MIN) to 0.04f
                        else -> (b.gain - GAIN_MIN).toFloat() / (GAIN_MAX - GAIN_MIN) to 0.04f
                    }
                    val knob = if (sel) Cabbie.Gold else Cabbie.Text
                    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().padding(top = 4.dp).height(8.dp).border(1.dp, Cabbie.Dim)) {
                        val w = size.width * span
                        val x = (size.width - w) * pos.coerceIn(0f, 1f)
                        drawRect(knob, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(64.dp).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((label, steps, fast) in listOf(Triple("−−", -1, true), Triple("−", -1, false), Triple("+", 1, false), Triple("++", 1, true))) {
                RepeatButton(label, Modifier.weight(1f)) { change(steps, fast) }
            }
        }
    }
}

/** A button that acts on press and repeats while held (ACTION_SETTINGS_INCREPEAT). */
@Composable
internal fun RepeatButton(label: String, modifier: Modifier, onStep: () -> Unit) {
    val step by rememberUpdatedState(onStep)
    Box(modifier.fillMaxHeight().background(Cabbie.Gold).pointerInput(Unit) {
        coroutineScope {
            awaitEachGesture {
                awaitFirstDown()
                step()
                val job = launch { delay(500); while (true) { step(); delay(100) } }
                do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                job.cancel()
            }
        }
    }, contentAlignment = Alignment.Center) {
        BasicText(label, style = Cabbie.body.copy(color = Color.Black, fontWeight = FontWeight.Bold))
    }
}

// eq_menu.h
private const val TYPE = 0
private const val GAIN = 1
private const val CUTOFF = 2
private const val Q = 3
private const val CUTOFF_MIN = 20
private const val CUTOFF_MAX = 22040
private const val CUTOFF_STEP = 10
private const val CUTOFF_FAST = 100
private const val GAIN_MIN = -240
private const val GAIN_MAX = 240
private const val GAIN_STEP = 1
private const val GAIN_FAST = 10
private const val Q_MIN = 1
private const val Q_MAX = 64
private const val Q_STEP = 1
private const val Q_FAST = 10
