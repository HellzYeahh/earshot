package dev.earshot.virtual

import dev.earshot.protocol.FunctionAddress
import dev.earshot.protocol.qcultra2.ModeLayout
import dev.earshot.protocol.qcultra2.ModePrompt
import dev.earshot.protocol.qcultra2.ModesCapabilities.Companion.FLAG_ANC_TOGGLE
import dev.earshot.protocol.qcultra2.ModesCapabilities.Companion.FLAG_AUTO_CNC
import dev.earshot.protocol.qcultra2.ModesCapabilities.Companion.FLAG_CNC
import dev.earshot.protocol.qcultra2.ModesCapabilities.Companion.FLAG_FAVORITES
import dev.earshot.protocol.qcultra2.ModesCapabilities.Companion.FLAG_SPATIAL
import dev.earshot.protocol.qcultra2.ModesCapabilities.Companion.FLAG_WIND
import dev.earshot.protocol.qcultra2.PRODUCT_ID_NC700
import dev.earshot.protocol.qcultra2.PRODUCT_ID_QC35_II
import dev.earshot.protocol.qcultra2.PRODUCT_ID_QC45
import dev.earshot.protocol.qcultra2.PRODUCT_ID_QC_EARBUDS
import dev.earshot.protocol.qcultra2.PRODUCT_ID_QC_EARBUDS_2
import dev.earshot.protocol.qcultra2.PRODUCT_ID_ULTRA_OPEN
import dev.earshot.protocol.qcultra2.QcUltra2
import dev.earshot.protocol.qcultra2.SpatialMode

/** A slot a virtual unit starts with: a built-in mode, or a custom mode already saved. */
data class VirtualSlot(
    val prompt: ModePrompt,
    val name: String,
    /** Wire scale, 0 = most cancelling. */
    val cncWire: Int,
    val spatial: Int = SpatialMode.OFF.code,
    val wind: Boolean = false,
    val anc: Boolean = true,
    /** STATUS byte 5 (the app's favourite bit) as the fw 10.12 dump has it. */
    val flag5: Int = 0,
    /** STATUS bytes 40-41; byte 41 = changeable fields. Null = the user-slot value 0x001d. */
    val word40: Int? = null,
)

/** `[2.2]` shape: one record (headphones) or right / left / combined / case records (earbuds). */
enum class VirtualBattery { SINGLE, BUDS }

/**
 * What one virtual Bose product looks like on the wire, so demo mode and the end-to-end tests can
 * run the real protocol against each model's quirks (docs/bose-models.md §A). Facts come from the
 * fw 10.12 capture (QC Ultra 2), bosectl's configs (QC45, QC Earbuds 2020, the #44 NC 700, the
 * Ultra Open report) and the Bose app; where they are silent the profile is a plausible guess, and
 * the comment on the profile says so.
 */
