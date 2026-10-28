package dev.earshot.dsp.sleep

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Sleep player runs all night on a phone, so the engine must be far faster than real time.
 * The bound is generous (a minute of three layers in under 3 s on the PC, i.e. 20x real time)
 * so the test is not flaky; the measured figure is printed for the record.
 */
class RenderSpeedTest {
    @Test
    fun `a minute of rain, fire and brown noise renders well under real time`() {
        val mixer = SoundMixer(30L)
        mixer.setLayers(
            listOf(
                SoundLayer(SleepSound.RAIN, 1f),
                SoundLayer(SleepSound.FIRE, 1f),
                SoundLayer(SleepSound.BROWN_NOISE, 1f),
            ),
        )
        val block = 960
        val buffer = FloatArray(2 * block)
        repeat(5 * Signals.RATE / block) { mixer.render(buffer, block) } // JIT warm-up

        val blocks = 60 * Signals.RATE / block
        val start = System.nanoTime()
        repeat(blocks) { mixer.render(buffer, block) }
        val seconds = (System.nanoTime() - start) / 1e9
        println("60 s of rain + fire + brown rendered in %.3f s (%.0fx real time)".format(seconds, 60.0 / seconds))
        assertTrue(seconds < 3.0, "60 s took $seconds s")

        for (sound in SleepSound.entries) {
            val source = SleepSounds.create(sound, 31L)
            val out = FloatArray(2 * block)
            repeat(250) { source.render(out, 0, block) }
            val t0 = System.nanoTime()
            repeat(10 * Signals.RATE / block) { source.render(out, 0, block) }
            val s = (System.nanoTime() - t0) / 1e9
            println("%-12s 10 s in %.3f s (%.0fx real time)".format(sound.storedId, s, 10.0 / s))
        }
    }
}
