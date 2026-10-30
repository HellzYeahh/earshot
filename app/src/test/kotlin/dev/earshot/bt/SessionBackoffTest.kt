package dev.earshot.bt

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where the reconnect schedule stands after a session ends (audit R26): only a session that
 * really worked resets it, so a headset that passes the probe and then drops the link during the
 * refresh climbs to the slow pulse instead of being reconnected every second.
 */
class SessionBackoffTest {
    private val lost = SessionEnd.classify(java.io.IOException("read failed"), userStopped = false, linked = true)
    private val deaf = SessionEnd.classify(UnresponsiveLinkException("3 requests in a row got no reply"), userStopped = false, linked = true)

    @Test fun `a session that really worked resets the schedule`() {
        assertEquals(0, attemptAfterSession(attemptsBefore = 4, end = lost, upForMs = 10 * 60_000L))
        assertEquals(0, attemptAfterSession(attemptsBefore = 0, end = lost, upForMs = MIN_HEALTHY_SESSION_MS))
    }

    @Test fun `a session that dropped within seconds costs a step like a failed connect`() {
        assertEquals(0, attemptAfterSession(attemptsBefore = 0, end = lost, upForMs = 4_000L))
        assertEquals(3, attemptAfterSession(attemptsBefore = 3, end = lost, upForMs = MIN_HEALTHY_SESSION_MS - 1))
    }

    @Test fun `a half-dead session never resets it, however long it lasted`() {
        assertEquals(2, attemptAfterSession(attemptsBefore = 2, end = deaf, upForMs = 10 * 60_000L))
    }

    @Test fun `sessions that keep dying early reach the slow pulse`() {
        // The loop: connect (attempt reset to 0 while up), the session dies, attemptAfterSession,
        // then the retry wait uses delayMs(attempt++), as in ConnectionManager.runLoop.
        var attempt = 0
        val waits = ArrayList<Long>()
        repeat(ReconnectBackoff.MAX_ATTEMPTS) {
            val before = attempt
            attempt = attemptAfterSession(before, lost, upForMs = 3_000L)
            waits += ReconnectBackoff.delayMs(attempt++)
        }
        assertEquals(listOf(1_000L, 2_000L, 5_000L, 10_000L), waits.take(4))
        assertTrue(attempt >= ReconnectBackoff.MAX_ATTEMPTS, "fast schedule spent: the slow pulse comes next")
    }
}
