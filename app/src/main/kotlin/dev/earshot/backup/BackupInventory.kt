package dev.earshot.backup

/**
 * What a settings backup may hold: an allow-list, nothing else is ever written into the zip or
 * accepted from one. Every path in a backup is one of [BackupInventory.allowedPath]'s shapes,
 * relative to the app's private storage (see [AppStorage.target]).
 *
 * ## Included (category [BackupCategory.SETTINGS])
 *
 * | Store (`files/datastore/<name>.preferences_pb`) | Holds | Kept on the phone instead |
 * |---|---|---|
 * | `audio_tuning` | Pro EQ settings, the user's presets, per-app profiles, global-session switch | – |
 * | `headset_eq` | the Pro EQ preset per headset (by its Bluetooth address) | – |
 * | `headset_shortcuts` | Shortcut button choices, per-headset overrides, spoken feedback, detected buttons | `seen_headset_press`: whether a real press reached Earshot *on this phone* (drives a first-press hint) |
 * | `call_audio` | call boost on/off, level, raise the headset too | – |
 * | `earshot_automation` | automation rules, low-battery alert, notification, auto-connect, tile cycle, appearance (theme), the mode-ref migration marker | `recent_log_json`: what the rules did on the old phone (history, not a setting) |
 * | `bt_device` | the saved headset, its "Headphone type", the families that answered, link hints | – (all of it is about the user's own headsets and stays valid for the same headset on a new phone; a stale link hint only costs one probe) |
 * | `sleep` | tonight's plan, wake alarms, saved mixes | `night`: a night running or recorded on that phone (restore, ring and snooze records). Imported, the new phone would "recover" a night it never had and restore settings it never changed |
 *
 * | SharedPreferences (`shared_prefs/<name>.xml`) | Holds |
 * |---|---|
 * | `whats_new` | the release whose "What's new" sheet was seen, so the restored app doesn't show it again |
 *
 * ## Optional (category [BackupCategory.OWN_SOUNDS])
 *
 * `files/sleep_custom/<id>`: the user's own sleep sounds, plus the `sleep` store's
 * `custom_sounds` key that lists them (the two only travel together). Left out, the target
 * phone keeps its own sounds and its own list.
 *
 * ## Excluded ([BackupInventory.EXCLUDED], reasons there)
 *
 * Bluetooth history, demo mode, the Bragi pairing keys, the locked-boot alarm mirror, caches,
 * Lab scans and (Android 9) voice memos, the backup's own staging files, and everything the app
 * doesn't write itself (libraries' files). Pairings and companion links belong to Android, not to us.
 *
 * ## Key types
 *
 * Every store lists the type of each key the app reads ([BackupItem.PreferencesStore.keyTypes],
 * and [BackupItem.PreferencesStore.keyPrefixTypes] for keys named at run time, such as
 * `preset_<address>`). A restore drops any imported key whose type doesn't match (the phone keeps
 * its own value) and any key this build doesn't know (a newer build's): DataStore's typed getters
 * do an unchecked cast, so one mistyped value would crash the app at every start.
 * `BackupInventoryTest` checks the lists against the key declarations in the source.
 */
enum class BackupCategory { SETTINGS, OWN_SOUNDS }

/** The type a DataStore key holds, as the app declares it (`booleanPreferencesKey`, `intPreferencesKey`, …). */
enum class PrefType {
    BOOLEAN, INT, LONG, FLOAT, DOUBLE, STRING, STRING_SET;

    /** Whether [value], as `PreferencesSerializer` decoded it, is of this type. */
    fun matches(value: Any?): Boolean = when (this) {
        BOOLEAN -> value is Boolean
        INT -> value is Int
        LONG -> value is Long
        FLOAT -> value is Float
        DOUBLE -> value is Double
        STRING -> value is String
        STRING_SET -> value is Set<*> && value.all { it is String }
    }
}

/** One allow-listed file. [path] is its name in the zip and in the manifest. */
sealed interface BackupItem {
    val path: String

