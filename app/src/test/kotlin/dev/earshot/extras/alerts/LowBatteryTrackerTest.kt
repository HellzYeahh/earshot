package dev.earshot.extras.alerts

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LowBatteryTrackerTest {
    @Test
    fun `notifies once when crossing the threshold and not again while it hovers`() {
        val t = LowBatteryTracker()
        assertFalse(t.onSample(40, false, 20))
        assertTrue(t.onSample(20, false, 20))
        assertFalse(t.onSample(19, false, 20))
        assertFalse(t.onSample(22, false, 20)) // inside hysteresis band
        assertFalse(t.onSample(18, false, 20))
    }

    @Test
    fun `re-arms after rising above threshold plus hysteresis`() {
        val t = LowBatteryTracker(hysteresis = 5)
        assertTrue(t.onSample(15, false, 20))
        assertFalse(t.onSample(25, false, 20)) // 25 is not > 25
        assertFalse(t.onSample(26, false, 20))
        assertTrue(t.onSample(20, false, 20))
    }

    @Test
    fun `charging resets the cycle`() {
        val t = LowBatteryTracker()
        assertTrue(t.onSample(10, false, 20))
        assertFalse(t.onSample(12, true, 20))
        assertTrue(t.onSample(12, false, 20))
    }

    @Test
    fun `off threshold and unknown level never notify`() {
        val t = LowBatteryTracker()
        assertFalse(t.onSample(5, false, null))
        assertFalse(t.onSample(null, false, 20))
        assertTrue(t.onSample(5, false, 20))
        assertFalse(t.onSample(5, null, 20))
    }
}
