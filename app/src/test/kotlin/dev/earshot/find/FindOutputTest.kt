package dev.earshot.find

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The find tone only ever goes to the shown headset's own output. */
class FindOutputTest {

    private val headset = "68:F2:1F:38:DB:0F"
    private val car = OutputCandidate(id = 3, type = FindOutput.TYPE_BLUETOOTH_A2DP, address = "11:22:33:44:55:66")
    private val speaker = OutputCandidate(id = 1, type = 2, address = "")

    @Test fun `the headset's A2DP output is picked, matched without case`() {
        val ours = OutputCandidate(id = 7, type = FindOutput.TYPE_BLUETOOTH_A2DP, address = headset.lowercase())
        assertEquals(ours, FindOutput.pick(listOf(speaker, car, ours), headset))
    }

    @Test fun `another Bluetooth device holding the audio is never a fallback`() {
        assertNull(FindOutput.pick(listOf(speaker, car), headset))
    }

    @Test fun `an LE Audio twin's output counts as the headset`() {
        val twin = "AA:BB:CC:DD:EE:FF"
        val le = OutputCandidate(id = 9, type = FindOutput.TYPE_BLE_HEADSET, address = twin)
        assertEquals(le, FindOutput.pick(listOf(car, le), headset, twins = listOf(twin)))
    }

    @Test fun `the output media is routed to wins when the headset has two`() {
        val a2dp = OutputCandidate(id = 7, type = FindOutput.TYPE_BLUETOOTH_A2DP, address = headset)
        val le = OutputCandidate(id = 9, type = FindOutput.TYPE_BLE_HEADSET, address = headset)
        assertEquals(le, FindOutput.pick(listOf(a2dp, le), headset, mediaRouteIds = setOf(9)))
        assertEquals(a2dp, FindOutput.pick(listOf(a2dp, le), headset))
    }

    @Test fun `a non-Bluetooth output with the same address string is ignored`() {
        assertNull(FindOutput.pick(listOf(OutputCandidate(id = 4, type = 22, address = headset)), headset))
    }
}
