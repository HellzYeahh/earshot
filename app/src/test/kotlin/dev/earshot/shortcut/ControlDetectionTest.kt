package dev.earshot.shortcut

import dev.earshot.core.ButtonAction
import dev.earshot.core.ButtonPress
import dev.earshot.core.ConnectionState
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneState
import dev.earshot.core.Playback
import dev.earshot.core.PlaybackState
import dev.earshot.core.fake.FakeHeadphoneRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Classification of `HeadphoneState` changes and the detector list's debounce. */
class ControlDetectionTest {
    private val connected = FakeHeadphoneRepository.connectedState().copy(
        playback = Playback(PlaybackState.PLAYING), volume = 14, lastButtonPress = null,
    )

    // ── Classification ───────────────────────────────────────────────────────────────

    @Test
    fun `a play state change is play pause, a seek is a track control`() {
        val paused = ControlClassifier.fromHeadphoneState(connected, connected.copy(playback = Playback(PlaybackState.PAUSED)), 10)
        val s = assertIs<ControlSignal.PlayState>(paused.single())
        assertFalse(s.playing)
        assertEquals(SignalOrigin.HEADPHONES, s.origin)

        val seek = ControlClassifier.fromHeadphoneState(connected, connected.copy(playback = Playback(PlaybackState.FAST_FORWARDING)), 10)
        assertTrue(assertIs<ControlSignal.TrackChange>(seek.single()).seeking)
    }

    @Test
    fun `a position jump back to the start while playing is a new track`() {
        val before = connected.copy(playback = Playback(PlaybackState.PLAYING, trackPosition = 140))
        val after = connected.copy(playback = Playback(PlaybackState.PLAYING, trackPosition = 0))
        assertIs<ControlSignal.TrackChange>(ControlClassifier.fromHeadphoneState(before, after, 1).single())
        // Unknown positions (the usual ffff) say nothing.
        assertTrue(ControlClassifier.fromHeadphoneState(connected, connected.copy(playback = Playback(PlaybackState.PLAYING)), 1).isEmpty())
    }

    @Test
    fun `volume and a new button push are reported`() {
        // Named as the Bose driver names a [2.12] push (StateMapping.toButtonPress).
        val pushed = ButtonPress(0x80, 9, ButtonAction.VOICE_ASSISTANT, atMillis = 5, buttonLabel = "Shortcut button", eventLabel = "Long press")
        val after = connected.copy(volume = 16, lastButtonPress = pushed)
        val out = ControlClassifier.fromHeadphoneState(connected, after, 7)
        assertEquals(ControlSignal.Volume(14, 16, SignalOrigin.HEADPHONES, 7), out.filterIsInstance<ControlSignal.Volume>().single())
        val press = out.filterIsInstance<ControlSignal.ButtonEventSeen>().single()
        assertEquals(0x80, press.buttonId)
        assertEquals("Shortcut button · Long press", ControlClassifier.buttonLabel(press.buttonId, press.eventType, press.buttonLabel, press.eventLabel))
        assertEquals("Shortcut button · Long press", DetectionLog().add(press).entries.single().label)
        // The same push again (no new atMillis) is not a second press.
        assertTrue(ControlClassifier.fromHeadphoneState(after, after.copy(), 8).isEmpty())
    }

    @Test
    fun `nothing across a connect, a disconnect or another headset`() {
        val disconnected = connected.copy(connection = ConnectionState(ConnectionStatus.DISCONNECTED))
        assertTrue(ControlClassifier.fromHeadphoneState(disconnected, connected.copy(volume = 3), 1).isEmpty())
        assertTrue(ControlClassifier.fromHeadphoneState(connected, disconnected, 1).isEmpty())
        assertTrue(ControlClassifier.fromHeadphoneState(null, connected, 1).isEmpty())
        val other = connected.copy(connection = connected.connection.copy(deviceAddress = "AA:BB:CC:DD:EE:FF"), volume = 2)
        assertTrue(ControlClassifier.fromHeadphoneState(connected, other, 1).isEmpty())
        // Values never reported (null) say nothing either.
        assertTrue(ControlClassifier.fromHeadphoneState(HeadphoneState(connection = connected.connection), connected, 1).isEmpty())
    }

    @Test
    fun `unknown Bose codes are named in hex`() {
        // A push the driver could not name.
        assertEquals("Button 0x42 · event 99", ControlClassifier.buttonLabel(0x42, 99))
        assertEquals("Right shortcut · Press and hold", ControlClassifier.buttonLabel(0x03, 5, "Right shortcut", "Press and hold"))
        assertEquals("Right shortcut · event 99", ControlClassifier.buttonLabel(0x03, 99, "Right shortcut", null))
    }

