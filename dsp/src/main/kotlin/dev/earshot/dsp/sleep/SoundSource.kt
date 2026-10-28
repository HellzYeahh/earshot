package dev.earshot.dsp.sleep

/**
 * A stateful generator of interleaved stereo float samples in [-1, 1]. Not thread-safe: one
 * thread renders it.
 *
 * Every source in this package is deterministic for its seed and independent of how the frames
 * are requested: rendering 1 000 frames in one call or in ten calls of 100 gives the same samples.
 * A source starts at full level on its first frame; [SoundMixer] fades it in, and an alarm played
 * on its own needs the app's fade-in.
 */
interface SoundSource {
    /** Writes [frames] stereo frames (2 floats each, L then R) into [out] starting at [offset]. */
    fun render(out: FloatArray, offset: Int, frames: Int)
}
