package dev.earshot.backup

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException

/** A checked import in [AppStorage.stagingDir]; [token] tells it apart from any later one. */
data class StagedImport(val manifest: BackupManifest, val token: String)

/**
 * Checks a backup zip and unpacks it into [AppStorage.stagingDir]; the live settings are never
 * touched here, and nothing staged is applied before the user confirms it ([StagingControl.confirm]).
 *
 * `manifest.json` must be the first file (the writer always puts it there), so everything is
 * decided before a byte is inflated: the format, every listed path against the allow-list
 * ([BackupInventory.allowedPath], which also rules out `..`, absolute paths and backslashes, so
 * nothing can land outside the staging folder), each declared size against its cap, the total
 * against the cap and the free space, and the own-sounds flag against the files. Each entry must
 * then be one the manifest lists, is cut off at its declared size while it streams (counted on
 * the decompressed bytes, so a zip bomb stops early), and duplicates are refused; folder entries
 * count as entries and must be empty. After the last entry every listed file must have arrived
 * with its SHA-256, and every settings file must parse. Only then is the `READY` marker written
 * and the folder swapped into place, replacing an earlier import that was never applied.
 *
 * One import at a time ([BackupLock]): a second one is refused with [BackupError.BUSY], and none
 * while an earlier restore's commit is under way ([BackupError.RESTORE_UNFINISHED]).
 */
