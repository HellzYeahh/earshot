package dev.earshot.bt

import org.junit.Test
import kotlin.test.assertEquals

class ReconnectBackoffTest {
    @Test
    fun `schedule is 1 2 5 10 then 30 seconds forever`() {
        val seen = (0 until 8).map { ReconnectBackoff.delayMs(it) }
        assertEquals(listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 30_000L, 30_000L, 30_000L), seen)
    }

    @Test
    fun `negative attempt clamps to the first step`() {
        assertEquals(1_000L, ReconnectBackoff.delayMs(-3))
    }

    @Test
    fun `slow retry is five minutes, well past the last fast step`() {
        assertEquals(300_000L, ReconnectBackoff.SLOW_RETRY_MS)
        assertEquals(true, ReconnectBackoff.SLOW_RETRY_MS > ReconnectBackoff.delayMs(ReconnectBackoff.MAX_ATTEMPTS))
    }
}
