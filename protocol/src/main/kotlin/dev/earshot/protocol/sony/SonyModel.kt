package dev.earshot.protocol.sony

/**
 * How a Sony headset is recognised before and after connecting. The control service is found by
 * SDP UUID, never by channel: many Sony models are Airoha-based and also expose the Airoha RACE
 * debug channel (CVE-2025-20700/20701/20702), which Earshot must never open.
 *
 * Sound Connect picks the command-table set from the UUID the headset advertises, v2 first, and
 * accepts each UUID **byte-reversed** too ("Android was return a reversed UUID", its own log); the
 * protocol-info reply length (8 bytes v2, 4 bytes v1) confirms the choice.
 */
object SonyModel {
    /** MDR v2 (WF-1000XM4 and every 2022+ model). */
    const val SERVICE_V2 = "956c7b26-d49a-4ba8-b03f-b17d393cb6e2"

    /** MDR v1 (WH-1000XM4 and older): not driven; such a headset stays a generic one. */
    const val SERVICE_V1 = "96cc203e-5068-46ad-b32d-e316f5e069ba"

    /** The same 16 bytes in reverse order, as some Android stacks report SDP UUIDs. */
    fun reversed(uuid: String): String {
        val hex = uuid.replace("-", "").lowercase()
        require(hex.length == 32) { "not a UUID: $uuid" }
        val r = hex.chunked(2).reversed().joinToString("")
        return "${r.substring(0, 8)}-${r.substring(8, 12)}-${r.substring(12, 16)}-${r.substring(16, 20)}-${r.substring(20)}"
    }

    val SERVICE_V2_REVERSED: String = reversed(SERVICE_V2)
    val SERVICE_V1_REVERSED: String = reversed(SERVICE_V1)

    fun isV2Service(uuid: String): Boolean = uuid.lowercase().let { it == SERVICE_V2 || it == SERVICE_V2_REVERSED }
    fun isV1Service(uuid: String): Boolean = uuid.lowercase().let { it == SERVICE_V1 || it == SERVICE_V1_REVERSED }

    /**
     * MDR v2 models by their Bluetooth name (also with an `LE_` prefix): WH-1000XM5/XM6,
     * WF-1000XM4/XM5/XM6, the LinkBuds family, ULT WEAR (WH-ULT900N), WH-CH720N, WF-C510,
     * WF-C700N and WF-C710N. The WH-1000XM4 is MDR v1 and deliberately not listed.
     */
    private val V2_NAME = Regex(
        """\bWH-1000XM[56]\b|\bWF-1000XM[456]\b|\bLinkBuds\b|\bWF-L(?:S)?9\d0N?\b|\bWF-LC900\b|\bULT\s*WEAR\b|\bWH-ULT900N\b|\bWH-CH720N\b|\bWF-C(?:510|700N|710N)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** LE Audio identities carry an `LE_` prefix, which `\b` would not separate from the model. */
    fun isSonyName(name: String?): Boolean =
        name != null && V2_NAME.containsMatchIn(name.trim().removePrefix("LE_").removePrefix("LE-"))

    /** Open-ear LinkBuds (the ring and the clip), by the model name the headset reports. */
    private val OPEN_EAR = Regex("""^WF-L(?:900|910|C900)\b|^LinkBuds(?:\s+(?:Open|Clip))?$""", RegexOption.IGNORE_CASE)

    enum class Shape { HEADPHONES, EARBUDS, OPEN_EARBUDS }

    /** From the MODEL_NAME string (e.g. "WH-1000XM5", "WF-1000XM5"), with the series byte as a fallback. */
    fun shapeOf(modelName: String?, series: Int? = null): Shape = when {
        modelName != null && OPEN_EAR.containsMatchIn(modelName.trim()) -> Shape.OPEN_EARBUDS
        modelName != null && modelName.trim().startsWith("WF-", ignoreCase = true) -> Shape.EARBUDS
        modelName != null && modelName.contains("LinkBuds", ignoreCase = true) -> Shape.EARBUDS
        modelName != null -> Shape.HEADPHONES
        series == 0x60 -> Shape.EARBUDS
        else -> Shape.HEADPHONES
    }
}
