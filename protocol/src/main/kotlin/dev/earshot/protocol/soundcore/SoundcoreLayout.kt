package dev.earshot.protocol.soundcore

/**
 * One single-setting flag in a model's `01 01` state, with the command that sets it. Offsets are
 * firmware specific (OpenSCQ30 notes: a Liberty 5 on firmware 03.11 no longer matches), so a
 * setting is only read or written when the state has exactly the size the layout documents.
 */
class SoundcoreSetting(
    /** The driver's key; also the `VendorControl` id in the app. */
    val id: String,
    val title: String,
    /** `(category shl 8) or code` of the write. */
    val command: Int,
    /** Offset of the value in the `01 01` body. */
    val at: Int,
    val subtitle: String? = null,
    /**
     * Auto power-off: the value is two bytes `[enabled, duration index]` at [at] and [at]+1, the
     * durations come from [SoundcoreLayout.autoOffMinutes]. Otherwise a single `[0|1]` byte.
     */
    val autoOff: Boolean = false,
)

/** Ids of the settings the layouts below use. */
object SoundcoreSettings {
    const val TOUCH_TONE = "touch_tone"
    const val GAMING_MODE = "gaming_mode"
    const val LEAK_COMPENSATION = "leak_compensation"
    const val MULTIPOINT = "multipoint"
    const val LOW_BATTERY_PROMPT = "low_battery_prompt"
    const val WEARING_TONE = "wearing_tone"
    const val WEAR_DETECTION = "wear_detection"
    const val SURROUND = "surround"
    const val AUTO_OFF = "auto_off"
}

/**
 * Where a model keeps its sound modes and settings in the `01 01` state, from the annotated
 * captures in the research notes (facts only). A layout is trusted only when all of these hold,
 * so a firmware that moved things falls back to a read-only view instead of a wrong write:
 *  - the state has exactly [stateSize] bytes (unknown for a few models: then nothing is trusted),
 *  - the length byte at [lengthByteAt] points at [modeBlockAt] (the notes' rule for gen-2 earbuds),
 *  - the serial in the state starts with the model's digits (soundcorebridge's policy).
 */
class SoundcoreLayout(
    val stateSize: Int?,
    val modeBlockAt: Int,
    val codec: SoundModeCodec,
    /** Offset whose value, added to itself, is [modeBlockAt]; null where the notes did not measure one. */
    val lengthByteAt: Int? = null,
    val caseBatteryAt: Int? = null,
    val caseFirmwareAt: Int? = null,
    val settings: List<SoundcoreSetting> = emptyList(),
    /** Minutes for each duration index of an auto-off setting. */
    val autoOffMinutes: List<Int> = emptyList(),
    /**
     * Earshot may write this model's sound modes and settings. False for layouts that are only
     * read (the mode shows, every change is refused with a message).
     */
    val writable: Boolean = false,
    /**
     * Send `05 01` then `05 81` once after the first state read. Some firmware ignores writes until
     * the app's session-init has run (verified for the Space 2; Gadgetbridge sends it on several
     * more); the OpenSCQ30 captures for these models answer it. Unverified on hardware.
     */
    val sessionInit: Boolean = false,
) {
    /** The packets this layout may send: the mode block and each setting, with the body shapes they take. */
    fun allows(command: Int, body: ByteArray): Boolean {
        if (!writable) return false
        if (command == SoundcoreCommands.SET_SOUND_MODES) return body.size == codec.size
        if (sessionInit && command == SoundcoreCommands.SESSION_INIT) return body.isEmpty()
        val setting = settings.firstOrNull { it.command == command } ?: return false
        return if (setting.autoOff) {
            body.size == 2 && (body[0].toInt() and 0xFF) <= 1 && (body[1].toInt() and 0xFF) in autoOffMinutes.indices
        } else {
            body.size == 1 && (body[0].toInt() and 0xFF) <= 1
        }
    }

    fun setting(id: String): SoundcoreSetting? = settings.firstOrNull { it.id == id }
}

/** The layouts Earshot knows, named after the model they came from. Offsets are the notes' §3 tables. */
object SoundcoreLayouts {
    private val TENS = listOf(10, 20, 30, 60)
    private val HALF_HOURS = listOf(30, 60, 90, 120)

