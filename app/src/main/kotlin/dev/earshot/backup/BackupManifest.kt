package dev.earshot.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.security.MessageDigest

/**
 * `manifest.json`, the first entry of every backup. [format] is the only thing an importer must
 * understand before anything else: a higher number is refused, so a format change can't be
 * half-read. Field names are frozen for format 1; new optional fields may be added (unknown
 * ones are ignored).
 */
@Serializable
data class BackupManifest(
    val format: Int,
    /** Always [APP]: tells an Earshot backup from any other zip with a manifest. */
    val app: String,
    /** The build that wrote it (`dev.earshot`, `dev.earshot.foss`, …); informative, never checked. */
    val applicationId: String? = null,
    val versionCode: Long = 0,
    val versionName: String? = null,
    /** ISO-8601 UTC, e.g. `2026-10-27T08:15:00Z`. */
    val created: String,
    /** Whether the user's own sleep sounds (and their list) are in it. */
    val ownSounds: Boolean = false,
    val entries: List<Entry>,
) {
    @Serializable
    data class Entry(val path: String, val size: Long, val sha256: String)

    companion object {
        const val FORMAT = 1
        const val APP = "earshot"

        internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

        fun encode(manifest: BackupManifest): ByteArray = json.encodeToString(serializer(), manifest).toByteArray(Charsets.UTF_8)

        /**
         * Reads a manifest, checking the format first: a newer one is refused with
         * [BackupError.NEWER_FORMAT] before the rest is even decoded (it may not fit this class).
         */
        fun decode(bytes: ByteArray): BackupManifest {
            val obj = runCatching { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
                ?: throw BackupException(BackupError.NOT_A_BACKUP, "manifest is not a JSON object")
            if ((obj["app"] as? JsonPrimitive)?.content != APP) throw BackupException(BackupError.NOT_A_BACKUP, "manifest is not Earshot's")
            val format = (obj["format"] as? JsonPrimitive)?.intOrNull
                ?: throw BackupException(BackupError.NOT_A_BACKUP, "manifest has no format")
            if (format > FORMAT) throw BackupException(BackupError.NEWER_FORMAT, "format $format, this build reads up to $FORMAT")
            if (format < 1) throw BackupException(BackupError.NOT_A_BACKUP, "format $format")
            return runCatching { json.decodeFromJsonElement(serializer(), obj) }
                .getOrElse { throw BackupException(BackupError.DAMAGED, "manifest fields unreadable", it) }
        }
    }
}

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
