package llm.slop.slopbox.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import llm.slop.slopbox.Pitch
import llm.slop.slopbox.SettingsStore

/** Rockbox's pitch screen actions (ACTION_PS_*). */
private enum class Ps { INC_SMALL, INC_BIG, DEC_SMALL, DEC_BIG, NUDGE_LEFT, NUDGE_RIGHT, NUDGE_LEFTOFF,
    NUDGE_RIGHTOFF, SLOWER, FASTER, RESET, TOGGLE_MODE, EXIT }

/** Touch events of one cell, as Rockbox's touchscreen driver reports them. */
private enum class Touch { PRESS, REPEAT, REL, REL_AFTER_REPEAT }

/**
 * Rockbox's pitch screen plugin (apps/plugins/pitch_screen.c) with its
 * touchscreen layout: three rows of three cells.
 *
 * Top: Pitch Up (centre; tap = small step, hold = big steps), sides toggle the
 * mode (rate / semitone / timestretch). Middle: centre resets; the sides nudge
 * the rate by 2% while held, or (timestretch mode) change the speed. Bottom:
 * Pitch Down (centre), sides leave.
 */
@Composable
fun PitchScreen(nav: Nav) {
    val ctx = LocalContext.current
    val store = remember { SettingsStore.get(ctx) }
    fun str(id: String) = store.catalog.str(id)
    val semitoneSetting = store.catalog.byCfg["Semitone pitch change"]
    val stretchSetting = store.catalog.byCfg["Timestretch mode"]
    fun semitoneMode() = semitoneSetting?.let { store.int(it) != 0 } ?: false
    fun stretchMode() = stretchSetting?.let { store.int(it) != 0 } ?: false

    var pitch by remember { mutableIntStateOf(Pitch.pitch) }
    var speed by remember {
        mutableIntStateOf(if (Pitch.timestretchAvailable) Pitch.getSpeed(Pitch.pitch, Pitch.stretch) else Pitch.pitch)
    }
    var semitone by remember { mutableIntStateOf(Pitch.semitoneFromPitch(Pitch.pitch)) }
    var nudged by remember { mutableStateOf(false) }
    var atLimit by remember { mutableStateOf(false) }
    var rev by remember { mutableIntStateOf(0) }

    // The plugin's main loop, one action at a time.
    fun act(a: Ps) {
        var delta = 0
        var newSpeed = 0
        val semi = semitoneMode()
        val ts = stretchMode()
        Pitch.atLimit = atLimit
        when (a) {
            Ps.INC_SMALL -> delta = if (semi) Pitch.SEMITONE_SMALL_DELTA else Pitch.PITCH_SMALL_DELTA
            Ps.INC_BIG -> delta = if (semi) Pitch.SEMITONE_BIG_DELTA else Pitch.PITCH_BIG_DELTA
            Ps.DEC_SMALL -> delta = -(if (semi) Pitch.SEMITONE_SMALL_DELTA else Pitch.PITCH_SMALL_DELTA)
            Ps.DEC_BIG -> delta = -(if (semi) Pitch.SEMITONE_BIG_DELTA else Pitch.PITCH_BIG_DELTA)
            Ps.NUDGE_RIGHT, Ps.NUDGE_LEFT -> {
                val sign = if (a == Ps.NUDGE_RIGHT) 1 else -1
                if (!ts) {
                    val np = Pitch.pitchIncrease(pitch, sign * Pitch.PITCH_NUDGE_DELTA, false, speed)
                    nudged = np != pitch
                    pitch = np; semitone = Pitch.semitoneFromPitch(pitch); speed = pitch
                } else {
                    newSpeed = speed + sign * Pitch.SPEED_SMALL_DELTA
                    Pitch.atLimit = false
                }
            }
            Ps.FASTER -> if (ts) {
                newSpeed = speed + Pitch.SPEED_BIG_DELTA
                if (newSpeed % Pitch.PRECISION != 0) newSpeed -= newSpeed % Pitch.PRECISION
                Pitch.atLimit = false
            }
            Ps.SLOWER -> if (ts) {
                newSpeed = speed - Pitch.SPEED_BIG_DELTA
                if (newSpeed % Pitch.PRECISION != 0) newSpeed += Pitch.PRECISION - speed % Pitch.PRECISION
                Pitch.atLimit = false
            }
            Ps.NUDGE_RIGHTOFF, Ps.NUDGE_LEFTOFF -> if (nudged) {
                val sign = if (a == Ps.NUDGE_RIGHTOFF) -1 else 1
                pitch = Pitch.pitchIncrease(pitch, sign * Pitch.PITCH_NUDGE_DELTA, false, speed)
                speed = pitch; semitone = Pitch.semitoneFromPitch(pitch); nudged = false
            }
            Ps.RESET -> {
                pitch = Pitch.P100
                Pitch.setPitch(pitch)
                speed = Pitch.P100
                if (Pitch.timestretchAvailable) { Pitch.setStretch(Pitch.P100); Pitch.atLimit = false }
                semitone = Pitch.semitoneFromPitch(pitch)
            }
            Ps.TOGGLE_MODE -> {
                val newSemi = !semi
                semitoneSetting?.let { store.set(it, if (newSemi) 1 else 0) }
                if (Pitch.timestretchAvailable && !newSemi) {
                    val newTs = !ts
                    stretchSetting?.let { store.set(it, if (newTs) 1 else 0) }
                    if (!newTs) { speed = pitch; Pitch.setStretch(Pitch.P100) }
                }
            }
            Ps.EXIT -> { nav.pop(); return }
        }
        if (delta != 0) {
            if (semitoneMode()) {
                semitone = Pitch.semitoneIncrease(pitch, semitone, delta, speed)
                pitch = Pitch.pitchFromSemitone(semitone)
            } else {
                pitch = Pitch.pitchIncrease(pitch, delta, true, speed)
                semitone = Pitch.semitoneFromPitch(pitch)
            }
            if (stretchMode()) newSpeed = speed else speed = pitch
        }
        if (newSpeed != 0) speed = Pitch.applySpeed(pitch, newSpeed)
        atLimit = Pitch.atLimit
        rev++
    }

    // pitchscreen_do_touchscreen(): which action a touch in each cell is.
    fun top(side: Boolean, t: Touch): Ps? = when {
        side -> if (t == Touch.REL) Ps.TOGGLE_MODE else null
        t == Touch.REPEAT -> Ps.INC_BIG
        t == Touch.REL || t == Touch.REL_AFTER_REPEAT -> Ps.INC_SMALL
        else -> null
    }
    fun bottom(side: Boolean, t: Touch): Ps? = when {
        side -> if (t == Touch.REL) Ps.EXIT else null
        t == Touch.REPEAT -> Ps.DEC_BIG
        t == Touch.REL || t == Touch.REL_AFTER_REPEAT -> Ps.DEC_SMALL
        else -> null
    }
    fun middle(col: Int, t: Touch): Ps? = when (col) {
        0 -> when (t) { Touch.PRESS -> Ps.NUDGE_LEFT; Touch.REPEAT -> Ps.SLOWER; else -> Ps.NUDGE_LEFTOFF }
        2 -> when (t) { Touch.PRESS -> Ps.NUDGE_RIGHT; Touch.REPEAT -> Ps.FASTER; else -> Ps.NUDGE_RIGHTOFF }
        else -> if (t == Touch.PRESS) Ps.RESET else null
    }

    @Suppress("UNUSED_EXPRESSION") rev
    val semi = semitoneMode()
    val ts = stretchMode()
    val big = Cabbie.body.copy(textAlign = TextAlign.Center, fontSize = 22.sp)
    val mid = Cabbie.body.copy(textAlign = TextAlign.Center)

    Column(Modifier.fillMaxSize()) {
        TitleBar(str("LANG_PITCH"), onBack = { nav.pop() })
        // Top row
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Cell(Modifier.weight(1f), { t -> top(true, t)?.let(::act) }) { BasicText("<", style = big) }
            Cell(Modifier.weight(1f), { t -> top(false, t)?.let(::act) }) {
                BasicText(str(if (semi) "LANG_PITCH_UP_SEMITONE" else "LANG_PITCH_UP"), style = mid)
            }
            Cell(Modifier.weight(1f), { t -> top(true, t)?.let(::act) }) { BasicText(">", style = big) }
        }
        // Middle row
        Row(Modifier.fillMaxWidth().weight(1.4f)) {
            Cell(Modifier.weight(1f), { t -> middle(0, t)?.let(::act) }) { BasicText(if (ts) "<<" else "-2%", style = big) }
            Cell(Modifier.weight(1f), { t -> middle(1, t)?.let(::act) }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText(
                        if (ts) "${str("LANG_PITCH")}: " + (if (semi) Pitch.semitoneText(semitone) else Pitch.percent(pitch))
                        else "${str("LANG_PLAYBACK_RATE")}:", style = mid)
                    BasicText(
                        if (ts) "${str("LANG_SPEED")}: ${Pitch.percent(speed)}"
                        else if (semi) Pitch.semitoneText(semitone) else Pitch.percent(pitch),
                        style = big)
                    if (atLimit) BasicText(str("LANG_STRETCH_LIMIT"), style = mid.copy(color = Cabbie.Dim))
                }
            }
            Cell(Modifier.weight(1f), { t -> middle(2, t)?.let(::act) }) { BasicText(if (ts) ">>" else "+2%", style = big) }
        }
        // Bottom row
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Cell(Modifier.weight(1f), { t -> bottom(true, t)?.let(::act) }) { BasicText(str("LANG_KBD_OK"), style = mid) }
            Cell(Modifier.weight(1f), { t -> bottom(false, t)?.let(::act) }) {
                BasicText(str(if (semi) "LANG_PITCH_DOWN_SEMITONE" else "LANG_PITCH_DOWN"), style = mid)
            }
            Cell(Modifier.weight(1f), { t -> bottom(true, t)?.let(::act) }) { BasicText(str("LANG_KBD_OK"), style = mid) }
        }
    }
}

/** One touch cell: reports press, repeats while held (as Rockbox's BUTTON_REPEAT), release. */
@Composable
private fun Cell(modifier: Modifier, onTouch: (Touch) -> Unit, content: @Composable () -> Unit) {
    val cb by rememberUpdatedState(onTouch)
    Box(
        modifier.fillMaxHeight().padding(2.dp).border(1.dp, Cabbie.Dim.copy(alpha = 0.3f))
            .pointerInput(Unit) {
                coroutineScope {
                    awaitEachGesture {
                        awaitFirstDown()
                        cb(Touch.PRESS)
                        var repeated = false
                        val job = launch {
                            delay(REPEAT_START_MS)
                            while (true) { repeated = true; cb(Touch.REPEAT); delay(REPEAT_INTERVAL_MS) }
                        }
                        // Wait for every pointer to lift.
                        do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                        job.cancel()
                        cb(if (repeated) Touch.REL_AFTER_REPEAT else Touch.REL)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

private const val REPEAT_START_MS = 500L
private const val REPEAT_INTERVAL_MS = 100L