    /**
     * A DataStore Preferences file. [keyTypes] is every key the app reads from it, with its type;
     * [keyPrefixTypes] covers keys whose name is built at run time (prefix → type). [localKeys]
     * belong to the phone (in-flight state, history): never exported, and on import the phone's
     * own values are kept. [ownSoundKeys] travel only with [BackupCategory.OWN_SOUNDS]; without it
     * they behave like [localKeys].
     */
    data class PreferencesStore(
        val name: String,
        val keyTypes: Map<String, PrefType>,
        val keyPrefixTypes: Map<String, PrefType> = emptyMap(),
        val localKeys: Set<String> = emptySet(),
        val ownSoundKeys: Set<String> = emptySet(),
    ) : BackupItem {
        override val path: String get() = "${BackupInventory.DATASTORE_DIR}/$name${BackupInventory.DATASTORE_SUFFIX}"

        /** The type this build reads [key] as, or null when it doesn't know the key. */
        fun typeOf(key: String): PrefType? =
            keyTypes[key] ?: keyPrefixTypes.entries.firstOrNull { key.startsWith(it.key) }?.value
    }

    /** A plain SharedPreferences XML file, copied as it is. */
    data class SharedPreferencesFile(val name: String) : BackupItem {
        override val path: String get() = "${BackupInventory.SHARED_PREFS_DIR}/$name.xml"
    }
}

/** Something the app stores that is deliberately not in a backup, with the reason (shown in tests and docs). */
data class ExcludedItem(val what: String, val reason: String)

object BackupInventory {
    const val MANIFEST = "manifest.json"
    const val DATASTORE_DIR = "datastore"
    const val DATASTORE_SUFFIX = ".preferences_pb"
    const val SHARED_PREFS_DIR = "shared_prefs"

    /** The user's own sleep sounds in the zip: `files/sleep_custom/<id>` (`BundledRecordings`' folder). */
    const val OWN_SOUNDS_DIR = "files/sleep_custom"

    /** The `sleep` store key that lists the own sounds ([dev.earshot.sleep.SleepStore]'s `custom_sounds`). */
    const val SLEEP_CUSTOM_SOUNDS_KEY = "custom_sounds"

    /** [dev.earshot.sleep.SleepStore]'s DataStore and its wake alarms (a JSON list of objects with an `id`). */
    const val SLEEP_STORE = "sleep"
    const val SLEEP_ALARMS_KEY = "alarms"

    private val S = PrefType.STRING
    private val B = PrefType.BOOLEAN
    private val I = PrefType.INT

    val stores: List<BackupItem.PreferencesStore> = listOf(
        // AudioTuningRepositoryImpl
        BackupItem.PreferencesStore(
            "audio_tuning",
            keyTypes = mapOf("settings_json" to S, "user_presets_json" to S, "app_profiles_json" to S, "use_global_session" to B),
        ),
        // HeadsetEqMemory: one key per headset, preset_<address>
        BackupItem.PreferencesStore("headset_eq", keyTypes = emptyMap(), keyPrefixTypes = mapOf("preset_" to S)),
        // ShortcutPrefs
        BackupItem.PreferencesStore(
            "headset_shortcuts",
            keyTypes = mapOf(
                "default_action" to S, "overrides" to S, "spoken_feedback" to B, "seen_headset_press" to B,
                "detected_shortcuts" to S, "button_choices" to S,
            ),
            localKeys = setOf("seen_headset_press"),
        ),
        // CallAudioPrefsStore
        BackupItem.PreferencesStore("call_audio", keyTypes = mapOf("boost_enabled" to B, "boost_percent" to I, "also_raise_headset" to B)),
        // AutomationRepositoryImpl
        BackupItem.PreferencesStore(
            "earshot_automation",
            keyTypes = mapOf(
                "rules_json" to S, "recent_log_json" to S, "low_battery_enabled" to B, "low_battery_threshold" to I,
                "persistent_notification" to B, "auto_connect_on_bluetooth" to B, "tile_cycle_modes" to S,
                "tile_cycle_refs_json" to S, "mode_refs_v1" to B, "appearance" to S,
            ),
            localKeys = setOf("recent_log_json"),
        ),
        // DevicePrefs: the legacy keys plus hint_<family>
        BackupItem.PreferencesStore(
            "bt_device",
            keyTypes = mapOf(
                "address" to S, "name" to S, "rfcomm_channel" to I, "bragi_profile" to S, "family_override" to S,
                "family_override_address" to S, "known_families" to PrefType.STRING_SET,
            ),
            keyPrefixTypes = mapOf("hint_" to S),
        ),
        // SleepStore
        BackupItem.PreferencesStore(
            SLEEP_STORE,
            keyTypes = mapOf("plan" to S, SLEEP_ALARMS_KEY to S, "mixes" to S, "night" to S, SLEEP_CUSTOM_SOUNDS_KEY to S),
            localKeys = setOf("night"),
            ownSoundKeys = setOf(SLEEP_CUSTOM_SOUNDS_KEY),
        ),
    )

