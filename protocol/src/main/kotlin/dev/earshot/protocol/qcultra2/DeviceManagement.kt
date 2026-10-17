package dev.earshot.protocol.qcultra2

import dev.earshot.protocol.BmapError
import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.BmapPacket

/*
 * DeviceManagement block [4.x] plus Control.Power [7.4]: pairing, the multipoint device list
 * and routing audio between the two connected phones.
 */

// ── Paired device list [4.4] ─────────────────────────────────────────────────────────────

data class PairedDevices(
    /**
     * Byte 0: the Bose app reads it as a bitmask of connected entries, bit *i* = the *i*-th
     * listed address is connected (8.2.20 capture `03` with both phones connected; fw 10.12
     * `01` with only the first one connected, then `03` with both).
     */
    val header: Int,
    val macs: List<String>,
    val raw: ByteArray,
) {
    /** Addresses whose bit in [header] is set. */
    val connectedMacs: Set<String> get() = macs.filterIndexed { i, _ -> i < 8 && (header shr i) and 1 == 1 }.toSet()

    fun isConnected(mac: String): Boolean = connectedMacs.any { it.equals(mac, ignoreCase = true) }

    override fun equals(other: Any?): Boolean = other is PairedDevices && raw.contentEquals(other.raw)
    override fun hashCode(): Int = raw.contentHashCode()
}

/**
 * `[4.4]` GET → `[header, mac(6) × N]`; all-zero addresses are padding and skipped
 * (capture `03 c40f08…… 9445608505a7` → two devices).
 */
object PairedDeviceList {
    fun parse(payload: ByteArray): PairedDevices? {
        if (payload.isEmpty()) return null
        val macs = ArrayList<String>()
        var i = 1
        while (i + Mac.LENGTH <= payload.size) {
            if (!Mac.isZero(payload, i)) macs.add(Mac.format(payload, i))
            i += Mac.LENGTH
        }
        return PairedDevices(payload.u8(0), macs, payload.copyOf())
    }
}

// ── Device info [4.5] ────────────────────────────────────────────────────────────────────

data class DeviceInfo(
    val mac: String,
    val name: String?,
    val raw: ByteArray,
    /**
     * Byte 6, the Bose app's flags: bit0 connected, bit1 isLocalDevice (the phone asking),
     * bit2 isBoseProduct, bit3 isComponent, bit7 productType. fw 10.12: `0x03` for the connected
     * phone running Earshot, `0x00` for a paired-but-absent device.
     */
    val linkFlags: Int = 0,
) {
    val looksConnected: Boolean get() = linkFlags != 0
    val isConnected: Boolean get() = linkFlags and 0x01 != 0
    /** Bit 1: this entry is the device that sent the GET, i.e. the phone running Earshot. */
    val isLocalDevice: Boolean get() = linkFlags and 0x02 != 0
    val isBoseProduct: Boolean get() = linkFlags and 0x04 != 0

    override fun equals(other: Any?): Boolean = other is DeviceInfo && raw.contentEquals(other.raw)
    override fun hashCode(): Int = raw.contentHashCode()
}

/**
 * `[4.5]` Info is a GET *with* a payload: the 6-byte address of the paired device to describe.
 *
 * STATUS on firmware 10.12 (two real frames): `[mac(6), flags, b7, b8, name...]`, the name
 * starting at offset 9 (b7-b8 = the Class of Device for a non-Bose device; for a Bose product the
 * app reads a product id there, a variant at 9 and the name from 10, not seen yet):
 * `2cda46000002 03 02 03 "My Galaxy Fold"` (the connected, streaming phone) and
 * `10b1df000003 00 01 01 "OLD_LAPTOP"` (paired, absent). Quiet Rebellion's "name follows the
 * first 0x03" rule only worked for the first shape; it is kept as a fallback for shorter frames.
 */
object DeviceInfoQuery {
    private const val NAME_OFFSET = Mac.LENGTH + 3

    fun request(mac: String): BmapPacket =
        BmapPacket(QcUltra2.DEVICE_INFO.block, QcUltra2.DEVICE_INFO.function, BmapOperator.GET, Mac.toBytes(mac))

