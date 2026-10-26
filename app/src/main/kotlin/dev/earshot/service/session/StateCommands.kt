package dev.earshot.service.session

import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneState
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
        if (mutex.isLocked) {
            deferred += packet
            // The command may have released the mutex between the check and the add.
            if (!mutex.isLocked) drain()
        } else {
            applyPush(packet)
        }
    }

    /**
     * Run one command under [mutex]: [block] gets the link and the current state and returns the
     * next state. [showBusy] false for background reads (the Crusher's battery poll) so they do not
     * grey out the controls. A [HeadphoneCommandException] passes through, [mapError] goes next,
     * anything else is logged and wrapped with its own message.
     */
    suspend fun run(name: String, showBusy: Boolean = true, block: suspend (C, HeadphoneState) -> HeadphoneState) {
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
            log.w("onUnsolicited failed for $packet: ${e.message}")
            null
        }
        if (next != null) apply(next)
    }

    private fun drain() {
        while (true) applyPush(deferred.poll() ?: break)
    }
}
