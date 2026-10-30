package dev.earshot.backup

import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import kotlin.concurrent.withLock

/** Left by a restore for the UI: "Settings restored" once, then dismissed. */
@Serializable
data class RestoredNotice(val restoredAtMillis: Long, val backupCreated: String, val fromVersion: String? = null)

/**
 * Left by a restore the user confirmed that didn't finish, for the UI once: with
 * [previousSettingsBack] the phone has exactly the settings it had before ("your previous
 * settings are back"); without it the undo itself failed and some files may be mixed.
 *
 * [finishAtNextStart]: the commit is still under way (the undo failed, or an error escaped it).
 * The app runs on a mix of restored and old files until the next start finishes the commit, or
 * undoes it; until then a new import is refused ([BackupError.RESTORE_UNFINISHED]), so the UI offers a restart
 * ([BackupStaging.restartToFinish]) instead of "restore from the file again".
 */
@Serializable
data class RestoreFailedNotice(
    val atMillis: Long,
    val backupCreated: String? = null,
    val previousSettingsBack: Boolean = true,
    val finishAtNextStart: Boolean = false,
)

/**
 * Applies a staged import ([BackupReader]) at process start, before any DataStore or
 * SharedPreferences is opened, so no live store ever sees its file change underneath it. Only a
 * staging the user confirmed a few minutes ago is applied ([StagingControl]); any other one is
 * left for the Backup screen ([Outcome.PENDING]) or dropped when it is too old.
 *
 * Two phases. **Prepare** checks the staged files against the manifest again and builds every
 * replacement next to its live file (`<file>.restore`): the imported values with their types
 * checked ([PreferencesFiles.typedOnly]: an unknown or mistyped key is dropped, the phone keeps its
 * own value of a mistyped one), minus the phone-local keys, plus this phone's own values of those
 * keys (a running Sleep night, the automation log, the own sounds' list). Own sounds are only ever
 * added: the backup's ones join this phone's (an id clash keeps the phone's file) and the lists are
 * merged ([OwnSoundList.merge]). Nothing live changes in this phase.
 *
 * **Commit** writes a journal into `COMMITTING` (which live files existed, which sounds it adds),
 * moves each live file aside as `<file>.prerestore` and the replacement in (one rename each), moves
 * the added sounds in, then writes `COMMITTED`. Only then are the `.prerestore` copies deleted, the
 * notice written and the staging removed. A failure part-way puts every `.prerestore` file back
 * and the added sounds out again, leaves a [RestoreFailedNotice] ("your previous settings are
 * back") and keeps the staging waiting for the user; the app never starts on a half commit unless
 * the undo itself fails, and then the notice says so and asks for a restart
 * ([RestoreFailedNotice.finishAtNextStart]). A process that dies in the middle leaves `COMMITTING`,
 * and the next start runs the same steps again: they are idempotent (staged files are copied, the
 * journal says what is the phone's), and if they fail then, the next start undoes them.
 * [MAX_ATTEMPTS] starts bound all of it.
 */
