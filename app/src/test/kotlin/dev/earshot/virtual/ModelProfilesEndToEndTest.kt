package dev.earshot.virtual

import dev.earshot.bt.BmapRequester
import dev.earshot.bt.BtLog
import dev.earshot.bt.ReplyMode
import dev.earshot.core.BatteryLevel
import dev.earshot.core.FormFactor
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneEq
import dev.earshot.core.HeadphoneState
import dev.earshot.core.NoiseSettings
import dev.earshot.core.SpatialAudio
import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.BmapPacket
import dev.earshot.protocol.qcultra2.ModeConfigs
import dev.earshot.protocol.qcultra2.QcUltra2
import dev.earshot.service.ProtocolLog
import dev.earshot.service.QcUltra2Protocol
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real [QcUltra2Protocol] against each [VirtualProfile]: what the connected state says about
 * the product (family, generation, form factor, support note), its modes and their names, the
 * noise path, the battery detail and auto-off. docs/bose-models.md §A, §E.
 */
class ModelProfilesEndToEndTest {

    /** The connection's reply matching without the socket and the timing; silence is a timeout. */
    private class DirectRequester(val device: VirtualQcUltra2) : BmapRequester {
        override val isOpen: Boolean get() = device.isPoweredOn
        val sent = ArrayList<BmapPacket>()

        override suspend fun request(packet: BmapPacket, mode: ReplyMode, timeoutMs: Long): List<BmapPacket> {
            if (!isOpen) throw HeadphoneCommandException("Headphones not connected")
            sent += packet
            val frames = device.handle(packet)
            device.drainDeferred()
            return when (mode) {
                ReplyMode.SINGLE -> frames.firstOrNull { it.matches(packet.address) && it.operator != BmapOperator.PROCESSING }?.let { listOf(it) }
                ReplyMode.MULTI -> frames.filter { it.block == packet.block }.ifEmpty { null }
            } ?: throw HeadphoneCommandException("${packet.address}: no reply from the headphones")
        }

        override suspend fun send(packet: BmapPacket) {
            sent += packet
            device.handle(packet)
        }

        fun writesTo(address: dev.earshot.protocol.FunctionAddress) = sent.filter { it.matches(address) && it.operator == BmapOperator.SET_GET }
    }

    private class Session(val device: VirtualQcUltra2, val r: DirectRequester, val protocol: QcUltra2Protocol, var state: HeadphoneState)

    private suspend fun connect(profile: VirtualProfile): Session {
        val device = VirtualQcUltra2(profile = profile)
        val r = DirectRequester(device)
        val protocol = QcUltra2Protocol()
        var s = protocol.onConnected(r, HeadphoneState())
        for (frame in BmapPacket.decodeAll(device.greeting())) s = protocol.onUnsolicited(frame, s) ?: s
        s = protocol.refresh(r, s)
        return Session(device, r, protocol, s)
    }

    @Before
    fun silenceLog() {
        ProtocolLog.sink = { _, _, _ -> }
        BtLog.sink = { _, _, _ -> }
    }

    @Test
    fun `QC Ultra Headphones 2nd Gen keep everything they had`() = runTest {
        val s = connect(VirtualProfile.QC_ULTRA_2).state
        assertEquals("QuietComfort Ultra Headphones", s.info?.family)
        assertEquals("2nd Gen", s.info?.generation)
        assertEquals(FormFactor.HEADPHONES, s.capabilities.formFactor)
        assertNull(s.capabilities.supportNote)
        assertEquals(11, s.modes.size)
        assertEquals(listOf("Quiet", "Aware", "Immersion", "Cinema", "Home"), s.modes.take(5).map { it.name })
        assertTrue(s.capabilities.liveNoiseControl && s.capabilities.ancToggle && s.capabilities.windBlock && s.capabilities.spatialAudio)
        assertEquals(BatteryLevel(72), s.battery?.overall)
        assertNull(s.battery?.left)
        assertEquals(72, s.batteryPercent)
        assertEquals(-10..10, s.eqRange)
        assertNull(s.autoOffMinutes, "[1.4] is FuncNotSupp on fw 10.12")
    }

