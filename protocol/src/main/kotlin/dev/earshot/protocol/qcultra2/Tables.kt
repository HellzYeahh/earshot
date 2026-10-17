package dev.earshot.protocol.qcultra2

/*
 * Enumerations of wire values, from bosectl constants.py / NOTES.md. Every table keeps the raw
 * code so unknown values coming from newer firmware still round-trip through the UI.
 */

/** `[31.10]` byte 2 and `[31.6]` byte 44. */
enum class SpatialMode(val code: Int) {
    OFF(0), ROOM(1), HEAD(2);

    companion object {
        fun fromCode(code: Int): SpatialMode? = entries.firstOrNull { it.code == code }
    }
}

/** `[1.11]` level byte. Note the order: 1 is *high*, 3 is *low*. */
enum class SidetoneLevel(val code: Int) {
    OFF(0), HIGH(1), MEDIUM(2), LOW(3);

    companion object {
        fun fromCode(code: Int): SidetoneLevel? = entries.firstOrNull { it.code == code }
    }
}

/** `[1.7]` band id. */
enum class EqBandId(val code: Int, val label: String) {
    BASS(0, "Bass"), MID(1, "Mid"), TREBLE(2, "Treble");

    companion object {
        fun fromCode(code: Int): EqBandId? = entries.firstOrNull { it.code == code }
    }
}

/** `[5.1]` byte 2 (Bose `AudioControlSourceType`). */
enum class AudioSourceType(val code: Int) {
    NONE(0), BLUETOOTH(1), AUXILIARY(2), USB(3), LE_AUDIO_UNICAST(4), LE_AUDIO_BROADCAST(5);

    companion object {
        fun fromCode(code: Int): AudioSourceType? = entries.firstOrNull { it.code == code }
    }
}

/** `[5.3]` START payload (Bose `AudioControlValue`); the GET bitset uses the same codes. */
enum class TransportControl(val code: Int) {
    STOP(0), PLAY(1), PAUSE(2), TRACK_FORWARD(3), TRACK_BACK(4),
    FAST_FORWARD_PRESS(5), FAST_FORWARD_RELEASE(6), REWIND_PRESS(7), REWIND_RELEASE(8);

    companion object {
        fun fromCode(code: Int): TransportControl? = entries.firstOrNull { it.code == code }
    }
}

/** `[2.2]` record component id (earbuds); headphones report a single record with id 0. */
enum class BatteryComponent(val code: Int) {
    SINGLE(0), RIGHT(1), LEFT(2), CASE(3), COMBINED_BUDS(4);

    companion object {
        fun fromCode(code: Int): BatteryComponent? = entries.firstOrNull { it.code == code }
    }
}

/** `[1.3]` language id (low 5 bits). */
enum class VoicePromptLanguage(val code: Int, val label: String) {
    UK_ENGLISH(0, "UK English"), US_ENGLISH(1, "US English"), FRENCH(2, "French"),
    ITALIAN(3, "Italian"), GERMAN(4, "German"), EU_SPANISH(5, "EU Spanish"),
    MX_SPANISH(6, "MX Spanish"), BR_PORTUGUESE(7, "BR Portuguese"), MANDARIN(8, "Mandarin"),
    KOREAN(9, "Korean"), RUSSIAN(10, "Russian"), POLISH(11, "Polish"), HEBREW(12, "Hebrew"),
    TURKISH(13, "Turkish"), DUTCH(14, "Dutch"), JAPANESE(15, "Japanese"), CANTONESE(16, "Cantonese"),
    ARABIC(17, "Arabic"), SWEDISH(18, "Swedish"), DANISH(19, "Danish"), NORWEGIAN(20, "Norwegian"),
    FINNISH(21, "Finnish"), HINDI(22, "Hindi");

    companion object {
        fun fromCode(code: Int): VoicePromptLanguage? = entries.firstOrNull { it.code == code }
    }
}

/** `[1.9]` byte 0 (Bose `ConfigurableButtonId`). 0x80 is the QC Ultra 2 programmable button. */
enum class ButtonId(val code: Int, val label: String) {
    DISTAL_CNC(0x00, "CNC button"), RESERVED(0x01, "Reserved"), VPA(0x02, "Voice assistant"),
    RIGHT_SHORTCUT(0x03, "Right shortcut"), LEFT_SHORTCUT(0x04, "Left shortcut"),
    ACTION(0x10, "Action button"), SHORTCUT(0x80, "Shortcut button");

    companion object {
        fun fromCode(code: Int): ButtonId? = entries.firstOrNull { it.code == code }
    }
}

