package dev.earshot.virtual

import dev.earshot.protocol.qcultra2.Notifications
import dev.earshot.core.TransportControl
import dev.earshot.core.PlaybackState
import dev.earshot.core.Playback
import dev.earshot.core.OperationMode
import dev.earshot.core.ModeSetting
import dev.earshot.core.LeAudioPreference
import dev.earshot.core.BluetoothProfile
import dev.earshot.bt.BmapRequester
import dev.earshot.bt.ReplyMode
import dev.earshot.core.ButtonAction
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneEq
import dev.earshot.core.HeadphoneState
import dev.earshot.core.NoiseSettings
import dev.earshot.core.Sidetone
import dev.earshot.core.SpatialAudio
import dev.earshot.protocol.BmapException
import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.BmapPacket
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
import kotlin.test.assertTrue

/**
 * [QcUltra2Protocol] against [VirtualQcUltra2] through a minimal requester: the typed layer and
 * the simulator must agree on every byte layout for this to pass.
 */
class VirtualEndToEndTest {

    /** What [dev.earshot.bt.BmapConnection] does with the frames, minus the socket and the timing. */
    private class VirtualRequester(val device: VirtualQcUltra2) : BmapRequester {
        override val isOpen: Boolean get() = device.isPoweredOn
        val unsolicited = ArrayList<BmapPacket>()

        override suspend fun request(packet: BmapPacket, mode: ReplyMode, timeoutMs: Long): List<BmapPacket> {
            if (!isOpen) throw HeadphoneCommandException("Headphones not connected")
            val frames = device.handle(packet)
            val mine = when (mode) {
                ReplyMode.SINGLE -> frames.firstOrNull { it.matches(packet.address) && it.operator != BmapOperator.PROCESSING }?.let { listOf(it) }
                ReplyMode.MULTI -> frames.filter { it.block == packet.block }.ifEmpty { null }
            } ?: throw HeadphoneCommandException("${packet.address}: no reply from the headphones")
            unsolicited += device.drainDeferred()
            return mine
        }

        /** Pushes queued by the device outside a request (button presses and the like). */
        fun drain(): List<BmapPacket> = device.drainDeferred().also { unsolicited += it }

        override suspend fun send(packet: BmapPacket) {
            if (!isOpen) throw HeadphoneCommandException("Headphones not connected")
            device.handle(packet)
        }
    }

    private val device = VirtualQcUltra2()
    private val requester = VirtualRequester(device)
    private val protocol = QcUltra2Protocol(localAddress = { "C4:0F:08:11:22:33" })

    @Before
    fun silenceLog() {
        ProtocolLog.sink = { _, _, _ -> }
    }

