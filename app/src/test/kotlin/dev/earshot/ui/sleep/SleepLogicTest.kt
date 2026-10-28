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
import dev.earshot.core.fake.FakeSleepRepository
import dev.earshot.dsp.sleep.AlarmTone
import dev.earshot.dsp.sleep.SleepSound
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The Sleep tab's pure decisions (SleepLogic): battery check, mix, alarm summary, days, clocks. */
class SleepLogicTest {

    private val minute = 60_000L
    private val now = 1_000_000_000_000L
    private val headset = FakeSleepRepository.SAMPLE_HEADSET
    private val weekdayAlarm = SleepAlarm(id = "a1", hour = 7, minute = 0, days = setOf(1, 2, 3, 4, 5))

    // ---- Battery check

    @Test fun `battery check warns when the timer outlasts the battery and suggests a timer that fits`() {
        val check = SleepLogic.batteryCheck(headset.copy(minutesLeft = 150), SleepPlan(durationMinutes = 180), null, emptyList(), now)
        assertEquals(SleepLogic.BatteryCheck.TimerOutlastsBattery(150, 180, 120), check)
    }

    @Test fun `battery check warns about an earbud alarm the battery won't reach`() {
        val next = NextAlarm("a1", now + 8 * 60 * minute)
        val check = SleepLogic.batteryCheck(headset.copy(minutesLeft = 7 * 60), SleepPlan(durationMinutes = 60), next, listOf(weekdayAlarm), now)
        assertEquals(SleepLogic.BatteryCheck.AlarmOutlastsBattery(7 * 60, 8 * 60, weekdayAlarm), check)
    }

    @Test fun `battery check is quiet when the alarm rings on the phone, or the battery lasts`() {
        val next = NextAlarm("a1", now + 8 * 60 * minute)
        val phone = weekdayAlarm.copy(output = AlarmOutput.PHONE)
        assertEquals(SleepLogic.BatteryCheck.Fine, SleepLogic.batteryCheck(headset.copy(minutesLeft = 7 * 60), SleepPlan(durationMinutes = 60), next, listOf(phone), now))
        assertEquals(SleepLogic.BatteryCheck.Fine, SleepLogic.batteryCheck(headset.copy(minutesLeft = 9 * 60), SleepPlan(durationMinutes = 60), next, listOf(weekdayAlarm), now))
        // BOTH still needs the earbuds to last.
        assertIs<SleepLogic.BatteryCheck.AlarmOutlastsBattery>(
            SleepLogic.batteryCheck(headset.copy(minutesLeft = 7 * 60), SleepPlan(durationMinutes = 60), next, listOf(weekdayAlarm.copy(output = AlarmOutput.BOTH)), now),
        )
    }

    @Test fun `battery check says nothing without an estimate or an audio link`() {
        val plan = SleepPlan(durationMinutes = 360)
        assertEquals(SleepLogic.BatteryCheck.Fine, SleepLogic.batteryCheck(headset.copy(minutesLeft = null), plan, null, emptyList(), now))
        assertEquals(SleepLogic.BatteryCheck.Fine, SleepLogic.batteryCheck(headset.copy(minutesLeft = 30, audioConnected = false), plan, null, emptyList(), now))
        assertEquals(SleepLogic.BatteryCheck.Fine, SleepLogic.batteryCheck(SleepHeadset(), plan, null, emptyList(), now))
    }

    @Test fun `suggested timer is the longest chip that fits, then quarter hours, else none`() {
        assertEquals(120, SleepLogic.suggestedTimer(150))
        assertEquals(360, SleepLogic.suggestedTimer(400))
        assertEquals(15, SleepLogic.suggestedTimer(29))
        assertEquals(null, SleepLogic.suggestedTimer(10))
    }

    @Test fun `battery estimates are rounded down so they never promise more than there is`() {
        assertEquals(420, SleepLogic.roughMinutes(7 * 60 + 10))
        assertEquals(420, SleepLogic.roughMinutes(7 * 60 + 40))
        assertEquals(90, SleepLogic.roughMinutes(95))
        assertEquals(40, SleepLogic.roughMinutes(44))
        assertEquals(5, SleepLogic.roughMinutes(1))
        // A warning can't read "about 3 h left, so the 3 h timer will outlast it".
        for (left in 15 until 360) assertTrue(SleepLogic.roughMinutes(left) <= left, "$left")
    }

