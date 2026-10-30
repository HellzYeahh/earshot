package dev.earshot.audio

import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How long announced audio sessions are kept: a session that still carries a working effect may
 * play far past the age limit, and a short Bluetooth drop doesn't forget the sessions.
 */
class SessionLifetimeTest {

    @Before fun clearBefore() {
        SessionRegistry.clear()
    }

    @After fun clearAfter() {
        SessionRegistry.clear()
    }

    @Test fun `a session with a working effect outlives the normal age limit, up to its own`() {
        SessionRegistry.open(11, "audiobook")
        SessionRegistry.open(12, null)
        val opened = System.currentTimeMillis()

        // Seven hours of playback since the one announcement.
        SessionRegistry.pruneOlderThan(6 * HOUR, opened + 7 * HOUR, attached = setOf(11), attachedMaxAgeMillis = 24 * HOUR)
        assertEquals(listOf(11), SessionRegistry.sessions.value.map { it.sessionId }, "only the session without an effect goes")

        // A player that died without CLOSE still goes eventually.
        SessionRegistry.pruneOlderThan(6 * HOUR, opened + 25 * HOUR, attached = setOf(11), attachedMaxAgeMillis = 24 * HOUR)
        assertTrue(SessionRegistry.sessions.value.isEmpty())
    }

    @Test fun `without attached sessions the age limit is unchanged`() {
        SessionRegistry.open(11, "music")
        val opened = System.currentTimeMillis()
        SessionRegistry.pruneOlderThan(6 * HOUR, opened + 5 * HOUR)
        assertEquals(1, SessionRegistry.sessions.value.size)
        SessionRegistry.pruneOlderThan(6 * HOUR, opened + 7 * HOUR)
        assertTrue(SessionRegistry.sessions.value.isEmpty())
    }

    @Test fun `a short Bluetooth drop keeps the sessions, a long one forgets them once`() {
        val grace = BluetoothLossGrace(graceMillis = 120_000)
        assertFalse(grace.shouldForgetSessions(present = true, nowMillis = 0))
        assertFalse(grace.shouldForgetSessions(present = false, nowMillis = 1_000), "just lost")
        assertFalse(grace.shouldForgetSessions(present = true, nowMillis = 30_000), "back within the grace (output switcher, a drop)")
        assertFalse(grace.shouldForgetSessions(present = true, nowMillis = 500_000), "nothing pending any more")

        assertFalse(grace.shouldForgetSessions(present = false, nowMillis = 600_000))
        assertTrue(grace.shouldForgetSessions(present = false, nowMillis = 800_000), "still gone after the grace")
        assertFalse(grace.shouldForgetSessions(present = false, nowMillis = 900_000), "only once")
        assertFalse(grace.shouldForgetSessions(present = true, nowMillis = 1_000_000))
    }

    @Test fun `coming back after the grace with no check in between still forgets`() {
        val grace = BluetoothLossGrace(graceMillis = 120_000)
        grace.shouldForgetSessions(present = true, nowMillis = 0)
        assertFalse(grace.shouldForgetSessions(present = false, nowMillis = 1_000))
        assertTrue(grace.shouldForgetSessions(present = true, nowMillis = 3_600_000))
    }

    @Test fun `starting without Bluetooth forgets nothing`() {
        val grace = BluetoothLossGrace(graceMillis = 120_000)
        assertFalse(grace.shouldForgetSessions(present = false, nowMillis = 0))
        assertFalse(grace.shouldForgetSessions(present = false, nowMillis = 10_000_000))
    }

    // SessionAging runs the repository's reconcile steps: prune before the attach pass,
    // noteAttached with what the engine holds after it (nothing while Bluetooth is absent).

