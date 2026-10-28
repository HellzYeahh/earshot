package dev.earshot.dsp.sleep

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Measurement helpers for the sleep-sound tests (test code only). */
internal object Signals {
    const val RATE = SleepSounds.SAMPLE_RATE

    /** Renders [seconds] of [source] in app-sized blocks (20 ms). */
    fun render(source: SoundSource, seconds: Double, block: Int = 960): FloatArray {
        val frames = (seconds * RATE).toInt()
        val out = FloatArray(2 * frames)
        var done = 0
        while (done < frames) {
            val n = min(block, frames - done)
            source.render(out, 2 * done, n)
            done += n
        }
        return out
    }

    /** Renders [seconds] of [mixer] (which always writes from index 0) in [block]-frame calls. */
    fun render(mixer: SoundMixer, seconds: Double, block: Int = 960): FloatArray {
        val frames = (seconds * RATE).toInt()
        val out = FloatArray(2 * frames)
        val buffer = FloatArray(2 * block)
        var done = 0
        while (done < frames) {
            val n = min(block, frames - done)
            mixer.render(buffer, n)
            buffer.copyInto(out, 2 * done, 0, 2 * n)
            done += n
        }
        return out
    }

    fun channel(stereo: FloatArray, ch: Int, fromFrame: Int = 0, toFrame: Int = stereo.size / 2): DoubleArray =
        DoubleArray(toFrame - fromFrame) { stereo[2 * (fromFrame + it) + ch].toDouble() }

    fun rms(x: DoubleArray): Double {
        var sum = 0.0
        for (v in x) sum += v * v
        return sqrt(sum / x.size)
    }

    fun rms(stereo: FloatArray, fromFrame: Int = 0, toFrame: Int = stereo.size / 2): Double {
        var sum = 0.0
        for (i in 2 * fromFrame until 2 * toFrame) sum += stereo[i].toDouble() * stereo[i]
        return sqrt(sum / (2 * (toFrame - fromFrame)))
    }

    fun peak(stereo: FloatArray): Double {
        var p = 0.0
        for (v in stereo) p = maxOf(p, abs(v.toDouble()))
        return p
    }

    /** Largest sample-to-sample jump on either channel between two frames. */
    fun maxStep(stereo: FloatArray, fromFrame: Int, toFrame: Int): Double {
        var m = 0.0
        for (f in maxOf(1, fromFrame) until toFrame) {
            m = maxOf(m, abs(stereo[2 * f] - stereo[2 * f - 2].toDouble()), abs(stereo[2 * f + 1] - stereo[2 * f - 1].toDouble()))
        }
        return m
    }

    /** Pearson correlation of two equal-length signals. */
    fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val n = min(a.size, b.size)
        var ma = 0.0
        var mb = 0.0
        for (i in 0 until n) {
            ma += a[i]
            mb += b[i]
        }
        ma /= n
        mb /= n
        var ab = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in 0 until n) {
            val da = a[i] - ma
            val db = b[i] - mb
            ab += da * db
            aa += da * da
            bb += db * db
        }
        return if (aa == 0.0 || bb == 0.0) 0.0 else ab / sqrt(aa * bb)
    }

    /** Welch power spectrum (Hann window, 50 % overlap), one value per FFT bin up to Nyquist. */
    fun powerSpectrum(x: DoubleArray, fftSize: Int = 8192): DoubleArray {
        val window = DoubleArray(fftSize) { 0.5 - 0.5 * cos(2.0 * PI * it / fftSize) }
        val power = DoubleArray(fftSize / 2 + 1)
        val re = DoubleArray(fftSize)
        val im = DoubleArray(fftSize)
        var segments = 0
        var start = 0
        while (start + fftSize <= x.size) {
            for (i in 0 until fftSize) {
                re[i] = x[start + i] * window[i]
                im[i] = 0.0
            }
            fft(re, im)
            for (k in power.indices) power[k] += re[k] * re[k] + im[k] * im[k]
            segments++
            start += fftSize / 2
        }
        for (k in power.indices) power[k] /= segments
        return power
    }

    /** Mean power per bin between [loHz] and [hiHz]: a power spectral density, in arbitrary units. */
    fun bandDensity(spectrum: DoubleArray, loHz: Double, hiHz: Double, fftSize: Int = 8192): Double {
        val binHz = RATE.toDouble() / fftSize
        val lo = (loHz / binHz).roundToInt()
        val hi = (hiHz / binHz).roundToInt()
        var sum = 0.0
        for (k in lo until hi) sum += spectrum[k]
        return sum / (hi - lo)
    }

    /** In-place iterative radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wr = cos(angle)
            val wi = sin(angle)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** 16-bit stereo PCM WAV. */
    fun writeWav(file: File, stereo: FloatArray) {
        file.parentFile?.mkdirs()
        val dataBytes = stereo.size * 2
        BufferedOutputStream(FileOutputStream(file)).use { out ->
            fun int32(v: Int) {
                out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
            }
            fun int16(v: Int) {
                out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            }
            out.write("RIFF".toByteArray()); int32(36 + dataBytes); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); int32(16); int16(1); int16(2); int32(RATE); int32(RATE * 4); int16(4); int16(16)
            out.write("data".toByteArray()); int32(dataBytes)
            for (v in stereo) int16((v.coerceIn(-1f, 1f) * 32767f).roundToInt())
        }
    }
}
