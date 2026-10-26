package dev.earshot.shortcut

import dev.earshot.core.ButtonAction
import dev.earshot.core.HeadphoneState
import dev.earshot.core.PlaybackState

/**
 * What "Detect a button" can tell about a press. Learned on the QC Ultra 2 (fw 10.12, a Samsung
 * Galaxy on Android 17): only the assistant gesture (HFP `AT+BVRA` → `VOICE_COMMAND`) reaches an
 * app; play/pause, track and volume go from Android's Bluetooth stack (AVRCP) straight to the
 * music app, so Earshot only sees their effects. The power button sends nothing at all.
 */
enum class ControlKind(val remappable: Boolean) {
    /** The press that starts the phone's assistant: the one Earshot can give a new job. */
    ASSISTANT_GESTURE(remappable = true),
    PLAY_PAUSE(remappable = false),
    TRACK_CHANGE(remappable = false),
    VOLUME(remappable = false),
    /** A Bose `[2.12]` push: the headphones say which button and event they saw. */
    HEADPHONE_BUTTON_EVENT(remappable = false),
    /** A call is ringing or active: the buttons answer, hang up and change the call volume. */
    CALL_CONTROLS(remappable = false),
}

/** Who saw it: the Bose connection, Android's audio service, Earshot's trigger, or demo mode's buttons. */
enum class SignalOrigin { HEADPHONES, PHONE, TRIGGER, DEMO }

/** One raw observation fed to [DetectionLog.add]; [at] is wall-clock milliseconds. */
sealed interface ControlSignal {
    val at: Long
    val origin: SignalOrigin

    data class PlayState(val playing: Boolean, override val origin: SignalOrigin, override val at: Long) : ControlSignal
    data class TrackChange(val seeking: Boolean, override val origin: SignalOrigin, override val at: Long) : ControlSignal
    /** [from] null when the previous level is unknown; the scale is the origin's own (Bose 0..31, Android's stream steps). */
    data class Volume(val from: Int?, val to: Int, override val origin: SignalOrigin, override val at: Long) : ControlSignal
    /** [buttonLabel] / [eventLabel]: the driver's names for the button and the press, when it has them. */
    data class ButtonEventSeen(
        val buttonId: Int,
        val eventType: Int,
        val action: ButtonAction,
        override val at: Long,
        override val origin: SignalOrigin = SignalOrigin.HEADPHONES,
        val buttonLabel: String? = null,
        val eventLabel: String? = null,
    ) : ControlSignal
    /** [headsetAddress]: the headset that pressed, when Android said; [wired] for the wired / USB-C alias. */
    data class AssistantGesture(
        val headsetAddress: String?,
        val wired: Boolean,
        override val at: Long,
        override val origin: SignalOrigin = SignalOrigin.TRIGGER,
    ) : ControlSignal
    data class CallState(val active: Boolean, override val at: Long, override val origin: SignalOrigin = SignalOrigin.PHONE) : ControlSignal
}

/**
 * One row of the detector's list. [label] and [detail] are plain English for logs and the
 * Bose button names (the UI shows its own strings per [kind]); [timestamp] is the first signal,
 * [lastAt] the last one merged in, [count] how many were merged.
 */
data class DetectedControl(
    val kind: ControlKind,
    val label: String,
    val remappable: Boolean = kind.remappable,
    val detail: String? = null,
    val timestamp: Long,
    val lastAt: Long = timestamp,
    val count: Int = 1,
    val origin: SignalOrigin,
    /** Who saw the last signal merged in (play/pause: the other side confirming a press). */
    val lastOrigin: SignalOrigin = origin,
    /** VOLUME only: first and latest level on [origin]'s scale. */
    val volumeFrom: Int? = null,
    val volumeTo: Int? = null,
    /** ASSISTANT_GESTURE only: the headset that pressed, when known. */
    val headsetAddress: String? = null,
    /** PLAY_PAUSE only: the latest state seen. */
    val playing: Boolean? = null,
) {
    /** A "still" finger on the QC Ultra 2 strip reads as one or two volume steps. */
    val isSmallVolumeNudge: Boolean
        get() = kind == ControlKind.VOLUME && volumeFrom != null && volumeTo != null &&
            kotlin.math.abs(volumeTo - volumeFrom) <= SMALL_VOLUME_STEPS && origin == SignalOrigin.HEADPHONES

    companion object {
        const val SMALL_VOLUME_STEPS = 2
    }
}

