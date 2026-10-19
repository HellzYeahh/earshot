package dev.earshot.shortcut

import android.util.Log
import dev.earshot.core.ButtonAction
import dev.earshot.core.ButtonAssignment
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.HeadphoneState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the Bose Shortcut button in the mode the remapper's choice needs ([ShortcutButtonPolicy]),
 * so the user never sets it up by hand.
 *
 * Two paths, and neither writes when the mode already matches:
 * - [applyChoice]: right after the user picks something in the remapper (errors go to the UI).
 * - [start]: once per connection, when the connected headphones' `[1.9]` has been read and no
 *   command is in flight; only for a choice the user made (see [ShortcutButtonPolicy.writeOnConnect]).
 *   It never retries within a connection, so a refused write can't turn into a loop.
 */
class ShortcutButtonSync(
    private val headphones: HeadphoneRepository,
    /** The saved settings ([ShortcutPrefs.current] in the app; a lambda so tests need no DataStore). */
    private val settings: suspend () -> ShortcutSettings,
    private val scope: CoroutineScope,
    /** Log sink; `android.util.Log` is a throwing stub in plain JVM tests. */
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    private data class Readiness(val address: String?, val ready: Boolean)

    /** The address already handled in the current connection (null while disconnected). */
    private var handledAddress: String? = null

    /** Last non-empty supported list per headset and button (upper-case address + button id). */
    private val supportedByAddress = ConcurrentHashMap<Pair<String, Int>, List<ButtonAction>>()

    /**
     * The connected headphones' Shortcut button with the supported modes filled in from the last
     * full `[1.9]` read when this frame carried none: a 3-byte echo or push has no mask, and an
     * empty list would otherwise hide the native options and block every write.
     */
    fun button(state: HeadphoneState): ButtonAssignment? = state.shortcutButton?.let { withSupport(state, it) }

    /** Every `[1.9]` record (primary first), each with its remembered supported modes. */
    fun buttons(state: HeadphoneState): List<ButtonAssignment> = state.allShortcutButtons.map { withSupport(state, it) }

    private fun withSupport(state: HeadphoneState, b: ButtonAssignment): ButtonAssignment {
        val address = state.connection.deviceAddress?.uppercase() ?: return b
        val key = address to b.buttonId
        if (b.supportedActions.isNotEmpty()) supportedByAddress[key] = b.supportedActions
        return ShortcutButtonPolicy.withKnownSupport(b, supportedByAddress[key])
    }

    fun start() {
        scope.launch {
            headphones.state
                .map { s -> Readiness(s.connection.deviceAddress.takeIf { s.isConnected }, s.isConnected && button(s) != null && !s.isBusy) }
                .distinctUntilChanged()
                .collect { r ->
                    if (r.address == null) { handledAddress = null; return@collect }
                    if (!r.ready || handledAddress.equals(r.address, ignoreCase = true)) return@collect
                    handledAddress = r.address
                    reapply(r.address)
                }
        }
    }

    private suspend fun reapply(address: String) {
        val saved = runCatching { settings() }.getOrNull() ?: return
        ShortcutButtonPolicy.writeOnConnect(saved, address, button(headphones.state.value))?.let { mode ->
            log("setting the shortcut button to ${mode.name} for the saved choice")
            writeOnConnect { headphones.setShortcutButton(mode) }
        }
        // The other buds' / buttons' records: only what the user set for them.
        for (b in buttons(headphones.state.value).drop(1)) {
            val mode = SecondaryButtonPolicy.writeFor(saved.buttonChoiceFor(address, b.buttonId), b) ?: continue
            log("setting button 0x%02x to ${mode.name} for the saved choice".format(b.buttonId))
            writeOnConnect { headphones.setShortcutButton(b.buttonId, b.eventType, mode) }
        }
    }

    private suspend fun writeOnConnect(write: suspend () -> Unit) {
        try {
            write()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("shortcut button write on connect failed: ${e.message}")
        }
    }

    /**
     * Writes [mode] to the secondary `[1.9]` record [buttonId] (with its own event type), if it
     * differs. Throws `HeadphoneCommandException` when refused.
     */
    suspend fun applySecondary(buttonId: Int, mode: ButtonAction) {
        val state = headphones.state.value
        if (!state.isConnected) return
        val b = buttons(state).firstOrNull { it.buttonId == buttonId } ?: return
        if (b.action == mode || mode !in b.supportedActions) return
        log("setting button 0x%02x to ${mode.name}".format(buttonId))
        headphones.setShortcutButton(b.buttonId, b.eventType, mode)
    }

    /**
     * Writes the mode [choice] needs to the connected headphones, if it differs. Throws
     * `HeadphoneCommandException` (user-readable) when the headphones refuse; the saved choice
     * stays either way.
     */
    suspend fun applyChoice(choice: ShortcutAction) {
        val state = headphones.state.value
        if (!state.isConnected) return
        val mode = ShortcutButtonPolicy.writeFor(choice, button(state)) ?: return
        log("setting the shortcut button to ${mode.name}")
        headphones.setShortcutButton(mode)
    }

    private companion object {
        const val TAG = "Earshot/Shortcut"
    }
}
