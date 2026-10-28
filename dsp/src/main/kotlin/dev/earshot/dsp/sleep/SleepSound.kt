package dev.earshot.dsp.sleep

/** How the Sleep tab groups its sounds. */
enum class SleepSoundGroup { NOISE, WATER, NATURE, HOME }

/**
 * Every sound the Sleep tab can play. The noises are synthesised as they play; the nature and
 * home sounds play a bundled real recording (a seamless loop, see [RecordingLibrary]) when the app
 * has one, and fall back to their synthesised version otherwise.
 *
 * [storedId] is frozen: it is saved in user settings, so never rename one; add new entries instead.
 */
enum class SleepSound(val storedId: String, val group: SleepSoundGroup) {
    WHITE_NOISE("white", SleepSoundGroup.NOISE),
    PINK_NOISE("pink", SleepSoundGroup.NOISE),
    BROWN_NOISE("brown", SleepSoundGroup.NOISE),
    RAIN("rain", SleepSoundGroup.WATER),
    HEAVY_RAIN("heavy_rain", SleepSoundGroup.WATER),
    OCEAN("ocean", SleepSoundGroup.WATER),
    STREAM("stream", SleepSoundGroup.WATER),
    WIND("wind", SleepSoundGroup.NATURE),
    FIRE("fire", SleepSoundGroup.HOME),
    FAN("fan", SleepSoundGroup.HOME),
    ;

    companion object {
        /** The sound saved as [id], or null for an unknown id (from a newer or older version). */
        fun fromStoredId(id: String): SleepSound? = entries.firstOrNull { it.storedId == id }
    }
}