data class VirtualProfile(
    val productId: Int,
    /** `[0.15]` OriginalProductName (the app's `originalName`). */
    val productName: String,
    /** `[1.2]` the user's name for the unit. */
    val customName: String,
    val firmwareVersion: String,
    /** `[0.2]`: every other block answers FblockNotSupp. */
    val blocks: Set<Int>,
    /** Built-in modes, slots `0 until presets.size`. Empty for a product without block 31. */
    val presets: List<VirtualSlot>,
    /** Custom slots after the presets (`[31.2]` byte 1). */
    val customSlots: Int,
    /** Custom slots that already hold a mode, by slot index. */
    val savedModes: Map<Int, VirtualSlot> = emptyMap(),
    /** `[31.6]` STATUS / write layout. */
    val layout: ModeLayout = ModeLayout.STANDARD,
    /** `[31.2]` byte 5. */
    val modesFlags: Int = 0,
    /** `[31.10]` exists. */
    val liveAudioSettings: Boolean = true,
    /** `[1.5]` exists. */
    val hasCnc: Boolean = true,
    /** `[1.5]` SET_GET `[step, enabled]` is accepted (else OpNotSupp, as on wolverine). */
    val cncWritable: Boolean = false,
    /** `[1.5]` byte 2: bit 0 enabled, bit 1 set = the user cannot switch it off. */
    val cncFlags: Int = 0x03,
    val battery: VirtualBattery = VirtualBattery.SINGLE,
    /** Bud records carry a time-left estimate instead of `ffff`. */
    val batteryMinutes: Boolean = false,
    /** `[1.4]` auto-off minutes; null = the function is absent (FuncNotSupp), as on wolverine fw 10.12. */
    val standbyMinutes: Int? = null,
    /** Functions this model answers FuncNotSupp to. */
    val absent: Set<FunctionAddress> = emptySet(),
    /** Bose Connect era (QC35): only ProductInfo and the battery answer. */
    val legacyOnly: Boolean = false,
    /**
     * `[1.9]` records as hex, one STATUS frame each; null = the fw 10.12 capture (Shortcut 0x80,
     * long press). Earbuds get one record per bud (a guess: no earbuds `[1.9]` was ever captured).
     */
    val buttonRecords: List<String>? = null,
) {
    val slotCount: Int get() = presets.size + customSlots

    companion object {
        private val ULTRA_PRESETS = listOf(
            VirtualSlot(ModePrompt.QUIET, "Quiet", cncWire = 0, flag5 = 1, word40 = 0x0000),
            VirtualSlot(ModePrompt.AWARE, "Aware", cncWire = 10, flag5 = 1, word40 = 0x0002),
            VirtualSlot(ModePrompt.IMMERSION, "Immersion", cncWire = 0, spatial = SpatialMode.HEAD.code, flag5 = 1, word40 = 0x0000),
            VirtualSlot(ModePrompt.CINEMA, "Cinema", cncWire = 0, spatial = SpatialMode.ROOM.code, word40 = 0x0000),
        )
        private val QUIET_AWARE = ULTRA_PRESETS.take(2)
        /** fw 10.12 `[31.2]` byte 5 = `7f`: every flag the app reads, plus bit 6 (unread). */
        private const val WOLVERINE_FLAGS =
            FLAG_CNC or FLAG_AUTO_CNC or FLAG_SPATIAL or FLAG_WIND or FLAG_FAVORITES or FLAG_ANC_TOGGLE or 0x40

        /** `[0.2]` of fw 10.12 (`87cc23ff`). */
        private val WOLVERINE_BLOCKS: Set<Int> = setOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 13, 18, 19, 22, 23, 24, 25, 26, 31)
        /** A guess for the pre-2024 platforms: the blocks bosectl talks to, plus FirmwareUpdate and Notification. */
        private val LEGACY_BLOCKS: Set<Int> = setOf(0, 1, 2, 3, 4, 5, 7, 9, 31)
        private val LE_AND_LATENCY = setOf(
            QcUltra2.LE_AUDIO_SUPPORTED, QcUltra2.LE_AUDIO_CONTROL, QcUltra2.LE_AUDIO_CHECK, QcUltra2.LATENCY,
        )

        /** The user's unit, firmware 10.12.12 (the reference everything else is compared with). */
        val QC_ULTRA_2 = VirtualProfile(
            productId = QcUltra2.PRODUCT_ID, productName = "Bose QC Ultra 2 HP", customName = "Fargo",
            firmwareVersion = "10.12.12+ga927c9a", blocks = WOLVERINE_BLOCKS,
            presets = ULTRA_PRESETS, customSlots = 7,
            savedModes = mapOf(4 to VirtualSlot(ModePrompt.HOME, "Home", cncWire = 0, wind = true)),
            layout = ModeLayout.STANDARD, modesFlags = WOLVERINE_FLAGS, liveAudioSettings = true,
        )

        /** edith: the wolverine layout with bud and case batteries (bosectl's fixture `3cffff01 3cffff02 3cffff04 50ffff03`). */
        val QC_ULTRA_EARBUDS_2 = QC_ULTRA_2.copy(
            productId = QcUltra2.PRODUCT_ID_EARBUDS, productName = "Bose QC Ultra 2 Earbuds", customName = "Bose QC Ultra 2 Earbuds",
            firmwareVersion = "8.0.11+g5d1e2a0", battery = VirtualBattery.BUDS,
            savedModes = mapOf(4 to VirtualSlot(ModePrompt.COMMUTE, "Commute", cncWire = 2)),
            // Right bud (0x03) on Voice assistant, left bud (0x04) on Immersive Audio; both long
            // press with the headphones' menu mask. The Bose app lets each bud's shortcut be set
            // on its own; the exact wire form is a guess until an earbuds capture exists.
            buttonRecords = listOf("0309010009400200010002", "0409130009400200010002"),
        )

        /**
         * smalls: Quiet / Aware plus two custom modes, no spatial audio (Bose app copy). The rest is
         * a guess: no `[31.10]` (noise lives in the slots), the 44-byte layout, `[1.5]` read-only.
         */
        val QC_EARBUDS_2 = VirtualProfile(
            productId = PRODUCT_ID_QC_EARBUDS_2, productName = "Bose QC Earbuds II", customName = "Bose QC Earbuds II",
            firmwareVersion = "4.0.5+g3a7f2c1", blocks = LEGACY_BLOCKS,
            presets = QUIET_AWARE, customSlots = 2,
            savedModes = mapOf(2 to VirtualSlot(ModePrompt.MUSIC, "Music", cncWire = 3)),
            layout = ModeLayout.BASIC, modesFlags = FLAG_CNC or FLAG_FAVORITES, liveAudioSettings = false,
            battery = VirtualBattery.BUDS, batteryMinutes = true, standbyMinutes = 20,
            absent = LE_AND_LATENCY + QcUltra2.AUDIO_SETTINGS,
        )

        /**
         * lando (bosectl `qc_earbuds.py`, fw 2.0.7): 44-byte slots, no `[31.10]`, `[1.5]` SET_GET
         * `[step, enabled]`, no `[1.24]` / `[1.27]`. The 37-byte slot write is accepted here; on the
         * real earbuds it is untested. `[1.5]` letting the user switch ANC off is a guess.
         */
        val QC_EARBUDS = VirtualProfile(
            productId = PRODUCT_ID_QC_EARBUDS, productName = "Bose QuietComfort Earbuds", customName = "Bose QC Earbuds",
            firmwareVersion = "2.0.7+g8c4d9b2", blocks = LEGACY_BLOCKS,
            presets = QUIET_AWARE, customSlots = 2,
            layout = ModeLayout.BASIC, modesFlags = FLAG_CNC or FLAG_FAVORITES, liveAudioSettings = false,
            cncWritable = true, cncFlags = 0x01, battery = VirtualBattery.BUDS,
            absent = LE_AND_LATENCY + setOf(QcUltra2.AUDIO_SETTINGS, QcUltra2.AUTO_PLAY_PAUSE, QcUltra2.AUTO_ANSWER, QcUltra2.DISABLE_CAPTOUCH),
        )

        /** duran (bosectl, fw 4.0.4): 47-byte slots, wind in custom modes, no `[31.10]`, no ANC toggle. */
        val QC45 = VirtualProfile(
            productId = PRODUCT_ID_QC45, productName = "Bose QC 45", customName = "Bose QC45",
            firmwareVersion = "4.0.4+g1f3e5d7", blocks = LEGACY_BLOCKS,
            presets = QUIET_AWARE, customSlots = 2,
            savedModes = mapOf(2 to VirtualSlot(ModePrompt.COMMUTE, "Commute", cncWire = 3)),
            layout = ModeLayout.COMPACT, modesFlags = FLAG_CNC or FLAG_WIND or FLAG_FAVORITES, liveAudioSettings = false,
            standbyMinutes = 20,
            absent = LE_AND_LATENCY + setOf(QcUltra2.AUDIO_SETTINGS, QcUltra2.AUTO_PLAY_PAUSE, QcUltra2.AUTO_ANSWER, QcUltra2.DISABLE_CAPTOUCH),
        )

        /**
         * goodyear (bosectl #44, fw 2.0.4): no AudioModes block at all; Settings, EQ, battery and
         * `[4.x]` work. Writing the level with `[1.5]` is the Bose app's path, assumed to work here.
         */
        val NC700 = VirtualProfile(
            productId = PRODUCT_ID_NC700, productName = "Bose NC 700 Headphones", customName = "Bose NC 700 HP",
            firmwareVersion = "2.0.4+g6b2a8e3", blocks = LEGACY_BLOCKS - 31,
            presets = emptyList(), customSlots = 0, liveAudioSettings = false,
            cncWritable = true, cncFlags = 0x01, standbyMinutes = 60,
            absent = LE_AND_LATENCY + QcUltra2.DISABLE_CAPTOUCH,
        )

        /**
         * serena (bosectl #25, fw 4.0.22): open-ear, no noise cancelling, presets Stereo and
         * Immersion. The slot length was never captured: the 45-byte guess (spatial only) is used,
         * and two custom slots.
         */
        val ULTRA_OPEN = VirtualProfile(
            productId = PRODUCT_ID_ULTRA_OPEN, productName = "Bose Ultra Open Earbuds", customName = "Bose Ultra Open Earbuds",
            firmwareVersion = "4.0.22+g9e1c4f6", blocks = LEGACY_BLOCKS,
            presets = listOf(
                VirtualSlot(ModePrompt.STEREO, "Stereo", cncWire = 0, flag5 = 1, word40 = 0x0000),
                VirtualSlot(ModePrompt.IMMERSION, "Immersion", cncWire = 0, spatial = SpatialMode.HEAD.code, flag5 = 1, word40 = 0x0004),
            ),
            customSlots = 2,
            layout = ModeLayout.SPATIAL, modesFlags = FLAG_SPATIAL or FLAG_FAVORITES, liveAudioSettings = false,
            hasCnc = false, battery = VirtualBattery.BUDS,
            absent = setOf(QcUltra2.CNC, QcUltra2.AUDIO_SETTINGS, QcUltra2.LE_AUDIO_CONTROL, QcUltra2.LATENCY),
        )

        /** baywolf: Bose Connect era. Only ProductInfo and the battery are emulated (that is all Earshot reads). */
        val QC35_II = VirtualProfile(
            productId = PRODUCT_ID_QC35_II, productName = "Bose QC35 II", customName = "Bose QC35 II",
            firmwareVersion = "4.8.1", blocks = setOf(0, 1, 2),
            presets = emptyList(), customSlots = 0, liveAudioSettings = false, hasCnc = false, legacyOnly = true,
        )
    }
}

