package dev.earshot.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

/**
 * Export to and import from a document the user picked (Storage Access Framework). Blocking:
 * call off the main thread. The only file that ever leaves the app is the one the user saves.
 */
class BackupTransfer(context: Context) {
    private val app = context.applicationContext
    private val storage = BackupStaging.storage(app)
    private val log: (String) -> Unit = { Log.i(TAG, it) }

    fun ownSounds(): OwnSoundsInfo = runCatching { BackupWriter(storage).ownSounds() }.getOrDefault(OwnSoundsInfo(0, 0))

    /**
     * Writes the backup into [uri]: built and checked in the cache first, copied into the
     * document only once nothing can fail any more ([DocumentExport] has the rules, so an existing
     * backup the picker hands back for overwriting is never truncated or deleted by a failed export).
     */
    fun export(uri: Uri, includeOwnSounds: Boolean): ExportResult =
        DocumentExport.export(DocumentTarget(uri), File(app.cacheDir, "backup-export")) { out ->
            BackupWriter(storage, log).write(out, includeOwnSounds, appInfo())
        }

    private inner class DocumentTarget(private val uri: Uri) : ExportTarget {
        override fun size(): Long? = documentSize(uri)
        override fun openForWrite(): OutputStream = openOutput(uri) ?: throw IOException("no stream for the document")
        override fun delete() {
            DocumentsContract.deleteDocument(app.contentResolver, uri)
        }
    }

    /**
     * Checks the backup at [uri] and stages it; nothing live changes and nothing is applied until
     * the user confirms. [stillWanted] see [BackupReader.stageImport].
     */
    fun stage(uri: Uri, stillWanted: () -> Boolean = { true }): StagedImport {
        try {
            val input = app.contentResolver.openInputStream(uri) ?: throw BackupException(BackupError.READ_FAILED, "no stream for the document")
            val reader = BackupReader(storage, log = log, currentVersionCode = runCatching { appInfo().versionCode }.getOrNull())
            return input.use { reader.stageImport(it, stillWanted) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupException) {
            throw e
        } catch (e: IOException) {
            throw BackupException(BackupError.READ_FAILED, "import failed: ${e.javaClass.simpleName}", e)
        } catch (e: SecurityException) {
            throw BackupException(BackupError.READ_FAILED, "no access to the document", e)
        } catch (e: RuntimeException) {
            throw BackupException(BackupError.READ_FAILED, "import failed: ${e.javaClass.simpleName}", e)
        }
    }

    /** The document's size, or null when the provider doesn't say. */
    private fun documentSize(uri: Uri): Long? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()

    // "wt" truncates a document picked again; not every provider knows it, "w" is the fallback.
    private fun openOutput(uri: Uri): OutputStream? =
        runCatching { app.contentResolver.openOutputStream(uri, "wt") }.getOrNull() ?: app.contentResolver.openOutputStream(uri, "w")

    private fun appInfo(): AppInfo {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        return AppInfo(app.packageName, PackageInfoCompat.getLongVersionCode(info), info.versionName)
    }

    companion object {
        private const val TAG = "Earshot/Backup"
        const val MIME = "application/zip"

        /** What the open picker offers: some file managers label a zip differently. */
        val OPEN_MIME_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/x-zip", "application/octet-stream")

        fun defaultFileName(today: LocalDate = LocalDate.now()): String = "earshot-settings-$today.zip"
    }
}
