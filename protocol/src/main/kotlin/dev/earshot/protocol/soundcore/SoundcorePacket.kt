package dev.earshot.protocol.soundcore

/**
 * One Soundcore frame (Anker's earbud protocol over RFCOMM). Layout, from the OpenSCQ30 and
 * Gadgetbridge sources (facts only; both are GPL/AGPL, nothing copied) and two captures of the
 * official app on a Sleep A30:
 * ```
 *  phone → buds:  08 EE 00 00 00 | cat code | len u16 LE | body | sum
 *  buds → phone:  09 FF 00 00 01 | cat code | len u16 LE | body | sum
 * ```
 * `len` is the whole frame (body + 10, checksum included); `sum` is every preceding byte added
 * up, mod 256. The command travels as `[category, code]`; codes 0x00..0x7F read, 0x80..0xFF write.
 * Example (request state): `08 EE 00 00 00 01 01 0A 00 02`.
 */
class SoundcorePacket(
    /** `(category shl 8) or code`, e.g. 0x0101 = request state, 0x0687 = Sleep A30 ANC on/off. */
    val command: Int,
    val body: ByteArray = ByteArray(0),
    /** True for a frame the headset sent (`09 FF …`). */
    val inbound: Boolean = false,
) {
    val category: Int get() = command shr 8
    val code: Int get() = command and 0xFF
    val isWrite: Boolean get() = code >= 0x80

    fun encode(): ByteArray {
        val total = body.size + OVERHEAD
        val out = ByteArray(total)
        (if (inbound) IN_PREFIX else OUT_PREFIX).copyInto(out, 0)
        out[5] = category.toByte()
        out[6] = code.toByte()
        out[7] = (total and 0xFF).toByte()
        out[8] = (total shr 8).toByte()
        body.copyInto(out, 9)
        out[total - 1] = checksum(out, total - 1).toByte()
        return out
    }

    override fun toString(): String = "${commandName(command)}${if (inbound) " ←" else " →"} ${body.joinToString("") { "%02x".format(it) }}"

    companion object {
        val OUT_PREFIX = byteArrayOf(0x08, 0xEE.toByte(), 0x00, 0x00, 0x00)
        val IN_PREFIX = byteArrayOf(0x09, 0xFF.toByte(), 0x00, 0x00, 0x01)

        /** Prefix (5) + command (2) + length (2) + checksum (1). */
        const val OVERHEAD = 10

        fun checksum(bytes: ByteArray, count: Int): Int {
            var sum = 0
            for (i in 0 until count) sum += bytes[i].toInt() and 0xFF
            return sum and 0xFF
        }

        fun commandName(command: Int): String = "%02x %02x".format(command shr 8, command and 0xFF)
    }
}

/**
 * Turns the RFCOMM byte stream back into frames. Chunks arrive split and merged arbitrarily; a
 * frame whose checksum fails, or bytes that don't start a frame, are skipped up to the next
 * prefix, so one corrupt read never wedges the link.
 */
class SoundcoreFrameBuffer(private val onJunk: (Int) -> Unit = {}) {
    private var buf = ByteArray(0)

    fun feed(chunk: ByteArray): List<SoundcorePacket> {
        buf += chunk
        val out = ArrayList<SoundcorePacket>()
        while (true) {
            val start = findPrefix()
            if (start < 0) {
                // Keep a possible partial prefix at the tail, drop the rest.
                val keep = minOf(buf.size, SoundcorePacket.IN_PREFIX.size - 1)
                if (buf.size > keep) onJunk(buf.size - keep)
                buf = buf.copyOfRange(buf.size - keep, buf.size)
                return out
            }
            if (start > 0) {
                onJunk(start)
                buf = buf.copyOfRange(start, buf.size)
            }
            if (buf.size < 9) return out
            val total = (buf[7].toInt() and 0xFF) or ((buf[8].toInt() and 0xFF) shl 8)
            if (total < SoundcorePacket.OVERHEAD || total > MAX_FRAME) {
                onJunk(1)
                buf = buf.copyOfRange(1, buf.size)
                continue
            }
            if (buf.size < total) return out
            val frame = buf.copyOfRange(0, total)
            buf = buf.copyOfRange(total, buf.size)
            if (SoundcorePacket.checksum(frame, total - 1) != (frame[total - 1].toInt() and 0xFF)) {
                onJunk(total)
                continue
            }
            val inbound = frame.copyOfRange(0, 5).contentEquals(SoundcorePacket.IN_PREFIX)
            val command = ((frame[5].toInt() and 0xFF) shl 8) or (frame[6].toInt() and 0xFF)
            out += SoundcorePacket(command, frame.copyOfRange(9, total - 1), inbound)
        }
    }

