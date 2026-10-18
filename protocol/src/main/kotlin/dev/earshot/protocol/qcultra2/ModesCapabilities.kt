package dev.earshot.protocol.qcultra2

/*
 * What the AudioModes block says about itself: `[31.2]` Capabilities, and the names the Bose app
 * gives the modes. docs/bose-models.md §C2, §E3.
 */

/**
 * `[31.2]` GET, the Bose app's `AudioModesCapabilitiesResponse.createFromPacket`:
 * ```
 * 0      boseModes   (built-in slots, numbered first)
 * 1      userModes   (custom slots after them)
 * 2-4    not read by the app
 * 5      flags: bit0 cnc, bit1 autoCnc, bit2 spatial (head / room), bit3 wind toggle,
 *               bit4 user favourites, bit5 ANC toggle
 * 6      minimum number of favourites (only when the payload has 7 bytes, else 0)
 * ```
 * The fw 10.12 capture `04 07 00 00 00 7f 02` = 4 presets, 7 custom, every flag, at least two
 * favourites (bit 6 of `7f` is set but unread by the app). The app's maximum mode count is
 * `boseModes + userModes`.
 */
data class ModesCapabilities(
    val boseModes: Int,
    val userModes: Int,
    val cnc: Boolean,
    val autoCnc: Boolean,
    val spatial: Boolean,
    val windBlock: Boolean,
    val favorites: Boolean,
    val ancToggle: Boolean,
    val minFavorites: Int,
) {
    val totalModes: Int get() = boseModes + userModes

    /** Built-in slots `0 until boseModes`. */
    val presetSlots: IntRange get() = 0 until boseModes

    /** Custom slots right after the presets; empty when the firmware reports none (some QC45 firmware). */
    val customSlots: IntRange get() = if (userModes <= 0) IntRange.EMPTY else boseModes until totalModes

    companion object {
        const val FLAG_CNC = 0x01
        const val FLAG_AUTO_CNC = 0x02
        const val FLAG_SPATIAL = 0x04
        const val FLAG_WIND = 0x08
        const val FLAG_FAVORITES = 0x10
        const val FLAG_ANC_TOGGLE = 0x20

        /** Null when shorter than the six bytes the app reads unconditionally. */
        fun parse(payload: ByteArray): ModesCapabilities? {
            if (payload.size < 6) return null
            val flags = payload.u8(5)
            return ModesCapabilities(
                boseModes = payload.u8(0),
                userModes = payload.u8(1),
                cnc = flags and FLAG_CNC != 0,
                autoCnc = flags and FLAG_AUTO_CNC != 0,
                spatial = flags and FLAG_SPATIAL != 0,
                windBlock = flags and FLAG_WIND != 0,
                favorites = flags and FLAG_FAVORITES != 0,
                ancToggle = flags and FLAG_ANC_TOGGLE != 0,
                minFavorites = if (payload.size >= 7) payload.u8(6) else 0,
            )
        }

        /** Device side (the virtual unit): the 7-byte form. */
        fun build(boseModes: Int, userModes: Int, flags: Int, minFavorites: Int = 2): ByteArray =
            byteArrayOf(boseModes.toByte(), userModes.toByte(), 0, 0, 0, flags.toByte(), minFavorites.toByte())
    }
}

/**
 * Display names for listening modes. The Bose app names a mode by its 2-byte prompt id, not by the
 * 32-byte name field: `ModeNameCollection.c(prompt)` returns its own string for Quiet, Aware,
 * Immersion, Stereo and Cinema and for the custom-name list (Commute, Focus, Home, Music, Outdoor,
 * Relax, Run, Walk, Work, Workout), so a mode named in another language still reads the same
 * (docs/bose-models.md §C2). Other prompts fall back to the UTF-8 name, then "Mode N".
 */
object ModeNames {
    val BY_PROMPT: Map<ModePrompt, String> = mapOf(
        ModePrompt.QUIET to "Quiet",
        ModePrompt.AWARE to "Aware",
        ModePrompt.IMMERSION to "Immersion",
        ModePrompt.STEREO to "Stereo",
        ModePrompt.CINEMA to "Cinema",
        ModePrompt.COMMUTE to "Commute",
        ModePrompt.FOCUS to "Focus",
        ModePrompt.HOME to "Home",
        ModePrompt.MUSIC to "Music",
        ModePrompt.OUTDOOR to "Outdoor",
        ModePrompt.RELAX to "Relax",
        ModePrompt.RUN to "Run",
        ModePrompt.WALK to "Walk",
        ModePrompt.WORK to "Work",
        ModePrompt.WORKOUT to "Workout",
    )

    /** The name the app shows for [prompt], or null when it would use the slot's own name. */
    fun forPrompt(prompt: ModePrompt?): String? = prompt?.let { BY_PROMPT[it] }

    /** Prompt name, else the UTF-8 name (unless it is the "None" free-slot sentinel), else "Mode N" (1-based). */
    fun displayName(config: ModeConfig): String =
        forPrompt(config.prompt)
            ?: config.name.trim().takeUnless { it.isEmpty() || it.equals(QcUltra2.EMPTY_SLOT_NAME, ignoreCase = true) }
            ?: "Mode ${config.index + 1}"
}
