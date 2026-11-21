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

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView
import llm.slop.slopbox.SettingsStore
import llm.slop.slopbox.SlopBoxSettings

/**
 * Vibration feedback standing in for a player's button clicks (Theme Settings ›
 * SlopBox › Haptic Feedback). [tick] for each step of something that repeats
 * (seek, −/+, a value), [press] for a long-press or a gesture that did something.
 */
class Haptics(private val view: View) {
    private fun on() = SettingsStore.get(view.context).int(SlopBoxSettings.HAPTICS) != 0
    fun tick() { if (on()) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    fun press() { if (on()) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return androidx.compose.runtime.remember(view) { Haptics(view) }
}
