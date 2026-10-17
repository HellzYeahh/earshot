package dev.earshot.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallVolumeBoostTest {

    @Test fun `100 percent is the maximum`() {
        assertEquals(7, CallVolumeBoost.target(max = 7, percent = 100))
        assertEquals(15, CallVolumeBoost.target(max = 15, percent = 100, min = 1))
    }

    @Test fun `rounds to nearest index`() {
        assertEquals(8, CallVolumeBoost.target(max = 15, percent = 50)) // 7.5
        assertEquals(6, CallVolumeBoost.target(max = 7, percent = 80))  // 5.6
        assertEquals(5, CallVolumeBoost.target(max = 7, percent = 70))  // 4.9
    }

    @Test fun `percent is clamped to 10 to 100`() {
        assertEquals(7, CallVolumeBoost.target(max = 7, percent = 250))
        assertEquals(2, CallVolumeBoost.target(max = 15, percent = 0)) // 10% of 15 = 1.5
    }

    @Test fun `never below the stream minimum`() {
        assertEquals(1, CallVolumeBoost.target(max = 5, percent = 10, min = 1)) // 0.5 rounds to 1 anyway
        assertEquals(2, CallVolumeBoost.target(max = 7, percent = 10, min = 2))
    }

    @Test fun `unknown range gives no target`() {
        assertNull(CallVolumeBoost.target(max = 0, percent = 100))
        assertNull(CallVolumeBoost.target(max = -1, percent = 100))
    }

    @Test fun `auto boost only raises`() {
        assertTrue(CallVolumeBoost.shouldRaise(current = 3, target = 7))
        assertFalse(CallVolumeBoost.shouldRaise(current = 7, target = 7))
        assertFalse(CallVolumeBoost.shouldRaise(current = 7, target = 5))
    }
}
