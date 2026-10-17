package dev.earshot.bt

import kotlinx.coroutines.flow.Flow
import java.io.Closeable
import java.io.IOException

/**
 * What [BmapConnection] needs from a socket. [RfcommTransport] is the real one; unit tests
 * plug in a fake so reply matching and timeouts can be exercised without Bluetooth.
 */
interface ByteTransport : Closeable {
    val isConnected: Boolean

    /**
     * Incoming bytes as the socket delivers them (chunk boundaries are arbitrary). Collect it
     * exactly once. Completes normally when [close] is called, exceptionally when the link
     * drops underneath us.
     */
    val incoming: Flow<ByteArray>

    suspend fun write(bytes: ByteArray)

    override fun close()
}

/**
 * Every connect strategy failed. [looksBusy] is true when the failure signature matches
 * "someone else already holds the channel" (the Bose app), which the manager turns into a
 * useful message when the headphones are otherwise connected to the phone.
 */
class RfcommConnectException(
    message: String,
    val looksBusy: Boolean,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Why [BmapConnection] closed itself: the socket is open but nothing answers. Seen on a Galaxy
 * (Android 17, firmware 10.12) while the Bose app ran in the background: channel 2 connected,
 * the greeting was silent and every request timed out. A silent-but-open channel-2 socket is the
 * signature of another app holding BMAP, so the manager reports it as the busy-channel case.
 */
class UnresponsiveLinkException(message: String) : IOException(message)