    /** First index where either prefix starts (the virtual device and tests feed outbound frames too). */
    private fun findPrefix(): Int {
        for (i in 0..buf.size - 5) {
            if (matches(i, SoundcorePacket.IN_PREFIX) || matches(i, SoundcorePacket.OUT_PREFIX)) return i
        }
        return -1
    }

    private fun matches(at: Int, prefix: ByteArray): Boolean = prefix.indices.all { buf[at + it] == prefix[it] }

    private companion object {
        /** The Sleep A30 state is 160 bytes; nothing seen is near this. */
        const val MAX_FRAME = 2048
    }
}

/**
 * The single write path's allow-list, like `bt/WriteGuard` for BMAP. Only commands Earshot has a
 * reason to send leave the phone; everything else, in particular the ones below, is refused:
 * `0b 83` forget a host (unpair), `0b 85` pairing mode, `0b 81`/`0b 82`/`0b 84` host connections,
 * `01 85` / `01 89` power off (ids differ by model), `10 89` find device (a loud tone in sleep
 * earbuds), `04 82` reset buttons, `14 82` delete alarm, and any unknown write. Factory reset and
 * firmware update have no known id here; the firmware-update service (BES `66666666-…`) is a
 * separate RFCOMM channel Earshot never opens.
 */
object SoundcoreWriteGuard {
    val ALLOWED: Set<Int> = setOf(
        // Reads.
        SoundcoreCommands.REQUEST_STATE, SoundcoreCommands.REQUEST_INFO, 0x1503, 0x1401,
        // Writes the driver uses (Sleep A30 ids, each a single setting).
        SoundcoreCommands.SET_ANC_D1301, SoundcoreCommands.SET_TAP_DISABLED, SoundcoreCommands.SET_LISTENING_MODE,
        SoundcoreCommands.SET_DEFAULT_LISTENING_MODE, SoundcoreCommands.SET_LOW_BATTERY_PROMPT,
        SoundcoreCommands.SET_AUTO_OFF_PROMPT, SoundcoreCommands.SET_LISTENING_MODE_PROMPT, SoundcoreCommands.SET_ANC_PROMPT,
        SoundcoreCommands.SET_INCOMING_CALLS, SoundcoreCommands.SET_AUTO_SWITCH_ASLEEP, SoundcoreCommands.SET_SLEEP_TIMER,
        // Session-init sequence some models need before writes take effect (soundcorebridge, MIT).
        0x0501, 0x0581,
    )

    fun isAllowed(packet: SoundcorePacket): Boolean = packet.command in ALLOWED

    fun check(packet: SoundcorePacket) {
        require(isAllowed(packet)) { "Soundcore command ${SoundcorePacket.commandName(packet.command)} is not on Earshot's allow-list" }
    }

    /**
     * Refused for every model, whatever its table says: the ids above that unpair, enter pairing
     * mode, connect or disconnect a host, power off, play the find tone, reset the buttons, delete
     * an alarm, or start the fit test (loud tones), plus the firmware / OTA and factory-reset
     * opcodes, which have no known id here and so stay unknown writes. `0b 84` is not in this set:
     * it is the multipoint switch on the earbuds that list it (and refused on every other model).
     */
    val NEVER: Set<Int> = setOf(
        0x0B81, 0x0B82, 0x0B83, 0x0B85, 0x0185, 0x0189, 0x1089, 0x0482, 0x1482, 0x0901,
    )

    /** Reads every driven model may make. `05 01` joins them only for models with a session-init. */
    private val READS: Set<Int> = setOf(SoundcoreCommands.REQUEST_STATE, SoundcoreCommands.REQUEST_INFO)

