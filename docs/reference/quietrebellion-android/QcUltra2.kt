/*
 * QcUltra2.kt – Feature address map + parsers for Bose QC Ultra Headphones (2nd Gen).
 * Port of QcUltraFeatures.cs / python/pybmap/devices/qc_ultra2.py.
 * Codename: wolverine | Platform: OTG-QCC-384 | Product-ID: 0x4082
 */
package net.quietrebellion

import java.util.UUID

enum class SpatialMode(val byte: Byte) { Off(0), Room(1), Head(2) }

// ButtonConfig removed — [1.9] remapping requires cloud ECDH auth (SETGET echoes but is ignored)

data class AudioSettings(
    val cncLevel:  Int,
    val spatial:   SpatialMode,
    val windBlock: Boolean,
    val ancToggle: Boolean,
)

data class EqBand(val bandId: Int, val name: String, val minVal: Int, val maxVal: Int, val current: Int)

object QcUltra2 {
    // RFCOMM channel 2 is hardcoded in all bosectl captures.
    // Upgrade: SDP lookup on BMAP_UUID if a device uses a different channel.
    const val RFCOMM_CHANNEL = 2
    val BMAP_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")

    // ── Feature Addresses [fblock, func] ─────────────────────────────────────
    val Firmware      = 0.toByte() to 5.toByte()   // GET → ASCII version string
    val ProductName   = 1.toByte() to 2.toByte()   // GET → [flag, ...utf8_name]
    val Eq            = 1.toByte() to 7.toByte()   // GET/SETGET EQ bands
    val Multipoint    = 1.toByte() to 10.toByte()  // GET/SETGET multipoint
    val Sidetone      = 1.toByte() to 11.toByte()  // GET/SETGET sidetone
    val Battery       = 2.toByte() to 2.toByte()   // GET → [percent]
    val ChargingState = 2.toByte() to 3.toByte()   // GET → [0=not charging, 1=charging]
    val PairedDevices = 4.toByte() to 4.toByte()
    val DeviceInfo    = 4.toByte() to 5.toByte()  // GET(mac) → STATUS: [mac(6), ?, ?, 0x03, name...]   // GET → paired MAC list
    val Pairing       = 4.toByte() to 8.toByte()   // START [0x01] → enter pairing mode
    val Routing       = 4.toByte() to 12.toByte()  // START → switch audio device
    val Source        = 5.toByte() to 1.toByte()   // GET → active audio source
    val Power         = 7.toByte() to 4.toByte()   // START [0x00] → power off
    val GetAllModes   = 31.toByte() to 1.toByte()  // START → drain STATUS [31.6]
    val CurrentMode   = 31.toByte() to 3.toByte()  // GET/START mode index
    val ModeConfigStatus = 31.toByte() to 6.toByte()
    val AudioSettings = 31.toByte() to 10.toByte() // GET/SETGET 5-byte audio config
    val Favorites     = 31.toByte() to 8.toByte()  // GET/SETGET favorite modes bitmask
    val AutoPlayPause = 1.toByte()  to 24.toByte() // GET/SETGET [bool] auto play/pause on ear removal
    val AutoAnswer    = 1.toByte()  to 27.toByte() // GET/SETGET [bool] auto-answer calls
    // Buttons [1.9] address removed – remapping requires cloud ECDH auth; SETGET echoes only


    val MODE_NAMES = mapOf(0 to "Quiet", 1 to "Aware", 2 to "Immersion", 3 to "Cinema")

    /** Resolve a mode index to a display name. 0xFF = device left the saved mode (manual ANC change etc.). */
    fun modeDisplayName(index: Int, names: Map<Int, String> = MODE_NAMES): String =
        names[index] ?: when (index) { 0xFF, 255 -> "Custom"; -1 -> ""; else -> "Mode $index" }


    // ── Parsers ──────────────────────────────────────────────────────────────

    fun parseBattery(p: ByteArray): Int = if (p.isNotEmpty()) p[0].toInt() and 0xFF else 0


    fun parseFirmware(p: ByteArray): String = String(p, Charsets.US_ASCII).trimEnd('\u0000')

    fun parseProductName(p: ByteArray): String =
        if (p.size > 1) String(p, 1, p.size - 1, Charsets.UTF_8).trimEnd('\u0000') else ""

    fun parseAudioSettings(p: ByteArray): AudioSettings {
        if (p.size < 5) return AudioSettings(0, SpatialMode.Off, false, false)
        val spatial = SpatialMode.entries.firstOrNull { it.byte == p[2] } ?: SpatialMode.Off
        // p[1] = autoCnc; firmware rejects autoCnc=1 with Runtime error 8 – always keep 0
        return AudioSettings(
            cncLevel  = p[0].toInt() and 0xFF,
            spatial   = spatial,
            windBlock = p[3] != 0.toByte(),
            ancToggle = p[4] != 0.toByte(),
        )
    }

    // autoCnc byte hardcoded to 0 – firmware rejects 1 with Runtime error 8
    fun buildAudioSettings(s: AudioSettings): ByteArray = byteArrayOf(
        s.cncLevel.toByte(), 0,
        s.spatial.byte, if (s.windBlock) 1 else 0, if (s.ancToggle) 1 else 0,
    )

