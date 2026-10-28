package dev.earshot.dsp.sleep

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepSoundsTest {
    @Test
    fun `stored ids are frozen and round-trip`() {
        val expected = mapOf(
            SleepSound.WHITE_NOISE to "white", SleepSound.PINK_NOISE to "pink", SleepSound.BROWN_NOISE to "brown",
            SleepSound.RAIN to "rain", SleepSound.HEAVY_RAIN to "heavy_rain", SleepSound.OCEAN to "ocean",
            SleepSound.STREAM to "stream", SleepSound.WIND to "wind", SleepSound.FIRE to "fire", SleepSound.FAN to "fan",
        )
        assertEquals(expected.keys, SleepSound.entries.toSet())
        for ((sound, id) in expected) {
            assertEquals(id, sound.storedId)
            assertEquals(sound, SleepSound.fromStoredId(id))
        }
        assertNull(SleepSound.fromStoredId("thunder"))
        assertEquals(SleepSoundGroup.WATER, SleepSound.OCEAN.group)
        assertEquals(SleepSoundGroup.HOME, SleepSound.FIRE.group)
    }

    @Test
    fun `every sound stays in range, finite, audible, stereo and never repeats over 30 s`() {
        for (sound in SleepSound.entries) {
            val audio = Signals.render(SleepSounds.create(sound, SEED), 30.0)
            for (v in audio) {
                assertTrue(v.isFinite(), "$sound produced $v")
                assertTrue(abs(v) <= 1f, "$sound produced $v")
            }
            val left = Signals.channel(audio, 0)
            val right = Signals.channel(audio, 1)
            assertTrue(Signals.rms(left) > MIN_RMS && Signals.rms(right) > MIN_RMS, "$sound is nearly silent: ${Signals.rms(audio)}")
            assertTrue(abs(Signals.correlation(left, right)) < 0.95, "$sound has two identical channels")

            // A looped buffer would make windows seconds apart near-identical.
            val starts = listOf(1.0, 4.0, 9.0, 16.0, 25.0).map { (it * Signals.RATE).toInt() }
            val windows = starts.map { Signals.channel(audio, 0, it, it + Signals.RATE / 2) }
            for (i in windows.indices) {
                for (j in i + 1 until windows.size) {
                    val r = Signals.correlation(windows[i], windows[j])
                    assertTrue(abs(r) < 0.3, "$sound repeats: windows $i and $j correlate at $r")
                }
            }
        }
    }

    @Test
    fun `the same seed gives the same audio and another seed different audio`() {
        for (sound in SleepSound.entries) {
            val a = Signals.render(SleepSounds.create(sound, SEED), 3.0)
            val b = Signals.render(SleepSounds.create(sound, SEED), 3.0)
            val c = Signals.render(SleepSounds.create(sound, SEED + 1), 3.0)
            assertContentEquals(a, b, "$sound is not deterministic")
            assertFalse(a.contentEquals(c), "$sound ignores its seed")
            val r = Signals.correlation(Signals.channel(a, 0), Signals.channel(c, 0))
            assertTrue(abs(r) < 0.5, "$sound with another seed is too similar ($r)")
        }
    }

    @Test
    fun `output does not depend on the block size`() {
        val random = Random(1)
        for (sound in SleepSound.entries) {
            val whole = FloatArray(2 * Signals.RATE)
            SleepSounds.create(sound, SEED).render(whole, 0, Signals.RATE)
            val pieces = FloatArray(2 * Signals.RATE)
            val source = SleepSounds.create(sound, SEED)
            var done = 0
            while (done < Signals.RATE) {
                val n = minOf(random.nextInt(1, 3000), Signals.RATE - done)
                source.render(pieces, 2 * done, n)
                done += n
            }
            assertContentEquals(whole, pieces, "$sound depends on the block size")
        }
    }

    @Test
    fun `render writes only the requested frames`() {
        val out = FloatArray(100) { 7f }
        SleepSounds.create(SleepSound.RAIN, SEED).render(out, 10, 20)
        for (i in 0 until 10) assertEquals(7f, out[i])
        for (i in 50 until 100) assertEquals(7f, out[i])
        assertFailsWith<IllegalArgumentException> { SleepSounds.create(SleepSound.RAIN, SEED).render(out, 90, 10) }
    }

    @Test
    fun `unsupported sample rates are refused`() {
        assertFailsWith<IllegalArgumentException> { SleepSounds.create(SleepSound.PINK_NOISE, SEED, sampleRate = 8_000) }
        // Other common rates work (the recipes are in hertz and seconds).
        val audio = FloatArray(2 * 4410)
        SleepSounds.create(SleepSound.FIRE, SEED, sampleRate = 44_100).render(audio, 0, 4410)
        assertTrue(audio.all { it.isFinite() && abs(it) <= 1f })
    }

    private companion object {
        const val SEED = 42L
        const val MIN_RMS = 0.01
    }
}
