package dev.earshot.shortcut

import dev.earshot.core.HeadphoneState
import dev.earshot.core.Playback
import dev.earshot.core.PlaybackState
import dev.earshot.core.fake.FakeHeadphoneRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The session: timeout, stop, feeds opened and closed, Bose state diffs, the trigger's report. */
@OptIn(ExperimentalCoroutinesApi::class)
class ControlDetectorTest {

    /** A feed the test drives by hand, counting opens and closes. */
    private class ManualFeed : ControlSignalFeed {
        var emit: ((ControlSignal) -> Unit)? = null
        var opened = 0
        var closed = 0
        override fun open(emit: (ControlSignal) -> Unit): AutoCloseable {
            opened++
            this.emit = emit
            return AutoCloseable { closed++; this.emit = null }
        }
    }

    private class Rig(val detector: ControlDetector, val feed: ManualFeed, val state: MutableStateFlow<HeadphoneState>)

    private fun TestScope.rig(): Rig {
        val feed = ManualFeed()
        val state = MutableStateFlow(FakeHeadphoneRepository.connectedState().copy(playback = Playback(PlaybackState.PLAYING), volume = 10))
        val detector = ControlDetector(backgroundScope, state, listOf(feed), clock = { testScheduler.currentTime })
        return Rig(detector, feed, state)
    }

    @Test
    fun `a session listens for 45 s and then stops by itself`() = runTest {
        val r = rig()
        r.detector.start("68:F2:1F:12:34:56")
        testScheduler.runCurrent()
        val s = r.detector.session.value
        assertTrue(s.listening)
        assertEquals(ControlDetector.SESSION_MS, s.endsAt!! - s.startedAt!!)
        assertEquals(1, r.feed.opened)

        testScheduler.advanceTimeBy(ControlDetector.SESSION_MS - 1)
        testScheduler.runCurrent()
        assertTrue(r.detector.isListening)

        testScheduler.advanceTimeBy(2)
        testScheduler.runCurrent()
        assertFalse(r.detector.isListening)
        assertTrue(r.detector.session.value.finished)
        assertEquals(1, r.feed.closed, "the Android sources are released")
    }

    @Test
    fun `stop ends it at once and keeps the list, a restart starts clean`() = runTest {
        val r = rig()
        r.detector.start()
        testScheduler.runCurrent()
        r.feed.emit!!(ControlSignal.PlayState(false, SignalOrigin.PHONE, testScheduler.currentTime))
        r.detector.stop()
        testScheduler.runCurrent()
        assertFalse(r.detector.isListening)
        assertEquals(1, r.detector.session.value.entries.size)
        assertEquals(1, r.feed.closed)

        r.detector.start()
        testScheduler.runCurrent()
        assertTrue(r.detector.session.value.entries.isEmpty())
        assertEquals(2, r.detector.session.value.generation)
        // The first session's end can't touch the second one.
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertTrue(r.detector.isListening)

        r.detector.clear()
        testScheduler.runCurrent()
        assertNotNull(r.detector.session.value).let { assertTrue(it.startedAt == null && it.entries.isEmpty()) }
    }

    @Test
    fun `Bose state changes become rows while listening, not before or after`() = runTest {
        val r = rig()
        r.state.update { it.copy(volume = 11) } // before: ignored
        r.detector.start()
        testScheduler.runCurrent()
        r.state.update { it.copy(playback = Playback(PlaybackState.PAUSED)) }
        testScheduler.runCurrent()
        r.state.update { it.copy(volume = 13) }
        testScheduler.runCurrent()
        assertEquals(listOf(ControlKind.VOLUME, ControlKind.PLAY_PAUSE), r.detector.session.value.entries.map { it.kind })

        testScheduler.advanceTimeBy(ControlDetector.SESSION_MS + 1)
        testScheduler.runCurrent()
        r.state.update { it.copy(volume = 20) }
        testScheduler.runCurrent()
        assertEquals(2, r.detector.session.value.entries.size)
    }

    @Test
    fun `the trigger's report counts while listening and just after the end, never outside a session`() = runTest {
        val r = rig()
        r.detector.report(ControlSignal.AssistantGesture(null, wired = false, at = testScheduler.currentTime))
        assertTrue(r.detector.session.value.entries.isEmpty(), "no session: nothing recorded")

        r.detector.start()
        testScheduler.runCurrent()
        val pressedAt = testScheduler.currentTime + 44_000
        testScheduler.advanceTimeBy(46_000)
        testScheduler.runCurrent()
        assertFalse(r.detector.isListening)
        // The headset lookup finished after the countdown, but the press was inside it.
        r.detector.report(ControlSignal.AssistantGesture("68:F2:1F:12:34:56", wired = false, at = pressedAt))
        assertTrue(r.detector.session.value.sawAssistantGesture)
        // A press stamped after the end is not.
        r.detector.report(ControlSignal.Volume(1, 2, SignalOrigin.PHONE, testScheduler.currentTime + 10))
        assertEquals(1, r.detector.session.value.entries.size)
    }

    @Test
    fun `a feed that fails to open only loses itself`() = runTest {
        val state = MutableStateFlow(FakeHeadphoneRepository.connectedState().copy(volume = 10))
        val broken = ControlSignalFeed { throw SecurityException("no permission") }
        val detector = ControlDetector(backgroundScope, state, listOf(broken), clock = { testScheduler.currentTime })
        detector.start()
        testScheduler.runCurrent()
        state.update { it.copy(volume = 12) }
        testScheduler.runCurrent()
        assertTrue(detector.isListening)
        assertEquals(ControlKind.VOLUME, detector.session.value.entries.single().kind)
    }
}