    private val BAND_NAMES = arrayOf("Bass", "Mid", "Treble")

    fun parseEq(p: ByteArray): List<EqBand> {
        val bands = mutableListOf<EqBand>()
        var i = 0
        while (i + 3 < p.size) {
            fun signed(b: Byte) = if (b >= 128) b - 256 else b.toInt()
            val bandId = p[i + 3].toInt() and 0xFF
            bands.add(EqBand(bandId,
                name    = BAND_NAMES.getOrElse(bandId) { "Band$bandId" },
                minVal  = signed(p[i]),
                maxVal  = signed(p[i + 1]),
                current = signed(p[i + 2]),
            ))
            i += 4
        }
        return bands
    }

    fun parseSidetone(p: ByteArray): Int = if (p.size >= 2) p[1].toInt() and 0xFF else 0

    // [1.11] payload: [persist, level]
    fun parseSidetonePersistence(p: ByteArray): Boolean = p.isNotEmpty() && p[0] != 0.toByte()

    fun parseMultipoint(p: ByteArray): Boolean = p.isNotEmpty() && (p[0].toInt() and 0x02) != 0

    fun parseModeConfigBasic(p: ByteArray): Pair<Int, String>? {
        // ModeConfigStatus payload layout (38 bytes):
        //   [0]    mode index
        //   [1..5] unknown header bytes (flags, sub-index, …)
        //   [6..37] UTF-8 name, NUL-terminated
        if (p.size < 38) return null
        val idx     = p[0].toInt() and 0xFF
        var nameEnd = p.indexOf(0, 6)
        if (nameEnd < 6 || nameEnd > 38) nameEnd = 38
        val name = String(p, 6, nameEnd - 6, Charsets.UTF_8).trim()
        return idx to (name.ifEmpty { "custom($idx)" })
    }

    fun parseSource(p: ByteArray): Pair<String, String?> {
        if (p.size < 3) return "none" to null
        val type = when (p[2].toInt() and 0xFF) { 1 -> "bluetooth"; 2 -> "auxiliary"; else -> "none" }
        val mac  = if (p[2] == 1.toByte() && p.size >= 9)
            p.drop(3).take(6).joinToString(":") { "%02X".format(it) } else null
        return type to mac
    }


    /** Parse 4.5 STATUS payload: [mac(6), unk, unk, 0x03, name...] → display name or null. */
    fun parseDeviceInfo(p: ByteArray): String? {
        // find 0x03 marker after the 6-byte MAC prefix, name follows
        val nameStart = p.indexOf(0x03.toByte(), 6)
        if (nameStart < 0 || nameStart + 1 >= p.size) return null
        return String(p, nameStart + 1, p.size - nameStart - 1, Charsets.UTF_8)
            .trimEnd('\u0000').ifBlank { null }
    }

    fun parsePairedDevices(p: ByteArray): List<String> {
        val result = mutableListOf<String>()
        var i = 1
        while (i + 5 < p.size) {
            if (p[i] == 0.toByte() && p[i+1] == 0.toByte() && p[i+2] == 0.toByte()) { i += 6; continue }
            result.add(p.drop(i).take(6).joinToString(":") { "%02X".format(it) })
            i += 6
        }
        return result
    }

    // ── Builders ─────────────────────────────────────────────────────────────

    fun buildSidetone(level: Int, persistent: Boolean = true): ByteArray =
        byteArrayOf(if (persistent) 1 else 0, level.toByte())
    fun buildToggle(enabled: Boolean): ByteArray = byteArrayOf(if (enabled) 1 else 0)
    fun buildEqBand(value: Int, bandId: Int): ByteArray = byteArrayOf((value and 0xFF).toByte(), bandId.toByte())
    fun buildDeviceName(name: String): ByteArray = name.toByteArray(Charsets.UTF_8)

    fun parseFavorites(p: ByteArray): Set<Int> {
        if (p.size < 3) return emptySet()
        // Wire format: [totalModes, 0x00, maskByte]  (p[1] is reserved/unknown)
        val mask = p[2].toInt() and 0xFF
        return (0..10).filter { (mask shr it) and 1 == 1 }.toSet()
    }

    fun buildFavorites(totalModes: Int, favSet: Set<Int>): ByteArray {
        // Mask is stored in one byte → only modes 0-7 survive toByte().
        // If the device ever uses modes 8-10 as favourites, byte[3] must carry bits 8-10.
        // Upgrade: mask.and(0xFF).toByte() in [2], (mask shr 8).toByte() in [3].
        var mask = 0
        for (idx in favSet) mask = mask or (1 shl idx)
        return byteArrayOf(totalModes.toByte(), 0, mask.toByte())
    }

    fun buildRouting(mac: String): ByteArray {
        val parts = mac.split(":").map { it.toInt(16).toByte() }
        require(parts.size == 6) { "Invalid MAC: $mac" }
        return byteArrayOf(0x82.toByte()) + parts.toByteArray()
    }

    // ── Helper ───────────────────────────────────────────────────────────────
    private fun ByteArray.indexOf(value: Byte, from: Int): Int {
        for (i in from until size) if (this[i] == value) return i
        return -1
    }
}

