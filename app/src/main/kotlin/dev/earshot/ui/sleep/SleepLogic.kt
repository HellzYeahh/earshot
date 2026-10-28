package dev.earshot.ui.sleep

import dev.earshot.core.AlarmOutput
import dev.earshot.core.NextAlarm
import dev.earshot.core.NightExtra
import dev.earshot.core.SleepAbility
import dev.earshot.core.SleepAlarm
import dev.earshot.core.SleepEndAction
import dev.earshot.core.SleepHeadset
import dev.earshot.core.SleepLayer
import dev.earshot.core.SleepPlan
import dev.earshot.dsp.sleep.AlarmTone
import dev.earshot.dsp.sleep.SleepSound
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Sleep tab's decisions, free of Compose and Android so they're unit-tested
 * (`SleepLogicTest`). The screens only turn these answers into words.
 */
object SleepLogic {
    /** The "+15 min" button in the night view. */
    const val EXTEND_MINUTES = 15

    /** "Custom" timer steps: fine enough for a nap, coarse enough to drag to 6 h. */
    const val CUSTOM_STEP_MINUTES = 15

    /** A sound added to the mix starts here; each layer has its own slider afterwards. */
    const val DEFAULT_LAYER_GAIN = 0.7f

    // ---- Bedtime battery check -----------------------------------------------------------------

    sealed interface BatteryCheck {
        data object Fine : BatteryCheck

        /** The battery runs out before the timer ends. [suggestedMinutes] is a timer that fits, if any. */
        data class TimerOutlastsBattery(val minutesLeft: Int, val timerMinutes: Int, val suggestedMinutes: Int?) : BatteryCheck

        /** The timer fits, but the earbuds won't last until [alarm], which should ring in them. */
        data class AlarmOutlastsBattery(val minutesLeft: Int, val minutesToAlarm: Int, val alarm: SleepAlarm) : BatteryCheck
    }

    /**
     * Warns before Start when the headset's estimated listening time is shorter than the timer, or
     * than the time until the next alarm when that alarm rings in the earbuds (the safety net would
     * still move it to the phone, but the user should know tonight, not at 7:00). Nothing to say
     * without an estimate or without an audio link: the "no headset" line covers that case.
     */
    fun batteryCheck(
        headset: SleepHeadset,
        plan: SleepPlan,
        nextAlarm: NextAlarm?,
        alarms: List<SleepAlarm>,
        nowMillis: Long,
    ): BatteryCheck {
        val left = headset.minutesLeft ?: return BatteryCheck.Fine
        if (!headset.audioConnected) return BatteryCheck.Fine
        if (left < plan.durationMinutes) {
            return BatteryCheck.TimerOutlastsBattery(left, plan.durationMinutes, suggestedTimer(left))
        }
        val next = nextAlarm ?: return BatteryCheck.Fine
        val alarm = alarms.firstOrNull { it.id == next.alarmId } ?: return BatteryCheck.Fine
        if (alarm.output == AlarmOutput.PHONE) return BatteryCheck.Fine
        val toAlarm = minutesUntil(next.atMillis, nowMillis)
        return if (toAlarm > 0 && left < toAlarm) BatteryCheck.AlarmOutlastsBattery(left, toAlarm, alarm) else BatteryCheck.Fine
    }

    /** The longest timer chip that fits in [minutesLeft], else whole quarter hours, else null. */
    fun suggestedTimer(minutesLeft: Int): Int? =
        SleepPlan.DURATION_CHOICES.filter { it <= minutesLeft }.maxOrNull()
            ?: (minutesLeft / CUSTOM_STEP_MINUTES * CUSTOM_STEP_MINUTES).takeIf { it >= SleepPlan.MIN_MINUTES }

    /**
     * A battery estimate rounded so it doesn't pretend to be precise: whole hours from 2 h, half
     * hours from 1 h, 5 minutes below that. Always rounded down: a battery figure should never
     * promise more than it has, and a warning must never read "about 3 h left, the 3 h timer
     * will outlast it".
     */
    fun roughMinutes(minutes: Int): Int = when {
        minutes >= 120 -> minutes / 60 * 60
        minutes >= 60 -> minutes / 30 * 30
        else -> (minutes / 5 * 5).coerceAtLeast(5)
    }

