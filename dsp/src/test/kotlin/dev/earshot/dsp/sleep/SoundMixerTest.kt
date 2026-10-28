package dev.earshot.dsp.sleep

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SoundMixerTest {
    private val rate = Signals.RATE

    private fun mixer(seed: Long = 11L, vararg layers: Pair<SleepSound, Float>) = SoundMixer(seed).apply {
        setLayers(layers.map { SoundLayer(it.first, it.second) })
    }

    @Test
    fun `three loud layers never pass the ceiling`() {
        val sets = listOf(
            listOf(SleepSound.RAIN, SleepSound.FIRE, SleepSound.BROWN_NOISE),
            listOf(SleepSound.WHITE_NOISE, SleepSound.PINK_NOISE, SleepSound.BROWN_NOISE),
            listOf(SleepSound.HEAVY_RAIN, SleepSound.OCEAN, SleepSound.WIND),
        )
        for (set in sets) {
            val m = mixer(3L, *set.map { it to 1f }.toTypedArray())
            val audio = Signals.render(m, 20.0)
            val peak = Signals.peak(audio)
            assertTrue(peak <= OutputLimiter.CEILING, "$set peaked at $peak")
            assertTrue(audio.all { it.isFinite() })
            assertTrue(Signals.rms(audio) > 0.05, "$set came out too quiet: ${Signals.rms(audio)}")
        }
    }

    @Test
    fun `a removed layer fades out along the equal-power curve`() {
        // Same seed and calls, so both mixers play the same brown noise; brown at full gain stays
        // below the limiter, so the ratio of the two outputs is exactly the fade gain.
        val steady = mixer(5L, SleepSound.BROWN_NOISE to 1f)
        val fading = mixer(5L, SleepSound.BROWN_NOISE to 1f)
        Signals.render(steady, 2.0)
        Signals.render(fading, 2.0)
        fading.setLayers(emptyList())
        val reference = Signals.render(steady, 2.0)
        val faded = Signals.render(fading, 2.0)
        val crossfade = SoundMixer.CROSSFADE_MS / 1000.0
        for (fraction in listOf(0.25, 0.5, 0.75)) {
            val at = (fraction * crossfade * rate).toInt()
            val gain = Signals.rms(faded, at, at + 480) / Signals.rms(reference, at, at + 480)
            val expected = cos(fraction * PI / 2)
            assertTrue(abs(gain - expected) < 0.03, "at $fraction of the fade the gain is $gain, expected $expected")
        }
        val end = (crossfade * rate).toInt() + 128
        assertEquals(0.0, Signals.rms(faded, end, end + rate / 4))
    }

    @Test
    fun `switching sounds cross-fades without a click`() {
        val m = mixer(5L, SleepSound.BROWN_NOISE to 1f)
        val before = Signals.render(m, 3.0)
        m.setLayers(listOf(SoundLayer(SleepSound.WIND, 1f)))
        val after = Signals.render(m, 3.0)

        val wind = Signals.render(mixer(6L, SleepSound.WIND to 1f), 4.0)
        val ownMovement = max(Signals.maxStep(before, 2 * rate, 3 * rate), Signals.maxStep(wind, 2 * rate, 4 * rate))
        val junction = max(abs(after[0] - before[before.size - 2]), abs(after[1] - before[before.size - 1])).toDouble()
        val switchMovement = max(junction, Signals.maxStep(after, 0, rate / 2))
        assertTrue(switchMovement <= 1.5 * ownMovement, "the switch jumps by $switchMovement, the sounds move by $ownMovement")

        // The old sound is still there right after the change, not cut.
        val level = Signals.rms(before, 2 * rate, 3 * rate)
        assertTrue(Signals.rms(after, 0, rate / 10) > 0.7 * level)
    }

    @Test
    fun `a gain change is smoothed over about 200 ms`() {
        val steady = mixer(8L, SleepSound.BROWN_NOISE to 1f)
        val changed = mixer(8L, SleepSound.BROWN_NOISE to 1f)
        Signals.render(steady, 2.0)
        Signals.render(changed, 2.0)
        changed.setLayers(listOf(SoundLayer(SleepSound.BROWN_NOISE, 0.2f)))
        val reference = Signals.render(steady, 1.0)
        val quieter = Signals.render(changed, 1.0)
        fun gainAt(seconds: Double): Double {
            val at = (seconds * rate).toInt()
            return Signals.rms(quieter, at, at + 96) / Signals.rms(reference, at, at + 96)
        }
        assertTrue(gainAt(0.002) > 0.9, "the gain dropped at once: ${gainAt(0.002)}")
        assertTrue(gainAt(0.05) in 0.3..0.9, "no smooth movement: ${gainAt(0.05)}")
        assertTrue(abs(gainAt(0.4) - 0.2) < 0.01, "not settled after 400 ms: ${gainAt(0.4)}")
        var last = 1.01
        for (ms in 0..300 step 10) {
            val g = gainAt(ms / 1000.0)
            assertTrue(g <= last + 0.01, "gain went back up at $ms ms")
            last = g
        }
    }

    @Test
    fun `master gain is smoothed and a fade to zero ends silent`() {
        val steady = mixer(9L, SleepSound.BROWN_NOISE to 1f)
        val faded = mixer(9L, SleepSound.BROWN_NOISE to 1f)
        val before = Signals.render(faded, 2.0)
        Signals.render(steady, 2.0)
        faded.masterGain = 0f
        val reference = Signals.render(steady, 1.0)
        val after = Signals.render(faded, 1.0)
        val first = Signals.rms(after, 0, 96) / Signals.rms(reference, 0, 96)
        assertTrue(first > 0.9, "master jumped: $first")
        assertTrue(Signals.maxStep(after, 0, rate / 2) <= 1.2 * Signals.maxStep(before, rate, 2 * rate))
        assertTrue(Signals.rms(after, rate / 4, rate / 2) < 0.05 * Signals.rms(reference, rate / 4, rate / 2))
        assertEquals(0.0, Signals.rms(after, (0.8 * rate).toInt(), rate))
        assertTrue(faded.isSilent)
        assertEquals(0f, faded.masterGain)
    }

    @Test
    fun `an app-driven fade-out through master gain never zips`() {
        val m = mixer(12L, SleepSound.BROWN_NOISE to 1f)
        val before = Signals.render(m, 2.0)
        val block = 960
        val buffer = FloatArray(2 * block)
        val out = FloatArray(2 * 5 * rate)
        val blocks = 5 * rate / block
        for (b in 0 until blocks) {
            m.masterGain = FadeCurve.fadeOut(b * 20L, 4_000L)
            m.render(buffer, block)
            buffer.copyInto(out, 2 * b * block)
        }
        assertTrue(Signals.maxStep(out, 0, 5 * rate) <= 1.2 * Signals.maxStep(before, rate, 2 * rate))
        assertEquals(0.0, Signals.rms(out, (4.6 * rate).toInt(), 5 * rate))
        assertTrue(m.isSilent)
    }

    @Test
    fun `settings made before the first render apply at once`() {
        val m = SoundMixer(13L)
        m.masterGain = 0f
        m.channelMode = ChannelMode.LEFT_ONLY
        m.setLayers(listOf(SoundLayer(SleepSound.PINK_NOISE, 1f)))
        val silent = Signals.render(m, 1.0)
        assertEquals(0.0, Signals.peak(silent))
        m.masterGain = 1f
        val audio = Signals.render(m, 1.0)
        assertTrue(Signals.rms(Signals.channel(audio, 0, rate / 2)) > 0.02)
        assertEquals(0.0, Signals.rms(Signals.channel(audio, 1)))
    }

    @Test
    fun `left only and right only silence the other side`() {
        val m = SoundMixer(14L)
        m.channelMode = ChannelMode.LEFT_ONLY
        m.setLayers(listOf(SoundLayer(SleepSound.RAIN, 1f), SoundLayer(SleepSound.FAN, 0.5f)))
        val left = Signals.render(m, 2.0)
        assertTrue(left.indices.filter { it % 2 == 1 }.all { left[it] == 0f }, "right channel not silent")
        assertTrue(Signals.rms(Signals.channel(left, 0, rate)) > 0.02)

        m.channelMode = ChannelMode.RIGHT_ONLY
        Signals.render(m, 0.1) // the 50 ms ramp
        val right = Signals.render(m, 2.0)
        assertTrue(right.indices.filter { it % 2 == 0 }.all { right[it] == 0f }, "left channel not silent")
        assertTrue(Signals.rms(Signals.channel(right, 1)) > 0.02)
    }

    @Test
    fun `mono gives identical channels`() {
        val m = mixer(15L, SleepSound.OCEAN to 1f, SleepSound.WIND to 0.6f)
        Signals.render(m, 0.5)
        m.channelMode = ChannelMode.MONO
        Signals.render(m, 0.1)
        val audio = Signals.render(m, 3.0)
        for (f in 0 until audio.size / 2) assertEquals(audio[2 * f], audio[2 * f + 1], "frame $f differs")
        assertTrue(Signals.rms(audio) > 0.02)
    }

    @Test
    fun `stereo keeps two different channels`() {
        val audio = Signals.render(mixer(16L, SleepSound.RAIN to 1f), 2.0)
        val r = Signals.correlation(Signals.channel(audio, 0, rate), Signals.channel(audio, 1, rate))
        assertTrue(abs(r) < 0.9, "channels correlate at $r")
    }

    @Test
    fun `more than three layers keeps the first three and a repeated sound keeps its first entry`() {
        val four = mixer(17L, SleepSound.RAIN to 0.5f, SleepSound.FIRE to 1f, SleepSound.FAN to 0.3f, SleepSound.WIND to 1f)
        val three = mixer(17L, SleepSound.RAIN to 0.5f, SleepSound.FIRE to 1f, SleepSound.FAN to 0.3f)
        assertContentEquals(Signals.render(three, 3.0), Signals.render(four, 3.0))

        val repeated = mixer(18L, SleepSound.RAIN to 0.5f, SleepSound.RAIN to 1f, SleepSound.FIRE to 1f)
        val once = mixer(18L, SleepSound.RAIN to 0.5f, SleepSound.FIRE to 1f)
        assertContentEquals(Signals.render(once, 2.0), Signals.render(repeated, 2.0))
    }

    @Test
    fun `isSilent follows the layers through their fades`() {
        val m = SoundMixer(19L)
        assertTrue(m.isSilent)
        m.setLayers(listOf(SoundLayer(SleepSound.STREAM, 1f)))
        assertFalse(m.isSilent)
        Signals.render(m, 2.0)
        assertFalse(m.isSilent)
        m.setLayers(emptyList())
        assertFalse(m.isSilent)
        Signals.render(m, 1.0)
        assertFalse(m.isSilent, "silent before the fade-out finished")
        Signals.render(m, 0.6)
        assertTrue(m.isSilent)
        assertEquals(0.0, Signals.peak(Signals.render(m, 0.5)))

        // A layer at gain 0 is silent too.
        val muted = mixer(20L, SleepSound.FIRE to 0f)
        Signals.render(muted, 0.1)
        assertTrue(muted.isSilent)
    }

    @Test
    fun `the same seed and calls give the same output whatever the block size`() {
        fun run(blockSizes: () -> Int): FloatArray {
            val m = mixer(21L, SleepSound.RAIN to 0.8f, SleepSound.FIRE to 0.6f)
            val total = 3 * rate
            val out = FloatArray(2 * total)
            val buffer = FloatArray(2 * 4096)
            var done = 0
            while (done < total) {
                if (done == rate) m.setLayers(listOf(SoundLayer(SleepSound.OCEAN, 1f), SoundLayer(SleepSound.FIRE, 0.3f)))
                if (done == 2 * rate) m.masterGain = 0.5f
                // Every run stops exactly at the one- and two-second marks, where the calls happen.
                val nextMark = if (done < rate) rate else if (done < 2 * rate) 2 * rate else total
                val n = minOf(blockSizes(), nextMark - done)
                m.render(buffer, n)
                buffer.copyInto(out, 2 * done, 0, 2 * n)
                done += n
            }
            return out
        }
        val random = Random(3)
        val even = run { 960 }
        val ragged = run { random.nextInt(1, 4096) }
        assertContentEquals(even, ragged)
        assertContentEquals(even, run { 960 })
        val other = SoundMixer(22L).apply { setLayers(listOf(SoundLayer(SleepSound.RAIN, 0.8f))) }
        assertFalse(even.copyOf(2 * rate).contentEquals(Signals.render(other, 1.0)))
    }

    @Test
    fun `out-of-range gains are clamped`() {
        val m = SoundMixer(23L)
        m.masterGain = 3f
        assertEquals(1f, m.masterGain)
        m.masterGain = Float.NaN
        assertEquals(0f, m.masterGain)
        m.masterGain = 1f
        m.setLayers(listOf(SoundLayer(SleepSound.PINK_NOISE, 5f)))
        val loud = Signals.render(m, 3.0)
        val reference = Signals.render(mixer(23L, SleepSound.PINK_NOISE to 1f), 3.0)
        assertContentEquals(reference, loud)
    }
}