class BackupReader(
    private val storage: AppStorage,
    private val limits: Limits = Limits(),
    private val log: (String) -> Unit = {},
    /** This build's versionCode, to tell "made by a newer Earshot" from a foreign file; null = unknown. */
    private val currentVersionCode: Long? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** The caps of a backup, shared with [BackupWriter] so it never writes a file this reader refuses. */
    data class Limits(
        val maxEntries: Int = 512,
        val maxManifestBytes: Long = 1L * 1024 * 1024,
        /** Any one settings file (DataStore or SharedPreferences): real ones are a few kB. */
        val maxSettingsFileBytes: Long = 4L * 1024 * 1024,
        /** Any one own sound: the user's own files, copied as they were picked. */
        val maxOwnSoundBytes: Long = 256L * 1024 * 1024,
        val maxTotalBytes: Long = 1024L * 1024 * 1024,
        /** Free space to leave on the phone besides the unpacked files. */
        val spareBytes: Long = 16L * 1024 * 1024,
    )

    /** Stages the backup read from [input] (closed when done) and returns its manifest. Throws [BackupException]. */
    fun stage(input: InputStream): BackupManifest = stageImport(input).manifest

    /**
     * Like [stage]. [stillWanted] is asked between entries and before the staging replaces an
     * earlier one: once it says no (the screen that started the import is gone), the import
     * stops, its files are removed and a [CancellationException] is thrown; a waiting earlier
     * import is left as it was.
     */
    fun stageImport(input: InputStream, stillWanted: () -> Boolean = { true }): StagedImport {
        if (!BackupLock.lock.tryLock()) {
            runCatching { input.close() }
            throw BackupException(BackupError.BUSY, "another import is still being checked")
        }
        try {
            return stageLocked(input, stillWanted)
        } finally {
            BackupLock.lock.unlock()
        }
    }

    private fun stageLocked(input: InputStream, stillWanted: () -> Boolean): StagedImport {
        val tmp = storage.stagingTmpDir
        var placed = false
        try {
            if (File(storage.stagingDir, AppStorage.COMMITTING).exists() || File(storage.stagingDir, AppStorage.COMMITTED).exists()) {
                // A restore stopped part-way and its rollback failed: the next start must finish it first.
                runCatching { input.close() }
                throw BackupException(BackupError.RESTORE_UNFINISHED, "an earlier restore is still being finished")
            }
            BackupFiles.deleteRecursively(tmp)
            val manifest = unpack(input, tmp, stillWanted)
            val token = UUID.randomUUID().toString()
            BackupFiles.writeSynced(File(tmp, AppStorage.TOKEN), token.toByteArray())
            BackupFiles.writeSynced(File(tmp, AppStorage.STAGED_AT), now().toString().toByteArray())
            BackupFiles.writeSynced(File(tmp, AppStorage.READY), BackupManifest.encode(manifest))
            if (!stillWanted()) throw CancellationException("the import was abandoned")
            // The new import replaces one that is waiting: the swap is two renames, and a crash
            // between them leaves no staging (nothing applied), never a mix of both.
            BackupFiles.deleteRecursively(storage.stagingDir)
            if (!tmp.renameTo(storage.stagingDir)) throw IOException("could not move the staging folder into place")
            placed = true
            log("staged a backup: ${manifest.entries.size} file(s), own sounds ${manifest.ownSounds}")
            return StagedImport(manifest, token)
        } catch (e: CancellationException) {
            log("import abandoned")
            throw e
        } catch (e: BackupException) {
            throw e
        } catch (e: ZipException) {
            throw BackupException(BackupError.DAMAGED, "zip unreadable: ${e.javaClass.simpleName}", e)
        } catch (e: IOException) {
            val full = e.message?.let { "ENOSPC" in it || "No space" in it } == true
            throw BackupException(if (full) BackupError.NO_SPACE else BackupError.READ_FAILED, "reading the backup failed: ${e.javaClass.simpleName}", e)
        } catch (e: RuntimeException) {
            // ZipInputStream throws IllegalArgumentException for an entry name that isn't UTF-8, and other runtime errors of its own.
            throw BackupException(BackupError.DAMAGED, "zip unreadable: ${e.javaClass.simpleName}", e)
        } finally {
            // Whatever went wrong (Errors included), a half-unpacked folder never stays behind.
            if (!placed) runCatching { BackupFiles.deleteRecursively(tmp) }
        }
    }

    private class Staged(val path: String, val kind: AllowedPath, val size: Long, val sha256: String)

    private fun unpack(input: InputStream, into: File, stillWanted: () -> Boolean): BackupManifest {
        var manifest: BackupManifest? = null
        var listed: Map<String, BackupManifest.Entry> = emptyMap()
        val staged = LinkedHashMap<String, Staged>()
        val folders = HashSet<String>()
        var entries = 0
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                if (!stillWanted()) throw CancellationException("the import was abandoned")
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (++entries > limits.maxEntries) throw BackupException(BackupError.TOO_LARGE, "more than ${limits.maxEntries} entries")
                if (entry.isDirectory) {
                    if (!BackupInventory.isAllowedDirectory(name)) throw unexpected(name, manifest, "unexpected folder entry")
                    if (!folders.add(name)) throw BackupException(BackupError.DAMAGED, "duplicate folder entry")
                    // A folder entry carries nothing; a payload would be inflated for nothing and counted nowhere.
                    if (zip.read(ByteArray(1)) != -1) throw BackupException(BackupError.DAMAGED, "a folder entry with content")
                    continue
                }
                val kind = BackupInventory.allowedPath(name) ?: throw unexpected(name, manifest, "entry outside the allow-list")
                val m = manifest
                if (m == null) {
                    // First, so nothing is inflated before the format, the sizes and the space are known.
                    if (kind != AllowedPath.Manifest) throw BackupException(BackupError.NOT_A_BACKUP, "the first file isn't the manifest")
                    val decoded = BackupManifest.decode(readCapped(zip, limits.maxManifestBytes))
                    listed = checkManifest(decoded, into)
                    manifest = decoded
                    continue
                }
                if (kind == AllowedPath.Manifest || name in staged) throw BackupException(BackupError.DAMAGED, "duplicate entry")
                val declared = listed[name] ?: throw BackupException(BackupError.DAMAGED, "an entry the manifest doesn't list")
                val (size, sha) = copyCapped(zip, File(into, name), declared.size, stillWanted)
                staged[name] = Staged(name, kind, size, sha)
            }
        }
        val m = manifest ?: throw BackupException(BackupError.NOT_A_BACKUP, "no manifest")
        verify(listed, staged, into)
        return m
    }

    /** Everything the manifest alone can tell, before anything is inflated. Returns its entries by path. */
    private fun checkManifest(manifest: BackupManifest, into: File): Map<String, BackupManifest.Entry> {
        val listed = LinkedHashMap<String, BackupManifest.Entry>()
        for (entry in manifest.entries) {
            if (listed.put(entry.path, entry) != null) throw BackupException(BackupError.DAMAGED, "manifest lists a file twice")
        }
        if (manifest.entries.size + 1 > limits.maxEntries) throw BackupException(BackupError.TOO_LARGE, "more than ${limits.maxEntries} entries listed")
        var declared = 0L
        var sounds = 0
        for (entry in manifest.entries) {
            val kind = BackupInventory.allowedPath(entry.path)
            if (kind == null || kind == AllowedPath.Manifest) throw unexpected(entry.path, manifest, "manifest lists a file outside the allow-list")
            if (entry.size < 0) throw BackupException(BackupError.DAMAGED, "a negative size")
            if (kind is AllowedPath.OwnSound) {
                if (!manifest.ownSounds) throw BackupException(BackupError.DAMAGED, "own sounds listed but not declared")
                sounds++
            }
            val cap = if (kind is AllowedPath.OwnSound) limits.maxOwnSoundBytes else limits.maxSettingsFileBytes
            if (entry.size > cap) throw BackupException(BackupError.TOO_LARGE, "a file over its cap")
            declared += entry.size
        }
        if (manifest.ownSounds && sounds == 0) throw BackupException(BackupError.DAMAGED, "own sounds declared but none listed")
        if (declared > limits.maxTotalBytes) throw BackupException(BackupError.TOO_LARGE, "declared size over the cap")
        into.mkdirs()
        val free = into.usableSpace
        // usableSpace is 0 when it can't be read: don't refuse on that, the copy will tell.
        if (free in 1 until declared + limits.spareBytes) throw BackupException(BackupError.NO_SPACE, "not enough free space")
        return listed
    }

    /**
     * A path this build doesn't restore. From a newer build (its manifest says so) a plain path
     * is most likely a store added since: "update Earshot first" says what to do, "files Earshot
     * doesn't restore" would not. A path that tries to leave the folder is never that.
     */
    private fun unexpected(path: String, manifest: BackupManifest?, why: String): BackupException {
        val current = currentVersionCode
        if (manifest != null && current != null && manifest.versionCode > current && BackupInventory.isPlainRelative(path)) {
            return BackupException(BackupError.NEWER_FORMAT, "$why, from a newer build (${manifest.versionCode} > $current)")
        }
        return BackupException(BackupError.UNEXPECTED_FILE, why)
    }

    private fun verify(listed: Map<String, BackupManifest.Entry>, staged: Map<String, Staged>, dir: File) {
        if (listed.keys != staged.keys) throw BackupException(BackupError.DAMAGED, "files and manifest differ (${staged.size} found, ${listed.size} listed)")
        for (file in staged.values) {
            val entry = listed.getValue(file.path)
            if (entry.size != file.size || !entry.sha256.equals(file.sha256, ignoreCase = true)) {
                throw BackupException(BackupError.DAMAGED, "checksum or size mismatch")
            }
        }
        // The files must be what their names say: a DataStore file must parse, a SharedPreferences file be
        // plain SharedPreferences XML (no DOCTYPE: it is restored byte for byte and parsed at every start).
        // (Key types are checked where they matter, when the restore is applied: BackupApplier drops mistyped keys.)
        for (file in staged.values) {
            val item = (file.kind as? AllowedPath.Item)?.item ?: continue
            val bytes = File(dir, file.path).readBytes()
            when (item) {
                is BackupItem.PreferencesStore -> runCatching { PreferencesFiles.decode(bytes) }
                    .onFailure { throw BackupException(BackupError.DAMAGED, "a settings file doesn't parse", it) }
                is BackupItem.SharedPreferencesFile -> if (!SharedPreferencesXml.isPlain(bytes)) {
                    throw BackupException(BackupError.DAMAGED, "a preferences file isn't plain SharedPreferences XML")
                }
            }
        }
    }

    private fun readCapped(input: InputStream, cap: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        var size = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            size += n
            if (size > cap) throw BackupException(BackupError.TOO_LARGE, "manifest over the cap")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Streams one entry to [target], refusing it as soon as it passes [declaredSize] (which the
     * manifest check already held to the caps and the free space), before those bytes are written.
     */
    private fun copyCapped(input: InputStream, target: File, declaredSize: Long, stillWanted: () -> Boolean): Pair<Long, String> {
        target.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        var buffers = 0
        FileOutputStream(target).use { out ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                size += n
                if (size > declaredSize) throw BackupException(BackupError.DAMAGED, "an entry larger than the manifest says")
                digest.update(buffer, 0, n)
                out.write(buffer, 0, n)
                if (++buffers % CANCEL_CHECK_BUFFERS == 0 && !stillWanted()) throw CancellationException("the import was abandoned")
            }
            out.flush()
            out.fd.sync()
        }
        if (size != declaredSize) throw BackupException(BackupError.DAMAGED, "an entry smaller than the manifest says")
        return size to digest.digest().toHex()
    }

    private companion object {
        const val BUFFER = 64 * 1024

        /** About every 1 MB: whether the screen that started the import is still there. */
        const val CANCEL_CHECK_BUFFERS = 16
    }
}