    /**
     * Switching the headset off at the end means it can't ring tomorrow's alarm, which then rings
     * on the phone; the tab says so before Start (docs/sleep-mode.md §4.6, conflict rule).
     */
    fun powerOffMovesAlarmToPhone(plan: SleepPlan, nextAlarm: NextAlarm?, alarms: List<SleepAlarm>): Boolean {
        if (plan.endAction != SleepEndAction.POWER_OFF) return false
        val alarm = alarms.firstOrNull { it.id == nextAlarm?.alarmId } ?: return false
        return alarm.output != AlarmOutput.PHONE
    }

    /** What the current headset can do for one end action. Stopping the sounds always works. */
    fun abilityFor(action: SleepEndAction, headset: SleepHeadset): SleepAbility = when (action) {
        SleepEndAction.STOP_SOUNDS -> SleepAbility(true)
        SleepEndAction.DISCONNECT -> headset.disconnect
        SleepEndAction.POWER_OFF -> headset.powerOff
    }

    /** Do Not Disturb and the volume cap are the phone's own; the other two need the headset's driver. */
    fun abilityFor(extra: NightExtra, headset: SleepHeadset): SleepAbility = when (extra) {
        NightExtra.TOUCH_LOCK -> headset.touchLock
        NightExtra.PROMPTS_OFF -> headset.promptsOff
        NightExtra.DO_NOT_DISTURB, NightExtra.VOLUME_CAP -> SleepAbility(true)
    }

    // ---- Timer and noise cancelling ------------------------------------------------------------

    data class HoursMinutes(val hours: Int, val minutes: Int)

    fun splitMinutes(total: Int): HoursMinutes = total.coerceAtLeast(0).let { HoursMinutes(it / 60, it % 60) }

    fun isCustomDuration(minutes: Int): Boolean = minutes !in SleepPlan.DURATION_CHOICES

    /** The custom slider's value snapped to [CUSTOM_STEP_MINUTES] inside the plan's limits. */
    fun snapDuration(minutes: Float): Int =
        ((minutes / CUSTOM_STEP_MINUTES).roundToInt() * CUSTOM_STEP_MINUTES).coerceIn(SleepPlan.MIN_MINUTES, SleepPlan.MAX_MINUTES)

    /** Number of intermediate stops on the custom slider (Material's `steps`). */
    val customSliderSteps: Int = (SleepPlan.MAX_MINUTES - SleepPlan.MIN_MINUTES) / CUSTOM_STEP_MINUTES - 1

    /**
     * A planned switch-off at or after the timer's end happens when the timer ends (the plan's
     * rule), so "Off after 60 min" on a 30 min timer gets a hint rather than a silent surprise.
     */
    fun noiseOffAtTimerEnd(plan: SleepPlan): Boolean =
        plan.noiseOffAfterMinutes?.let { it > 0 && it >= plan.durationMinutes } == true

    // ---- "What happens when" lines above the timer and the noise choices -----------------------

    /** What the headset does when the timer ends, as the line above the timer says it. */
    enum class EndOutcome {
        /** Sounds stop; the headset stays connected (chosen). */
        STAYS_CONNECTED,
        DISCONNECTS,
        SWITCHES_OFF,
        /** Disconnect or switch-off was chosen but this headset can't: it stays connected. */
        STAYS_CONNECTED_CANT,
    }

    /**
     * The end action as it will really play out on [headset]: switching off falls back to
     * disconnecting (the runtime's rule), and either falls back to staying connected.
     */
    fun endOutcome(plan: SleepPlan, headset: SleepHeadset): EndOutcome = when (plan.endAction) {
        SleepEndAction.STOP_SOUNDS -> EndOutcome.STAYS_CONNECTED
        SleepEndAction.DISCONNECT -> if (headset.disconnect.available) EndOutcome.DISCONNECTS else EndOutcome.STAYS_CONNECTED_CANT
        SleepEndAction.POWER_OFF -> when {
            headset.powerOff.available -> EndOutcome.SWITCHES_OFF
            headset.disconnect.available -> EndOutcome.DISCONNECTS
            else -> EndOutcome.STAYS_CONNECTED_CANT
        }
    }

    /** Minutes the sounds take to fade out at the end: the plan's fade, at most half the night. */
    fun fadeMinutes(plan: SleepPlan): Int = minOf(plan.fadeOutMinutes, plan.durationMinutes / 2)

