package dev.earshot.service.session

import dev.earshot.bt.describeForLog
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneState
import dev.earshot.protocol.BmapPacket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Commands that return a whole new [HeadphoneState] (Bose's `HeadphoneProtocol`, the Crusher's
 * `CrusherProtocol`), and the pushes ([P]) that must not be lost under them.
 *
 * Why pushes are deferred: [run] computes its result from a snapshot taken before the request,
 * then replaces the state with it. A push applied while the command holds [mutex] would only be
 * overwritten, so it is parked and merged on top of the result instead (also when the command
 * failed: the push is real either way). Drivers whose commands update the state field by field
 * (Soundcore, Sennheiser, Sony) must not use this: their pushes apply at once.
 *
 * Pushes are applied only while holding [mutex] themselves (taken with `tryLock`, never waited
 * for): a push checked as "no command running" could otherwise be overwritten by a command that
 * took its snapshot in between. Whoever releases the mutex last applies what is parked, so a
 * push that lands between a command's final drain and its unlock is not left waiting for the
 * next push or command.
 */
class StateCommands<C : Any, P : Any>(
    private val state: MutableStateFlow<HeadphoneState>,
    private val mutex: Mutex,
    /** The open link, or throws the driver's not-connected [HeadphoneCommandException]. */
    private val requireConnection: () -> C,
    private val log: SessionLog,
    /** The state after push [P], or null to ignore it. */
    private val onPush: (P, HeadphoneState) -> HeadphoneState?,
    /** A driver's own translation of an exception (Bose: a device ERROR); null = wrap it as usual. */
    private val mapError: (Exception) -> HeadphoneCommandException? = { null },
) {
    private val deferred = ConcurrentLinkedQueue<P>()

    /** At session start: anything left from the previous session describes a dead link. */
    fun reset() = deferred.clear()

    /** A push from the headset: applied now, or parked while a command holds [mutex]. */
    fun push(packet: P) {
        // Always through the queue: older pushes still parked (a lock holder that doesn't drain)
        // go first, or they would later overwrite this newer one.
        deferred += packet
        drainIfFree()
    }

    /**
     * Run one command under [mutex]: [block] gets the link and the current state and returns the
     * next state. [showBusy] false for background reads (the Crusher's battery poll) so they do not
     * grey out the controls. A [HeadphoneCommandException] passes through, [mapError] goes next,
     * anything else is logged and wrapped with its own message.
     */
    suspend fun run(name: String, showBusy: Boolean = true, block: suspend (C, HeadphoneState) -> HeadphoneState) {
        try {
            mutex.withLock {
                val conn = requireConnection()
                if (showBusy) state.update { it.copy(isBusy = true) }
                try {
                    apply(block(conn, state.value))
                } catch (e: HeadphoneCommandException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mapError(e)?.let { throw it }
                    log.w("$name failed", e)
                    throw HeadphoneCommandException(e.message ?: "Command failed", e)
                } finally {
                    // Pushes parked while we held the mutex are real whether or not the command
                    // succeeded; merge them on top of whatever the state is now.
                    drain()
                    if (showBusy) state.update { it.copy(isBusy = false) }
                }
            }
        } finally {
            // A push that came in after the drain above, while the mutex was still ours, was parked
            // and found the lock taken; nothing else would apply it until the next push or command.
            drainIfFree()
        }
    }

    /** Merge a protocol result into the state, keeping the fields the repository owns. */
    private fun apply(next: HeadphoneState) {
        state.update { cur ->
            next.copy(connection = cur.connection, isBusy = cur.isBusy, lastUpdatedMillis = System.currentTimeMillis())
        }
    }

    private fun applyPush(packet: P) {
        val next = try {
            onPush(packet, state.value)
        } catch (e: Exception) {
            log.w("onUnsolicited failed for ${describe(packet)}: ${e.message}")
            null
        }
        if (next != null) apply(next)
    }

    // A Bose frame prints without its payload: block 4 carries other people's device names and
    // MACs, and log lines reach the connection report users paste into bug reports.
    private fun describe(packet: P): String = (packet as? BmapPacket)?.describeForLog() ?: packet.toString()

    /** Applies the parked pushes in order. For code that holds [mutex] outside [run] (the Bose Lab console). */
    fun drain() {
        while (true) applyPush(deferred.poll() ?: break)
    }

    /**
     * Applies the parked pushes under [mutex] if nobody holds it; otherwise the holder does (a
     * command at its end, the console on its way out). Never suspends. Loops because a push
     * parked while this drain held the lock found it taken and left the work to us. Code that
     * holds [mutex] outside [run] calls it after letting go, for a push that came after its [drain].
     */
    fun drainIfFree() {
        while (deferred.isNotEmpty() && mutex.tryLock()) {
            try {
                drain()
            } finally {
                mutex.unlock()
            }
        }
    }
}
