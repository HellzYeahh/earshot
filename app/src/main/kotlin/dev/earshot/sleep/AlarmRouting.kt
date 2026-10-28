package dev.earshot.sleep

import dev.earshot.core.AlarmOutput
import dev.earshot.find.FindOutput
import dev.earshot.find.OutputCandidate

/** Where each alarm track goes: the headset's output id, the phone speaker's id, and why the phone is used. */
data class AlarmRoute(
    val headsetOutputId: Int?,
    val speakerOutputId: Int?,
    /** Set when the phone rings although only the earbuds were chosen. */
    val fallbackReason: AlarmFallback? = null,
)

enum class AlarmFallback {
    /** No audio output for the headset at ring time (off, in its case, out of range, another device has it). */
    HEADSET_NOT_CONNECTED,
    /** Nobody stopped the alarm within [dev.earshot.core.SleepAlarm.PHONE_FALLBACK_SECONDS]. */
    NOT_STOPPED,
    /** Android played the earbud track somewhere else. */
    ROUTED_ELSEWHERE,
    /** The earbud stream couldn't open or its output closed. */
    STREAM_FAILED,
}

/**
 * Picks the outputs for an alarm (docs/sleep-mode.md §4.7). Pure: the candidates come from
 * `AudioManager.getDevices`. The headset is matched strictly, like Find my earbuds ([FindOutput]):
 * its own address or its LE Audio twin, never "the first Bluetooth output", which could be a car.
 * The alarm never depends on the headset alone: EARBUDS without a headset output rings the phone.
 */
object AlarmRouting {
    /** AudioDeviceInfo.TYPE_BUILTIN_SPEAKER. */
    const val TYPE_BUILTIN_SPEAKER = 2

    fun route(
        output: AlarmOutput,
        outputs: List<OutputCandidate>,
        headsetAddress: String?,
        twins: List<String> = emptyList(),
    ): AlarmRoute {
        val headset = headsetAddress?.let { FindOutput.pick(outputs, it, twins) }?.id
        val speaker = outputs.firstOrNull { it.type == TYPE_BUILTIN_SPEAKER }?.id
        return when (output) {
            AlarmOutput.PHONE -> AlarmRoute(headsetOutputId = null, speakerOutputId = speaker)
            AlarmOutput.BOTH -> AlarmRoute(headsetOutputId = headset, speakerOutputId = speaker)
            AlarmOutput.EARBUDS ->
                if (headset != null) AlarmRoute(headsetOutputId = headset, speakerOutputId = null)
                else AlarmRoute(headsetOutputId = null, speakerOutputId = speaker, fallbackReason = AlarmFallback.HEADSET_NOT_CONNECTED)
        }
    }

    /** After [elapsedMs] of ringing in the earbuds only, the phone joins in. */
    fun phoneShouldJoin(route: AlarmRoute, elapsedMs: Long, fallbackSeconds: Int): Boolean =
        route.speakerOutputId == null && elapsedMs >= fallbackSeconds * 1000L
}
