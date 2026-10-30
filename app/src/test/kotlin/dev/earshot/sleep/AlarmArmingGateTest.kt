package dev.earshot.sleep

import dev.earshot.core.SleepAlarm
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Audit X02/X04: wake alarms are armed only when they can ring on time, the same rule saveAlarm
 * keeps ([SleepScheduler.mayEnable]). Restored alarms on a new phone without exact-alarm access
 * wait unarmed instead of being armed inexactly.
 */
class AlarmArmingGateTest {
    private val zone = ZoneId.of("Europe/London")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    // 2026-10-27, a Tuesday, after the clocks went back (no DST change in these tests).
    private val now = at(2026, 10, 27, 22, 0)

    @Test
    fun `with exact alarms every enabled alarm is armed, with its wake-up window`() {
        val rings = SleepPlanner.ringsToArm(
            listOf(SleepAlarm("a", 7, 0, wakeWindowMinutes = 20), SleepAlarm("off", 6, 0, enabled = false), SleepAlarm("b", 6, 30)),
            now, emptyMap(), exactAllowed = true, zone = zone,
        )
        assertEquals(listOf("a", "b"), rings.map { it.alarm.id })
        val a = rings.first { it.alarm.id == "a" }
        assertEquals(at(2026, 10, 28, 7, 0), a.atMillis)
        assertEquals(at(2026, 10, 28, 6, 40), a.windowStartMillis)
        assertNull(rings.first { it.alarm.id == "b" }.windowStartMillis)
    }

    @Test
    fun `without exact alarms nothing is armed, not even an alarm that is switched on`() {
        // A backup restored onto a new phone: Android 14+ denies exact alarms to the fresh install,
        // and the restored alarms come back switched on.
        val restored = listOf(SleepAlarm("a", 7, 0, wakeWindowMinutes = 20), SleepAlarm("b", 6, 30, days = setOf(1, 2, 3, 4, 5)))
        assertTrue(SleepPlanner.ringsToArm(restored, now, emptyMap(), exactAllowed = false, zone = zone).isEmpty())
    }

    @Test
    fun `an alarm is armed only when saveAlarm would let it be switched on`() {
        val alarms = listOf(SleepAlarm("on", 7, 0, wakeWindowMinutes = 10), SleepAlarm("off", 7, 0, enabled = false))
        for (exact in listOf(true, false)) {
            for (ring in SleepPlanner.ringsToArm(alarms, now, emptyMap(), exact, zone)) {
                assertTrue(SleepScheduler.mayEnable(ring.alarm, exact), "${ring.alarm.id} armed with exactAllowed=$exact")
            }
        }
    }

    @Test
    fun `the soonest planned ring is the one the next-alarm line shows`() {
        val alarms = listOf(SleepAlarm("late", 9, 0), SleepAlarm("early", 6, 30), SleepAlarm("off", 5, 0, enabled = false))
        val soonest = SleepPlanner.ringsToArm(alarms, now, emptyMap(), exactAllowed = true, zone = zone).minByOrNull { it.atMillis }
        assertEquals(SleepPlanner.nextAlarm(alarms, now, zone)?.first?.id, soonest?.alarm?.id)
        assertNull(SleepPlanner.ringsToArm(alarms, now, emptyMap(), exactAllowed = false, zone = zone).minByOrNull { it.atMillis })
    }

    @Test
    fun `a skipped ring moves on, and a wake-up window that already began is not armed`() {
        val daily = SleepAlarm("a", 7, 0, days = (1..7).toSet(), wakeWindowMinutes = 30)
        // Stopped during tomorrow's wake-up window: that morning's ring is skipped.
        val skipped = SleepPlanner.ringsToArm(listOf(daily), now, mapOf("a" to at(2026, 10, 28, 7, 1)), exactAllowed = true, zone = zone).single()
        assertEquals(at(2026, 10, 29, 7, 0), skipped.atMillis)
        val late = SleepPlanner.ringsToArm(listOf(daily), at(2026, 10, 28, 6, 45), emptyMap(), exactAllowed = true, zone = zone).single()
        assertEquals(at(2026, 10, 28, 7, 0), late.atMillis)
        assertNull(late.windowStartMillis)
    }
}
