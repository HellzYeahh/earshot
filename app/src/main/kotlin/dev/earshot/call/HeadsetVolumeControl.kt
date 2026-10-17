package dev.earshot.call

/**
 * Raises the headphones' own volume over BMAP. Kept as a tiny adapter so this package does not
 * depend on a `HeadphoneRepository.setVolume` that may or may not exist yet: the integrator wires
 * it in `AppContainer`, e.g. `HeadsetVolumeControl { pct -> headphones.setVolume(pct); true }`
 * (converting the percentage to the repository's units there).
 */
fun interface HeadsetVolumeControl {
    /**
     * Set the headset volume to [percent] (0..100) of its range.
     * @return true when a command was sent, false when not supported.
     */
    suspend fun setVolumePercent(percent: Int): Boolean

    companion object {
        /** Default until the repository exposes a volume setter. */
        val None = HeadsetVolumeControl { false }
    }
}
