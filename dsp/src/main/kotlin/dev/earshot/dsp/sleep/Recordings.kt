package dev.earshot.dsp.sleep

import java.util.SplittableRandom

/**
 * A recorded sound, decoded: interleaved stereo 16-bit PCM at [sampleRate]. The bundled files are
 * prepared as seamless loops (`tools/sleep-sounds/make_loops.py` crossfades the end into the
 * start), so playing one end-to-start never clicks.
 */
class LoopPcm(val samples: ShortArray, val sampleRate: Int) {
    init {
        require(samples.size >= 4 && samples.size % 2 == 0) { "A loop needs whole stereo frames" }
    }

    val frames: Int get() = samples.size / 2
}

/**
 * Where recorded sounds come from. The app installs one ([SleepSounds.recordings]) that decodes
 * the files bundled in the APK. Ids are a [SleepSound.storedId], or [ALARM_PREFIX] plus an
 * [AlarmTone.storedId] for alarm recordings.
 *
 * [loop] must never block: the mixer asks on the audio thread. The app decodes ahead of time and
 * [loop] returns only what is already in memory; null means "use the synthesised version".
 */
interface RecordingLibrary {
    /** The ids it has a file for, decoded or not. */
    val ids: Set<String>

    fun loop(id: String): LoopPcm?

    companion object {
        const val ALARM_PREFIX = "alarm_"
    }
}

/**
 * Plays a [LoopPcm] round and round, starting at a point the [seed] picks, so no two nights
 * start the same way. Allocation-free while rendering.
 */
class LoopSource(private val pcm: LoopPcm, seed: Long) : SoundSource {
    private var frame = SplittableRandom(seed).nextInt(pcm.frames)

    override fun render(out: FloatArray, offset: Int, frames: Int) {
        val samples = pcm.samples
        val total = pcm.frames
        var o = offset
        repeat(frames) {
            val i = frame * 2
            out[o++] = samples[i] * SCALE
            out[o++] = samples[i + 1] * SCALE
            if (++frame == total) frame = 0
        }
    }

    private companion object {
        const val SCALE = 1f / 32768f
    }
}
