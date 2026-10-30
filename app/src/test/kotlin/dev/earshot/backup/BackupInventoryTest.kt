package dev.earshot.backup

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The allow-list: which paths a backup may hold, and a tripwire that every store the app
 * declares has been decided (included or excluded with a reason), so a new DataStore can't
 * silently miss the backup or slip into it.
 */
class BackupInventoryTest {

    // Gradle runs unit tests with the module directory as the working directory.
    private val sources = File("src/main/kotlin/dev/earshot")

    @Test fun `allowed paths are exactly the inventory`() {
        assertEquals(AllowedPath.Manifest, BackupInventory.allowedPath("manifest.json"))
        for (item in BackupInventory.items) assertEquals(AllowedPath.Item(item), BackupInventory.allowedPath(item.path), item.path)
        assertEquals(AllowedPath.OwnSound("3f2a9c1e-77b0-4c1d-9a51-0b6f2d1e8a44"), BackupInventory.allowedPath("files/sleep_custom/3f2a9c1e-77b0-4c1d-9a51-0b6f2d1e8a44"))
        assertEquals(
            setOf(
                "datastore/audio_tuning.preferences_pb", "datastore/headset_eq.preferences_pb", "datastore/headset_shortcuts.preferences_pb",
                "datastore/call_audio.preferences_pb", "datastore/earshot_automation.preferences_pb", "datastore/bt_device.preferences_pb",
                "datastore/sleep.preferences_pb", "shared_prefs/whats_new.xml",
            ),
            BackupInventory.items.map { it.path }.toSet(),
        )
    }

    @Test fun `paths that leave the folder or aren't listed are refused`() {
        for (path in listOf(
            "../datastore/sleep.preferences_pb",
            "datastore/../../evil",
            "files/sleep_custom/../../../evil",
            "/data/data/dev.earshot/files/datastore/sleep.preferences_pb",
            "/manifest.json",
            "C:/Windows/evil",
            "datastore\\sleep.preferences_pb",
            "./manifest.json",
            "datastore//sleep.preferences_pb",
            "datastore/sleep.preferences_pb/",
            "files/sleep_custom/",
            "files/sleep_custom/a/b",
            "files/sleep_custom/name.with.dots",
            "files/sleep_custom/" + "a".repeat(65),
            "datastore/demo_mode.preferences_pb",
            "shared_prefs/bragi_trust.xml",
            "shared_prefs/sleep_locked.xml",
            "files/bt_timeline.tsv",
            "files/backup/staging/READY",
            "datastore/sleep.preferences_pb.restore",
            "",
        )) assertNull(BackupInventory.allowedPath(path), path)
    }

    @Test fun `excluded stores are never on the allow-list`() {
        val included = BackupInventory.stores.map { it.name }.toSet()
        assertTrue((included intersect BackupInventory.excludedStoreNames).isEmpty())
        val includedPrefs = BackupInventory.sharedPreferences.map { it.name }.toSet()
        assertTrue((includedPrefs intersect BackupInventory.excludedSharedPreferences).isEmpty())
        assertTrue(BackupInventory.EXCLUDED.all { it.reason.isNotBlank() })
    }

    @Test fun `every DataStore in the source is decided`() {
        assertTrue(sources.isDirectory, "run from the app module directory (${File(".").absolutePath})")
        val declared = Regex("""preferencesDataStore\(\s*name\s*=\s*"([^"]+)"""")
        val names = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> declared.findAll(file.readText()).map { it.groupValues[1] } }
            .toSet()
        assertTrue("sleep" in names && "bt_device" in names, "the scan found the stores: $names")
        val decided = BackupInventory.stores.map { it.name }.toSet() + BackupInventory.excludedStoreNames
        assertEquals(emptySet(), names - decided, "new DataStore: add it to BackupInventory.stores or excludedStoreNames, with the reason")
        assertEquals(emptySet(), BackupInventory.stores.map { it.name }.toSet() - names, "an inventory store no longer exists in the source")
    }

    @Test fun `every SharedPreferences file in the source is decided`() {
        // The names are constants, so the scan pins the files that open SharedPreferences instead.
        val opening = sources.walkTopDown().filter { it.isFile && it.extension == "kt" && "getSharedPreferences(" in it.readText() }
            .map { it.name }.toSet()
        assertEquals(
            setOf("AndroidBragiTrustStore.kt", "WhatsNew.kt", "LockedAlarms.kt", "NotificationPromptStore.kt"),
            opening,
            "a new SharedPreferences file: decide it in BackupInventory (sharedPreferences or excludedSharedPreferences) and update this list",
        )
        assertTrue("\"whats_new\"" in File(sources, "ui/whatsnew/WhatsNew.kt").readText())
        assertTrue("\"bragi_trust\"" in File(sources, "bt/AndroidBragiTrustStore.kt").readText())
        assertTrue("\"sleep_locked\"" in File(sources, "sleep/LockedAlarms.kt").readText())
        assertTrue("\"ui_prompts\"" in File(sources, "ui/onboarding/NotificationPromptStore.kt").readText())
    }