    @Test
    fun `refresh reads the whole virtual unit into HeadphoneState`() = runTest {
        val connected = protocol.onConnected(requester, HeadphoneState())
        assertTrue(connected.livePushEnabled)
        assertEquals(Notifications.WOLVERINE_BLOCKS, device.notifyBlocks)
        assertEquals(LeAudioPreference(supported = true, preferred = false, prohibited = false), connected.lePreference)
        val s = protocol.refresh(requester, connected)

        assertEquals(72, s.batteryPercent)
        assertEquals(false, s.isCharging) // [2.5] ChargerDetect
        assertEquals(true, s.micMuted)
        assertEquals(OperationMode.NORMAL, s.operationMode)
        assertEquals("10.12.12+ga927c9a", s.info?.firmwareVersion)
        assertEquals("1.2.0", s.info?.bmapVersion)
        assertEquals(true, s.leAudioSupported)
        assertEquals(true, s.leAudioEnabled)
        assertEquals(true, s.touchControlsEnabled) // scan [1.34] = 01: touch on
        assertEquals(22, s.volume)
        assertEquals(31, s.volumeMax)
        assertEquals(210, s.latencyMs)
        assertEquals(0..520, s.latencyRangeMs)
        assertEquals(Playback(PlaybackState.PLAYING), s.playback)
        assertEquals(TransportControl.entries.toSet(), s.supportedTransportControls)
        assertEquals(true, s.availableToConnect)
        assertEquals("C4:0F:08:11:22:33", s.thisPhoneAddress)
        assertTrue(s.livePushEnabled)
        assertEquals("Bose QC Ultra 2 HP", s.info?.productName)
        assertEquals("Fargo", s.info?.customName)
        assertEquals(0x4082, s.info?.productId)
        assertEquals("68:F2:1F:12:34:56", s.info?.bluetoothAddress)
        assertEquals("085958T53330200AB", s.info?.serialNumber)

        assertEquals(HeadphoneEq(bass = 0, mid = -2, treble = -6), s.eq)
        assertEquals(Sidetone.MEDIUM, s.sidetone)
        assertEquals(true, s.sidetonePersistent)
        assertEquals(true, s.multipointEnabled)
        assertEquals(true, s.autoPlayPause)
        assertEquals(true, s.autoAnswer)
        assertEquals(1, s.voicePrompts?.languageId)
        assertEquals(ButtonAction.DISABLED, s.shortcutButton?.action)

        assertEquals(NoiseSettings(cancellingLevel = 10, ancEnabled = true, windBlock = false, spatialAudio = SpatialAudio.OFF), s.noise)
        assertEquals(0, s.currentModeIndex)
        assertEquals(11, s.modes.size)
        assertEquals(listOf("Quiet", "Aware", "Immersion", "Cinema", "Home"), s.modes.take(5).map { it.name })
        assertTrue(s.modes.drop(5).none { it.isConfigured })
        assertTrue(s.modes.take(4).all { it.isPreset && !it.isEditable })
        assertTrue(s.modes[4].isEditable && s.modes[4].isConfigured)
        assertEquals(SpatialAudio.HEAD, s.modes[2].settings?.spatialAudio)
        assertEquals(0, s.modes[1].settings?.cancellingLevel) // Aware = no cancelling
        assertEquals(setOf(0, 1), s.favoriteModes.map { it.index }.toSet())

        assertEquals(2, s.pairedDevices.size)
        assertEquals(listOf("Pixel 9", "ThinkPad"), s.pairedDevices.map { it.name })
        assertTrue(s.pairedDevices[0].isActiveSource)
        assertTrue(s.pairedDevices[0].isThisPhone)        // from [4.9] AppAddress
        assertFalse(s.pairedDevices[1].isThisPhone)
        assertFalse(s.pairedDevices[1].isActiveSource)
        assertTrue(s.pairedDevices.all { it.isConnected == true })
        assertEquals(true, s.pairedDevices[0].profiles?.leAudioPaired)
        assertEquals(setOf(BluetoothProfile.A2DP, BluetoothProfile.HFP, BluetoothProfile.AVRCP), s.pairedDevices[1].profiles?.classicConnected)
        assertEquals(setOf(ModeSetting.CANCELLING_LEVEL, ModeSetting.SPATIAL_AUDIO, ModeSetting.WIND_BLOCK, ModeSetting.ANC), s.modes[4].changeableSettings)
    }

