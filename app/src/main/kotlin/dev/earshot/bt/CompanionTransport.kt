package dev.earshot.bt

import kotlinx.coroutines.flow.Flow

/**
 * A [ByteTransport] whose reads and writes go to [primary] while a second socket, [companion], is
 * only kept connected and closed together with it. The companion is never written to and its bytes
 * are never read: it exists so the headphones see both services connected, as with the official app.
 */
class CompanionTransport(private val primary: ByteTransport, private val companion: ByteTransport) : ByteTransport {
    override val isConnected: Boolean get() = primary.isConnected
    override val incoming: Flow<ByteArray> get() = primary.incoming
    override suspend fun write(bytes: ByteArray) = primary.write(bytes)

    override fun close() {
        runCatching { primary.close() }
        runCatching { companion.close() }
    }
}