    @Test fun `the sleep store's transient key stays on the phone`() {
        val sleep = BackupInventory.stores.first { it.name == "sleep" }
        assertEquals(setOf("night"), sleep.localKeys)
        assertEquals(setOf("custom_sounds"), sleep.ownSoundKeys)
        // The keys SleepStore writes: the transient one must be in localKeys, the rest travel.
        val sleepStore = File(sources, "sleep/SleepStore.kt").readText()
        val keys = Regex("""stringPreferencesKey\("([^"]+)"\)""").findAll(sleepStore).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("plan", "alarms", "mixes", "night", "custom_sounds"), keys, "SleepStore keys changed: decide the new one for the backup")
    }

    /**
     * The applier keeps only keys whose value has the type this build reads ([PrefType]), so the
     * type lists must follow the source: a key added without its type would be dropped from every
     * restore, a key whose type changed would be dropped as mistyped. Each included store's keys
     * are declared in the one file that declares the store (its DataStore delegate is private there).
     */
    @Test fun `every key the app reads has its type in the inventory`() {
        val files = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val declared = Regex("""preferencesDataStore\(\s*name\s*=\s*"([^"]+)"""")
        val keyCall = Regex("""\b(boolean|int|long|float|double|stringSet|string|byteArray)PreferencesKey\(([^)]*)\)""")
        val literal = Regex("""^"([^"$]+)"$""")
        val templated = Regex("""^"([^"$]*)\$.*"$""")
        val constantPlus = Regex("""^(\w+)\s*\+.*""")
        for (store in BackupInventory.stores) {
            val owners = files.filter { file -> declared.findAll(file.readText()).any { it.groupValues[1] == store.name } }
            assertEquals(1, owners.size, "${store.name}: one file declares it ($owners)")
            val text = owners.single().readText()
            val constants = Regex("""const val (\w+)\s*=\s*"([^"$]*)"""").findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
            val exact = HashMap<String, PrefType>()
            val prefixes = HashMap<String, PrefType>()
            for (call in keyCall.findAll(text)) {
                val type = when (call.groupValues[1]) {
                    "boolean" -> PrefType.BOOLEAN
                    "int" -> PrefType.INT
                    "long" -> PrefType.LONG
                    "float" -> PrefType.FLOAT
                    "double" -> PrefType.DOUBLE
                    "string" -> PrefType.STRING
                    "stringSet" -> PrefType.STRING_SET
                    else -> error("${store.name}: ${call.value} has no PrefType yet: add one, and to PrefType.matches")
                }
                val arg = call.groupValues[2].trim()
                literal.matchEntire(arg)?.let { exact[it.groupValues[1]] = type }
                    ?: templated.matchEntire(arg)?.let { prefixes[it.groupValues[1]] = type }
                    ?: constantPlus.matchEntire(arg)?.let {
                        prefixes[constants[it.groupValues[1]] ?: error("${store.name}: can't resolve ${it.groupValues[1]}")] = type
                    }
                // Anything else re-makes a key from a stored name (DevicePrefs' `stringPreferencesKey(it.name)`), declared above.
            }
            assertTrue(exact.isNotEmpty() || prefixes.isNotEmpty(), "${store.name}: the scan found its keys")
            assertEquals(
                exact.toSortedMap(), store.keyTypes.toSortedMap(),
                "${store.name}: a key was added, removed or retyped: update BackupInventory.stores (keyTypes) and decide whether it is local",
            )
            assertEquals(
                prefixes.toSortedMap(), store.keyPrefixTypes.toSortedMap(),
                "${store.name}: a key built at run time changed: update BackupInventory.stores (keyPrefixTypes)",
            )
            assertTrue(store.keyTypes.keys.containsAll(store.localKeys + store.ownSoundKeys), "${store.name}: local and own-sound keys are keys")
        }
    }

    @Test fun `key types match what DataStore decodes`() {
        assertTrue(PrefType.BOOLEAN.matches(true))
        assertTrue(PrefType.INT.matches(3))
        assertFalse(PrefType.INT.matches(3L), "a Long is not an Int: DataStore keeps them apart")
        assertTrue(PrefType.LONG.matches(3L))
        assertTrue(PrefType.FLOAT.matches(1f))
        assertFalse(PrefType.FLOAT.matches(1.0))
        assertTrue(PrefType.DOUBLE.matches(1.0))
        assertTrue(PrefType.STRING.matches("x"))
        assertFalse(PrefType.STRING.matches(7))
        assertTrue(PrefType.STRING_SET.matches(setOf("a")))
        assertFalse(PrefType.STRING_SET.matches(setOf(1)))
        assertFalse(PrefType.STRING.matches(null))
        val bt = BackupInventory.stores.first { it.name == "bt_device" }
        assertEquals(PrefType.STRING, bt.typeOf("hint_sony"))
        assertEquals(PrefType.INT, bt.typeOf("rfcomm_channel"))
        assertNull(bt.typeOf("added_by_a_newer_build"))
    }

    @Test fun `plain relative paths`() {
        assertTrue(BackupInventory.isPlainRelative("a/b/c"))
        assertFalse(BackupInventory.isPlainRelative("a/../c"))
        assertFalse(BackupInventory.isPlainRelative("a/\u0000"))
    }
}
