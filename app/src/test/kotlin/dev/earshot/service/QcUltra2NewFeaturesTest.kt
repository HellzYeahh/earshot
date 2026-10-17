package dev.earshot.service

import dev.earshot.core.ButtonAction
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneState
import dev.earshot.core.LeAudioPreference
import dev.earshot.core.OperationMode
import dev.earshot.core.PairedDevice
import dev.earshot.core.Playback
import dev.earshot.core.PlaybackState
import dev.earshot.core.TransportControl
import dev.earshot.protocol.BmapError
import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.BmapPacket
import dev.earshot.protocol.qcultra2.QcUltra2
import dev.earshot.protocol.toHex
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The 2026-10-17 additions to [QcUltra2Protocol], against the real fw 10.12 deep-scan frames. */
class QcUltra2NewFeaturesTest {

    private val protocol = QcUltra2Protocol(clock = { 1234L })
    private val fake = FakeRequester()
    private val galaxy = "2C:DA:46:00:00:02"
    private val mobius = "10:B1:DF:00:00:03"

    @Before
    fun silenceLog() {
        ProtocolLog.sink = { _, _, _ -> }
    }

    // ── [9.2] subscription ───────────────────────────────────────────────────────────────

    @Test
    fun `onConnected sends the Bose app's 9_2 enable frame and runs the LE audio check`() = runTest {
        fake.status(QcUltra2.ALL_FUNCTION_BLOCKS, "87cc23ff")
        fake.echoWith(QcUltra2.NOTIFY_BY_FBLOCK, "800422b7")
        fake.on(QcUltra2.LE_AUDIO_CHECK, BmapOperator.START) { listOf(BmapPacket(4, 19, BmapOperator.RESULT, byteArrayOf(0x03))) }
        val s = protocol.onConnected(fake, HeadphoneState())

        val subscribe = fake.sent.single { it.matches(QcUltra2.NOTIFY_BY_FBLOCK) }
        assertEquals("0902020501800422b7", subscribe.encode().toHex())
        assertTrue(s.livePushEnabled)
        assertEquals(LeAudioPreference(supported = true, preferred = true, prohibited = false), s.lePreference)
        assertEquals(BmapOperator.START, fake.sent.last().operator)
    }

    @Test
    fun `a refused 9_2 leaves pushes off and the battery poll reads 2_2`() = runTest {
        fake.error(QcUltra2.NOTIFY_BY_FBLOCK, BmapError.OP_NOT_SUPPORTED)
        val s = protocol.onConnected(fake, HeadphoneState(livePushEnabled = true))
        assertFalse(s.livePushEnabled)
        assertNull(s.lePreference) // [4.19] unscripted → FuncNotSupp, tolerated
        // [0.2] unanswered → the fixed wolverine set was asked for
        assertEquals("01800422b7", fake.sent.single { it.matches(QcUltra2.NOTIFY_BY_FBLOCK) }.payload.toHex())

        fake.status(QcUltra2.BATTERY, "5affff00")
        assertEquals(90, protocol.pollBattery(fake, s).batteryPercent)
        assertEquals(QcUltra2.BATTERY, fake.sent.last().address)
    }

    // ── refresh on the scan's frames ─────────────────────────────────────────────────────

    private fun scriptScan() = with(fake) {
        status(QcUltra2.BATTERY, "5affff00")
        status(QcUltra2.CHARGER_DETECT, "00")
        status(QcUltra2.MIC_MUTE, "01")
        status(QcUltra2.OPERATION_MODE, "01")
        status(QcUltra2.BMAP_VERSION, "312e322e30")
        status(QcUltra2.LE_AUDIO_SUPPORTED, "01")
        status(QcUltra2.DISABLE_CAPTOUCH, "01")
        status(QcUltra2.LE_AUDIO_CONTROL, "01")
        status(QcUltra2.PAIRED_DEVICES, "0310b1df0000032cda46000002")
        status(QcUltra2.APP_ADDRESS, "2cda46000002")
        on(QcUltra2.DEVICE_INFO, BmapOperator.GET) { req ->
            val hex = if (req.payload.toHex() == "2cda46000002") "2cda460000020302034d792047616c61787920466f6c64"
            else "10b1df0000030001014f4c445f4c4150544f50"
            listOf(FakeRequester.statusFrame(QcUltra2.DEVICE_INFO, hex))
        }
        status(QcUltra2.AUDIO_SOURCE, "000f0110b1df000003")
        status(QcUltra2.AVAILABLE_TO_CONNECT, "01")
        status(QcUltra2.AUDIO_CONTROL, "1f")
        status(QcUltra2.AUDIO_STATUS, "01ffff")
        status(QcUltra2.VOLUME, "1f16")
        status(QcUltra2.LATENCY, "0000020800d2")
    }

