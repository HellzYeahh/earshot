package dev.earshot.protocol.soundcore

/** The three ambient sound modes every Soundcore ANC model has (the Sleep A30 only has noise cancelling on/off). */
enum class AmbientMode(val wire: Int) {
    NOISE_CANCELLING(0),
    TRANSPARENCY(1),
    NORMAL(2),
    ;

    companion object {
        fun fromWire(value: Int): AmbientMode? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * How one model lays out its sound-mode block, the body of `06 01` (pushed) and `06 81` (set).
 * Facts from the OpenSCQ30 and soundcorebridge notes (`docs/research/research_notes/Top brand headset
 * protocols/soundcore.md` §3); the code is ours. Every "v2" codec shares the first five positions:
 * ```
 *  0 ambient (0 noise cancelling, 1 transparency, 2 normal)
 *  1 level byte: manual noise-cancelling level in the high nibble, adaptive level in the low one
 *  2 transparency type (a repeat of byte 0 on some models)
 *  3 noise-cancelling type (0 manual, 1 adaptive, 2 transport / multi-scene)
 *  4 wind: bit 0 suppression on, bit 1 wind detected (read-only)
 *  5.. model specific (sensitivity, environment, scene): never interpreted, written back as read
 * ```
 * Writing is always read-modify-write of the whole block, so the bytes Earshot does not understand
 * go back exactly as the earbuds reported them.
 */
class SoundModeCodec(
    val name: String,
    /** Bytes in the block; a write with any other length is refused. */
    val size: Int,
    /** Manual level range in the high nibble of byte 1; null when the block has no such level. */
    val manualLevels: IntRange? = null,
    /** Labels for byte 3 by value; empty when the block has no noise-cancelling type. */
    val ncTypes: List<String> = emptyList(),
    /** Labels for byte 2 by value; empty where byte 2 is not a transparency type. */
    val transparencyTypes: List<String> = emptyList(),
    /** Byte 2 repeats the ambient byte (Space One, R50i NC, A30i), so a write keeps them equal. */
    val mirrorsAmbientAt2: Boolean = false,
    /** Byte 4 is the wind switch. */
    val hasWind: Boolean = true,
) {
    companion object {
        private val TRANSPARENCY = listOf("Fully transparent", "Vocal mode")
        private val NC_SCENE = listOf("Manual", "Adaptive", "Multi-scene")

        /** Liberty 4 NC (A3947) and Liberty 5 (A3957): 7 bytes, byte 3 = manual / adaptive / transportation. */
        val V2_7_TRANSPORT = SoundModeCodec("v2-7 transport", 7, 1..5, listOf("Manual", "Adaptive", "Transportation"), TRANSPARENCY)

        /** P40i (A3955): 7 bytes, byte 3 = manual / adaptive / multi-scene (byte 6 = scene). */
        val V2_7_SCENE = SoundModeCodec("v2-7 scene", 7, 1..5, NC_SCENE, TRANSPARENCY)

        /** R50i NC / P30i (A3959) and A30i (A3958): as above, but byte 2 only repeats the ambient mode. */
        val V2_7_MIRROR = SoundModeCodec("v2-7 mirror", 7, 1..5, NC_SCENE, mirrorsAmbientAt2 = true)

        /** P31i / R60i NC (D1202): 8 bytes, byte 6 = scene, byte 7 = real-time adaptive ANC. */
        val V2_8 = SoundModeCodec("v2-8", 8, 1..5, NC_SCENE, TRANSPARENCY)

        /** Space A40 (A3936) and Liberty 3 Pro (A3952): 6 bytes, manual level 1..3. */
        val V2_6_TWS = SoundModeCodec("v2-6 tws", 6, 1..3, listOf("Manual", "Adaptive"), TRANSPARENCY)

        /** Liberty 4 (A3953): 6 bytes, manual level 1..6, byte 3 is an "automation mode" Earshot does not label. */
        val V2_6_L4 = SoundModeCodec("v2-6 liberty 4", 6, 1..6, listOf("Manual", "Adaptive"), TRANSPARENCY)

        /** Space One (A3035): 6 bytes, custom level 1..5, byte 2 repeats the ambient mode, byte 5 = transparency level. */
        val V2_6_SPACE = SoundModeCodec("v2-6 space one", 6, 1..5, listOf("Manual", "Adaptive"), mirrorsAmbientAt2 = true)

        /** Space One Pro (A3062): as the One, but byte 2 is a fixed 1 that a write must not touch. */
        val V2_6_SPACE_PRO = SoundModeCodec("v2-6 space one pro", 6, 1..5, listOf("Manual", "Adaptive"))

        /** Space Q45 (A3040): 6 bytes; byte 2 is a talk / manual transparency mode, so it is not labelled here. */
        val V2_6_Q45 = SoundModeCodec("v2-6 q45", 6, 1..5, listOf("Manual", "Adaptive"))

        /** Space 2 (D1402): `[mode, level<<4 | 0xF, 02, 00, 00, 01]`. Read here; writes need the app's handshake first. */
        val SPACE_2 = SoundModeCodec("space 2", 6, 1..5, hasWind = false)

        /** Liberty 4 Pro (A3954): a 4-byte block with a slider whose polarity the sources contradict: ambient only. */
        val SLIDER = SoundModeCodec("slider", 4, hasWind = false)
    }
}

/** One sound-mode block as the earbuds reported it, with its [codec]. Immutable: the `with…` calls return a changed copy. */
class SoundModes(val codec: SoundModeCodec, raw: ByteArray) {
    val raw: ByteArray = raw.copyOf()

    init {
        require(raw.size == codec.size) { "${codec.name} block is ${codec.size} bytes, got ${raw.size}" }
    }

    private fun u8(i: Int): Int = raw[i].toInt() and 0xFF

    /** Null for a value this driver doesn't know (Liberty 4 Pro's 3 = airplane). */
    val ambient: AmbientMode? get() = AmbientMode.fromWire(u8(0))

    /** Manual noise-cancelling level, or null when the block has none or the byte is out of range. */
    val manualLevel: Int? get() = codec.manualLevels?.let { range -> (u8(1) shr 4).takeIf { it in range } }

    val adaptiveLevel: Int? get() = if (codec.manualLevels != null) u8(1) and 0x0F else null

    /** Index into [SoundModeCodec.ncTypes], or null when the block has no type or the byte is out of range. */
    val ncType: Int? get() = if (codec.ncTypes.isEmpty()) null else u8(3).takeIf { it in codec.ncTypes.indices }

    val transparencyType: Int? get() = if (codec.transparencyTypes.isEmpty()) null else u8(2).takeIf { it in codec.transparencyTypes.indices }

    val windOn: Boolean? get() = if (codec.hasWind) u8(4) and 1 != 0 else null

    fun withAmbient(mode: AmbientMode): SoundModes = changed {
        it[0] = mode.wire.toByte()
        if (codec.mirrorsAmbientAt2) it[2] = mode.wire.toByte()
    }

    fun withNcType(index: Int): SoundModes = changed { it[3] = index.toByte() }

    fun withTransparencyType(index: Int): SoundModes = changed { it[2] = index.toByte() }

    fun withManualLevel(level: Int): SoundModes = changed { it[1] = ((level shl 4) or (u8(1) and 0x0F)).toByte() }

    /** Sets bit 0 only: bit 1 ("wind detected") belongs to the earbuds. */
    fun withWind(on: Boolean): SoundModes = changed { it[4] = ((u8(4) and 0x02) or (if (on) 1 else 0)).toByte() }

    private fun changed(edit: (ByteArray) -> Unit): SoundModes = SoundModes(codec, raw.copyOf().also(edit))

    override fun equals(other: Any?): Boolean = other is SoundModes && other.codec === codec && other.raw.contentEquals(raw)

    override fun hashCode(): Int = codec.hashCode() * 31 + raw.contentHashCode()
}
