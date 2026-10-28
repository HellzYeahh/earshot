package dev.earshot.dsp.sleep

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlarmTonesTest {
    @Test
    fun `stored ids are frozen and round-trip`() {
        val expected = mapOf(AlarmTone.CHIME to "chime", AlarmTone.BIRDS to "birds", AlarmTone.CLASSIC to "classic")
        assertEquals(expected.keys, AlarmTone.entries.toSet())
        for ((tone, id) in expected) {
            assertEquals(id, tone.storedId)
            assertEquals(tone, AlarmTone.fromStoredId(id))
        }
        assertNull(AlarmTone.fromStoredId("siren"))
    }

    @Test
    fun `every tone stays in range, finite and audible over 30 s`() {
        for (tone in AlarmTone.entries) {
            val audio = Signals.render(AlarmTones.create(tone, SEED), 30.0)
            for (v in audio) {
                assertTrue(v.isFinite(), "$tone produced $v")
                assertTrue(abs(v) <= 1f, "$tone produced $v")
            }
            assertTrue(Signals.rms(audio) > MIN_RMS, "$tone is nearly silent: ${Signals.rms(audio)}")
            // Loops forever: still sounding in the last ten seconds.
            val lastTen = 20 * Signals.RATE
            assertTrue(Signals.rms(audio, lastTen) > MIN_RMS, "$tone stopped")
        }
    }

    @Test
    fun `the same seed gives the same audio and another seed different audio`() {
        for (tone in AlarmTone.entries) {
            val a = Signals.render(AlarmTones.create(tone, SEED), 5.0)
            val b = Signals.render(AlarmTones.create(tone, SEED), 5.0)
            val c = Signals.render(AlarmTones.create(tone, SEED + 1), 5.0)
            assertContentEquals(a, b, "$tone is not deterministic")
            assertFalse(a.contentEquals(c), "$tone ignores its seed")
        }
    }

    @Test
    fun `output does not depend on the block size`() {
        val random = Random(2)
        for (tone in AlarmTone.entries) {
            val whole = FloatArray(2 * Signals.RATE)
            AlarmTones.create(tone, SEED).render(whole, 0, Signals.RATE)
            val pieces = FloatArray(2 * Signals.RATE)
            val source = AlarmTones.create(tone, SEED)
            var done = 0
            while (done < Signals.RATE) {
                val n = minOf(random.nextInt(1, 3000), Signals.RATE - done)
                source.render(pieces, 2 * done, n)
                done += n
            }
            assertContentEquals(whole, pieces, "$tone depends on the block size")
        }
    }

    @Test
    fun `classic beeps in a fixed pattern, centred, with silent rests`() {
        val audio = Signals.render(AlarmTones.create(AlarmTone.CLASSIC, SEED), 2.6)
        for (f in 0 until audio.size / 2) assertEquals(audio[2 * f], audio[2 * f + 1])
        val rate = Signals.RATE
        // Beeps in 0–0.14 s and 0.2–0.34 s, rest in 0.8–1.3 s, the same again from 1.3 s.
        assertTrue(Signals.rms(audio, (0.02 * rate).toInt(), (0.12 * rate).toInt()) > 0.3)
        assertTrue(Signals.rms(audio, (0.22 * rate).toInt(), (0.32 * rate).toInt()) > 0.3)
        assertEquals(0.0, Signals.rms(audio, (0.8 * rate).toInt(), (1.29 * rate).toInt()))
        assertTrue(Signals.rms(audio, (1.32 * rate).toInt(), (1.42 * rate).toInt()) > 0.3)
    }

    private companion object {
        const val SEED = 7L
        const val MIN_RMS = 0.01
    }
}