    @Test fun `power off at the end moves an earbud alarm to the phone`() {
        val next = NextAlarm("a1", now + 8 * 60 * minute)
        val off = SleepPlan(endAction = SleepEndAction.POWER_OFF)
        assertTrue(SleepLogic.powerOffMovesAlarmToPhone(off, next, listOf(weekdayAlarm)))
        assertFalse(SleepLogic.powerOffMovesAlarmToPhone(off, next, listOf(weekdayAlarm.copy(output = AlarmOutput.PHONE))))
        assertFalse(SleepLogic.powerOffMovesAlarmToPhone(SleepPlan(endAction = SleepEndAction.DISCONNECT), next, listOf(weekdayAlarm)))
        assertFalse(SleepLogic.powerOffMovesAlarmToPhone(off, null, listOf(weekdayAlarm)))
    }

    @Test fun `abilities come from the headset, except the phone's own steps`() {
        val generic = FakeSleepRepository.GENERIC_HEADSET
        assertTrue(SleepLogic.abilityFor(SleepEndAction.STOP_SOUNDS, generic).available)
        assertEquals(generic.powerOff, SleepLogic.abilityFor(SleepEndAction.POWER_OFF, generic))
        assertEquals(generic.disconnect, SleepLogic.abilityFor(SleepEndAction.DISCONNECT, generic))
        assertEquals(generic.touchLock, SleepLogic.abilityFor(NightExtra.TOUCH_LOCK, generic))
        assertEquals(SleepAbility(true), SleepLogic.abilityFor(NightExtra.DO_NOT_DISTURB, generic))
        assertEquals(SleepAbility(true), SleepLogic.abilityFor(NightExtra.VOLUME_CAP, generic))
    }

    // ---- Timer and noise cancelling

    @Test fun `durations split into hours and minutes, custom ones snap to quarter hours inside the limits`() {
        assertEquals(SleepLogic.HoursMinutes(1, 45), SleepLogic.splitMinutes(105))
        assertEquals(SleepLogic.HoursMinutes(0, 30), SleepLogic.splitMinutes(30))
        assertTrue(SleepLogic.isCustomDuration(105))
        assertFalse(SleepLogic.isCustomDuration(60))
        assertEquals(105, SleepLogic.snapDuration(101f))
        assertEquals(SleepPlan.MIN_MINUTES, SleepLogic.snapDuration(2f))
        assertEquals(SleepPlan.MAX_MINUTES, SleepLogic.snapDuration(999f))
        // 15, 30 … 360: 24 stops, 22 between the ends.
        assertEquals(22, SleepLogic.customSliderSteps)
    }

    @Test fun `noise off planned after the timer happens at its end`() {
        assertTrue(SleepLogic.noiseOffAtTimerEnd(SleepPlan(durationMinutes = 30, noiseOffAfterMinutes = 60)))
        assertTrue(SleepLogic.noiseOffAtTimerEnd(SleepPlan(durationMinutes = 30, noiseOffAfterMinutes = SleepPlan.MAX_MINUTES)))
        assertFalse(SleepLogic.noiseOffAtTimerEnd(SleepPlan(durationMinutes = 120, noiseOffAfterMinutes = 60)))
        assertFalse(SleepLogic.noiseOffAtTimerEnd(SleepPlan(durationMinutes = 120, noiseOffAfterMinutes = 0)))
        assertFalse(SleepLogic.noiseOffAtTimerEnd(SleepPlan(durationMinutes = 120, noiseOffAfterMinutes = null)))
    }

    // ---- Sound mix

