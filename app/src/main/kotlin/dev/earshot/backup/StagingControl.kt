package dev.earshot.backup

import java.io.File
import kotlin.concurrent.withLock

/**
 * The life of a checked import, Android-free (BackupStaging is the Context side):
 *
 * 1. [BackupReader] stages it: `READY`, not applicable yet.
 * 2. The user taps "Restart now": [confirm] writes `CONFIRMED` with the time, then the app restarts.
 * 3. At start, [BackupApplier] applies only a confirmation younger than [CONFIRM_VALID_MILLIS]; a
 *    stale one is dropped (with a notice), so a start days later, from an alarm or a Bluetooth
 *    broadcast, never swaps settings in the background.
 * 4. An unconfirmed staging stays for the Backup screen to offer again ([pending]) and goes after
 *    [UNCONFIRMED_KEEP_MILLIS] (deleted by [pending] or by the next start, whichever comes first).
 */
internal object StagingControl {
    const val CONFIRM_VALID_MILLIS = 10L * 60 * 1000
    const val UNCONFIRMED_KEEP_MILLIS = 24L * 60 * 60 * 1000

    /** How far the clock may seem to go back between the tap and the start (a network time fix). */
    private const val CLOCK_SLACK_MILLIS = 60L * 1000

    /**
     * The checked import waiting for the user, or null (none, expired, or a commit is under way).
     * An expired one is deleted here ([dropExpired]), not only hidden: the foreground service can
     * keep the process alive for days, and the applier only sees it at the next fresh start,
     * while up to a gigabyte of staged own sounds would sit there with no way to discard it.
     */
    fun pending(storage: AppStorage, now: Long): StagedImport? = runCatching {
        val dir = storage.stagingDir
        val ready = File(dir, AppStorage.READY)
        if (!ready.isFile || commitBegun(storage)) return@runCatching null
        if (isExpired(storage, now)) {
            dropExpired(storage, now)
            return@runCatching null
        }
        StagedImport(BackupManifest.decode(ready.readBytes()), token(dir).orEmpty())
    }.getOrNull()

    /**
     * Deletes the staging when it is an unconfirmed one past [UNCONFIRMED_KEEP_MILLIS]. Skipped
     * (false) while an import or a restore holds [BackupLock]: a later call, or the next start, does it.
     */
    fun dropExpired(storage: AppStorage, now: Long): Boolean {
        if (!BackupLock.lock.tryLock()) return false
        try {
            val dir = storage.stagingDir
            // Checked again under the lock: an import may have swapped a new staging in meanwhile.
            if (!File(dir, AppStorage.READY).isFile || commitBegun(storage) || File(dir, AppStorage.CONFIRMED).exists()) return false
            if (!isExpired(storage, now)) return false
            BackupFiles.deleteRecursively(dir)
            return !dir.exists()
        } finally {
            BackupLock.lock.unlock()
        }
    }

    /** A commit has begun (`COMMITTING`) or gone through (`COMMITTED`): only a start finishes it, nothing may drop the staging. */
    fun commitBegun(storage: AppStorage): Boolean {
        val dir = storage.stagingDir
        return File(dir, AppStorage.COMMITTING).exists() || File(dir, AppStorage.COMMITTED).exists()
    }

    /** A commit began and never got to `COMMITTED`: the live files may be a mix until a start finishes (or undoes) it. */
    fun commitCutShort(storage: AppStorage): Boolean {
        val dir = storage.stagingDir
        return File(dir, AppStorage.COMMITTING).exists() && !File(dir, AppStorage.COMMITTED).exists()
    }

