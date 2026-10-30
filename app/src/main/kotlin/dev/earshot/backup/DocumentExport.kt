package dev.earshot.backup

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/** The document an export goes into (a Storage Access Framework document in the app). */
internal interface ExportTarget {
    /** Its size in bytes, or null when the provider doesn't say. */
    fun size(): Long?

    /** Opens it for writing from the start, dropping what was in it. */
    fun openForWrite(): OutputStream

    /** Removes it. */
    fun delete()
}

/**
 * How an export reaches the user's document, Android-free so it is tested on the JVM. The picker
 * may hand back an existing backup to overwrite, and opening it for writing truncates it at once,
 * so the whole zip is built in [tempDir] first (the stores read, every own sound hashed and
 * copied: everything that can fail), then the built zip is opened, and only then is the document
 * opened and the zip copied in.
 *
 * A failure before the copy leaves the document untouched; it is removed only when it is the
 * empty one the picker just created. A failed copy removes a document that was empty before (it
 * never held anything worth keeping) and leaves one that had content, or whose size the provider
 * doesn't report: [BackupError.OVERWRITE_FAILED] says it may now be incomplete.
 */
internal object DocumentExport {
    /** Older leftovers of an export the process didn't survive: never one still being written. */
    const val STALE_TEMP_MILLIS = 60L * 60 * 1000
    private const val BUFFER = 64 * 1024

    fun export(target: ExportTarget, tempDir: File, now: Long = System.currentTimeMillis(), build: (OutputStream) -> ExportResult): ExportResult {
        val sizeBefore = runCatching { target.size() }.getOrNull()
        tempDir.mkdirs()
        tempDir.listFiles()?.filter { it.lastModified() < now - STALE_TEMP_MILLIS }?.forEach { it.delete() }
        val temp = File(tempDir, "${UUID.randomUUID()}.zip")
        try {
            val result = try {
                FileOutputStream(temp).use { out -> build(out) }
            } catch (e: Exception) {
                // The document was never opened: one with content is intact, the empty new one goes.
                if (sizeBefore == 0L && runCatching { target.size() }.getOrNull() == 0L) runCatching { target.delete() }
                throw asBackupException(e)
            }
            // The built zip is opened before the document: "wt" truncates the document the moment it
            // opens, and Android may clear cacheDir under storage pressure (just when a large export
            // runs). Gone by now, it costs nothing; once open, the descriptor keeps it readable.
            val input = try {
                temp.inputStream()
            } catch (e: Exception) {
                if (sizeBefore == 0L && runCatching { target.size() }.getOrNull() == 0L) runCatching { target.delete() }
                throw asBackupException(e)
            }
            try {
                input.use { src ->
                    target.openForWrite().use { out ->
                        src.copyTo(out, BUFFER)
                        out.flush()
                    }
                }
            } catch (e: Exception) {
                if (sizeBefore == 0L) {
                    runCatching { target.delete() }
                    throw BackupException(BackupError.WRITE_FAILED, "copying into the new document failed: ${e.javaClass.simpleName}", e)
                }
                throw BackupException(BackupError.OVERWRITE_FAILED, "copying over a document failed: ${e.javaClass.simpleName}", e)
            }
            return result
        } finally {
            temp.delete()
        }
    }

    private fun asBackupException(e: Exception): BackupException = when (e) {
        is BackupException -> e
        is IOException -> {
            val full = e.message?.let { "ENOSPC" in it || "No space" in it } == true
            BackupException(if (full) BackupError.NO_SPACE else BackupError.WRITE_FAILED, "building the backup failed: ${e.javaClass.simpleName}", e)
        }
        else -> BackupException(BackupError.WRITE_FAILED, "building the backup failed: ${e.javaClass.simpleName}", e)
    }
}