    @Test
    fun `refresh decodes the fw 10_12 scan frames`() = runTest {
        scriptScan()
        val s = protocol.refresh(fake, HeadphoneState())
        assertEquals(90, s.batteryPercent)
        assertEquals(false, s.isCharging)
        assertEquals(true, s.micMuted)
        assertEquals(OperationMode.NORMAL, s.operationMode)
        assertEquals("1.2.0", s.info?.bmapVersion)
        assertEquals(true, s.leAudioSupported)
        assertEquals(true, s.leAudioEnabled)
        assertEquals(false, s.touchControlsEnabled)
        assertEquals(true, s.availableToConnect)
        assertEquals(TransportControl.entries.toSet(), s.supportedTransportControls)
        assertEquals(Playback(PlaybackState.PLAYING, null), s.playback)
        assertEquals(22, s.volume)
        assertEquals(31, s.volumeMax)
        assertEquals(210, s.latencyMs)
        assertEquals(0..520, s.latencyRangeMs)

        // [4.9] AppAddress identifies the Galaxy; [5.1] says the other device streams.
        assertEquals(galaxy, s.thisPhoneAddress)
        val byMac = s.pairedDevices.associateBy { it.address }
        assertTrue(byMac.getValue(galaxy).isThisPhone)
        assertFalse(byMac.getValue(mobius).isThisPhone)
        assertTrue(byMac.getValue(mobius).isActiveSource)
        assertEquals(true, byMac.getValue(galaxy).isConnected)
        assertEquals("My Galaxy Fold", byMac.getValue(galaxy).name)
    }

    @Test
    fun `without 4_9 the 4_5 local-device flag still finds this phone`() = runTest {
        scriptScan()
        fake.error(QcUltra2.APP_ADDRESS, BmapError.FUNC_NOT_SUPPORTED)
        val s = protocol.refresh(fake, HeadphoneState())
        assertNull(s.thisPhoneAddress)
        assertEquals(listOf(galaxy), s.pairedDevices.filter { it.isThisPhone }.map { it.address })
    }

    // ── writes ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `LE audio toggle tolerates the restart but not a refusal`() = runTest {
        fake.silent(QcUltra2.LE_AUDIO_CONTROL, BmapOperator.SET_GET)
        val s = protocol.setLeAudioEnabled(fake, HeadphoneState(leAudioEnabled = true), false)
        assertEquals(false, s.leAudioEnabled)
        assertEquals("0125020100", fake.sent.single().encode().toHex())

