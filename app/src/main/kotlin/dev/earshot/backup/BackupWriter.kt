package dev.earshot.backup

import java.io.File
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The build writing a backup, for the manifest. */
data class AppInfo(val applicationId: String, val versionCode: Long, val versionName: String?)

/** The user's own sleep sounds on this phone: how many and how big, for the checkbox. */
data class OwnSoundsInfo(val count: Int, val bytes: Long) {
    /** Ticked by default only while the file stays small enough to keep around easily. */
    val includeByDefault: Boolean get() = count > 0 && bytes < DEFAULT_INCLUDE_LIMIT

    companion object {
        const val DEFAULT_INCLUDE_LIMIT = 50L * 1024 * 1024
    }
}

/** An own sound the backup had to leave out: over [BackupReader.Limits.maxOwnSoundBytes], or past the total or entry cap. */
data class LeftOutSound(val fileName: String, val displayName: String?, val bytes: Long)

/** What an export wrote, and which own sounds didn't fit. */
data class ExportResult(val manifest: BackupManifest, val leftOut: List<LeftOutSound> = emptyList())

/**
 * Writes a backup zip of the allow-listed files ([BackupInventory]) found in [storage]:
 * `manifest.json` first (so an importer decides everything before unpacking anything), then each
 * file. Phone-local keys are stripped from the DataStore files here, so they never leave the
 * phone at all. The caps are the reader's own ([BackupReader.Limits]): an own sound that would
 * break one is left out (and named in [ExportResult.leftOut]) rather than writing a backup the
 * new phone refuses whole. Android-free; the caller provides the [OutputStream].
 */
