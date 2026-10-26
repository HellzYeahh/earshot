package dev.earshot.virtual

import dev.earshot.bt.BmapConnection
import dev.earshot.bt.BragiConnection
import dev.earshot.bt.BragiFrameReader
import dev.earshot.bt.BtLog
import dev.earshot.bt.ByteTransport
import dev.earshot.bt.JblConnection
import dev.earshot.bt.JblLog
import dev.earshot.bt.SennheiserLinkFactory
import dev.earshot.bt.SennheiserLog
import dev.earshot.bt.SonyLinkFactory
import dev.earshot.bt.SonyLog
import dev.earshot.bt.SoundcoreConnection
import dev.earshot.core.HeadphoneState
import dev.earshot.core.LabReading
import dev.earshot.protocol.BmapFrameBuffer
import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.BmapPacket
import dev.earshot.protocol.bragi.BosMessageType
import dev.earshot.protocol.bragi.BosRpcPacket
import dev.earshot.protocol.bragi.BragiModel
import dev.earshot.protocol.bragi.BragiReads
import dev.earshot.protocol.bragi.BragiServices
import dev.earshot.protocol.gaia.GaiaCommand
import dev.earshot.protocol.gaia.GaiaFrame
import dev.earshot.protocol.gaia.GaiaFrameBuffer
import dev.earshot.protocol.gaia.GaiaVendor
import dev.earshot.protocol.jbl.JblReads
import dev.earshot.protocol.jbl.JblWriteGuard
import dev.earshot.protocol.sennheiser.SennheiserCommands
import dev.earshot.protocol.sennheiser.SennheiserReads
import dev.earshot.protocol.sennheiser.SennheiserWriteGuard
import dev.earshot.protocol.sony.SonyMessage
import dev.earshot.protocol.sony.SonyReads
import dev.earshot.protocol.sony.SonyTable
import dev.earshot.protocol.sony.T1
import dev.earshot.protocol.sony.T2
import dev.earshot.protocol.sony.TandemFrameBuffer
import dev.earshot.protocol.soundcore.SoundcoreFrameBuffer
import dev.earshot.protocol.soundcore.SoundcoreModel
import dev.earshot.protocol.soundcore.SoundcorePacket
import dev.earshot.protocol.soundcore.SoundcoreReads
import dev.earshot.protocol.soundcore.SoundcoreWriteGuard
import dev.earshot.service.CrusherProtocol
import dev.earshot.service.ProtocolLog
import dev.earshot.service.SonySession
import dev.earshot.service.SennheiserSession
import dev.earshot.service.lab.BmapLab
import dev.earshot.service.lab.BragiLab
import dev.earshot.service.lab.JblLab
import dev.earshot.service.lab.SennheiserLab
import dev.earshot.service.lab.SonyLab
import dev.earshot.service.lab.SoundcoreLab
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Lab scanner of every stack against its virtual device, through the real connection classes:
 * a Deep scan completes and returns readings, and a spy on the transport proves that nothing but
 * the stack's documented reads left the phone (none of the ids its write guard blocks, no write of
 * any kind). Demo mode runs these same scanners against these same virtual devices.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LabScanEndToEndTest {

    @Before
    fun quiet() {
        BtLog.sink = { _, _, _ -> }
        SennheiserLog.sink = { _, _, _ -> }
        SonyLog.sink = { _, _, _ -> }
        JblLog.sink = { _, _, _ -> }
        ProtocolLog.sink = { _, _, _ -> }
    }

    /** The virtual device's own transport, recording every byte the phone writes. */
    private class SpyTransport(private val inner: ByteTransport) : ByteTransport {
        private val written = mutableListOf<ByteArray>()
        override val isConnected: Boolean get() = inner.isConnected
        override val incoming = inner.incoming
        override suspend fun write(bytes: ByteArray) {
            synchronized(written) { written += bytes.copyOf() }
            inner.write(bytes)
        }
        override fun close() = inner.close()
        fun clear() = synchronized(written) { written.clear() }
        fun all(): ByteArray = synchronized(written) { written.fold(ByteArray(0)) { a, b -> a + b } }
    }

    private suspend fun Flow<LabReading>.collectAll(): List<LabReading> = toList()

    private fun assertReadings(readings: List<LabReading>, expected: Int) {
        assertEquals(expected, readings.size)
        assertTrue(readings.all { it.frames.isNotEmpty() || it.address == null }, "every non-BMAP read reports a frame (reply or why not)")
        assertTrue(readings.any { r -> r.frames.any { !it.isError } }, "the virtual device answered")
    }

    // ---- Bose (BMAP) ----------------------------------------------------------------------

    @Test fun `Bose deep scan sweeps 64 x 64 GETs and sends nothing else`() = runTest {
        val spy = SpyTransport(VirtualTransport(VirtualQcUltra2(), replyDelayMs = 1L..1L, tickIntervalMs = 3_600_000L, random = Random(1)))
        val conn = BmapConnection(spy, backgroundScope, minWriteGapMs = 0)
        testScheduler.runCurrent()

        val readings = BmapLab.scan(conn, emptyList(), deep = true).collectAll()
        assertEquals(64 * 64, readings.size)
        assertTrue(readings.first { it.block == 2 && it.function == 2 }.frames.single().operatorName == "STATUS", "battery answered")
        assertEquals("Settings", readings.first { it.block == 1 }.groupName)

        val sent = BmapFrameBuffer().feed(spy.all())
        assertEquals(64 * 64, sent.size)
        assertTrue(sent.all { it.operator == BmapOperator.GET }, "GET only: ${sent.filter { it.operator != BmapOperator.GET }}")
        assertEquals((0..63).flatMap { b -> (0..63).map { b to it } }, sent.map { it.block to it.function })
        conn.close()
    }

    @Test fun `Bose scan reads functions 0 to 40 of the selected blocks`() = runTest {
        val spy = SpyTransport(VirtualTransport(VirtualQcUltra2(), replyDelayMs = 1L..1L, tickIntervalMs = 3_600_000L, random = Random(1)))
        val conn = BmapConnection(spy, backgroundScope, minWriteGapMs = 0)
        testScheduler.runCurrent()
        val profile = BmapLab.profile()
        assertTrue(profile.printConsole)
        assertEquals(setOf(1, 2, 31), profile.defaultGroups)
        assertEquals(4096, profile.deepReadCount)

        val readings = BmapLab.scan(conn, listOf(1, 31), deep = false).collectAll()
        assertEquals(2 * 41, readings.size)
        assertEquals(setOf(1, 31), readings.map { it.block }.toSet())
        assertTrue(BmapFrameBuffer().feed(spy.all()).all { it.operator == BmapOperator.GET })
        conn.close()
    }

    // ---- Soundcore --------------------------------------------------------------------------

    private fun soundcoreSent(spy: SpyTransport): List<SoundcorePacket> = SoundcoreFrameBuffer().feed(spy.all())

    @Test fun `Sleep A30 deep scan sends its five reads and nothing else`() = runTest {
        val unit = VirtualSleepA30()
        val spy = SpyTransport(VirtualSleepA30Transport(unit))
        val conn = SoundcoreConnection(spy, backgroundScope, minWriteGapMs = 0, model = SoundcoreModel.SLEEP_A30)
        testScheduler.runCurrent()

        val profile = SoundcoreLab.profile(SoundcoreModel.SLEEP_A30)
        assertEquals(5, profile.deepReadCount)
        assertEquals(listOf(0x01, 0x05, 0x14, 0x15), profile.groups.map { it.id })

        val readings = SoundcoreLab.scan(conn, emptyList(), deep = true).collectAll()
        assertReadings(readings, 5)
        val state = readings.first { it.function == 0x0101 }.frames.single()
        assertFalse(state.isError)
        assertEquals(300, state.payloadHex.length, "the 150-byte state")
        assertEquals("01 01", readings.first().address)

        val sent = soundcoreSent(spy)
        assertEquals(listOf(0x0101, 0x0105, 0x0501, 0x1401, 0x1503), sent.map { it.command }, "one try each, in order")
        for (p in sent) {
            assertFalse(p.isWrite)
            assertFalse(p.command in SoundcoreWriteGuard.NEVER)
            assertTrue(SoundcoreReads.isRead(SoundcoreModel.SLEEP_A30, p.command))
        }
        assertEquals(sent.map { it.command }, unit.received.map { it.command })
        conn.close()
    }

    @Test fun `Soundcore table models scan only what their allow-list reads`() = runTest {
        for (model in listOf(SoundcoreModel.LIBERTY_4_NC, SoundcoreModel.SPACE_ONE_PRO, SoundcoreModel.LIBERTY_5)) {
            val unit = VirtualSoundcore(model)
            val spy = SpyTransport(VirtualSoundcoreTransport(unit))
            val conn = SoundcoreConnection(spy, backgroundScope, minWriteGapMs = 0, model = model)
            testScheduler.runCurrent()

            val expected = SoundcoreReads.forModel(model).map { it.command }
            val readings = SoundcoreLab.scan(conn, emptyList(), deep = true).collectAll()
            assertReadings(readings, expected.size)
            assertFalse(readings.first { it.function == 0x0101 }.frames.single().isError, "${model.code} state answered")

            val sent = soundcoreSent(spy)
            assertEquals(expected, sent.map { it.command }, model.code)
            assertTrue(sent.none { it.isWrite || it.command in SoundcoreWriteGuard.NEVER }, model.code)
            assertTrue(sent.all { SoundcoreWriteGuard.isAllowed(model, it) }, model.code)

            // Scan with one group: only that category.
            spy.clear()
            val device = SoundcoreLab.scan(conn, listOf(0x01), deep = false).collectAll()
            assertTrue(device.all { it.block == 0x01 })
            assertTrue(soundcoreSent(spy).all { it.category == 0x01 })
            conn.close()
        }
    }

    // ---- Sennheiser -------------------------------------------------------------------------

    @Test fun `Sennheiser deep scan runs discovery then the reported features' GETs only`() = runTest {
        val unit = VirtualSennheiser()
        val spy = SpyTransport(VirtualSennheiserTransport(unit))
        val conn = SennheiserLinkFactory().adopt(spy, backgroundScope)
        val session = SennheiserSession(conn, "MOMENTUM 4")
        backgroundScope.launch { conn.pushes.collect { session.onPush(it) } }
        testScheduler.runCurrent()
        session.start()
        testScheduler.runCurrent()
        spy.clear()

        val profile = SennheiserLab.profile(conn.features)
        assertEquals(23, profile.deepReadCount)
        assertEquals(listOf(0, 2, 3, 4, 8, 9, 12, 13), profile.groups.map { it.id })
        assertTrue(profile.note!!.contains("10 Device management"), profile.note)

        val readings = SennheiserLab.scan(conn, emptyList(), deep = true).collectAll()
        assertReadings(readings, 23)
        assertTrue(readings.all { r -> r.frames.none { it.isError } }, "the Momentum 4 answers every documented GET: ${readings.filter { r -> r.frames.any { it.isError } }.map { it.address }}")
        assertEquals("0495/0603", readings.first { it.knownName == "Battery" }.address)

        val sent: List<GaiaFrame> = GaiaFrameBuffer().feed(spy.all())
        assertEquals(23, sent.size)
        val allowed = SennheiserReads.forFeatures(conn.features).map { Triple(it.vendor, it.command, it.payload.toList()) }.toSet()
        for (f in sent) {
            assertEquals(GaiaCommand.TYPE_COMMAND, f.type)
            assertTrue(Triple(f.vendor, f.command, f.payload.toList()) in allowed, "$f")
            if (f.vendor == SennheiserCommands.VENDOR) {
                assertFalse(f.command in SennheiserWriteGuard.BLOCKED_SENNHEISER)
                assertFalse(GaiaCommand.feature(f.command) in SennheiserWriteGuard.BLOCKED_SENNHEISER_FEATURES)
            } else {
                assertEquals(GaiaVendor.QUALCOMM, f.vendor)
                assertFalse(f.command in SennheiserWriteGuard.BLOCKED_QUALCOMM)
            }
        }
        // Nothing the Lab sent changed the headset.
        assertTrue(unit.ancOn)
        conn.close()
    }

    // ---- Bragi (Crusher 1080, Method 360 / 540) ---------------------------------------------

    private fun bragiSent(spy: SpyTransport): List<BosRpcPacket> =
        BragiFrameReader().feed(spy.all()).map { BosRpcPacket.decode(it.body) }

    @Test fun `Bragi deep scan sends the read-only calls the product has, and no other call`() = runTest {
        val cases = listOf(
            VirtualCrusher() to BragiModel.SKULLCANDY_CRUSHER_1080,
            VirtualCrusher.method360() to BragiModel.SKULLCANDY_METHOD_360,
            VirtualCrusher.method540() to BragiModel.SKULLCANDY_METHOD_540,
        )
        for ((device, model) in cases) {
            val spy = SpyTransport(VirtualCrusherTransport(device))
            val conn = BragiConnection(spy, backgroundScope, clock = { testScheduler.currentTime })
            val protocol = CrusherProtocol()
            protocol.onConnected(conn, HeadphoneState())
            assertEquals(model, protocol.currentModel)
            spy.clear()

            val expected = BragiReads.forFeatures(model.features)
            assertEquals(expected.size, BragiLab.profile(model).deepReadCount)
            val readings = BragiLab.scan(conn, protocol.currentModel, emptyList(), deep = true).collectAll()
            assertReadings(readings, expected.size)
            assertFalse(readings.first { it.knownName == "device.batt_info" }.frames.single().isError)

            val sent = bragiSent(spy)
            assertEquals(expected.map { it.address }, sent.map { it.address }, model.name)
            for (p in sent) {
                assertEquals(BosMessageType.REQUEST, p.type)
                assertTrue(BragiServices.isRead(p.address), "${p.address}")
                assertTrue(p.address.service != BragiServices.AUTHENTICATED_ACCESS)
            }
            if (model != BragiModel.SKULLCANDY_CRUSHER_1080) {
                assertTrue(sent.none { it.address.service == BragiServices.CRUSHER || it.address.service == BragiServices.SPATIAL_AUDIO }, model.name)
            }
            conn.close()
        }
    }

    // ---- Sony -------------------------------------------------------------------------------

    @Test fun `Sony deep scan sends GET inquiries for the announced functions only`() = runTest {
        val unit = VirtualSony()
        val factory = SonyLinkFactory()
        val spy = SpyTransport(VirtualSonyTransport(unit))
        val conn = factory.adopt(spy, backgroundScope)
        factory.probe(conn)
        val session = SonySession(conn, clock = { testScheduler.currentTime })
        backgroundScope.launch { conn.unsolicited.collect { session.onPush(it) } }
        session.initialize()
        spy.clear()
        fun settings() = listOf(
            unit.ncEffect, unit.ncMode, unit.focusOnVoice, unit.ambientLevel, unit.eqPreset, unit.eqBands.toList(), unit.autoOff,
            unit.multipoint, unit.touchPanel, unit.dsee, unit.connectionMode, unit.speakToChat, unit.pauseWhenTakenOff, unit.voiceGuidance,
        )
        val before = settings()

        val profile = SonyLab.profile(session.snapshot)
        assertEquals(26, profile.deepReadCount)
        val readings = SonyLab.scan(conn, session.snapshot, emptyList(), deep = true).collectAll()
        assertReadings(readings, 26)
        assertFalse(readings.first { it.address == "T1 66 17" }.frames.single().isError, "NCASM answered")

        val sent = TandemFrameBuffer().feed(spy.all()).filter { !it.isAck }.map { f ->
            SonyMessage(SonyTable.forDataType(f.dataType)!!, f.payload)
        }
        assertEquals(26, sent.size)
        val forbiddenT1 = T1.UPDT.toSet() + setOf(T1.UPDT_TRANSFER_DATA, T1.UPDT_NTFY_MESSAGE, T1.GET_TEST, T1.LOG_SET_STATUS, T1.LOG_NTFY_PARAM)
        for (m in sent) {
            assertTrue(SonyReads.isRead(m), "$m")
            if (m.table == SonyTable.T1) {
                assertTrue(m.command in SonyReads.T1_GETS && m.command !in forbiddenT1, "$m")
            } else {
                assertTrue(m.command in SonyReads.T2_GETS && m.command != T2.PERI_SET_EXTENDED_PARAM, "$m")
            }
        }
        // The headset is as it was: no setting changed, still on.
        assertEquals(before, settings())
        assertFalse(unit.poweredOff)
        conn.close()
    }

    // ---- JBL (H-AA, Wave Buds 2) ---------------------------------------------------------------

    @Test fun `JBL deep scan sends the documented reads once each and no write`() = runTest {
        val unit = VirtualJbl()
        val spy = SpyTransport(VirtualJblTransport(unit))
        // JblLinkFactory needs an Android Context; the connection is what the factory's adopt() builds.
        val conn = JblConnection(spy, backgroundScope, clock = { testScheduler.currentTime })
        testScheduler.runCurrent()

        val profile = JblLab.profile()
        assertEquals("JBL H-AA", profile.stack)
        assertEquals(JblReads.ALL.size, profile.deepReadCount)
        assertEquals(listOf("Device", "Sound", "Settings"), profile.groups.map { it.name })
        val before = listOf(unit.mode, unit.eqCategory, unit.eqGains.toList(), unit.voicePrompts, unit.ancLevel)

        val readings = JblLab.scan(conn, emptyList(), deep = true).collectAll()
        assertReadings(readings, JblReads.ALL.size)
        // The Wave Buds 2 never answers the level read or the older-earbuds battery read: "no answer", not an error that closes the link.
        assertEquals(listOf("Battery (older earbuds)", "Noise-cancelling level"), readings.filter { r -> r.frames.single().isError }.map { it.knownName })
        assertFalse(readings.first { it.knownName == "Listening mode" }.frames.single().isError)
        assertEquals("91 11", readings.first { it.knownName == "Listening mode" }.address)

        // The frames the unit decoded are the documented reads in order, byte for byte what left the phone, each a read for the guard.
        assertEquals(JblReads.ALL.map { it.frame }, unit.received)
        assertTrue(spy.all().contentEquals(unit.received.fold(ByteArray(0)) { a, f -> a + f.encode() }), "every byte on the wire was one of those frames")
        for (f in unit.received) {
            assertTrue(JblWriteGuard.isRead(f), "$f")
            assertFalse(f.command in JblWriteGuard.NEVER, "$f")
        }
        assertEquals(before, listOf(unit.mode, unit.eqCategory, unit.eqGains.toList(), unit.voicePrompts, unit.ancLevel), "nothing the Lab sent changed the unit")
        assertTrue(conn.isOpen)

        // One group: only that group's reads.
        spy.clear()
        unit.received.clear()
        val sound = JblLab.scan(conn, listOf(JblReads.GROUP_SOUND), deep = false).collectAll()
        assertTrue(sound.all { it.block == JblReads.GROUP_SOUND })
        assertEquals(JblReads.ALL.filter { it.group == JblReads.GROUP_SOUND }.map { it.frame }, unit.received)
        conn.close()
    }
}
