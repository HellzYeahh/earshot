package dev.earshot.protocol.gaia

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GaiaRequestQueueTest {

    /** A headset that answers every request through [answer] (null = silent). */
    private class Rig(val answer: (GaiaFrame) -> List<GaiaFrame>?) {
        val sent = mutableListOf<GaiaFrame>()
        val unsolicited = mutableListOf<GaiaFrame>()
        private val decoder = GaiaFrameBuffer()
        lateinit var queue: GaiaRequestQueue

        fun build(policy: GaiaWritePolicy = GaiaWritePolicy { null }): Rig {
            queue = GaiaRequestQueue(send = { bytes ->
                for (f in decoder.feed(bytes)) {
                    sent += f
                    answer(f)?.forEach { reply -> if (!queue.offer(reply)) unsolicited += reply }
                }
            }, policy = policy)
            return this
        }
    }

    private fun ok(f: GaiaFrame, vararg payload: Int) = GaiaFrame(f.vendor, GaiaCommand.response(f.command), ByteArray(payload.size) { payload[it].toByte() })

    @Test fun `a request gets its response, notifications pass through`() = runTest {
        val rig = Rig { f -> listOf(ok(f), GaiaFrame(0x0495, 0x1A85, byteArrayOf(1))) }.build()
        val reply = rig.queue.request(GaiaFrame(0x0495, 0x1A04, byteArrayOf(1)), 1_000)
        assertIs<GaiaReply.Ok>(reply)
        assertEquals(listOf(0x1A85), rig.unsolicited.map { it.command })
    }

    @Test fun `an error frame becomes its reason`() = runTest {
        val rig = Rig { f -> listOf(GaiaFrame(f.vendor, GaiaCommand.error(f.command), byteArrayOf(5))) }.build()
        val reply = rig.queue.request(GaiaFrame(0x0495, 0x1601, byteArrayOf(0, 1)), 1_000)
        assertEquals(5, assertIs<GaiaReply.Error>(reply).code)
    }

    @Test fun `a response for another command or vendor does not answer`() = runTest {
        val rig = Rig { f ->
            listOf(
                GaiaFrame(f.vendor, GaiaCommand.response(0x1A03), byteArrayOf(0)), // wrong command
                GaiaFrame(GaiaVendor.QUALCOMM, GaiaCommand.response(f.command)), // wrong vendor
                ok(f, 42),
            )
        }.build()
        val reply = assertIs<GaiaReply.Ok>(rig.queue.request(GaiaFrame(0x0495, 0x0603), 1_000))
        assertContentEquals(byteArrayOf(42), reply.payload)
        assertEquals(2, rig.unsolicited.size)
    }

    @Test fun `silence times out instead of hanging`() = runTest {
        val rig = Rig { null }.build()
        assertFailsWith<GaiaTimeoutException> { rig.queue.request(GaiaFrame(0x0495, 0x0603), 9_000) }
        assertEquals(9_000, testScheduler.currentTime)
    }

    @Test fun `the policy refuses before anything is sent`() = runTest {
        val rig = Rig { f -> listOf(ok(f)) }.build(policy = { f -> if (f.command == 0x0040) "factory reset" else null })
        assertFailsWith<GaiaRefusedException> { rig.queue.request(GaiaFrame(0x0495, 0x0040), 1_000) }
        assertTrue(rig.sent.isEmpty())
    }

    @Test fun `requests go one at a time, in order`() = runTest {
        val replies = mutableListOf<() -> Unit>()
        lateinit var queue: GaiaRequestQueue
        val sent = mutableListOf<Int>()
        queue = GaiaRequestQueue(send = { bytes ->
            val f = GaiaFrameBuffer().feed(bytes).single()
            sent += f.command
            replies += { queue.offer(GaiaFrame(f.vendor, GaiaCommand.response(f.command))) }
        }, policy = { null })
        val a = async { queue.request(GaiaFrame(0x0495, 0x0603), 5_000) }
        val b = async { queue.request(GaiaFrame(0x0495, 0x1A05), 5_000) }
        testScheduler.runCurrent()
        assertEquals(listOf(0x0603), sent, "the second waits for the first's answer")
        replies.removeAt(0).invoke()
        testScheduler.runCurrent()
        assertEquals(listOf(0x0603, 0x1A05), sent)
        replies.removeAt(0).invoke()
        assertIs<GaiaReply.Ok>(a.await())
        assertIs<GaiaReply.Ok>(b.await())
    }

    @Test fun `closing fails the waiting request and refuses new ones`() = runTest {
        val queue = GaiaRequestQueue(send = {}, policy = { null })
        val waiting = async { runCatching { queue.request(GaiaFrame(0x0495, 0x0603), 60_000) } }
        testScheduler.runCurrent()
        queue.close()
        assertIs<GaiaLinkClosedException>(waiting.await().exceptionOrNull())
        assertFailsWith<GaiaLinkClosedException> { queue.request(GaiaFrame(0x0495, 0x0603), 1_000) }
    }

    @Test fun `discovery reads the version and pages through Qualcomm's list only`() = runTest {
        val rig = Rig { f ->
            when {
                f.vendor == GaiaVendor.QUALCOMM && f.command == 0x0000 -> listOf(ok(f, 3, 1))
                f.vendor == GaiaVendor.QUALCOMM && f.command == 0x0001 -> listOf(ok(f, 1, 0x00, 0x04))
                f.vendor == GaiaVendor.QUALCOMM && f.command == 0x0002 -> listOf(ok(f, 0, 0x0C, 0x01))
                f.vendor == 0x0495 && f.command == 0x0001 -> listOf(ok(f, 1, 0x0D, 0x01)) // "more", but no next page for vendors
                else -> null
            }
        }.build()
        assertEquals(GaiaApiVersion(3, 1), GaiaDiscovery.apiVersion(rig.queue, 1_000))
        assertEquals(mapOf(0 to 4, 12 to 1), GaiaDiscovery.features(rig.queue, GaiaVendor.QUALCOMM, 1_000))
        assertEquals(mapOf(13 to 1), GaiaDiscovery.features(rig.queue, 0x0495, 1_000))
        assertFalse(rig.sent.any { it.vendor == 0x0495 && it.command == 0x0002 }, "never guesses a vendor's next-page id")
    }

    @Test fun `a refused feature list reads as null`() = runTest {
        val rig = Rig { f -> listOf(GaiaFrame(f.vendor, GaiaCommand.error(f.command), byteArrayOf(1))) }.build()
        assertNull(GaiaDiscovery.features(rig.queue, 0x0495, 1_000))
        assertNull(GaiaDiscovery.apiVersion(rig.queue, 1_000))
    }

    @Test fun `a late reply after a timeout does not answer the next request`() = runTest {
        lateinit var queue: GaiaRequestQueue
        var count = 0
        queue = GaiaRequestQueue(send = { bytes ->
            val f = GaiaFrameBuffer().feed(bytes).single()
            count++
            if (count == 2) {
                // The first request's answer finally arrives, then the second's.
                assertFalse(queue.offer(GaiaFrame(f.vendor, GaiaCommand.response(0x0603), byteArrayOf(1))))
                queue.offer(GaiaFrame(f.vendor, GaiaCommand.response(f.command), byteArrayOf(2)))
            }
        }, policy = { null })
        assertFailsWith<GaiaTimeoutException> { queue.request(GaiaFrame(0x0495, 0x0603), 1_000) }
        val reply = assertIs<GaiaReply.Ok>(queue.request(GaiaFrame(0x0495, 0x1A05), 1_000))
        assertContentEquals(byteArrayOf(2), reply.payload)
    }
}
