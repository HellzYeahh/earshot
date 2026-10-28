package dev.earshot.sleep

import dev.earshot.core.FormFactor
import dev.earshot.core.SleepAlarm
import dev.earshot.core.SleepPhase
import dev.earshot.core.SleepPlan
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** The moments of one night, in wall-clock milliseconds. */
data class SleepTimeline(
    val startedAtMillis: Long,
    val endsAtMillis: Long,
    /** Sounds start fading here; equal to [endsAtMillis] when there is nothing to fade. */
    val fadeOutStartsAtMillis: Long,
    /** Noise cancelling goes off here; null = leave it. */
    val noiseOffAtMillis: Long?,
) {
    val durationMillis: Long get() = endsAtMillis - startedAtMillis
}

/**
 * The Sleep tab's rules as pure functions (docs/sleep-mode.md §4.2): the night's timeline, the
 * sound envelope, alarm times and the battery guess. Everything time-based takes `now` so the
 * tests can pin it.
 */
object SleepPlanner {
    const val MINUTE = 60_000L

    /** Sounds fade in over this long at the start. */
    const val FADE_IN_MS = 30_000L

    /** [SleepPlan] clamped to what the tab allows (a stored plan from a newer build may be out of range). */
    fun sanitise(plan: SleepPlan): SleepPlan = plan.copy(
        durationMinutes = plan.durationMinutes.coerceIn(SleepPlan.MIN_MINUTES, SleepPlan.MAX_MINUTES),
        layers = plan.layers.take(SleepPlan.MAX_LAYERS).map { it.copy(gain = it.gain.coerceIn(0f, 1f)) },
        fadeOutMinutes = plan.fadeOutMinutes.coerceIn(0, 30),
        volume = plan.volume.coerceIn(0f, 1f),
        volumeCapPercent = plan.volumeCapPercent.coerceIn(5, 100),
        noiseOffAfterMinutes = plan.noiseOffAfterMinutes?.coerceAtLeast(0),
    )

    fun timeline(plan: SleepPlan, startedAtMillis: Long): SleepTimeline {
        val p = sanitise(plan)
        val ends = startedAtMillis + p.durationMinutes * MINUTE
        // A fade never takes more than half the night: a 15-minute timer with a 10-minute fade
        // would otherwise spend most of its time getting quieter.
        val fade = minOf(p.fadeOutMinutes * MINUTE, (ends - startedAtMillis) / 2)
        val noiseOff = p.noiseOffAfterMinutes?.let { minutes -> minOf(startedAtMillis + minutes * MINUTE, ends) }
        return SleepTimeline(startedAtMillis, ends, ends - fade, noiseOff)
    }

    /** The timeline after "+N min": the end and the fade move, noise-off stays where it was unless it was "at the end". */
    fun extended(timeline: SleepTimeline, plan: SleepPlan, minutes: Int): SleepTimeline {
        val p = sanitise(plan)
        val ends = timeline.endsAtMillis + minutes * MINUTE
        val fade = minOf(p.fadeOutMinutes * MINUTE, (ends - timeline.startedAtMillis) / 2)
        val noiseAtEnd = timeline.noiseOffAtMillis == timeline.endsAtMillis
        return timeline.copy(
            endsAtMillis = ends,
            fadeOutStartsAtMillis = ends - fade,
            noiseOffAtMillis = if (noiseAtEnd) ends else timeline.noiseOffAtMillis,
        )
    }

    fun phase(timeline: SleepTimeline, hasSounds: Boolean, nowMillis: Long): SleepPhase = when {
        !hasSounds -> SleepPhase.TIMER_ONLY
        nowMillis < timeline.startedAtMillis + FADE_IN_MS -> SleepPhase.FADING_IN
        nowMillis >= timeline.fadeOutStartsAtMillis -> SleepPhase.FADING_OUT
        else -> SleepPhase.PLAYING
    }

    /** 0..1: fade in over [FADE_IN_MS], hold, then the long fade out to silence at the end. */
    fun envelope(timeline: SleepTimeline, nowMillis: Long): Float {
        if (nowMillis >= timeline.endsAtMillis) return 0f
        val fadeIn = Fades.fadeIn(nowMillis - timeline.startedAtMillis, FADE_IN_MS)
        val fadeLength = timeline.endsAtMillis - timeline.fadeOutStartsAtMillis
        val fadeOut = if (nowMillis < timeline.fadeOutStartsAtMillis || fadeLength <= 0) 1f
        else Fades.fadeOut(nowMillis - timeline.fadeOutStartsAtMillis, fadeLength)
        return fadeIn * fadeOut
    }

    /**
     * The next time [alarm] rings at or after [nowMillis] (strictly after: an alarm set for this
     * very minute rings tomorrow), or null when it is disabled. [days] empty = the next occurrence.
     */
    fun nextRing(alarm: SleepAlarm, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (!alarm.enabled) return null
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        val time = LocalTime.of(alarm.hour.coerceIn(0, 23), alarm.minute.coerceIn(0, 59))
        val days = alarm.days.filter { it in 1..7 }.map { DayOfWeek.of(it) }.toSet()
        var date: LocalDate = now.toLocalDate()
        repeat(8) {
            // atZone resolves a time inside a DST gap to the next valid instant, never to an earlier day.
            val candidate = date.atTime(time).atZone(zone)
            if (candidate.isAfter(now) && (days.isEmpty() || candidate.dayOfWeek in days)) return candidate.toInstant().toEpochMilli()
            date = date.plusDays(1)
        }
        return null
    }

    /** The soonest enabled alarm and when it rings. */
    fun nextAlarm(alarms: List<SleepAlarm>, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<SleepAlarm, Long>? =
        alarms.mapNotNull { a -> nextRing(a, nowMillis, zone)?.let { a to it } }.minByOrNull { it.second }

    /**
     * Listening minutes left: the headset's own figure when it gives one, else a rough guess from
     * the percentage (earbuds ~8 h, headphones ~24 h at 100 %, playing quiet sounds). Null when
     * nothing is known. The second value says whether it is a guess.
     */
    fun minutesLeft(percent: Int?, headsetMinutes: Int?, formFactor: FormFactor): Pair<Int?, Boolean> {
        if (headsetMinutes != null && headsetMinutes > 0) return headsetMinutes to false
        val p = percent?.takeIf { it in 0..100 } ?: return null to true
        val fullHours = when (formFactor) {
            FormFactor.HEADPHONES -> 24
            FormFactor.EARBUDS, FormFactor.OPEN_EARBUDS -> 8
        }
        return (fullHours * 60 * p / 100) to true
    }
}

/** Equal-power fade in and a slow-starting fade out, matching the sound engine's `FadeCurve`. */
internal object Fades {
    fun fadeIn(elapsedMs: Long, durationMs: Long): Float {
        if (durationMs <= 0) return 1f
        val x = elapsedMs.coerceIn(0, durationMs).toDouble() / durationMs
        return kotlin.math.sin(x * Math.PI / 2).toFloat()
    }

    fun fadeOut(elapsedMs: Long, durationMs: Long): Float {
        if (durationMs <= 0) return 0f
        val x = elapsedMs.coerceIn(0, durationMs).toDouble() / durationMs
        val c = kotlin.math.cos(x * Math.PI / 2)
        return (c * c).toFloat()
    }
}