    @Test fun `a tile tap adds or removes a sound, and a fourth is refused`() {
        val one = SleepLogic.toggleLayer(emptyList(), "rain")
        assertEquals(SleepLogic.LayerChange.Changed(listOf(SleepLayer("rain", SleepLogic.DEFAULT_LAYER_GAIN))), one)
        val three = listOf(SleepLayer("rain"), SleepLayer("fire"), SleepLayer("fan"))
        assertEquals(SleepLogic.LayerChange.LimitReached, SleepLogic.toggleLayer(three, "ocean"))
        // Removing still works on a full mix.
        assertEquals(SleepLogic.LayerChange.Changed(listOf(SleepLayer("rain"), SleepLayer("fan"))), SleepLogic.toggleLayer(three, "fire"))
        assertEquals(SleepPlan.MAX_LAYERS, three.size)
    }

    @Test fun `layer gains change one sound and stay in range`() {
        val layers = listOf(SleepLayer("rain", 0.7f), SleepLayer("fire", 0.4f))
        assertEquals(listOf(SleepLayer("rain", 1f), SleepLayer("fire", 0.4f)), SleepLogic.setGain(layers, "rain", 1.4f))
        assertEquals(listOf(SleepLayer("rain", 0.7f), SleepLayer("fire", 0f)), SleepLogic.setGain(layers, "fire", -1f))
    }

    @Test fun `a saved mix matches by its sounds, not their levels`() {
        assertTrue(SleepLogic.sameSounds(listOf(SleepLayer("rain", 0.2f), SleepLayer("fire")), listOf(SleepLayer("fire", 0.9f), SleepLayer("rain"))))
        assertFalse(SleepLogic.sameSounds(listOf(SleepLayer("rain")), listOf(SleepLayer("rain"), SleepLayer("fire"))))
        assertFalse(SleepLogic.sameSounds(emptyList(), emptyList()))
    }

    @Test fun `the alarm sound list has the tones, the phone's sound and every sleep sound`() {
        val choices = SleepLogic.alarmToneChoices()
        assertEquals(AlarmTone.entries.map { it.storedId }, choices.take(AlarmTone.entries.size))
        assertTrue(SleepAlarm.TONE_SYSTEM in choices)
        assertTrue(SleepSound.entries.all { SleepAlarm.TONE_SOUND_PREFIX + it.storedId in choices })
        assertEquals(choices.size, choices.toSet().size)
    }

    // ---- Alarms

    @Test fun `a new alarm rings once at 7, in the earbuds, with the chime`() {
        val alarm = SleepLogic.newAlarm("x")
        assertEquals(7 to 0, alarm.hour to alarm.minute)
        assertTrue(alarm.days.isEmpty())
        assertEquals(AlarmOutput.EARBUDS, alarm.output)
        assertEquals(AlarmTone.CHIME.storedId, alarm.toneId)
        assertEquals(30, alarm.fadeInSeconds)
        assertEquals(10, alarm.snoozeMinutes)
    }

    @Test fun `repeat days are named by their pattern`() {
        assertEquals(SleepLogic.RepeatKind.ONCE, SleepLogic.repeatKind(emptySet()))
        assertEquals(SleepLogic.RepeatKind.WEEKDAYS, SleepLogic.repeatKind(setOf(1, 2, 3, 4, 5)))
        assertEquals(SleepLogic.RepeatKind.WEEKENDS, SleepLogic.repeatKind(setOf(6, 7)))
        assertEquals(SleepLogic.RepeatKind.EVERY_DAY, SleepLogic.repeatKind((1..7).toSet()))
        assertEquals(SleepLogic.RepeatKind.CUSTOM, SleepLogic.repeatKind(setOf(1, 3, 5)))
    }

