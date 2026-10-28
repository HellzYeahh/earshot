package dev.earshot.dsp.sleep

/** The wake-up sounds. [storedId] is frozen: it is saved in user settings. */
enum class AlarmTone(val storedId: String) {
    CHIME("chime"),
    BIRDS("birds"),
    CLASSIC("classic"),
    ;

    companion object {
        /** The tone saved as [id], or null for an unknown id. */
        fun fromStoredId(id: String): AlarmTone? = entries.firstOrNull { it.storedId == id }
    }
}

/** Creates the alarm generators. */
object AlarmTones {
    /**
     * A generator for [tone] that loops forever at full level; the app applies the fade-in.
     *
     * - [AlarmTone.CHIME]: a soft four-note bell arpeggio every four seconds, its order varying.
     * - [AlarmTone.BIRDS]: three synthetic birds singing over a quiet outdoor bed.
     * - [AlarmTone.CLASSIC]: a clear two-tone beep pattern, the loud last resort. Its pattern is
     *   the same for every seed (only the oscillator's start phase follows the seed).
     */
    fun create(tone: AlarmTone, seed: Long, sampleRate: Int = SleepSounds.SAMPLE_RATE): SoundSource {
        require(sampleRate in SleepSounds.MIN_RATE..SleepSounds.MAX_RATE) { "Unsupported sample rate $sampleRate" }
        // A recording when the app has one (real birdsong for BIRDS), else the synthesis.
        SleepSounds.recordings?.loop(RecordingLibrary.ALARM_PREFIX + tone.storedId)
            ?.takeIf { it.sampleRate == sampleRate }
            ?.let { return LoopSource(it, seed) }
        return when (tone) {
            AlarmTone.CHIME -> ChimeTone(seed, sampleRate)
            AlarmTone.BIRDS -> BirdsTone(seed, sampleRate)
            AlarmTone.CLASSIC -> ClassicTone(seed, sampleRate)
        }
    }
}