class BackupWriter(
    private val storage: AppStorage,
    private val log: (String) -> Unit = {},
    private val limits: BackupReader.Limits = BackupReader.Limits(),
) {

    fun ownSounds(): OwnSoundsInfo {
        val files = ownSoundFiles()
        return OwnSoundsInfo(files.size, files.sumOf { it.length() })
    }

    /** Writes the backup to [out] (not closed). Throws [BackupException]. */
    fun write(out: OutputStream, includeOwnSounds: Boolean, app: AppInfo, now: Instant = Instant.now()): ExportResult {
        // The sleep store as it is (its list of own sounds included) bounds what the settings take.
        var small = settingsFiles(includeOwnSoundList = includeOwnSounds, keepSounds = null)
        val candidates = if (includeOwnSounds) ownSoundFiles() else emptyList()
        val (sounds, tooBig) = fit(candidates, settingsBytes = small.values.sumOf { it.size.toLong() }, settingsFiles = small.size)
        val withSounds = sounds.isNotEmpty()
        // The list travels only with the sounds, and lists only the ones that made it (a smaller list: still within the caps).
        small = settingsFiles(includeOwnSoundList = withSounds, keepSounds = sounds.map { it.name }.toSet())

        val entries = small.map { (path, bytes) -> BackupManifest.Entry(path, bytes.size.toLong(), sha256Hex(bytes)) } +
            sounds.map { file ->
                val (size, sha) = try {
                    hashFile(file)
                } catch (e: java.io.IOException) {
                    throw BackupException(BackupError.CHANGED_DURING_EXPORT, "an own sound could not be read", e)
                }
                if (size > limits.maxOwnSoundBytes) throw BackupException(BackupError.CHANGED_DURING_EXPORT, "an own sound grew while saving")
                BackupManifest.Entry(soundPath(file), size, sha)
            }
        if (entries.sumOf { it.size } > limits.maxTotalBytes) throw BackupException(BackupError.CHANGED_DURING_EXPORT, "own sounds grew while saving")
        val manifest = BackupManifest(
            format = BackupManifest.FORMAT,
            app = BackupManifest.APP,
            applicationId = app.applicationId,
            versionCode = app.versionCode,
            versionName = app.versionName,
            created = now.toString(),
            // Only when sounds are really in it: the reader refuses the flag without them.
            ownSounds = withSounds,
            entries = entries,
        )

        try {
            val zip = ZipOutputStream(NonClosingOutputStream(out))
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            zip.putNextEntry(ZipEntry(BackupInventory.MANIFEST))
            zip.write(BackupManifest.encode(manifest))
            zip.closeEntry()
            for ((path, bytes) in small) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
            // Audio is compressed already: spend as little time as possible deflating it.
            zip.setLevel(Deflater.BEST_SPEED)
            for (file in sounds) {
                val expected = entries.first { it.path == soundPath(file) }
                zip.putNextEntry(ZipEntry(soundPath(file)))
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                val stream = try {
                    file.inputStream()
                } catch (e: java.io.FileNotFoundException) {
                    throw BackupException(BackupError.CHANGED_DURING_EXPORT, "an own sound was removed while saving", e)
                }
                DigestInputStream(stream, digest).use { input ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        zip.write(buffer, 0, n)
                        size += n
                    }
                }
                zip.closeEntry()
                if (size != expected.size || digest.digest().toHex() != expected.sha256) {
                    throw BackupException(BackupError.CHANGED_DURING_EXPORT, "an own sound changed while it was written")
                }
            }
            zip.finish()
            zip.flush()
        } catch (e: BackupException) {
            throw e
        } catch (e: java.io.IOException) {
            val full = e.message?.let { "ENOSPC" in it || "No space" in it } == true
            throw BackupException(if (full) BackupError.NO_SPACE else BackupError.WRITE_FAILED, "writing the backup failed: ${e.javaClass.simpleName}", e)
        }
        val leftOut = tooBig.map { LeftOutSound(it.name, null, it.length()) }.withDisplayNames()
        log("exported ${entries.size} file(s), own sounds ${if (includeOwnSounds) "${sounds.size} (${leftOut.size} left out)" else "not included"}")
        return ExportResult(manifest, leftOut)
    }

    /** Path → bytes of every settings file there is to save, each within its cap. */
    private fun settingsFiles(includeOwnSoundList: Boolean, keepSounds: Set<String>?): LinkedHashMap<String, ByteArray> {
        val small = LinkedHashMap<String, ByteArray>()
        for (store in BackupInventory.stores) {
            val bytes = exportedStore(store, includeOwnSoundList, keepSounds) ?: continue
            small[store.path] = bytes
        }
        for (prefs in BackupInventory.sharedPreferences) {
            val target = storage.target(prefs)
            // SharedPreferences renames the file to .bak while it writes; that copy is the whole old one.
            val source = target.takeIf { it.isFile } ?: File(target.path + ".bak").takeIf { it.isFile } ?: continue
            small[prefs.path] = source.readBytes()
        }
        for ((path, bytes) in small) {
            // Never in practice (a few kB each); refusing beats a backup the reader refuses whole.
            if (bytes.size > limits.maxSettingsFileBytes) throw BackupException(BackupError.TOO_LARGE, "settings file $path over the cap")
        }
        return small
    }

    /**
     * The own sounds that fit the reader's caps next to the settings, in name order: each at most
     * [BackupReader.Limits.maxOwnSoundBytes], all together within the total and the entry count.
     */
    private fun fit(candidates: List<File>, settingsBytes: Long, settingsFiles: Int): Pair<List<File>, List<File>> {
        val kept = ArrayList<File>()
        val left = ArrayList<File>()
        var total = settingsBytes
        var count = 1 + settingsFiles // the manifest and the settings
        for (file in candidates) {
            val size = file.length()
            if (size > limits.maxOwnSoundBytes || total + size > limits.maxTotalBytes || count + 1 > limits.maxEntries) {
                left += file
            } else {
                kept += file
                total += size
                count++
            }
        }
        return kept to left
    }

    /** The names the user gave the left-out sounds, from the sleep store's list. */
    private fun List<LeftOutSound>.withDisplayNames(): List<LeftOutSound> {
        if (isEmpty()) return this
        val sleep = runCatching { PreferencesFiles.read(storage.target(BackupInventory.stores.first { it.name == BackupInventory.SLEEP_STORE })) }.getOrNull()
        val names = OwnSoundList.namesByFile(PreferencesFiles.string(sleep, BackupInventory.SLEEP_CUSTOM_SOUNDS_KEY))
        return map { it.copy(displayName = names[it.fileName]) }
    }

    /**
     * The store's bytes as exported (phone-local keys removed; the own sounds' list only with
     * [includeOwnSoundList], and then only the sounds in [keepSounds] unless that is null), or
     * null when there is nothing to save.
     */
    private fun exportedStore(store: BackupItem.PreferencesStore, includeOwnSoundList: Boolean, keepSounds: Set<String>?): ByteArray? {
        val file = storage.target(store)
        if (!file.isFile) return null
        val prefs = try {
            PreferencesFiles.decode(file.readBytes())
        } catch (e: Exception) {
            // The app can't read it either (it would start from defaults); a backup of defaults is no backup.
            log("skipped unreadable store ${store.name}: ${e.javaClass.simpleName}")
            return null
        }
        val strip = store.localKeys + if (includeOwnSoundList) emptySet() else store.ownSoundKeys
        var exported = PreferencesFiles.without(prefs, strip)
        if (includeOwnSoundList && keepSounds != null && BackupInventory.SLEEP_CUSTOM_SOUNDS_KEY in store.ownSoundKeys) {
            val key = BackupInventory.SLEEP_CUSTOM_SOUNDS_KEY
            exported = PreferencesFiles.withString(exported, key, OwnSoundList.keepOnly(PreferencesFiles.string(exported, key), keepSounds))
        }
        if (exported.asMap().isEmpty()) return null
        return PreferencesFiles.encode(exported)
    }

    private fun ownSoundFiles(): List<File> =
        storage.ownSoundsDir.listFiles()?.filter { it.isFile && BackupInventory.isOwnSoundName(it.name) }?.sortedBy { it.name }.orEmpty()

    private fun soundPath(file: File) = "${BackupInventory.OWN_SOUNDS_DIR}/${file.name}"

    private fun hashFile(file: File): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                size += n
            }
        }
        return size to digest.digest().toHex()
    }

    /** The caller owns the stream; finishing the zip must not close it early. */
    private class NonClosingOutputStream(private val out: OutputStream) : OutputStream() {
        override fun write(b: Int) = out.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun flush() = out.flush()
        override fun close() = out.flush()
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}