    @Test fun `day chips follow the locale's week and toggle one day`() {
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), SleepLogic.weekOrder(DayOfWeek.MONDAY))
        assertEquals(listOf(7, 1, 2, 3, 4, 5, 6), SleepLogic.weekOrder(DayOfWeek.SUNDAY))
        assertEquals(listOf(6, 7, 1, 2, 3, 4, 5), SleepLogic.weekOrder(DayOfWeek.SATURDAY))
        assertEquals(setOf(1, 3), SleepLogic.toggleDay(setOf(1), 3))
        assertEquals(setOf(1), SleepLogic.toggleDay(setOf(1, 3), 3))
    }

    @Test fun `the alarm summary carries the phone fallback only for earbuds`() {
        val weekdays = SleepLogic.alarmSummary(weekdayAlarm.copy(wakeWindowMinutes = 20), DayOfWeek.MONDAY)
        assertEquals(SleepLogic.RepeatKind.WEEKDAYS, weekdays.repeat)
        assertEquals(SleepAlarm.PHONE_FALLBACK_SECONDS, weekdays.phoneAfterSeconds)
        assertEquals(20, weekdays.wakeWindowMinutes)

        val custom = SleepLogic.alarmSummary(weekdayAlarm.copy(days = setOf(7, 3, 1), output = AlarmOutput.BOTH), DayOfWeek.SUNDAY)
        assertEquals(listOf(7, 1, 3), custom.days)
        assertEquals(null, custom.phoneAfterSeconds)
        assertEquals(null, custom.wakeWindowMinutes)
        assertEquals(null, SleepLogic.alarmSummary(weekdayAlarm.copy(output = AlarmOutput.PHONE), DayOfWeek.MONDAY).phoneAfterSeconds)
    }

    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0, nanos: Int = 0) =
        ZonedDateTime.of(LocalDateTime.of(year, month, day, hour, minute, second, nanos), ZoneOffset.UTC)

    @Test fun `the next ring is today if still ahead, else the next allowed day`() {
        // 2026-10-26 is a Monday.
        assertEquals(8 * 60 + 30, SleepLogic.minutesUntilAlarm(7, 0, emptySet(), utc(2026, 10, 26, 22, 30)))
        assertEquals(60, SleepLogic.minutesUntilAlarm(7, 0, setOf(1), utc(2026, 10, 26, 6, 0)))
        // Friday evening, weekdays only: Monday 7:00.
        assertEquals((8 + 48) * 60, SleepLogic.minutesUntilAlarm(7, 0, setOf(1, 2, 3, 4, 5), utc(2026, 10, 30, 23, 0)))
        // The same minute rings next week, not now.
        assertEquals(7 * 24 * 60, SleepLogic.minutesUntilAlarm(7, 0, setOf(1), utc(2026, 10, 26, 7, 0)))
        // Rounded up: 30 s, or half a second, away is 1 min, never 0.
        assertEquals(1, SleepLogic.minutesUntilAlarm(7, 0, emptySet(), utc(2026, 10, 26, 6, 59, 30)))
        assertEquals(1, SleepLogic.minutesUntilAlarm(7, 0, emptySet(), utc(2026, 10, 26, 6, 59, 59, 500_000_000)))
    }

    @Test fun `a night with a clock change counts the real time to the alarm`() {
        // Berlin leaves summer time at 03:00 on 2026-10-25: 23:00 to 07:00 lasts 9 hours.
        val berlin = ZoneId.of("Europe/Berlin")
        val evening = ZonedDateTime.of(LocalDateTime.of(2026, 10, 24, 23, 0), berlin)
        assertEquals(9 * 60, SleepLogic.minutesUntilAlarm(7, 0, emptySet(), evening))
        // And joins it at 02:00 on 2026-03-29: 23:00 to 07:00 lasts 7.
        val spring = ZonedDateTime.of(LocalDateTime.of(2026, 3, 28, 23, 0), berlin)
        assertEquals(7 * 60, SleepLogic.minutesUntilAlarm(7, 0, emptySet(), spring))
    }

    // ---- Time formats

    @Test fun `minutes until a time round up and stop at zero`() {
        assertEquals(1, SleepLogic.minutesUntil(now + 1_000, now))
        assertEquals(60, SleepLogic.minutesUntil(now + 60 * minute, now))
        assertEquals(61, SleepLogic.minutesUntil(now + 60 * minute + 1, now))
        assertEquals(0, SleepLogic.minutesUntil(now - minute, now))
    }

    @Test fun `the countdown shows hours only when there are some`() {
        assertEquals("2:41:07", SleepLogic.countdown((2 * 3600 + 41 * 60 + 7) * 1000L, Locale.US))
        assertEquals("41:07", SleepLogic.countdown((41 * 60 + 7) * 1000L, Locale.US))
        assertEquals("0:00", SleepLogic.countdown(-5_000, Locale.US))
        // A part second still counts, so the display never shows 0:00 while time is left.
        assertEquals("0:01", SleepLogic.countdown(200, Locale.US))
    }

    @Test fun `clock follows the phone's 12 or 24 hour choice`() {
        assertEquals("07:05" to null, SleepLogic.clockParts(7, 5, is24Hour = true, Locale.US))
        assertEquals("7:05" to "AM", SleepLogic.clockParts(7, 5, is24Hour = false, Locale.US))
        assertEquals("12:00" to "PM", SleepLogic.clockParts(12, 0, is24Hour = false, Locale.US))
        assertEquals("23:30", SleepLogic.clockText(23, 30, is24Hour = true, Locale.US))
        assertEquals("11:30 PM", SleepLogic.clockText(23, 30, is24Hour = false, Locale.US))
    }

    // ---- "What happens when" lines

    @Test fun `end outcome follows the end action and what the headset can do`() {
        val generic = FakeSleepRepository.GENERIC_HEADSET
        assertEquals(SleepLogic.EndOutcome.STAYS_CONNECTED, SleepLogic.endOutcome(SleepPlan(endAction = SleepEndAction.STOP_SOUNDS), headset))
        assertEquals(SleepLogic.EndOutcome.DISCONNECTS, SleepLogic.endOutcome(SleepPlan(endAction = SleepEndAction.DISCONNECT), headset))
        // The sample (a Sleep A30) can't switch off but can disconnect: that's what will happen.
        assertEquals(SleepLogic.EndOutcome.DISCONNECTS, SleepLogic.endOutcome(SleepPlan(endAction = SleepEndAction.POWER_OFF), headset))
        assertEquals(SleepLogic.EndOutcome.SWITCHES_OFF, SleepLogic.endOutcome(SleepPlan(endAction = SleepEndAction.POWER_OFF), headset.copy(powerOff = SleepAbility(true))))
        assertEquals(SleepLogic.EndOutcome.STAYS_CONNECTED_CANT, SleepLogic.endOutcome(SleepPlan(endAction = SleepEndAction.DISCONNECT), generic))
        assertEquals(SleepLogic.EndOutcome.STAYS_CONNECTED_CANT, SleepLogic.endOutcome(SleepPlan(endAction = SleepEndAction.POWER_OFF), generic))
    }

    @Test fun `noise outcome and time`() {
        assertEquals(SleepLogic.NoiseOutcome.Untouched, SleepLogic.noiseOutcome(SleepPlan(noiseOffAfterMinutes = null)))
        assertEquals(null, SleepLogic.noiseOffAt(SleepPlan(noiseOffAfterMinutes = null), now))
        assertEquals(SleepLogic.NoiseOutcome.AtStart, SleepLogic.noiseOutcome(SleepPlan(noiseOffAfterMinutes = 0)))
        assertEquals(now, SleepLogic.noiseOffAt(SleepPlan(noiseOffAfterMinutes = 0), now))
        val after = SleepPlan(durationMinutes = 120, noiseOffAfterMinutes = 30)
        assertEquals(SleepLogic.NoiseOutcome.After(30), SleepLogic.noiseOutcome(after))
        assertEquals(now + 30 * minute, SleepLogic.noiseOffAt(after, now))
        val atEnd = SleepPlan(durationMinutes = 60, noiseOffAfterMinutes = SleepPlan.MAX_MINUTES)
        assertEquals(SleepLogic.NoiseOutcome.AtEnd(later = false), SleepLogic.noiseOutcome(atEnd))
        assertEquals(now + 60 * minute, SleepLogic.noiseOffAt(atEnd, now))
        // "After 1 h" on a 30 min timer: the timer ends first.
        assertEquals(SleepLogic.NoiseOutcome.AtEnd(later = true), SleepLogic.noiseOutcome(SleepPlan(durationMinutes = 30, noiseOffAfterMinutes = 60)))
    }

    @Test fun `the fade never takes more than half the night`() {
        assertEquals(10, SleepLogic.fadeMinutes(SleepPlan(durationMinutes = 120, fadeOutMinutes = 10)))
        assertEquals(15, SleepLogic.fadeMinutes(SleepPlan(durationMinutes = 30, fadeOutMinutes = 20)))
    }
}