    fun parse(payload: ByteArray): DeviceInfo? {
        if (payload.size < Mac.LENGTH) return null
        val mac = Mac.format(payload, 0)
        val flags = if (payload.size > Mac.LENGTH) payload.u8(Mac.LENGTH) else 0
        val name = when {
            payload.size > NAME_OFFSET -> cleanName(payload.utf8Until(NAME_OFFSET))
            else -> {
                var marker = -1
                for (i in Mac.LENGTH until payload.size) if (payload[i] == 0x03.toByte()) { marker = i; break }
                if (marker < 0 || marker + 1 >= payload.size) null else cleanName(payload.utf8Until(marker + 1))
            }
        }
        return DeviceInfo(mac, name, payload.copyOf(), flags)
    }

    /** Drop stray control bytes (a misjudged header) and padding; blank → null. */
    private fun cleanName(s: String): String? = s.trim { it <= ' ' }.ifBlank { null }
}

// ── Extended info [4.6] ──────────────────────────────────────────────────────────────────

/** Classic profiles in the `[4.6]` bit order (Bose app: A2DP, HFP, AVRCP, SPP, iAP; bit order inferred). */
enum class ClassicProfile(val bit: Int) {
    A2DP(0), HFP(1), AVRCP(2), SPP(3), IAP(4);

    companion object {
        fun fromBits(bits: Int): Set<ClassicProfile> = entries.filter { (bits shr it.bit) and 1 == 1 }.toSet()
    }
}

data class DeviceExtendedInfo(
    val mac: String,
    val pairedClassic: Set<ClassicProfile>,
    val connectedClassic: Set<ClassicProfile>,
    val leUnicastPaired: Boolean,
    val leBroadcastPaired: Boolean,
    val leUnicastConnected: Boolean,
    val leBroadcastConnected: Boolean,
    val raw: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is DeviceExtendedInfo && raw.contentEquals(other.raw)
    override fun hashCode(): Int = raw.contentHashCode()
}

/**
 * `[4.6]` ExtendedInfo: GET with the 6-byte MAC (a bare GET answers InvalidData on fw 10.12).
 * STATUS `[mac(6)][paired classic bits][connected classic bits][8][9][10 LE paired: bit0
 * unicast, bit1 broadcast][11 LE connected: same bits]`. Layout from the Bose app parser; no real
 * frame captured yet (**untested on hardware**). Shorter frames leave the LE fields false.
 */
object DeviceExtendedInfoQuery {
    fun request(mac: String): BmapPacket =
        BmapPacket(QcUltra2.DEVICE_EXTENDED_INFO.block, QcUltra2.DEVICE_EXTENDED_INFO.function, BmapOperator.GET, Mac.toBytes(mac))

    fun parse(payload: ByteArray): DeviceExtendedInfo? {
        if (payload.size < Mac.LENGTH + 2) return null
        fun byte(i: Int) = if (payload.size > i) payload.u8(i) else 0
        return DeviceExtendedInfo(
            mac = Mac.format(payload, 0),
            pairedClassic = ClassicProfile.fromBits(byte(6)),
            connectedClassic = ClassicProfile.fromBits(byte(7)),
            leUnicastPaired = byte(10) and 0x01 != 0,
            leBroadcastPaired = byte(10) and 0x02 != 0,
            leUnicastConnected = byte(11) and 0x01 != 0,
            leBroadcastConnected = byte(11) and 0x02 != 0,
            raw = payload.copyOf(),
        )
    }
}

// ── App address [4.9], available to connect [4.18], LE Audio check [4.19] ────────────────

/**
 * `[4.9]` AppAddress: the phone's own Bluetooth address *as the headphones see it* (fw 10.12
 * `2cda46000002` = the Galaxy running Earshot). Android hides the local adapter address from
 * apps, so this is how Earshot recognises "this phone" in the paired list.
 */
object AppAddress {
    fun parse(payload: ByteArray): String? = if (payload.size < Mac.LENGTH) null else Mac.format(payload, 0)
}