    @Test
    fun `QC Ultra Earbuds 2nd Gen report each bud and the case`() = runTest {
        val session = connect(VirtualProfile.QC_ULTRA_EARBUDS_2)
        val s = session.state
        assertEquals("QuietComfort Ultra Earbuds (2nd Gen)", s.info?.modelName)
        assertEquals("QuietComfort Ultra Earbuds", s.info?.family)
        assertEquals("2nd Gen", s.info?.generation)
        assertEquals(FormFactor.EARBUDS, s.capabilities.formFactor)
        assertEquals(listOf("Quiet", "Aware", "Immersion", "Cinema", "Commute"), s.modes.take(5).map { it.name })
        assertTrue(s.capabilities.liveNoiseControl)
        assertEquals(BatteryLevel(72), s.battery?.right)
        assertEquals(BatteryLevel(69), s.battery?.left)
        assertEquals(BatteryLevel(80), s.battery?.case)
        assertEquals(BatteryLevel(69), s.battery?.overall, "id 4 combined, with no id 0")
        assertEquals(69, s.batteryPercent, "headline: the combined record")
        assertNull(s.capabilities.supportNote)
    }

    @Test
    fun `QC Earbuds II write noise into the active custom mode with the 37-byte layout`() = runTest {
        val session = connect(VirtualProfile.QC_EARBUDS_2)
        var s = session.state
        assertEquals("QuietComfort Earbuds II", s.info?.family)
        assertNull(s.info?.generation)
        assertEquals(FormFactor.EARBUDS, s.capabilities.formFactor)
        assertEquals(4, s.modes.size, "[31.2] 2 + 2")
        assertEquals(listOf("Quiet", "Aware", "Music"), s.modes.take(3).map { it.name })
        assertFalse(s.modes[3].isConfigured)
        assertTrue(s.modes[2].isEditable && !s.modes[1].isEditable)
        assertFalse(s.capabilities.liveNoiseControl)
        assertFalse(s.capabilities.spatialAudio || s.capabilities.windBlock || s.capabilities.ancToggle)
        assertTrue(s.capabilities.editableModes)
        assertEquals(BatteryLevel(72, minutesLeft = 216), s.battery?.right)
        assertEquals(20, s.autoOffMinutes)
        assertTrue(session.r.sent.none { it.matches(QcUltra2.AUDIO_SETTINGS) }, "known absent")

        s = session.protocol.setMode(session.r, s, 2)
        s = session.protocol.setNoise(session.r, s, NoiseSettings(cancellingLevel = 4))
        assertEquals(37, session.r.writesTo(QcUltra2.MODE_CONFIG).single().payload.size)
        assertEquals(6, ModeConfigs.parse(session.device.slots[2])!!.cncWire) // level 4 → wire 6
        assertEquals("Music", s.modes[2].name, "the slot keeps its prompt")
        assertEquals(4, s.noise?.cancellingLevel)
    }

    @Test
    fun `QC Earbuds 2020 set the level with 1_5 and save modes with the untested 37-byte write`() = runTest {
        val session = connect(VirtualProfile.QC_EARBUDS)
        var s = session.state
        assertEquals("QuietComfort Earbuds", s.info?.modelName)
        assertEquals(FormFactor.EARBUDS, s.capabilities.formFactor)
        assertTrue(s.capabilities.supportNote.orEmpty().startsWith("Basic support"))
        assertTrue(s.capabilities.listeningModes)
        assertTrue(s.capabilities.liveNoiseControl, "direct [1.5]")
        assertTrue(s.capabilities.ancToggle, "[1.5] flags bit 1 clear")
        assertFalse(s.capabilities.windBlock || s.capabilities.spatialAudio)
        assertEquals(listOf("Quiet", "Aware", "", ""), s.modes.map { it.name })
        assertEquals(10, s.noise?.cancellingLevel, "Quiet: step 0")
        assertNull(s.autoOffMinutes)
        assertTrue(session.r.sent.none { it.matches(QcUltra2.AUTO_PLAY_PAUSE) }, "known absent on lando")

        s = session.protocol.setNoise(session.r, s, NoiseSettings(cancellingLevel = 4, ancEnabled = true))
        assertEquals(listOf(6, 1), session.r.writesTo(QcUltra2.CNC).single().payload.map { it.toInt() })
        assertEquals(6, session.device.live.cncWire)
        assertEquals(4, s.noise?.cancellingLevel)

        s = session.protocol.setMode(session.r, s, 1) // Aware: step 10
        assertEquals(0, s.noise?.cancellingLevel, "re-read from [1.5] after the switch")

        s = session.protocol.saveCustomMode(session.r, s, 3, "Run", NoiseSettings(cancellingLevel = 7))
        assertEquals(37, session.r.writesTo(QcUltra2.MODE_CONFIG).single().payload.size)
        assertEquals("Run", s.modes[3].name)
        assertEquals(3, ModeConfigs.parse(session.device.slots[3])!!.cncWire)
    }