    private fun touchTone(at: Int) = SoundcoreSetting(SoundcoreSettings.TOUCH_TONE, "Touch tone", SoundcoreCommands.SET_TOUCH_TONE, at, "A beep when you tap the earbuds")
    private fun multipoint(at: Int) = SoundcoreSetting(SoundcoreSettings.MULTIPOINT, "Multipoint", SoundcoreCommands.SET_MULTIPOINT, at, "Stay connected to two devices at once")
    private fun autoOff(at: Int) = SoundcoreSetting(SoundcoreSettings.AUTO_OFF, "Auto power-off", SoundcoreCommands.SET_AUTO_OFF, at, autoOff = true)
    private fun lowBattery(at: Int) = SoundcoreSetting(SoundcoreSettings.LOW_BATTERY_PROMPT, "Low battery prompt", SoundcoreCommands.SET_LOW_BATTERY_PROMPT, at)
    private fun gaming(at: Int, command: Int) = SoundcoreSetting(SoundcoreSettings.GAMING_MODE, "Gaming mode", command, at, "Lower latency")

    // ---- Gen-2 earbuds, 7- and 8-byte codecs (recommended order, step 2) ------------------

    /** Liberty 4 NC (A3947), 159-byte state. Length byte 108 = 18 → 126. */
    val LIBERTY_4_NC = SoundcoreLayout(
        stateSize = 159, modeBlockAt = 126, codec = SoundModeCodec.V2_7_TRANSPORT, lengthByteAt = 108, caseBatteryAt = 139,
        settings = listOf(
            touchTone(144), gaming(143, SoundcoreCommands.SET_GAMING_MODE_10),
            SoundcoreSetting(SoundcoreSettings.LEAK_COMPENSATION, "Sound leak compensation", SoundcoreCommands.SET_LEAK_COMPENSATION, 141),
            multipoint(147), lowBattery(157), autoOff(154),
        ),
        autoOffMinutes = TENS, writable = true,
    )

    /** Liberty 5 (A3957), 154 bytes. The Faker capture answers `05 01`/`05 81`. */
    val LIBERTY_5 = SoundcoreLayout(
        stateSize = 154, modeBlockAt = 119, codec = SoundModeCodec.V2_7_TRANSPORT, lengthByteAt = 109, caseBatteryAt = 37, caseFirmwareAt = 32,
        settings = listOf(
            SoundcoreSetting(SoundcoreSettings.WEARING_TONE, "Wearing tone", SoundcoreCommands.SET_WEARING_TONE, 127),
            multipoint(130), autoOff(131),
            SoundcoreSetting(SoundcoreSettings.WEAR_DETECTION, "Wear detection", SoundcoreCommands.SET_WEAR_DETECTION, 144, "Pause when you take an earbud out"),
            touchTone(145), gaming(146, SoundcoreCommands.SET_GAMING_MODE_10),
        ),
        autoOffMinutes = TENS, writable = true, sessionInit = true,
    )

    /** P40i (A3955), 152 bytes. */
    val P40I = SoundcoreLayout(
        stateSize = 152, modeBlockAt = 119, codec = SoundModeCodec.V2_7_SCENE, lengthByteAt = 109, caseBatteryAt = 37, caseFirmwareAt = 32,
        settings = listOf(touchTone(133), multipoint(134), autoOff(138), lowBattery(141)),
        autoOffMinutes = TENS, writable = true, sessionInit = true,
    )

    /** R50i NC / P30i (A3959), 91 bytes. Its low-battery prompt id is not documented, so it is not offered. */
    val R50I_NC = SoundcoreLayout(
        stateSize = 91, modeBlockAt = 64, codec = SoundModeCodec.V2_7_MIRROR, lengthByteAt = 54,
        settings = listOf(touchTone(72), multipoint(73), autoOff(75), gaming(78, SoundcoreCommands.SET_GAMING_MODE)),
        autoOffMinutes = TENS, writable = true,
    )

    /** A30i (A3958), 91 bytes, the same mode block as the R50i NC. */
    val A30I = SoundcoreLayout(
        stateSize = 91, modeBlockAt = 64, codec = SoundModeCodec.V2_7_MIRROR, lengthByteAt = 54,
        settings = listOf(
            touchTone(72),
            SoundcoreSetting(SoundcoreSettings.SURROUND, "3D surround", SoundcoreCommands.SET_SURROUND, 74),
            autoOff(75),
        ),
        autoOffMinutes = TENS, writable = true,
    )

