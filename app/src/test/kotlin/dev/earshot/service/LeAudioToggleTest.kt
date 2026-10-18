package dev.earshot.service

import dev.earshot.bt.BmapRequester
import dev.earshot.bt.ReplyMode
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneState
import dev.earshot.protocol.BmapError
import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.BmapPacket
import dev.earshot.protocol.qcultra2.QcUltra2
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `[1.37]` written on headphones that support LE Audio, ignore the write, or have no such switch. */
class LeAudioToggleTest {

    /** Answers `[1.37]` with [reply] (null = silence, as when the headphones restart at once). */
    private class OneShot(private val reply: (BmapPacket) -> BmapPacket?) : BmapRequester {
        val sent = ArrayList<BmapPacket>()
        override val isOpen: Boolean = true
        override suspend fun request(packet: BmapPacket, mode: ReplyMode, timeoutMs: Long): List<BmapPacket> {
            sent += packet
            return listOf(reply(packet) ?: throw HeadphoneCommandException("${packet.address}: no reply from the headphones"))
        }
        override suspend fun send(packet: BmapPacket) { sent += packet }
    }

    private val protocol = QcUltra2Protocol()

    @Before
    fun quiet() { ProtocolLog.sink = { _, _, _ -> } }

    @Test
    fun `a restart right after the write counts as success`() = runTest {
        val c = OneShot { null }
        val s = protocol.setLeAudioEnabled(c, HeadphoneState(leAudioSupported = true, leAudioEnabled = false), true)
        assertEquals(true, s.leAudioEnabled)
        assertEquals(BmapOperator.SET_GET, c.sent.single().operator)
        assertEquals(QcUltra2.LE_AUDIO_CONTROL, c.sent.single().address)
    }

    @Test
    fun `it can be forced on headphones that report no support`() = runTest {
        val c = OneShot { p -> BmapPacket(p.block, p.function, BmapOperator.STATUS, byteArrayOf(1)) }
        val s = protocol.setLeAudioEnabled(c, HeadphoneState(leAudioSupported = false, leAudioEnabled = null), true)
        assertEquals(true, s.leAudioEnabled)
        assertEquals(1, c.sent.size, "the write is sent even though [0.23] said no")
    }

    @Test
    fun `firmware without the switch gets a message that explains it`() = runTest {
        // OpNotSupp (5) is not here: the switch exists but can't be changed (QcUltra2NewFeaturesTest).
        for (error in listOf(BmapError.FUNC_NOT_SUPPORTED, BmapError.FBLOCK_NOT_SUPPORTED)) {
            val c = OneShot { p -> BmapPacket(p.block, p.function, BmapOperator.ERROR, byteArrayOf(error.code.toByte())) }
            val e = assertFailsWith<HeadphoneCommandException> {
                protocol.setLeAudioEnabled(c, HeadphoneState(leAudioSupported = false), true)
            }
            assertEquals(QcUltra2Protocol.LE_AUDIO_ABSENT_MESSAGE, e.message, "$error")
            assertTrue(e.message!!.contains("firmware update"))
        }
    }
}
