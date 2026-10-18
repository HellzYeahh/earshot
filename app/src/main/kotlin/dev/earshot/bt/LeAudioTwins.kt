package dev.earshot.bt

/**
 * Bose earbuds and headphones with LE Audio switched on appear in the phone's bonded list twice:
 * the classic (BR/EDR) device, e.g. "Bose QC Ultra Earbuds", and an LE Audio identity whose name
 * Android prefixes with "LE-", e.g. "LE-Bose QC Ultra Earbuds", under a different address. While
 * the phone plays over LE Audio only the LE twin is connected; A2DP and HFP report the classic one
 * as disconnected and an RFCOMM socket to the LE address cannot open (user report on QC Ultra
 * Earbuds 1st Gen, 0.3.1: "the program waits for the headphones to be connected").
 *
 * Pure name logic, so it is unit-tested without Bluetooth.
 */
object LeAudioTwins {
    private val LE_PREFIX = Regex("^le[-_ ]", RegexOption.IGNORE_CASE)

    /** True for the name Android gives an LE Audio identity ("LE-Bose …"). */
    fun isLeName(name: String?): Boolean = name != null && LE_PREFIX.containsMatchIn(name.trim())

    /** The name without the "LE-" prefix: what the user sees in the Bose app and what the classic twin is called. */
    fun baseName(name: String?): String? = name?.trim()?.replace(LE_PREFIX, "")?.trim()?.ifEmpty { null }

    /**
     * Addresses among [bonded] (address to name) that are the same headphones as [address]: the
     * other half of an LE / classic pair, matched by [baseName]. Excludes [address] itself; empty
     * when the device has no twin or no name.
     */
    fun twinsOf(address: String, bonded: Map<String, String?>): List<String> {
        val self = bonded.entries.firstOrNull { it.key.equals(address, ignoreCase = true) } ?: return emptyList()
        val base = baseName(self.value) ?: return emptyList()
        val selfIsLe = isLeName(self.value)
        return bonded.entries
            .filter { !it.key.equals(address, ignoreCase = true) }
            // A twin is the *other* kind: classic for an LE identity and vice versa. Two classic
            // devices that happen to share a name (two identical earbuds) are not twins.
            .filter { isLeName(it.value) != selfIsLe && baseName(it.value).equals(base, ignoreCase = true) }
            .map { it.key }
    }
}
