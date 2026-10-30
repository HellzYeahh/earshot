package dev.earshot.sleep

import dev.earshot.core.ConnectionState
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneState
import dev.earshot.core.SleepPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Audit X18: the morning restore opens Earshot's control link once for itself (auto-connect off),
 * only after the morning came, only for a headset linked for audio, and never twice.
 */
class RestoreConnectTest {
    private val buds = "00:11:22:33:44:55"
    private val other = "AA:BB:CC:DD:EE:FF"
    private val touchLocked = RestoreRecord(address = buds, touchControlsEnabled = true, due = true)

    private fun state(status: ConnectionStatus = ConnectionStatus.DISCONNECTED, audioLinked: Boolean? = true, address: String? = buds) =
        HeadphoneState(connection = ConnectionState(status, "Buds", address, audioLinked = audioLinked))

    @Test
    fun `a due restore connects its headset once that is linked for audio`() {
        val night = NightRecord(restore = touchLocked)
        assertTrue(RestoreConnect.shouldConnect(state(), night))
        assertTrue(RestoreConnect.shouldConnect(state(address = buds.lowercase()), night))
        assertEquals(touchLocked, RestoreConnect.waitingFor(buds, night))
    }

    @Test
    fun `a restore gets one connect only`() {
        val night = RestoreConnect.markTried(NightRecord(restore = touchLocked), buds)
        assertTrue(night.restore!!.connectTried)
        assertFalse(RestoreConnect.shouldConnect(state(), night))
        assertNull(RestoreConnect.waitingFor(buds, night))
    }

    @Test
    fun `nothing connects before the morning or while a night runs`() {
        // Disconnected by the end action at 1 am, the headset links again at 2 am: the night's touch lock stays.
        assertFalse(RestoreConnect.shouldConnect(state(), NightRecord(restore = touchLocked.copy(due = false))))
        val running = ActiveNight(SleepPlan(), 0, 1, 1, headsetAddress = buds)
        assertFalse(RestoreConnect.shouldConnect(state(), NightRecord(active = running, restore = touchLocked)))
    }

    @Test
    fun `nothing connects for a headset that is away, another headset, or a link already on its way`() {
        val night = NightRecord(restore = touchLocked)
        assertFalse(RestoreConnect.shouldConnect(state(audioLinked = false), night))
        assertFalse(RestoreConnect.shouldConnect(state(audioLinked = null), night))
        assertFalse(RestoreConnect.shouldConnect(state(address = other), night))
        assertFalse(RestoreConnect.shouldConnect(state(address = null), night))
        for (status in listOf(ConnectionStatus.CONNECTING, ConnectionStatus.CONNECTED, ConnectionStatus.NO_PERMISSION, ConnectionStatus.NO_PAIRED_DEVICE)) {
            assertFalse(RestoreConnect.shouldConnect(state(status = status), night), "connected while $status")
        }
    }

    @Test
    fun `a restore with only a phone part never connects`() {
        val phoneOnly = RestoreRecord(address = buds, musicVolume = 7, musicVolumeOutput = "a2dp", due = true)
        assertFalse(RestoreConnect.shouldConnect(state(), NightRecord(restore = phoneOnly)))
    }

    @Test
    fun `an older restore waiting for its own headset connects that one, unless tonight changed it again`() {
        // Tonight was spent with another headset; last night's restore for these buds still waits.
        val elsewhere = NightRecord(restore = touchLocked.copy(address = other, due = false), pending = listOf(touchLocked))
        assertTrue(RestoreConnect.shouldConnect(state(), elsewhere))
        // Tonight's changes to the same buds aren't due yet: the older restore would undo them.
        val sameBuds = NightRecord(restore = touchLocked.copy(due = false), pending = listOf(touchLocked))
        assertFalse(RestoreConnect.shouldConnect(state(), sameBuds))
    }

    @Test
    fun `marking touches only that headset's restores`() {
        val theirs = touchLocked.copy(address = other)
        val night = RestoreConnect.markTried(NightRecord(restore = touchLocked, pending = listOf(theirs)), buds)
        assertTrue(night.restore!!.connectTried)
        assertFalse(night.pending.single().connectTried)
        assertTrue(RestoreConnect.shouldConnect(state(address = other), night))
    }

    @Test
    fun `no connect right after the end action sent the headset away, and the connect isn't used up`() {
        // The morning time passed during the night: the restore is due the moment the Disconnect runs,
        // while Android still reports the audio link up.
        val hold = RestoreConnect.Hold(buds, untilMillis = 1_000 + RestoreConnect.HOLD_MS)
        assertTrue(RestoreConnect.isHeld(hold, buds, now = 1_000))
        assertTrue(RestoreConnect.isHeld(hold, buds.lowercase(), now = 1_000))
        assertFalse(RestoreConnect.isHeld(hold, other, now = 1_000))
        assertFalse(RestoreConnect.isHeld(null, buds, now = 1_000))
        // The stale "still linked" state keeps the hold, and so does news about another headset.
        assertEquals(hold, RestoreConnect.release(hold, state(audioLinked = true), now = 2_000))
        assertEquals(hold, RestoreConnect.release(hold, state(audioLinked = null), now = 2_000))
        assertEquals(hold, RestoreConnect.release(hold, state(audioLinked = false, address = other), now = 2_000))
        // Holding never marks the restore: it still has its one connect for later.
        assertEquals(touchLocked, RestoreConnect.waitingFor(buds, NightRecord(restore = touchLocked)))
    }

    @Test
    fun `the hold ends once the headset is seen gone, or after its time`() {
        val hold = RestoreConnect.Hold(buds, untilMillis = 1_000 + RestoreConnect.HOLD_MS)
        // Seen unlinked: when it links again in the morning it gets its one connect.
        assertNull(RestoreConnect.release(hold, state(audioLinked = false), now = 2_000))
        assertNull(RestoreConnect.release(hold, state(audioLinked = false, address = buds.lowercase()), now = 2_000))
        // Never seen gone (Android refused the disconnect): it doesn't hold the connect forever.
        assertNull(RestoreConnect.release(hold, state(audioLinked = true), now = hold.untilMillis))
        assertFalse(RestoreConnect.isHeld(hold, buds, now = hold.untilMillis))
        assertTrue(RestoreConnect.shouldConnect(state(), NightRecord(restore = touchLocked)))
    }

    @Test
    fun `the flag survives the store, and a file from an older build reads as not tried`() {
        val json = SleepStore.json
        val tried = touchLocked.copy(connectTried = true)
        assertEquals(tried, json.decodeFromString(RestoreRecord.serializer(), json.encodeToString(RestoreRecord.serializer(), tried)))
        val older = """{"address":"$buds","touchControlsEnabled":true,"due":true}"""
        assertFalse(json.decodeFromString(RestoreRecord.serializer(), older).connectTried)
    }
}
