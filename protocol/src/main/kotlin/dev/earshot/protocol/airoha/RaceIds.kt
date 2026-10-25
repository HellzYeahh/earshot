package dev.earshot.protocol.airoha

/**
 * The RACE ids Earshot knows by name: the Airoha-standard settings a companion app uses, the
 * Harman-reserved ids JBL adds on its Airoha models, and the ranges that must never be sent.
 * Source: the JBL app's Airoha SDK (`RaceId`, `MmiStage_*`) and the ERNW disclosure, as recorded in
 * docs/research/research_notes/Top brand headset protocols/jbl.md §3–4. Facts only, no code.
 */
object RaceIds {
    // ---- Airoha standard ------------------------------------------------------------------

    /** ANC / pass-through. Payload `00 0A filter [mode]` on, `00 0B` off, `00 0F gain16` pass-through gain, `00 1E level16` level. */
    const val ANC_PASSTHROUGH = 0x0E06

    /** HOSTAUDIO_MMI_SET_ENUM: `module16 value`. Module 0 = EQ preset group, 1 = voice prompts. */
    const val MMI_SET_ENUM = 0x0900

    /** HOSTAUDIO_MMI_GET_ENUM: `module16`. Modules 0 EQ group, 1 prompts, 5 ANC status, 6 Smart Audio & Video, 7 Ambient Aware level. */
    const val MMI_GET_ENUM = 0x0901

    /** TWS battery: payload `00` agent / `01` partner; the reply is an indication (`5D`). */
    const val TWS_BATTERY = 0x0CD6

    /** Role queries (agent / partner): reads. */
    const val ROLE_QUERY_A = 0x0CC4
    const val ROLE_QUERY_B = 0x0CD4

    /** Settings get / set with a module: 1 auto power-off, 9 prompt language, 11 hotword, 19 voice EQ. */
    const val SETTINGS_SET = 0x2C82
    const val SETTINGS_GET = 0x2C83

    /** Find me: query, and `blink ring target` (a tone in the buds: only from the user's own tap). */
    const val FIND_ME_QUERY = 0x2C00
    const val FIND_ME = 0x2C01

    // ---- Never sent ------------------------------------------------------------------------

    /** Relay to the partner bud: wraps another RACE frame. Refused until its wrapper is captured. */
    const val RELAY = 0x0D01

    /** JBL's tunnel for a raw Harman `AA` frame: refused (the H-AA block-list would have to apply inside). */
    const val HARMAN_TUNNEL = 0x0093

    /** MMI key codes: the safe ones were never enumerated, so none is sent. */
    const val MMI_KEY = 0x1101

    private val NAMES = mapOf(
        ANC_PASSTHROUGH to "ANC/pass-through",
        MMI_SET_ENUM to "MMI set enum",
        MMI_GET_ENUM to "MMI get enum",
        TWS_BATTERY to "TWS battery",
        ROLE_QUERY_A to "role query",
        ROLE_QUERY_B to "role query",
        SETTINGS_SET to "settings set",
        SETTINGS_GET to "settings get",
        FIND_ME_QUERY to "find-me query",
        FIND_ME to "find me",
        RELAY to "relay",
        HARMAN_TUNNEL to "Harman tunnel",
        MMI_KEY to "MMI key",
    )

    fun name(id: Int): String = NAMES[id]?.let { "$it (0x%04X)".format(id) } ?: "0x%04X".format(id)
}
