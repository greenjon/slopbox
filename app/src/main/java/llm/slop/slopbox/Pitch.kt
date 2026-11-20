/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/plugins/pitch_screen.c - Copyright (C) 2002 Björn Stenberg
 *   firmware/sound.c - Copyright (C) 2005 by Linus Nielsen Feltzing; 2007 by Christian Gmeiner; 2013 by Michael Sevakis
 *   lib/rbcodec/dsp/tdspeed.c - Copyright (C) 2006 by Nicolas Pitre <nico@cam.org>; 2006-2007 by Stéphane Doyon <s.doyon@videotron.ca>; 2012 Michael Sevakis
 * and the other Rockbox contributors (third_party/rockbox/docs/CREDITS).
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package llm.slop.slopbox

import android.content.Context

/**
 * Rockbox pitch and speed (firmware/sound.c sound_set_pitch(),
 * lib/rbcodec/dsp/tdspeed.c dsp_set_timestretch()) and the arithmetic of the
 * pitch screen plugin (apps/plugins/pitch_screen.c), integer for integer.
 * Values use Rockbox's PITCH_SPEED_PRECISION: 10000 = 100%.
 *
 * The current pitch and stretch are kept like Rockbox's global_status
 * resume_pitch / resume_speed, and restored when the app starts.
 */
object Pitch {
    const val PRECISION = 100
    const val P100 = 100 * PRECISION
    const val PITCH_MAX = 200 * PRECISION
    const val PITCH_MIN = 50 * PRECISION
    const val PITCH_SMALL_DELTA = PRECISION / 10
    const val PITCH_BIG_DELTA = PRECISION
    const val PITCH_NUDGE_DELTA = 2 * PRECISION
    const val SPEED_SMALL_DELTA = PRECISION / 10
    const val SPEED_BIG_DELTA = PRECISION
    const val SEMITONE_SMALL_DELTA = PRECISION / 10
    const val SEMITONE_BIG_DELTA = PRECISION
    const val STRETCH_MAX = 250 * PRECISION
    const val STRETCH_MIN = 35 * PRECISION

    private fun prec(x: Double) = ((x * PRECISION * 10 + 5) / 10).toInt()

    private val SEMITONES = doubleArrayOf(
        50.00000000, 52.97315472, 56.12310242, 59.46035575, 62.99605249, 66.74199271,
        70.71067812, 74.91535384, 79.37005260, 84.08964153, 89.08987181, 94.38743127,
        100.0000000, 105.9463094, 112.2462048, 118.9207115, 125.9921049, 133.4839854,
        141.4213562, 149.8307077, 158.7401052, 168.1792831, 178.1797436, 188.7748625,
        200.0000000,
    ).map { prec(it) }.toIntArray()
    private val NUM_SEMITONES = SEMITONES.size
    private val SEMITONE_END = NUM_SEMITONES / 2
    private val SEMITONE_START = -SEMITONE_END

    private val CENT_INTERP = doubleArrayOf(
        100.0000000, 101.1619440, 102.3373892, 103.5264924, 104.7294123, 105.9463094,
    ).map { prec(it) }.toIntArray()
    private const val CENT_INTERP_INTERVAL = 20
    private val CENT_INTERP_NUM = CENT_INTERP.size

    // tdspeed.h
    fun getSpeed(pitch: Int, stretch: Int) = ((pitch.toLong() * stretch + P100 / 2) / P100).toInt()
    fun getPitch(speed: Int, stretch: Int) = ((speed.toLong() * P100 + stretch / 2) / stretch).toInt()
    fun getStretch(pitch: Int, speed: Int) = ((speed.toLong() * P100 + pitch / 2) / pitch).toInt()

    // ---- state (sound_get_pitch / dsp_get_timestretch) ----

    @Volatile var pitch = P100
        private set
    @Volatile private var stretchRaw = P100
    private var prefs: android.content.SharedPreferences? = null
    private var store: SettingsStore? = null

    /** Rockbox dsp_timestretch_available(): only with Sound Settings › Timestretch on. */
    val timestretchAvailable: Boolean get() = (store?.int("timestretch enabled") ?: 0) != 0

    /** dsp_get_timestretch(): 100% unless timestretch is available. */
    val stretch: Int get() = if (timestretchAvailable) stretchRaw else P100

    fun init(ctx: Context) {
        if (prefs != null) return
        store = SettingsStore.get(ctx)
        prefs = ctx.getSharedPreferences("global_status", Context.MODE_PRIVATE).also {
            pitch = it.getInt("resume_pitch", P100).coerceIn(PITCH_MIN, PITCH_MAX)
            stretchRaw = it.getInt("resume_speed", P100).coerceIn(STRETCH_MIN, STRETCH_MAX)
        }
        apply()
    }

    /** Rockbox sound_set_pitch(). */
    fun setPitch(p: Int) {
        pitch = p.coerceIn(PITCH_MIN, PITCH_MAX)
        apply()
    }

    /** Rockbox dsp_set_timestretch(). */
    fun setStretch(s: Int) {
        stretchRaw = s.coerceIn(STRETCH_MIN, STRETCH_MAX)
        apply()
    }

    /** Rockbox reset_pitch(): both back to 100%. */
    fun reset() { pitch = P100; stretchRaw = P100; apply() }

    /** Told when pitch or speed changes (the player republishes its playback speed). */
    @Volatile var onChange: (() -> Unit)? = null

    private fun apply() {
        onChange?.invoke()
        Engine.setPitch(pitch / P100.toDouble())
        Engine.setTempo(stretch / P100.toDouble())
        prefs?.edit()?.putInt("resume_pitch", pitch)?.putInt("resume_speed", stretchRaw)?.apply()
    }

    // ---- pitch_screen.c ----

