package dev.earshot.bt

/** What happened to a headset's Bluetooth link. */
enum class TimelineKind {
    /** The baseband link (ACL) came up or went down. */
    LINK_UP,
    LINK_DOWN,

    /** An audio profile (A2DP, HFP, LE Audio) connected or disconnected; [TimelineEvent.detail] names it and, from Android 17, why it failed. */
    PROFILE_UP,
    PROFILE_DOWN,

    /** Paired or unpaired. */
    BONDED,
    UNBONDED,

    /** The phone found the headset's pairing key missing (Android 16+): the bond is lost until re-pairing. */
    KEY_MISSING,

    /** Link encryption changed (Android 16+): on/off, key size, algorithm. */
    ENCRYPTION,

    /** The music codec Android negotiated. */
    CODEC,

    /** The call codec (narrowband CVSD, wideband mSBC, super-wideband LC3), Android 17.2+. */
    CALL_CODEC,
}

/** One line of the diagnostics timeline. Addresses only, never names: the log stays free of personal data. */
data class TimelineEvent(val timeMs: Long, val address: String, val kind: TimelineKind, val detail: String = "") {
    /** One tab-separated line for the timeline file. */
    fun encode(): String = listOf(timeMs.toString(), address.uppercase(), kind.name, detail.replace(CONTROL, " ")).joinToString("\t")

    companion object {
        private val CONTROL = Regex("[\\t\\r\\n]")

        /** Null for a line this version can't read (a newer kind, a torn write): it is skipped, never fatal. */
        fun decode(line: String): TimelineEvent? {
            val parts = line.split('\t', limit = 4)
            if (parts.size < 3) return null
            val time = parts[0].toLongOrNull() ?: return null
            val kind = TimelineKind.entries.firstOrNull { it.name == parts[2] } ?: return null
            return TimelineEvent(time, parts[1], kind, parts.getOrElse(3) { "" })
        }
    }
}

/** When a headset was last seen, from its timeline: the last link or profile event. */
data class LastSeen(val timeMs: Long, val connected: Boolean)

/**
 * The timeline's rules, Android-free: capacity, last seen, and the readable labels for the codes
 * Android sends (research "Universal headset control accelerators", android_platform §5).
 */
object BtTimeline {
    /** Enough for a few weeks of daily use; the file stays a few tens of kilobytes. */
    const val CAPACITY = 500

    /** Keep the newest [capacity] events, oldest first. */
    fun trim(events: List<TimelineEvent>, capacity: Int = CAPACITY): List<TimelineEvent> =
        if (events.size <= capacity) events else events.subList(events.size - capacity, events.size)

    /**
     * One link-up sends the same news several ways (A2DP and LE Audio both report, the codec is
     * broadcast two to four times): an event equal to one recorded in the last [windowMs] is a
     * repeat, unless something in between contradicts it. A quick drop and reconnect (up, down, up)
     * keeps its second link-up.
     */
    fun isRepeat(recent: List<TimelineEvent>, event: TimelineEvent, windowMs: Long = REPEAT_WINDOW_MS): Boolean {
        for (it in recent.asReversed()) {
            if (event.timeMs - it.timeMs > windowMs) return false
            if (!it.address.equals(event.address, ignoreCase = true)) continue
            if (it.kind == event.kind && it.detail == event.detail) return true
            if (contradicts(it, event)) return false
        }
        return false
    }

    /** Up after down (or down after up) for the same link or profile. */
    private fun contradicts(earlier: TimelineEvent, event: TimelineEvent): Boolean {
        val pair = setOf(earlier.kind, event.kind)
        return when (pair) {
            setOf(TimelineKind.LINK_UP, TimelineKind.LINK_DOWN) -> true
            setOf(TimelineKind.PROFILE_UP, TimelineKind.PROFILE_DOWN) -> profileOf(earlier) == profileOf(event)
            else -> false
        }
    }

    /** "A2DP" from "A2DP" or "A2DP, the audio stream couldn't be set up". */
    private fun profileOf(event: TimelineEvent): String = event.detail.substringBefore(',')

    const val REPEAT_WINDOW_MS = 3_000L

    /** Kinds that describe a state: recorded when it changes, not each time Android re-announces it. */
    private val STATE_KINDS = setOf(TimelineKind.CODEC, TimelineKind.ENCRYPTION, TimelineKind.CALL_CODEC)

