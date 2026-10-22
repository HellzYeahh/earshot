package dev.earshot.protocol.sennheiser

import dev.earshot.protocol.gaia.GaiaCommand
import dev.earshot.protocol.gaia.GaiaDiscovery
import dev.earshot.protocol.gaia.GaiaFrame
import dev.earshot.protocol.gaia.GaiaVendor
import dev.earshot.protocol.gaia.GaiaWritePolicy
import dev.earshot.protocol.sennheiser.SennheiserCommands as C

/**
 * The single write path's policy for Sennheiser headsets: refuse by default. A frame leaves the
 * phone only when
 *  - it is a request (type 0) to Sennheiser (0x0495) or Qualcomm Core discovery (0x001D 0x0000 /
 *    0x0001 / 0x0002), and
 *  - for Sennheiser, it is a discovery read, or the headset reported the command's feature in its
 *    `0x0001` list (nothing else goes out before that list arrives), and
 *  - it is one of the GETs Earshot reads or one of the SETs it writes, with a payload that passes
 *    that SET's check (single bytes 0/1, levels 0..100, auto-off from the app's choices, mode-table
 *    pairs, EQ bands only on generation-2 firmware).
 *
 * Named so they stay refused whatever the allow-list grows into (Sennheiser notes §4; the
 * momentum4-control catalogue marks the same ids Blocked): factory reset `0x0040`; firmware update
 * (Sennheiser feature 1 `0x0200`/`0x0201`, Qualcomm feature 6 `0x0C00`–`0x0C02`); reboot `0x060F`
 * and role switch `0x1C00` (feature 14); pairing-list disconnect / delete `0x1403`/`0x1405`/`0x1406`;
 * debug and data transfer (Qualcomm feature 4 `0x0803`/`0x0804`, `0x0009`/`0x000A`, transport
 * switch `0x000D`; Sennheiser sensor calibration `0x080E`/`0x080F`); hearing-profile reset
 * `0x2009`; button-layout reset `0x1604`; rename `0x2801`; voice-assistant select `0x001D/0x0601`;
 * MCU upgrade (feature 18); and button mappings (`0x1600`) to function 8 (power off) or 19
 * (disconnect every BLE device). The iAP2 record and fixed channels are a transport rule, kept in
 * the link factory: this guard only ever sees frames for the SDP-resolved GAIA v3 socket.
 */
