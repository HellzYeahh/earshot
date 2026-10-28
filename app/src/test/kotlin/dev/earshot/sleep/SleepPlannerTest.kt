package dev.earshot.sleep

import dev.earshot.core.FormFactor
import dev.earshot.core.SleepAlarm
import dev.earshot.core.SleepLayer
import dev.earshot.core.SleepPhase
import dev.earshot.core.SleepPlan
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepPlannerTest {
    private val zone = ZoneId.of("Europe/London")
    private val min = SleepPlanner.MINUTE
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `timeline puts the fade before the end and noise-off where asked`() {
        val t = SleepPlanner.timeline(SleepPlan(durationMinutes = 120, fadeOutMinutes = 10, noiseOffAfterMinutes = 30), startedAtMillis = 0)
        assertEquals(120 * min, t.endsAtMillis)
        assertEquals(110 * min, t.fadeOutStartsAtMillis)
        assertEquals(30 * min, t.noiseOffAtMillis)
    }

    @Test
    fun `noise off at the end lands on the end, and never later`() {
        val t = SleepPlanner.timeline(SleepPlan(durationMinutes = 60, noiseOffAfterMinutes = SleepPlan.MAX_MINUTES), 0)
        assertEquals(t.endsAtMillis, t.noiseOffAtMillis)
        assertNull(SleepPlanner.timeline(SleepPlan(noiseOffAfterMinutes = null), 0).noiseOffAtMillis)
    }

    @Test
    fun `a fade never takes more than half of a short night`() {
        val t = SleepPlanner.timeline(SleepPlan(durationMinutes = 15, fadeOutMinutes = 30), 0)
        assertEquals(15 * min, t.endsAtMillis)
        assertTrue(t.fadeOutStartsAtMillis >= t.endsAtMillis / 2)
    }

    @Test
    fun `sanitise clamps duration, layers and volume`() {
        val p = SleepPlanner.sanitise(SleepPlan(durationMinutes = 9999, layers = List(5) { SleepLayer("rain", 3f) }, volume = 2f))
        assertEquals(SleepPlan.MAX_MINUTES, p.durationMinutes)
        assertEquals(SleepPlan.MAX_LAYERS, p.layers.size)
        assertTrue(p.layers.all { it.gain == 1f })
        assertEquals(1f, p.volume)
        assertEquals(SleepPlan.MIN_MINUTES, SleepPlanner.sanitise(SleepPlan(durationMinutes = 1)).durationMinutes)
    }

    @Test
    fun `extending moves the end and the fade, and noise-off only when it was at the end`() {
        val plan = SleepPlan(durationMinutes = 60, fadeOutMinutes = 10, noiseOffAfterMinutes = 30)
        val t = SleepPlanner.extended(SleepPlanner.timeline(plan, 0), plan, 15)
        assertEquals(75 * min, t.endsAtMillis)
        assertEquals(65 * min, t.fadeOutStartsAtMillis)
        assertEquals(30 * min, t.noiseOffAtMillis)
        val atEnd = SleepPlan(durationMinutes = 60, noiseOffAfterMinutes = SleepPlan.MAX_MINUTES)
        assertEquals(75 * min, SleepPlanner.extended(SleepPlanner.timeline(atEnd, 0), atEnd, 15).noiseOffAtMillis)
    }

    @Test
    fun `envelope fades in, holds, fades out to silence`() {
        val t = SleepPlanner.timeline(SleepPlan(durationMinutes = 60, fadeOutMinutes = 10), 0)
        assertEquals(0f, SleepPlanner.envelope(t, 0))
        assertEquals(1f, SleepPlanner.envelope(t, 30 * min), 1e-4f)
        assertEquals(0f, SleepPlanner.envelope(t, 60 * min))
        var last = 1f
        for (m in 50..60) {
            val g = SleepPlanner.envelope(t, m * min)
            assertTrue(g <= last + 1e-6f, "fade-out must not rise at minute $m")
            last = g
        }
    }

    @Test
    fun `phase follows the timeline`() {
        val t = SleepPlanner.timeline(SleepPlan(durationMinutes = 60, fadeOutMinutes = 10), 0)
        assertEquals(SleepPhase.FADING_IN, SleepPlanner.phase(t, hasSounds = true, nowMillis = 1_000))
        assertEquals(SleepPhase.PLAYING, SleepPlanner.phase(t, true, 20 * min))
        assertEquals(SleepPhase.FADING_OUT, SleepPlanner.phase(t, true, 55 * min))
        assertEquals(SleepPhase.TIMER_ONLY, SleepPlanner.phase(t, hasSounds = false, nowMillis = 20 * min))
    }

    @Test
    fun `a one-off alarm rings at the next occurrence, strictly after now`() {
        val alarm = SleepAlarm(id = "a", hour = 7, minute = 0)
        assertEquals(at(2026, 10, 27, 7, 0), SleepPlanner.nextRing(alarm, at(2026, 10, 26, 23, 0), zone))
        assertEquals(at(2026, 10, 27, 7, 0), SleepPlanner.nextRing(alarm, at(2026, 10, 26, 7, 0), zone))
        assertEquals(at(2026, 10, 26, 7, 0), SleepPlanner.nextRing(alarm, at(2026, 10, 26, 6, 59), zone))
        assertNull(SleepPlanner.nextRing(alarm.copy(enabled = false), 0, zone))
    }

    @Test
    fun `a weekday alarm skips the weekend`() {
        // 2026-10-30 is a Friday.
        val weekdays = SleepAlarm(id = "a", hour = 7, minute = 30, days = setOf(1, 2, 3, 4, 5))
        assertEquals(at(2026, 11, 2, 7, 30), SleepPlanner.nextRing(weekdays, at(2026, 10, 30, 8, 0), zone))
    }

    @Test
    fun `an alarm inside the spring-forward gap still rings that morning`() {
        // UK clocks go forward at 01:00 on 2027-03-28; 01:30 doesn't exist that day.
        val alarm = SleepAlarm(id = "a", hour = 1, minute = 30)
        val ring = SleepPlanner.nextRing(alarm, at(2027, 3, 27, 23, 0), zone)!!
        assertTrue(ring > at(2027, 3, 27, 23, 0) && ring <= at(2027, 3, 28, 3, 0))
    }

    @Test
    fun `next alarm picks the soonest enabled one`() {
        val now = at(2026, 10, 26, 22, 0)
        val (alarm, _) = SleepPlanner.nextAlarm(
            listOf(SleepAlarm("late", 9, 0), SleepAlarm("early", 6, 30), SleepAlarm("off", 5, 0, enabled = false)), now, zone,
        )!!
        assertEquals("early", alarm.id)
    }

    @Test
    fun `battery minutes prefer the headset's own figure`() {
        assertEquals(300 to false, SleepPlanner.minutesLeft(50, 300, FormFactor.EARBUDS))
        assertEquals(240 to true, SleepPlanner.minutesLeft(50, null, FormFactor.EARBUDS))
        assertEquals(720 to true, SleepPlanner.minutesLeft(50, null, FormFactor.HEADPHONES))
        assertNull(SleepPlanner.minutesLeft(null, null, FormFactor.EARBUDS).first)
    }
}