    sealed interface NoiseOutcome {
        /** "Keep it as it is": noise cancelling isn't touched. */
        data object Untouched : NoiseOutcome
        data object AtStart : NoiseOutcome
        /** Goes off [minutes] after Start, before the timer ends. */
        data class After(val minutes: Int) : NoiseOutcome
        /** Goes off when the timer ends ([later]: the choice was later than the timer, so it moved). */
        data class AtEnd(val later: Boolean) : NoiseOutcome
    }

    fun noiseOutcome(plan: SleepPlan): NoiseOutcome {
        val after = plan.noiseOffAfterMinutes ?: return NoiseOutcome.Untouched
        return when {
            after <= 0 -> NoiseOutcome.AtStart
            after < plan.durationMinutes -> NoiseOutcome.After(after)
            else -> NoiseOutcome.AtEnd(later = after != SleepPlan.MAX_MINUTES && after > plan.durationMinutes)
        }
    }

    /** When noise cancelling goes off if the night starts at [startMillis]; null when it isn't touched. */
    fun noiseOffAt(plan: SleepPlan, startMillis: Long): Long? = when (val o = noiseOutcome(plan)) {
        NoiseOutcome.Untouched -> null
        NoiseOutcome.AtStart -> startMillis
        is NoiseOutcome.After -> startMillis + o.minutes * 60_000L
        is NoiseOutcome.AtEnd -> startMillis + plan.durationMinutes * 60_000L
    }

    // ---- Sound mix -----------------------------------------------------------------------------

    sealed interface LayerChange {
        data class Changed(val layers: List<SleepLayer>) : LayerChange

        /** Adding would go past [SleepPlan.MAX_LAYERS]; the screen explains instead. */
        data object LimitReached : LayerChange
    }

    /** A tile tap: removes the sound if it's in the mix, else adds it at [gain] if there's room. */
    fun toggleLayer(layers: List<SleepLayer>, soundId: String, gain: Float = DEFAULT_LAYER_GAIN): LayerChange = when {
        layers.any { it.soundId == soundId } -> LayerChange.Changed(layers.filterNot { it.soundId == soundId })
        layers.size >= SleepPlan.MAX_LAYERS -> LayerChange.LimitReached
        else -> LayerChange.Changed(layers + SleepLayer(soundId, gain))
    }

    fun setGain(layers: List<SleepLayer>, soundId: String, gain: Float): List<SleepLayer> =
        layers.map { if (it.soundId == soundId) it.copy(gain = gain.coerceIn(0f, 1f)) else it }

    /** A saved mix is "the one playing" when it has the same sounds, whatever their levels. */
    fun sameSounds(a: List<SleepLayer>, b: List<SleepLayer>): Boolean =
        a.isNotEmpty() && a.map { it.soundId }.toSet() == b.map { it.soundId }.toSet()

    /** Every sound id the alarm editor offers: the tones, the phone's own sound, then the sleep sounds. */
    fun alarmToneChoices(): List<String> =
        AlarmTone.entries.map { it.storedId } + SleepAlarm.TONE_SYSTEM + SleepSound.entries.map { SleepAlarm.TONE_SOUND_PREFIX + it.storedId }

    // ---- Alarms --------------------------------------------------------------------------------

    /** A new alarm: once, at the next 7:00, in the earbuds with the chime (the editor's defaults). */
    fun newAlarm(id: String): SleepAlarm = SleepAlarm(
        id = id,
        hour = 7,
        minute = 0,
        days = emptySet(),
        output = AlarmOutput.EARBUDS,
        toneId = AlarmTone.CHIME.storedId,
        fadeInSeconds = 30,
        snoozeMinutes = 10,
    )

    enum class RepeatKind { ONCE, EVERY_DAY, WEEKDAYS, WEEKENDS, CUSTOM }

    private val WEEKDAYS = setOf(1, 2, 3, 4, 5)
    private val WEEKENDS = setOf(6, 7)

    fun repeatKind(days: Set<Int>): RepeatKind = when (days.filter { it in 1..7 }.toSet()) {
        emptySet<Int>() -> RepeatKind.ONCE
        (1..7).toSet() -> RepeatKind.EVERY_DAY
        WEEKDAYS -> RepeatKind.WEEKDAYS
        WEEKENDS -> RepeatKind.WEEKENDS
        else -> RepeatKind.CUSTOM
    }

