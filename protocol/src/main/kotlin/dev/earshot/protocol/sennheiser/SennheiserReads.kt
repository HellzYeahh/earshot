package dev.earshot.protocol.sennheiser

import dev.earshot.protocol.gaia.GaiaCommand
import dev.earshot.protocol.gaia.GaiaDiscovery
import dev.earshot.protocol.gaia.GaiaFrame
import dev.earshot.protocol.gaia.GaiaVendor
import dev.earshot.protocol.sennheiser.SennheiserCommands as C

/** One documented GAIA read for the Lab. [group] is the Sennheiser feature, or [SennheiserReads.DISCOVERY_GROUP]. */
class SennheiserRead(
    val vendor: Int,
    val command: Int,
    val name: String,
    val group: Int,
    payload: ByteArray = ByteArray(0),
    /** Smart Control waits 9-10 s for battery and version reads; so does Earshot. */
    val slow: Boolean = false,
) {
    private val body = payload.copyOf()
    val payload: ByteArray get() = body.copyOf()
    fun frame(): GaiaFrame = GaiaFrame(vendor, command, body.copyOf())
    val address: String get() = GaiaCommand.name(vendor, command)
}

/**
 * The Lab's read list for Sennheiser (GAIA v3). In GAIA a command id is `feature << 9 | pdu`, and
 * the ids next to the reads are factory reset (`0x0040`), firmware update (feature 1) and pairing
 * deletes (`0x1405`/`0x1406`), so the Lab never walks ids: it sends the discovery reads, then the
 * GETs on [SennheiserWriteGuard.READS] for the features the headset reported, each one checked
 * against the guard with that feature list ([forFeatures]).
 */
object SennheiserReads {
    /** The scan group of the discovery reads (Qualcomm API version and both feature lists). */
    const val DISCOVERY_GROUP = 0

    val DISCOVERY: List<SennheiserRead> = listOf(
        SennheiserRead(GaiaVendor.QUALCOMM, GaiaDiscovery.CORE_API_VERSION, "Qualcomm GAIA API version", DISCOVERY_GROUP),
        SennheiserRead(GaiaVendor.QUALCOMM, GaiaDiscovery.CORE_SUPPORTED_FEATURES, "Qualcomm feature list", DISCOVERY_GROUP),
        SennheiserRead(C.VENDOR, C.SUPPORTED_FEATURES, "Sennheiser feature list", DISCOVERY_GROUP, slow = true),
    )

    /** Names for every GET on the guard's read list (a test keeps the two in step). */
    val NAMES: Map<Int, String> = mapOf(
        C.ON_HEAD_GET to "On-head detection",
        C.WEAR_STATE_GET to "Wear state",
        C.AUTO_OFF_GET to "Auto power-off",
        C.CHARGING_GET to "Charging",
        C.BATTERY_GET to "Battery",
        C.SIDETONE_GET to "Sidetone",
        C.AUTO_ANSWER_GET to "Auto answer",
        C.SMART_PAUSE_GET to "Smart pause",
        C.COMFORT_CALL_GET to "Comfort call",
        C.EQ_CONFIG_GET to "EQ configuration",
        C.EQ_ALL_GET to "EQ gains",
        C.EQ_SUBMODE_GET to "EQ sub-mode (bass boost)",
        C.FIRMWARE_GET to "Firmware version",
        C.MODEL_ID_GET to "Model and colour",
        C.TH_BEHAVIOUR_GET to "Transparency: pause music",
        C.TH_LEVEL_GET to "Transparency level",
        C.TH_GET to "Transparency",
        C.ANC_MODE_GET to "ANC mode table (anti-wind, comfort, adaptive)",
        C.ANC_LEVEL_GET to "ANC level",
        C.ANC_GET to "ANC",
    )

    private val SLOW = setOf(C.BATTERY_GET, C.FIRMWARE_GET, C.MODEL_ID_GET)

    /** Every feature GET Earshot knows, feature by feature (the feature list read is in [DISCOVERY]). */
    val FEATURE_READS: List<SennheiserRead> = SennheiserWriteGuard.READS
        .filterKeys { it != C.SUPPORTED_FEATURES }
        .toSortedMap()
        .map { (command, payload) ->
            SennheiserRead(C.VENDOR, command, NAMES[command] ?: C.name(command), GaiaCommand.feature(command), payload, slow = command in SLOW)
        }

    /** Sennheiser feature names (notes §3), for the scan groups and the "reported, nothing to read" note. */
    val FEATURE_NAMES: Map<Int, String> = mapOf(
        SennheiserFeature.CORE to "Core",
        SennheiserFeature.UPGRADE to "Firmware upgrade",
        SennheiserFeature.DEVICE to "Device",
        SennheiserFeature.BATTERY to "Battery and power",
        SennheiserFeature.GENERIC_AUDIO to "Generic audio",
        SennheiserFeature.USER_EQ to "User EQ",
        SennheiserFeature.VERSIONS to "Versions",
        SennheiserFeature.DEVICE_MANAGEMENT to "Device management",
        SennheiserFeature.MMI to "Buttons",
        SennheiserFeature.TRANSPARENT_HEARING to "Transparent hearing",
        SennheiserFeature.ANC to "ANC",
        SennheiserFeature.ROLE_SWITCH to "Role switch",
        SennheiserFeature.FIT_TEST to "Fit test",
        SennheiserFeature.PERSONALISED_SOUND to "Personalised sound",
        SennheiserFeature.SENSORS to "Sensors",
        SennheiserFeature.MCU to "MCU",
    )

    fun groupName(group: Int): String = if (group == DISCOVERY_GROUP) "Discovery" else FEATURE_NAMES[group] ?: "Feature $group"

    /** A read the guard lets out for a headset that reported [reported] (null before the list arrived). */
    fun isRead(read: SennheiserRead, reported: Map<Int, Int>?): Boolean {
        val frame = read.frame()
        if (GaiaCommand.type(frame.command) != GaiaCommand.TYPE_COMMAND) return false
        val known = (read.vendor == C.VENDOR && read.command in SennheiserWriteGuard.READS) || read in DISCOVERY
        return known && SennheiserWriteGuard.refusal(frame, reported) == null
    }

    /** Discovery, then every documented GET of each reported feature the guard lets out (EQ gen-2 reads only on gen-2 firmware). */
    fun forFeatures(reported: Map<Int, Int>?): List<SennheiserRead> =
        DISCOVERY.filter { isRead(it, reported) } + FEATURE_READS.filter { isRead(it, reported) }

    /** Reported features with no documented GET: listed in the Lab, never scanned. */
    fun unreadFeatures(reported: Map<Int, Int>?): List<Int> {
        val readable = FEATURE_READS.map { it.group }.toSet() + SennheiserFeature.CORE
        return reported.orEmpty().keys.filter { it !in readable }.sorted()
    }
}