/**
 * `[4.18]` AvailableToConnect: `[0 NotAvailable, 1 Available, 2 Unknown]`; fw 10.12 reads `01`.
 * Read as "the headphones can take another source connection right now" [inference].
 */
object AvailableToConnect {
    fun parse(payload: ByteArray): AvailableToConnectCode? =
        if (payload.isEmpty()) null else AvailableToConnectCode.fromCode(payload.u8(0))
}

data class LeAudioCheckResult(val supported: Boolean, val preferred: Boolean, val prohibited: Boolean)

/**
 * `[4.19]` LeAudioCheck: START (no payload) → STATUS/RESULT `[bit0 supported, bit1 preferred,
 * bit2 prohibited]`. GET answers error 5 on fw 10.12 (START-only). The Bose app runs it on every
 * BLE connection to decide whether to stay on LE; untested on hardware over RFCOMM.
 */
object LeAudioCheck {
    fun parse(payload: ByteArray): LeAudioCheckResult? {
        if (payload.isEmpty()) return null
        val b = payload.u8(0)
        return LeAudioCheckResult(supported = b and 0x01 != 0, preferred = b and 0x02 != 0, prohibited = b and 0x04 != 0)
    }
}

// ── Routing [4.12] ───────────────────────────────────────────────────────────────────────

/**
 * `[4.12]` START `[0x82, mac(6)]` routes audio to a paired device (multipoint switch).
 * 0x82 = bit 7 "UP" routing direction | bit 1 device slot (Bose `ROUTING_TYPE.UP << 7`).
 *
 * Replies (Bose `DeviceManagementBmapPacketParser`): RESULT bytes 1-6 = now-active MAC;
 * STATUS bytes 2-7 = newly routed MAC; ERROR carries a 16-bit code.
 *
 * The firmware's routing table is only populated while the target device is actually
 * streaming; otherwise the START answers FuncNotSupp (4). Quiet Rebellion retries 5× at
 * 500 ms while the user starts playback on the other device, and uses a GET on this address
 * (`[canRoute]`) to tell whether the table is filled. On fw 10.12 the GET always answers
 * FuncNotSupp, so [canRoute] is useless there; the START has not been tried on 10.12 yet.
 */
object Routing {
    const val FLAG_UP = 0x82

    fun startPayload(mac: String): ByteArray = byteArrayOf(FLAG_UP.toByte()) + Mac.toBytes(mac)

    /** True when the reply says the routing table is empty right now (retry later), false otherwise. */
    fun isTableEmpty(reply: BmapPacket): Boolean = reply.isError && reply.error == BmapError.FUNC_NOT_SUPPORTED

    /** Quiet Rebellion's `canRoute()`: a GET on `[4.12]` that is *not* FuncNotSupp means routing is possible. */
    fun canRoute(reply: BmapPacket?): Boolean = reply != null && !isTableEmpty(reply)

    /** MAC confirmed by a RESULT or STATUS reply, null for anything else. */
    fun parseReply(reply: BmapPacket): String? = when (reply.operator) {
        BmapOperator.RESULT -> if (reply.payload.size >= 7) Mac.format(reply.payload, 1) else null
        BmapOperator.STATUS -> if (reply.payload.size >= 8) Mac.format(reply.payload, 2) else null
        else -> null
    }

    const val RETRY_ATTEMPTS = 5
    const val RETRY_DELAY_MS = 500L
}

// ── Pairing mode [4.8] ───────────────────────────────────────────────────────────────────

/** `[4.8]` GET → `[0|1]`; START `[1]` enters pairing (the link usually drops), `[0]` leaves it. */
object PairingMode {
    fun parse(payload: ByteArray): Boolean? = Toggle.parse(payload)
    fun startPayload(enable: Boolean): ByteArray = byteArrayOf(enable.toByteValue())
}

// ── Power [7.4] ──────────────────────────────────────────────────────────────────────────

/** `[7.4]` GET → `[1]` while on; START `[0]` powers off (socket closes immediately), `[1]` on. */
object Power {
    fun parse(payload: ByteArray): Boolean? = Toggle.parse(payload)
    fun startPayload(on: Boolean): ByteArray = byteArrayOf(on.toByteValue())
}
