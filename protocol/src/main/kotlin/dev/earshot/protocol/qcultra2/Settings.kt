package dev.earshot.protocol.qcultra2

/*
 * Settings block [1.x]. GET and SET_GET both work (bosectl "BREAKTHROUGH #3"); plain SET answers
 * error 5 because the firmware does not implement that operator here (the Bose app only ever
 * writes with SET_GET). SET_GET echoes the new state as a STATUS frame, so every builder here
 * is paired with the parser that reads the echo.
 */

// ── EQ [1.7] ─────────────────────────────────────────────────────────────────────────────

/** One band of the 3-band EQ. Values are signed dB-ish steps, -10..+10. */
data class EqBand(val bandCode: Int, val min: Int, val max: Int, val value: Int) {
    val band: EqBandId? get() = EqBandId.fromCode(bandCode)
}

/**
 * `[1.7]` GET → three 4-byte groups `[min, max, current, bandId]`, all signed bytes.
 * Capture `f60a0000 f60afe01 f60afa02` → bass 0, mid -2, treble -6 (min -10, max +10).
 * SET_GET → `[value, bandId]`, one band per request; the reply is the full 12-byte GET form.
 */
object Eq {
    fun parse(payload: ByteArray): List<EqBand> {
        val out = ArrayList<EqBand>(3)
        var i = 0
        while (i + 3 < payload.size) {
            out.add(EqBand(bandCode = payload.u8(i + 3), min = payload.s8(i), max = payload.s8(i + 1), value = payload.s8(i + 2)))
            i += 4
        }
        return out
    }

    fun buildBand(value: Int, band: EqBandId): ByteArray = buildBand(value, band.code)

    fun buildBand(value: Int, bandCode: Int): ByteArray {
        require(value in QcUltra2.EQ_MIN..QcUltra2.EQ_MAX) { "EQ value $value outside ${QcUltra2.EQ_MIN}..${QcUltra2.EQ_MAX}" }
        require(bandCode in 0..255) { "bad band $bandCode" }
        return byteArrayOf((value and 0xFF).toByte(), bandCode.toByte())
    }

    /** Value of [band] in a parsed list, or null if the device did not report it. */
    fun valueOf(bands: List<EqBand>, band: EqBandId): Int? = bands.firstOrNull { it.bandCode == band.code }?.value
}

// ── Buttons [1.9] ────────────────────────────────────────────────────────────────────────

data class ButtonConfig(
    val buttonCode: Int,
    val eventCode: Int,
    val actionCode: Int,
    /** Action codes offered for this button/event: bit n of the big-endian 32-bit mask at bytes 3-6 = action n. */
    val supportedActionCodes: Set<Int>,
    /**
     * Bytes 7-10 read the same way. Guess: actions the firmware will not accept *right now*
     * (`00010002` on firmware 10.12 = {1 VPA, 16 SpotifyGo}, plausibly "no assistant / Spotify
     * app paired"). Empty when the frame is the 7-byte form. Not a filter the UI should apply
     * until confirmed on-device.
     */
    val unavailableActionCodes: Set<Int>,
    /**
     * Whatever follows byte 10. Firmware 10.12 sends `01 01 10 01`, which could be a second
     * record (`0x10` is the Action button id) or capability flags. Kept raw, meaning unknown.
     */
    val tail: ByteArray,
    val raw: ByteArray,
) {
    val button: ButtonId? get() = ButtonId.fromCode(buttonCode)
    val event: ButtonEvent? get() = ButtonEvent.fromCode(eventCode)
    val action: ButtonAction? get() = ButtonAction.fromCode(actionCode)
    val supportedActions: List<ButtonAction> get() = supportedActionCodes.mapNotNull { ButtonAction.fromCode(it) }

    /** True when the current action is among the offered ones: the sanity check that caught the old bit order. */
    val isConsistent: Boolean get() = actionCode in supportedActionCodes

    /**
     * Supported minus unavailable. On firmware 10.12 that is {14 Disabled, 19 SpatialAudio}: the
     * two actions that need nothing paired. Built on the unavailable-mask guess, so the UI
     * should keep offering [supportedActionCodes] until a remap of an "unavailable" action is
     * seen to fail on-device.
     */
    val availableActionCodes: Set<Int> get() = supportedActionCodes - unavailableActionCodes

    override fun equals(other: Any?): Boolean = other is ButtonConfig && raw.contentEquals(other.raw)
    override fun hashCode(): Int = raw.contentHashCode()
}

