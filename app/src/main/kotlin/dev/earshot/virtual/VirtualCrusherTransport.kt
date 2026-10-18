package dev.earshot.virtual

import dev.earshot.bt.BragiFrameReader
import dev.earshot.bt.ByteTransport
import dev.earshot.protocol.bragi.BosRpcPacket
import dev.earshot.protocol.bragi.PppFraming
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [ByteTransport] backed by a [VirtualCrusher] instead of an RFCOMM socket. Requests are PPP frames
 * (found with the same [BragiFrameReader] the app uses, so any vendor header in front is skipped);
 * replies are PPP frames, optionally wrapped in a vendor-style header so the app's receive side is
 * exercised against bytes that are not a bare frame.
 *
 * @param replyWrapper wraps each reply on its way out; the default adds nothing.
 * @param silentUntilWrapped when set, the device ignores requests that did not arrive in that exact
 *   vendor wrapping (models firmware that only answers the right wrapper) so the app's
 *   try-each-wrapper logic can be tested.
 */
class VirtualCrusherTransport(
    val device: VirtualCrusher = VirtualCrusher(),
    private val replyDelayMs: Long = 20L,
    private val replyWrapper: (ByteArray) -> ByteArray = { it },
    private val acceptsRequest: (ByteArray) -> Boolean = { true },
) : ByteTransport {

    private val requests = Channel<ByteArray>(Channel.UNLIMITED)
    private val closed = AtomicBoolean(false)
    private val collected = AtomicBoolean(false)
    private val reader = BragiFrameReader()

    /** Frames to push at the app on its own (a button press changing the mode). */
    private val unsolicited = Channel<List<BosRpcPacket>>(Channel.UNLIMITED)

    override val isConnected: Boolean get() = !closed.get()

    /** Replies to the app's requests. */
    private val replies: Flow<ByteArray> = channelFlow {
        check(collected.compareAndSet(false, true)) { "incoming must be collected exactly once" }
        if (closed.get()) throw IOException("Virtual transport already closed")
        for (bytes in requests) {
            if (!acceptsRequest(bytes)) continue
            val replies = reader.feed(bytes).flatMap { frame ->
                // Only cleartext requests are understood; the secure path is not modelled here.
                if (frame.protocolId != PppFraming.PROTOCOL_CLEARTEXT) emptyList()
                else device.handle(BosRpcPacket.decode(frame.body))
            }
            delay(replyDelayMs)
            if (replies.isNotEmpty()) send(encode(replies))
        }
    }

    /** Replies plus the frames the device pushes by itself, merged into the one byte stream the app reads. */
    override val incoming: Flow<ByteArray> = merge(replies, unsolicited.receiveAsFlow().map { encode(it) })

    private fun encode(packets: List<BosRpcPacket>): ByteArray =
        packets.fold(ByteArray(0)) { acc, p -> acc + replyWrapper(PppFraming.frame(p.encode())) }

    /** Push frames at the app as if the headphones sent them by themselves. */
    fun push(packets: List<BosRpcPacket>) { unsolicited.trySend(packets) }

    override suspend fun write(bytes: ByteArray) {
        if (!isConnected) throw IOException("Virtual transport is not connected")
        try {
            requests.send(bytes)
        } catch (e: ClosedSendChannelException) {
            throw IOException("Virtual transport closed", e)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) { requests.close(); unsolicited.close() }
    }
}
