package dev.earshot.bt

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The blocking socket connect must not outlive its timeout (issue #1: a Xiaomi kept "ch9" and
 * "SDP BMAP" blocked for 78 and 82 s with a 6 s timeout). Real threads and real time, with short
 * timeouts; each test releases its own latches so no thread outlives it.
 */
class AbandonableCallTest {

    private fun millisSince(start: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

    @Test
    fun `a call that finishes returns its result`() = runBlocking {
        assertEquals(42, runAbandonable("t", abort = { error("not aborted") }) { 42 })
    }

    @Test
    fun `a call that fails throws its own exception`() {
        assertFailsWith<IOException> {
            runBlocking { runAbandonable<Unit>("t", abort = { }) { throw IOException("read failed") } }
        }
    }

    @Test
    fun `a stack that ignores the abort cannot hold the caller past the timeout`() = runBlocking {
        val stuck = CountDownLatch(1)
        val aborted = CountDownLatch(1)
        val start = System.nanoTime()
        val result = withTimeoutOrNull(100) {
            runAbandonable("t", abort = { aborted.countDown() }, graceMs = 200) { stuck.await(30, TimeUnit.SECONDS) }
        }
        val elapsed = millisSince(start)
        assertNull(result, "timed out")
        assertTrue(elapsed < 3_000, "released after the grace period, not when the stack gives up (took $elapsed ms)")
        assertTrue(aborted.await(2, TimeUnit.SECONDS), "the socket was still closed")
        stuck.countDown() // the abandoned call finishes on its own thread without taking anything down
    }

    @Test
    fun `an abort that itself blocks does not hold the caller either`() = runBlocking {
        val stuck = CountDownLatch(1)
        val start = System.nanoTime()
        val result = withTimeoutOrNull(100) {
            runAbandonable("t", abort = { stuck.await(30, TimeUnit.SECONDS) }, graceMs = 200) { stuck.await(30, TimeUnit.SECONDS) }
        }
        val elapsed = millisSince(start)
        assertNull(result)
        assertTrue(elapsed < 3_000, "took $elapsed ms")
        stuck.countDown()
    }

    @Test
    fun `a call that does end when aborted keeps its own exception`() {
        // BluetoothSocket.connect() aborted by close() fails with "read failed, socket might closed ...":
        // RfcommTransport.open() classifies that message as the busy signature, so it must survive.
        val gate = CountDownLatch(1)
        val e = assertFailsWith<IOException> {
            runBlocking {
                withTimeoutOrNull(100) {
                    runAbandonable<Unit>("t", abort = { gate.countDown() }) {
                        gate.await(30, TimeUnit.SECONDS)
                        throw IOException("read failed, socket might closed or timeout, read ret: -1")
                    }
                }
            }
        }
        assertTrue("socket might closed" in e.message.orEmpty())
    }
}