/**
 * `[1.9]` GET → `[buttonId, eventType, action, supportedMask(4), unavailableMask(4), tail...]`.
 *
 * Both masks are **big-endian 32-bit** integers, bit n = action code n. bosectl read them byte
 * by byte little-endian, which turned `00094002` into {8, 11, 22, 25}; a real frame from
 * firmware 10.12 (`80 09 13 00094002 00010002 01011001`) has current action 0x13 = 19
 * SpatialAudioMode, which that reading does not contain. As 0x00094002 the bits are
 * {1 VPA, 14 Disabled, 16 SpotifyGo, 19 SpatialAudio}: the shortcut-button menu (voice
 * assistant, none, Spotify Tap, spatial audio), containing the current action; the 8.2.20
 * sample (`80 09 0e 00094002`, current Disabled) fits too. The second mask, `00010002` =
 * {1, 16}, then reads naturally as "VPA and Spotify Tap are unavailable until their apps are
 * set up", which leaves Disabled and SpatialAudio as the live choices.
 *
 * SET_GET → `[buttonId, eventType, newAction]`, three bytes; the reply is the full GET form.
 *
 * Caveat: bosectl says remapping "works, verified"; Quiet Rebellion says the SET_GET echoes
 * but the firmware ignores it. The Bose app writes exactly this SET_GET (no handshake), and the
 * second mask now reads as "unavailable right now"; treat as experimental until tried on-device.
 */
object Buttons {
    private const val SUPPORTED_OFFSET = 3
    private const val UNAVAILABLE_OFFSET = 7
    private const val TAIL_OFFSET = 11

    fun parse(payload: ByteArray): ButtonConfig? {
        if (payload.size < 3) return null
        return ButtonConfig(
            buttonCode = payload.u8(0),
            eventCode = payload.u8(1),
            actionCode = payload.u8(2),
            supportedActionCodes = maskBits(payload, SUPPORTED_OFFSET),
            unavailableActionCodes = maskBits(payload, UNAVAILABLE_OFFSET),
            tail = if (payload.size > TAIL_OFFSET) payload.copyOfRange(TAIL_OFFSET, payload.size) else ByteArray(0),
            raw = payload.copyOf(),
        )
    }

    fun build(button: ButtonId, event: ButtonEvent, action: ButtonAction): ByteArray =
        build(button.code, event.code, action.code)

    fun build(buttonCode: Int, eventCode: Int, actionCode: Int): ByteArray {
        require(buttonCode in 0..255 && eventCode in 0..255 && actionCode in 0..255) { "button fields are bytes" }
        return byteArrayOf(buttonCode.toByte(), eventCode.toByte(), actionCode.toByte())
    }

    /** Bits of the big-endian 32-bit mask at [offset] ([Bitset]); empty when the payload is too short for all 4 bytes. */
    private fun maskBits(payload: ByteArray, offset: Int): Set<Int> =
        if (payload.size < offset + 4) emptySet() else Bitset.decode(payload, offset, 4)
}

// ── Multipoint [1.10] ────────────────────────────────────────────────────────────────────

/**
 * `[1.10]` GET → one byte, bit 0x02 = multipoint enabled (capture `07`). The other bits are
 * unknown. SET_GET → `[0|1]`.
 *
 * In bosectl's multipoint capture the headphones dropped the RFCOMM link right after the
 * toggle and every later read failed: expect a disconnect/reconnect after writing this.
 */
object Multipoint {
    fun parse(payload: ByteArray): Boolean? = if (payload.isEmpty()) null else (payload.u8(0) and 0x02) != 0
    fun build(enabled: Boolean): ByteArray = Toggle.build(enabled)
}

// ── Sidetone [1.11] ──────────────────────────────────────────────────────────────────────

