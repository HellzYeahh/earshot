package dev.earshot.virtual

import dev.earshot.bt.ByteTransport
import dev.earshot.protocol.BmapPacket
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * [ByteTransport] backed by a [VirtualQcUltra2] instead of an RFCOMM socket, with the timing
 * quirks of the real link so the frame buffer, reply matching and reconnect paths all get
 * exercised:
 *
 * - the greeting burst is written as soon as [incoming] is collected;
 * - each request is answered after a 30-80 ms delay; replies to one request are serialised
 *   like a single-threaded firmware would;
 * - a multi-frame reply (the `[31.6]` slot dump) is written as two chunks split *mid-frame*
 *   with a ~50 ms gap, which is what a Bluetooth read of a long dump looks like;
 * - [VirtualQcUltra2.tick] runs every [tickIntervalMs], so battery pushes and the deferred
 *   `[31.3]` / `[31.10]` STATUS frames arrive unsolicited;
 * - `[7.4]` power off is answered and then the link drops: [incoming] fails with an
 *   [IOException] exactly like a socket reset, and [isConnected] goes false.
 *
 * All the machinery lives inside the [incoming] flow, so no external scope is needed; collect
 * it once (as [dev.earshot.bt.BmapConnection] does) and cancel/[close] to tear it down.
 *
 * @param random source for the reply delay; tests pass a seeded one.
 */
class VirtualTransport(
    val device: VirtualQcUltra2 = VirtualQcUltra2(),
    private val replyDelayMs: LongRange = 30L..80L,
    private val chunkGapMs: Long = 50L,
    private val tickIntervalMs: Long = 250L,
    private val sendGreeting: Boolean = true,
    private val random: Random = Random.Default,
) : ByteTransport {

    private val requests = Channel<BmapPacket>(Channel.UNLIMITED)
    private val closed = AtomicBoolean(false)
    private val collected = AtomicBoolean(false)

    @Volatile
    private var dropped = false

    override val isConnected: Boolean
        get() = !closed.get() && !dropped && device.isPoweredOn

    override val incoming: Flow<ByteArray> = channelFlow {
        check(collected.compareAndSet(false, true)) { "incoming must be collected exactly once" }
        if (closed.get()) throw IOException("Virtual transport already closed")
        device.onSessionStart()
        if (sendGreeting) send(device.greeting())
        val ticker = launch {
            while (isActive) {
                delay(tickIntervalMs)
                val pushes = device.tick()
                if (pushes.isNotEmpty()) send(encodeAll(pushes))
            }
        }
        try {
            for (request in requests) {
                val replies = device.handle(request)
                delay(random.nextLong(replyDelayMs.first, replyDelayMs.last + 1))
                if (replies.isNotEmpty()) sendChunked(encodeAll(replies))
                if (!device.isPoweredOn) {
                    // The real unit closes the RFCOMM link right after acknowledging power off
                    // (and after the [1.37] LE Audio toggle, which restarts it).
                    delay(chunkGapMs)
                    dropped = true
                    throw IOException("Connection reset by peer (virtual QC Ultra 2 powered off / restarting)")
                }
            }
        } finally {
            ticker.cancel()
        }
    }

    /** One frame goes out whole; anything longer is cut in two somewhere in the middle. */
    private suspend fun ProducerScope<ByteArray>.sendChunked(bytes: ByteArray) {
        if (bytes.size <= SINGLE_CHUNK_MAX) {
            send(bytes)
            return
        }
        val cut = (bytes.size / 2 + random.nextInt(-5, 6)).coerceIn(1, bytes.size - 1)
        send(bytes.copyOfRange(0, cut))
        delay(chunkGapMs)
        send(bytes.copyOfRange(cut, bytes.size))
    }

    override suspend fun write(bytes: ByteArray) {
        if (!isConnected) throw IOException("Virtual transport is not connected")
        val packets = BmapPacket.decodeAll(bytes)
        try {
            for (p in packets) requests.send(p)
        } catch (e: ClosedSendChannelException) {
            throw IOException("Virtual transport closed", e)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) requests.close()
    }

    companion object {
        /** Longest reply that is still written in one piece: a single max-size frame. */
        private const val SINGLE_CHUNK_MAX = BmapPacket.HEADER_SIZE + 255

        private fun encodeAll(frames: List<BmapPacket>): ByteArray {
            var out = ByteArray(0)
            for (f in frames) out += f.encode()
            return out
        }

        /**
         * What the integrator hands to the connection manager in demo mode: one shared
         * [VirtualQcUltra2] so state (EQ, modes, battery) survives reconnects, a fresh transport
         * per "connection". A unit that was powered off comes back on when the next connection
         * is attempted, as if the user pressed the button, so the manager's reconnect loop
         * finds it again after its backoff.
         */
        fun factory(device: VirtualQcUltra2 = VirtualQcUltra2()): () -> ByteTransport = {
            device.powerOn()
            VirtualTransport(device)
        }
    }
}
