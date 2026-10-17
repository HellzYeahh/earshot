package dev.earshot.call

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CallStartBoostSchedulerTest {

    /** (attempt, virtual time) of every fire. */
    private class Harness(scope: TestScope) {
        val fired = mutableListOf<Pair<Int, Long>>()
        val scheduler = CallStartBoostScheduler(scope.backgroundScope) { attempt -> fired += attempt to scope.testScheduler.currentTime }
    }

    private fun TestScope.advance(ms: Long) { advanceTimeBy(ms); runCurrent() }

    @Test fun `fires at +1 s and +3 s after the call lands on the headphones`() = runTest {
        val h = Harness(this)
        h.scheduler.update(inCall = true, onHeadphones = true)
        advance(999)
        assertTrue(h.fired.isEmpty())
        advance(1)
        assertEquals(listOf(0 to 1_000L), h.fired)
        advance(2_000)
        assertEquals(listOf(0 to 1_000L, 1 to 3_000L), h.fired)
        assertFalse(h.scheduler.isPending)
        advance(10_000)
        assertEquals(2, h.fired.size) // nothing more for the same call
    }

    @Test fun `repeating the same signal does not restart the schedule`() = runTest {
        val h = Harness(this)
        h.scheduler.update(true, true)
        advance(800)
        h.scheduler.update(true, true) // a poll result
        advance(200)
        assertEquals(listOf(0 to 1_000L), h.fired)
    }

    @Test fun `a flap inside the debounce window cancels and restarts`() = runTest {
        val h = Harness(this)
        h.scheduler.update(true, true)
        advance(500)
        h.scheduler.update(true, false) // SCO dropped for a moment
        advance(300)
        h.scheduler.update(true, true) // back at t=800
        advance(999)
        assertTrue(h.fired.isEmpty())
        advance(1)
        assertEquals(listOf(0 to 1_800L), h.fired)
    }

    @Test fun `call on the phone earpiece never fires, moving it to the headphones does`() = runTest {
        val h = Harness(this)
        h.scheduler.update(inCall = true, onHeadphones = false)
        advance(5_000)
        assertTrue(h.fired.isEmpty())
        h.scheduler.update(inCall = true, onHeadphones = true)
        advance(3_000)
        assertEquals(listOf(0 to 6_000L, 1 to 8_000L), h.fired)
    }

    @Test fun `ending the call after the first fire cancels the second`() = runTest {
        val h = Harness(this)
        h.scheduler.update(true, true)
        advance(1_500)
        h.scheduler.update(inCall = false, onHeadphones = false)
        advance(5_000)
        assertEquals(listOf(0 to 1_000L), h.fired)
    }

    @Test fun `a new call starts a new schedule`() = runTest {
        val h = Harness(this)
        h.scheduler.update(true, true)
        advance(4_000)
        h.scheduler.update(false, false)
        advance(1_000)
        h.scheduler.update(true, true) // t=5000
        advance(3_000)
        assertEquals(listOf(0 to 1_000L, 1 to 3_000L, 0 to 6_000L, 1 to 8_000L), h.fired)
    }

    @Test fun `cancel drops pending fires and re-arms on the next edge`() = runTest {
        val h = Harness(this)
        h.scheduler.update(true, true)
        h.scheduler.cancel()
        advance(5_000)
        assertTrue(h.fired.isEmpty())
        h.scheduler.update(true, true)
        advance(1_000)
        assertEquals(1, h.fired.size)
    }
}