/**
 * The detector's list, newest first, with the debounce rules. Immutable, so concurrent
 * callbacks update it with one atomic `StateFlow.update`.
 *
 * - VOLUME: steps within [VOLUME_MERGE_MS] of the previous one join it (one swipe = one row),
 *   whichever side saw them (the Bose `[5.5]` push and Android's stream volume both fire).
 * - PLAY_PAUSE: repeats on the same side within [PLAY_PAUSE_MERGE_MS] join; the other side
 *   seeing the same press later (the player starts up to a second after the `[5.4]` push) joins
 *   within [SAME_PRESS_MS].
 * - While a call is active, volume and play/pause effects belong to the call and are dropped.
 */
data class DetectionLog(val entries: List<DetectedControl> = emptyList(), val callActive: Boolean = false) {

    val sawAssistantGesture: Boolean get() = entries.any { it.kind == ControlKind.ASSISTANT_GESTURE }

    fun add(signal: ControlSignal): DetectionLog = when (signal) {
        is ControlSignal.CallState -> when {
            signal.active && !callActive -> copy(callActive = true, entries = listOf(callEntry(signal)) + entries)
            signal.active -> this
            else -> copy(callActive = false)
        }
        is ControlSignal.Volume -> if (callActive) this else addVolume(signal)
        is ControlSignal.PlayState -> if (callActive) this else addPlayState(signal)
        is ControlSignal.TrackChange -> if (callActive) this else mergeOrAdd(
            ControlKind.TRACK_CHANGE, signal, SAME_PRESS_MS,
            DetectedControl(
                ControlKind.TRACK_CHANGE, "Track change", detail = if (signal.seeking) "Seeking" else null,
                timestamp = signal.at, origin = signal.origin,
            ),
        )
        is ControlSignal.ButtonEventSeen -> {
            val label = ControlClassifier.buttonLabel(signal.buttonId, signal.eventType, signal.buttonLabel, signal.eventLabel)
            val detail = signal.action.takeIf { it != ButtonAction.UNKNOWN }?.label
            mergeOrAdd(
                ControlKind.HEADPHONE_BUTTON_EVENT, signal, PLAY_PAUSE_MERGE_MS,
                DetectedControl(ControlKind.HEADPHONE_BUTTON_EVENT, label, detail = detail, timestamp = signal.at, origin = signal.origin),
                sameLabel = label,
            )
        }
        is ControlSignal.AssistantGesture -> mergeOrAdd(
            ControlKind.ASSISTANT_GESTURE, signal, ASSISTANT_MERGE_MS,
            DetectedControl(
                ControlKind.ASSISTANT_GESTURE, "Assistant gesture",
                detail = when {
                    signal.origin == SignalOrigin.DEMO -> "Demo"
                    signal.wired -> "Wired headset"
                    else -> null
                },
                timestamp = signal.at, origin = signal.origin, headsetAddress = signal.headsetAddress,
            ),
        )
    }

    private fun callEntry(s: ControlSignal.CallState) =
        DetectedControl(ControlKind.CALL_CONTROLS, "Call controls", timestamp = s.at, origin = s.origin)

    private fun addVolume(s: ControlSignal.Volume): DetectionLog {
        val last = entries.firstOrNull { it.kind == ControlKind.VOLUME }
        if (last != null && s.at - last.lastAt in 0..VOLUME_MERGE_MS) {
            val merged = last.copy(
                lastAt = s.at,
                count = last.count + 1,
                // Levels are on different scales per side: only the side that opened the row moves it.
                volumeTo = if (s.origin == last.origin) s.to else last.volumeTo,
            )
            return moveToFront(last, merged)
        }
        val entry = DetectedControl(
            ControlKind.VOLUME, "Volume", timestamp = s.at, origin = s.origin, volumeFrom = s.from ?: s.to, volumeTo = s.to,
        )
        return copy(entries = listOf(entry) + entries)
    }

    private fun addPlayState(s: ControlSignal.PlayState): DetectionLog {
        val last = entries.firstOrNull { it.kind == ControlKind.PLAY_PAUSE }
        if (last != null) {
            val gap = s.at - last.lastAt
            val sameSide = s.origin == last.lastOrigin
            if (gap >= 0 && ((sameSide && gap <= PLAY_PAUSE_MERGE_MS) || (!sameSide && gap <= SAME_PRESS_MS))) {
                // The other side confirming the same press is not a second press.
                val merged = last.copy(
                    lastAt = s.at, lastOrigin = s.origin, count = if (sameSide) last.count + 1 else last.count, playing = s.playing,
                )
                return moveToFront(last, merged)
            }
        }
        val entry = DetectedControl(
            ControlKind.PLAY_PAUSE, "Play / pause", detail = if (s.playing) "Playing" else "Paused",
            timestamp = s.at, origin = s.origin, playing = s.playing,
        )
        return copy(entries = listOf(entry) + entries)
    }