    /** Set when a change hit a pitch or stretch limit (the "Limit" label). */
    var atLimit = false

    /** pitch_increase(): the new pitch (also applied). */
    fun pitchIncrease(pitch: Int, delta0: Int, allowCutoff: Boolean, speed: Int): Int {
        var delta = delta0
        var newPitch: Int
        atLimit = false
        if (delta < 0) {
            if (allowCutoff && delta <= -PRECISION && (pitch + delta) % PRECISION != 0)
                delta += PRECISION - ((pitch + delta) % PRECISION)
            newPitch = pitch + delta
            if (newPitch < PITCH_MIN) {
                if (!allowCutoff) return pitch
                newPitch = PITCH_MIN
                atLimit = true
            }
        } else if (delta > 0) {
            if (allowCutoff && delta >= PRECISION && (pitch + delta) % PRECISION != 0)
                delta -= (pitch + delta) % PRECISION
            newPitch = pitch + delta
            if (newPitch > PITCH_MAX) {
                if (!allowCutoff) return pitch
                newPitch = PITCH_MAX
                atLimit = true
            }
        } else return pitch
        if (timestretchAvailable) {
            val newStretch = getStretch(newPitch, speed)
            if (newStretch < STRETCH_MIN) newPitch = getPitch(speed, STRETCH_MIN)
            else if (newStretch > STRETCH_MAX) newPitch = getPitch(speed, STRETCH_MAX)
            if (newStretch >= STRETCH_MAX || newStretch <= STRETCH_MIN) atLimit = true
        }
        setPitch(newPitch)
        return newPitch
    }

    /** get_semitone_from_pitch(): semitones x PRECISION. */
    fun semitoneFromPitch(pitch: Int): Int {
        var semitone = 0
        var frac = 0
        while (semitone < NUM_SEMITONES - 1 && pitch >= SEMITONES[semitone + 1]) semitone++
        while (pitch > CENT_INTERP[frac + 1] * SEMITONES[semitone] / P100) {
            if (frac >= CENT_INTERP_NUM - 1) break
            frac++
        }
        // At the top of the table there's no next cent step; Rockbox reads one past it.
        if (frac + 1 >= CENT_INTERP_NUM) frac = CENT_INTERP_NUM - 2
        val a = CENT_INTERP[frac] * SEMITONES[semitone] / P100
        val b = CENT_INTERP[frac + 1] * SEMITONES[semitone] / P100
        val ofs = (pitch - a) * CENT_INTERP_INTERVAL / (b - a)
        return (semitone + SEMITONE_START) * PRECISION + frac * CENT_INTERP_INTERVAL + ofs
    }

    /** get_pitch_from_semitone(). */
    fun pitchFromSemitone(semitone: Int): Int {
        val adj = semitone - SEMITONE_START * PRECISION
        val idx = (adj / PRECISION).coerceAtMost(NUM_SEMITONES - 1)
        val p = SEMITONES[idx]
        val ci = ((adj % PRECISION) / CENT_INTERP_INTERVAL).coerceAtMost(CENT_INTERP_NUM - 2)
        val modA = CENT_INTERP[ci]
        val modB = CENT_INTERP[ci + 1]
        val mod = modA + (modB - modA) * (adj % CENT_INTERP_INTERVAL) / CENT_INTERP_INTERVAL
        return ((p.toLong() * mod + P100 / 2) / P100).toInt()
    }

    /** pitch_increase_semitone(): the new semitone value (pitch applied). */
    fun semitoneIncrease(pitch: Int, current: Int, delta: Int, speed: Int): Int {
        var s = current
        if (current % delta != 0) {
            if (current > 0 && delta > 0) s += delta
            else if (current < 0 && delta < 0) s += delta
            s -= s % delta
        } else s += delta
        if (s < SEMITONE_START * PRECISION) { s = SEMITONE_START * PRECISION; atLimit = true }
        else if (s > SEMITONE_END * PRECISION) { s = SEMITONE_END * PRECISION; atLimit = true }
        var newPitch = pitchFromSemitone(s)
        val newStretch = getStretch(newPitch, speed)
        if (newStretch > STRETCH_MAX) {
            newPitch = getPitch(speed, STRETCH_MAX); s = semitoneFromPitch(newPitch); atLimit = true
        } else if (newStretch < STRETCH_MIN) {
            newPitch = getPitch(speed, STRETCH_MIN); s = semitoneFromPitch(newPitch); atLimit = true
        }
        pitchIncrease(pitch, newPitch - pitch, false, speed)
        return s
    }

    /** The speed part of the main loop: clamp the stretch, apply it; the new speed. */
    fun applySpeed(pitch: Int, newSpeed0: Int): Int {
        var newSpeed = newSpeed0
        var newStretch = getStretch(pitch, newSpeed)
        if (newStretch > STRETCH_MAX) { newStretch = STRETCH_MAX; newSpeed = getSpeed(pitch, newStretch) }
        else if (newStretch < STRETCH_MIN) { newStretch = STRETCH_MIN; newSpeed = getSpeed(pitch, newStretch) }
        newStretch = getStretch(pitch, newSpeed)
        if (newStretch >= STRETCH_MAX || newStretch <= STRETCH_MIN) atLimit = true
        setStretch(newStretch)
        return newSpeed
    }

    /** "123.4%" (Rockbox's %ld.%ld%%). */
    fun percent(v: Int) = "${v / PRECISION}.${(v % PRECISION) / (PRECISION / 10)}%"

    /** "+1.05" semitones. */
    fun semitoneText(s: Int) = (if (s >= 0) "+" else "-") + "${Math.abs(s / PRECISION)}." +
        "%02d".format(Math.abs((s % PRECISION) / (PRECISION / 100)))
}
