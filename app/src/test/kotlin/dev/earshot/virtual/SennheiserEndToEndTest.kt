package dev.earshot.virtual

import dev.earshot.bt.BtLog
import dev.earshot.bt.ByteTransport
import dev.earshot.bt.DeviceFamily
import dev.earshot.bt.RfcommConnectException
import dev.earshot.bt.SennheiserConnection
import dev.earshot.bt.SennheiserLinkFactory
import dev.earshot.bt.SennheiserLog
import dev.earshot.bt.UnresponsiveLinkException
import dev.earshot.core.FormFactor
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneEq
import dev.earshot.core.HeadphoneState
import dev.earshot.core.NoiseSettings
import dev.earshot.extras.HeadphoneLabels
import dev.earshot.protocol.gaia.GaiaCommand
import dev.earshot.protocol.gaia.GaiaFrame
import dev.earshot.protocol.gaia.GaiaVendor
import dev.earshot.protocol.sennheiser.SennheiserCommands as C
import dev.earshot.protocol.sennheiser.SennheiserModel
import dev.earshot.service.SennheiserSession
import dev.earshot.service.SennheiserStateMapping
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real Sennheiser stack (the link factory's adopt path, [SennheiserConnection] with its GAIA
 * queue and write guard, [SennheiserSession], [SennheiserStateMapping]) against a
 * [VirtualSennheiser] that behaves like the Momentum 4 in momentum4-control's captures, ANC side
 * effects included. [dev.earshot.bt.ConnectionManager] itself needs an Android Context (receivers,
 * DataStore), so the session is driven here the way the repository drives it once the manager
 * hands over the connection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SennheiserEndToEndTest {

    @Before
    fun quiet() {
        BtLog.sink = { _, _, _ -> }
        SennheiserLog.sink = { _, _, _ -> }
    }

    private class Rig(val unit: VirtualSennheiser, val transport: VirtualSennheiserTransport, val conn: SennheiserConnection, val session: SennheiserSession)

    /** What the repository does: adopt the transport, collect pushes into the session, start it. */
    private suspend fun TestScope.rig(unit: VirtualSennheiser = VirtualSennheiser(), name: String = "MOMENTUM 4", start: Boolean = true): Rig {
        val transport = VirtualSennheiserTransport(unit)
        val conn = SennheiserLinkFactory().adopt(transport, backgroundScope)
        val session = SennheiserSession(conn, name)
        backgroundScope.launch { conn.pushes.collect { session.onPush(it) } }
        testScheduler.runCurrent()
        if (start) session.start()
        testScheduler.runCurrent()
        return Rig(unit, transport, conn, session)
    }

    private fun Rig.state(): HeadphoneState = SennheiserStateMapping.apply(HeadphoneState(), session.status.value, session.model, now = 0)

    private fun Rig.sent(): List<Int> = unit.received.filter { it.vendor == C.VENDOR }.map { it.command }

    // ---- Connect ------------------------------------------------------------------------------

    @Test fun `connect discovers, identifies, registers and reads a Momentum 4 into HeadphoneState`() = runTest {
        val r = rig()
        val s = r.session.status.value
        assertEquals("M4AEBT Black", s.modelId)
        assertEquals(SennheiserModel.MOMENTUM_4, r.session.model)
        assertEquals("3.37.3", s.firmware)
        assertEquals(VirtualSennheiser.M4_FEATURES, s.features)
        assertEquals(VirtualSennheiser.M4_FEATURES, r.conn.features, "the guard now knows what the headset has")

        val state = r.state()
        assertEquals(listOf("Noise cancelling", "Transparency", "Off"), state.modes.map { it.name })
        assertEquals(SennheiserStateMapping.MODE_ANC, state.currentModeIndex)
        assertEquals(NoiseSettings(cancellingLevel = 10, ancEnabled = true), state.noise)
        assertEquals(80, state.batteryPercent)
        assertEquals(false, state.isCharging)
        assertEquals("Sennheiser MOMENTUM 4", state.info?.modelName)
        assertEquals("3.37.3", state.info?.firmwareVersion)
        assertEquals(HeadphoneEq(0, 0, 0), state.eq)
        assertEquals(-6..6, state.eqRange)
        with(state.capabilities) {
            assertFalse(verified)
            assertTrue(noiseCancelling && ancToggle && liveNoiseControl && modesOnHome)
            assertFalse(editableModes || favoriteModes || windBlock || spatialAudio)
            assertEquals(FormFactor.HEADPHONES, formFactor)
            assertTrue(supportNote!!.startsWith("Experimental"))
        }
        assertEquals(
            listOf("adaptive", "anti_wind", "comfort", "pause_in_transparency", "on_head", "smart_pause", "auto_answer", "comfort_call", "sidetone", "auto_off", "bass_boost"),
            state.vendorControls.map { it.id },
        )
        assertEquals(listOf("adaptive", "anti_wind"), state.vendorControls.filter { it.onHome }.map { it.id })
        val byId = state.vendorControls.associateBy { it.id }
        assertEquals(1, byId.getValue("anti_wind").value) // Max
        assertEquals(1, byId.getValue("auto_off").value) // 900 s = 15 min
        assertEquals(2, byId.getValue("sidetone").value) // High
        assertEquals(1, byId.getValue("on_head").value)

        // Pushes registered for battery, ANC, transparency, wear, audio and EQ; never for multipoint or buttons.
        val registered = r.unit.received.filter { it.command == C.REGISTER_NOTIFICATION }.map { it.payload.single().toInt() }
        assertEquals(listOf(3, 13, 12, 2, 4, 8), registered)
        assertEquals(listOf(3), r.session.status.value.wear, "the stray wear response after registering feature 2 was applied")
    }

    @Test fun `the only Qualcomm commands are the version and feature reads`() = runTest {
        val r = rig()
        val qualcomm = r.unit.received.filter { it.vendor == GaiaVendor.QUALCOMM }.map { it.command }.toSet()
        assertEquals(setOf(0x0000, 0x0001), qualcomm)
        assertTrue(r.unit.received.all { it.type == GaiaCommand.TYPE_COMMAND })
    }

    // ---- Modes and noise (the Momentum 4 side effects) ----------------------------------------

    @Test fun `transparency then noise cancelling, with the level side effects read back`() = runTest {
        val r = rig(VirtualSennheiser(level = 30))
        r.session.setMode(SennheiserStateMapping.MODE_TRANSPARENCY)
        testScheduler.runCurrent()
        assertTrue(r.unit.transparencyOn)
        assertEquals(100, r.unit.level, "transparency on sets the level to 100")
        var state = r.state()
        assertEquals(SennheiserStateMapping.MODE_TRANSPARENCY, state.currentModeIndex)
        assertEquals(0, state.noise?.cancellingLevel)

        r.unit.received.clear()
        r.session.setMode(SennheiserStateMapping.MODE_ANC)
        testScheduler.runCurrent()
        // Transparency off first (restores 30), then ANC on (level to 0): both written, then the group re-read.
        assertEquals(listOf(C.TH_SET, C.ANC_SET, C.ANC_GET, C.TH_GET, C.ANC_LEVEL_GET, C.ANC_MODE_GET), r.sent())
        state = r.state()
        assertEquals(SennheiserStateMapping.MODE_ANC, state.currentModeIndex)
        assertEquals(10, state.noise?.cancellingLevel)
        assertFalse(r.unit.transparencyOn)
    }

    @Test fun `off, then the slider brings ANC back at the chosen level`() = runTest {
        val r = rig()
        r.session.setMode(SennheiserStateMapping.MODE_OFF)
        testScheduler.runCurrent()
        assertFalse(r.unit.ancOn)
        assertEquals(SennheiserStateMapping.MODE_OFF, r.state().currentModeIndex)

        r.unit.received.clear()
        r.session.setNoise(NoiseSettings(cancellingLevel = 7, ancEnabled = true))
        testScheduler.runCurrent()
        // ANC on resets the level to 0 on the headset, so the level goes after it.
        val writes = r.unit.received.filter { it.command == C.ANC_SET || it.command == C.ANC_LEVEL_SET }
        assertEquals(listOf(C.ANC_SET, C.ANC_LEVEL_SET), writes.map { it.command })
        assertEquals(30, writes[1].payload.single().toInt())
        assertEquals(30, r.unit.level)
        assertEquals(NoiseSettings(cancellingLevel = 7, ancEnabled = true), r.state().noise)
    }

    @Test fun `a mode-table control switches transparency off and the level to 0, and the screen shows it`() = runTest {
        val r = rig(VirtualSennheiser(transparencyOn = true, level = 100))
        assertEquals(SennheiserStateMapping.MODE_TRANSPARENCY, r.state().currentModeIndex)
        r.session.setVendorControl(SennheiserStateMapping.ANTI_WIND, 2)
        testScheduler.runCurrent()
        val modeWrite = r.unit.received.last { it.command == C.ANC_MODE_SET }
        assertEquals(listOf(1, 2), modeWrite.payload.map { it.toInt() }, "a single pair, as the Momentum 4 accepts")
        val state = r.state()
        assertEquals(2, state.vendorControls.first { it.id == "anti_wind" }.value) // Auto
        assertEquals(SennheiserStateMapping.MODE_ANC, state.currentModeIndex)
        assertEquals(10, state.noise?.cancellingLevel)
    }

    // ---- Settings -----------------------------------------------------------------------------

    @Test fun `every setting round-trips through the headset`() = runTest {
        val r = rig()
        val targets = mapOf(
            "adaptive" to 1, "comfort" to 1, "pause_in_transparency" to 1, "on_head" to 0, "smart_pause" to 0,
            "auto_answer" to 1, "comfort_call" to 0, "sidetone" to 1, "auto_off" to 2, "bass_boost" to 1,
        )
        for ((id, value) in targets) {
            r.session.setVendorControl(id, value)
            testScheduler.runCurrent()
            assertEquals(value, r.state().vendorControls.first { it.id == id }.value, id)
        }
        assertEquals(1800, r.unit.autoOffSeconds, "auto-off pushes nothing; the re-read showed it")
        assertEquals(1, r.unit.sidetone)
        assertTrue(r.unit.bassBoost)
        assertFailsWith<HeadphoneCommandException> { r.session.setVendorControl("sidetone", 4) }
        assertFailsWith<HeadphoneCommandException> { r.session.setVendorControl("power_off", 1) }
        assertFailsWith<HeadphoneCommandException> { r.session.setVendorControl("transparency_level", 1) } // earbuds only
    }

    @Test fun `the three-band EQ writes only the bands of the third that changed`() = runTest {
        val r = rig(VirtualSennheiser(eqGains = mutableListOf(0, 0, 22, 22, 10)))
        val before = assertNotNull(r.state().eq)
        assertEquals(HeadphoneEq(0, 2, 2), before)
        r.unit.received.clear()
        r.session.setEq(before, before.copy(bass = 4))
        testScheduler.runCurrent()
        val writes = r.unit.received.filter { it.command == C.EQ_BAND_SET }.map { it.payload.map { b -> b.toInt() } }
        assertEquals(listOf(listOf(0, 40), listOf(1, 40)), writes)
        assertEquals(listOf(40, 40, 22, 22, 10), r.unit.eqGains)
        assertEquals(HeadphoneEq(4, 2, 2), r.state().eq)
        // Clamped to the headset's ±6 dB.
        r.session.setEq(r.state().eq, HeadphoneEq(4, 2, 9))
        assertEquals(listOf(40, 40, 22, 60, 60), r.unit.eqGains)
    }

    @Test fun `generation-1 EQ firmware gets no EQ at all`() = runTest {
        val r = rig(VirtualSennheiser(features = VirtualSennheiser.M4_FEATURES + (8 to 2)))
        assertNull(r.state().eq)
        assertTrue(r.unit.received.none { GaiaCommand.feature(it.command) == 8 && it.vendor == C.VENDOR })
        assertFailsWith<HeadphoneCommandException> { r.session.setEq(null, HeadphoneEq(1, 0, 0)) }
        assertFalse(r.state().vendorControls.any { it.id == "bass_boost" })
    }

    @Test fun `pushes from the headset reach the state`() = runTest {
        val r = rig()
        r.transport.push(GaiaFrame(C.VENDOR, GaiaCommand.notification(C.BATTERY_GET), byteArrayOf(41)))
        r.transport.push(GaiaFrame(C.VENDOR, GaiaCommand.notification(C.TH_GET), byteArrayOf(1)))
        testScheduler.runCurrent()
        val state = r.state()
        assertEquals(41, state.batteryPercent)
        assertEquals(SennheiserStateMapping.MODE_TRANSPARENCY, state.currentModeIndex)
    }

    // ---- What never leaves the phone ----------------------------------------------------------

    @Test fun `the block-list never reaches the headset, even with every feature reported`() = runTest {
        val r = rig()
        r.conn.features = (0..30).associateWith { 6 }
        r.unit.received.clear()
        val blocked = listOf(
            C.VENDOR to 0x0040, C.VENDOR to 0x0200, C.VENDOR to 0x0201, C.VENDOR to 0x060F, C.VENDOR to 0x1C00,
            C.VENDOR to 0x1403, C.VENDOR to 0x1405, C.VENDOR to 0x1406, C.VENDOR to 0x2009,
            GaiaVendor.QUALCOMM to 0x0C00, GaiaVendor.QUALCOMM to 0x0C01, GaiaVendor.QUALCOMM to 0x0C02,
            GaiaVendor.QUALCOMM to 0x0803, GaiaVendor.QUALCOMM to 0x0804, GaiaVendor.QUALCOMM to 0x0009, GaiaVendor.QUALCOMM to 0x000A,
        )
        for ((vendor, command) in blocked) {
            assertFailsWith<HeadphoneCommandException> { r.conn.request(vendor, command, byteArrayOf(1)) }
        }
        for (function in listOf(8, 19)) {
            assertFailsWith<HeadphoneCommandException> { r.conn.request(C.VENDOR, 0x1600, byteArrayOf(0, 1, function.toByte())) }
        }
        assertTrue(r.unit.received.isEmpty(), "nothing was written: ${r.unit.received}")
        assertTrue(r.conn.isOpen, "a refusal is not a dead link")
    }

    @Test fun `before the feature list only discovery goes out`() = runTest {
        val r = rig(start = false)
        assertFailsWith<HeadphoneCommandException> { r.conn.call(C.BATTERY_GET) }
        assertFailsWith<HeadphoneCommandException> { r.conn.call(C.ANC_SET, byteArrayOf(1)) }
        assertTrue(r.unit.received.isEmpty())
        r.conn.call(C.SUPPORTED_FEATURES)
        assertEquals(listOf(C.SUPPORTED_FEATURES), r.sent())
    }

    @Test fun `a headset that refuses its feature list is left alone`() = runTest {
        val unit = VirtualSennheiser()
        // A unit whose Sennheiser feature list answers "command not supported".
        val transport = object : ByteTransport {
            private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
            private val frames = dev.earshot.protocol.gaia.GaiaFrameBuffer()
            override val isConnected = true
            override val incoming: Flow<ByteArray> = inbound.receiveAsFlow()
            override suspend fun write(bytes: ByteArray) {
                for (f in frames.feed(bytes)) {
                    unit.received += f
                    val reply = if (f.vendor == C.VENDOR && f.command == C.SUPPORTED_FEATURES) {
                        GaiaFrame(f.vendor, GaiaCommand.error(f.command), byteArrayOf(1))
                    } else unit.answer(f).first()
                    inbound.trySend(reply.encode())
                }
            }
            override fun close() { inbound.close() }
        }
        val conn = SennheiserConnection(transport, backgroundScope)
        val session = SennheiserSession(conn, "MOMENTUM 4")
        session.start()
        assertNull(session.status.value.features)
        assertTrue(unit.received.all { it.command == 0x0000 || it.command == C.SUPPORTED_FEATURES }, "${unit.received}")
        val state = SennheiserStateMapping.apply(HeadphoneState(), session.status.value, session.model, now = 0)
        assertEquals(SennheiserStateMapping.NO_FEATURES_NOTE, state.capabilities.supportNote)
        assertTrue(state.modes.isEmpty())
        assertFailsWith<HeadphoneCommandException> { session.setMode(0) }
    }

    @Test fun `a silent link fails each request and closes as busy after three`() = runTest {
        val silent = object : ByteTransport {
            private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
            override val isConnected = true
            override val incoming: Flow<ByteArray> = inbound.receiveAsFlow()
            override suspend fun write(bytes: ByteArray) = Unit
            override fun close() { inbound.close() }
        }
        val conn = SennheiserConnection(silent, backgroundScope)
        repeat(3) { assertFailsWith<HeadphoneCommandException> { conn.request(GaiaVendor.QUALCOMM, 0x0000) } }
        assertFalse(conn.isOpen)
        assertIs<UnresponsiveLinkException>(conn.awaitClosed())
    }

    // ---- Other families -----------------------------------------------------------------------

    @Test fun `true wireless earbuds get modes, anti-wind and a transparency level, but no slider`() = runTest {
        val unit = VirtualSennheiser(modelId = "MTW4 Black Copper")
        val r = rig(unit, name = "MOMENTUM TW 4")
        assertEquals(SennheiserModel.MOMENTUM_TW4, r.session.model)
        val state = r.state()
        assertEquals(FormFactor.EARBUDS, state.capabilities.formFactor)
        assertFalse(state.capabilities.noiseCancelling)
        assertEquals(listOf("anti_wind", "transparency_level"), state.vendorControls.filter { it.onHome }.map { it.id })
        assertNull(state.vendorControls.first { it.id == "anti_wind" }.options, "a switch on earbuds")
        assertFalse(state.vendorControls.any { it.id == "adaptive" || it.id == "on_head" || it.id == "comfort_call" })

        r.unit.received.clear()
        r.session.setMode(SennheiserStateMapping.MODE_TRANSPARENCY)
        // Earbuds: transparency on by itself, no ANC write first.
        assertEquals(C.TH_SET, r.sent().first())
        assertFalse(C.ANC_SET in r.sent())
        r.session.setVendorControl("transparency_level", 1)
        assertEquals(1, r.state().vendorControls.first { it.id == "transparency_level" }.value)
    }

    @Test fun `the Accentum gets two switches through its modes and no mode-table control`() = runTest {
        val r = rig(VirtualSennheiser(modelId = "ACAEBT Black"), name = "ACCENTUM")
        assertEquals(SennheiserModel.ACCENTUM, r.session.model)
        val state = r.state()
        assertFalse(state.capabilities.noiseCancelling)
        assertFalse(state.vendorControls.any { it.id in setOf("anti_wind", "adaptive", "comfort", "smart_pause") })
        assertTrue(r.unit.received.none { it.command == C.ANC_MODE_GET || it.command == C.ANC_MODE_SET }, "its mode table is never touched")
        r.session.setMode(SennheiserStateMapping.MODE_OFF)
        assertEquals(SennheiserStateMapping.MODE_OFF, r.state().currentModeIndex)
    }

    // ---- Routing and transport rules ----------------------------------------------------------

    @Test fun `Sennheiser names route to the Sennheiser stack, never to Bose`() {
        for (name in listOf("MOMENTUM 4", "MOMENTUM TW 4", "ACCENTUM Plus", "ACCENTUM TW", "MOMENTUM Sport", "MOMENTUM 5", "ACCENTUM")) {
            assertEquals(DeviceFamily.SENNHEISER, DeviceFamily.forName(name), name)
        }
        for (name in listOf("CX Plus True Wireless", "CX 500BT", "MOMENTUM TW 2", "HD 450BT")) {
            assertEquals(DeviceFamily.GENERIC, DeviceFamily.forName(name), name)
        }
        assertEquals(DeviceFamily.BOSE, DeviceFamily.forName("Bose QC Ultra Headphones"))
        assertEquals(DeviceFamily.SENNHEISER, DeviceFamily.fromKey("sennheiser"))
        assertEquals(FormFactor.EARBUDS, HeadphoneLabels.formFactorFromName("MOMENTUM TW 4"))
        assertEquals(FormFactor.HEADPHONES, HeadphoneLabels.formFactorFromName("MOMENTUM 4"))
    }

    @Test fun `the link factory names Smart Control and refuses records without the GAIA v3 service`() {
        val factory = SennheiserLinkFactory()
        assertEquals(DeviceFamily.SENNHEISER, factory.family)
        assertEquals("Sennheiser Smart Control", factory.competingApp)
        assertEquals("Close the Sennheiser Smart Control app, then try again", factory.busyMessage)
        // Unknown record: left to SDP. The GAIA v3 record: fine.
        SennheiserLinkFactory.checkServiceRecord(emptyList())
        SennheiserLinkFactory.checkServiceRecord(listOf(SPP, IAP2, SennheiserModel.GAIA3_SERVICE))
        // Only iAP2 / SPP (a BLE-first model) or only the legacy GAIA record: refused before any socket.
        assertFalse(assertFailsWith<RfcommConnectException> { SennheiserLinkFactory.checkServiceRecord(listOf(SPP, IAP2)) }.looksBusy)
        assertTrue(
            assertFailsWith<RfcommConnectException> { SennheiserLinkFactory.checkServiceRecord(listOf(SennheiserModel.GAIA_LEGACY_SERVICE)) }
                .message!!.contains("older protocol"),
        )
    }

    private companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        val IAP2: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")
    }
}
