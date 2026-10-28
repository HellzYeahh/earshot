package dev.earshot.dsp.sleep

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The user's own sounds: any file becomes a seamless, levelled 48 kHz stereo loop. */
class LoopMakerTest {
    /** A sine at [hz] with a little noise, as interleaved shorts. */
    private fun tone(seconds: Double, rate: Int, channels: Int, hz: Double = 440.0, amp: Double = 0.1, noise: Double = 0.1): ShortArray {
        val frames = (seconds * rate).toInt()
        val rnd = java.util.Random(1)
        return ShortArray(frames * channels) { i ->
            val f = i / channels
            ((amp * sin(2 * PI * hz * f / rate) + noise * amp * rnd.nextGaussian()) * 32767).toInt().toShort()
        }
    }

    private fun rmsDb(pcm: LoopPcm): Double {
        var e = 0.0
        for (s in pcm.samples) e += (s / 32768.0) * (s / 32768.0)
        return 20 * log10(sqrt(e / pcm.samples.size))
    }

    @Test fun `a 44 1 kHz mono file becomes a 48 kHz stereo loop at the target level`() {
        val loop = assertNotNull(LoopMaker.make(tone(20.0, 44_100, 1), channels = 1, rate = 44_100))
        assertEquals(48_000, loop.sampleRate)
        // 20 s minus the 3 s crossfade.
        assertEquals(17.0, loop.frames / 48_000.0, 0.01)
        assertEquals(LoopMaker.TARGET_RMS_DB, rmsDb(loop), 0.5)
        // Mono: both sides identical.
        assertTrue((0 until loop.frames step 997).all { loop.samples[2 * it] == loop.samples[2 * it + 1] })
    }

    @Test fun `long files are cut to the maximum`() {
        val loop = assertNotNull(LoopMaker.make(tone(100.0, 48_000, 2), channels = 2, rate = 48_000))
        assertEquals(LoopMaker.MAX_SECONDS * 48_000, loop.frames)
    }

    @Test fun `the pitch survives resampling`() {
        val loop = assertNotNull(LoopMaker.make(tone(10.0, 44_100, 2, hz = 1_000.0, amp = 0.3, noise = 0.0), channels = 2, rate = 44_100))
        // Count zero crossings of the left channel over one second in the middle: about 2 per cycle.
        val start = 4 * 48_000
        var crossings = 0
        for (i in start until start + 48_000) {
            val a = loop.samples[2 * i]; val b = loop.samples[2 * i + 2]
            if ((a < 0) != (b < 0)) crossings++
        }
        assertEquals(2_000.0, crossings.toDouble(), 60.0)
    }

    @Test fun `the wrap is no bigger than an ordinary step`() {
        val loop = assertNotNull(LoopMaker.make(tone(30.0, 48_000, 2, hz = 211.0, amp = 0.3), channels = 2, rate = 48_000))
        val s = loop.samples
        var steps = 0.0
        for (i in 0 until loop.frames - 1) steps += abs(s[2 * (i + 1)] - s[2 * i].toInt())
        val mean = steps / (loop.frames - 1)
        val wrap = abs(s[0] - s[2 * (loop.frames - 1)].toInt())
        assertTrue(wrap < 4 * mean, "wrap $wrap vs mean step $mean")
    }

    @Test fun `96 kHz files come out at the right length`() {
        val loop = assertNotNull(LoopMaker.make(tone(12.0, 96_000, 2), channels = 2, rate = 96_000))
        assertEquals(9.0, loop.frames / 48_000.0, 0.01)
    }

    @Test fun `too short or silent files are refused`() {
        assertNull(LoopMaker.make(tone(3.0, 48_000, 2), channels = 2, rate = 48_000))
        assertNull(LoopMaker.make(ShortArray(48_000 * 2 * 10), channels = 2, rate = 48_000))
    }

    @Test fun `a quiet file isn't boosted by more than 30 dB`() {
        val loop = assertNotNull(LoopMaker.make(tone(10.0, 48_000, 2, amp = 0.0005), channels = 2, rate = 48_000))
        assertTrue(rmsDb(loop) < LoopMaker.TARGET_RMS_DB - 3)
    }
}
