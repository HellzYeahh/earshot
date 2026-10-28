package dev.earshot.dsp.sleep

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Recorded loops: playback wraps, the seed picks the start, the library is used only when it has the sound. */
class RecordingsTest {
    /** Left = frame index, right = minus it, so every sample says where it came from. */
    private fun ramp(frames: Int) = LoopPcm(ShortArray(frames * 2) { i -> if (i % 2 == 0) (i / 2).toShort() else (-(i / 2)).toShort() }, 48_000)

    private class FakeLibrary(private val loops: Map<String, LoopPcm>) : RecordingLibrary {
        override val ids: Set<String> get() = loops.keys
        override fun loop(id: String): LoopPcm? = loops[id]
    }

    @AfterTest fun reset() {
        SleepSounds.recordings = null
    }

    @Test fun `a loop plays round and round from where the seed puts it`() {
        val pcm = ramp(100)
        val source = LoopSource(pcm, seed = 7)
        val out = FloatArray(250 * 2)
        source.render(out, 0, 250)
        val first = Math.round(out[0] * 32768f)
        for (f in 0 until 250) {
            val expected = (first + f) % 100
            assertEquals(expected, Math.round(out[f * 2] * 32768f), "left at frame $f")
            assertEquals(-expected, Math.round(out[f * 2 + 1] * 32768f), "right at frame $f")
        }
    }

    @Test fun `the same seed starts at the same place, another seed usually elsewhere`() {
        val pcm = ramp(10_000)
        fun startOf(seed: Long) = FloatArray(2).also { LoopSource(pcm, seed).render(it, 0, 1) }[0]
        assertEquals(startOf(1), startOf(1))
        assertTrue((2L..20L).map { startOf(it) }.toSet().size > 10)
    }

    @Test fun `block size doesn't change the output`() {
        val pcm = ramp(333)
        val whole = FloatArray(1000 * 2).also { LoopSource(pcm, 3).render(it, 0, 1000) }
        val pieces = FloatArray(1000 * 2)
        val chunked = LoopSource(pcm, 3)
        var done = 0
        while (done < 1000) {
            val n = minOf(37, 1000 - done)
            chunked.render(pieces, done * 2, n)
            done += n
        }
        assertTrue(whole.contentEquals(pieces))
    }

    @Test fun `a recorded sound plays its loop, the rest stay synthesised`() {
        SleepSounds.recordings = FakeLibrary(mapOf("rain" to ramp(480), "alarm_birds" to ramp(480)))
        assertIs<LoopSource>(SleepSounds.create(SleepSound.RAIN, seed = 1))
        assertIs<LoopSource>(AlarmTones.create(AlarmTone.BIRDS, seed = 1))
        assertFalse(SleepSounds.create(SleepSound.FIRE, seed = 1) is LoopSource)
        assertFalse(SleepSounds.create(SleepSound.BROWN_NOISE, seed = 1) is LoopSource)
        assertTrue(SleepSounds.isRecorded(SleepSound.RAIN))
        assertFalse(SleepSounds.isRecorded(SleepSound.FIRE))
    }

    @Test fun `a loop at another rate isn't used`() {
        SleepSounds.recordings = FakeLibrary(mapOf("rain" to LoopPcm(ShortArray(200), 44_100)))
        assertFalse(SleepSounds.create(SleepSound.RAIN, seed = 1) is LoopSource)
    }

    @Test fun `the mixer plays a recording through the library`() {
        SleepSounds.recordings = FakeLibrary(mapOf("rain" to LoopPcm(ShortArray(48_000 * 2) { 8_000 }, 48_000)))
        val mixer = SoundMixer(seed = 1)
        mixer.setLayers(listOf(SoundLayer(SleepSound.RAIN, 1f)))
        mixer.masterGain = 1f
        val out = FloatArray(48_000 * 2 * 3)
        mixer.render(out, 48_000 * 3) // three seconds: well past the 1.5 s fade-in
        // A constant recording comes out as a constant: proof it's the loop, not the rain synthesis.
        val tail = out.copyOfRange(out.size - 200, out.size)
        assertTrue(tail.all { kotlin.math.abs(it - tail[0]) < 1e-4f && it > 0.1f }, "expected the constant loop, got ${tail.take(4)}")
    }
}
