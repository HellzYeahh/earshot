package dev.earshot.dsp.sleep

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns any decoded recording into a seamless loop the mixer can play: the user's own sounds,
 * which are rarely made to loop. The same steps `tools/sleep-sounds/make_loops.py` applies to the
 * bundled recordings, done on the phone:
 *
 * 1. Resampled to 48 kHz stereo (Catmull-Rom interpolation; a short averaging pre-filter above
 *    48 kHz so a 96 kHz file doesn't alias). Mono becomes the same on both sides; more than two
 *    channels keep the first two.
 * 2. At most [MAX_SECONDS] long: a whole night loops it, and memory holds it decoded.
 * 3. The last [CROSSFADE_SECONDS] are blended into the start with an equal-power curve, so the end
 *    runs straight into the beginning: no click, no gap, whatever the file did.
 * 4. DC removed and levelled to about the bundled sounds' loudness, peaks under -1 dBFS, at most
 *    +30 dB of gain so a near-silent file isn't turned into hiss.
 *
 * Pure and allocation-heavy by design: it runs once per sound, off the audio thread.
 */
object LoopMaker {
    const val MAX_SECONDS = 90
    const val MIN_SECONDS = 5
    const val CROSSFADE_SECONDS = 3.0
    const val TARGET_RMS_DB = -20.0
    private const val PEAK_CEILING = 0.891 // -1 dBFS
    private const val MAX_GAIN_DB = 30.0

    /** How many source frames [make] uses at most from a file at [rate] (decode no more than this). */
    fun framesNeeded(rate: Int): Int = ((MAX_SECONDS + CROSSFADE_SECONDS + 1) * rate).toInt()

    /**
     * [interleaved]: 16-bit PCM with [channels] channels at [rate] Hz. Returns a 48 kHz stereo
     * loop, or null when the sound is shorter than [MIN_SECONDS] or silent.
     */
    fun make(interleaved: ShortArray, channels: Int, rate: Int): LoopPcm? {
        require(channels >= 1 && rate > 0)
        val frames = interleaved.size / channels
        if (frames.toDouble() / rate < MIN_SECONDS) return null
        val out = SleepSounds.SAMPLE_RATE
        val total = floor((frames - 1).toDouble() * out / rate).toInt()
        val fade = min((CROSSFADE_SECONDS * out).toInt(), total / 4)
        val length = min(MAX_SECONDS * out, total - fade)
        if (length < MIN_SECONDS * out) return null

        val left = resample(interleaved, channels, 0, rate, out, length + fade)
        val right = if (channels == 1) left.copyOf() else resample(interleaved, channels, 1, rate, out, length + fade)
        for (ch in arrayOf(left, right)) seamless(ch, length, fade)
        val l = left.copyOf(length)
        val r = right.copyOf(length)
        removeDc(l)
        removeDc(r)
        val gain = levelGain(l, r) ?: return null
        val pcm = ShortArray(length * 2)
        for (i in 0 until length) {
            pcm[2 * i] = toShort(l[i] * gain)
            pcm[2 * i + 1] = toShort(r[i] * gain)
        }
        return LoopPcm(pcm, out)
    }

    /** One channel, [count] frames at [toRate], from interleaved shorts at [fromRate]. */
    private fun resample(src: ShortArray, channels: Int, channel: Int, fromRate: Int, toRate: Int, count: Int): FloatArray {
        val frames = src.size / channels
        // Above 48 kHz: average neighbours first (a crude low-pass) so the interpolation doesn't alias.
        val taps = max(1, (fromRate.toDouble() / toRate).roundToInt())
        fun at(i: Int): Float {
            val k = i.coerceIn(0, frames - 1)
            if (taps == 1) return src[k * channels + channel] / 32768f
            var sum = 0f
            for (t in 0 until taps) sum += src[(k + t).coerceAtMost(frames - 1) * channels + channel]
            return sum / taps / 32768f
        }
        val step = fromRate.toDouble() / toRate
        val out = FloatArray(count)
        if (fromRate == toRate && taps == 1) {
            for (i in 0 until count) out[i] = at(i)
            return out
        }
        for (i in 0 until count) {
            val pos = i * step
            val k = pos.toInt()
            val t = (pos - k).toFloat()
            val p0 = at(k - 1); val p1 = at(k); val p2 = at(k + 1); val p3 = at(k + 2)
            // Catmull-Rom: passes through the samples, smooth between them.
            out[i] = p1 + 0.5f * t * (p2 - p0 + t * (2f * p0 - 5f * p1 + 4f * p2 - p3 + t * (3f * (p1 - p2) + p3 - p0)))
        }
        return out
    }

    /** Blends the [fade] frames after [length] into the first [fade] frames (equal power). */
    private fun seamless(x: FloatArray, length: Int, fade: Int) {
        for (i in 0 until fade) {
            val t = i.toDouble() / fade * PI / 2
            x[i] = (x[i] * sin(t) + x[length + i] * cos(t)).toFloat()
        }
    }

    private fun removeDc(x: FloatArray) {
        var sum = 0.0
        for (v in x) sum += v
        val mean = (sum / x.size).toFloat()
        for (i in x.indices) x[i] -= mean
    }

    /** The gain to the target level, capped by the peak ceiling and +30 dB; null for silence. */
    private fun levelGain(l: FloatArray, r: FloatArray): Float? {
        var energy = 0.0
        var peak = 0f
        for (i in l.indices) {
            energy += l[i] * l[i] + r[i] * r[i]
            peak = max(peak, max(abs(l[i]), abs(r[i])))
        }
        val rms = sqrt(energy / (2.0 * l.size))
        if (rms < 1e-6 || peak == 0f) return null
        var gain = 10.0.pow(TARGET_RMS_DB / 20) / rms
        gain = min(gain, 10.0.pow(MAX_GAIN_DB / 20))
        gain = min(gain, PEAK_CEILING / peak)
        return gain.toFloat()
    }

    private fun toShort(v: Float): Short = (v.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
}
