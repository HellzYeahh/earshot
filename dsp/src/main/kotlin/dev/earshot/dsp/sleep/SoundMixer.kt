package dev.earshot.dsp.sleep

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mixes up to [MAX_LAYERS] sleep sounds into one interleaved stereo stream.
 *
 * - Adding or removing a layer cross-fades over [CROSSFADE_MS] with an equal-power curve, so a
 *   change of sound never cuts and the overall loudness holds through the swap.
 * - Layer gains and [masterGain] are smoothed (time constant [GAIN_SMOOTHING_MS] / 3, so a change
 *   has settled within about 200 ms): a slider dragged fast never zips or clicks.
 * - A [ChannelMode] change ramps over [CHANNEL_RAMP_MS].
 * - [OutputLimiter] keeps every output sample strictly inside ±[OutputLimiter.CEILING] (0.98),
 *   whatever the mix.
 *
 * Threading: [render] runs on one thread (the audio thread). [setLayers], [masterGain] and
 * [channelMode] may be called from another thread; changes are picked up at the start of the next
 * [render] and gain changes at the next 64-frame boundary. Settings made before the first render
 * apply at once (no fade from the default master gain of 1), but layers always fade in.
 *
 * Deterministic: the same seed and the same calls give the same output. Ramps run on a fixed
 * 64-frame grid that carries across calls, so the block size the app pulls does not matter either.
 */
class SoundMixer(seed: Long, val sampleRate: Int = SleepSounds.SAMPLE_RATE) {
    companion object {
        const val MAX_LAYERS = 3
        const val CROSSFADE_MS = 1_500
        const val GAIN_SMOOTHING_MS = 200
        const val CHANNEL_RAMP_MS = 50

        private const val GRID = 64
        private const val POOL = MAX_LAYERS * 3
        private const val SNAP = 1e-4
        private val HALF_POWER = 1.0 / sqrt(2.0)
    }

    private val baseSeed = seed
    private var created = 0L

    private val pending = AtomicReference<List<SoundLayer>?>(null)

    @Volatile private var masterTarget = 1f

    @Volatile private var modeTarget = ChannelMode.STEREO

    @Volatile private var silent = true

    private val voices = arrayOfNulls<Voice>(POOL)
    private val scratch = FloatArray(2 * GRID)
    private val mix = DoubleArray(2 * GRID)
    private val limiter = OutputLimiter(sampleRate)

    private val fadeStep = GRID / (CROSSFADE_MS * sampleRate / 1000.0)
    private val smoothing = 1.0 - exp(-GRID / (GAIN_SMOOTHING_MS / 3.0 * sampleRate / 1000.0))
    private val matrixStep = GRID / (CHANNEL_RAMP_MS * sampleRate / 1000.0)

    private var started = false
    private var gridPosition = 0
    private var masterSmoothed = 1.0
    private var masterStart = 1.0
    private var masterEnd = 1.0
    private var matrixMode = ChannelMode.STEREO
    private var matrixProgress = 1.0
    private val matrixFrom = DoubleArray(4)
    private val matrixStart = DoubleArray(4)
    private val matrixEnd = DoubleArray(4)

    init {
        require(sampleRate in SleepSounds.MIN_RATE..SleepSounds.MAX_RATE) { "Unsupported sample rate $sampleRate" }
        coefficients(ChannelMode.STEREO, matrixEnd)
        coefficients(ChannelMode.STEREO, matrixStart)
    }

    /**
     * The layers to play. A sound already playing keeps playing (only its gain moves); a new one
     * fades in, one no longer listed fades out (and one brought back mid-fade turns round). If a
     * sound is listed twice its first entry wins; after that, more than [MAX_LAYERS] layers keeps
     * the first three. Up to six layers can be fading out at once; changing the whole mix more
     * often than that within one cross-fade cuts the quietest fading layer short.
     */
    fun setLayers(layers: List<SoundLayer>) {
        pending.set(layers.distinctBy { it.id }.take(MAX_LAYERS))
        if (layers.isNotEmpty()) silent = false
    }

