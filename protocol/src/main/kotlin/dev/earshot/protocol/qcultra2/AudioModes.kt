package dev.earshot.protocol.qcultra2

/*
 * AudioModes block [31.x] — the Bose app's own path to noise control (bosectl "BREAKTHROUGH"
 * #1 and #2). START and SET_GET both work here; no handshake is involved anywhere in BMAP.
 */

// ── Live audio settings [31.10] ──────────────────────────────────────────────────────────

/**
 * The register the Bose app writes for live noise control. Wire values, not UI values:
 * [cncWire] is 0 = maximum cancelling, 10 = most ambient (see [AudioSettings.cancellingLevelFromWire]).
 *
 * CNC is only audible when [ancEnabled] is true and [windBlock] is false: wind block masks the
 * CNC DSP path (CNC 0 and 10 sound identical with wind on). ANC off ignores CNC entirely.
 */
data class AudioSettings(
    val cncWire: Int,
    val spatialCode: Int,
    val windBlock: Boolean,
    val ancEnabled: Boolean,
    /** Always false on the wire: firmware answers Runtime error 8 to autoCnc=1. Kept for the echo. */
    val autoCnc: Boolean = false,
) {
    val spatial: SpatialMode? get() = SpatialMode.fromCode(spatialCode)

    /** Noise-cancelling strength in UI units, 0..10 with 10 = most cancelling. */
    val cancellingLevel: Int get() = cancellingLevelFromWire(cncWire)

    fun withCancellingLevel(level: Int) = copy(cncWire = cancellingLevelToWire(level))
    fun withSpatial(mode: SpatialMode) = copy(spatialCode = mode.code)

    /** True when a CNC change would be inaudible (ANC off or wind block on). */
    val cncMasked: Boolean get() = !ancEnabled || windBlock

    companion object {
        /**
         * `[31.10]` GET/STATUS → 5 bytes `[cnc, autoCnc, spatial, wind, anc]`. The first field is
         * inverted relative to the UI: 0 = max cancelling, 10 = most ambient.
         */
        fun parse(payload: ByteArray): AudioSettings? {
            if (payload.size < 5) return null
            return AudioSettings(
                cncWire = payload.u8(0),
                autoCnc = payload[1] != 0.toByte(),
                spatialCode = payload.u8(2),
                windBlock = payload[3] != 0.toByte(),
                ancEnabled = payload[4] != 0.toByte(),
            )
        }

        /**
         * `[31.10]` SET_GET payload. Byte 1 (autoCnc) is forced to 0 whatever [AudioSettings.autoCnc]
         * says: the firmware rejects 1 with Runtime error 8 and would leave the other four
         * fields unapplied.
         */
        fun build(settings: AudioSettings): ByteArray {
            require(settings.cncWire in 0..QcUltra2.CNC_WIRE_MAX) { "cnc ${settings.cncWire} outside 0..${QcUltra2.CNC_WIRE_MAX}" }
            require(settings.spatialCode in 0..255) { "bad spatial code" }
            return byteArrayOf(
                settings.cncWire.toByte(),
                0,
                settings.spatialCode.toByte(),
                settings.windBlock.toByteValue(),
                settings.ancEnabled.toByteValue(),
            )
        }

        /** UI level (10 = most cancelling) → wire byte (0 = most cancelling). */
        fun cancellingLevelToWire(level: Int): Int {
            require(level in 0..QcUltra2.CNC_WIRE_MAX) { "level $level outside 0..${QcUltra2.CNC_WIRE_MAX}" }
            return QcUltra2.CNC_WIRE_MAX - level
        }

        /** Wire byte (0 = most cancelling) → UI level (10 = most cancelling). Out-of-range wire values clamp. */
        fun cancellingLevelFromWire(wire: Int): Int = QcUltra2.CNC_WIRE_MAX - wire.coerceIn(0, QcUltra2.CNC_WIRE_MAX)
    }
}

// ── Current mode [31.3] ──────────────────────────────────────────────────────────────────

/**
 * `[31.3]` GET → `[index]`; 0xFF ([QcUltra2.NO_SAVED_MODE]) once the user tweaked settings away
 * from any saved slot. START `[index, announce]` switches; announce=1 should play the prompt
 * but Quiet Rebellion found it silent on this firmware. The reply is RESULT (or PROCESSING
 * on some firmware, with the switch applied asynchronously).
 */
object CurrentMode {
    fun parse(payload: ByteArray): Int? = if (payload.isEmpty()) null else payload.u8(0)

    fun isSavedSlot(index: Int): Boolean = index in 0 until QcUltra2.MODE_SLOT_COUNT

