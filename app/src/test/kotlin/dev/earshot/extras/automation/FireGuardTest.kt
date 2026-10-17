package dev.earshot.extras.automation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FireGuardTest {
    @Test
    fun `same rule within five seconds is suppressed`() {
        val g = FireGuard(5_000)
        assertTrue(g.shouldFire("a", 1_000))
        assertFalse(g.shouldFire("a", 3_000))
        assertFalse(g.shouldFire("a", 5_999))
        assertTrue(g.shouldFire("a", 6_000))
    }

    @Test
    fun `different rules do not interfere`() {
        val g = FireGuard(5_000)
        assertTrue(g.shouldFire("a", 1_000))
        assertTrue(g.shouldFire("b", 1_000))
    }

    @Test
    fun `suppressed attempts do not extend the window`() {
        val g = FireGuard(5_000)
        assertTrue(g.shouldFire("a", 0))
        assertFalse(g.shouldFire("a", 4_000))
        assertTrue(g.shouldFire("a", 5_000))
    }

    @Test
    fun `reset forgets everything`() {
        val g = FireGuard(5_000)
        assertTrue(g.shouldFire("a", 0))
        g.reset()
        assertTrue(g.shouldFire("a", 1))
    }
}