data class SidetoneState(
    /** Byte 0: keep the setting across power cycles. */
    val persist: Boolean,
    val levelCode: Int,
    /**
     * Byte 2 when present: `0x0f` on both firmware 8.2.20 (`01020f`) and 10.12 (`01030f`), i.e.
     * bits 0-3 set while the device knows exactly four levels (0-3). Read as the mask of
     * levels the firmware supports; null on a 2-byte frame.
     */
    val supportedLevelsMask: Int?,
) {
    val level: SidetoneLevel? get() = SidetoneLevel.fromCode(levelCode)

    /** Level codes flagged in [supportedLevelsMask]; all four when the mask is absent. */
    val supportedLevelCodes: Set<Int>
        get() = supportedLevelsMask?.let { m -> Bitset.decode(byteArrayOf(m.toByte())) }
            ?: SidetoneLevel.entries.map { it.code }.toSet()

    val supportedLevels: List<SidetoneLevel> get() = supportedLevelCodes.sorted().mapNotNull { SidetoneLevel.fromCode(it) }
}

/**
 * `[1.11]` GET → `[persist, level, supportedMask]` (captures `01020f` = persistent, medium;
 * `01030f` = persistent, low).
 * SET_GET → `[persist, level]` with level 0=off, 1=high, 2=medium, 3=low (yes, in that order).
 */
object Sidetone {
    fun parse(payload: ByteArray): SidetoneState? {
        if (payload.size < 2) return null
        return SidetoneState(payload[0] != 0.toByte(), payload.u8(1), if (payload.size >= 3) payload.u8(2) else null)
    }

    fun build(level: SidetoneLevel, persist: Boolean = true): ByteArray = build(level.code, persist)

    fun build(levelCode: Int, persist: Boolean = true): ByteArray {
        require(levelCode in 0..255) { "bad sidetone level $levelCode" }
        return byteArrayOf(persist.toByteValue(), levelCode.toByte())
    }
}

// ── Voice prompts [1.3] ──────────────────────────────────────────────────────────────────

data class VoicePromptsState(
    val enabled: Boolean,
    val languageCode: Int,
    /**
     * Bytes 1-4 read as a big-endian bitmask, bit n = language n (capture `00008102` → US English,
     * Mandarin, Japanese). Guess: the languages installed on the unit. Null when absent.
     */
    val installedLanguageCodes: Set<Int>?,
    val raw: ByteArray,
) {
    val language: VoicePromptLanguage? get() = VoicePromptLanguage.fromCode(languageCode)
    val installedLanguages: List<VoicePromptLanguage>
        get() = installedLanguageCodes.orEmpty().mapNotNull { VoicePromptLanguage.fromCode(it) }

    override fun equals(other: Any?): Boolean = other is VoicePromptsState && raw.contentEquals(other.raw)
    override fun hashCode(): Int = raw.contentHashCode()
}

/**
 * `[1.3]` GET → 7 bytes on this firmware (capture `41 00008102 0000`). bosectl decodes byte 0 as
 * `(enabled << 5) | languageId`, which makes `0x41` = language 1 (US English) with prompts off and
 * an unexplained bit 6. Nothing in the captures toggles prompts, so the enabled bit is the
 * weakest fact in this file; the raw byte is kept on the parsed state for that reason.
 * SET_GET → the single byte `(enabled << 5) | language`.
 */
object VoicePrompts {
    private const val ENABLED_BIT = 0x20
    private const val LANGUAGE_MASK = 0x1F

    fun parse(payload: ByteArray): VoicePromptsState? {
        if (payload.isEmpty()) return null
        val b0 = payload.u8(0)
        val installed = if (payload.size >= 5) Bitset.decode(payload, 1, 4) else null
        return VoicePromptsState(enabled = (b0 and ENABLED_BIT) != 0, languageCode = b0 and LANGUAGE_MASK,
            installedLanguageCodes = installed, raw = payload.copyOf())
    }

    fun build(enabled: Boolean, language: VoicePromptLanguage): ByteArray = build(enabled, language.code)

    fun build(enabled: Boolean, languageCode: Int): ByteArray {
        require(languageCode in 0..LANGUAGE_MASK) { "language id $languageCode outside 0..31" }
        return byteArrayOf(((if (enabled) ENABLED_BIT else 0) or languageCode).toByte())
    }
}

// ── Simple toggles: [1.24] auto play/pause, [1.27] auto answer, [1.12] setup complete ───

