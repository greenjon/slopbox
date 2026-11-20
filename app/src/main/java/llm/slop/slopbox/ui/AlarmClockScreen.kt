/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/plugins/alarmclock.c - Copyright (C) 2010 Clément Pit-Claudel
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * Rockbox's alarmclock plugin: pause now, resume playback at a set time.
 * "Set alarm at [HH]:MM." -- with the plugin library's touch grid: top and
 * bottom change the value, left and right switch hours/minutes, the centre
 * starts waiting ("Next alarm in ..."). The screen stays on while it waits,
 * as a Rockbox player stays in the plugin.
 */
@Composable
fun AlarmClockScreen(controller: MediaController?, nav: Nav) {
    val ctx = LocalContext.current
    var hours by remember { mutableIntStateOf(0) }
    var minutes by remember { mutableIntStateOf(0) }
    var current by remember { mutableIntStateOf(0) }
    var waiting by remember { mutableStateOf(false) }
    var tomorrow by remember { mutableStateOf(false) }
    var prevTick by remember { mutableIntStateOf(3600 * 24) }
    var tick by remember { mutableIntStateOf(0) }

    // can_play(): something playing, or a playlist to resume.
    val c = controller
    LaunchedEffect(Unit) {
        if (c == null || c.mediaItemCount == 0) {
            Toast.makeText(ctx, "No track to resume! Play or pause one first.", Toast.LENGTH_LONG).show()
            nav.pop()
        } else c.pause()
    }
    val view = LocalView.current
    DisposableEffect(waiting) {
        view.keepScreenOn = waiting
        onDispose { view.keepScreenOn = false }
    }

    /** rem_seconds(), with its "tomorrow" flag. */
    fun remSeconds(): Int {
        val now = Calendar.getInstance()
        var s = (hours - now.get(Calendar.HOUR_OF_DAY)) * 3600 + (minutes - now.get(Calendar.MINUTE)) * 60 - now.get(Calendar.SECOND)
        if (s > prevTick) tomorrow = false
        prevTick = s
        return s + if (tomorrow) 24 * 3600 else 0
    }
    LaunchedEffect(waiting) {
        while (true) {
            delay(500); tick++
            if (waiting && remSeconds() <= 0) {
                // resume_audio(), then to the WPS.
                c?.let { it.prepare(); it.play() }
                nav.pop(); nav.toNowPlaying()
                break
            }
        }
    }

    @Suppress("UNUSED_EXPRESSION") tick
    val info = if (waiting) {
        val s = remSeconds()
        "Next alarm in %02dh, %02dmn, and %02ds.".format(s / 3600, (s / 60) % 60, s % 60)
    } else if (current == 0) "Set alarm at [%02d]:%02d.".format(hours, minutes)
    else "Set alarm at %02d:[%02d].".format(hours, minutes)

    Column(Modifier.fillMaxSize()) {
        TitleBar("Alarm Clock", onBack = { nav.pop() })
        Box(Modifier.fillMaxSize().pointerInput(waiting) {
            detectTapGestures { o ->
                if (waiting) return@detectTapGestures
                val col = when { o.x < size.width / 3f -> 0; o.x > 2 * size.width / 3f -> 2; else -> 1 }
                val row = when { o.y < size.height / 3f -> 0; o.y > 2 * size.height / 3f -> 2; else -> 1 }
                val max = if (current == 0) 24 else 60
                fun set(v: Int) { if (current == 0) hours = v else minutes = v }
                val v = if (current == 0) hours else minutes
                when {
                    row == 0 -> set((v + 1) % max)
                    row == 2 -> set((v + max - 1) % max)
                    col != 1 -> current = (current + 1) % 2
                    else -> { if (remSeconds() < 0) tomorrow = true; waiting = true }
                }
            }
        }, contentAlignment = Alignment.Center) {
            BasicText(info, Modifier.fillMaxWidth(), style = Cabbie.body.copy(textAlign = TextAlign.Center))
        }
    }
}
