package dev.earshot.bt

import dev.earshot.core.HeadphoneCommandException
import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.BmapPacket
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WriteGuardTest {
    private fun p(block: Int, fn: Int, op: BmapOperator) = BmapPacket(block, fn, op, byteArrayOf(0))

    @Test
    fun `reads are always allowed`() {
        assertNull(WriteGuard.refusalReason(p(3, 1, BmapOperator.GET)))
        assertNull(WriteGuard.refusalReason(p(18, 24, BmapOperator.GET)))
        assertNull(WriteGuard.refusalReason(p(7, 5, BmapOperator.GET)))
    }

    @Test
    fun `firmware update and authentication blocks are read-only`() {
        assertFailsWith<HeadphoneCommandException> { WriteGuard.check(p(3, 2, BmapOperator.START)) }
        assertFailsWith<HeadphoneCommandException> { WriteGuard.check(p(3, 4, BmapOperator.SET_GET)) }
        assertFailsWith<HeadphoneCommandException> { WriteGuard.check(p(18, 19, BmapOperator.START)) }
    }

    @Test
    fun `destructive control functions are refused but normal ones pass`() {
        assertFailsWith<HeadphoneCommandException> { WriteGuard.check(p(7, 5, BmapOperator.START)) }
        assertFailsWith<HeadphoneCommandException> { WriteGuard.check(p(7, 6, BmapOperator.START)) }
        assertFailsWith<HeadphoneCommandException> { WriteGuard.check(p(7, 11, BmapOperator.START)) }
        assertFailsWith<HeadphoneCommandException> { WriteGuard.check(p(4, 7, BmapOperator.START)) }
        assertNull(WriteGuard.refusalReason(p(7, 4, BmapOperator.START)))   // power off
        assertNull(WriteGuard.refusalReason(p(31, 9, BmapOperator.START)))  // reset a custom slot
        assertNull(WriteGuard.refusalReason(p(31, 10, BmapOperator.SET_GET)))
        assertNull(WriteGuard.refusalReason(p(22, 5, BmapOperator.SET_GET))) // Print console
    }
}