    /** P31i / R60i NC (D1202), 152 bytes, 8-byte codec. */
    val P31I = SoundcoreLayout(
        stateSize = 152, modeBlockAt = 119, codec = SoundModeCodec.V2_8, lengthByteAt = 109, caseBatteryAt = 37, caseFirmwareAt = 32,
        settings = listOf(touchTone(128), lowBattery(129), multipoint(131), autoOff(132)),
        autoOffMinutes = TENS, writable = true, sessionInit = true,
    )

    // ---- Older 6-byte earbuds (step 4) ----------------------------------------------------

    /** Space A40 (A3936), 143 bytes. */
    val SPACE_A40 = SoundcoreLayout(
        stateSize = 143, modeBlockAt = 117, codec = SoundModeCodec.V2_6_TWS, lengthByteAt = 103, caseBatteryAt = 124,
        settings = listOf(touchTone(123), multipoint(127), autoOff(128), gaming(130, SoundcoreCommands.SET_GAMING_MODE)),
        autoOffMinutes = HALF_HOURS, writable = true,
    )

    /** Liberty 3 Pro (A3952), 153 bytes. */
    val LIBERTY_3_PRO = SoundcoreLayout(
        stateSize = 153, modeBlockAt = 121, codec = SoundModeCodec.V2_6_TWS, lengthByteAt = 103, caseBatteryAt = 130,
        settings = listOf(
            touchTone(127),
            SoundcoreSetting(SoundcoreSettings.WEAR_DETECTION, "Wear detection", SoundcoreCommands.SET_WEAR_DETECTION, 128, "Pause when you take an earbud out"),
            SoundcoreSetting(SoundcoreSettings.WEARING_TONE, "Wearing tone", SoundcoreCommands.SET_WEARING_TONE, 136),
            autoOff(138),
        ),
        autoOffMinutes = HALF_HOURS, writable = true,
    )

    /** Liberty 4 (A3953): the state size isn't in the notes, so the mode shows but nothing is written. */
    val LIBERTY_4 = SoundcoreLayout(stateSize = null, modeBlockAt = 121, codec = SoundModeCodec.V2_6_L4, lengthByteAt = 103)

    /** Liberty 4 Pro (A3954), 165 bytes; the slider polarity is disputed, so it is read-only. */
    val LIBERTY_4_PRO = SoundcoreLayout(stateSize = 165, modeBlockAt = 125, codec = SoundModeCodec.SLIDER, lengthByteAt = 115, caseBatteryAt = 37, caseFirmwareAt = 32)

    // ---- Space headphones (step 3) --------------------------------------------------------

    /** Space One (A3035), 95 bytes. */
    val SPACE_ONE = SoundcoreLayout(
        stateSize = 95, modeBlockAt = 69, codec = SoundModeCodec.V2_6_SPACE,
        settings = listOf(multipoint(82), autoOff(85)),
        autoOffMinutes = HALF_HOURS, writable = true,
    )

    /** Space One Pro (A3062), 95 bytes. */
    val SPACE_ONE_PRO = SoundcoreLayout(
        stateSize = 95, modeBlockAt = 69, codec = SoundModeCodec.V2_6_SPACE_PRO,
        settings = listOf(multipoint(79), autoOff(80)),
        autoOffMinutes = HALF_HOURS, writable = true,
    )

    /** Space Q45 (A3040), 142 bytes. */
    val SPACE_Q45 = SoundcoreLayout(
        stateSize = 142, modeBlockAt = 49, codec = SoundModeCodec.V2_6_Q45,
        settings = listOf(multipoint(62), autoOff(65)),
        autoOffMinutes = HALF_HOURS, writable = true,
    )

    /**
     * Space 2 (D1402), 103 bytes. Read-only: it discards writes until the app's handshake
     * (`01 01`, `05 01 [01]`, `05 81` ×2, `05 81 [01]`, `05 81`, `18 85 [01]`) has run, and the
     * meaning of `18 85` is unknown, so Earshot doesn't send it.
     */
    val SPACE_2 = SoundcoreLayout(stateSize = 103, modeBlockAt = 71, codec = SoundModeCodec.SPACE_2)
}
