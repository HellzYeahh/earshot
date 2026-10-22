package dev.earshot.virtual

import dev.earshot.bt.BtLog
import dev.earshot.bt.DeviceFamily
import dev.earshot.bt.RfcommConnectException
import dev.earshot.bt.SonyConnection
import dev.earshot.bt.SonyLinkFactory
import dev.earshot.bt.SonyLog
import dev.earshot.core.FormFactor
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneState
import dev.earshot.core.NoiseSettings
import dev.earshot.protocol.sony.SonyTable
import dev.earshot.protocol.sony.SonyWriteGuard
import dev.earshot.service.SonySession
import dev.earshot.service.SonyStateMapping
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real Sony link factory probe, [SonyConnection] (Tandem framing, ACK / 1-bit sequence,
 * resends), [SonySession] and [SonyStateMapping] against a [VirtualSony] WH-1000XM5. The
 * [dev.earshot.bt.ConnectionManager] around them needs an Android Context; its policy is the
 * same for every stack and covered elsewhere.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SonyEndToEndTest {

    @Before
    fun quiet() {
        SonyLog.sink = { _, _, _ -> }
        BtLog.sink = { _, _, _ -> }
    }

    private class Rig(val unit: VirtualSony, val conn: SonyConnection, val session: SonySession) {
        fun state(): HeadphoneState = SonyStateMapping.apply(HeadphoneState(), session.snapshot, now = 0)
        fun control(id: String) = state().vendorControls.first { it.id == id }
    }

    /** Through the factory's adopt + probe, as the connection manager opens a session. */
    private suspend fun TestScope.rig(unit: VirtualSony = VirtualSony(), initialize: Boolean = true): Rig {
        val factory = SonyLinkFactory()
        val conn = factory.adopt(VirtualSonyTransport(unit), backgroundScope)
        factory.probe(conn)
        val session = SonySession(conn, clock = { testScheduler.currentTime })
        backgroundScope.launch { conn.unsolicited.collect { session.onPush(it) } }
        if (initialize) session.initialize()
        return Rig(unit, conn, session)
    }

    @Test fun `the factory names Sony's app and family`() {
        val f = SonyLinkFactory()
        assertEquals(DeviceFamily.SONY, f.family)
        assertEquals("Sony Sound Connect", f.competingApp)
        assertEquals("Close the Sony Sound Connect app, then try again", f.busyMessage)
        assertEquals(DeviceFamily.SONY, DeviceFamily.forName("WH-1000XM5"))
        assertEquals(DeviceFamily.GENERIC, DeviceFamily.forName("WH-1000XM4"), "MDR v1 stays generic")
        assertEquals(DeviceFamily.SONY, DeviceFamily.fromKey("sony"))
    }

    @Test fun `the probe accepts MDR v2, refuses v1, and calls a silent socket busy`() = runTest {
        val factory = SonyLinkFactory()
        val v2 = factory.adopt(VirtualSonyTransport(VirtualSony()), backgroundScope)
        factory.probe(v2)
        assertEquals(0x03003010, v2.protocolInfo?.version)
        v2.close()

        val v1 = factory.adopt(VirtualSonyTransport(VirtualSony(mdrV1 = true)), backgroundScope)
        val e1 = assertFailsWith<RfcommConnectException> { factory.probe(v1) }
        assertFalse(e1.looksBusy)
        assertEquals(SonyLinkFactory.MESSAGE_V1, e1.message)

        val held = factory.adopt(VirtualSonyTransport(VirtualSony().apply { silent = true }), backgroundScope)
        assertTrue(assertFailsWith<RfcommConnectException> { factory.probe(held) }.looksBusy)
    }

    @Test fun `connect reads the whole headset into HeadphoneState`() = runTest {
        val r = rig()
        val s = r.state()
        assertEquals("Sony WH-1000XM5", s.info?.productName)
        assertEquals("1.1.3", s.info?.firmwareVersion)
        assertEquals("1000X", s.info?.family)
        assertEquals(FormFactor.HEADPHONES, s.capabilities.formFactor)
        assertFalse(s.capabilities.verified)
        assertTrue(s.capabilities.supportNote!!.startsWith("Experimental"))
        assertEquals(70, s.batteryPercent)
        assertEquals(false, s.isCharging)
        assertEquals(70, s.battery?.overall?.percent)
        // Noise is a mode: slot 0 NC, 1 ambient, 2 off; no level slider of its own.
        assertEquals(listOf(0 to "Noise cancelling", 1 to "Ambient sound", 2 to "Off"), s.modes.map { it.index to it.name })
        assertEquals(0, s.currentModeIndex)
        assertTrue(s.capabilities.listeningModes && s.capabilities.modesOnHome)
        assertFalse(s.capabilities.noiseCancelling)
        assertEquals(true, s.noise?.ancEnabled)

        val ids = s.vendorControls.map { it.id }
        assertEquals(
            listOf(
                "ambient_level", "focus_on_voice", "eq_preset", "eq_band_0", "eq_band_1", "eq_band_2", "eq_band_3", "eq_band_4", "eq_band_5",
                "auto_power_off", "speak_to_chat", "pause_when_taken_off", "dsee", "connection_priority", "voice_guidance", "general_d1", "general_d2",
            ),
            ids,
        )
        assertEquals(listOf("ambient_level", "focus_on_voice"), s.vendorControls.filter { it.onHome }.map { it.id })
        val level = r.control("ambient_level")
        assertEquals(1..20, level.range, "the range comes from the capability reply")
        assertEquals(15, level.value)
        assertEquals(12, r.control("eq_preset").options?.size)
        assertEquals("Off", r.control("eq_preset").options?.get(r.control("eq_preset").value!!))
        assertEquals("Clear Bass", r.control("eq_band_0").title)
        assertEquals(-10..10, r.control("eq_band_0").range)
        assertEquals("dB", r.control("eq_band_0").unit)
        assertEquals(listOf("5 min", "30 min", "1 hour", "3 hours", "When taken off", "Off"), r.control("auto_power_off").options)
        assertEquals("When taken off", r.control("auto_power_off").let { it.options!![it.value!!] })
        assertEquals("DSEE Extreme", r.control("dsee").title)
        assertEquals("Connect to 2 devices at once", r.control("general_d1").title)
        assertEquals("Touch controls", r.control("general_d2").title)
        assertEquals(1, r.control("voice_guidance").value)

        // Init: no usage logging, no update, no reset, no test, nothing off the allow-list; prompts switched on.
        assertTrue(r.unit.received.none { it.table == SonyTable.T1 && it.command in setOf(0xC4, 0x0F, 0xF4) + (0x30..0x3F) })
        assertTrue(r.unit.received.all { SonyWriteGuard.isAllowed(it) })
        assertTrue(r.unit.alertsEnabled)
        r.conn.close()
    }

    @Test fun `the three modes reach the headset and come back as the current slot`() = runTest {
        val r = rig()
        r.session.setMode(1)
        assertEquals(1, r.unit.ncMode)
        assertTrue(r.unit.ncEffect)
        assertEquals(1, r.state().currentModeIndex)
        r.session.setMode(2)
        assertFalse(r.unit.ncEffect)
        assertEquals(2, r.state().currentModeIndex)
        r.session.setMode(0)
        assertEquals(0, r.unit.ncMode)
        assertEquals(0, r.state().currentModeIndex)
        assertFailsWith<HeadphoneCommandException> { r.session.setMode(5) }
        // Automations speak NoiseSettings: ANC off is the Off mode, on again is noise cancelling.
        r.session.setNoise(NoiseSettings(ancEnabled = false))
        assertEquals(2, r.state().currentModeIndex)
        r.session.setNoise(NoiseSettings(ancEnabled = true))
        assertEquals(0, r.state().currentModeIndex)
        r.conn.close()
    }

    @Test fun `vendor controls round-trip through the headset`() = runTest {
        val r = rig()
        r.session.setVendorControl("ambient_level", 5)
        assertEquals(5, r.unit.ambientLevel)
        assertEquals(5, r.control("ambient_level").value)
        r.session.setVendorControl("focus_on_voice", 1)
        assertTrue(r.unit.focusOnVoice)

        // A preset: the headset applies its own bands.
        val bass = r.control("eq_preset").options!!.indexOf("Bass boost")
        r.session.setVendorControl("eq_preset", bass)
        assertEquals(0x16, r.unit.eqPreset)
        assertEquals(6, r.control("eq_band_0").value) // Clear Bass +6 in the Bass boost preset
        // Editing a band moves to the Manual slot and keeps the others.
        r.session.setVendorControl("eq_band_5", -4)
        assertEquals(0xA0, r.unit.eqPreset)
        assertEquals(listOf(16, 14, 11, 10, 10, 6), r.unit.eqBands)
        assertEquals("Manual", r.control("eq_preset").let { it.options!![it.value!!] })

        r.session.setVendorControl("auto_power_off", 1)
        assertEquals(0x01, r.unit.autoOff)
        assertEquals("30 min", r.control("auto_power_off").let { it.options!![it.value!!] })
        r.session.setVendorControl("auto_power_off", 5)
        assertEquals(0x11, r.unit.autoOff)
        assertEquals(0x01, r.unit.autoOffLast, "switching off remembers the last timer")

        r.session.setVendorControl("speak_to_chat", 1)
        assertTrue(r.unit.speakToChat)
        r.session.setVendorControl("pause_when_taken_off", 0)
        assertFalse(r.unit.pauseWhenTakenOff)
        r.session.setVendorControl("dsee", 0)
        assertFalse(r.unit.dsee)
        r.session.setVendorControl("connection_priority", 1)
        assertEquals(1, r.unit.connectionMode)
        r.session.setVendorControl("voice_guidance", 0)
        assertFalse(r.unit.voiceGuidance)
        assertEquals(0, r.control("voice_guidance").value)
        r.session.setVendorControl("general_d2", 0)
        assertFalse(r.unit.touchPanel)
        assertEquals(0, r.control("general_d2").value)
        assertFailsWith<HeadphoneCommandException> { r.session.setVendorControl("factory_reset", 1) }
        r.conn.close()
    }

    @Test fun `a confirmation prompt after the user's change is accepted`() = runTest {
        val r = rig()
        r.session.setVendorControl("general_d1", 0)
        testScheduler.runCurrent()
        assertFalse(r.unit.multipoint)
        assertEquals(listOf(0x07 to true), r.unit.alertAnswers)
        r.conn.close()
    }

    @Test fun `a prompt the user didn't cause is declined`() = runTest {
        val r = rig()
        testScheduler.advanceTimeBy(SonySession.ALERT_WINDOW_MS + 1_000)
        r.unit.push(SonyTable.T1, byteArrayOf(0x99.toByte(), 0x00, 0x07, 0x01))
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(1_000)
        assertEquals(listOf(0x07 to false), r.unit.alertAnswers)
        r.conn.close()
    }

    @Test fun `the headset's own button and battery pushes update the state`() = runTest {
        val r = rig()
        // NC/AMB button: ambient, level 8.
        r.unit.push(SonyTable.T1, byteArrayOf(0x69, 0x17, 0x01, 0x01, 0x01, 0x00, 0x08))
        r.unit.push(SonyTable.T1, byteArrayOf(0x25, 0x00, 0x41, 0x01))
        testScheduler.runCurrent()
        val s = r.state()
        assertEquals(1, s.currentModeIndex)
        assertEquals(8, r.control("ambient_level").value)
        assertEquals(65, s.batteryPercent)
        assertEquals(true, s.isCharging)
        r.conn.close()
    }

    @Test fun `a lost ACK during a write is resent and the setting is applied once`() = runTest {
        val r = rig()
        val before = r.unit.received.size
        r.unit.dropAcks = 1
        r.session.setVendorControl("ambient_level", 3)
        assertEquals(3, r.unit.ambientLevel)
        assertEquals(3, r.control("ambient_level").value)
        val writes = r.unit.received.drop(before).filter { it.command == 0x68 }
        assertEquals(1, writes.size, "the resent SET was ACKed again but not applied twice")
        r.conn.close()
    }

    @Test fun `power off sends the user power-off and the link drops`() = runTest {
        val r = rig()
        r.session.powerOff()
        testScheduler.runCurrent()
        assertTrue(r.unit.poweredOff)
        assertEquals(listOf(0x24, 0x03, 0x01), r.unit.received.last().payload.map { it.toInt() and 0xFF })
        assertNull(r.conn.awaitClosed())
        assertFalse(r.conn.isOpen)
    }

    @Test fun `an unknown protocol version is read-only`() = runTest {
        val r = rig(VirtualSony(protocolVersion = 0x04000001))
        val s = r.state()
        assertEquals(SonyStateMapping.READ_ONLY_NOTE, s.capabilities.supportNote)
        assertEquals(15, r.control("ambient_level").value, "reads still work")
        val before = r.unit.received.size
        assertFailsWith<HeadphoneCommandException> { r.session.setMode(1) }
        assertFailsWith<HeadphoneCommandException> { r.session.setVendorControl("dsee", 0) }
        assertEquals(before, r.unit.received.size, "nothing was written")
        assertFalse(r.unit.alertsEnabled)
        r.conn.close()
    }

    @Test fun `the demo device is the Sony entry`() {
        assertTrue(DemoDevice.SONY_WH1000XM5.isSony)
        assertNull(DemoDevice.SONY_WH1000XM5.virtualProfile())
        assertNotNull(DemoDevice.entries.firstOrNull { it.isSony })
    }
}