/** One-byte booleans: GET → `[0|1]`, SET_GET → `[0|1]`. */
object Toggle {
    fun parse(payload: ByteArray): Boolean? = if (payload.isEmpty()) null else payload[0] != 0.toByte()
    fun build(enabled: Boolean): ByteArray = byteArrayOf(enabled.toByteValue())
}

// ── Touch controls [1.34] DisableCaptouch ────────────────────────────────────────────────

/**
 * `[1.34]` DisableCaptouch: GET → `[bit0]`, SET_GET `[0|1]`, where **1 = touch disabled** (the
 * Bose app's name and bit). fw 10.12 reads `01`, i.e. touch controls off; whether the user-visible
 * effect is the capacitive volume strip is still to be verified on hardware.
 */
object TouchControls {
    /** Touch controls *enabled* (the inverse of the wire bit); null on an empty payload. */
    fun parseEnabled(payload: ByteArray): Boolean? = if (payload.isEmpty()) null else (payload.u8(0) and 0x01) == 0

    fun build(enabled: Boolean): ByteArray = byteArrayOf(if (enabled) 0 else 1)
}

// ── LE Audio [1.37] LeAudioControl ───────────────────────────────────────────────────────

/**
 * `[1.37]` LeAudioControl: GET → `[bit0]` (fw 10.12: `01` = LE Audio on), SET_GET `[0|1]`.
 * Changing it makes the headphones **restart** (Bose app copy): expect the link to drop right
 * after the write. Support is `[0.23]`; the per-connection preference is `[4.19]` ([LeAudioCheck]).
 */
object LeAudioControl {
    fun parse(payload: ByteArray): Boolean? = if (payload.isEmpty()) null else (payload.u8(0) and 0x01) != 0
    fun build(enabled: Boolean): ByteArray = Toggle.build(enabled)
}

// ── Custom device name [1.2] ─────────────────────────────────────────────────────────────

data class CustomNameState(
    /** Byte 0: `00` on the renamed reference unit ("Fargo"), `01` on a unit still carrying the factory name. */
    val flag: Int,
    val name: String,
) {
    /** Guess from the two observations: flag 1 = the name is the factory default, 0 = user-set. */
    val isFactoryDefault: Boolean get() = flag == 1
}

/**
 * `[1.2]` GET → `[flag, utf8 name...]`: `00 466172676f` = "Fargo" on firmware 8.2.20 (renamed in
 * the Bose app), `01 426f7365...` = "Bose QC Ultra 2 HP" on firmware 10.12 (never renamed).
 * SET_GET → the bare UTF-8 name, no flag byte, at most 31 bytes.
 */
object CustomName {
    fun parse(payload: ByteArray): String? = parseState(payload)?.name

    fun parseState(payload: ByteArray): CustomNameState? =
        if (payload.size < 2) null else CustomNameState(payload.u8(0), payload.utf8Until(1).trimEnd())

    fun build(name: String): ByteArray {
        val bytes = name.trim().toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty()) { "name is empty" }
        require(bytes.size <= QcUltra2.CUSTOM_NAME_MAX_BYTES) { "name longer than ${QcUltra2.CUSTOM_NAME_MAX_BYTES} UTF-8 bytes" }
        return bytes
    }
}

// ── CNC [1.5] (read-only on this firmware) ───────────────────────────────────────────────

data class CncState(val steps: Int, val step: Int, val flags: Int) {
    /** flags bit 0 per the notes. */
    val enabled: Boolean get() = (flags and 0x01) != 0
    /** flags bit 1 = "!userEnableDisable" per the notes, i.e. the user cannot toggle it. */
    val userCanToggle: Boolean get() = (flags and 0x02) == 0
    val maxStep: Int get() = steps - 1
}

/**
 * `[1.5]` GET → `[numSteps, currentStep, flags]`; capture `0b0003` = 11 steps, step 0, flags 3.
 * The slider capture moves it `0b0003 → 0b0703 → 0b0503 → 0b0103 → 0b0003`, so it does mirror
 * the Bose app's noise-cancelling slider. Whether its step runs in the same direction as the
 * inverted `[31.10]` byte is not established by the captures. SET_GET here answers error 5
 * (operator not implemented for this function): write through [AudioSettings] instead.
 */
object Cnc {
    fun parse(payload: ByteArray): CncState? =
        if (payload.size < 3) null else CncState(payload.u8(0), payload.u8(1), payload.u8(2))
}
