package dev.earshot.ui.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.earshot.backup.BackupError
import dev.earshot.backup.BackupException
import dev.earshot.backup.BackupInventory
import dev.earshot.backup.BackupReader
import dev.earshot.backup.BackupStaging
import dev.earshot.backup.BackupTransfer
import dev.earshot.backup.OwnSoundsInfo
import dev.earshot.backup.RestoreFailedNotice
import dev.earshot.backup.RestoredNotice
import dev.earshot.backup.StagedImport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BackupBusy { SAVING, CHECKING, RESTARTING }

/** The one line the screen shows about the last action. */
sealed interface BackupMessage {
    /** Saved; [leftOut] own sounds didn't fit a backup's caps (their names where known, else null). */
    data class Saved(val leftOut: List<String?> = emptyList()) : BackupMessage
    data object RestoreCancelled : BackupMessage
    data object RestartFailed : BackupMessage
    data class Failed(val error: BackupError) : BackupMessage
}

/** A checked backup waiting for the restart, as the confirmation shows it. */
data class StagedBackup(val createdIso: String, val versionName: String?, val ownSoundCount: Int?, val token: String? = null)

data class BackupUiState(
    /** Null until counted. */
    val ownSounds: OwnSoundsInfo? = null,
    val includeOwnSounds: Boolean = false,
    val busy: BackupBusy? = null,
    val message: BackupMessage? = null,
    /** Just checked: the "Ready to restore" dialog. */
    val readyToRestore: StagedBackup? = null,
    /** Checked earlier and never confirmed (the screen was left, a restore didn't finish): offered again in a banner. */
    val waitingRestore: StagedBackup? = null,
    val restored: RestoredNotice? = null,
    val restoreFailed: RestoreFailedNotice? = null,
)

/**
 * Back up and restore. All file work runs on [Dispatchers.IO]. The own-sounds choice lives in
 * [saved], so a result delivered after the activity was recreated behind the save picker still
 * honours it.
 */
class BackupViewModel(context: Context, private val saved: SavedStateHandle = SavedStateHandle()) : ViewModel() {
    private val app = context.applicationContext
    private val transfer = BackupTransfer(app)
    private val _state = MutableStateFlow(
        BackupUiState(includeOwnSounds = saved.get<Boolean>(KEY_INCLUDE) == true && saved.get<Boolean>(KEY_TOUCHED) == true),
    )
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    /** The user set the checkbox themselves: the size-based default no longer applies. */
    private val includeTouched: Boolean get() = saved.get<Boolean>(KEY_TOUCHED) == true

