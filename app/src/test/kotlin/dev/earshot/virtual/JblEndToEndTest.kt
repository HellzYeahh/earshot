package dev.earshot.virtual

import dev.earshot.bt.BtLog
import dev.earshot.bt.ByteTransport
import dev.earshot.bt.JblConnection
import dev.earshot.bt.JblLog
import dev.earshot.core.BatteryLevel
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.FormFactor
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneEvent
import dev.earshot.core.HeadphoneState
import dev.earshot.core.ModeMeaning
import dev.earshot.core.NoiseSettings
import dev.earshot.core.VendorControl
import dev.earshot.protocol.jbl.JblCommands as C
import dev.earshot.protocol.jbl.JblFrame
import dev.earshot.protocol.jbl.JblReads
import dev.earshot.protocol.jbl.JblWriteGuard
import dev.earshot.service.JblHeadphoneRepository
import dev.earshot.service.JblStateMapping
import dev.earshot.service.session.FakeForegroundHost
import dev.earshot.service.session.FakeLinkSource
import dev.earshot.service.session.recordEvents
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real JBL stack end to end: [JblHeadphoneRepository] (its state, events, command mutex and
 * Lab) over a [FakeLinkSource] that hands it a real [JblConnection] to a [VirtualJbl], a Wave Buds 2
 * as jbl-control captured it (firmware 2.7.0, right bud in its case, Ambient Aware on, EQ off, voice
 * prompts on, no answer to the noise-cancelling level). Only `ConnectionManager` is replaced (it needs
 * an Android Context, as does `JblLinkFactory`); the manager's one job here, publishing the link and
 * clearing it when the connection ends, is done by the fake.
 *
 * Every test ends by proving the wire: each write the unit saw was one whole frame [JblWriteGuard]
 * allows, and none was on its never list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JblEndToEndTest {

    @Before
    fun quiet() {
        JblLog.sink = { _, _, _ -> }
        BtLog.sink = { _, _, _ -> }
    }

    /** The unit's transport, keeping every write as the phone made it: one whole frame per GATT write. */
    private class WireSpy(private val inner: ByteTransport) : ByteTransport by inner {
        val writes = mutableListOf<ByteArray>()

        override suspend fun write(bytes: ByteArray) {
            writes += bytes.copyOf()
            inner.write(bytes)
        }
    }

    private class Rig(
        val unit: VirtualJbl,
        val transport: VirtualJblTransport,
        val wire: WireSpy,
        val link: FakeLinkSource<JblConnection>,
        val conn: JblConnection,
        val repo: JblHeadphoneRepository,
        val events: List<HeadphoneEvent>,
    ) {
        val state: HeadphoneState get() = repo.state.value

        fun control(id: String): VendorControl? = state.vendorControls.firstOrNull { it.id == id }
    }

    /** What the manager does after the factory: open the link, publish it, CONNECTED; then wait for the repository's session to finish its reads. */
    private suspend fun TestScope.connected(unit: VirtualJbl = VirtualJbl(), deviceName: String = "JBL Wave Buds 2"): Rig {
        val transport = VirtualJblTransport(unit)
        val wire = WireSpy(transport)
        val link = FakeLinkSource<JblConnection>(deviceName = deviceName)
        val repo = JblHeadphoneRepository(link, { emptyList() }, FakeForegroundHost(), backgroundScope)
        val events = recordEvents(repo)
        val conn = JblConnection(wire, backgroundScope, clock = { testScheduler.currentTime })
        link.open(conn)
        var waited = 0L
        while (HeadphoneEvent.Connected !in events && waited < 60_000) {
            advanceTimeBy(100)
            runCurrent()
            waited += 100
        }
        assertTrue(HeadphoneEvent.Connected in events, "the session connected: $events")
        return Rig(unit, transport, wire, link, conn, repo, events)
    }

    private fun ByteArray.hex(): String = joinToString(" ") { "%02x".format(it) }

    /** Nothing but whole, guard-allowed frames ever reached the unit, and none of the never list. */
    private fun Rig.assertWireWasGuarded() {
        val frames = wire.writes.map { assertNotNull(JblFrame.decodeWhole(it), "a write that is no whole H-AA frame: ${it.hex()}") }
        assertEquals(frames, unit.received, "the unit saw exactly what the phone wrote")
        for (f in frames) {
            assertTrue(JblWriteGuard.isAllowed(f), "outside the guard: $f")
            assertFalse(f.command in NEVER_SENT, "sent $f")
        }
    }

    private fun Rig.sentCommands(): List<Int> = unit.received.map { it.command }

    // ---- Connect ------------------------------------------------------------------------------

    @Test fun `connect reads a Wave Buds 2 into HeadphoneState`() = runTest {
        val r = connected()
        val s = r.state
        assertEquals(ConnectionStatus.CONNECTED, s.connection.status)
        assertEquals("JBL Wave Buds 2", s.info?.productName)
        assertEquals("JBL Wave Buds 2", s.info?.modelName)
        assertEquals(0x2100, s.info?.productId)
        assertEquals("2.7.0", s.info?.firmwareVersion)

        assertEquals(listOf("Noise cancelling", "Ambient Aware", "Off", "TalkThru"), s.modes.map { it.name })
        assertEquals(listOf(0, 1, 2, 3), s.modes.map { it.index })
        assertEquals(listOf(ModeMeaning.QUIET, ModeMeaning.AWARE, ModeMeaning.OFF, ModeMeaning.CUSTOM), s.modes.map { it.meaning })
        assertTrue(s.modes.all { it.isConfigured && it.isPreset && !it.isEditable })
        assertEquals(JblStateMapping.MODE_AMBIENT, s.currentModeIndex)

        // The right bud is in its case: it reads unknown (0xFF), so the headline is the left bud.
        assertEquals(100, s.batteryPercent)
        assertEquals(BatteryLevel(100), s.battery?.left)
        assertEquals(BatteryLevel(null), s.battery?.right)
        assertEquals(BatteryLevel(100), s.battery?.case)
        assertEquals(false, s.isCharging)

        // The level read goes unanswered on a Wave Buds 2: no slider, so nothing to write either.
        assertEquals(listOf(JblStateMapping.EQ_PRESET, JblStateMapping.VOICE_PROMPTS), s.vendorControls.map { it.id })
        val eq = assertNotNull(r.control(JblStateMapping.EQ_PRESET))
        assertEquals(0, eq.value)
        assertEquals("Off", eq.options?.get(0))
        assertEquals(C.EQ_PRESETS.map { it.label }, eq.options)
        assertEquals(1, r.control(JblStateMapping.VOICE_PROMPTS)?.value)
        assertNull(r.control(JblStateMapping.ANC_LEVEL))

        with(s.capabilities) {
            assertTrue(listeningModes && modesOnHome)
            assertFalse(verified)
            assertFalse(editableModes || favoriteModes || spatialAudio || ancToggle || noiseCancelling || windBlock)
            assertEquals(FormFactor.EARBUDS, formFactor)
            assertTrue(supportNote!!.startsWith("Experimental"), supportNote)
        }
        assertTrue(r.events.none { it is HeadphoneEvent.Error }, "${r.events}")
        assertTrue(r.unit.notificationsOn)

        // Pushes on first, then each feature probed once in the order jbl-control reads them.
        assertEquals(C.notifyOn(), r.unit.received.first())
        assertEquals(listOf(C.NOTIFY_ON, C.LISTENING, C.LISTENING, C.INFO, C.EQ, C.VOICE_PROMPTS), r.sentCommands())
        r.assertWireWasGuarded()
    }

    // ---- Modes --------------------------------------------------------------------------------

    @Test fun `setMode(0) switches the unit to noise cancelling with the app's three pairs`() = runTest {
        val r = connected()
        r.repo.setMode(JblStateMapping.MODE_ANC)
        runCurrent()
        assertEquals(C.WIRE_ANC, r.unit.mode)
        assertEquals(JblStateMapping.MODE_ANC, r.state.currentModeIndex)
        assertFalse(r.state.isBusy)
        // One `[mode, on]` pair for each mode the headset listed, as the app and jbl-control send them.
        val write = r.unit.received.last { it.command == C.LISTENING && it.sub == C.MODE_SET }
        assertEquals(listOf(C.MODE_SET, 1, 1, 2, 0, 3, 0), write.payload.map { it.toInt() })
        r.assertWireWasGuarded()
    }

    @Test fun `every mode switches the unit, reads back, and the slots are the carousel's`() = runTest {
        val r = connected()
        val expected = mapOf(
            JblStateMapping.MODE_ANC to C.WIRE_ANC,
            JblStateMapping.MODE_AMBIENT to C.WIRE_AMBIENT,
            JblStateMapping.MODE_OFF to null,
            JblStateMapping.MODE_TALKTHRU to C.WIRE_TALKTHRU,
        )
        for ((slot, wire) in expected) {
            r.repo.setMode(slot)
            runCurrent()
            assertEquals(wire, r.unit.mode, "the unit after slot $slot")
            assertEquals(slot, r.state.currentModeIndex, "the state after slot $slot")
            r.repo.refresh()
            assertEquals(slot, r.state.currentModeIndex, "a refresh after slot $slot")
        }
        val before = r.unit.received.size
        assertFailsWith<HeadphoneCommandException> { r.repo.setMode(4) }
        assertFailsWith<HeadphoneCommandException> { r.repo.setMode(-1) }
        assertEquals(before, r.unit.received.size, "an impossible slot is refused before anything is written")
        r.assertWireWasGuarded()
    }

    @Test fun `with a bud out the buds stay put and the sentence says why`() = runTest {
        val r = connected(VirtualJbl(bothBudsIn = false))
        val e = assertFailsWith<HeadphoneCommandException> { r.repo.setMode(JblStateMapping.MODE_ANC) }
        assertEquals("The headphones stayed in Ambient Aware (JBL earbuds may need both buds in for that)", e.message)
        assertEquals(C.WIRE_AMBIENT, r.unit.mode)
        assertEquals(JblStateMapping.MODE_AMBIENT, r.state.currentModeIndex, "the screen shows what the headset reports")
        assertFalse(r.state.isBusy, "the busy flag came down")
        assertTrue(r.conn.isOpen, "a declined mode is not a dead link")

        // TalkThru is declined the same way; Off needs no second bud.
        assertEquals(
            "The headphones stayed in Ambient Aware (JBL earbuds may need both buds in for that)",
            assertFailsWith<HeadphoneCommandException> { r.repo.setMode(JblStateMapping.MODE_TALKTHRU) }.message,
        )
        r.repo.setMode(JblStateMapping.MODE_OFF)
        assertNull(r.unit.mode)
        assertEquals(JblStateMapping.MODE_OFF, r.state.currentModeIndex)
        r.assertWireWasGuarded()
    }

    // ---- Settings -----------------------------------------------------------------------------

    @Test fun `every EQ preset reaches the unit as a whole EQ frame and reads back`() = runTest {
        val r = connected()
        for ((index, preset) in C.EQ_PRESETS.withIndex().reversed()) {
            r.repo.setVendorControl(JblStateMapping.EQ_PRESET, index)
            assertEquals(preset.category, r.unit.eqCategory, preset.label)
            assertEquals(preset.gainsDb, r.unit.eqGains, "${preset.label}: the ten gains the unit stored")
            assertEquals(index, r.control(JblStateMapping.EQ_PRESET)?.value, "${preset.label}: read back")
            assertFalse(r.state.isBusy)
        }
        val write = r.unit.received.last { it.command == C.EQ && it.longLength }
        assertEquals(C.EQ_SET_SIZE, write.size, "the whole EQ in one frame")
        // Back at Off (the last one written): the unit answers the all-zero frame again.
        assertEquals(C.EQ_OFF, r.unit.eqCategory)
        assertFailsWith<HeadphoneCommandException> { r.repo.setVendorControl(JblStateMapping.EQ_PRESET, C.EQ_PRESETS.size) }
        r.assertWireWasGuarded()
    }

    @Test fun `voice prompts switch off and on at the unit and read back`() = runTest {
        val r = connected()
        r.repo.setVendorControl(JblStateMapping.VOICE_PROMPTS, 0)
        assertFalse(r.unit.voicePrompts)
        assertEquals(0, r.control(JblStateMapping.VOICE_PROMPTS)?.value)
        r.repo.setVendorControl(JblStateMapping.VOICE_PROMPTS, 1)
        assertTrue(r.unit.voicePrompts)
        assertEquals(1, r.control(JblStateMapping.VOICE_PROMPTS)?.value)
        // Not a value a switch takes, and not a setting these buds have: refused, nothing written.
        val before = r.unit.received.size
        assertFailsWith<HeadphoneCommandException> { r.repo.setVendorControl(JblStateMapping.VOICE_PROMPTS, 2) }
        assertFailsWith<HeadphoneCommandException> { r.repo.setVendorControl("power_off", 1) }
        assertEquals(before, r.unit.received.size)
        r.assertWireWasGuarded()
    }

    @Test fun `without an answer to the level read there is no slider and no level write`() = runTest {
        val r = connected()
        assertNull(r.control(JblStateMapping.ANC_LEVEL))
        val before = r.unit.received.size
        val e = assertFailsWith<HeadphoneCommandException> { r.repo.setVendorControl(JblStateMapping.ANC_LEVEL, 4) }
        assertEquals("These JBL headphones have no setting \"anc_level\"", e.message)
        assertEquals(before, r.unit.received.size)
        r.assertWireWasGuarded()
    }

    @Test fun `a unit that answers the level read shows the slider and takes a level`() = runTest {
        val r = connected(VirtualJbl(ancLevel = 4))
        assertEquals(listOf(JblStateMapping.ANC_LEVEL, JblStateMapping.EQ_PRESET, JblStateMapping.VOICE_PROMPTS), r.state.vendorControls.map { it.id })
        val slider = assertNotNull(r.control(JblStateMapping.ANC_LEVEL))
        assertEquals(4, slider.value)
        assertEquals(C.ANC_LEVELS, slider.range)
        assertTrue(slider.onHome, "the level is a quick control on Home")

        r.repo.setVendorControl(JblStateMapping.ANC_LEVEL, 6)
        assertEquals(6, r.unit.ancLevel)
        assertEquals(6, r.control(JblStateMapping.ANC_LEVEL)?.value)
        for (edge in listOf(C.ANC_LEVELS.first, C.ANC_LEVELS.last)) {
            r.repo.setVendorControl(JblStateMapping.ANC_LEVEL, edge)
            assertEquals(edge, r.unit.ancLevel)
            assertEquals(edge, r.control(JblStateMapping.ANC_LEVEL)?.value)
        }
        // Outside 1..7 is not a level these headphones take: refused before the wire.
        val before = r.unit.received.size
        for (bad in listOf(0, 8)) assertFailsWith<HeadphoneCommandException> { r.repo.setVendorControl(JblStateMapping.ANC_LEVEL, bad) }
        assertEquals(before, r.unit.received.size)
        r.assertWireWasGuarded()
    }

    // ---- Pushes -------------------------------------------------------------------------------

    @Test fun `a bud tap and a battery change reach the state without a request`() = runTest {
        val r = connected()
        val sent = r.wire.writes.size

        // The user taps a bud: TalkThru, then Off.
        r.unit.pushMode(C.WIRE_TALKTHRU)
        runCurrent()
        assertEquals(JblStateMapping.MODE_TALKTHRU, r.state.currentModeIndex)
        r.unit.pushMode(null)
        runCurrent()
        assertEquals(JblStateMapping.MODE_OFF, r.state.currentModeIndex)

        // The right bud comes out of its case and the left is charging.
        r.unit.leftCharging = true
        r.unit.pushBattery(left = 42, right = 40, case = 90)
        runCurrent()
        assertEquals(40, r.state.batteryPercent, "the lower bud is the headline")
        assertEquals(BatteryLevel(42), r.state.battery?.left)
        assertEquals(BatteryLevel(40), r.state.battery?.right)
        assertEquals(BatteryLevel(90), r.state.battery?.case)
        assertEquals(true, r.state.isCharging)

        assertEquals(sent, r.wire.writes.size, "pushes needed no request")
        r.assertWireWasGuarded()
    }

    @Test fun `the heartbeat is echoed, each time, with the app's frame`() = runTest {
        val r = connected()
        assertEquals(0, r.unit.heartbeatEchoes)
        repeat(3) {
            r.unit.pushHeartbeat()
            // The echo is a paced write: 450 ms after the last one.
            advanceTimeBy(1_000)
            runCurrent()
        }
        assertEquals(3, r.unit.heartbeatEchoes)
        assertEquals(C.heartbeatEcho().encode().hex(), r.wire.writes.last().hex())
        assertTrue(r.conn.isOpen)
        // A heartbeat does not disturb a command that follows it.
        r.repo.setMode(JblStateMapping.MODE_ANC)
        assertEquals(C.WIRE_ANC, r.unit.mode)
        r.assertWireWasGuarded()
    }

    // ---- Lab ----------------------------------------------------------------------------------

    @Test fun `the Lab profile exists and a deep scan sends the documented reads and nothing else`() = runTest {
        val r = connected()
        val profile = assertNotNull(r.repo.labProfile(), "the Lab sees the session")
        assertEquals("JBL H-AA", profile.stack)
        assertEquals(listOf("Device", "Sound", "Settings"), profile.groups.map { it.name })
        assertEquals(JblReads.ALL.size, profile.groups.sumOf { it.readCount })
        assertEquals(JblReads.ALL.size, profile.deepReadCount)

        val before = settings(r)
        r.unit.received.clear()
        r.wire.writes.clear()
        val readings = r.repo.scanLab(emptyList(), deep = true).toList()

        assertEquals(JblReads.ALL.size, readings.size)
        assertEquals(JblReads.ALL.map { it.frame }, r.unit.received, "each documented read went out once, in the list's order")
        assertTrue(r.unit.received.all { JblWriteGuard.isRead(it) }, "every frame is a read: ${r.unit.received.filterNot { JblWriteGuard.isRead(it) }}")
        assertTrue(r.unit.received.none { it.command in NEVER_SENT })
        // The Wave Buds 2 stays silent on the level and the older battery read; the rest answer.
        val silent = readings.filter { it.frames.single().isError }.map { it.knownName }
        assertEquals(listOf("Battery (older earbuds)", "Noise-cancelling level"), silent)
        assertEquals("no answer", readings.first { it.knownName == "Noise-cancelling level" }.frames.single().errorName)
        assertEquals("12010002010300", readings.first { it.knownName == "Listening mode" }.frames.single().payloadHex)

        assertEquals(before, settings(r), "the scan changed nothing on the unit")
        assertTrue(r.conn.isOpen)
        r.assertWireWasGuarded()
    }

    /** What the unit holds that a write could change. */
    private fun settings(r: Rig) = listOf(r.unit.mode, r.unit.eqCategory, r.unit.eqGains.toList(), r.unit.voicePrompts, r.unit.ancLevel, r.unit.bothBudsIn)

    // ---- Disconnect ---------------------------------------------------------------------------

    @Test fun `closing the transport ends the session and clears the state`() = runTest {
        val r = connected()
        assertTrue(r.state.modes.isNotEmpty())

        r.transport.close()
        withTimeout(10_000) { r.conn.awaitClosed() }
        assertFalse(r.conn.isOpen)
        // The manager publishes the end of the link; the repository drops what it knew.
        r.link.drop()
        runCurrent()

        assertTrue(HeadphoneEvent.Disconnected in r.events)
        assertEquals(ConnectionStatus.DISCONNECTED, r.state.connection.status)
        assertTrue(r.state.modes.isEmpty())
        assertTrue(r.state.vendorControls.isEmpty())
        assertNull(r.state.batteryPercent)
        assertNull(r.state.currentModeIndex)
        assertNull(r.repo.labProfile())
        val written = r.unit.received.size
        assertEquals("Headphones not connected", assertFailsWith<HeadphoneCommandException> { r.repo.setMode(0) }.message)
        assertEquals("Headphones not connected", assertFailsWith<HeadphoneCommandException> { r.repo.refresh() }.message)
        assertFailsWith<HeadphoneCommandException> { r.repo.setVendorControl(JblStateMapping.VOICE_PROMPTS, 0) }
        assertFailsWith<HeadphoneCommandException> { r.repo.scanLab(listOf(1), deep = false).toList() }
        assertEquals(written, r.unit.received.size, "a dead link got no write")
        r.assertWireWasGuarded()
    }

    // ---- The guard ----------------------------------------------------------------------------

    @Test fun `a whole session never puts a frame outside the guard on the wire`() = runTest {
        val r = connected(VirtualJbl(ancLevel = 3))
        for (slot in listOf(0, 3, 2, 1)) r.repo.setMode(slot)
        r.repo.setVendorControl(JblStateMapping.ANC_LEVEL, 5)
        r.repo.setVendorControl(JblStateMapping.EQ_PRESET, 3)
        r.repo.setVendorControl(JblStateMapping.VOICE_PROMPTS, 0)
        r.unit.pushHeartbeat()
        r.unit.pushMode(C.WIRE_ANC)
        advanceTimeBy(1_000)
        runCurrent()
        r.repo.refresh()
        r.repo.scanLab(emptyList(), deep = true).toList()

        // What the repository does not drive is refused in the driver, before the connection.
        val unsupported = JblHeadphoneRepository.UNSUPPORTED
        assertEquals(unsupported, assertFailsWith<HeadphoneCommandException> { r.repo.powerOff() }.message)
        assertEquals(unsupported, assertFailsWith<HeadphoneCommandException> { r.repo.setDeviceName("x") }.message)
        assertEquals(unsupported, assertFailsWith<HeadphoneCommandException> { r.repo.enterPairingMode() }.message)
        assertEquals(unsupported, assertFailsWith<HeadphoneCommandException> { r.repo.setNoise(NoiseSettings(cancellingLevel = 5, ancEnabled = true)) }.message)

        // And what a bug or a hand-built frame could try goes no further than the connection's guard.
        val written = r.wire.writes.size
        for (frame in forbidden()) assertFailsWith<HeadphoneCommandException>("$frame") { r.conn.send(frame) }
        assertEquals(written, r.wire.writes.size, "refused frames never reached the transport")
        assertTrue(r.conn.isOpen)

        assertTrue(r.unit.received.isNotEmpty())
        assertTrue(r.unit.received.none { it.command in NEVER_SENT || it.command in JblWriteGuard.NEVER })
        r.assertWireWasGuarded()
    }

    private fun forbidden(): List<JblFrame> = listOf(
        JblFrame(0x95), // factory reset
        JblFrame(0x97), // power off
        JblFrame(0xF0, byteArrayOf(0x01, 0x00, 0x00, 0x10, 0x00)), // firmware transfer
        JblFrame(C.INFO, byteArrayOf(0x00, 0x00, C.FID_AUTO_OFF.toByte(), 0x03, 0x01, 0x05, 0x00), longLength = true), // TLV set: auto-off
        C.eqSet(C.EQ_CUSTOM, List(C.EQ_BAND_HZ.size) { 1f }), // a custom EQ curve
        JblFrame(C.LISTENING, byteArrayOf(C.MODE_SET.toByte(), 1, 1, 2, 1, 3, 0)), // two modes on
    )

    private companion object {
        /** Factory reset, power off and the firmware transfer: the commands that wipe, switch off or rewrite the headset. */
        val NEVER_SENT = setOf(0x95, 0x97, 0xF0)
    }
}