    fun startPayload(index: Int, announce: Boolean = false): ByteArray {
        require(isSavedSlot(index)) { "mode index $index outside 0..${QcUltra2.MODE_SLOT_COUNT - 1}" }
        return byteArrayOf(index.toByte(), announce.toByteValue())
    }
}

// ── Mode config [31.6] ───────────────────────────────────────────────────────────────────

/** A field of a mode slot that byte 41 of the `[31.6]` STATUS can mark as changeable. */
enum class ModeField(val bit: Int) {
    CNC(0), AUTO_CNC(1), SPATIAL(2), WIND(3), ANC(4);

    companion object {
        fun fromFlags(flags: Int): Set<ModeField> = entries.filter { (flags shr it.bit) and 1 == 1 }.toSet()
    }
}

/**
 * One mode slot as reported by the 48-byte `[31.6]` STATUS (or, with the nullable fields
 * absent, by the 40-byte SET_GET echo).
 */
data class ModeConfig(
    val index: Int,
    val promptBytes: Pair<Int, Int>,
    val name: String,
    /** Wire scale, 0 = max cancelling (Quiet reads 0, Aware reads 10). */
    val cncWire: Int,
    val autoCnc: Boolean,
    val spatialCode: Int,
    /** STATUS byte **46** (the Bose app's reading; bosectl used 45), SET_GET byte 38. */
    val windBlock: Boolean,
    val ancEnabled: Boolean,
    /** STATUS byte 3 (Bose `userConfigurable`); presets read 0, user slots 1. Null for a SET_GET echo. */
    val editable: Boolean?,
    /** STATUS byte 4 (`userConfigured`); set on first write and never cleared, so not a free-slot test. */
    val configured: Boolean?,
    /** STATUS byte 5: the Bose app's `favorite` bit for the slot. */
    val flagByte5: Int?,
    val unknownWord38: Int?,
    /** STATUS bytes 40-41 raw; byte 41 alone is [changeableFlags]. */
    val unknownWord40: Int?,
    /**
     * STATUS byte 41, the Bose app's per-field mutability flags (bit0 cnc, bit1 autoCnc, bit2
     * spatial, bit3 wind toggle, bit4 anc toggle). `0x1d` on every user slot = all but autoCnc;
     * presets carry less (Aware `0x02`). Null for a SET_GET echo.
     */
    val changeableFlags: Int?,
    /**
     * STATUS byte 45, not read by the Bose app. `01` on every user slot of the fw 10.12 dump
     * (empty, the two configured user slots alike), so it is *not* wind: probably a per-field "wind
     * configurable" flag. Kept raw. Null for a SET_GET echo.
     */
    val flags45: Int?,
    val raw: ByteArray,
) {
    val prompt: ModePrompt? get() = ModePrompt.fromBytes(promptBytes.first, promptBytes.second)
    val spatial: SpatialMode? get() = SpatialMode.fromCode(spatialCode)
    val cancellingLevel: Int get() = AudioSettings.cancellingLevelFromWire(cncWire)
    val isPreset: Boolean get() = index in QcUltra2.PRESET_MODE_SLOTS

    /** Fields byte 41 marks as changeable; null for a SET_GET echo. */
    val changeableFields: Set<ModeField>? get() = changeableFlags?.let { ModeField.fromFlags(it) }

    /** Byte 45 read as a flag ("wind configurable?"); a guess, see [flags45]. */
    val windConfigurable: Boolean? get() = flags45?.let { it != 0 }

    @Deprecated("Byte 46 is the wind block flag", ReplaceWith("windBlock"))
    val unknownByte46: Int? get() = if (raw.size >= ModeConfigs.STATUS_SIZE) raw[46].toInt() and 0xFF else null

    /** bosectl's free-slot rule: name is the "None" sentinel or blank (not the configured bit). */
    val isEmptySlot: Boolean get() = name.isBlank() || name.trim().equals(QcUltra2.EMPTY_SLOT_NAME, ignoreCase = true)

    /** Live settings equivalent of this slot, for seeding a `[31.10]` write. */
    fun toAudioSettings() = AudioSettings(cncWire, spatialCode, windBlock, ancEnabled, autoCnc)

    override fun equals(other: Any?): Boolean = other is ModeConfig && raw.contentEquals(other.raw)
    override fun hashCode(): Int = raw.contentHashCode()
}