    /**
     * What the UI shows about a restore that didn't finish: the [stored] notice, with its restart
     * offer dropped once no commit is under way any more (a start resolved it). With no notice, a
     * commit cut short still gets one: a process that died mid-commit and came back locked (direct
     * boot) skips the restore at start and runs on a mix of files with nobody told.
     */
    fun failedNotice(storage: AppStorage, stored: RestoreFailedNotice?, now: Long): RestoreFailedNotice? = when {
        stored == null -> if (commitCutShort(storage)) RestoreFailedNotice(now, previousSettingsBack = false, finishAtNextStart = true) else null
        stored.finishAtNextStart && !commitBegun(storage) -> stored.copy(finishAtNextStart = false)
        else -> stored
    }

    /** The user's "Restart now": arms the waiting staging for the next start. False when there is none or the mark can't be written. */
    fun confirm(storage: AppStorage, now: Long): Boolean = BackupLock.lock.withLock {
        val dir = storage.stagingDir
        if (!File(dir, AppStorage.READY).isFile || commitBegun(storage)) return@withLock false
        runCatching { BackupFiles.writeSynced(File(dir, AppStorage.CONFIRMED), now.toString().toByteArray()) }.isSuccess
    }

    /** Back to "checked, waiting": a start that couldn't apply it leaves it for the user to try again. */
    fun unconfirm(storage: AppStorage) {
        runCatching { File(storage.stagingDir, AppStorage.CONFIRMED).delete() }
    }

    fun confirmedAt(storage: AppStorage): Long? {
        val file = File(storage.stagingDir, AppStorage.CONFIRMED)
        if (!file.exists()) return null
        // An unreadable mark counts as long ago: confirmed, but not recently.
        return BackupFiles.readMillis(file) ?: Long.MIN_VALUE
    }

    fun isFreshConfirmation(confirmedAt: Long, now: Long): Boolean =
        confirmedAt != Long.MIN_VALUE && now - confirmedAt in -CLOCK_SLACK_MILLIS..CONFIRM_VALID_MILLIS

    /** An unconfirmed staging older than a day (by its own clock mark, else the READY file's time). */
    fun isExpired(storage: AppStorage, now: Long): Boolean {
        val dir = storage.stagingDir
        val stagedAt = BackupFiles.readMillis(File(dir, AppStorage.STAGED_AT)) ?: File(dir, AppStorage.READY).lastModified()
        val age = now - stagedAt
        return age > UNCONFIRMED_KEEP_MILLIS || age < -UNCONFIRMED_KEEP_MILLIS
    }

    /**
     * Drops the waiting staging; with [token], only if it is still that import's (an abandoned
     * import must not drop a newer one). Refused once a commit has begun: the next start
     * finishes or undoes it, and deleting the staging then would strand the phone's files.
     */
    fun cancel(storage: AppStorage, token: String? = null): Boolean = BackupLock.lock.withLock {
        val dir = storage.stagingDir
        if (!dir.exists()) return@withLock true
        if (commitBegun(storage)) return@withLock false
        if (token != null && token(dir) != token) return@withLock false
        runCatching { BackupFiles.deleteRecursively(dir) }.isSuccess && !dir.exists()
    }

    /** Adds [ids] to the alarms the Sleep tab must cancel ([AppStorage.cancelAlarmIdsFile]). */
    fun recordAlarmIdsToCancel(storage: AppStorage, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val all = (readAlarmIds(storage) + ids).filter { it.isNotBlank() && '\n' !in it }.distinct()
        BackupFiles.writeAtomically(storage.cancelAlarmIdsFile, all.joinToString("\n").toByteArray())
    }

    /** The alarm ids to cancel, once: the file is removed. */
    fun takeAlarmIdsToCancel(storage: AppStorage): List<String> {
        val ids = readAlarmIds(storage)
        runCatching { storage.cancelAlarmIdsFile.delete() }
        return ids
    }

    private fun readAlarmIds(storage: AppStorage): List<String> =
        runCatching { storage.cancelAlarmIdsFile.readLines().map { it.trim() }.filter { it.isNotEmpty() } }.getOrDefault(emptyList())

    private fun token(dir: File): String? = runCatching { File(dir, AppStorage.TOKEN).readText().trim() }.getOrNull()
}
