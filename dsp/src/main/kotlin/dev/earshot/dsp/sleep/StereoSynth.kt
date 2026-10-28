package dev.earshot.dsp.sleep

/**
 * The shared frame loop of every synthesised sound.
 *
 * Subclasses produce one stereo frame at a time in [frame] (into [left] and [right]) and do their
 * slow work in [control], which runs before the first frame and then every [CONTROL_FRAMES]
 * frames. The loop applies the sound's loudness ([outputGain]), its stereo [width] (mid/side) and
 * a soft safety clip, so no sound can leave [-1, 1] however its random events pile up.
 */
internal abstract class StereoSynth(protected val sampleRate: Int) : SoundSource {
    protected var left = 0.0
    protected var right = 0.0

    /**
     * Brings the sound to its loudness target; set once in the subclass's `init`. Every sound is
     * calibrated to about −18 LUFS (ITU-R BS.1770, measured on the exported files), so switching
     * sounds does not jump in level. Fire and ocean sit 1–2 dB lower: their crackles and wave
     * crests set the peak, which is kept below about −1 dBFS.
     */
    protected var outputGain = 1.0

    /**
     * 1 = as generated, 0 = mono. Below 1 the side signal is scaled down, which narrows a fully
     * decorrelated noise towards a frontal image (a fireplace or a fan sits in front of you,
     * rain is all around).
     */
    protected var width = 1.0

    private var countdown = 0

    final override fun render(out: FloatArray, offset: Int, frames: Int) {
        require(frames >= 0 && offset >= 0 && offset + 2 * frames <= out.size) {
            "render($offset, $frames) does not fit a buffer of ${out.size}"
        }
        val gain = outputGain
        val w = width
        var i = offset
        val end = offset + 2 * frames
        while (i < end) {
            if (countdown == 0) {
                control()
                countdown = CONTROL_FRAMES
            }
            countdown--
            frame()
            var l = left * gain
            var r = right * gain
            if (w != 1.0) {
                val mid = 0.5 * (l + r)
                val side = 0.5 * w * (l - r)
                l = mid + side
                r = mid - side
            }
            out[i] = SoftClip.source(l).toFloat()
            out[i + 1] = SoftClip.source(r).toFloat()
            i += 2
        }
    }

    /** Slow updates; runs before the first frame and then every [CONTROL_FRAMES] frames. */
    protected abstract fun control()

    /** Produces one frame into [left] and [right]. */
    protected abstract fun frame()
}
