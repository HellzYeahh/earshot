package dev.earshot.protocol.soundcore

/**
 * What a gen-2 Soundcore model reported in `01 01`, in human units: the read-only view every
 * driven model gets (battery, charging, firmware, serial), plus the sound mode and settings where
 * the model's [SoundcoreLayout] fits the state. Built by [SoundcoreStateReader]; the old Sleep A30
 * keeps its own [D1301State].
 */
data class SoundcoreState(
    val model: SoundcoreModel,
    val bodySize: Int,
    val leftPercent: Int?,
    val rightPercent: Int?,
    /** Headphones: their one battery. Null on earbuds. */
    val overallPercent: Int?,
    val casePercent: Int?,
    /** True if any part reports charging, false if parts report and none charge, null if nothing reports. */
    val charging: Boolean?,
    val firmwareLeft: String?,
    val firmwareRight: String?,
    val caseFirmware: String?,
    val serial: String?,
    /** The ambient mode, where the model's mode block (or the TLV tag) is known. */
    val ambient: AmbientMode?,
    /** The decoded mode block; null when the layout is unknown or doesn't fit this state. */
    val modes: SoundModes?,
    /** Setting id → value (0/1, or for auto-off the option index: 0 off, n = nth duration) read from the state. */
    val settings: Map<String, Int?>,
    /** Earshot may write this model's modes and settings: the layout is known and every check on the state passed. */
    val writable: Boolean,
    /** Why the model is read-only (null when [writable]); shown to the user. */
    val note: String?,
    /** Auto power-off settings: the duration index the earbuds hold, also while it is off (an "off" write keeps it). */
    val autoOffIndex: Map<String, Int> = emptyMap(),
) {
    val firmware: String? get() = listOfNotNull(firmwareLeft, firmwareRight).distinct().joinToString(" / ").ifEmpty { null }

    /** The headline figure: the lower bud (it runs out first), or the headphones' own battery. */
    val headlinePercent: Int? get() = listOfNotNull(leftPercent, rightPercent).minOrNull() ?: overallPercent

    fun withModeBlock(block: SoundModes): SoundcoreState = copy(modes = block, ambient = block.ambient)

    fun withBattery(left: Int?, right: Int?, case: Int?, overall: Int?): SoundcoreState =
        copy(leftPercent = left, rightPercent = right, casePercent = case ?: casePercent, overallPercent = overall)

    fun withCharging(value: Boolean?): SoundcoreState = copy(charging = value)
}

/** A battery push (`01 03`) already in percent. */
data class SoundcoreBatteryPush(val left: Int?, val right: Int?, val case: Int?, val overall: Int?)

/**
 * Parses the `01 01` body. Per the research notes' recommended order (step 1) it never trusts a
 * layout blindly:
 *  1. the model's header is tried first, then the other fixed headers, and a header is accepted
 *     only if its firmware fields read `NN.NN` and its serial starts with four digits;
 *  2. the sound-mode block and settings are read only if the state has exactly the size the layout
 *     documents, or (mode only) the notes' length-byte rule points at the layout's offset;
 *  3. they are *written* only if, on top, the serial starts with the model's digits (the
 *     soundcorebridge policy: writes only when the model read from the device matches).
 * Otherwise the view is read-only and [SoundcoreState.note] says why.
 */
object SoundcoreStateReader {
    private val FIRMWARE = Regex("""\d{2}\.\d{2}""")
    private val FIRMWARE_RELAXED = Regex("""[0-9A-Za-z._-]{3,5}""")
    private val SERIAL = Regex("""\d{4}[0-9A-Za-z]{12}""")

    fun read(model: SoundcoreModel, body: ByteArray): SoundcoreState? {
        if (model.header == SoundcoreHeader.TLV) return readTlv(model, body)
        val order = (listOf(model.header) + listOf(SoundcoreHeader.G2T, SoundcoreHeader.SLEEP, SoundcoreHeader.G2H))
            .filter { it != SoundcoreHeader.UNKNOWN && it != SoundcoreHeader.TLV }
            .distinct()
        var header: Header? = null
        for (kind in order) {
            val h = parseHeader(kind, model, body) ?: continue
            if (h.plausible(strict = true)) { header = h; break }
        }
        if (header == null) {
            // A firmware string that isn't NN.NN: accept the model's own header if the serial still reads right.
            header = order.firstOrNull()?.let { parseHeader(it, model, body) }?.takeIf { it.plausible(strict = false) }
        }
        header ?: return null
        return apply(model, body, header)
    }