    /**
     * True for a codec, encryption or call-codec event that says what the last one of its kind
     * already said since the headset last linked up or dropped (One UI re-broadcasts the codec on
     * play/pause). After a new link-up, or a disconnect, the state is news again.
     */
    fun isUnchanged(history: List<TimelineEvent>, event: TimelineEvent): Boolean {
        if (event.kind !in STATE_KINDS) return false
        val last = history.asReversed().firstOrNull {
            it.address.equals(event.address, ignoreCase = true) && (it.kind == event.kind || it.kind in LINK_KINDS)
        } ?: return false
        return last.kind == event.kind && last.detail == event.detail
    }

    private val LINK_KINDS = setOf(TimelineKind.LINK_UP, TimelineKind.LINK_DOWN, TimelineKind.PROFILE_DOWN)

    fun lastSeen(events: List<TimelineEvent>, address: String): LastSeen? = events.asReversed()
        .firstOrNull { it.address.equals(address, ignoreCase = true) && it.kind in PRESENCE_KINDS }
        ?.let { LastSeen(it.timeMs, connected = it.kind == TimelineKind.LINK_UP || it.kind == TimelineKind.PROFILE_UP) }

    private val PRESENCE_KINDS = setOf(TimelineKind.LINK_UP, TimelineKind.LINK_DOWN, TimelineKind.PROFILE_UP, TimelineKind.PROFILE_DOWN)

    /** `BluetoothA2dp` / `BluetoothHeadset.EXTRA_DISCONNECTED_REASON` (Android 17), from `BluetoothStatusCodes`. */
    fun disconnectReason(code: Int): String? = when (code) {
        1400 -> "service discovery (SDP) failed"
        1401 -> "the audio stream couldn't be set up"
        1402 -> "role switch failed"
        1403 -> "AVDTP discovery failed"
        1404 -> "the phone ran out of Bluetooth resources"
        1405 -> "the RFCOMM connection failed"
        Int.MAX_VALUE -> "unknown reason"
        // SUCCESS: an ordinary disconnect, not a failure.
        0, NO_CODE -> null
        else -> "reason $code"
    }

    /** `BluetoothDevice.EXTRA_BOND_LOSS_REASON` (Android 16.1). */
    fun bondLossReason(code: Int): String = when (code) {
        1 -> "classic authentication failed"
        2 -> "the headset started a new classic pairing"
        3 -> "LE encryption failed"
        4 -> "the headset started a new LE pairing"
        else -> "reason unknown"
    }

    /** `ACTION_ENCRYPTION_CHANGE` extras (Android 16). */
    fun encryption(enabled: Boolean, keySize: Int, algorithm: Int): String {
        if (!enabled) return "off"
        val name = when (algorithm) {
            1 -> "E0"
            2 -> "AES"
            0 -> "none"
            else -> "unknown"
        }
        return if (keySize > 0) "$name, $keySize-byte key" else name
    }

    /**
     * `BluetoothHeadset.CODEC_TYPE_*` (Android 17.2) by constant name: the values are not in the
     * 37.0 SDK this app compiles against, so the recorder finds the name of the returned value at
     * runtime instead of trusting guessed numbers. Null for UNSUPPORTED or an unknown name.
     */
    fun callCodecLabel(constantName: String): String? = when (constantName.removePrefix("CODEC_TYPE_")) {
        "CVSD" -> "CVSD (narrowband)"
        "MSBC" -> "mSBC (wideband)"
        "LC3_SWB" -> "LC3 (super-wideband)"
        "VENDOR_SPECIFIC" -> "vendor-specific"
        else -> null
    }

    const val NO_CODE = Int.MIN_VALUE

    /** One readable line for the screen and the connection report, without the address. */
    fun describe(event: TimelineEvent): String {
        val what = when (event.kind) {
            TimelineKind.LINK_UP -> "Connected"
            TimelineKind.LINK_DOWN -> "Disconnected"
            TimelineKind.PROFILE_UP -> "Audio profile up"
            TimelineKind.PROFILE_DOWN -> "Audio profile down"
            TimelineKind.BONDED -> "Paired"
            TimelineKind.UNBONDED -> "Unpaired"
            TimelineKind.KEY_MISSING -> "Pairing key missing"
            TimelineKind.ENCRYPTION -> "Encryption"
            TimelineKind.CODEC -> "Music codec"
            TimelineKind.CALL_CODEC -> "Call codec"
        }
        return if (event.detail.isBlank()) what else "$what: ${event.detail}"
    }
}