class BackupApplier(
    private val storage: AppStorage,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    /** Tests only: called before each change to a live file, so a test can fail or "crash" the commit at any step. */
    internal val onCommitStep: (String) -> Unit = {},
    /** Tests only: called before each file the undo puts back, so a test can make the undo fail (a disk error mid-rename). */
    internal val onUndoStep: (String) -> Unit = {},
    /** Tests only: called before a committed staging is removed, so a test can make that clean-up fail and leave `COMMITTED`. */
    internal val onCleanUp: () -> Unit = {},
) {
    enum class Outcome {
        /** No staged import. */
        NOTHING,

        /** A checked import that the user hasn't confirmed: left for the Backup screen. */
        PENDING,

        /** Restored; [AppStorage.noticeFile] says so to the UI. */
        APPLIED,

        /** The staging was unusable, expired or given up, and is gone; the settings are as they were. */
        DISCARDED,

        /**
         * It couldn't be prepared (an I/O error): the staging waits for the user to try again. Or
         * the undo of a failed commit failed: the commit stays under way, a [RestoreFailedNotice]
         * with [RestoreFailedNotice.finishAtNextStart] asks for a restart, and the next start
         * finishes it (or undoes it).
         */
        RETRY_LATER,

        /** The commit failed part-way and was undone: the previous settings are back, the staging waits for the user again. */
        ROLLED_BACK,
    }

    private sealed interface Step {
        val target: File

        /** Files to clear before [target] changes (a SharedPreferences `.bak` would win over the new file). */
        val alsoDelete: List<File>

        data class Replace(val temp: File, override val target: File, override val alsoDelete: List<File> = emptyList()) : Step

        /** The backup has nothing here (defaults on the old phone): the live file goes too. */
        data class Delete(override val target: File, override val alsoDelete: List<File> = emptyList()) : Step
    }

    private class Prepared(val manifest: BackupManifest, val steps: List<Step>, val oldAlarmIds: List<String>)

    /** What a commit changes, written before it changes anything: [existed] are the live files (by [rel]) the phone had. */
    @Serializable
    internal data class Journal(val existed: List<String>, val addedSounds: List<String>)

    fun applyIfReady(): Outcome = BackupLock.lock.withLock { applyLocked() }

    private fun applyLocked(): Outcome {
        // An import that was still unpacking when the process ended: never applied.
        BackupFiles.deleteRecursively(storage.stagingTmpDir)
        val staging = storage.stagingDir
        if (!staging.exists()) return Outcome.NOTHING
        if (File(staging, AppStorage.COMMITTED).exists()) return finish(readManifest())
        if (File(staging, AppStorage.COMMITTING).exists()) return resume()
        if (!File(staging, AppStorage.READY).isFile) return discard("staging without its ready marker", notify = false)

        val confirmedAt = StagingControl.confirmedAt(storage)
        if (confirmedAt == null) {
            if (StagingControl.isExpired(storage, now())) return discard("checked over a day ago and never confirmed", notify = false)
            log("a checked backup waits for the user to confirm it")
            return Outcome.PENDING
        }
        if (!StagingControl.isFreshConfirmation(confirmedAt, now())) return discard("confirmed too long ago", notify = true)

        val attempts = readAttempts()
        if (attempts >= MAX_ATTEMPTS) return discard("gave up after $attempts attempts", notify = true)
        // Uncounted, a staging that fails at every start would be tried for ever.
        if (!writeAttempts(attempts + 1)) return discard("the attempt counter can't be written", notify = true)
        val attempt = attempts + 1

        val prepared = try {
            prepare(journal = null)
        } catch (e: BackupException) {
            removeTemps()
            return discard("staging failed its check: ${e.message}", notify = true)
        } catch (e: Exception) {
            removeTemps()
            return waitAgain(attempt, "preparing failed: ${e.javaClass.simpleName}")
        }
        val journal = try {
            startCommit()
        } catch (e: Exception) {
            removeTemps()
            runCatching { File(staging, AppStorage.COMMITTING).delete() }
            return waitAgain(attempt, "the journal couldn't be written: ${e.javaClass.simpleName}")
        }
        return commitOrRollBack(prepared, journal, attempt)
    }

    /** A start after one that died during the commit: finish it, or undo it when that fails. */
    private fun resume(): Outcome {
        val attempts = readAttempts()
        val counted = writeAttempts(attempts + 1)
        val journal = readJournal() ?: return giveUp("the commit's journal is unreadable")
        if (attempts >= MAX_ATTEMPTS) {
            log("a restore stopped part-way $attempts times; undoing it")
            return if (rollBack(journal)) discardAfterUndo() else giveUp("undoing it failed")
        }
        // Without a counted attempt, this is the last one.
        val attempt = if (counted) attempts + 1 else MAX_ATTEMPTS
        log("finishing a restore that stopped part-way")
        val prepared = try {
            prepare(journal)
        } catch (e: Exception) {
            removeTemps()
            return rollBackAfterFailure(journal, attempt, "preparing it again failed: ${e.javaClass.simpleName}")
        }
        return commitOrRollBack(prepared, journal, attempt)
    }

    private fun commitOrRollBack(prepared: Prepared, journal: Journal, attempt: Int): Outcome {
        try {
            commit(prepared, journal)
        } catch (e: Exception) {
            return rollBackAfterFailure(journal, attempt, "commit stopped part-way: ${e.javaClass.simpleName}")
        }
        return finish(prepared.manifest)
    }

    private fun prepare(journal: Journal?): Prepared {
        val staging = storage.stagingDir
        val manifest = BackupManifest.decode(File(staging, AppStorage.READY).readBytes())
        val listed = manifest.entries.associateBy { it.path }
        val soundFiles = checkSounds(manifest, staging, journal)
        var oldAlarmIds = emptyList<String>()
        val steps = BackupInventory.stores.map { store ->
            val target = storage.target(store)
            val imported: Preferences = listed[store.path]?.let { entry -> PreferencesFiles.decode(verified(File(staging, store.path), entry)) }
                ?: PreferencesFiles.empty()
            // This phone's own file as it was before any commit; an unreadable one has nothing worth keeping.
            val current = originalOf(store.path, target, journal)?.let { runCatching { PreferencesFiles.read(it) }.getOrNull() }
            if (store.name == BackupInventory.SLEEP_STORE) {
                oldAlarmIds = OwnSoundList.ids(PreferencesFiles.string(current, BackupInventory.SLEEP_ALARMS_KEY))
            }
            val typed = PreferencesFiles.typedOnly(imported, store::typeOf)
            val mistyped = PreferencesFiles.mistyped(imported, store::typeOf)
            val unknown = imported.asMap().keys.count { store.typeOf(it.name) == null }
            if (mistyped.isNotEmpty() || unknown > 0) {
                log("store ${store.name}: kept this phone's value of ${mistyped.size} mistyped key(s), dropped $unknown unknown key(s)")
            }
            val phone = current?.let { PreferencesFiles.typedOnly(it, store::typeOf) }
            val keep = store.localKeys + (if (manifest.ownSounds) emptySet() else store.ownSoundKeys) + mistyped
            var result = PreferencesFiles.withKeysFrom(typed, phone, keep)
            if (manifest.ownSounds && BackupInventory.SLEEP_CUSTOM_SOUNDS_KEY in store.ownSoundKeys) {
                val key = BackupInventory.SLEEP_CUSTOM_SOUNDS_KEY
                val merged = OwnSoundList.merge(PreferencesFiles.string(typed, key), PreferencesFiles.string(phone, key), soundFiles)
                result = PreferencesFiles.withString(result, key, merged)
            }
            if (result.asMap().isEmpty()) {
                Step.Delete(target)
            } else {
                val temp = File(target.path + RESTORE_SUFFIX)
                BackupFiles.writeSynced(temp, PreferencesFiles.encode(result))
                Step.Replace(temp, target)
            }
        } + BackupInventory.sharedPreferences.map { prefs ->
            val target = storage.target(prefs)
            val bak = File(target.path + ".bak")
            val entry = listed[prefs.path]
            if (entry == null) {
                Step.Delete(target, listOf(bak))
            } else {
                val temp = File(target.path + RESTORE_SUFFIX)
                val bytes = verified(File(staging, prefs.path), entry)
                // Copied byte for byte, so checked here too (the reader did): a staging an older build left had no DOCTYPE check.
                if (!SharedPreferencesXml.isPlain(bytes)) throw BackupException(BackupError.DAMAGED, "a preferences file isn't plain SharedPreferences XML")
                BackupFiles.writeSynced(temp, bytes)
                Step.Replace(temp, target, listOf(bak))
            }
        }
        return Prepared(manifest, steps, oldAlarmIds)
    }

    /**
     * Checks the staged own sounds and returns the file names `sleep_custom` will hold once the
     * restore is done (this phone's and the backup's). Sizes only: the reader checked every hash
     * after syncing the files, and hashing a few hundred MB again would hold up the app's start.
     */
    private fun checkSounds(manifest: BackupManifest, staging: File, journal: Journal?): Set<String> {
        if (!manifest.ownSounds) return emptySet()
        val entries = manifest.entries.filter { BackupInventory.allowedPath(it.path) is AllowedPath.OwnSound }
        val stagedDir = File(staging, BackupInventory.OWN_SOUNDS_DIR)
        val live = storage.ownSoundsDir.list()?.toSet().orEmpty()
        val names = entries.map { it.path.substringAfterLast('/') }.toSet()
        val found = stagedDir.list()?.toSet().orEmpty()
        if (!names.containsAll(found)) throw BackupException(BackupError.DAMAGED, "own sounds differ from the manifest")
        for (entry in entries) {
            val name = entry.path.substringAfterLast('/')
            val staged = File(stagedDir, name)
            when {
                staged.isFile -> if (staged.length() != entry.size) throw BackupException(BackupError.DAMAGED, "an own sound changed size in the staging")
                // Moved in by the start that died during the commit.
                journal != null && name in journal.addedSounds && name in live -> Unit
                else -> throw BackupException(BackupError.DAMAGED, "own sounds missing from the staging")
            }
        }
        return live + names
    }

    /** The journal of a fresh commit, written (atomically) before any live file changes. */
    private fun startCommit(): Journal {
        // Copies a restore that finished long ago left behind: not ours, and they would be mistaken for originals.
        for ((_, file) in committedFiles()) runCatching { prerestoreOf(file).delete() }
        val manifest = readManifest()
        val live = storage.ownSoundsDir.list()?.toSet().orEmpty()
        val added = if (manifest?.ownSounds == true) {
            manifest.entries.filter { BackupInventory.allowedPath(it.path) is AllowedPath.OwnSound }
                .map { it.path.substringAfterLast('/') }.filter { it !in live }.sorted()
        } else {
            emptyList()
        }
        val journal = Journal(existed = committedFiles().filter { (_, file) -> file.exists() }.map { it.first }, addedSounds = added)
        BackupFiles.writeAtomically(File(storage.stagingDir, AppStorage.COMMITTING), json.encodeToString(Journal.serializer(), journal).toByteArray())
        return journal
    }

    private fun commit(prepared: Prepared, journal: Journal) {
        // Before the sleep store changes: this phone's alarms stay armed in AlarmManager across the restart.
        StagingControl.recordAlarmIdsToCancel(storage, prepared.oldAlarmIds)
        for (step in prepared.steps) {
            for (extra in step.alsoDelete) {
                onCommitStep("clear ${rel(extra)}")
                keepOriginal(extra, journal)
                if (extra.exists() && !extra.delete()) throw IOException("could not clear a file")
            }
            onCommitStep("swap ${rel(step.target)}")
            keepOriginal(step.target, journal)
            when (step) {
                is Step.Replace -> BackupFiles.moveReplacing(step.temp, step.target)
                is Step.Delete -> if (step.target.exists() && !step.target.delete()) throw IOException("could not remove a file")
            }
        }
        val stagedDir = File(storage.stagingDir, BackupInventory.OWN_SOUNDS_DIR)
        for (name in journal.addedSounds) {
            val from = File(stagedDir, name)
            val to = File(storage.ownSoundsDir, name)
            onCommitStep("add sound $name")
            when {
                from.isFile -> BackupFiles.moveReplacing(from, to)
                to.isFile -> Unit // moved by an earlier start
                else -> throw BackupException(BackupError.DAMAGED, "a staged own sound is gone")
            }
        }
        onCommitStep("committed")
        BackupFiles.writeSynced(File(storage.stagingDir, AppStorage.COMMITTED), ByteArray(0))
    }

    /** Moves a live file the phone had aside as `<file>.prerestore`, once (a re-run finds it there). */
    private fun keepOriginal(file: File, journal: Journal) {
        val pre = prerestoreOf(file)
        if (rel(file) in journal.existed && file.exists() && !pre.exists()) BackupFiles.moveReplacing(file, pre)
    }

    /** Every live file is the restored one: drop the copies, tell the UI, remove the staging. */
    private fun finish(manifest: BackupManifest?): Outcome {
        for ((_, file) in committedFiles()) runCatching { prerestoreOf(file).delete() }
        removeTemps()
        // The notice before the staging goes: a crash in between writes it again, never loses it.
        // A start that finds the staging half removed (no READY any more) keeps the notice it has.
        // Only the commit's first finish writes it: COMMITTED stays until the rest of the staging
        // is gone, so a clean-up that keeps failing runs this at every start, and those starts
        // restored nothing; one must not bring back a notice the user already dismissed. The mark
        // goes into COMMITTED after the notice (still only checked for existence elsewhere), so a
        // crash between the two writes the notice once more, and a cut-short mark reads as none.
        val committed = File(storage.stagingDir, AppStorage.COMMITTED)
        val announced = runCatching { committed.readText() == NOTICE_WRITTEN }.getOrDefault(false)
        runCatching {
            if (!announced && (manifest != null || !storage.noticeFile.exists())) {
                val notice = RestoredNotice(now(), manifest?.created.orEmpty(), manifest?.versionName)
                BackupFiles.writeSynced(storage.noticeFile, json.encodeToString(RestoredNotice.serializer(), notice).toByteArray())
            }
            if (!announced) BackupFiles.writeSynced(committed, NOTICE_WRITTEN.toByteArray())
            storage.failedNoticeFile.delete()
        }
        removeCommittedStaging()
        log("restored ${manifest?.entries?.size ?: "?"} file(s) from a backup made by ${manifest?.versionName ?: "?"}")
        return Outcome.APPLIED
    }

    /**
     * Removes a staging whose commit went through, `COMMITTED` last: as long as it is there, a
     * start that finds the rest half deleted only finishes again. Deleted in any order, a start
     * could find the journal without `COMMITTED`, take the commit for one cut short and undo it,
     * deleting restored files the phone didn't have before (the `.prerestore` copies are gone by then).
     */
    private fun removeCommittedStaging() {
        onCleanUp()
        val staging = storage.stagingDir
        val others = staging.listFiles()?.filter { it.name != AppStorage.COMMITTED }.orEmpty()
        others.forEach { BackupFiles.deleteRecursively(it) }
        // Only once nothing else is left; otherwise the next start finishes (and cleans up) again.
        if (others.none { it.exists() }) {
            File(staging, AppStorage.COMMITTED).delete()
            BackupFiles.deleteRecursively(staging)
        }
    }

    /** Puts every live file back as the journal says it was. True when all of it worked. */
    private fun rollBack(journal: Journal): Boolean {
        var ok = true
        for ((rel, file) in committedFiles()) {
            ok = ok and runCatching {
                onUndoStep("undo $rel")
                val pre = prerestoreOf(file)
                if (rel in journal.existed) {
                    // No copy: never moved, the live file is still the phone's own.
                    if (pre.exists()) BackupFiles.moveReplacing(pre, file)
                } else if (file.exists() && !file.delete()) {
                    throw IOException("could not remove a restored file")
                }
            }.isSuccess
        }
        val stagedDir = File(storage.stagingDir, BackupInventory.OWN_SOUNDS_DIR)
        for (name in journal.addedSounds) {
            ok = ok and runCatching {
                onUndoStep("take back sound $name")
                val live = File(storage.ownSoundsDir, name)
                val back = File(stagedDir, name)
                if (live.isFile && !back.exists()) BackupFiles.moveReplacing(live, back)
            }.isSuccess
        }
        removeTemps()
        runCatching { File(storage.stagingDir, AppStorage.COMMITTED).delete() }
        if (ok) ok = runCatching { File(storage.stagingDir, AppStorage.COMMITTING).let { !it.exists() || it.delete() } }.getOrDefault(false)
        log(if (ok) "undid a restore that stopped part-way" else "undoing a restore that stopped part-way failed")
        return ok
    }

    private fun rollBackAfterFailure(journal: Journal, attempt: Int, why: String): Outcome {
        log("$why; undoing it")
        if (!rollBack(journal)) {
            if (attempt >= MAX_ATTEMPTS) return giveUp("undoing it failed")
            // COMMITTING stays: the next start tries to finish it, else to undo it again. Until
            // then the app runs on a mix of files and refuses a new import, which can be days
            // while the service keeps the process alive: the user is told, and offered the restart.
            writeFailedNotice(previousBack = false, finishAtNextStart = true)
            return Outcome.RETRY_LATER
        }
        if (attempt >= MAX_ATTEMPTS) return discardAfterUndo()
        StagingControl.unconfirm(storage)
        writeFailedNotice(previousBack = true)
        return Outcome.ROLLED_BACK
    }

    private fun discardAfterUndo(): Outcome {
        writeFailedNotice(previousBack = true)
        BackupFiles.deleteRecursively(storage.stagingDir)
        return Outcome.DISCARDED
    }

    /** Nothing more to try: the staging goes (the `.prerestore` copies stay, a later restore removes them). */
    private fun giveUp(why: String): Outcome {
        log("gave up on a restore that stopped part-way: $why")
        writeFailedNotice(previousBack = false)
        removeTemps()
        runCatching { File(storage.stagingDir, AppStorage.COMMITTING).delete() }
        BackupFiles.deleteRecursively(storage.stagingDir)
        return Outcome.DISCARDED
    }

    /** Preparing failed with a passing error: nothing changed; the staging waits for the user to try again. */
    private fun waitAgain(attempt: Int, why: String): Outcome {
        writeFailedNotice(previousBack = true)
        if (attempt >= MAX_ATTEMPTS) {
            log("$why; giving up")
            BackupFiles.deleteRecursively(storage.stagingDir)
            return Outcome.DISCARDED
        }
        log("$why; it waits for the user to try again")
        StagingControl.unconfirm(storage)
        return Outcome.RETRY_LATER
    }

    /** Only for a staging whose commit never began. [notify]: the user confirmed it and expects a restore. */
    private fun discard(why: String, notify: Boolean): Outcome {
        log("discarded the staged backup: $why")
        removeTemps()
        if (notify) writeFailedNotice(previousBack = true)
        BackupFiles.deleteRecursively(storage.stagingDir)
        return Outcome.DISCARDED
    }

    /**
     * After a [Throwable] escaped [applyIfReady] (BackupStaging's last resort). A commit under way
     * is left for the next start, which counts its attempts; anything else is dropped. One that
     * was cut before `COMMITTED` leaves this process on a mix of files: the notice asks for the
     * restart that finishes it.
     */
    fun abandonAfterCrash() = BackupLock.lock.withLock {
        val staging = storage.stagingDir
        BackupFiles.deleteRecursively(storage.stagingTmpDir)
        if (File(staging, AppStorage.COMMITTED).exists()) return@withLock
        if (File(staging, AppStorage.COMMITTING).exists()) {
            writeFailedNotice(previousBack = false, finishAtNextStart = true)
            return@withLock
        }
        if (File(staging, AppStorage.CONFIRMED).exists()) writeFailedNotice(previousBack = true)
        removeTemps()
        BackupFiles.deleteRecursively(staging)
    }

    private fun writeFailedNotice(previousBack: Boolean, finishAtNextStart: Boolean = false) {
        runCatching {
            val notice = RestoreFailedNotice(now(), readManifest()?.created, previousBack, finishAtNextStart)
            BackupFiles.writeSynced(storage.failedNoticeFile, json.encodeToString(RestoreFailedNotice.serializer(), notice).toByteArray())
        }
    }

    /** The staged file's bytes, after checking them against the manifest once more. */
    private fun verified(file: File, entry: BackupManifest.Entry): ByteArray {
        if (!file.isFile) throw BackupException(BackupError.DAMAGED, "a staged file is missing")
        val bytes = file.readBytes()
        if (bytes.size.toLong() != entry.size || !sha256Hex(bytes).equals(entry.sha256, ignoreCase = true)) {
            throw BackupException(BackupError.DAMAGED, "a staged file changed")
        }
        return bytes
    }

    /** The phone's file as it was before this commit: its `.prerestore` copy once moved aside, null when it had none. */
    private fun originalOf(rel: String, target: File, journal: Journal?): File? = when {
        journal == null -> target
        rel !in journal.existed -> null
        else -> prerestoreOf(target).takeIf { it.exists() } ?: target
    }

    /** Every live file a commit may change, by a name relative to the app's storage: the items and the SharedPreferences `.bak`s. */
    private fun committedFiles(): List<Pair<String, File>> = BackupInventory.items.flatMap { item ->
        val target = storage.target(item)
        when (item) {
            is BackupItem.PreferencesStore -> listOf(item.path to target)
            is BackupItem.SharedPreferencesFile -> listOf(item.path to target, item.path + ".bak" to File(target.path + ".bak"))
        }
    }

    private fun rel(file: File): String = committedFiles().firstOrNull { it.second == file }?.first ?: file.name

    private fun prerestoreOf(file: File) = File(file.path + PRERESTORE_SUFFIX)

    private fun readManifest(): BackupManifest? =
        runCatching { BackupManifest.decode(File(storage.stagingDir, AppStorage.READY).readBytes()) }.getOrNull()

    private fun readJournal(): Journal? =
        runCatching { json.decodeFromString(Journal.serializer(), File(storage.stagingDir, AppStorage.COMMITTING).readText()) }.getOrNull()

    private fun readAttempts(): Int =
        runCatching { File(storage.stagingDir, AppStorage.ATTEMPTS).readText().trim().toInt() }.getOrDefault(0)

    private fun writeAttempts(n: Int): Boolean =
        runCatching { BackupFiles.writeSynced(File(storage.stagingDir, AppStorage.ATTEMPTS), n.toString().toByteArray()) }.isSuccess

    private fun removeTemps() {
        for (item in BackupInventory.items) runCatching { File(storage.target(item).path + RESTORE_SUFFIX).delete() }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val RESTORE_SUFFIX = ".restore"

        /** A live file a commit replaced, until the whole commit got through (or put back when it didn't). */
        const val PRERESTORE_SUFFIX = ".prerestore"

        /** What `COMMITTED` holds once this commit's "Settings restored" notice is written (empty before). */
        internal const val NOTICE_WRITTEN = "notice written"

        internal val json = Json { ignoreUnknownKeys = true }

        /** The notice a restore left, or null. */
        fun readNotice(storage: AppStorage): RestoredNotice? =
            runCatching { json.decodeFromString(RestoredNotice.serializer(), storage.noticeFile.readText()) }.getOrNull()

        fun clearNotice(storage: AppStorage) {
            storage.noticeFile.delete()
        }

        /** The notice a confirmed restore that didn't finish left, or null. */
        fun readFailedNotice(storage: AppStorage): RestoreFailedNotice? =
            runCatching { json.decodeFromString(RestoreFailedNotice.serializer(), storage.failedNoticeFile.readText()) }.getOrNull()

        fun clearFailedNotice(storage: AppStorage) {
            storage.failedNoticeFile.delete()
        }
    }
}