    init {
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                Loaded(transfer.ownSounds(), BackupStaging.pendingRestore(app), BackupStaging.restoredNotice(app), BackupStaging.restoreFailedNotice(app))
            }
            _state.update {
                it.copy(
                    ownSounds = loaded.sounds,
                    includeOwnSounds = if (includeTouched) saved.get<Boolean>(KEY_INCLUDE) == true else loaded.sounds.includeByDefault,
                    // The dialog of an import checked just now wins over the banner for the same one.
                    waitingRestore = loaded.pending?.toStaged()?.takeIf { p -> it.readyToRestore?.token != p.token },
                    restored = loaded.notice,
                    restoreFailed = loaded.failed,
                )
            }
        }
    }

    private class Loaded(val sounds: OwnSoundsInfo, val pending: StagedImport?, val notice: RestoredNotice?, val failed: RestoreFailedNotice?)

    fun setIncludeOwnSounds(include: Boolean) {
        saved[KEY_TOUCHED] = true
        saved[KEY_INCLUDE] = include
        _state.update { it.copy(includeOwnSounds = include) }
    }

    /** The save picker returned [uri]; null when the user backed out. */
    fun export(uri: Uri?) {
        if (uri == null || _state.value.busy != null) return
        // Read now, on the main thread: after a recreation the counts may not be loaded yet, the saved choice is.
        val touched = includeTouched
        val choice = saved.get<Boolean>(KEY_INCLUDE) == true
        run(BackupBusy.SAVING) {
            val sounds = transfer.ownSounds()
            val include = sounds.count > 0 && (if (touched) choice else sounds.includeByDefault)
            val result = transfer.export(uri, include)
            _state.update { it.copy(message = BackupMessage.Saved(result.leftOut.map { s -> s.displayName })) }
        }
    }

    /** The open picker returned [uri]; null when the user backed out. */
    fun import(uri: Uri?) {
        if (uri == null || _state.value.busy != null) return
        _state.update { it.copy(busy = BackupBusy.CHECKING, message = null) }
        viewModelScope.launch {
            // Set while a staged import exists that nobody has been shown yet.
            var unseen: String? = null
            try {
                val staged = withContext(Dispatchers.IO) {
                    val coroutine = currentCoroutineContext()
                    // The check keeps going on its thread when the screen is left; this stops it and drops what it staged.
                    transfer.stage(uri, stillWanted = { coroutine.isActive }).also { unseen = it.token }
                }
                unseen = null
                _state.update { it.copy(readyToRestore = staged.toStaged(), waitingRestore = null) }
            } catch (e: CancellationException) {
                unseen?.let { token -> withContext(NonCancellable + Dispatchers.IO) { BackupStaging.cancelPendingRestore(app, token) } }
                throw e
            } catch (e: BackupException) {
                Log.w(TAG, "checking failed: ${e.error} (${e.message})")
                _state.update { it.copy(message = BackupMessage.Failed(e.error)) }
                // The banner with the restart that finishes the earlier restore.
                if (e.error == BackupError.RESTORE_UNFINISHED) refresh()
            } catch (e: Exception) {
                Log.w(TAG, "checking failed: ${e.javaClass.simpleName}")
                _state.update { it.copy(message = BackupMessage.Failed(BackupError.READ_FAILED)) }
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    /** "Restart now" on the dialog or the banner: confirms the checked backup, then the process ends. */
    fun restartNow() {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = BackupBusy.RESTARTING, message = null) }
        viewModelScope.launch {
            // When it works the process ends in there; it returns false when it couldn't confirm
            // (nothing waiting any more, or the mark couldn't be written).
            val restarting = withContext(Dispatchers.IO) { BackupStaging.restart(app) }
            if (restarting) return@launch
            _state.update { it.copy(busy = null, readyToRestore = null, message = BackupMessage.RestartFailed) }
            refresh()
        }
    }

    /**
     * "Restart now" on a restore that stopped part-way and couldn't be undone: the process ends
     * and the next start finishes it. Nothing to confirm: a commit under way is always resumed.
     */
    fun finishRestoreNow() {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = BackupBusy.RESTARTING, message = null) }
        viewModelScope.launch {
            // Returns only when no restore is under way any more (a start already resolved it).
            val restarting = withContext(Dispatchers.IO) { BackupStaging.restartToFinish(app) }
            if (restarting) return@launch
            _state.update { it.copy(busy = null) }
            refresh()
        }
    }

    /** "Cancel" on the dialog or "Discard" on the banner: the checked backup is dropped, nothing was changed. */
    fun cancelRestore() {
        val token = (_state.value.readyToRestore ?: _state.value.waitingRestore)?.token
        viewModelScope.launch {
            val dropped = withContext(Dispatchers.IO) { BackupStaging.cancelPendingRestore(app, token) }
            _state.update {
                it.copy(readyToRestore = null, waitingRestore = null, message = if (dropped) BackupMessage.RestoreCancelled else it.message)
            }
            if (!dropped) refresh()
        }
    }

    fun dismissRestored() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { BackupStaging.dismissRestoredNotice(app) }
            _state.update { it.copy(restored = null) }
        }
    }

    fun dismissRestoreFailed() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { BackupStaging.dismissRestoreFailedNotice(app) }
            _state.update { it.copy(restoreFailed = null) }
        }
    }

    private fun run(busy: BackupBusy, block: suspend () -> Unit) {
        _state.update { it.copy(busy = busy, message = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                Log.w(TAG, "${busy.name.lowercase()} failed: ${e.error} (${e.message})")
                _state.update { it.copy(message = BackupMessage.Failed(e.error)) }
            } catch (e: Exception) {
                Log.w(TAG, "${busy.name.lowercase()} failed: ${e.javaClass.simpleName}")
                val error = if (busy == BackupBusy.SAVING) BackupError.WRITE_FAILED else BackupError.READ_FAILED
                _state.update { it.copy(message = BackupMessage.Failed(error)) }
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    private fun StagedImport.toStaged() = StagedBackup(
        createdIso = manifest.created,
        versionName = manifest.versionName,
        ownSoundCount = if (manifest.ownSounds) manifest.entries.count { it.path.startsWith(BackupInventory.OWN_SOUNDS_DIR + "/") } else null,
        token = token.ifEmpty { null },
    )

    companion object {
        private const val TAG = "Earshot/Backup"
        private const val KEY_INCLUDE = "include_own_sounds"
        private const val KEY_TOUCHED = "include_own_sounds_touched"

        /** The caps a backup must stay within (the screen names them when sounds were left out). */
        val LIMITS = BackupReader.Limits()
    }
}
