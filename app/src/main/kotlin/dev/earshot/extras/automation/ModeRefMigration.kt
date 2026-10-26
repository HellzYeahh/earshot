package dev.earshot.extras.automation

import android.util.Log
import dev.earshot.core.AppPreferences
import dev.earshot.core.AutomationAction
import dev.earshot.core.AutomationRepository
import dev.earshot.core.AutomationRule
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.HeadphoneState
import dev.earshot.core.ListeningMode
import dev.earshot.core.ModeResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Binds 0.75's bare slot numbers (rules' `SetMode(modeIndex)`, the tile's `tile_cycle_modes`) to
 * mode meanings, on the first real headset that shows its modes after the upgrade. It reads the
 * slot as 0.75 meant it, on the headset connected now, and keeps the number as
 * [dev.earshot.core.ModeRef.legacySlot], so a migrated rule never does less than it did in 0.75:
 * on that headset it hits the same slots; elsewhere a built-in meaning wins where it exists, else
 * the old slot, unless that slot means Off there ([ModeResolver.slotFor]).
 *
 * Until a rule is bound, RuleRunner runs its slot exactly as 0.75 did, so nothing changes for it
 * before this has run. Rules are re-checked on every connect (only unbound ones change: a rule a
 * 0.75 build rewrote after a downgrade gets bound again); the tile once, behind
 * [AppPreferences.modeRefsMigrated]. Skipped in demo mode: every demo device shares a virtual
 * address and its modes, so binding to one would bind the user's rules to a headset they don't own.
 */
class ModeRefMigration(
    private val headphones: HeadphoneRepository,
    private val automation: AutomationRepository,
    private val demoEnabled: Flow<Boolean>,
    /** The Bose dump can arrive a little after the presets: wait for the mode list to settle. */
    private val settleMillis: Long = SETTLE_MILLIS,
    /** This runs by itself on every connect, so a store error (disk full, corrupt file) is logged, never thrown. */
    private val onError: (Throwable) -> Unit = { Log.w(TAG, "Mode migration failed; it is retried on the next connect", it) },
) {
    fun start(scope: CoroutineScope): Job = scope.launch {
        combine(headphones.state, demoEnabled) { state, demo -> if (demo) null else Ready.of(state) }
            .distinctUntilChanged()
            .collectLatest { ready ->
                if (ready == null) return@collectLatest
                delay(settleMillis)
                try {
                    migrate(ready.modes, ready.address)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(e)
                }
            }
    }

    /** Binds what can be bound on the headset at [address] with [modes]. */
    suspend fun migrate(modes: List<ListeningMode>, address: String) {
        // Only when there is something to bind. An empty list may be stored text this build can't
        // read, which only a user's own edit may replace.
        if (automation.rules.value.any(::hasUnboundMode)) {
            automation.updateRules { rules -> bindRules(rules, modes, address) }
        }
        if (!automation.preferences.value.modeRefsMigrated) {
            automation.updatePreferences { prefs -> migrateTile(prefs, modes, address) }
        }
    }

    /** The part of the state the migration depends on; null while it must not run. */
    private data class Ready(val address: String, val modes: List<ListeningMode>) {
        companion object {
            fun of(s: HeadphoneState): Ready? {
                val address = s.connection.deviceAddress ?: return null
                if (!s.isConnected || !s.capabilities.listeningModes) return null
                val configured = s.modes.filter { it.isConfigured }
                if (configured.isEmpty()) return null
                // Only what binding reads, so battery pushes and the like don't restart the wait.
                return Ready(address.uppercase(), configured.map { it.copy(settings = null, changeableSettings = null, isFavorite = false) })
            }
        }
    }

    companion object {
        const val SETTLE_MILLIS = 3_000L
        private const val TAG = "Earshot/Automation"

        private fun hasUnboundMode(rule: AutomationRule) = rule.actions.any { it is AutomationAction.SetMode && it.mode == null }

        /**
         * Every unbound `SetMode(i)` whose slot `i` is configured on this headset gets the meaning of
         * that slot here. One whose slot isn't configured stays as it is (it still runs as 0.75 did)
         * and is bound on a headset that has it.
         */
        fun bindRules(rules: List<AutomationRule>, modes: List<ListeningMode>, address: String): List<AutomationRule> =
            rules.map { rule ->
                if (!hasUnboundMode(rule)) return@map rule
                rule.copy(actions = rule.actions.map { action -> if (action is AutomationAction.SetMode) bind(action, modes, address) else action })
            }

        private fun bind(action: AutomationAction.SetMode, modes: List<ListeningMode>, address: String): AutomationAction.SetMode {
            if (action.mode != null) return action
            val mode = modes.firstOrNull { it.index == action.modeIndex && it.isConfigured } ?: return action
            return action.copy(mode = ModeResolver.refFor(mode, address).copy(legacySlot = action.modeIndex))
        }

        /**
         * The 0.75 tile list as refs, read on this headset; a slot it doesn't have becomes a custom
         * ref that only knows the number. Marks the tile migrated (also when the list is empty).
         */
        fun migrateTile(prefs: AppPreferences, modes: List<ListeningMode>, address: String): AppPreferences {
            if (prefs.modeRefsMigrated) return prefs
            val refs = prefs.tileCycleModes.distinct().map { ModeResolver.refForLegacySlot(it, modes, address) }
            return prefs.withTileCycle(refs)
        }
    }
}