/**
 * `[31.6]` payloads.
 *
 * STATUS (48 bytes, Bose `AudioModesModeConfigResponse`), one per slot after `START [31.6]` /
 * `START [31.1]`, or one for `GET [31.6] [index]`:
 * ```
 * 0      modeIndex
 * 1-2    voicePrompt (0, ModePrompt code)
 * 3      userConfigurable   4  userConfigured   5  favorite
 * 6-37   modeName, UTF-8, NUL padded to 32
 * 38-40  not read by the app (0 on user slots)
 * 41     changeable-field flags (0x1d on user slots)
 * 42     cncLevel (wire scale)   43 autoCnc   44 spatial   45 ? (flag, 01 on user slots)
 * 46     windBlock               47 ancToggle
 * ```
 * Wind is byte **46**: the fw 10.12 dump has empty slots ending `1d 0a 00 00 01 00 01`, Work
 * `1d 00 00 00 01 00 01` and a wind-on user slot `1d 00 00 00 01 01 01`, and `[31.10]` read wind=1 while in
 * Commute. NOTES.md (and Earshot before 2026-10-17) read wind at 45, which made every user slot
 * look like "wind on".
 *
 * SET_GET (40 bytes), custom slots only — see [ModeConfigWrite].
 */
object ModeConfigs {
    const val STATUS_SIZE = 48
    const val WRITE_SIZE = 40

    /** STATUS byte offsets. */
    const val CHANGEABLE_FLAGS_OFFSET = 41
    const val FLAGS45_OFFSET = 45
    const val WIND_OFFSET = 46

    fun parse(payload: ByteArray): ModeConfig? = when {
        payload.size >= STATUS_SIZE -> parseStatus(payload)
        payload.size >= WRITE_SIZE -> parseEcho(payload)
        else -> null
    }

    /** `[31.6]` GET takes the slot index as payload (a bare GET answers InvalidData on fw 10.12). */
    fun getPayload(index: Int): ByteArray {
        require(index in 0 until QcUltra2.MODE_SLOT_COUNT) { "mode index $index outside 0..${QcUltra2.MODE_SLOT_COUNT - 1}" }
        return byteArrayOf(index.toByte())
    }

    private fun parseStatus(p: ByteArray) = ModeConfig(
        index = p.u8(0),
        promptBytes = p.u8(1) to p.u8(2),
        name = p.utf8Until(6, 38),
        cncWire = p.u8(42),
        autoCnc = p[43] != 0.toByte(),
        spatialCode = p.u8(44),
        windBlock = p[WIND_OFFSET] != 0.toByte(),
        ancEnabled = p[47] != 0.toByte(),
        editable = p[3] != 0.toByte(),
        configured = p[4] != 0.toByte(),
        flagByte5 = p.u8(5),
        unknownWord38 = p.u16be(38),
        unknownWord40 = p.u16be(40),
        changeableFlags = p.u8(CHANGEABLE_FLAGS_OFFSET),
        flags45 = p.u8(FLAGS45_OFFSET),
        raw = p.copyOf(),
    )

    private fun parseEcho(p: ByteArray) = ModeConfig(
        index = p.u8(0),
        promptBytes = p.u8(1) to p.u8(2),
        name = p.utf8Until(3, 35),
        cncWire = p.u8(35),
        autoCnc = p[36] != 0.toByte(),
        spatialCode = p.u8(37),
        windBlock = p[38] != 0.toByte(),
        ancEnabled = p[39] != 0.toByte(),
        editable = null, configured = null, flagByte5 = null,
        unknownWord38 = null, unknownWord40 = null, changeableFlags = null, flags45 = null,
        raw = p.copyOf(),
    )

    /** 32-byte NUL-terminated, NUL-padded name field. Throws when the UTF-8 form exceeds 31 bytes. */
    fun encodeName(name: String): ByteArray {
        val bytes = name.toByteArray(Charsets.UTF_8)
        require(bytes.size <= QcUltra2.MODE_NAME_MAX_BYTES) { "mode name longer than ${QcUltra2.MODE_NAME_MAX_BYTES} UTF-8 bytes" }
        return ByteArray(QcUltra2.MODE_NAME_FIELD_BYTES).also { bytes.copyInto(it) }
    }

    /** Longest prefix of [name] that fits the 31-byte field without splitting a code point. */
    fun truncateName(name: String): String {
        var s = name
        while (s.toByteArray(Charsets.UTF_8).size > QcUltra2.MODE_NAME_MAX_BYTES) {
            s = s.substring(0, s.length - 1)
            if (s.isNotEmpty() && Character.isHighSurrogate(s.last())) s = s.substring(0, s.length - 1)
        }
        return s
    }
}

/**
 * The 40-byte `[31.6]` SET_GET payload: `[index, prompt b1, prompt b2, name(32), cnc, autoCnc=0,
 * spatial, wind, anc]` (offsets 35-39). This matches the Bose app's builder
 * (`createAudioModesConfigSetGetPayload`), which writes the same order but may stop early when a
 * field is unknown (spatial forced to 0 when only wind is given); Earshot always knows all five,
 * so the payload is always the full 40 bytes. The STATUS form has two extra bytes in its tail
 * (41 flags, 45 flag), which is why wind sits at 46 there but at 38 here.
 * Only custom slots take it: presets 0-3 answer Runtime error 8. The device replies with STATUS
 * frames (bosectl treats "no STATUS in the reply" as failure). Writing name "None" with
 * everything off is how bosectl frees a slot; `[31.9]` ([ModeReset]) is the firmware's own reset.
 */
