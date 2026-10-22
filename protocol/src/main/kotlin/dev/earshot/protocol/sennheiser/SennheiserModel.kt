package dev.earshot.protocol.sennheiser

import java.util.UUID

/** How a model's noise control works (Sennheiser notes §3). */
enum class SennheiserNoiseFamily {
    /** Momentum 4, Accentum Plus (and the Momentum 5): ANC on/off, a 0..100 ANC↔transparency level, the mode table. */
    SLIDER,

    /** Accentum: ANC and transparency as two on/off switches; its mode table reads zeros and ignores writes. */
    TOGGLES,

    /** True wireless: ANC on/off with anti-wind, transparency on/off with its own level. */
    EARBUDS,
}

/** A setting the model's file in the Sennheiser app declares (so Earshot offers it). */
enum class SennheiserSetting {
    ANTI_WIND, ADAPTIVE, COMFORT, TRANSPARENCY_LEVEL, PAUSE_IN_TRANSPARENCY,
    ON_HEAD, SMART_PAUSE, AUTO_ANSWER, COMFORT_CALL, AUTO_OFF, SIDETONE,
}

/**
 * The Sennheiser models Earshot drives over classic RFCOMM (GAIA v3, vendor 0x0495). Identified
 * first by the Bluetooth name (the app's name map), then exactly by the `0x1206` model string
 * ("M4AEBT Black"). Not here, so they stay generic: the CX Plus TW and CX 500BT (BLE only in the
 * app), the 2020 Momentum True Wireless 2 and HD 4x0BT line (GAIA v1/v2), the Momentum 3 / PXC 550
 * (another protocol) and the Smart Control Plus generation apart from the Momentum 5, whose
 * captures show the same commands. The app's internal codenames (Mars, Venus, Aphrodite, Magnum)
 * are left out of the name rule: a device someone named "Mars" should not be claimed.
 */
enum class SennheiserModel(
    val displayName: String,
    /** Start of the `0x1206` model string. */
    private val modelIdPrefix: String,
    val noise: SennheiserNoiseFamily,
    val earbuds: Boolean,
    private val nameRule: Regex,
    val settings: Set<SennheiserSetting>,
) {
    MOMENTUM_4(
        "MOMENTUM 4", "M4AEBT", SennheiserNoiseFamily.SLIDER, earbuds = false,
        name("""\bmomentum\s*4\b"""), HEADBAND_SLIDER,
    ),

    /** Smart Control Plus generation; momentum4-control's captures (firmware 6.21.5) show the same commands. */
    MOMENTUM_5(
        "MOMENTUM 5", "M5AEBT", SennheiserNoiseFamily.SLIDER, earbuds = false,
        name("""\bmomentum\s*5\b"""), HEADBAND_SLIDER,
    ),
    ACCENTUM_PLUS(
        "ACCENTUM Plus", "ACPAEBT", SennheiserNoiseFamily.SLIDER, earbuds = false,
        name("""\baccentum\s*plus\b"""), HEADBAND_SLIDER,
    ),
    ACCENTUM(
        "ACCENTUM", "ACAEBT", SennheiserNoiseFamily.TOGGLES, earbuds = false,
        name("""\baccentum\b(?!\s*(?:plus|tw|true|clip))"""),
        setOf(
            SennheiserSetting.PAUSE_IN_TRANSPARENCY, SennheiserSetting.ON_HEAD, SennheiserSetting.AUTO_ANSWER,
            SennheiserSetting.COMFORT_CALL, SennheiserSetting.AUTO_OFF, SennheiserSetting.SIDETONE,
        ),
    ),
    MOMENTUM_TW3(
        "MOMENTUM True Wireless 3", "MTW3", SennheiserNoiseFamily.EARBUDS, earbuds = true,
        name("""\bmomentum\s*(?:tw|true\s*wireless)\s*3\b"""), EARBUD_SETTINGS,
    ),
    MOMENTUM_TW4(
        "MOMENTUM True Wireless 4", "MTW4", SennheiserNoiseFamily.EARBUDS, earbuds = true,
        name("""\bmomentum\s*(?:tw|true\s*wireless)\s*4\b"""), EARBUD_SETTINGS,
    ),
    ACCENTUM_TW(
        "ACCENTUM True Wireless", "ATW1", SennheiserNoiseFamily.EARBUDS, earbuds = true,
        name("""\baccentum\s*(?:tw|true\s*wireless)\b"""), EARBUD_SETTINGS,
    ),
    MOMENTUM_SPORT(
        "MOMENTUM Sport", "MSPORT1", SennheiserNoiseFamily.EARBUDS, earbuds = true,
        name("""\bmomentum\s*sport|\bmsport1\b"""), EARBUD_SETTINGS,
    ),
    ;

    companion object {
        /**
         * Sennheiser's GAIA v3 RFCOMM service (`GAIA3_SPP_UUID` in Smart Control). The only socket
         * Earshot opens on these headsets, always by SDP: channels differ by model (Accentum 15,
         * Momentum 4 on 1 and 2) and the Accentum's iAP2 record (channel 14, the `deca-fade`
         * UUID) is reported to power the headset off when it sees GAIA frames.
         */
        val GAIA3_SERVICE: UUID = UUID.fromString("a2129ff3-081b-4c45-8afe-469d9c4842ec")

        /** The GAIA v1/v2 service of the 2020 models (MTW2, HD 4x0BT): recognised, never opened. */
        val GAIA_LEGACY_SERVICE: UUID = UUID.fromString("a2129ff3-081b-4c45-8afe-469d9c4842eb")

        fun fromName(name: String?): SennheiserModel? = name?.let { n -> entries.firstOrNull { it.nameRule.containsMatchIn(n) } }

        /** The model a `0x1206` string names ("M4AEBT Black" → [MOMENTUM_4]), or null for one Earshot doesn't drive. */
        fun fromModelId(modelId: String?): SennheiserModel? {
            val id = modelId?.trim()?.uppercase() ?: return null
            // Longest prefix first: "ACPAEBT" must not be read as "ACAEBT"-something.
            return entries.sortedByDescending { it.modelIdPrefix.length }.firstOrNull { id.startsWith(it.modelIdPrefix) }
        }
    }
}

private fun name(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

private val HEADBAND_SLIDER = setOf(
    SennheiserSetting.ANTI_WIND, SennheiserSetting.ADAPTIVE, SennheiserSetting.COMFORT, SennheiserSetting.PAUSE_IN_TRANSPARENCY,
    SennheiserSetting.ON_HEAD, SennheiserSetting.SMART_PAUSE, SennheiserSetting.AUTO_ANSWER, SennheiserSetting.COMFORT_CALL,
    SennheiserSetting.AUTO_OFF, SennheiserSetting.SIDETONE,
)

private val EARBUD_SETTINGS = setOf(
    SennheiserSetting.ANTI_WIND, SennheiserSetting.TRANSPARENCY_LEVEL, SennheiserSetting.PAUSE_IN_TRANSPARENCY,
    SennheiserSetting.SMART_PAUSE, SennheiserSetting.AUTO_ANSWER, SennheiserSetting.AUTO_OFF, SennheiserSetting.SIDETONE,
)
