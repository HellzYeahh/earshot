package dev.earshot.protocol.airoha

/**
 * The vendors whose control protocol is RACE, each with its own allow-list on top of the Airoha
 * standard ids. RACE is never spoken to anyone else: Sony's Airoha units expose RACE as an unused
 * debug path, Bose's and Bragi's own protocols run beside it, so for them the RACE channel stays
 * closed (decision for 0.8, research "Top brand headset protocols": "keep never for Sony, Bose and
 * Bragi BOS-RPC").
 */
enum class RaceVendor {
    /** JBL's Airoha models (A-RACE): Tour Pro 3, Live Buds 3, Live Free 2, Reflect Aero, Tune 670–777NC, Endurance Race 2, Wave/Vibe Beam 2. */
    JBL,

    /** Skullcandy's 2022–25 non-Bragi line. Its custom ids are unresearched, so it gets the Airoha standard ids only. */
    SKULLCANDY,
}

/**
 * Refuse-by-default policy for every RACE frame Earshot sends, keyed on type, id, payload length
 * and, where the notes give the layout, the sub-command or module. [refusal] returns null to send
 * or the reason (a log line) not to; nothing it refuses leaves the phone.
 *
 * Order of the checks: only commands (`5A`) are ever sent; anything in [NEVER] is refused whatever
 * a vendor list says (firmware update, flash, storage, NV-key writes, dumps, power and role
 * switches, the relay and tunnel wrappers, JBL's destructive Harman ids); then the id must be on
 * the vendor's list and its payload must fit that entry.
 */
class RaceWriteGuard(private val vendor: RaceVendor) {

    /**
     * @param userInitiated true only when the user's own tap asked for this frame. The find-me tone
     *   needs it: Earshot never rings the buds on its own.
     */
    fun refusal(packet: RacePacket, userInitiated: Boolean = false): String? {
        if (!packet.isCommand) return "only RACE commands are sent, not ${RacePacket.typeName(packet.type)}"
        if (isNever(packet.id)) return "${RaceIds.name(packet.id)} is on the never-send list"
        val rule = allowed(vendor)[packet.id] ?: return "${RaceIds.name(packet.id)} is not on the ${vendor.name} allow-list"
        if (rule.userOnly && !userInitiated) return "${RaceIds.name(packet.id)} is sent only from the user's own tap"
        return rule.check(packet.payload)?.let { "${RaceIds.name(packet.id)}: $it" }
    }

    /** One allow-list entry: a payload check returning null when the payload is fine. */
    private class Rule(val userOnly: Boolean = false, val check: (ByteArray) -> String?)