    @Test
    fun `commands round-trip through the simulator`() = runTest {
        var s = protocol.refresh(requester, protocol.onConnected(requester, HeadphoneState()))

        s = protocol.setEq(requester, s, HeadphoneEq(bass = 5, mid = 0, treble = -3))
        assertEquals(HeadphoneEq(5, 0, -3), s.eq)
        assertEquals(listOf(5, 0, -3), device.eq.toList())

        s = protocol.setMode(requester, s, 4)
        assertEquals(4, s.currentModeIndex)
        assertEquals(NoiseSettings(cancellingLevel = 10, ancEnabled = true, windBlock = true), s.noise)
        assertTrue(requester.unsolicited.any { it.matches(QcUltra2.AUDIO_SETTINGS) })

        s = protocol.setNoise(requester, s, NoiseSettings(cancellingLevel = 4, ancEnabled = true, windBlock = false, spatialAudio = SpatialAudio.ROOM))
        assertEquals(4, s.noise?.cancellingLevel)
        assertEquals(SpatialAudio.ROOM, s.noise?.spatialAudio)
        // The manual tweak diverged from Home: the unit pushes [31.3] = 0xFF, which the app applies.
        val push = requester.unsolicited.last { it.matches(QcUltra2.CURRENT_MODE) }
        s = assertNotNull(protocol.onUnsolicited(push, s))
        assertEquals(0xFF, s.currentModeIndex)

        s = protocol.saveCustomMode(requester, s, 5, "Office", NoiseSettings(cancellingLevel = 7, ancEnabled = true, windBlock = false))
        assertEquals("Office", s.modes[5].name)
        assertTrue(s.modes[5].isConfigured)
        assertEquals(7, s.modes[5].settings?.cancellingLevel)
        assertFailsWith<HeadphoneCommandException> { protocol.saveCustomMode(requester, s, 1, "Nope", NoiseSettings()) }

        s = protocol.setFavorites(requester, s, setOf(0, 5))
        assertEquals(setOf(0, 5), s.favoriteModes.map { it.index }.toSet())

        s = protocol.setSidetone(requester, s, Sidetone.LOW, persistent = false)
        assertEquals(Sidetone.LOW, s.sidetone)
        assertEquals(false, s.sidetonePersistent)

        // Rename answers error 5 on the virtual unit: "the firmware doesn't support changing this".
        val locked = assertFailsWith<BmapException> { protocol.setDeviceName(requester, s, "Desk cans") }
        assertTrue(locked.operatorNotSupported)
        assertEquals("Fargo", s.info?.customName)

        // Routing: first attempt finds the table empty, the retry (500 ms virtual) succeeds.
        s = protocol.switchToDevice(requester, s, "94:45:60:85:05:A7")
        assertEquals("94:45:60:85:05:A7", s.pairedDevices.single { it.isActiveSource }.address)

        // A second refresh sees everything that was written.
        val again = protocol.refresh(requester, s)
        assertEquals(HeadphoneEq(5, 0, -3), again.eq)
        assertEquals("Office", again.modes[5].name)
        assertEquals("Fargo", again.info?.customName)
        assertEquals("94:45:60:85:05:A7", again.pairedDevices.single { it.isActiveSource }.address)

        // New writes
        // Switching touch off writes 00 and the unit reports it off; back on writes 01.
        s = protocol.setTouchControls(requester, again, false)
        assertEquals(false, s.touchControlsEnabled)
        assertFalse(device.touchEnabled)
        s = protocol.setTouchControls(requester, s, true)
        assertEquals(true, s.touchControlsEnabled)
        assertTrue(device.touchEnabled)
        s = protocol.setVolume(requester, s, 12)
        assertEquals(12, s.volume)
        assertFailsWith<HeadphoneCommandException> { protocol.setVolume(requester, s, 40) }
        s = protocol.setLatencyMs(requester, s, 150)
        assertEquals(150, s.latencyMs)
        assertFailsWith<HeadphoneCommandException> { protocol.setLatencyMs(requester, s, 600) }
        s = protocol.transport(requester, s, TransportControl.PAUSE)
        assertEquals(PlaybackState.PAUSED, s.playback?.state)
        s = protocol.resetCustomMode(requester, s, 5)
        assertFalse(s.modes[5].isConfigured)
        assertFailsWith<HeadphoneCommandException> { protocol.resetCustomMode(requester, s, 2) }
        val console = protocol.printConsole(requester, "bind uart")
        assertTrue("[22.3] STATUS: uart" in console, console)
        assertFailsWith<HeadphoneCommandException> { protocol.printConsole(requester, "format flash") }

        // A button press on the headphones arrives as a [2.12] push.
        device.pressShortcutButton()
        val press = requester.drain().single { it.matches(QcUltra2.STATUS_BUTTON) }
        s = assertNotNull(protocol.onUnsolicited(press, s))
        assertEquals(ButtonAction.DISABLED, s.lastButtonPress?.action)
        assertEquals(0x80, s.lastButtonPress?.buttonId)

        // LE Audio toggle: the unit restarts, the command still succeeds with the requested value.
        s = protocol.setLeAudioEnabled(requester, s, false)
        assertEquals(false, s.leAudioEnabled)
        assertFalse(device.isPoweredOn)
        device.powerOn()
        assertEquals(false, protocol.refresh(requester, s).leAudioEnabled)

        protocol.powerOff(requester, again)
        assertFalse(device.isPoweredOn)
        assertFailsWith<HeadphoneCommandException> { protocol.refresh(requester, again) }
    }
}