    @Test
    fun `QC45 keeps the 47-byte slots, wind and the auto-off timer`() = runTest {
        val session = connect(VirtualProfile.QC45)
        var s = session.state
        assertEquals("QuietComfort 45", s.info?.family)
        assertEquals("QuietComfort 45 / QC SE", s.info?.modelName)
        assertEquals(listOf("Quiet", "Aware", "Commute"), s.modes.take(3).map { it.name })
        assertFalse(s.capabilities.liveNoiseControl)
        assertFalse(s.capabilities.ancToggle)
        assertTrue(s.capabilities.ancAlwaysOn)
        assertTrue(s.capabilities.windBlock)
        assertTrue(s.capabilities.windExcludesCancelling)
        assertEquals(20, s.autoOffMinutes)

        s = session.protocol.setMode(session.r, s, 2)
        s = session.protocol.setNoise(session.r, s, NoiseSettings(cancellingLevel = 3, windBlock = true))
        assertEquals(39, session.r.writesTo(QcUltra2.MODE_CONFIG).single().payload.size)
        assertEquals(10, s.noise?.cancellingLevel, "wind on: the QC45 stores CNC 0")
        assertTrue(s.noise!!.windBlock)

        s = session.protocol.setAutoOff(session.r, s, 1440)
        assertEquals(1440, s.autoOffMinutes)
        assertEquals(1440, session.device.standbyMinutes)
        assertEquals(listOf(0xa0, 0x05), session.r.writesTo(QcUltra2.STANDBY_TIMER).single().payload.map { it.toInt() and 0xFF })
        s = session.protocol.setAutoOff(session.r, s, 0)
        assertEquals(0, s.autoOffMinutes, "never")
    }

    @Test
    fun `NC 700 has no modes and writes the level with 1_5`() = runTest {
        val session = connect(VirtualProfile.NC700)
        var s = session.state
        assertEquals("Noise Cancelling Headphones 700", s.info?.family)
        assertEquals(FormFactor.HEADPHONES, s.capabilities.formFactor)
        assertTrue(s.capabilities.supportNote.orEmpty().contains("no listening modes"))
        assertFalse(s.capabilities.listeningModes)
        assertTrue(s.modes.isEmpty())
        assertTrue(s.capabilities.liveNoiseControl)
        assertEquals(60, s.autoOffMinutes)
        assertEquals(0, session.device.requestsToBlock(31), "not in [0.2]: never asked")
        assertEquals(HeadphoneEq(0, -2, -6), s.eq)

        s = session.protocol.setNoise(session.r, s, NoiseSettings(cancellingLevel = 3))
        assertEquals(7, session.device.live.cncWire)
        assertEquals(3, s.noise?.cancellingLevel)
        val e = assertFailsWith<HeadphoneCommandException> { session.protocol.setMode(session.r, s, 1) }
        assertEquals(QcUltra2Protocol.MODES_UNAVAILABLE_MESSAGE, e.message)
    }