class SennheiserWriteGuard(
    /** The headset's reported Sennheiser features (feature → version); null until `0x0001` answered. */
    private val reported: () -> Map<Int, Int>?,
) : GaiaWritePolicy {

    override fun refusal(frame: GaiaFrame): String? = refusal(frame, reported())

    companion object {
        /** Read before the feature list exists: nothing in them changes the headset. */
        private val QUALCOMM_DISCOVERY = setOf(
            GaiaDiscovery.CORE_API_VERSION, GaiaDiscovery.CORE_SUPPORTED_FEATURES, GaiaDiscovery.CORE_SUPPORTED_FEATURES_NEXT,
        )

        val BLOCKED_SENNHEISER_FEATURES: Set<Int> = setOf(SennheiserFeature.UPGRADE, SennheiserFeature.ROLE_SWITCH, SennheiserFeature.MCU)
        val BLOCKED_QUALCOMM_FEATURES: Set<Int> = setOf(4 /* debug */, 6 /* upgrade */)

        val BLOCKED_SENNHEISER: Map<Int, String> = mapOf(
            0x0040 to "factory reset",
            0x0200 to "firmware update (enter DFU)",
            0x0201 to "firmware update (file size)",
            0x060F to "reboot",
            0x1C00 to "role switch",
            0x1403 to "disconnect a paired device",
            0x1405 to "delete a pairing",
            0x1406 to "delete every pairing",
            0x080E to "raw sensor data",
            0x080F to "sensor calibration",
            0x2009 to "hearing profile reset",
            0x1604 to "button layout reset",
            0x2801 to "rename",
        )

        val BLOCKED_QUALCOMM: Map<Int, String> = mapOf(
            0x0009 to "data transfer",
            0x000A to "data transfer",
            0x000D to "transport switch",
            0x0601 to "voice assistant select",
            0x0803 to "panic log",
            0x0804 to "panic log erase",
            0x0C00 to "firmware update (connect)",
            0x0C01 to "firmware update (disconnect)",
            0x0C02 to "firmware update (control)",
        )

        /** `0x1600 [button, pattern, function]`: functions that power off or drop every BLE link. */
        const val BUTTON_MAPPING_SET = 0x1600
        val REFUSED_BUTTON_FUNCTIONS: Set<Int> = setOf(8, 19)

        /** The GETs Earshot sends, with their payload (empty unless given). */
        private val READS: Map<Int, ByteArray> = mapOf(
            C.SUPPORTED_FEATURES to ByteArray(0),
            C.ON_HEAD_GET to ByteArray(0),
            C.WEAR_STATE_GET to ByteArray(0),
            C.AUTO_OFF_GET to SennheiserWire.AUTO_OFF_TIMER,
            C.CHARGING_GET to ByteArray(0),
            C.BATTERY_GET to ByteArray(0),
            C.SIDETONE_GET to ByteArray(0),
            C.AUTO_ANSWER_GET to ByteArray(0),
            C.SMART_PAUSE_GET to ByteArray(0),
            C.COMFORT_CALL_GET to ByteArray(0),
            C.EQ_CONFIG_GET to ByteArray(0),
            C.EQ_ALL_GET to byteArrayOf(0),
            C.EQ_SUBMODE_GET to ByteArray(0),
            C.FIRMWARE_GET to ByteArray(0),
            C.MODEL_ID_GET to ByteArray(0),
            C.TH_BEHAVIOUR_GET to ByteArray(0),
            C.TH_LEVEL_GET to ByteArray(0),
            C.TH_GET to ByteArray(0),
            C.ANC_MODE_GET to ByteArray(0),
            C.ANC_LEVEL_GET to ByteArray(0),
            C.ANC_GET to ByteArray(0),
        )

        private fun bit(p: ByteArray) = p.size == 1 && (p[0].toInt() == 0 || p[0].toInt() == 1)
        private fun percent(p: ByteArray) = p.size == 1 && (p[0].toInt() and 0xFF) in 0..100

        /** The SETs Earshot sends, each with its payload check. */
        private val WRITES: Map<Int, (ByteArray) -> Boolean> = mapOf(
            C.ON_HEAD_SET to ::bit,
            C.AUTO_OFF_SET to { p -> p.size == 3 && p[0].toInt() == 0 && SennheiserWire.autoOffSeconds(p) in SennheiserWire.AUTO_OFF_CHOICES },
            C.SIDETONE_SET to { p -> p.size == 1 && p[0].toInt() in 0..2 },
            C.AUTO_ANSWER_SET to ::bit,
            C.SMART_PAUSE_SET to ::bit,
            C.COMFORT_CALL_SET to ::bit,
            C.EQ_BAND_SET to { p -> p.size == 2 && (p[0].toInt() and 0xFF) < 16 && p[1].toInt() in -120..120 },
            C.EQ_SUBMODE_SET to ::bit,
            C.TH_BEHAVIOUR_SET to ::bit,
            C.TH_LEVEL_SET to ::percent,
            C.TH_SET to ::bit,
            C.ANC_MODE_SET to ::modeTable,
            C.ANC_LEVEL_SET to ::percent,
            C.ANC_SET to ::bit,
        )

        /** Commands of the user EQ that exist only on generation-2 firmware (gen-1 reuses the ids). */
        private val EQ_GEN2_ONLY = setOf(C.EQ_CONFIG_GET, C.EQ_BAND_SET, C.EQ_ALL_GET, C.EQ_SUBMODE_SET, C.EQ_SUBMODE_GET)

        /** One, two or three distinct pairs: anti-wind 0..2, comfort and adaptive 0/1. */
        private fun modeTable(p: ByteArray): Boolean {
            if (p.isEmpty() || p.size % 2 != 0 || p.size > 6) return false
            val modes = HashSet<Int>()
            for (i in p.indices step 2) {
                val mode = p[i].toInt() and 0xFF
                val state = p[i + 1].toInt() and 0xFF
                val max = when (mode) {
                    C.MODE_ANTI_WIND -> 2
                    C.MODE_COMFORT, C.MODE_ADAPTIVE -> 1
                    else -> return false
                }
                if (state > max || !modes.add(mode)) return false
            }
            return true
        }

        /** Null when [frame] may be sent to a headset that reported [reported]; otherwise why not. */
        fun refusal(frame: GaiaFrame, reported: Map<Int, Int>?): String? {
            val command = frame.command
            if (GaiaCommand.type(command) != GaiaCommand.TYPE_COMMAND) return "not a request"
            val feature = GaiaCommand.feature(command)
            return when (frame.vendor) {
                GaiaVendor.QUALCOMM -> when {
                    command in BLOCKED_QUALCOMM -> "blocked: ${BLOCKED_QUALCOMM.getValue(command)}"
                    feature in BLOCKED_QUALCOMM_FEATURES -> "blocked: Qualcomm feature $feature"
                    command in QUALCOMM_DISCOVERY && frame.payload.isEmpty() -> null
                    else -> "not on the allow-list"
                }
                C.VENDOR -> sennheiserRefusal(frame, feature, reported)
                else -> "unknown vendor"
            }
        }

        private fun sennheiserRefusal(frame: GaiaFrame, feature: Int, reported: Map<Int, Int>?): String? {
            val command = frame.command
            val p = frame.payload
            BLOCKED_SENNHEISER[command]?.let { return "blocked: $it" }
            if (feature in BLOCKED_SENNHEISER_FEATURES) return "blocked: feature $feature"
            if (command == BUTTON_MAPPING_SET && p.size >= 3 && (p[2].toInt() and 0xFF) in REFUSED_BUTTON_FUNCTIONS) {
                return "blocked: button function ${p[2].toInt() and 0xFF}"
            }
            // The feature list itself is the one read allowed before the headset described itself.
            if (command == C.SUPPORTED_FEATURES) return if (p.isEmpty()) null else "unexpected payload"
            if (reported == null) return "the headset has not listed its features yet"
            if (feature !in reported) return "feature $feature not reported by the headset"
            if (command == C.REGISTER_NOTIFICATION) {
                val target = p.singleOrNull()?.toInt()?.and(0xFF) ?: return "bad registration"
                return if (target in SennheiserFeature.NOTIFY && target in reported) null else "registration for feature $target not allowed"
            }
            if (command in EQ_GEN2_ONLY && (reported[SennheiserFeature.USER_EQ] ?: 0) < SennheiserFeature.EQ_GEN2_MIN_VERSION) {
                return "EQ generation 1: not driven"
            }
            READS[command]?.let { expected -> return if (p.contentEquals(expected)) null else "unexpected payload" }
            WRITES[command]?.let { check -> return if (check(p)) null else "payload out of range" }
            return "not on the allow-list"
        }
    }
}
