package dev.earshot.dsp.sleep

/**
 * One sound in the mix at [gain] (0..1, linear amplitude: 0.5 is −6 dB). Out-of-range gains are
 * clamped by the mixer. [id] is a built-in [SleepSound.storedId] or the id of the user's own sound,
 * which plays from the app's [RecordingLibrary].
 */
data class SoundLayer(val id: String, val gain: Float) {
    constructor(sound: SleepSound, gain: Float) : this(sound.storedId, gain)

    /** The built-in sound, or null for the user's own. */
    val sound: SleepSound? get() = SleepSound.fromStoredId(id)
}

/** How the mix reaches the two earbuds. */
enum class ChannelMode {
    /** As generated: rain all around, drops and crackles placed left and right. */
    STEREO,

    /** Both sides carry the same (L + R) / √2 mix. */
    MONO,

    /** For side sleepers: the whole mix, summed to mono, in the left earbud; the right is silent. */
    LEFT_ONLY,

    /** For side sleepers: the whole mix, summed to mono, in the right earbud; the left is silent. */
    RIGHT_ONLY,
}
