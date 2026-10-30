package dev.earshot.sleep

import dev.earshot.core.SleepAlarm
import dev.earshot.sleep.SleepScheduler.ArmWay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Audit R05: how wake alarms and night steps are armed, and when a wake alarm may be switched on. */
class SleepSchedulerTest {

    @Test
    fun `a wake alarm is an alarm clock, and falls back instead of staying unarmed`() {
        assertEquals(listOf(ArmWay.ALARM_CLOCK, ArmWay.EXACT, ArmWay.INEXACT), SleepScheduler.armWays(showAsAlarmClock = true, exactAllowed = true))
    }

    @Test
    fun `night steps and the wake window are exact, never in the status bar`() {
        val ways = SleepScheduler.armWays(showAsAlarmClock = false, exactAllowed = true)
        assertEquals(listOf(ArmWay.EXACT, ArmWay.INEXACT), ways)
        assertFalse(ArmWay.ALARM_CLOCK in ways)
    }

    @Test
    fun `without exact alarms only the inexact way is tried`() {
        assertEquals(listOf(ArmWay.INEXACT), SleepScheduler.armWays(showAsAlarmClock = true, exactAllowed = false))
        assertEquals(listOf(ArmWay.INEXACT), SleepScheduler.armWays(showAsAlarmClock = false, exactAllowed = false))
    }

    @Test
    fun `every list ends in the inexact way`() {
        for (clock in listOf(true, false)) for (exact in listOf(true, false)) {
            assertEquals(ArmWay.INEXACT, SleepScheduler.armWays(clock, exact).last())
        }
    }

    @Test
    fun `a wake alarm is switched on only when it can ring on time`() {
        val alarm = SleepAlarm(id = "a", hour = 7, minute = 0)
        assertTrue(SleepScheduler.mayEnable(alarm, exactAllowed = true))
        assertFalse(SleepScheduler.mayEnable(alarm, exactAllowed = false))
    }

    @Test
    fun `a switched-off alarm can always be saved`() {
        val off = SleepAlarm(id = "a", hour = 7, minute = 0, enabled = false)
        assertTrue(SleepScheduler.mayEnable(off, exactAllowed = false))
        assertTrue(SleepScheduler.mayEnable(off, exactAllowed = true))
    }
}
