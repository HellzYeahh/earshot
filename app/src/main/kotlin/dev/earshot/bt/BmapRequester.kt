package dev.earshot.bt

import dev.earshot.protocol.BmapPacket
import dev.earshot.protocol.FunctionAddress

/**
 * The slice of [BmapConnection] the typed protocol layer uses. Exists so
 * `QcUltra2Protocol` can be unit-tested against a scripted fake instead of a socket.
 */
interface BmapRequester {
    /** False once the link dropped or [BmapConnection.close] ran; requests then fail immediately. */
    val isOpen: Boolean

    /**
     * Functions that stayed silent while the link still answered `[0.1]`, so this firmware lacks
     * them (it ignores the request instead of answering FuncNotSupp). Per connection.
     */
    val silentFunctions: Set<FunctionAddress> get() = emptySet()

    /**
     * Send [packet] and collect its reply according to [mode]. Throws
     * [dev.earshot.core.HeadphoneCommandException] ("no reply") after [timeoutMs] without a first
     * matching frame, or when the link drops. ERROR frames are *returned*, not thrown.
     */
    suspend fun request(
        packet: BmapPacket,
        mode: ReplyMode = ReplyMode.SINGLE,
        timeoutMs: Long = BmapConnection.DEFAULT_TIMEOUT_MS,
    ): List<BmapPacket>

    /** Write without waiting for a reply (power off, pairing mode: the "reply" is the link going away). */
    suspend fun send(packet: BmapPacket)
}
