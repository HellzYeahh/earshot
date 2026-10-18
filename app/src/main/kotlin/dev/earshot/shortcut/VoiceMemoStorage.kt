package dev.earshot.shortcut

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where a voice memo goes.
 *  - Android 10+: `MediaStore.Audio`, `Recordings/Earshot`, so it shows up in the phone's recorder /
 *    file apps. "Recordings" is only an allowed audio folder from Android 12; on 10–11 MediaStore
 *    refuses it, and the memo goes to `Music/Earshot` instead. No storage permission is needed for
 *    files Earshot creates itself. The entry is `IS_PENDING` while recording, so nothing half-written
 *    is visible.
 *  - Android 9: the app's own external folder (`Android/data/dev.earshot/files/Recordings`), which
 *    needs no permission either.
 */
class VoiceMemoStorage(private val context: Context) {

    class Target(val descriptor: ParcelFileDescriptor, val uri: Uri?, val file: File?, val displayName: String, val folder: String)

    fun create(now: Date = Date()): Target {
        val name = "Earshot memo " + SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(now) + ".m4a"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) createInMediaStore(name) else createInAppFolder(name)
    }

    /** Publishes the memo ([keep]) or removes the half-written entry. */
    fun finish(target: Target, keep: Boolean) {
        runCatching { target.descriptor.close() }
        val uri = target.uri
        if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                if (keep) {
                    context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
                } else {
                    context.contentResolver.delete(uri, null, null)
                }
            }.onFailure { Log.w(TAG, "finishing the memo entry failed: $it") }
        }
        if (!keep) target.file?.let { runCatching { it.delete() } }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createInMediaStore(name: String): Target {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        for (folder in listOf(RECORDINGS_FOLDER, MUSIC_FOLDER)) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, MIME)
                put(MediaStore.Audio.Media.RELATIVE_PATH, folder)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = try {
                resolver.insert(collection, values)
            } catch (e: IllegalArgumentException) {
                // "Primary directory Recordings not allowed" before Android 12.
                Log.i(TAG, "MediaStore refused $folder, trying the next folder")
                null
            } ?: continue
            val fd = resolver.openFileDescriptor(uri, "rw")
            if (fd == null) {
                runCatching { resolver.delete(uri, null, null) }
                continue
            }
            return Target(fd, uri, null, name, folder.trimEnd('/'))
        }
        throw IllegalStateException("couldn't create the recording in shared storage")
    }

    private fun createInAppFolder(name: String): Target {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "Recordings").apply { mkdirs() }
        val file = File(dir, name)
        val fd = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
        )
        return Target(fd, null, file, name, "Android/data/${context.packageName}/files/Recordings")
    }

    private companion object {
        const val TAG = "Earshot/VoiceMemo"
        const val MIME = "audio/mp4"
        // Environment.DIRECTORY_RECORDINGS is API 31; the literal works as a path on every version.
        const val RECORDINGS_FOLDER = "Recordings/Earshot/"
        val MUSIC_FOLDER = Environment.DIRECTORY_MUSIC + "/Earshot/"
    }
}
