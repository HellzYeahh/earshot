package dev.earshot.virtual

import dev.earshot.bt.BtLog
import dev.earshot.bt.SoundcoreConnection
import dev.earshot.bt.SoundcoreLinkFactory
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneState
import dev.earshot.protocol.soundcore.D1301State
import dev.earshot.protocol.soundcore.SoundcoreCommands
import dev.earshot.protocol.soundcore.SoundcorePacket
import dev.earshot.service.SoundcoreStateMapping
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The real Soundcore connection, framing and state mapping against a [VirtualSleepA30]. */
@OptIn(ExperimentalCoroutinesApi::class)
class SoundcoreEndToEndTest {

    @Before
    fun quiet() {
        BtLog.sink = { _, _, _ -> }
    }

    private fun TestScope.connect(unit: VirtualSleepA30): Pair<SoundcoreConnection, VirtualSleepA30Transport> {
        val transport = VirtualSleepA30Transport(unit)
        return SoundcoreConnection(transport, backgroundScope, minWriteGapMs = 0) to transport
    }

    @Test fun `reads the state and maps it to two modes, per-bud battery and firmware`() = runTest {
        val unit = VirtualSleepA30(ancOn = true, leftBattery = 7, rightBattery = 5)
        val (conn, _) = connect(unit)
        val body = conn.request(SoundcoreCommands.REQUEST_STATE).body
        val state = SoundcoreStateMapping.apply(HeadphoneState(), assertNotNull(D1301State.parse(body)), now = 0)
        assertEquals(SoundcoreStateMapping.MODE_ANC, state.currentModeIndex)
        assertEquals(listOf("Noise cancelling", "Off"), state.modes.map { it.name })
        assertEquals(60, state.batteryPercent) // the lower bud: 5 → 60 %
        assertEquals(80, state.battery?.left?.percent)
        assertEquals("01.91", state.info?.firmwareVersion)
        assertEquals(true, state.touchControlsEnabled)
        assertFalse(state.capabilities.verified)
        conn.close()
    }

    @Test fun `noise cancelling off and tap controls off reach the earbuds`() = runTest {
        val unit = VirtualSleepA30(ancOn = true)
        val (conn, _) = connect(unit)
        conn.request(SoundcoreCommands.SET_ANC_D1301, byteArrayOf(0))
        assertFalse(unit.ancOn)
        conn.request(SoundcoreCommands.SET_TAP_DISABLED, byteArrayOf(1))
        assertTrue(unit.tapControlsDisabled)
        val after = assertNotNull(D1301State.parse(conn.request(SoundcoreCommands.REQUEST_STATE).body))
        assertFalse(after.ancOn)
        assertTrue(after.tapControlsDisabled)
        conn.close()
    }

    @Test fun `unpair, power off and unknown writes never leave the phone`() = runTest {
        val unit = VirtualSleepA30()
        val (conn, _) = connect(unit)
        for (command in listOf(0x0B83, 0x0189, 0x1089, 0x0681)) {
            assertFailsWith<HeadphoneCommandException> { conn.request(command, byteArrayOf(1)) }
        }
        assertTrue(unit.received.isEmpty(), "nothing was written: ${unit.received}")
        conn.close()
    }

    @Test fun `a silent earbud fails after the retries instead of hanging`() = runTest {
        val unit = VirtualSleepA30()
        val (conn, _) = connect(unit)
        // Listening mode is allowed but this virtual unit answers it; 0x0501 is allowed and unanswered.
        assertFailsWith<HeadphoneCommandException> { conn.request(0x0501) }
        assertEquals(3, unit.received.count { it.command == 0x0501 }, "three attempts, as OpenSCQ30 does")
        conn.close()
    }

    @Test fun `pushes arrive on unsolicited, not as answers`() = runTest {
        val unit = VirtualSleepA30()
        val (conn, transport) = connect(unit)
        val pushes = mutableListOf<SoundcorePacket>()
        val job = backgroundScope.launch { conn.unsolicited.collect { pushes += it } }
        testScheduler.runCurrent()
        transport.push(SoundcorePacket(SoundcoreCommands.PUSH_BATTERY, byteArrayOf(2, 3), inbound = true))
        testScheduler.runCurrent()
        assertEquals(listOf(SoundcoreCommands.PUSH_BATTERY), pushes.map { it.command })
        assertEquals(30 to 40, D1301State.parseBattery(pushes.single().body))
        job.cancel()
        conn.close()
    }

    @Test fun `the Soundcore stack reports the soundcore app when the channel is held`() {
        val factory = SoundcoreLinkFactory()
        assertEquals("soundcore", factory.competingApp)
        assertEquals(dev.earshot.bt.DeviceFamily.SOUNDCORE, factory.family)
    }

    @Test fun `every Sleep A30 control round-trips through the earbuds' state`() = runTest {
        val unit = VirtualSleepA30()
        val (conn, _) = connect(unit)
        val before = SoundcoreStateMapping.controls(assertNotNull(D1301State.parse(conn.request(SoundcoreCommands.REQUEST_STATE).body)))
        assertEquals(10, before.size)
        assertEquals(listOf("anc", "listening_mode"), before.filter { it.onHome }.map { it.id })
        for (control in before) {
            val target = if (control.value == 1) 0 else 1
            val (command, body) = assertNotNull(SoundcoreStateMapping.write(control.id, target), control.id)
            conn.request(command, body)
            val after = SoundcoreStateMapping.controls(assertNotNull(D1301State.parse(conn.request(SoundcoreCommands.REQUEST_STATE).body)))
            assertEquals(target, after.first { it.id == control.id }.value, control.id)
        }
        assertEquals(null, SoundcoreStateMapping.write("power_off", 1))
        conn.close()
    }

    @Test fun `Home shows the switch, not the two stand-in modes`() {
        val state = SoundcoreStateMapping.apply(HeadphoneState(), assertNotNull(D1301State.parse(VirtualSleepA30().stateBody())), now = 0)
        assertFalse(state.capabilities.modesOnHome)
        assertTrue(state.capabilities.listeningModes, "the tile, widget and automations still use the modes")
        assertEquals("Noise cancelling", state.vendorControls.first().title)
    }
}
