package dev.earshot.virtual

import dev.earshot.bt.BmapRequester
import dev.earshot.bt.BragiConnection
import dev.earshot.bt.BtLog
import dev.earshot.bt.ReplyMode
import dev.earshot.core.ButtonAction
import dev.earshot.core.ConnectionState
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneState
import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.BmapPacket
import dev.earshot.protocol.qcultra2.QcUltra2
import dev.earshot.service.CrusherProtocol
import dev.earshot.service.ProtocolLog
import dev.earshot.service.QcUltra2Protocol
import dev.earshot.shortcut.ControlDetector
import dev.earshot.shortcut.ControlKind
import dev.earshot.shortcut.ControlSignal
import dev.earshot.shortcut.ControlSignalFeed
import dev.earshot.shortcut.DetectHint
import dev.earshot.shortcut.DetectHintInput
import dev.earshot.shortcut.DetectHints
import dev.earshot.shortcut.HandlerStatus
import dev.earshot.shortcut.SignalOrigin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Detect a button" against each virtual model: the real [QcUltra2Protocol] reads the unit, the
 * unit's "presses" arrive as the pushes real headphones send, and the detector labels them.
 * Every model also gets the Android-side baseline (here a hand-driven feed), which is all a
 * headset without BMAP pushes (or the Crusher) has.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ControlDetectionEndToEndTest {

    @Before
    fun silenceLog() {
        ProtocolLog.sink = { _, _, _ -> }
        BtLog.sink = { _, _, _ -> }
    }

    private class DirectRequester(val device: VirtualQcUltra2) : BmapRequester {
        override val isOpen: Boolean get() = device.isPoweredOn

        override suspend fun request(packet: BmapPacket, mode: ReplyMode, timeoutMs: Long): List<BmapPacket> {
            if (!isOpen) throw HeadphoneCommandException("Headphones not connected")
            val frames = device.handle(packet)
            device.drainDeferred()
            return when (mode) {
                ReplyMode.SINGLE -> frames.firstOrNull { it.matches(packet.address) && it.operator != BmapOperator.PROCESSING }?.let { listOf(it) }
                ReplyMode.MULTI -> frames.filter { it.block == packet.block }.ifEmpty { null }
            } ?: throw HeadphoneCommandException("${packet.address}: no reply from the headphones")
        }

        override suspend fun send(packet: BmapPacket) {
            device.handle(packet)
        }
    }

    private class ManualFeed : ControlSignalFeed {
        var emit: (ControlSignal) -> Unit = {}
        override fun open(emit: (ControlSignal) -> Unit): AutoCloseable {
            this.emit = emit
            return AutoCloseable { this.emit = {} }
        }
    }

    /** One connected virtual unit, its state as the repository would publish it, and a running detector. */
    private class Rig(
        val device: VirtualQcUltra2,
        val protocol: QcUltra2Protocol,
        val state: MutableStateFlow<HeadphoneState>,
        val detector: ControlDetector,
        val phone: ManualFeed,
    ) {
        /** Applies the pushes the unit queued since the last call, like the connection's unsolicited stream. */
        fun deliverPushes() {
            for (frame in device.drainDeferred()) protocol.onUnsolicited(frame, state.value)?.let { state.value = it }
        }
    }

    private suspend fun TestScope.connect(profile: VirtualProfile, configure: VirtualQcUltra2.() -> Unit = {}): Rig {
        val device = VirtualQcUltra2(profile = profile).apply(configure)
        val r = DirectRequester(device)
        val protocol = QcUltra2Protocol(localAddress = { "C4:0F:08:11:22:33" })
        var s = protocol.onConnected(r, HeadphoneState(connection = ConnectionState(ConnectionStatus.CONNECTED, profile.customName, device.macAddress)))
        for (frame in BmapPacket.decodeAll(device.greeting())) s = protocol.onUnsolicited(frame, s) ?: s
        s = protocol.refresh(r, s)
        val state = MutableStateFlow(s.copy(connection = ConnectionState(ConnectionStatus.CONNECTED, profile.customName, device.macAddress)))
        val phone = ManualFeed()
        val detector = ControlDetector(backgroundScope, state, listOf(phone), clock = { testScheduler.currentTime })
        detector.start(device.macAddress)
        testScheduler.runCurrent()
        return Rig(device, protocol, state, detector, phone)
    }

    /** Presses play/pause, swipes the volume and presses the first `[1.9]` button, each 2 s apart. */
    private fun TestScope.pressEverything(r: Rig) {
        r.device.pressPlayPause()
        r.deliverPushes(); testScheduler.runCurrent()
        testScheduler.advanceTimeBy(2_000)
        r.device.swipeVolume(+2)
        r.deliverPushes(); testScheduler.runCurrent()
        testScheduler.advanceTimeBy(2_000)
        r.device.pressButton()
        r.deliverPushes(); testScheduler.runCurrent()
        testScheduler.advanceTimeBy(2_000)
    }

    private fun recordIds(device: VirtualQcUltra2) = device.buttonRecords.map { it[0].toInt() and 0xFF }

    @Test
    fun `QC Ultra 2 - the pushes label play pause, volume and the Shortcut button`() = runTest {
        val r = connect(VirtualProfile.QC_ULTRA_2)
        assertEquals(listOf(0x80), r.state.value.allShortcutButtons.map { it.buttonId })
        pressEverything(r)
        val rows = r.detector.session.value.entries
        assertEquals(listOf(ControlKind.HEADPHONE_BUTTON_EVENT, ControlKind.VOLUME, ControlKind.PLAY_PAUSE), rows.map { it.kind })
        assertTrue(rows.all { it.origin == SignalOrigin.HEADPHONES && !it.remappable })
        assertEquals("Shortcut button · Long press", rows[0].label)
        assertEquals(22 to 24, rows[1].volumeFrom to rows[1].volumeTo)
        assertTrue(rows[1].isSmallVolumeNudge, "two steps: what a still finger on the strip produces")
    }

    @Test
    fun `QC Ultra Earbuds 2 - one record per bud, each pressed on its own`() = runTest {
        val r = connect(VirtualProfile.QC_ULTRA_EARBUDS_2)
        val buttons = r.state.value.allShortcutButtons
        assertEquals(recordIds(r.device), buttons.map { it.buttonId })
        assertEquals(listOf(0x03, 0x04), buttons.map { it.buttonId }, "right bud first: Shortcut Button 1 (right earbud)")
        assertEquals(listOf(ButtonAction.VOICE_ASSISTANT, ButtonAction.SPATIAL_AUDIO), buttons.map { it.action })
        assertTrue(buttons.all { ButtonAction.SPATIAL_AUDIO in it.supportedActions })

        r.device.pressButton(0x04)
        r.deliverPushes(); testScheduler.runCurrent()
        testScheduler.advanceTimeBy(2_000)
        r.device.pressButton(0x03)
        r.deliverPushes(); testScheduler.runCurrent()
        assertEquals(
            listOf("Right shortcut · Long press", "Left shortcut · Long press"),
            r.detector.session.value.entries.map { it.label },
        )
    }

    @Test
    fun `QC Ultra Earbuds 2 - a bud's write uses its own button id and keeps the other bud`() = runTest {
        val r = connect(VirtualProfile.QC_ULTRA_EARBUDS_2)
        val s = r.protocol.setShortcutButton(DirectRequester(r.device), r.state.value, 0x04, 9, ButtonAction.VOICE_ASSISTANT)
        assertEquals(listOf(ButtonAction.VOICE_ASSISTANT, ButtonAction.VOICE_ASSISTANT), s.allShortcutButtons.map { it.action })
        assertEquals(1, r.device.buttonRecords[1][2].toInt(), "the left record on the unit changed")
        assertEquals(1, r.device.buttonRecords[0][2].toInt(), "the right record untouched")
        assertTrue(dev.earshot.shortcut.SecondaryButtonPolicy.sharesAssistant(s.allShortcutButtons))
    }

    @Test
    fun `QC45 and NC 700 - pushes where the unit sends them, the phone's view otherwise`() = runTest {
        for (profile in listOf(VirtualProfile.QC45, VirtualProfile.NC700)) {
            val r = connect(profile)
            assertEquals(recordIds(r.device), r.state.value.allShortcutButtons.map { it.buttonId }, profile.productName)
            pressEverything(r)
            val kinds = r.detector.session.value.entries.map { it.kind }.toSet()
            assertTrue(ControlKind.PLAY_PAUSE in kinds && ControlKind.VOLUME in kinds, "${profile.productName}: $kinds")
        }
    }

    @Test
    fun `without the push subscription only the phone's baseline labels the controls`() = runTest {
        // [9.2] silent, as on firmware that doesn't push: the state never moves by itself.
        val r = connect(VirtualProfile.QC45) { silentFunctions = setOf(QcUltra2.NOTIFY_BY_FBLOCK) }
        pressEverything(r)
        assertTrue(r.detector.session.value.entries.none { it.origin == SignalOrigin.HEADPHONES })
        val t = testScheduler.currentTime
        r.phone.emit(ControlSignal.PlayState(false, SignalOrigin.PHONE, t))
        r.phone.emit(ControlSignal.Volume(7, 8, SignalOrigin.PHONE, t + 2_000))
        assertEquals(listOf(ControlKind.VOLUME, ControlKind.PLAY_PAUSE), r.detector.session.value.entries.map { it.kind })
    }

    @Test
    fun `Crusher - no BMAP, no Bose buttons, the baseline labels everything and no native rows appear`() = runTest {
        val transport = VirtualCrusherTransport(VirtualCrusher())
        val conn = BragiConnection(transport, backgroundScope, clock = { testScheduler.currentTime })
        val s = CrusherProtocol().onConnected(conn, HeadphoneState())
            .copy(connection = ConnectionState(ConnectionStatus.CONNECTED, "Crusher ANC", "00:11:22:33:44:55"))
        assertTrue(s.allShortcutButtons.isEmpty())

        val state = MutableStateFlow(s)
        val phone = ManualFeed()
        val detector = ControlDetector(backgroundScope, state, listOf(phone), clock = { testScheduler.currentTime })
        detector.start(s.connection.deviceAddress)
        testScheduler.runCurrent()
        val t = testScheduler.currentTime
        phone.emit(ControlSignal.PlayState(true, SignalOrigin.PHONE, t))
        phone.emit(ControlSignal.Volume(5, 6, SignalOrigin.PHONE, t + 2_000))
        phone.emit(ControlSignal.CallState(active = false, at = t + 2_100))
        detector.report(ControlSignal.AssistantGesture(null, wired = false, at = t + 4_000, origin = SignalOrigin.DEMO))
        val rows = detector.session.value.entries
        assertEquals(listOf(ControlKind.ASSISTANT_GESTURE, ControlKind.VOLUME, ControlKind.PLAY_PAUSE), rows.map { it.kind })
        assertTrue(rows.first().remappable)

        testScheduler.advanceTimeBy(ControlDetector.SESSION_MS)
        testScheduler.runCurrent()
        val hints = DetectHints.hints(
            DetectHintInput(
                listening = false, finished = true, sawAssistantGesture = false, bose = true,
                buttons = s.allShortcutButtons, touchControlsEnabled = s.touchControlsEnabled, handler = HandlerStatus.Earshot,
            ),
        )
        assertEquals(listOf(DetectHint.OTHER_NO_SHORTCUT), hints, "no Bose wording for the Crusher")
    }
}