    /** `01 03` battery push in percent, or null for a shape this model doesn't use. */
    fun batteryPush(model: SoundcoreModel, body: ByteArray): SoundcoreBatteryPush? {
        if (model.header == SoundcoreHeader.TLV || model.header == SoundcoreHeader.UNKNOWN || body.isEmpty()) return null
        fun p(i: Int): Int? = body.getOrNull(i)?.let { model.battery.percent(it.toInt() and 0xFF) }
        return when (model.header) {
            SoundcoreHeader.G2H -> SoundcoreBatteryPush(null, null, null, p(0))
            else -> if (body.size < 2) null else SoundcoreBatteryPush(p(0), p(1), p(2), null)
        }
    }

    /** `01 04` charging push `[left, right]`, or null. */
    fun chargingPush(body: ByteArray): Boolean? = combine(listOf(flag(body.getOrNull(0)), flag(body.getOrNull(1))))

    // ---- Headers ----------------------------------------------------------------------------

    private class Header(
        val kind: SoundcoreHeader,
        val left: Int?, val right: Int?, val overall: Int?,
        val charging: Boolean?,
        val firmwareLeft: Text, val firmwareRight: Text, val serial: Text,
    ) {
        fun plausible(strict: Boolean): Boolean {
            val fwOk = { t: Text -> t.valid && (t.value == null || (if (strict) FIRMWARE else FIRMWARE_RELAXED).matches(t.value)) }
            return fwOk(firmwareLeft) && fwOk(firmwareRight) && serial.valid && (serial.value == null || SERIAL.matches(serial.value))
        }
    }

    /** ASCII text from the state: [value] null when the field is all 00 / FF (absent); [valid] false for binary junk. */
    private class Text(val value: String?, val valid: Boolean)

    private fun text(b: ByteArray, from: Int, len: Int): Text {
        val slice = b.copyOfRange(from, from + len)
        if (slice.all { it == 0.toByte() } || slice.all { it == 0xFF.toByte() }) return Text(null, true)
        // A trailing NUL pad is normal (TLV serial); anything else must be printable.
        val end = slice.indexOfLast { it != 0.toByte() } + 1
        val printable = (0 until end).all { slice[it] in 0x20..0x7E }
        if (!printable) return Text(null, false)
        return Text(String(slice, 0, end, Charsets.US_ASCII).trim().ifEmpty { null }, true)
    }

    private fun flag(b: Byte?): Boolean? = when (b?.toInt()?.and(0xFF)) {
        1 -> true
        0 -> false
        else -> null
    }

    private fun combine(parts: List<Boolean?>): Boolean? {
        val known = parts.filterNotNull()
        return if (known.isEmpty()) null else known.any { it }
    }

    private fun parseHeader(kind: SoundcoreHeader, model: SoundcoreModel, b: ByteArray): Header? {
        fun p(i: Int): Int? = model.battery.percent(b[i].toInt() and 0xFF)
        return when (kind) {
            SoundcoreHeader.G2T -> if (b.size < 32) null else Header(
                kind, p(2), p(3), null, combine(listOf(flag(b[4]), flag(b[5]))),
                text(b, 6, 5), text(b, 11, 5), text(b, 16, 16),
            )
            SoundcoreHeader.SLEEP -> if (b.size < 30) null else Header(
                kind, p(2), p(3), null, null,
                text(b, 4, 5), text(b, 9, 5), text(b, 14, 16),
            )
            SoundcoreHeader.G2H -> if (b.size < 23) null else Header(
                kind, null, null, p(0), flag(b[1]),
                text(b, 2, 5), Text(null, true), text(b, 7, 16),
            )
            else -> null
        }
    }

    // ---- Layout -----------------------------------------------------------------------------