    @Test fun `a session that carried an effect survives a short drop past the normal age limit`() {
        val aging = aging()
        SessionRegistry.open(11, "sleepsounds")
        val opened = System.currentTimeMillis()

        // 23:00: the headset plays and the session gets its effect.
        aging.prune(btPresent = true, elapsedMillis = 0, wallMillis = opened)
        aging.noteAttached(setOf(11))

        // 05:30: a one-second drop (or the output switcher); every effect has been released.
        val drop = 6 * HOUR + 30 * MINUTE
        aging.prune(btPresent = false, elapsedMillis = drop, wallMillis = opened + drop)
        aging.noteAttached(emptySet())
        assertEquals(listOf(11), ids(), "kept through the drop although it holds no effect now")

        // Another reconcile inside the grace (a session event) keeps it too.
        aging.prune(btPresent = false, elapsedMillis = drop + 500, wallMillis = opened + drop + 500)
        aging.noteAttached(emptySet())
        assertEquals(listOf(11), ids())

        // The headset is back: still listed, so the attach pass gives it the EQ again.
        aging.prune(btPresent = true, elapsedMillis = drop + 1_000, wallMillis = opened + drop + 1_000)
        assertEquals(listOf(11), ids(), "listed when Bluetooth returns")
    }

    @Test fun `only sessions that carried an effect get the longer limit, and it still ends`() {
        val aging = aging()
        SessionRegistry.open(11, "sleepsounds")
        SessionRegistry.open(12, null)
        val opened = System.currentTimeMillis()
        aging.prune(btPresent = true, elapsedMillis = 0, wallMillis = opened)
        aging.noteAttached(setOf(11))

        aging.prune(btPresent = false, elapsedMillis = 7 * HOUR, wallMillis = opened + 7 * HOUR)
        aging.noteAttached(emptySet())
        assertEquals(listOf(11), ids(), "the session without an effect goes at the normal limit")

        // A player that died without CLOSE goes after the longer limit.
        aging.prune(btPresent = true, elapsedMillis = 7 * HOUR + 1_000, wallMillis = opened + 25 * HOUR)
        assertTrue(ids().isEmpty())
    }

    @Test fun `a long loss forgets the sessions and what they carried`() {
        val aging = aging()
        SessionRegistry.open(11, "sleepsounds")
        val opened = System.currentTimeMillis()
        aging.prune(btPresent = true, elapsedMillis = 0, wallMillis = opened)
        aging.noteAttached(setOf(11))

        aging.prune(btPresent = false, elapsedMillis = HOUR, wallMillis = opened + HOUR)
        aging.noteAttached(emptySet())
        aging.prune(btPresent = false, elapsedMillis = HOUR + 3 * MINUTE, wallMillis = opened + HOUR + 3 * MINUTE)
        assertTrue(ids().isEmpty(), "gone after the grace")

        // The same id announced again has not carried an effect yet: the normal limit applies.
        SessionRegistry.open(11, "sleepsounds")
        aging.prune(btPresent = true, elapsedMillis = 2 * HOUR, wallMillis = opened + 7 * HOUR)
        assertTrue(ids().isEmpty())
    }

    @Test fun `a closed session leaves the carried set`() {
        val aging = aging()
        SessionRegistry.open(11, "music")
        val opened = System.currentTimeMillis()
        aging.prune(btPresent = true, elapsedMillis = 0, wallMillis = opened)
        aging.noteAttached(setOf(11))

        SessionRegistry.close(11)
        aging.prune(btPresent = true, elapsedMillis = 1_000, wallMillis = opened + 1_000)
        aging.noteAttached(emptySet())

        // A new session reusing the id starts on the normal limit.
        SessionRegistry.open(11, "music")
        aging.prune(btPresent = true, elapsedMillis = 2_000, wallMillis = opened + 7 * HOUR)
        assertTrue(ids().isEmpty())
    }

    private fun aging() = SessionAging(graceMillis = 2 * MINUTE, maxAgeMillis = 6 * HOUR, carriedMaxAgeMillis = 24 * HOUR)

    private fun ids() = SessionRegistry.sessions.value.map { it.sessionId }

    private companion object {
        const val MINUTE = 60L * 1000
        const val HOUR = 60 * MINUTE
    }
}
