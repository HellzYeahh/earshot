package dev.earshot.call

/**
 * The battery a headset last reported to the phone over HFP, read from `dumpsys bluetooth_manager`
 * through Shizuku. Android's own getter (`BluetoothDevice.getBatteryLevel()`) is a SystemApi since
 * Android 13 and its broadcast only fires on a change, so this is how a headset Earshot has no
 * protocol for gets a figure the moment it connects. Without Shizuku: null, and the broadcast fills
 * it in later.
 */
object HeadsetBatteryReader {
    suspend fun read(shell: PrivilegedShell, address: String): Int? {
        if (!shell.isAvailable()) return null
        val text = shell.exec("dumpsys bluetooth_manager").getOrNull() ?: return null
        return fromDump(text, address)
    }

    /** Pure part, unit-tested: the HFP block for [address] (or its last two octets, as dumps redact). */
    fun fromDump(text: String, address: String): Int? {
        val devices = CallCodecParser.parse(text).hfpDevices
        val tail = address.takeLast(5)
        val device = devices.firstOrNull { it.address.equals(address, ignoreCase = true) }
            ?: devices.firstOrNull { it.address?.endsWith(tail, ignoreCase = true) == true }
        return device?.batteryLevel
    }
}
