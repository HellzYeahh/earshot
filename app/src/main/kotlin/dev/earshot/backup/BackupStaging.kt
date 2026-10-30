package dev.earshot.backup

import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import dev.earshot.MainActivity
import java.io.File

/**
 * The Android side of a restore: applying a confirmed import at process start, the one-time
 * notices ("Settings restored", "The restore didn't finish"), the waiting import the Backup
 * screen offers again, and the restart that gets there. The rules live in [StagingControl] and
 * [BackupApplier].
 *
 * Wiring (integrator): `EarshotApp.onCreate` calls [applyIfReady] at a fresh unlocked start,
 * before `startUnlocked()` and before anything opens a DataStore or SharedPreferences (the
 * repositories in `AppContainer` are lazy). A start that was locked skips it; the confirmation
 * only holds a few minutes anyway, and "Restart now" always starts unlocked. The Sleep tab's
 * re-arm calls [takeAlarmIdsToCancel]; More shows [restoredNotice] and [restoreFailedNotice]
 * (with [restartToFinish] when that notice says a restart finishes the restore).
 */
object BackupStaging {
    private const val TAG = "Earshot/Backup"

    fun storage(context: Context): AppStorage {
        val app = context.applicationContext ?: context
        return AppStorage(app.filesDir, File(app.dataDir, "shared_prefs"))
    }

    /**
     * Applies a staged import the user confirmed in the last few minutes, if there is one. Never
     * throws: an unusable staging is logged (no personal data) and removed, a failure part-way is
     * undone, and the app starts with the settings it has. An unconfirmed import is left alone.
     */
    fun applyIfReady(context: Context) {
        try {
            // Credential storage isn't readable before the first unlock; the caller should only
            // call this once unlocked, but a locked call must not discard a good staging.
            if (context.getSystemService(UserManager::class.java)?.isUserUnlocked == false) return
            val storage = storage(context)
            // The usual start: nothing staged, two file checks.
            if (!storage.stagingDir.exists() && !storage.stagingTmpDir.exists()) return
            val outcome = BackupApplier(storage, log = { Log.i(TAG, it) }).applyIfReady()
            Log.i(TAG, "restore at start: $outcome")
        } catch (t: Throwable) {
            // A crash here would happen again at every start. A commit under way is left for the
            // next start (its attempts are counted, so this ends); anything else is dropped.
            Log.w(TAG, "restore at start failed (${t.javaClass.simpleName})")
            runCatching { BackupApplier(storage(context), log = { Log.i(TAG, it) }).abandonAfterCrash() }
        }
    }

    /** The checked import waiting for the user's "Restart now" (or "Discard"), or null. */
    fun pendingRestore(context: Context): StagedImport? = StagingControl.pending(storage(context), System.currentTimeMillis())

    /**
     * Drops a checked import the user decided not to restore after all; with [token], only that
     * import (one whose screen is gone must not drop a newer one). False when a restore is being
     * finished and can't be dropped, or the files couldn't be removed.
     */
    fun cancelPendingRestore(context: Context, token: String? = null): Boolean =
        runCatching { StagingControl.cancel(storage(context), token) }.getOrDefault(false)

    /** The notice the last restore left, until [dismissRestoredNotice]. */
    fun restoredNotice(context: Context): RestoredNotice? =
        runCatching { BackupApplier.readNotice(storage(context)) }.getOrNull()

    fun dismissRestoredNotice(context: Context) {
        runCatching { BackupApplier.clearNotice(storage(context)) }
    }

    /**
     * The notice a confirmed restore that didn't finish left, until [dismissRestoreFailedNotice];
     * also one for a commit cut short that left none ([StagingControl.failedNotice]). With
     * [RestoreFailedNotice.finishAtNextStart] the UI offers [restartToFinish].
     */
    fun restoreFailedNotice(context: Context): RestoreFailedNotice? = runCatching {
        val storage = storage(context)
        StagingControl.failedNotice(storage, BackupApplier.readFailedNotice(storage), System.currentTimeMillis())
    }.getOrNull()

    fun dismissRestoreFailedNotice(context: Context) {
        runCatching { BackupApplier.clearFailedNotice(storage(context)) }
    }

    /**
     * The Sleep alarm ids this phone had before a restore replaced its alarms, once (the list is
     * removed). Their AlarmManager entries survive the restart; the Sleep tab cancels the ring,
     * wake-window and snooze alarms of every id the restored list no longer has, before it re-arms.
     */
    fun takeAlarmIdsToCancel(context: Context): List<String> =
        runCatching { StagingControl.takeAlarmIdsToCancel(storage(context)) }.getOrDefault(emptyList())

    /**
     * The user's "Restart now": marks the waiting import confirmed (now), then relaunches Earshot
     * on the More tab and ends this process, so the next start applies it before any store opens.
     * Returns false, without restarting, when there is nothing to confirm or the mark can't be
     * written. The confirmation holds for [StagingControl.CONFIRM_VALID_MILLIS]: if Android
     * doesn't bring the app back within it, the import is dropped at the next start and a notice says so.
     */
    fun restart(context: Context): Boolean {
        if (!runCatching { StagingControl.confirm(storage(context), System.currentTimeMillis()) }.getOrDefault(false)) {
            Log.w(TAG, "restore not confirmed: nothing waiting, or the mark couldn't be written")
            return false
        }
        Log.i(TAG, "restarting to apply a restore")
        relaunchAndExit(context)
        return true
    }

    /**
     * "Restart now" on a restore that stopped part-way and couldn't be undone
     * ([RestoreFailedNotice.finishAtNextStart]): relaunches Earshot and ends this process, and the
     * next start finishes the commit (or undoes it). No confirmation is written: a commit under
     * way is always resumed at start. Returns false, without restarting, when no commit is under
     * way any more.
     */
    fun restartToFinish(context: Context): Boolean {
        if (!runCatching { StagingControl.commitBegun(storage(context)) }.getOrDefault(false)) {
            Log.w(TAG, "no restore under way to finish")
            return false
        }
        Log.i(TAG, "restarting to finish a restore that stopped part-way")
        relaunchAndExit(context)
        return true
    }

    /** Brings Earshot back on the More tab in a new process, then ends this one (before any store sees a change). */
    private fun relaunchAndExit(context: Context) {
        runCatching {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val component = launch?.component
            if (component != null) {
                val intent = Intent.makeRestartActivityTask(component).putExtra(MainActivity.EXTRA_OPEN_TAB, "more")
                context.startActivity(intent)
            }
        }.onFailure { Log.w(TAG, "relaunch failed (${it.javaClass.simpleName})") }
        Runtime.getRuntime().exit(0)
    }
}