    @Test
    fun `Ultra Open Earbuds have Stereo and Immersion and no noise cancelling`() = runTest {
        val session = connect(VirtualProfile.ULTRA_OPEN)
        var s = session.state
        assertEquals("Ultra Open Earbuds", s.info?.family)
        assertNull(s.info?.generation)
        assertEquals(FormFactor.OPEN_EARBUDS, s.capabilities.formFactor)
        assertFalse(s.capabilities.noiseCancelling)
        assertFalse(s.capabilities.windBlock || s.capabilities.ancToggle)
        assertTrue(s.capabilities.spatialAudio, "a mode setting in the 45-byte slots")
        assertEquals(listOf("Stereo", "Immersion"), s.modes.filter { it.isConfigured }.map { it.name })
        assertTrue(s.modes.take(2).all { it.isPreset })
        assertEquals(4, s.modes.size)
        assertTrue(session.r.sent.none { it.matches(QcUltra2.CNC) || it.matches(QcUltra2.AUDIO_SETTINGS) })
        assertNotNull(s.battery?.case)

        s = session.protocol.setMode(session.r, s, 1)
        assertEquals(SpatialAudio.HEAD, s.noise?.spatialAudio, "Immersion's slot")
        // Custom slots open up once [31.2] and the 45-byte layout are known (untested on hardware).
        assertTrue(s.capabilities.editableModes)
        s = session.protocol.saveCustomMode(session.r, s, 2, "Walk", NoiseSettings(spatialAudio = SpatialAudio.ROOM))
        assertEquals(39, session.r.writesTo(QcUltra2.MODE_CONFIG).single().payload.size)
        assertEquals("Walk", s.modes[2].name)
        assertEquals(SpatialAudio.ROOM, s.modes[2].settings?.spatialAudio)
    }

    @Test
    fun `QuietComfort 35 II is recognised, shows the battery and nothing is written`() = runTest {
        val session = connect(VirtualProfile.QC35_II)
        val s = session.state
        assertEquals("QuietComfort 35 II", s.info?.modelName)
        assertEquals("QuietComfort 35 II", s.info?.family)
        assertTrue(s.capabilities.supportNote.orEmpty().contains("Bose Connect"))
        assertEquals(72, s.batteryPercent)
        assertEquals("4.8.1", s.info?.firmwareVersion)
        assertFalse(s.livePushEnabled, "the repository polls the battery")
        with(s.capabilities) {
            assertFalse(listeningModes || liveNoiseControl || noiseCancelling || editableModes || favoriteModes || spatialAudio || windBlock)
        }
        assertTrue(s.modes.isEmpty())
        assertNull(s.noise)
        // Only ProductInfo and the battery were asked.
        assertEquals(session.r.sent.map { it.address }.filter { it.block !in setOf(0, 2) }, emptyList())
        assertEquals(1, session.r.sent.count { it.address.block == 2 })

        val e = assertFailsWith<HeadphoneCommandException> { session.protocol.setEq(session.r, s, HeadphoneEq(bass = 3)) }
        assertEquals(s.capabilities.supportNote, e.message)
        assertFailsWith<HeadphoneCommandException> { session.protocol.setNoise(session.r, s, NoiseSettings()) }
        assertTrue(session.r.sent.none { it.operator != BmapOperator.GET })
        assertEquals(72, session.protocol.pollBattery(session.r, s).batteryPercent)
    }

    @Test
    fun `every Bose demo device has a virtual profile and QC Ultra stays the default`() {
        assertEquals(DemoDevice.QC_ULTRA, DemoDevice.entries.first())
        for (d in DemoDevice.entries) {
            // Bose demo devices have a virtual BMAP unit; the Crusher, Soundcore and generic ones (AirPods) don't.
            assertEquals(!d.isCrusher && !d.isGeneric && !d.isSoundcore && !d.isSennheiser, d.virtualProfile() != null, d.name)
            assertEquals(d.isGeneric, d.demoHeadset() != null, d.name)
            assertTrue(d.displayName.isNotBlank())
        }
        assertEquals(VirtualProfile.QC_ULTRA_2, DemoDevice.QC_ULTRA.virtualProfile())
    }
}
