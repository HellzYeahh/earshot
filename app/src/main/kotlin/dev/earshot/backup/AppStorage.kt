package dev.earshot.backup

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock

/**
 * Where the app keeps what a backup holds, as plain directories so the backup logic runs on the
 * JVM in tests: [filesDir] is `Context.filesDir`, [sharedPrefsDir] is `<dataDir>/shared_prefs`.
 * The backup's own working files live in [backupDir], which is never part of a backup.
 */
class AppStorage(val filesDir: File, val sharedPrefsDir: File) {
    val dataStoreDir: File get() = File(filesDir, BackupInventory.DATASTORE_DIR)
    val ownSoundsDir: File get() = File(filesDir, OWN_SOUNDS_FOLDER)

    val backupDir: File get() = File(filesDir, "backup")

    /** A checked import (it holds [READY]); applied at a start only once the user confirmed it ([CONFIRMED]). */
    val stagingDir: File get() = File(backupDir, "staging")

    /** An import being unpacked; never applied, deleted when found. */
    val stagingTmpDir: File get() = File(backupDir, "staging.tmp")

    /** Written once a restore was applied, until the UI has said so. */
    val noticeFile: File get() = File(backupDir, "restored.json")

    /** Written when a confirmed restore didn't finish, until the UI has said so. */
    val failedNoticeFile: File get() = File(backupDir, "restore_failed.json")

    /**
     * The Sleep alarm ids this phone had before a restore replaced the `sleep` store, one per
     * line. Their AlarmManager entries outlive the restart (`Runtime.exit` is no force-stop), so
     * the Sleep tab cancels the ones the restored list no longer has.
     */
    val cancelAlarmIdsFile: File get() = File(backupDir, "cancel_alarm_ids.txt")

    /** The live file an allow-listed item restores to. */
    fun target(item: BackupItem): File = when (item) {
        is BackupItem.PreferencesStore -> File(dataStoreDir, item.name + BackupInventory.DATASTORE_SUFFIX)
        is BackupItem.SharedPreferencesFile -> File(sharedPrefsDir, item.name + ".xml")
    }

    companion object {
        /** `BundledRecordings`' folder under `filesDir`. */
        const val OWN_SOUNDS_FOLDER = "sleep_custom"

        /** Inside a staging dir: the manifest, written last, once every file checked out. */
        const val READY = "READY"

        /** Inside a staging dir: when the import was checked (epoch millis), for the 24-hour expiry. */
        const val STAGED_AT = "STAGED_AT"

        /** Inside a staging dir: a random id, so an abandoned import drops its own staging and no other. */
        const val TOKEN = "TOKEN"

        /** Inside a staging dir: the user tapped "Restart now" (epoch millis); only then is it applied. */
        const val CONFIRMED = "CONFIRMED"

        /** Inside a staging dir: the commit has begun; it holds the journal that undoes it. */
        const val COMMITTING = "COMMITTING"

        /** Inside a staging dir: every live file is swapped in; only the clean-up is left. */
        const val COMMITTED = "COMMITTED"

        /** Inside a staging dir: how many starts tried to apply it. */
        const val ATTEMPTS = "ATTEMPTS"
    }
}

/**
 * One import, one restore at a time, process-wide: two imports would unpack into the same
 * `staging.tmp`, and a cancel or confirm must not race the swap of a new staging into place.
 */
internal object BackupLock {
    val lock = ReentrantLock()
}

/** Small file helpers shared by the writer, reader and applier. */
internal object BackupFiles {
    /** Writes [bytes] and syncs them to disk, so a marker written after it is never ahead of the data. */
    fun writeSynced(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
    }

    /** Writes [bytes] next to [file] and renames it into place: a reader sees the old file or the whole new one. */
    fun writeAtomically(file: File, bytes: ByteArray) {
        val temp = File(file.path + ".tmp")
        writeSynced(temp, bytes)
        moveReplacing(temp, file)
    }

    /** Replaces [target] with [source] in one step where the file system allows it. */
    fun moveReplacing(source: File, target: File) {
        target.parentFile?.mkdirs()
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun deleteRecursively(file: File) {
        if (file.exists()) file.deleteRecursively()
    }

    /** The epoch millis written in [file], or null. */
    fun readMillis(file: File): Long? = runCatching { file.readText().trim().toLong() }.getOrNull()
}
