package dev.earshot.ui

import dev.earshot.ui.tools.PendingDialog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The root's once-per-process connect (audit R16) and the remote's dialog hand-off (audit R14). */
class RootGuardsTest {

    @Test
    fun `the root connects once per process, not on every new Activity`() {
        val once = AutoConnectOnce()
        assertTrue(once.claim(), "the first composition with Bluetooth allowed connects")
        // A rotation, a fold or a notification tap builds the root again in the same process.
        assertFalse(once.claim())
        assertFalse(once.claim())
        // A new process starts with a new instance and connects again.
        assertTrue(AutoConnectOnce().claim())
    }

    @Test
    fun `a dialog handed over waits for the screen, and a newer one is not lost`() {
        val pending = PendingDialog<String>()
        assertNull(pending.state.value)

        pending.offer("first")
        assertEquals("first", pending.state.value)
        pending.taken("first")
        assertNull(pending.state.value)

        // A second link tap's dialog arrives while the screen is still launching the first.
        pending.offer("first")
        pending.offer("second")
        pending.taken("first")
        assertEquals("second", pending.state.value)
    }
}