/** The virtual unit each Bose demo device runs; null for the Crusher demos (their own simulator). */
fun DemoDevice.virtualProfile(): VirtualProfile? = when (this) {
    DemoDevice.QC_ULTRA -> VirtualProfile.QC_ULTRA_2
    DemoDevice.QC_ULTRA_EARBUDS_2 -> VirtualProfile.QC_ULTRA_EARBUDS_2
    DemoDevice.QC_EARBUDS_2 -> VirtualProfile.QC_EARBUDS_2
    DemoDevice.QC_EARBUDS -> VirtualProfile.QC_EARBUDS
    DemoDevice.QC45 -> VirtualProfile.QC45
    DemoDevice.NC700 -> VirtualProfile.NC700
    DemoDevice.ULTRA_OPEN -> VirtualProfile.ULTRA_OPEN
    DemoDevice.QC35_II -> VirtualProfile.QC35_II
    DemoDevice.CRUSHER, DemoDevice.CRUSHER_SECURE, DemoDevice.AIRPODS_PRO, DemoDevice.SOUNDCORE_SLEEP_A30 -> null
    DemoDevice.SOUNDCORE_LIBERTY_4_NC, DemoDevice.SOUNDCORE_SPACE_ONE_PRO -> null
    DemoDevice.METHOD_360, DemoDevice.METHOD_540 -> null
    DemoDevice.SENNHEISER_MOMENTUM_4 -> null
}
