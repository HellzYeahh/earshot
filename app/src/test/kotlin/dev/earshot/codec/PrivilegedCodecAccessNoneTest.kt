package dev.earshot.codec

import dev.earshot.core.PrivilegeState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Google Play build's privileged codec route (B8): none. CodecRepositoryImpl relies on it never
 * looking usable, so the panel stays on the companion link and the public readouts, and on
 * [PrivilegedCodecAccess.included] being false, so no message points at Shizuku.
 */
class PrivilegedCodecAccessNoneTest {
    private val none = PrivilegedCodecAccess.None

    @Test fun `it is never usable`() = runTest {
        assertFalse(none.included)
        assertEquals(PrivilegeState.UNAVAILABLE, none.state())
        assertFalse(none.request())
        assertTrue(none.exec("dumpsys bluetooth_manager").isFailure)
    }

    @Test fun `reads and switches fail instead of pretending`() = runTest {
        assertFailsWith<IllegalStateException> { none.readStatus("00:11:22:33:44:55") }
        assertFailsWith<IllegalStateException> {
            none.select("00:11:22:33:44:55", CodecRequest("AAC", 2, 0, 0, 0L))
        }
    }

    @Test fun `watching and closing do nothing`() {
        var calls = 0
        none.watch(onAppeared = { calls++ }, onGone = { calls++ })
        none.close()
        assertEquals(0, calls)
    }
}
