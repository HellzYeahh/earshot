package dev.earshot.protocol.sennheiser

import dev.earshot.protocol.gaia.GaiaCommand

/**
 * Sennheiser's GAIA v3 command ids (vendor [VENDOR] = 0x0495), identical across every 2021+ model
 * file in Smart Control 4.9.3 (facts only, the app is proprietary) and cross-checked against the
 * MIT-licensed momentum4-control catalogue and captures and momentumctl. SET ids are even, GET ids
 * odd; a response adds 0x0100, a notification 0x0080, an error 0x0180 (see [GaiaCommand]).
 * Multi-byte values are big-endian. Full tables: docs/research/research_notes/Top brand headset
 * protocols/sennheiser.md §3.
 */
object SennheiserCommands {
    const val VENDOR = 0x0495

    // ---- Feature 0: core ------------------------------------------------------------------
    /** `→ [moreData, (feature, version)…]`; Momentum 4 3.37.3: features 9, 8 (v6), 12, 3, 16, 4, 2, 10, 20, 11, 13, 0. */
    const val SUPPORTED_FEATURES = 0x0001
    /** `[feature]`, acked with an empty `0x0107`; the headset then pushes that feature's state. */
    const val REGISTER_NOTIFICATION = 0x0007

    // ---- Feature 2: device ----------------------------------------------------------------
    const val ON_HEAD_SET = 0x0400
    const val ON_HEAD_GET = 0x0401
    /** `→ [headset or left, right]`: 0 unknown, 1 in case, 2 not on head, 3 on head. */
    const val WEAR_STATE_GET = 0x0402

    // ---- Feature 3: battery and power -----------------------------------------------------
    /** `[timer u8, seconds u16]`; timer 0 = power off. The app offers never / 900 / 1800 / 3600 s. No push follows. */
    const val AUTO_OFF_SET = 0x0600
    /** `[timer]` → `[timer, seconds u16]`. */
    const val AUTO_OFF_GET = 0x0601
    /** `→ [L(, R)]`: 0 not charging, 1 charging, 2 complete. */
    const val CHARGING_GET = 0x0602
    /** `→ [p]`, `[L, R]` or `[L, R, case]` percent, 0xFF = unknown (bud in the case, charging). */
    const val BATTERY_GET = 0x0603

    // ---- Feature 4: generic audio ---------------------------------------------------------
    const val SIDETONE_SET = 0x0805
    /** `→ [level]`: the app's UI uses 0 off / 1 low / 2 high (the model files say 0..5). */
    const val SIDETONE_GET = 0x0806
    const val AUTO_ANSWER_SET = 0x080A
    const val AUTO_ANSWER_GET = 0x080B
    /** Smart pause (pause when taken off); on headbands only with on-head detection on. */
    const val SMART_PAUSE_SET = 0x080C
    const val SMART_PAUSE_GET = 0x080D
    const val COMFORT_CALL_SET = 0x0814
    const val COMFORT_CALL_GET = 0x0815

    // ---- Feature 8: user EQ, generation 2 (feature version ≥ 6) ---------------------------
    /** `→ [bands, min i8, max i8, presets, userPresets]`, gains in tenths of a dB (Momentum 5: `08 C4 3C 00 00`). */
    const val EQ_CONFIG_GET = 0x1000
    /** `[band, gain i8]`, band 0-based, gain in tenths of a dB. */
    const val EQ_BAND_SET = 0x1001
    /** Gen-2: GET one band. Gen-1 firmware uses this id to SET a parameter group: never sent. Its push (`0x1082`) carries all gains. */
    const val EQ_BAND_GET = 0x1002
    /** Gen-2: `[0]` → every gain (`3C 3C 16 16 0A` = 6.0 / 6.0 / 2.2 / 2.2 / 1.0 dB on a Momentum 4). */
    const val EQ_ALL_GET = 0x1003
    /** Sub-mode bitmask, bit 0 = bass boost. */
    const val EQ_SUBMODE_SET = 0x1008
    const val EQ_SUBMODE_GET = 0x1009

    // ---- Feature 9: versions --------------------------------------------------------------
    /** `→ [major u16, minor u16, patch u16]`. */
    const val FIRMWARE_GET = 0x1201
    /** `→` NUL-terminated model and colour, e.g. "M4AEBT Black". */
    const val MODEL_ID_GET = 0x1206

    // ---- Feature 12: transparent hearing --------------------------------------------------
    /** `[0 keep music playing | 1 pause]` while transparency is full. */
    const val TH_BEHAVIOUR_SET = 0x1800
    const val TH_BEHAVIOUR_GET = 0x1801
    /** Earbuds: transparency level; the app offers 1 / 50 / 100. */
    const val TH_LEVEL_SET = 0x1802
    const val TH_LEVEL_GET = 0x1803
    /** `[0|1]`. On a Momentum 4, on sets the ANC level to 100; off restores the level from before. */
    const val TH_SET = 0x1804
    const val TH_GET = 0x1805

    // ---- Feature 13: ANC ------------------------------------------------------------------
    /**
     * Mode table `[mode, state]` pairs (one or all three): mode 1 anti-wind (0 off, 1 on/max, 2 auto),
     * 2 comfort (0/1), 3 adaptive (0/1). On a Momentum 4 a write also switches transparency off
     * and sets the level to 0. The Accentum reads zeros and ignores writes.
     */
    const val ANC_MODE_SET = 0x1A00
    const val ANC_MODE_GET = 0x1A01
    /** `[0..100]`, 0 = full ANC, 100 = full transparency (Momentum 4, Accentum Plus slider). */
    const val ANC_LEVEL_SET = 0x1A02
    const val ANC_LEVEL_GET = 0x1A03
    /** `[0|1]`. On a Momentum 4, on also switches transparency off and sets the level to 0. */
    const val ANC_SET = 0x1A04
    const val ANC_GET = 0x1A05

    /** The anti-wind / comfort / adaptive entries of the mode table. */
    const val MODE_ANTI_WIND = 1
    const val MODE_COMFORT = 2
    const val MODE_ADAPTIVE = 3

    fun name(command: Int): String = GaiaCommand.name(VENDOR, command)
}

/** Sennheiser feature numbers (`command >> 9`). */
object SennheiserFeature {
    const val CORE = 0
    const val UPGRADE = 1
    const val DEVICE = 2
    const val BATTERY = 3
    const val GENERIC_AUDIO = 4
    const val USER_EQ = 8
    const val VERSIONS = 9
    const val DEVICE_MANAGEMENT = 10
    const val MMI = 11
    const val TRANSPARENT_HEARING = 12
    const val ANC = 13
    const val ROLE_SWITCH = 14
    const val FIT_TEST = 15
    const val PERSONALISED_SOUND = 16
    const val SENSORS = 17
    const val MCU = 18

    /** Feature 8 from this version on is the generation-2 EQ (version 6 on Momentum 4 firmware 3.37.3). */
    const val EQ_GEN2_MIN_VERSION = 6

    /** The features Earshot registers for pushes, when the headset reports them. */
    val NOTIFY: List<Int> = listOf(BATTERY, ANC, TRANSPARENT_HEARING, DEVICE, GENERIC_AUDIO, USER_EQ)
}
