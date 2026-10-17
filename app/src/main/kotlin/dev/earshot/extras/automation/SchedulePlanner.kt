package dev.earshot.extras.automation

import dev.earshot.core.Trigger
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Pure next-occurrence maths for [Trigger.Schedule]; no Android types so it is unit-testable. */
object SchedulePlanner {

    /**
     * The first `hour:minute` on one of [Trigger.Schedule.days] strictly after [now].
     * Returns null when the schedule has no days or an out-of-range time.
     *
     * "Strictly after" matters: when the alarm fires we re-arm from the fire time, and an
     * alarm that is late by a few seconds must not re-arm for the same minute.
     */
    fun next(schedule: Trigger.Schedule, now: LocalDateTime): LocalDateTime? {
        val days = schedule.days.filter { it in 1..7 }.map { DayOfWeek.of(it) }.toSet()
        if (days.isEmpty()) return null
        if (schedule.hour !in 0..23 || schedule.minute !in 0..59) return null
        val time = LocalTime.of(schedule.hour, schedule.minute)
        for (offset in 0..7) {
            val day = now.toLocalDate().plusDays(offset.toLong())
            if (day.dayOfWeek !in days) continue
            val candidate = LocalDateTime.of(day, time)
            if (candidate.isAfter(now)) return candidate
        }
        return null
    }

    fun nextEpochMillis(schedule: Trigger.Schedule, now: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): Long? =
        next(schedule, now)?.atZone(zone)?.toInstant()?.toEpochMilli()
}