    val sharedPreferences: List<BackupItem.SharedPreferencesFile> = listOf(
        BackupItem.SharedPreferencesFile("whats_new"),
    )

    val items: List<BackupItem> = stores + sharedPreferences

    val EXCLUDED: List<ExcludedItem> = listOf(
        ExcludedItem("files/bt_timeline.tsv", "Bluetooth history: about this phone's links, and it holds other people's headsets' addresses"),
        ExcludedItem("datastore/demo_mode", "demo mode talks to a virtual headset; on a new phone it would hide the real one"),
        ExcludedItem("shared_prefs/bragi_trust", "Bragi pairing keys, wrapped by an Android Keystore key that never leaves this phone; useless (and replaced) elsewhere"),
        ExcludedItem("device-protected shared_prefs/sleep_locked", "the locked-boot copy of the next alarms, rebuilt from the alarms at every start"),
        ExcludedItem("cache/", "caches, such as the mic check recording"),
        ExcludedItem(
            "external files lab/ and Recordings/",
            "Lab scans (and voice memos on Android 9, which has no shared Recordings folder for us) live in app-specific " +
                "external storage, Android/data/<package>/files, which Android deletes with the app; they are files, not " +
                "settings, so the screen tells the user to copy them out before removing the old app",
        ),
        ExcludedItem("files/backup/", "this feature's own staging folder and restored notice"),
        ExcludedItem("companion links, pairings, permissions, exact-alarm access", "held by Android for this phone; the user grants them again"),
        ExcludedItem("everything else under files/", "written by libraries (profile installer and the like), never by Earshot"),
    )

    /** The DataStore names that are deliberately left out (the inventory test checks every store in the source is decided). */
    val excludedStoreNames: Set<String> = setOf("demo_mode")

    /**
     * SharedPreferences names deliberately left out. `ui_prompts` (ui/onboarding/NotificationPromptStore)
     * only records whether Earshot already asked for the notification permission on this phone, and
     * permissions are per phone.
     */
    val excludedSharedPreferences: Set<String> = setOf("bragi_trust", "sleep_locked", "ui_prompts")

    private val ownSoundName = Regex("[A-Za-z0-9-]{1,64}")

    /** What a zip path is, or null when it is not allowed (anything outside the allow-list, `..`, absolute, backslashes …). */
    fun allowedPath(path: String): AllowedPath? {
        if (!isPlainRelative(path)) return null
        if (path == MANIFEST) return AllowedPath.Manifest
        items.firstOrNull { it.path == path }?.let { return AllowedPath.Item(it) }
        if (path.startsWith("$OWN_SOUNDS_DIR/")) {
            val name = path.removePrefix("$OWN_SOUNDS_DIR/")
            if (ownSoundName.matches(name)) return AllowedPath.OwnSound(name)
        }
        return null
    }

    /** Directory entries some zip tools add; skipped, but only once each and only when they carry nothing. */
    fun isAllowedDirectory(path: String): Boolean =
        path in setOf("$DATASTORE_DIR/", "$SHARED_PREFS_DIR/", "files/", "$OWN_SOUNDS_DIR/")

    /** `a/b/c` with plain segments only: no leading slash, drive letter, backslash, `.` or `..` or empty segment. */
    internal fun isPlainRelative(path: String): Boolean {
        if (path.isEmpty() || path.length > 200) return false
        if (path.startsWith("/") || '\\' in path || ':' in path || '\u0000' in path) return false
        return path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    }

    fun isOwnSoundName(name: String): Boolean = ownSoundName.matches(name)
}

sealed interface AllowedPath {
    data object Manifest : AllowedPath
    data class Item(val item: BackupItem) : AllowedPath
    data class OwnSound(val name: String) : AllowedPath
}
