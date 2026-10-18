package dev.earshot.shortcut

/** Where the address of the headset that pressed its button came from (logged, shown nowhere else). */
enum class HeadsetSource { INTENT, ACTIVE_HFP, HFP_ONLY, HFP_SAVED, HFP_FIRST, SAVED }

data class ResolvedHeadset(val address: String, val source: HeadsetSource)

/**
 * Which headset pressed. AOSP's `HeadsetSystemInterface.activateVoiceRecognition` sends a bare
 * `VOICE_COMMAND` (no extras, no device), but just before it `HeadsetService
 * .startVoiceRecognitionByHeadset` makes the presser the *active* HFP device, which audio exposes
 * as the `TYPE_BLUETOOTH_SCO` output. So, in order:
 *  1. a Bluetooth device in the intent extras (some vendors add one; never relied on);
 *  2. the active HFP device ([activeHfpAddress], the SCO output's address);
 *  3. the connected HFP device when there is exactly one; with several, the saved headphones if
 *     they are among them, else the first;
 *  4. Earshot's saved headphones.
 * Pure: the Android lookups feed it, the tests drive it.
 */
object HeadsetResolution {
    fun resolve(
        intentAddress: String?,
        activeHfpAddress: String?,
        connectedHfp: List<String>,
        savedAddress: String?,
    ): ResolvedHeadset? {
        intentAddress?.takeIf(::isAddress)?.let { return ResolvedHeadset(it.uppercase(), HeadsetSource.INTENT) }
        activeHfpAddress?.takeIf(::isAddress)?.let { return ResolvedHeadset(it.uppercase(), HeadsetSource.ACTIVE_HFP) }
        val connected = connectedHfp.filter(::isAddress).map { it.uppercase() }.distinct()
        when (connected.size) {
            0 -> Unit
            1 -> return ResolvedHeadset(connected.single(), HeadsetSource.HFP_ONLY)
            else -> {
                savedAddress?.let { s -> connected.firstOrNull { it.equals(s, ignoreCase = true) } }
                    ?.let { return ResolvedHeadset(it, HeadsetSource.HFP_SAVED) }
                return ResolvedHeadset(connected.first(), HeadsetSource.HFP_FIRST)
            }
        }
        return savedAddress?.takeIf(::isAddress)?.let { ResolvedHeadset(it.uppercase(), HeadsetSource.SAVED) }
    }

    private val ADDRESS = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

    /** `AA:BB:CC:DD:EE:FF`. Audio-device addresses can be empty, and `00:00:…` means "unknown". */
    fun isAddress(text: String?): Boolean =
        text != null && ADDRESS.matches(text) && text != "00:00:00:00:00:00"
}