    /** 0..1, linear amplitude, smoothed; the app drives its fades (see [FadeCurve]) through it. */
    var masterGain: Float
        get() = masterTarget
        set(value) {
            val v = if (value.isNaN()) 0f else value.coerceIn(0f, 1f)
            masterTarget = v
            if (v > 0f) silent = false
        }

    var channelMode: ChannelMode
        get() = modeTarget
        set(value) {
            modeTarget = value
        }

    /**
     * True when nothing would be heard: no layer left (all removed and their fade-outs finished),
     * every remaining layer at gain 0, or the master gain settled at 0. Recomputed by each
     * [render]; a setter that could make the mix audible clears it at once.
     */
    val isSilent: Boolean
        get() = silent && pending.get() == null

    /** Writes [frames] interleaved stereo frames (L, R) into [out] from index 0. */
    fun render(out: FloatArray, frames: Int) {
        require(frames >= 0 && 2 * frames <= out.size) { "render($frames) does not fit a buffer of ${out.size}" }
        pending.getAndSet(null)?.let { apply(it) }
        if (!started) {
            started = true
            masterSmoothed = masterTarget.toDouble()
            masterStart = masterSmoothed
            masterEnd = masterSmoothed
            matrixMode = modeTarget
            coefficients(matrixMode, matrixStart)
            coefficients(matrixMode, matrixEnd)
        }
        var done = 0
        while (done < frames) {
            if (gridPosition == 0) plan()
            val n = min(GRID - gridPosition, frames - done)
            mixChunk(out, 2 * done, n)
            gridPosition += n
            if (gridPosition == GRID) gridPosition = 0
            done += n
        }
        silent = computeSilent()
    }

    private fun apply(layers: List<SoundLayer>) {
        for (v in voices) v?.fadingOut = true
        for (layer in layers) {
            val gain = if (layer.gain.isNaN()) 0.0 else layer.gain.coerceIn(0f, 1f).toDouble()
            val existing = find(layer.id)
            if (existing != null) {
                existing.fadingOut = false
                existing.target = gain
            } else {
                add(layer.id, gain)
            }
        }
    }

    private fun find(id: String): Voice? {
        for (v in voices) if (v != null && v.id == id) return v
        return null
    }

    private fun add(id: String, gain: Double) {
        var slot = voices.indexOfFirst { it == null }
        if (slot < 0) {
            // Only fading-out voices can be evicted (at most MAX_LAYERS are not fading); take the quietest.
            var quietest = Double.MAX_VALUE
            for (i in voices.indices) {
                val v = voices[i] ?: continue
                if (v.fadingOut && v.end < quietest) {
                    quietest = v.end
                    slot = i
                }
            }
        }
        val seed = Rng(baseSeed, SEED_STREAM + created++).nextLong()
        voices[slot] = Voice(id, SleepSounds.create(id, seed, sampleRate), gain)
    }

    /** Runs on each 64-frame boundary: the end values for the coming period. */
    private fun plan() {
        for (i in voices.indices) {
            val v = voices[i] ?: continue
            v.start = v.end
            v.fade = if (v.fadingOut) max(0.0, v.fade - fadeStep) else min(1.0, v.fade + fadeStep)
            v.smoothed = smooth(v.smoothed, v.target)
            v.end = equalPower(v.fade) * v.smoothed
            if (v.fadingOut && v.start == 0.0 && v.end == 0.0) voices[i] = null
        }

        masterStart = masterEnd
        masterSmoothed = smooth(masterSmoothed, masterTarget.toDouble())
        masterEnd = masterSmoothed

        matrixEnd.copyInto(matrixStart)
        val mode = modeTarget
        if (mode != matrixMode) {
            matrixEnd.copyInto(matrixFrom)
            matrixMode = mode
            matrixProgress = 0.0
        }
        if (matrixProgress < 1.0) {
            matrixProgress = min(1.0, matrixProgress + matrixStep)
            if (matrixProgress >= 1.0) {
                coefficients(mode, matrixEnd)
            } else {
                coefficients(mode, matrixEnd)
                for (k in 0 until 4) matrixEnd[k] = matrixFrom[k] + (matrixEnd[k] - matrixFrom[k]) * matrixProgress
            }
        }
    }

