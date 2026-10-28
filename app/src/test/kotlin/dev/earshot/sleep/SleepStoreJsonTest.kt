package dev.earshot.sleep

import dev.earshot.core.AlarmOutput
import dev.earshot.core.ModeMeaning
import dev.earshot.core.ModeRef
import dev.earshot.core.NightExtra
import dev.earshot.core.SleepAlarm
import dev.earshot.core.SleepEndAction
import dev.earshot.core.SleepLayer
import dev.earshot.core.SleepPlan
import kotlin.test.Test
import kotlin.test.assertEquals

/** The "sleep" store's JSON: everything round-trips, and unknown fields from a newer build are ignored. */
class SleepStoreJsonTest {
    private val json = SleepStore.json

    @Test
    fun `plan, alarm and night round-trip`() {
        val plan = SleepPlan(120, listOf(SleepLayer("rain", 0.5f)), 30, 10, SleepEndAction.DISCONNECT, setOf(NightExtra.TOUCH_LOCK), true)
        assertEquals(plan, json.decodeFromString(SleepPlan.serializer(), json.encodeToString(SleepPlan.serializer(), plan)))

        val alarm = SleepAlarm("a", 6, 45, setOf(1, 2), output = AlarmOutput.BOTH, toneId = SleepAlarm.TONE_SYSTEM, wakeWindowMinutes = 20)
        assertEquals(alarm, json.decodeFromString(SleepAlarm.serializer(), json.encodeToString(SleepAlarm.serializer(), alarm)))

        val night = NightRecord(
            active = ActiveNight(plan, 1, 2, 3, 4, headsetAddress = "00:11"),
            restore = RestoreRecord(address = "00:11", mode = ModeRef(ModeMeaning.QUIET, "Quiet", "00:11", 0), vendorPrompts = mapOf("prompt_anc" to 1)),
            snooze = SnoozeRecord("a", 9),
            log = listOf(NoteRecord(5, "Started", false)),
        )
        assertEquals(night, json.decodeFromString(NightRecord.serializer(), json.encodeToString(NightRecord.serializer(), night)))
    }

    @Test
    fun `fields from a newer build are ignored`() {
        val raw = """{"durationMinutes":90,"layers":[{"soundId":"fire","gain":0.4}],"somethingNew":true}"""
        val plan = json.decodeFromString(SleepPlan.serializer(), raw)
        assertEquals(90, plan.durationMinutes)
        assertEquals("fire", plan.layers.single().soundId)
    }
}