    /** The seven `DayOfWeek` values (1 = Monday) starting at the locale's first day of the week. */
    fun weekOrder(firstDayOfWeek: DayOfWeek): List<Int> = (0 until 7).map { firstDayOfWeek.plus(it.toLong()).value }

    fun toggleDay(days: Set<Int>, day: Int): Set<Int> = if (day in days) days - day else days + day

    /** What the alarm list says about one alarm; the screen turns each part into words. */
    data class AlarmSummary(
        val repeat: RepeatKind,
        /** The repeat days in week order, for [RepeatKind.CUSTOM]. */
        val days: List<Int>,
        val output: AlarmOutput,
        /** Seconds before "Earbuds" also rings on the phone; null when the phone rings anyway. */
        val phoneAfterSeconds: Int?,
        /** The gentle wake window, null when off. */
        val wakeWindowMinutes: Int?,
    )

    fun alarmSummary(alarm: SleepAlarm, firstDayOfWeek: DayOfWeek): AlarmSummary {
        val order = weekOrder(firstDayOfWeek)
        return AlarmSummary(
            repeat = repeatKind(alarm.days),
            days = order.filter { it in alarm.days },
            output = alarm.output,
            phoneAfterSeconds = if (alarm.output == AlarmOutput.EARBUDS) SleepAlarm.PHONE_FALLBACK_SECONDS else null,
            wakeWindowMinutes = alarm.wakeWindowMinutes.takeIf { it > 0 },
        )
    }

    /**
     * Minutes from [now] to the alarm's next ring: today if the time is still ahead and today is
     * one of its [days] (any day when it rings once), else the next allowed day. Measured in
     * [now]'s zone, so a night with a clock change counts the hour it gains or loses. Rounded up,
     * so an alarm 30 s (or 0.5 s) away says "1 min", never "0 min".
     */
    fun minutesUntilAlarm(hour: Int, minute: Int, days: Set<Int>, now: ZonedDateTime): Int {
        val time = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        val allowed = days.filter { it in 1..7 }.toSet()
        var date = now.toLocalDate()
        repeat(8) {
            // atZone moves a time that a spring-forward skips to just after the gap.
            val candidate = date.atTime(time).atZone(now.zone)
            if (candidate.isAfter(now) && (allowed.isEmpty() || candidate.dayOfWeek.value in allowed)) {
                val millis = Duration.between(now, candidate).toMillis()
                return ((millis + 59_999) / 60_000).toInt().coerceAtLeast(1)
            }
            date = date.plusDays(1)
        }
        return 0
    }

    // ---- Time formats --------------------------------------------------------------------------

    /** Minutes until [atMillis], rounded up; 0 once it has passed. */
    fun minutesUntil(atMillis: Long, nowMillis: Long): Int {
        val millis = atMillis - nowMillis
        return if (millis <= 0) 0 else ((millis + 59_999) / 60_000).toInt()
    }

    /** The night view's big countdown: "2:41:07", or "41:07" under an hour. Never negative. */
    fun countdown(millisLeft: Long, locale: Locale = Locale.getDefault()): String {
        val total = (millisLeft.coerceAtLeast(0) + 999) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(locale, "%d:%02d:%02d", h, m, s) else String.format(locale, "%d:%02d", m, s)
    }

    /** "07:00" with the phone's 24-hour clock, else "7:00" plus the "AM" part on its own (for big type). */
    fun clockParts(hour: Int, minute: Int, is24Hour: Boolean, locale: Locale = Locale.getDefault()): Pair<String, String?> {
        val time = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        return if (is24Hour) {
            time.format(DateTimeFormatter.ofPattern("HH:mm", locale)) to null
        } else {
            time.format(DateTimeFormatter.ofPattern("h:mm", locale)) to time.format(DateTimeFormatter.ofPattern("a", locale))
        }
    }

    /** One line: "07:00" or "7:00 AM". */
    fun clockText(hour: Int, minute: Int, is24Hour: Boolean, locale: Locale = Locale.getDefault()): String =
        clockParts(hour, minute, is24Hour, locale).let { (time, amPm) -> if (amPm == null) time else "$time $amPm" }
}