    private fun mixChunk(out: FloatArray, outOffset: Int, n: Int) {
        val t0 = gridPosition
        java.util.Arrays.fill(mix, 0, 2 * n, 0.0)
        val masterSilent = masterStart == 0.0 && masterEnd == 0.0
        if (!masterSilent) {
            for (v in voices) {
                if (v == null || (v.start == 0.0 && v.end == 0.0)) continue
                v.source.render(scratch, 0, n)
                val step = (v.end - v.start) / GRID
                var g = v.start + step * t0
                for (j in 0 until n) {
                    mix[2 * j] += scratch[2 * j] * g
                    mix[2 * j + 1] += scratch[2 * j + 1] * g
                    g += step
                }
            }
        }

        val masterStep = (masterEnd - masterStart) / GRID
        var m = masterStart + masterStep * t0
        val llStep = (matrixEnd[0] - matrixStart[0]) / GRID
        val lrStep = (matrixEnd[1] - matrixStart[1]) / GRID
        val rlStep = (matrixEnd[2] - matrixStart[2]) / GRID
        val rrStep = (matrixEnd[3] - matrixStart[3]) / GRID
        var ll = matrixStart[0] + llStep * t0
        var lr = matrixStart[1] + lrStep * t0
        var rl = matrixStart[2] + rlStep * t0
        var rr = matrixStart[3] + rrStep * t0
        for (j in 0 until n) {
            val l = mix[2 * j] * m
            val r = mix[2 * j + 1] * m
            limiter.process(ll * l + lr * r, rl * l + rr * r)
            out[outOffset + 2 * j] = limiter.left.toFloat()
            out[outOffset + 2 * j + 1] = limiter.right.toFloat()
            m += masterStep
            ll += llStep
            lr += lrStep
            rl += rlStep
            rr += rrStep
        }
    }

    private fun computeSilent(): Boolean {
        if (masterEnd == 0.0 && masterTarget == 0f) return true
        for (v in voices) {
            if (v == null) continue
            if (v.end != 0.0 || (!v.fadingOut && v.target != 0.0)) return false
        }
        return true
    }

    private fun smooth(current: Double, target: Double): Double {
        val next = current + (target - current) * smoothing
        return if (abs(target - next) < SNAP) target else next
    }

    /** sin(π/2·x): with fade-in x and fade-out 1 − x the two powers always add up to 1. */
    private fun equalPower(x: Double): Double = if (x >= 1.0) 1.0 else if (x <= 0.0) 0.0 else sin(x * PI / 2.0)

    /** [ll, lr, rl, rr]: left out = ll·L + lr·R, right out = rl·L + rr·R. */
    private fun coefficients(mode: ChannelMode, into: DoubleArray) {
        val h = HALF_POWER
        when (mode) {
            ChannelMode.STEREO -> { into[0] = 1.0; into[1] = 0.0; into[2] = 0.0; into[3] = 1.0 }
            ChannelMode.MONO -> { into[0] = h; into[1] = h; into[2] = h; into[3] = h }
            ChannelMode.LEFT_ONLY -> { into[0] = h; into[1] = h; into[2] = 0.0; into[3] = 0.0 }
            ChannelMode.RIGHT_ONLY -> { into[0] = 0.0; into[1] = 0.0; into[2] = h; into[3] = h }
        }
    }

    private class Voice(val id: String, val source: SoundSource, gain: Double) {
        /** The gain the user asked for. */
        var target = gain

        /** [target] after smoothing (it starts there: a new layer's entry is the cross-fade). */
        var smoothed = gain

        /** Cross-fade position, 0 (silent) to 1 (in). */
        var fade = 0.0
        var fadingOut = false

        /** Effective gain at the start and the end of the current 64-frame period. */
        var start = 0.0
        var end = 0.0
    }
}

/** Keeps the layers' seeds apart from any other use of the mixer seed. */
private const val SEED_STREAM = 1_000L