        fake.error(QcUltra2.LE_AUDIO_CONTROL, BmapError.OP_NOT_SUPPORTED, BmapOperator.SET_GET)
        val e = runCatching { protocol.setLeAudioEnabled(fake, HeadphoneState(), true) }.exceptionOrNull()
        assertTrue(e is dev.earshot.protocol.BmapException && e.operatorNotSupported, "$e")
    }

    @Test
    fun `touch, volume and latency write the documented payloads`() = runTest {
        fake.echo(QcUltra2.DISABLE_CAPTOUCH)
        assertEquals(true, protocol.setTouchControls(fake, HeadphoneState(), true).touchControlsEnabled)
        assertEquals("0122020100", fake.sent.last().encode().toHex()) // DisableCaptouch = 0

        fake.on(QcUltra2.VOLUME, BmapOperator.SET_GET) { listOf(FakeRequester.statusFrame(QcUltra2.VOLUME, "1f" + "%02x".format(it.payload[0]))) }
        val v = protocol.setVolume(fake, HeadphoneState(volume = 22, volumeMax = 31), 16)
        assertEquals("10", fake.sent.last().payload.toHex())
        assertEquals(16, v.volume)
        assertTrue(runCatching { protocol.setVolume(fake, v, 32) }.exceptionOrNull() is HeadphoneCommandException)

        // The firmware may ignore the latency write: the echo wins.
        fake.echoWith(QcUltra2.LATENCY, "0000020800d2")
        val l = protocol.setLatencyMs(fake, HeadphoneState(latencyMs = 210, latencyRangeMs = 0..520), 150)
        assertEquals("0096", fake.sent.last().payload.toHex())
        assertEquals(210, l.latencyMs)
        assertTrue(runCatching { protocol.setLatencyMs(fake, l, 521) }.exceptionOrNull() is HeadphoneCommandException)
    }

    @Test
    fun `transport is a 5_3 START and updates the play state`() = runTest {
        fake.on(QcUltra2.AUDIO_CONTROL, BmapOperator.START) { listOf(BmapPacket(5, 3, BmapOperator.RESULT, it.payload)) }
        var s = protocol.transport(fake, HeadphoneState(playback = Playback(PlaybackState.PLAYING)), TransportControl.PAUSE)
        assertEquals("05030501" + "02", fake.sent.last().encode().toHex())
        assertEquals(PlaybackState.PAUSED, s.playback?.state)
        s = protocol.transport(fake, s, TransportControl.NEXT)
        assertEquals("03", fake.sent.last().payload.toHex())
        assertEquals(PlaybackState.PAUSED, s.playback?.state)
        protocol.transport(fake, s, TransportControl.PREVIOUS)
        assertEquals("04", fake.sent.last().payload.toHex())

        fake.error(QcUltra2.AUDIO_CONTROL, BmapError.INVALID_DATA, BmapOperator.START)
        assertTrue(runCatching { protocol.transport(fake, s, TransportControl.PLAY) }.exceptionOrNull() is dev.earshot.protocol.BmapException)
    }

    @Test
    fun `resetCustomMode starts 31_9 and reads the slot back with a 31_6 GET`() = runTest {
        fake.on(QcUltra2.MODES_RESET, BmapOperator.START) { listOf(BmapPacket(31, 9, BmapOperator.RESULT)) }
        fake.on(QcUltra2.MODE_CONFIG, BmapOperator.GET) { listOf(FakeRequester.modeStatus(it.payload[0].toInt(), "None", cncWire = 10)) }
        val before = HeadphoneState(modes = buildModeList(mapOf(6 to dev.earshot.protocol.qcultra2.ModeConfigs.parse(
            FakeRequester.modeStatus(6, "Gym", cncWire = 0).payload)!!), setOf(6), emptyList()))
        assertTrue(before.modes[6].isConfigured)
        val s = protocol.resetCustomMode(fake, before, 6)
        assertEquals("1f090501" + "06", fake.sent[0].encode().toHex())
        assertEquals("1f060101" + "06", fake.sent[1].encode().toHex())
        assertFalse(s.modes[6].isConfigured)
        assertTrue(runCatching { protocol.resetCustomMode(fake, before, 0) }.exceptionOrNull() is HeadphoneCommandException)
    }

    @Test
    fun `print console renders replies and refuses unknown verbs`() = runTest {
        fake.on(QcUltra2.PRINT_VERBOSITY, BmapOperator.SET_GET) { listOf(FakeRequester.errorFrame(QcUltra2.PRINT_VERBOSITY, BmapError.SECURE_SESSION_REQUIRED)) }
        assertEquals("[22.5] ERROR: ERROR SECURE_SESSION_REQUIRED", protocol.printConsole(fake, "verbosity 3"))
        assertTrue(runCatching { protocol.printConsole(fake, "erase") }.exceptionOrNull() is HeadphoneCommandException)
    }

    // ── pushes ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `pushes for the new functions update their fields`() {
        val current = HeadphoneState(
            thisPhoneAddress = galaxy,
            pairedDevices = listOf(PairedDevice(galaxy, "Fold", isConnected = true), PairedDevice(mobius, "Mobius", isConnected = true)),
        )
        fun push(a: dev.earshot.protocol.FunctionAddress, hex: String) = protocol.onUnsolicited(FakeRequester.statusFrame(a, hex), current)!!

        assertEquals(true, push(QcUltra2.CHARGER_DETECT, "01").isCharging)
        assertEquals(false, push(QcUltra2.MIC_MUTE, "00").micMuted)
        assertEquals(OperationMode.FIRMWARE_UPDATE_ONLY, push(QcUltra2.OPERATION_MODE, "02").operationMode)
        assertEquals(false, push(QcUltra2.LE_AUDIO_CONTROL, "00").leAudioEnabled)
        assertEquals(true, push(QcUltra2.DISABLE_CAPTOUCH, "00").touchControlsEnabled)
        assertEquals(14, push(QcUltra2.VOLUME, "1f0e").volume)
        assertEquals(PlaybackState.PAUSED, push(QcUltra2.AUDIO_STATUS, "02ffff").playback?.state)
        assertEquals(215, push(QcUltra2.LATENCY, "0000020800d7").latencyMs)
        val press = push(QcUltra2.STATUS_BUTTON, "800913").lastButtonPress!!
        assertEquals(ButtonAction.SPATIAL_AUDIO, press.action)
        assertEquals(1234L, press.atMillis)
        // [4.4] header 01: only the first listed (the Mobius) is still connected
        val list = push(QcUltra2.PAIRED_DEVICES, "0110b1df0000032cda46000002").pairedDevices
        assertEquals(listOf(mobius, galaxy), list.map { it.address })
        assertEquals(listOf(true, false), list.map { it.isConnected })
        assertTrue(list.single { it.address == galaxy }.isThisPhone)
        assertEquals("Fold", list.single { it.address == galaxy }.name)
        // RESULT frames count only for [31.3] and [4.19]
        assertEquals(3, protocol.onUnsolicited(BmapPacket(31, 3, BmapOperator.RESULT, byteArrayOf(3)), current)?.currentModeIndex)
        assertEquals(true, protocol.onUnsolicited(BmapPacket(4, 19, BmapOperator.RESULT, byteArrayOf(1)), current)?.lePreference?.supported)
        assertNull(protocol.onUnsolicited(BmapPacket(5, 5, BmapOperator.RESULT, byteArrayOf(1, 2)), current))
    }
}