    private fun mergeOrAdd(kind: ControlKind, s: ControlSignal, windowMs: Long, fresh: DetectedControl, sameLabel: String? = null): DetectionLog {
        val last = entries.firstOrNull { it.kind == kind && (sameLabel == null || it.label == sameLabel) }
        if (last != null && s.at - last.lastAt in 0..windowMs) return moveToFront(last, last.copy(lastAt = s.at, count = last.count + 1))
        return copy(entries = listOf(fresh) + entries)
    }

    private fun moveToFront(old: DetectedControl, new: DetectedControl): DetectionLog {
        val i = entries.indexOf(old)
        val rest = entries.toMutableList().apply { removeAt(i) }
        return copy(entries = listOf(new) + rest)
    }

    companion object {
        const val VOLUME_MERGE_MS = 1_500L
        const val PLAY_PAUSE_MERGE_MS = 500L
        /** The headphones and the phone reporting one press: the player can take a second to start. */
        const val SAME_PRESS_MS = 1_500L
        const val ASSISTANT_MERGE_MS = 1_000L
    }
}

/** Turns `HeadphoneState` changes into signals, and names Bose buttons. Pure. */
object ControlClassifier {

    /**
     * The signals between two states of the same connection. Nothing across a connect, a
     * disconnect or another headset (the first read fills everything at once), and nothing for
     * values the headphones never reported (null before or after).
     */
    fun fromHeadphoneState(before: HeadphoneState?, after: HeadphoneState, now: Long): List<ControlSignal> {
        if (before == null || !before.isConnected || !after.isConnected) return emptyList()
        if (!before.connection.deviceAddress.equals(after.connection.deviceAddress, ignoreCase = true)) return emptyList()
        val out = ArrayList<ControlSignal>(2)

        val was = before.playback?.state
        val now0 = after.playback?.state
        if (was != null && now0 != null && was != now0) {
            val seeking = now0 == PlaybackState.FAST_FORWARDING || now0 == PlaybackState.REWINDING
            when {
                // A held next / previous seeks: that is the track control, not play/pause.
                seeking -> out += ControlSignal.TrackChange(seeking = true, origin = SignalOrigin.HEADPHONES, at = now)
                isPlaying(was) != isPlaying(now0) -> out += ControlSignal.PlayState(isPlaying(now0), SignalOrigin.HEADPHONES, now)
            }
        } else if (was != null && now0 != null && isPlaying(now0) && trackRestarted(before, after)) {
            out += ControlSignal.TrackChange(seeking = false, origin = SignalOrigin.HEADPHONES, at = now)
        }

        val v0 = before.volume
        val v1 = after.volume
        if (v0 != null && v1 != null && v0 != v1) out += ControlSignal.Volume(v0, v1, SignalOrigin.HEADPHONES, now)

        val press = after.lastButtonPress
        if (press != null && press != before.lastButtonPress) {
            out += ControlSignal.ButtonEventSeen(
                press.buttonId, press.eventType, press.action, now, buttonLabel = press.buttonLabel, eventLabel = press.eventLabel,
            )
        }
        return out
    }

    /**
     * `[5.4]` usually reports the position as unknown (`ffff`); when it does report one, a jump
     * back to the start while playing is a new track. (`[5.6]` now-playing isn't parsed yet.)
     */
    private fun trackRestarted(before: HeadphoneState, after: HeadphoneState): Boolean {
        val p0 = before.playback?.trackPosition ?: return false
        val p1 = after.playback?.trackPosition ?: return false
        return p0 >= TRACK_RESTART_FROM && p1 <= TRACK_RESTART_TO
    }

    fun isPlaying(state: PlaybackState): Boolean =
        state == PlaybackState.PLAYING || state == PlaybackState.FAST_FORWARDING || state == PlaybackState.REWINDING

    /**
     * "Shortcut button · Long press": the names the driver gave the push ([buttonName], [pressName];
     * the Bose driver fills them from its `[1.9]` / `[2.12]` tables), else the id in hex and the
     * event number.
     */
    fun buttonLabel(buttonId: Int, eventType: Int, buttonName: String? = null, pressName: String? = null): String =
        "${buttonName ?: "Button 0x%02x".format(buttonId)} · ${pressName ?: "event $eventType"}"

    private const val TRACK_RESTART_FROM = 5
    private const val TRACK_RESTART_TO = 1
}