    companion object {
        /**
         * Airoha-wide ranges from the JBL analysis plus the Harman-reserved ids that delete, power
         * off or overwrite (jbl.md §4). Checked before any allow-list, so no vendor entry can open one.
         */
        private val NEVER_RANGES: List<IntRange> = listOf(
            0x1C00..0x1C1B, // FOTA, incl. 0x1C05 software reset
            0x0400..0x04F0, // storage / flash program, erase, lock
            0x0702..0x070F, // flash
            0x1E00..0x1E15, // dump / log / assert (0x1E07 forces an assert)
        )

        private val NEVER_IDS: Set<Int> = setOf(
            0x0A01, 0x0A03, 0x0A09, 0x0A0A, 0x0A0D, // NV-key write, reclaim, reload
            0x020D, // power-mode switch
            0x0E01, 0x0E02, // DSP suspend / resume
            0x0E0A, 0x0E0B, // ANC / air dump
            RaceIds.MMI_KEY,
            0x0CD7, // role switch
            0x2C86, // device operation
            0x3101, // dongle action
            RaceIds.RELAY,
            RaceIds.HARMAN_TUNNEL,
            // Harman-reserved: delete a pairing, rewrite the paired list, power control, clear
            // analytics, smart-case action, delete a screensaver, set device info, ULL pairing.
            0x0057, 0x0067, 0x0007, 0x0011, 0x0083, 0x0089, 0x0094, 0x0030,
        )

        fun isNever(id: Int): Boolean = id in NEVER_IDS || NEVER_RANGES.any { id in it }

        private fun u16(payload: ByteArray, at: Int): Int =
            (payload[at].toInt() and 0xFF) or ((payload[at + 1].toInt() and 0xFF) shl 8)

        private fun size(payload: ByteArray, vararg sizes: Int): String? =
            if (payload.size in sizes) null else "payload of ${payload.size} bytes, expected ${sizes.joinToString(" or ")}"

        /** `00 0A filter [mode]` on (filter 0..7), `00 0B` off, `00 0F gain16`, `00 1E level16`. */
        private fun ancPayload(p: ByteArray): String? {
            if (p.size < 2 || p[0].toInt() != 0) return "unknown ANC sub-command"
            return when (p[1].toInt() and 0xFF) {
                0x0A -> size(p, 3, 4) ?: if ((p[2].toInt() and 0xFF) in 0..7) null else "ANC filter ${p[2]} out of range"
                0x0B -> size(p, 2)
                0x0F, 0x1E -> size(p, 4)
                else -> "unknown ANC sub-command 0x%02x".format(p[1])
            }
        }

        private fun moduleIn(p: ByteArray, modules: Set<Int>, valueBytes: Int): String? {
            if (p.size != 2 + valueBytes) return "payload of ${p.size} bytes, expected ${2 + valueBytes}"
            val module = u16(p, 0)
            return if (module in modules) null else "module $module is not allowed"
        }

        /**
         * The settings commands carry a module first; the notes give the modules but not the value
         * layout, so only the module (first byte) and a sane length are checked. Confirm with a
         * capture before a driver writes them.
         */
        private fun settingsModule(p: ByteArray, write: Boolean): String? {
            if (p.isEmpty() || p.size > SETTINGS_MAX) return "payload of ${p.size} bytes"
            if (write && p.size < 2) return "a settings write needs a value"
            val module = p[0].toInt() and 0xFF
            return if (module in SETTINGS_MODULES) null else "settings module $module is not allowed"
        }

        private const val SETTINGS_MAX = 8
        private val SETTINGS_MODULES = setOf(1, 9, 11, 19)

        /** Harman-reserved settings: small get/set payloads whose layouts the notes don't give, so only a length cap. */
        private const val HARMAN_MAX = 16

        private val standard: Map<Int, Rule> = mapOf(
            RaceIds.ANC_PASSTHROUGH to Rule(check = ::ancPayload),
            RaceIds.MMI_GET_ENUM to Rule { moduleIn(it, setOf(0, 1, 5, 6, 7), valueBytes = 0) },
            // Writes only the two modules whose layout the notes show: EQ preset and voice prompts.
            RaceIds.MMI_SET_ENUM to Rule { moduleIn(it, setOf(0, 1), valueBytes = 1) },
            RaceIds.TWS_BATTERY to Rule { p -> size(p, 1) ?: if (p[0].toInt() in 0..1) null else "role ${p[0]} is neither agent nor partner" },
            RaceIds.ROLE_QUERY_A to Rule { size(it, 0) },
            RaceIds.ROLE_QUERY_B to Rule { size(it, 0) },
            RaceIds.SETTINGS_GET to Rule { settingsModule(it, write = false) },
            RaceIds.SETTINGS_SET to Rule { settingsModule(it, write = true) },
            RaceIds.FIND_ME_QUERY to Rule { size(it, 0) },
            RaceIds.FIND_ME to Rule(userOnly = true) { size(it, 3) },
        )

        /**
         * The Harman-reserved get/set pairs JBL's Airoha models use (jbl.md §3): spatial, head
         * tracking, Hi-Res, low-volume EQ, balance, limiter, SafeSound, Smart Talk, chat optimiser,
         * auto pick-up, mic gain, PSAP, Silent Now, ANC sports mode, adaptive ANC, audio source,
         * in-ear sensor, device pushes, case and bud battery, colour, case firmware, device info.
         * Left out on purpose: the paired-list writes, connect-a-device, lighting, the smart-case
         * display, screensaver, time and notification ids, PersoniFi set-up and analytics.
         */
        private val HARMAN_IDS: Set<Int> = setOf(
            0x0006, // device pushes on/off
            0x0009, 0x000B, // charging info, case battery
            0x000A, 0x000C, // in-ear sensor on/off, status
            0x000D, // ANC sports mode
            0x000E, 0x000F, // colour, case firmware
            0x0012, 0x0013, // 3D spatial set/get
            0x0014, 0x0015, // SafeSound
            0x0036, 0x0037, // left/right balance
            0x0046, 0x0047, // spatial sound
            0x0048, 0x0049, // head tracker
            0x0050, 0x0051, // audio limiter
            0x0052, 0x0053, // chat optimiser
            0x0054, 0x0055, // audio source
            0x0058, 0x0059, // low-volume EQ
            0x0060, 0x0061, // Smart Talk
            0x0064, 0x0065, // mic gain
            0x0066, // paired list (read)
            0x0068, 0x0069, // Silent Now
            0x0075, 0x0076, // Hi-Res
            0x0077, 0x0078, // PSAP
            0x0087, 0x0088, // auto pick-up
            0x0092, // device info (read)
            0x0095, // auto-compensation off
            0x0E13, // auto-compensation check
            0x0E26, // full-adaptive ANC mode
        )

        private val jbl: Map<Int, Rule> = standard + HARMAN_IDS.associateWith { Rule { p -> if (p.size <= HARMAN_MAX) null else "payload of ${p.size} bytes" } }

        private fun allowed(vendor: RaceVendor): Map<Int, Rule> = when (vendor) {
            RaceVendor.JBL -> jbl
            RaceVendor.SKULLCANDY -> standard
        }

        /** Every id on [vendor]'s list, for the tests and the Lab's read list. */
        fun allowedIds(vendor: RaceVendor): Set<Int> = allowed(vendor).keys
    }
}
