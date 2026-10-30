package dev.earshot.backup

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Temp-dir phones, preference files and hand-made zips for the backup tests. No Android. */
internal object BackupTestKit {
    val APP = AppInfo("dev.earshot", 10, "0.9.0")
    val NOW: Instant = Instant.parse("2026-10-27T08:15:00Z")

    fun phone(root: File, name: String): AppStorage =
        AppStorage(File(root, "$name/files").apply { mkdirs() }, File(root, "$name/shared_prefs").apply { mkdirs() })

    fun store(name: String): BackupItem.PreferencesStore = BackupInventory.stores.first { it.name == name }

    fun writeStore(storage: AppStorage, name: String, prefs: Preferences) {
        val file = storage.target(store(name))
        file.parentFile.mkdirs()
        file.writeBytes(PreferencesFiles.encode(prefs))
    }

    /** A store outside the inventory (demo mode), written where DataStore would put it. */
    fun writeRawStore(storage: AppStorage, name: String, prefs: Preferences) {
        File(storage.dataStoreDir, "$name.preferences_pb").apply { parentFile.mkdirs() }.writeBytes(PreferencesFiles.encode(prefs))
    }

    fun readStore(storage: AppStorage, name: String): Preferences? = PreferencesFiles.read(storage.target(store(name)))

    fun sharedPrefsXml(vararg strings: Pair<String, String>): ByteArray = buildString {
        append("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n")
        strings.forEach { (k, v) -> append("    <string name=\"$k\">$v</string>\n") }
        append("</map>\n")
    }.toByteArray()

    fun export(storage: AppStorage, includeOwnSounds: Boolean = true): ByteArray =
        ByteArrayOutputStream().also { BackupWriter(storage).write(it, includeOwnSounds, APP, NOW) }.toByteArray()

    /** The user's "Restart now" on the checked backup (the restart itself is Android's). */
    fun confirm(storage: AppStorage, at: Long = System.currentTimeMillis()) {
        check(StagingControl.confirm(storage, at)) { "nothing staged to confirm" }
    }

    /** Stages [zip] and confirms it, as a user who taps "Restart now" does. */
    fun stageAndConfirm(storage: AppStorage, zip: ByteArray): BackupManifest =
        BackupReader(storage).stage(ByteArrayInputStream(zip)).also { confirm(storage) }

    /**
     * Every file the restore may touch (all of `files/` but the backup's own folder, and
     * `shared_prefs/`), relative path → SHA-256: equal snapshots are byte-identical phones.
     */
    fun snapshot(storage: AppStorage): Map<String, String> {
        val out = sortedMapOf<String, String>()
        fun walk(root: File, prefix: String) {
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                val rel = file.relativeTo(root).invariantSeparatorsPath
                if (prefix == "files/" && rel.startsWith("backup/")) return@forEach
                out[prefix + rel] = sha256Hex(file.readBytes())
            }
        }
        walk(storage.filesDir, "files/")
        walk(storage.sharedPrefsDir, "shared_prefs/")
        return out
    }

    /** Entry name → bytes, in zip order. */
    fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out[entry.name] = zip.readBytes()
            }
        }
        return out
    }

    /** A zip with exactly these entries, in this order. */
    fun zipOf(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { buffer ->
        ZipOutputStream(buffer).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    fun manifestFor(
        files: List<Pair<String, ByteArray>>,
        format: Int = BackupManifest.FORMAT,
        ownSounds: Boolean = files.any { it.first.startsWith(BackupInventory.OWN_SOUNDS_DIR) },
    ): ByteArray {
        val manifest = BackupManifest(
            format = format, app = BackupManifest.APP, applicationId = APP.applicationId, versionCode = APP.versionCode,
            versionName = APP.versionName, created = NOW.toString(), ownSounds = ownSounds,
            entries = files.map { (path, bytes) -> BackupManifest.Entry(path, bytes.size.toLong(), sha256Hex(bytes)) },
        )
        return BackupManifest.encode(manifest)
    }

    /** A well-formed backup of [files], manifest first. */
    fun backupOf(files: List<Pair<String, ByteArray>>): ByteArray = zipOf(listOf(BackupInventory.MANIFEST to manifestFor(files)) + files)

    // Keys as the app's stores name them.
    val PLAN = stringPreferencesKey("plan")
    val ALARMS = stringPreferencesKey("alarms")
    val MIXES = stringPreferencesKey("mixes")
    val NIGHT = stringPreferencesKey("night")
    val CUSTOM = stringPreferencesKey("custom_sounds")
    val RULES = stringPreferencesKey("rules_json")
    val LOG = stringPreferencesKey("recent_log_json")
    val APPEARANCE = stringPreferencesKey("appearance")
    val MIGRATED = booleanPreferencesKey("mode_refs_v1")
    val SEEN_PRESS = booleanPreferencesKey("seen_headset_press")
    val DEFAULT_ACTION = stringPreferencesKey("default_action")
    val ADDRESS = stringPreferencesKey("address")
    val FAMILY = stringPreferencesKey("family_override")
    val CHANNEL = intPreferencesKey("rfcomm_channel")
    val KNOWN = stringSetPreferencesKey("known_families")
    val BOOST = intPreferencesKey("boost_percent")
    val DEMO_ENABLED = booleanPreferencesKey("enabled")

    fun sleepPrefs(night: String?, custom: String?): Preferences {
        val pairs = mutableListOf<Preferences.Pair<*>>(
            PLAN to """{"durationMinutes":90}""",
            ALARMS to """[{"id":"a1","hour":7,"minute":0}]""",
            MIXES to """[{"name":"Rain"}]""",
        )
        night?.let { pairs += NIGHT to it }
        custom?.let { pairs += CUSTOM to it }
        return preferencesOf(*pairs.toTypedArray())
    }
}
