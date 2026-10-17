package dev.earshot.bt

import dev.earshot.core.HeadphoneCommandException
import dev.earshot.protocol.BmapPacket
import dev.earshot.protocol.FunctionAddress
import java.io.IOException

/**
 * One cheap request right after the socket opens, before the session is published as CONNECTED.
 *
 * Why: with the Bose app running in the background, `createInsecureRfcommSocket(2)` still
 * connects and the greeting is silent, but nothing ever answers (Galaxy, Android 17, firmware
 * 10.12). Without this the user saw CONNECTED with an empty battery and no modes, and the
 * initial refresh burned three 2 s timeouts before giving up. One `[2.2]` GET settles it in 2 s
 * and sends the failure down the existing busy-channel path.
 */
internal object LivenessProbe {
    /** Battery: GET-only, answered by every firmware, tiny reply. */
    val ADDRESS = FunctionAddress(2, 2)
    const val TIMEOUT_MS = 2_000L

    /**
     * Returns when [connection] answers (any frame, ERROR included, or any stray byte). Otherwise
     * closes it and throws [RfcommConnectException] with `looksBusy = true`; if the link dropped
     * during the probe, a plain [IOException] (that is not the busy signature).
     */
    suspend fun check(connection: BmapConnection, timeoutMs: Long = TIMEOUT_MS) {
        try {
            // Unwatched: this single probe decides on its own; it must not prime the watchdog.
            connection.requestUnwatched(BmapPacket.get(ADDRESS), ReplyMode.SINGLE, timeoutMs)
            return
        } catch (e: HeadphoneCommandException) {
            if (connection.isOpen && connection.hasAnswered) {
                // [2.2] itself stayed quiet but something arrived (a push, a stray frame): alive.
                BtLog.w("liveness probe: ${ADDRESS} silent but the link sent other bytes; carrying on")
                return
            }
            val dropped = connection.closeCause
            connection.close()
            if (dropped != null) throw IOException("Link dropped during the liveness probe: ${dropped.message}", dropped)
            BtLog.w("liveness probe: no reply to $ADDRESS within ${timeoutMs}ms; channel 2 is open but silent (another app holding BMAP?)")
            throw RfcommConnectException(
                "Channel 2 opened but the headphones did not answer (another app is probably holding it)",
                looksBusy = true,
                cause = e,
            )
        }
    }
}