    private fun apply(model: SoundcoreModel, b: ByteArray, h: Header): SoundcoreState {
        val layout = model.layout
        val serial = h.serial.value
        var case: Int? = null
        var caseFirmware: String? = null
        var modes: SoundModes? = null
        var settings: Map<String, Int?> = emptyMap()
        var autoOffIndex: Map<String, Int> = emptyMap()
        var writable = false
        var note: String? = when {
            layout == null -> "Earshot reads this model's battery and firmware; its settings aren't mapped yet."
            !layout.writable -> "Noise cancelling is shown but Earshot can't change it on this model yet."
            else -> null
        }

        if (layout != null) {
            val headerMatches = h.kind == model.header
            val sizeOk = layout.stateSize != null && layout.stateSize == b.size
            val lengthRule = layout.lengthByteAt?.let { at -> b.size > at && at + (b[at].toInt() and 0xFF) == layout.modeBlockAt }
            val fits = b.size >= layout.modeBlockAt + layout.codec.size
            val serialOk = serial != null && serial.startsWith(model.serialPrefix)

            if (headerMatches && sizeOk) {
                layout.caseBatteryAt?.let { case = model.battery.percent(b[it].toInt() and 0xFF) }
                layout.caseFirmwareAt?.let { caseFirmware = text(b, it, 5).value?.takeIf { s -> FIRMWARE.matches(s) } }
                settings = layout.settings.associate { s -> s.id to settingValue(layout, s, b) }
                autoOffIndex = layout.settings.filter { it.autoOff && it.at + 1 < b.size }
                    .mapNotNull { s -> (b[s.at + 1].toInt() and 0xFF).takeIf { it in layout.autoOffMinutes.indices }?.let { s.id to it } }
                    .toMap()
            }
            if (headerMatches && fits && (sizeOk || lengthRule == true)) {
                modes = SoundModes(layout.codec, b.copyOfRange(layout.modeBlockAt, layout.modeBlockAt + layout.codec.size))
            }
            if (layout.writable) {
                val ok = modes != null && sizeOk && lengthRule != false && serialOk
                writable = ok
                if (!ok) {
                    note = "This doesn't match the layout Earshot knows for the ${model.displayName.removePrefix("Soundcore ")} " +
                        "(another firmware, or a different model), so its settings are read-only."
                }
            }
        }
        return SoundcoreState(
            model = model, bodySize = b.size,
            leftPercent = h.left, rightPercent = h.right, overallPercent = h.overall, casePercent = case,
            charging = h.charging,
            firmwareLeft = h.firmwareLeft.value, firmwareRight = h.firmwareRight.value, caseFirmware = caseFirmware, serial = serial,
            ambient = modes?.ambient, modes = modes, settings = settings, writable = writable, note = note,
            autoOffIndex = autoOffIndex,
        )
    }

    private fun settingValue(layout: SoundcoreLayout, s: SoundcoreSetting, b: ByteArray): Int? {
        if (s.at + (if (s.autoOff) 1 else 0) >= b.size) return null
        val first = b[s.at].toInt() and 0xFF
        if (!s.autoOff) return if (first <= 1) first else null
        val index = b[s.at + 1].toInt() and 0xFF
        return when {
            first == 0 -> 0
            first == 1 && index in layout.autoOffMinutes.indices -> index + 1
            else -> null
        }
    }

    // ---- TLV generation (read-only) ---------------------------------------------------------

    private fun readTlv(model: SoundcoreModel, b: ByteArray): SoundcoreState? {
        val tags = HashMap<Int, ByteArray>()
        var i = 0
        while (i + 2 <= b.size) {
            val tag = b[i].toInt() and 0xFF
            val len = b[i + 1].toInt() and 0xFF
            if (i + 2 + len > b.size) break
            tags[tag] = b.copyOfRange(i + 2, i + 2 + len)
            i += 2 + len
        }
        if (tags[3]?.size != 2 && tags[4]?.size != 2 && tags[7] == null) return null

        fun percent(tag: Int): Int? = tags[tag]?.takeIf { it.size == 2 }?.let { model.battery.percent(it[1].toInt() and 0xFF) }
        fun charging(tag: Int): Boolean? = tags[tag]?.takeIf { it.size == 2 }?.let { flag(it[0]) }
        fun firmware(tag: Int): String? = tags[tag]?.takeIf { it.size == 5 }?.let { text(it, 0, 5).value }
        val case = tags[8]?.takeIf { it.size == 2 }?.let { model.battery.percent(it[1].toInt() and 0xFF) }
        val serial = tags[7]?.takeIf { it.size >= 16 }?.let { text(it, 0, 16).value }
        val ambient = tags[36]?.firstOrNull()?.let {
            when (it.toInt() and 0xFF) {
                0, 3 -> AmbientMode.NOISE_CANCELLING
                1 -> AmbientMode.TRANSPARENCY
                2 -> AmbientMode.NORMAL
                else -> null
            }
        }
        return SoundcoreState(
            model = model, bodySize = b.size,
            leftPercent = percent(3), rightPercent = percent(4), overallPercent = null, casePercent = case,
            charging = combine(listOf(charging(3), charging(4))),
            firmwareLeft = firmware(5), firmwareRight = firmware(6), caseFirmware = firmware(9), serial = serial,
            ambient = ambient, modes = null, settings = emptyMap(), writable = false,
            note = "Earshot reads this model's battery, firmware and sound mode; its write commands aren't documented yet.",
        )
    }
}