    // ── Debounce ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a burst of volume steps within 1_5 s is one row, from either side`() {
        val log = DetectionLog()
            .add(ControlSignal.Volume(14, 15, SignalOrigin.HEADPHONES, 1_000))
            .add(ControlSignal.Volume(7, 8, SignalOrigin.PHONE, 1_200)) // Android's stream for the same swipe
            .add(ControlSignal.Volume(15, 16, SignalOrigin.HEADPHONES, 2_600))
        val row = log.entries.single()
        assertEquals(ControlKind.VOLUME, row.kind)
        assertEquals(3, row.count)
        assertEquals(14, row.volumeFrom)
        assertEquals(16, row.volumeTo, "only the side that opened the row moves the levels")
        assertTrue(row.isSmallVolumeNudge)
        // 1.5 s of quiet: a new swipe.
        val two = log.add(ControlSignal.Volume(16, 20, SignalOrigin.HEADPHONES, 4_200))
        assertEquals(2, two.entries.size)
        assertFalse(two.entries.first().isSmallVolumeNudge)
    }

    @Test
    fun `play pause repeats within 0_5 s merge, the other side confirming a press merges within 1_5 s`() {
        val log = DetectionLog()
            .add(ControlSignal.PlayState(false, SignalOrigin.HEADPHONES, 1_000))
            .add(ControlSignal.PlayState(true, SignalOrigin.HEADPHONES, 1_400))
        assertEquals(1, log.entries.size)
        assertEquals(2, log.entries.single().count)
        assertEquals(true, log.entries.single().playing)

        val confirmed = log.add(ControlSignal.PlayState(true, SignalOrigin.PHONE, 2_300))
        assertEquals(1, confirmed.entries.size)
        assertEquals(2, confirmed.entries.single().count, "the phone seeing the same press is not another press")

        val again = confirmed.add(ControlSignal.PlayState(false, SignalOrigin.PHONE, 2_900))
        assertEquals(2, again.entries.size, "the same side 0.6 s later is a second press")
    }

    @Test
    fun `newest first and the assistant gesture is the remappable one`() {
        val log = DetectionLog()
            .add(ControlSignal.Volume(1, 2, SignalOrigin.PHONE, 1_000))
            .add(ControlSignal.AssistantGesture("68:F2:1F:12:34:56", wired = false, at = 5_000))
            .add(ControlSignal.ButtonEventSeen(0x80, 9, ButtonAction.VOICE_ASSISTANT, 5_100))
        assertEquals(listOf(ControlKind.HEADPHONE_BUTTON_EVENT, ControlKind.ASSISTANT_GESTURE, ControlKind.VOLUME), log.entries.map { it.kind })
        assertEquals(listOf(false, true, false), log.entries.map { it.remappable })
        assertTrue(log.sawAssistantGesture)
        assertEquals("68:F2:1F:12:34:56", log.entries[1].headsetAddress)
        assertEquals("Voice assistant", log.entries[0].detail)
        // A second VOICE_COMMAND for the same press merges.
        assertEquals(3, log.add(ControlSignal.AssistantGesture(null, wired = false, at = 5_500)).entries.size)
    }

    @Test
    fun `during a call the controls belong to the call`() {
        val log = DetectionLog()
            .add(ControlSignal.CallState(active = false, at = 0)) // the feed's first reading
            .add(ControlSignal.CallState(active = true, at = 1_000))
            .add(ControlSignal.Volume(3, 5, SignalOrigin.PHONE, 1_100))
            .add(ControlSignal.PlayState(false, SignalOrigin.PHONE, 1_200))
            .add(ControlSignal.CallState(active = true, at = 1_300))
        assertEquals(listOf(ControlKind.CALL_CONTROLS), log.entries.map { it.kind })
        val after = log.add(ControlSignal.CallState(active = false, at = 9_000)).add(ControlSignal.Volume(5, 6, SignalOrigin.PHONE, 9_100))
        assertEquals(listOf(ControlKind.VOLUME, ControlKind.CALL_CONTROLS), after.entries.map { it.kind })
    }

    @Test
    fun `demo and wired gestures say so`() {
        val log = DetectionLog()
            .add(ControlSignal.AssistantGesture(null, wired = false, at = 1, origin = SignalOrigin.DEMO))
            .add(ControlSignal.AssistantGesture(null, wired = true, at = 5_000))
        assertEquals(listOf("Wired headset", "Demo"), log.entries.map { it.detail })
    }
}
