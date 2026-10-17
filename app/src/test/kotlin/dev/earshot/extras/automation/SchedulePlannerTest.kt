package dev.earshot.extras.automation

import dev.earshot.core.Trigger
import java.time.DayOfWeek
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SchedulePlannerTest {
    // Thursday 15 Oct 2026, 12:00
    private val thursdayNoon = LocalDateTime.of(2026, 10, 15, 12, 0).also { check(it.dayOfWeek == DayOfWeek.THURSDAY) }

    @Test
    fun `later today when the time has not passed`() {
        val s = Trigger.Schedule(18, 30, setOf(4)) // Thursday
        assertEquals(LocalDateTime.of(2026, 10, 15, 18, 30), SchedulePlanner.next(s, thursdayNoon))
    }

    @Test
    fun `next week when today's time has already passed`() {
        val s = Trigger.Schedule(8, 0, setOf(4))
        assertEquals(LocalDateTime.of(2026, 10, 22, 8, 0), SchedulePlanner.next(s, thursdayNoon))
    }

    @Test
    fun `exact same minute counts as passed so a late alarm does not re-fire`() {
        val s = Trigger.Schedule(12, 0, setOf(4))
        assertEquals(LocalDateTime.of(2026, 10, 22, 12, 0), SchedulePlanner.next(s, thursdayNoon))
    }

    @Test
    fun `picks the earliest of several days`() {
        val s = Trigger.Schedule(7, 15, setOf(1, 6, 7)) // Mon, Sat, Sun -> Saturday 17th
        assertEquals(LocalDateTime.of(2026, 10, 17, 7, 15), SchedulePlanner.next(s, thursdayNoon))
    }

    @Test
    fun `weekday schedule from a Friday evening lands on Monday`() {
        val fridayEvening = LocalDateTime.of(2026, 10, 16, 22, 0)
        val s = Trigger.Schedule(9, 0, setOf(1, 2, 3, 4, 5))
        assertEquals(LocalDateTime.of(2026, 10, 19, 9, 0), SchedulePlanner.next(s, fridayEvening))
    }

    @Test
    fun `no days or bad time gives null`() {
        assertNull(SchedulePlanner.next(Trigger.Schedule(9, 0, emptySet()), thursdayNoon))
        assertNull(SchedulePlanner.next(Trigger.Schedule(25, 0, setOf(1)), thursdayNoon))
        assertNull(SchedulePlanner.next(Trigger.Schedule(9, 0, setOf(0, 8)), thursdayNoon))
    }
}
