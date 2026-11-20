package llm.slop.slopbox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pitch screen arithmetic against Rockbox's own get_semitone_from_pitch() and
 * get_pitch_from_semitone() (apps/plugins/pitch_screen.c), compiled on the
 * host to make src/test/resources/pitch_vectors.txt.
 */
class PitchTest {
    private val vectors = javaClass.classLoader!!.getResourceAsStream("pitch_vectors.txt")!!
        .bufferedReader().readLines().map { it.split(' ') }

    @Test fun semitoneFromPitchMatchesRockbox() {
        val v = vectors.filter { it[0] == "P" }
        check(v.size > 2000)
        for ((_, p, s) in v) assertEquals("pitch $p", s.toInt(), Pitch.semitoneFromPitch(p.toInt()))
    }

    @Test fun pitchFromSemitoneMatchesRockbox() {
        val v = vectors.filter { it[0] == "S" }
        check(v.size > 700)
        for ((_, s, p) in v) assertEquals("semitone $s", p.toInt(), Pitch.pitchFromSemitone(s.toInt()))
    }

    @Test fun speedPitchStretch() {
        // tdspeed.h: speed = pitch x stretch.
        assertEquals(15000, Pitch.getSpeed(12000, 12500))
        assertEquals(12000, Pitch.getPitch(15000, 12500))
        assertEquals(12500, Pitch.getStretch(12000, 15000))
    }

    @Test fun labels() {
        assertEquals("100.0%", Pitch.percent(10000))
        assertEquals("98.7%", Pitch.percent(9875))
        assertEquals("+1.00", Pitch.semitoneText(100))
        assertEquals("-0.10", Pitch.semitoneText(-10))
    }
}
