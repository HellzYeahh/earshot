package dev.earshot.find

/** An audio output as `AudioManager` lists it, reduced to what the match needs. */
data class OutputCandidate(val id: Int, val type: Int, val address: String)

/**
 * Which audio output is the headset Earshot shows. Strict on purpose: the tone goes to that
 * headset's own output (same address, or its LE Audio twin's) or nowhere. `A2dpProxyHolder`'s
 * "first A2DP output" would ring a car or a speaker that happens to have the audio. Pure.
 */
object FindOutput {
    // AudioDeviceInfo types, written out so this stays JVM-testable.
    const val TYPE_BLUETOOTH_A2DP = 8
    const val TYPE_HEARING_AID = 23
    const val TYPE_BLE_HEADSET = 26 // API 31
    const val TYPE_BLE_SPEAKER = 27 // API 31

    private val BLUETOOTH_TYPES = setOf(TYPE_BLUETOOTH_A2DP, TYPE_HEARING_AID, TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER)

    /**
     * The output for [address] (or one of its [twins]); among several, the one media is routed to
     * now ([mediaRouteIds]) first. Null when the headset has no output, e.g. another Bluetooth
     * device holds the audio.
     */
    fun pick(outputs: List<OutputCandidate>, address: String, twins: List<String> = emptyList(), mediaRouteIds: Set<Int> = emptySet()): OutputCandidate? {
        val wanted = (listOf(address) + twins).map { it.uppercase() }.toSet()
        return outputs
            .filter { it.type in BLUETOOTH_TYPES && it.address.uppercase() in wanted }
            .sortedBy { if (it.id in mediaRouteIds) 0 else 1 }
            .firstOrNull()
    }
}