    /**
     * The per-model allow-list (research notes §4: ids are reused across models, so the list is
     * the model table's own, keyed on command id and body shape). `model == null` is the original
     * global list, which the tested Sleep A30 driver keeps using unchanged.
     *  - Sleep A30: the original list, as before.
     *  - Any other model: the state reads, and for a model whose layout is writable the mode block
     *    (`06 81`, exact codec size), its documented settings (`[0|1]`, auto-off `[enabled, index]`)
     *    and, where the model needs it, the session-init `05 01` / `05 81`. Anything else is refused.
     */
    fun isAllowed(model: SoundcoreModel?, packet: SoundcorePacket): Boolean {
        if (model == null || model == SoundcoreModel.SLEEP_A30) return isAllowed(packet)
        if (packet.command in NEVER) return false
        val layout = model.layout
        if (!packet.isWrite) {
            if (packet.command in READS) return packet.body.isEmpty()
            return packet.command == SoundcoreCommands.SESSION_INFO && layout?.sessionInit == true && layout.writable
        }
        return layout?.allows(packet.command, packet.body) == true
    }

    fun check(model: SoundcoreModel?, packet: SoundcorePacket) {
        require(isAllowed(model, packet)) { "Soundcore command ${SoundcorePacket.commandName(packet.command)} is not on Earshot's allow-list" }
    }
}

/** Command ids (`(category shl 8) or code`). */
object SoundcoreCommands {
    /** Full state; the Sleep A30 answers with a 150-byte body ([D1301State]). Also pushed. */
    const val REQUEST_STATE = 0x0101
    const val PUSH_TWS = 0x0102
    const val PUSH_BATTERY = 0x0103
    /** Firmware left/right and serial. */
    const val REQUEST_INFO = 0x0105
    /** Sleep A30: noise cancelling `[0 off | 1 on]`. Other models use `06 81` with their own payload. */
    const val SET_ANC_D1301 = 0x0687
    /** `[1 = tap controls disabled]`. */
    const val SET_TAP_DISABLED = 0x1094
    /** Sleep A30: `[0 Bluetooth | 1 Local]` (sound played from the buds). */
    const val SET_LISTENING_MODE = 0x01A9

    // Sleep A30 single-byte settings, `[0|1]`.
    const val SET_DEFAULT_LISTENING_MODE = 0x1095
    const val SET_LOW_BATTERY_PROMPT = 0x1082
    const val SET_AUTO_OFF_PROMPT = 0x109E
    const val SET_LISTENING_MODE_PROMPT = 0x109F
    const val SET_ANC_PROMPT = 0x1077
    const val SET_INCOMING_CALLS = 0x1075
    const val SET_AUTO_SWITCH_ASLEEP = 0x158F

    /**
     * Sleep A30 timer and post-sleep action, 4 bytes: `[FF FF FF action]` sets only the action
     * (FF = leave the timer fields alone), `[enabled, minutes lo, minutes hi, x]` sets the timer.
     */
    const val SET_SLEEP_TIMER = 0x1585

    // ---- Gen-2 earbuds and headphones (research notes §3; ids are reused across models, so each
    // model's table says which of these it may send) ----

    /** `[left charging, right charging]`, pushed. */
    const val PUSH_CHARGING = 0x0104

    /** The sound-mode block, pushed when the modes change on the earbuds. Body = the model's codec size. */
    const val PUSH_SOUND_MODES = 0x0601

    /** Set the sound-mode block (ambient mode, noise-cancelling type and level, wind). */
    const val SET_SOUND_MODES = 0x0681

    /** Wear detection (auto play/pause on some models), `[0|1]`. */
    const val SET_WEAR_DETECTION = 0x0181

    /** Touch tone, `[0|1]`. */
    const val SET_TOUCH_TONE = 0x0183

    /** Auto power-off `[enabled, duration index]`. */
    const val SET_AUTO_OFF = 0x0186

    /** Gaming mode on the Note 3 / A40 / P3 / R50i NC family, `[0|1]`. */
    const val SET_GAMING_MODE = 0x0187

    /** Wearing tone, `[0|1]`. */
    const val SET_WEARING_TONE = 0x018C

    /** 3D surround (A30i), `[0|1]`. */
    const val SET_SURROUND = 0x0286

    /** Gaming mode on the Liberty 4 NC / Liberty 5, `[0|1]`. */
    const val SET_GAMING_MODE_10 = 0x1085

    /** Sound-leak compensation (Liberty 4 NC), `[0|1]`. */
    const val SET_LEAK_COMPENSATION = 0x1086

    /** Multipoint (two phones at once), `[0|1]`. On other models this id has been seen as a host connection, so it is per model. */
    const val SET_MULTIPOINT = 0x0B84

    /** Capability table read, and the empty "session init" some models want before writes. */
    const val SESSION_INFO = 0x0501
    const val SESSION_INIT = 0x0581
}