data class ModeConfigWrite(
    val index: Int,
    val name: String,
    val cncWire: Int = 0,
    val spatialCode: Int = SpatialMode.OFF.code,
    val windBlock: Boolean = true,
    val ancEnabled: Boolean = true,
    val promptBytes: Pair<Int, Int> = 0 to 0,
) {
    constructor(index: Int, name: String, cancellingLevel: Int, spatial: SpatialMode, windBlock: Boolean,
                ancEnabled: Boolean, prompt: ModePrompt = ModePrompt.NONE) : this(
        index, name, AudioSettings.cancellingLevelToWire(cancellingLevel), spatial.code, windBlock, ancEnabled, 0 to prompt.code,
    )

    fun build(): ByteArray {
        require(index in QcUltra2.EDITABLE_MODE_SLOTS) { "slot $index is not writable (presets 0-3 reject SET_GET)" }
        require(cncWire in 0..QcUltra2.CNC_WIRE_MAX) { "cnc $cncWire outside 0..${QcUltra2.CNC_WIRE_MAX}" }
        val out = ByteArray(ModeConfigs.WRITE_SIZE)
        out[0] = index.toByte()
        out[1] = promptBytes.first.toByte()
        out[2] = promptBytes.second.toByte()
        ModeConfigs.encodeName(name).copyInto(out, 3)
        out[35] = cncWire.toByte()
        out[36] = 0 // autoCnc: firmware rejects 1 (Runtime 8)
        out[37] = spatialCode.toByte()
        out[38] = windBlock.toByteValue()
        out[39] = ancEnabled.toByteValue()
        return out
    }

    companion object {
        /** Start from an existing slot so an edit keeps the prompt and the fields it does not touch. */
        fun from(config: ModeConfig) = ModeConfigWrite(
            config.index, config.name, config.cncWire, config.spatialCode, config.windBlock, config.ancEnabled, config.promptBytes,
        )

        /** The payload bosectl uses to delete a profile. */
        fun clearSlot(index: Int) = ModeConfigWrite(index, QcUltra2.EMPTY_SLOT_NAME, 0, SpatialMode.OFF.code, false, false)
    }
}

// ── Reset [31.9] ─────────────────────────────────────────────────────────────────────────

/**
 * `[31.9]` Reset: START `[modeIndex]` → RESULT with no payload; resets that one slot (Bose app
 * `AudioModesResetStartPacket`). GET answers error 5 on fw 10.12, i.e. the function exists.
 * Which slots it accepts is untested on hardware; Earshot only offers it for custom slots.
 */
object ModeReset {
    fun startPayload(index: Int): ByteArray {
        require(index in 0 until QcUltra2.MODE_SLOT_COUNT) { "mode index $index outside 0..${QcUltra2.MODE_SLOT_COUNT - 1}" }
        return byteArrayOf(index.toByte())
    }
}

// ── Favourites [31.8] ────────────────────────────────────────────────────────────────────

data class Favorites(val totalModes: Int, val indices: Set<Int>, val raw: ByteArray) {
    override fun equals(other: Any?): Boolean = other is Favorites && raw.contentEquals(other.raw)
    override fun hashCode(): Int = raw.contentHashCode()
}

/**
 * `[31.8]` GET/SET_GET → `[totalModes][ceil(totalModes / 8) bytes]`, the bytes being a big-endian
 * bitset of favourite slots ([Bitset]); bit n = slot n. With 11 slots that is two bytes, so the
 * fw 10.12 frame `0b 00 37` = {0, 1, 2, 4, 5} and slots 8-10 live in the *first* mask byte. (Quiet
 * Rebellion read `[total, 0, mask]`, which agrees for slots 0-7 only.) Writing slots 8-10 follows
 * the Bose app's layout but is untested on hardware.
 */
object FavoritesCodec {
    fun maskBytes(totalModes: Int): Int = maxOf(1, (totalModes + 7) / 8)

    fun parse(payload: ByteArray): Favorites? {
        if (payload.size < 2) return null
        return Favorites(payload.u8(0), Bitset.decode(payload, 1, payload.size - 1), payload.copyOf())
    }

    fun build(indices: Set<Int>, totalModes: Int = QcUltra2.MODE_SLOT_COUNT): ByteArray {
        require(indices.all { it in 0 until totalModes }) { "favourite slots outside 0..${totalModes - 1}: $indices" }
        return byteArrayOf(totalModes.toByte()) + Bitset.encode(indices, maskBytes(totalModes))
    }
}
