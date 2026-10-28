package dev.earshot.dsp.sleep

/** Creates the sound generators. */
object SleepSounds {
    /** The rate the Sleep player's `AudioTrack` runs at. */
    const val SAMPLE_RATE = 48_000

    /**
     * A new generator for [sound]. The same [seed] always gives the same audio (the tests rely on
     * it); the player passes a fresh seed per night so no two nights are identical.
     *
     * Every recipe is written in hertz and seconds, so other rates work, but only 48 kHz is tuned
     * and tested.
     */
    fun create(sound: SleepSound, seed: Long, sampleRate: Int = SAMPLE_RATE): SoundSource {
        require(sampleRate in MIN_RATE..MAX_RATE) { "Unsupported sample rate $sampleRate" }
        // A real recording when the app has one decoded at this rate; the synthesis otherwise
        // (the noises always, and everything in plain JVM tests).
        recordings?.loop(sound.storedId)?.takeIf { it.sampleRate == sampleRate }?.let { return LoopSource(it, seed) }
        return synthesised(sound, seed, sampleRate)
    }

    /**
     * A source for any layer id: a built-in sound (see [create] above), or the user's own sound
     * from [recordings]. An own sound that isn't decoded yet plays silence (the app decodes before
     * it plays, so this is only a guard).
     */
    fun create(id: String, seed: Long, sampleRate: Int = SAMPLE_RATE): SoundSource {
        SleepSound.fromStoredId(id)?.let { return create(it, seed, sampleRate) }
        recordings?.loop(id)?.takeIf { it.sampleRate == sampleRate }?.let { return LoopSource(it, seed) }
        return Silence
    }

    private object Silence : SoundSource {
        override fun render(out: FloatArray, offset: Int, frames: Int) = out.fill(0f, offset, offset + frames * 2)
    }

    /**
     * Recorded sounds, installed once by the app. Null (the default) plays everything synthesised.
     * Read on the audio thread, so it is volatile and its [RecordingLibrary.loop] never blocks.
     */
    @Volatile
    var recordings: RecordingLibrary? = null

    /** True when [sound] plays a recording (the app has a file for it), false when it is synthesised. */
    fun isRecorded(sound: SleepSound): Boolean = recordings?.ids?.contains(sound.storedId) == true

    /** The synthesised version, whatever [recordings] holds. */
    fun synthesised(sound: SleepSound, seed: Long, sampleRate: Int = SAMPLE_RATE): SoundSource {
        require(sampleRate in MIN_RATE..MAX_RATE) { "Unsupported sample rate $sampleRate" }
        return when (sound) {
            SleepSound.WHITE_NOISE -> ColouredNoise(ColouredNoise.Colour.WHITE, seed, sampleRate)
            SleepSound.PINK_NOISE -> ColouredNoise(ColouredNoise.Colour.PINK, seed, sampleRate)
            SleepSound.BROWN_NOISE -> ColouredNoise(ColouredNoise.Colour.BROWN, seed, sampleRate)
            SleepSound.RAIN -> RainSynth(seed, sampleRate, heavy = false)
            SleepSound.HEAVY_RAIN -> RainSynth(seed, sampleRate, heavy = true)
            SleepSound.OCEAN -> OceanSynth(seed, sampleRate)
            SleepSound.STREAM -> StreamSynth(seed, sampleRate)
            SleepSound.WIND -> WindSynth(seed, sampleRate)
            SleepSound.FIRE -> FireSynth(seed, sampleRate)
            SleepSound.FAN -> FanSynth(seed, sampleRate)
        }
    }

    internal const val MIN_RATE = 22_050
    internal const val MAX_RATE = 192_000
}
