package dev.earshot.sleep

import dev.earshot.core.AlarmOutput
import dev.earshot.find.FindOutput
import dev.earshot.find.OutputCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlarmRoutingTest {
    private val buds = "00:11:22:33:44:55"
    private val speaker = OutputCandidate(1, AlarmRouting.TYPE_BUILTIN_SPEAKER, "")
    private val budsOut = OutputCandidate(7, FindOutput.TYPE_BLUETOOTH_A2DP, buds)
    private val car = OutputCandidate(9, FindOutput.TYPE_BLUETOOTH_A2DP, "AA:BB:CC:DD:EE:FF")

    @Test
    fun `earbuds ring only in the earbuds when they are there`() {
        val r = AlarmRouting.route(AlarmOutput.EARBUDS, listOf(speaker, budsOut), buds)
        assertEquals(7, r.headsetOutputId)
        assertNull(r.speakerOutputId)
        assertNull(r.fallbackReason)
    }

    @Test
    fun `earbuds that are gone ring the phone instead, with the reason`() {
        val r = AlarmRouting.route(AlarmOutput.EARBUDS, listOf(speaker, car), buds)
        assertNull(r.headsetOutputId)
        assertEquals(1, r.speakerOutputId)
        assertEquals(AlarmFallback.HEADSET_NOT_CONNECTED, r.fallbackReason)
    }

    @Test
    fun `never rings a car or speaker that happens to have the audio`() {
        val r = AlarmRouting.route(AlarmOutput.BOTH, listOf(speaker, car), buds)
        assertNull(r.headsetOutputId)
        assertEquals(1, r.speakerOutputId)
    }

    @Test
    fun `an LE Audio twin counts as the headset`() {
        val twin = OutputCandidate(12, FindOutput.TYPE_BLE_HEADSET, "66:11:22:33:44:55")
        val r = AlarmRouting.route(AlarmOutput.EARBUDS, listOf(speaker, twin), buds, twins = listOf("66:11:22:33:44:55"))
        assertEquals(12, r.headsetOutputId)
    }

    @Test
    fun `phone and both`() {
        assertNull(AlarmRouting.route(AlarmOutput.PHONE, listOf(speaker, budsOut), buds).headsetOutputId)
        val both = AlarmRouting.route(AlarmOutput.BOTH, listOf(speaker, budsOut), buds)
        assertEquals(7, both.headsetOutputId)
        assertEquals(1, both.speakerOutputId)
    }

    @Test
    fun `the phone joins after the fallback time`() {
        val r = AlarmRouting.route(AlarmOutput.EARBUDS, listOf(speaker, budsOut), buds)
        assertFalse(AlarmRouting.phoneShouldJoin(r, elapsedMs = 59_000, fallbackSeconds = 60))
        assertTrue(AlarmRouting.phoneShouldJoin(r, elapsedMs = 60_000, fallbackSeconds = 60))
    }
}