/** `[1.9]` byte 1. */
enum class ButtonEvent(val code: Int, val label: String) {
    RESERVED(0, "Reserved"), RISING_EDGE(1, "Rising edge"), FALLING_EDGE(2, "Falling edge"),
    SHORT_PRESS(3, "Short press"), SINGLE_PRESS(4, "Single press"), PRESS_AND_HOLD(5, "Press and hold"),
    DOUBLE_PRESS(6, "Double press"), DOUBLE_PRESS_AND_HOLD(7, "Double press and hold"),
    TRIPLE_PRESS(8, "Triple press"), LONG_PRESS(9, "Long press"), VERY_LONG_PRESS(10, "Very long press"),
    VERY_VERY_LONG_PRESS(11, "Very very long press"), VERY_VERY_VERY_LONG_PRESS(12, "Very very very long press");

    companion object {
        fun fromCode(code: Int): ButtonEvent? = entries.firstOrNull { it.code == code }
    }
}

/** `[1.9]` byte 2 and the bit positions of the supported/unavailable masks (Bose `ActionButtonMode`). */
enum class ButtonAction(val code: Int, val label: String) {
    NOT_CONFIGURED(0, "Not configured"), VPA(1, "Voice assistant"), ANC_CYCLE(2, "Cycle noise cancelling"),
    BATTERY_LEVEL(3, "Battery level"), PLAY_PAUSE(4, "Play / pause"), INCREASE_CNC(5, "Increase CNC"),
    DECREASE_CNC(6, "Decrease CNC"), TOGGLE_WAKE_WORD(7, "Toggle wake word"), SWITCH_DEVICE(8, "Switch device"),
    CONVERSATION_MODE(9, "Conversation mode"), TRACK_FORWARD(10, "Next track"), TRACK_BACK(11, "Previous track"),
    FETCH_NOTIFICATIONS(12, "Fetch notifications"), WIND_MODE(13, "Wind mode"), DISABLED(14, "Disabled"),
    CLIENT_INTERACTION(15, "App interaction"), SPOTIFY_GO(16, "Spotify Tap"), MODES_CAROUSEL(17, "Cycle modes"),
    VOLUME_PRESET_CAROUSEL(18, "Cycle volume presets"),
    SPATIAL_AUDIO_MODE(19, "Spatial audio"), LINE_IN_SWITCH(20, "Line-in switch"), LINKING(21, "Linking"),
    PANIC_LOCAL(22, "Panic (local)"), PANIC_REMOTE(23, "Panic (remote)"), PANIC_BOTH(24, "Panic (both)"),
    THIRD_PARTY_SERVICE(25, "Third-party service"), SMART_ACTION(26, "Smart action"), ALEXA(27, "Alexa");

    companion object {
        fun fromCode(code: Int): ButtonAction? = entries.firstOrNull { it.code == code }
    }
}

/** `[5.4]` byte 0 (Bose `AudioStatus`). */
enum class PlaybackStatus(val code: Int) {
    STOPPED(0), PLAYING(1), PAUSED(2), FAST_FORWARDING(3), REWINDING(4);

    companion object {
        fun fromCode(code: Int): PlaybackStatus? = entries.firstOrNull { it.code == code }
    }
}

/** `[2.16]` (Bose `OperationMode`). */
enum class OperationModeCode(val code: Int) {
    UNKNOWN(0), NORMAL(1), OTA_ONLY(2);

    companion object {
        fun fromCode(code: Int): OperationModeCode? = entries.firstOrNull { it.code == code }
    }
}

/** `[4.18]` (Bose `AvailableToConnect`). */
enum class AvailableToConnectCode(val code: Int) {
    NOT_AVAILABLE(0), AVAILABLE(1), UNKNOWN(2);

    companion object {
        fun fromCode(code: Int): AvailableToConnectCode? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Voice prompt spoken when a mode is selected: `[31.6]` bytes 1-2 as (0, code). The first byte
 * has only ever been seen as 0, so the table is keyed on the second.
 */
enum class ModePrompt(val code: Int) {
    NONE(0), QUIET(1), AWARE(2), TRANSPARENT(3), TRANSPARENCY(4), MASKING(5), COMFORT(6), COMMUTE(7),
    OUTDOOR(8), WORKOUT(9), HOME(10), WORK(11), MUSIC(12), FOCUS(13), RELAX(14), FLIGHT(15), AIRPORT(16),
    DRIVING(17), TRAINING(18), GYM(19), RUN(20), WALK(21), HIKE(22), TALK(23), CALL(24), WHISPER(25),
    HEARING(26), LEARN(27), PODCAST(28), AUDIOBOOK(29), CALM(30), SLEEP(31), MEDITATE(32), YOGA(33),
    IMMERSION(34), STEREO(35), CINEMA(36);

    companion object {
        fun fromBytes(b1: Int, b2: Int): ModePrompt? = if (b1 != 0) null else entries.firstOrNull { it.code == b2 }
    }
}
