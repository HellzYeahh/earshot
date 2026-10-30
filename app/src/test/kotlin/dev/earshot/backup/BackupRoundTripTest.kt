package dev.earshot.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.preferencesOf
import dev.earshot.backup.BackupTestKit.ADDRESS
import dev.earshot.backup.BackupTestKit.ALARMS
import dev.earshot.backup.BackupTestKit.APPEARANCE
import dev.earshot.backup.BackupTestKit.BOOST
import dev.earshot.backup.BackupTestKit.CHANNEL
import dev.earshot.backup.BackupTestKit.CUSTOM
import dev.earshot.backup.BackupTestKit.DEFAULT_ACTION
import dev.earshot.backup.BackupTestKit.DEMO_ENABLED
import dev.earshot.backup.BackupTestKit.FAMILY
import dev.earshot.backup.BackupTestKit.KNOWN
import dev.earshot.backup.BackupTestKit.LOG
import dev.earshot.backup.BackupTestKit.MIGRATED
import dev.earshot.backup.BackupTestKit.NIGHT
import dev.earshot.backup.BackupTestKit.PLAN
import dev.earshot.backup.BackupTestKit.RULES
import dev.earshot.backup.BackupTestKit.SEEN_PRESS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Export on one temp-dir "phone", stage and apply on another: what travels, what stays. */
class BackupRoundTripTest {

    @get:Rule val folder = TemporaryFolder()

    private val soundA = "11111111-2222-3333-4444-555555555555"
    private val soundB = "66666666-7777-8888-9999-000000000000"

    private fun oldPhone(): AppStorage = BackupTestKit.phone(folder.root, "old").also { s ->
        BackupTestKit.writeStore(s, "sleep", BackupTestKit.sleepPrefs(night = """{"active":{"startedAtMillis":1}}""", custom = """[{"id":"custom:$soundA"}]"""))
        BackupTestKit.writeStore(s, "earshot_automation", preferencesOf(RULES to "[{\"id\":\"r1\"}]", LOG to "[\"old phone did this\"]", APPEARANCE to "FIELD", MIGRATED to true))
        BackupTestKit.writeStore(s, "headset_shortcuts", preferencesOf(DEFAULT_ACTION to "VOICE_MEMO", SEEN_PRESS to true))
        BackupTestKit.writeStore(s, "bt_device", preferencesOf(ADDRESS to "AA:BB:CC:DD:EE:01", FAMILY to "bose", CHANNEL to 2, KNOWN to setOf("AA:BB:CC:DD:EE:01=BOSE")))
        BackupTestKit.writeRawStore(s, "demo_mode", preferencesOf(DEMO_ENABLED to true))
        File(s.sharedPrefsDir, "whats_new.xml").writeBytes(BackupTestKit.sharedPrefsXml("last_seen_release" to "0.9.0"))
        File(s.sharedPrefsDir, "bragi_trust.xml").writeBytes(BackupTestKit.sharedPrefsXml("identity_private" to "secret"))
        File(s.filesDir, "bt_timeline.tsv").writeText("history\n")
        s.ownSoundsDir.mkdirs()
        File(s.ownSoundsDir, soundA).writeBytes(ByteArray(3000) { it.toByte() })
        File(s.ownSoundsDir, soundB).writeBytes(ByteArray(10) { 7 })
    }

    /** A phone that already has Earshot state of its own, including a night in progress. */
    private fun newPhone(): AppStorage = BackupTestKit.phone(folder.root, "new").also { s ->
        BackupTestKit.writeStore(s, "sleep", BackupTestKit.sleepPrefs(night = """{"active":{"startedAtMillis":99}}""", custom = """[{"id":"custom:mine"}]""").let {
            PreferencesFiles.withKeysFrom(PreferencesFiles.empty(), it, setOf("night", "custom_sounds"))
        })
        BackupTestKit.writeStore(s, "earshot_automation", preferencesOf(LOG to "[\"new phone log\"]", APPEARANCE to "DYNAMIC"))
        BackupTestKit.writeStore(s, "call_audio", preferencesOf(BOOST to 40))
        BackupTestKit.writeRawStore(s, "demo_mode", preferencesOf(DEMO_ENABLED to false))
        File(s.sharedPrefsDir, "whats_new.xml.bak").writeBytes(BackupTestKit.sharedPrefsXml("last_seen_release" to "0.1.0"))
        s.ownSoundsDir.mkdirs()
        File(s.ownSoundsDir, "mine").writeBytes(ByteArray(5) { 1 })
    }

    @Test fun `the zip holds the manifest first and only allow-listed files`() {
        val zip = BackupTestKit.unzip(BackupTestKit.export(oldPhone()))
        assertEquals(BackupInventory.MANIFEST, zip.keys.first())
        assertEquals(
            setOf(
                "manifest.json", "datastore/sleep.preferences_pb", "datastore/earshot_automation.preferences_pb",
                "datastore/headset_shortcuts.preferences_pb", "datastore/bt_device.preferences_pb", "shared_prefs/whats_new.xml",
                "files/sleep_custom/$soundA", "files/sleep_custom/$soundB",
            ),
            zip.keys,
        )
        val manifest = BackupManifest.decode(zip.getValue("manifest.json"))
        assertEquals(1, manifest.format)
        assertEquals("0.9.0", manifest.versionName)
        assertEquals(10L, manifest.versionCode)
        assertEquals("2026-10-27T08:15:00Z", manifest.created)
        assertTrue(manifest.ownSounds)
        for (entry in manifest.entries) {
            val bytes = zip.getValue(entry.path)
            assertEquals(entry.size, bytes.size.toLong(), entry.path)
            assertEquals(entry.sha256, sha256Hex(bytes), entry.path)
        }
        assertEquals(zip.keys - "manifest.json", manifest.entries.map { it.path }.toSet())
    }

    @Test fun `phone-local keys never leave the phone`() {
        val zip = BackupTestKit.unzip(BackupTestKit.export(oldPhone()))
        val automation = PreferencesFiles.decode(zip.getValue("datastore/earshot_automation.preferences_pb"))
        assertNull(automation[LOG])
        assertEquals("[{\"id\":\"r1\"}]", automation[RULES])
        assertEquals(true, automation[MIGRATED])
        val shortcuts = PreferencesFiles.decode(zip.getValue("datastore/headset_shortcuts.preferences_pb"))
        assertNull(shortcuts[SEEN_PRESS])
        assertEquals("VOICE_MEMO", shortcuts[DEFAULT_ACTION])
    }

    @Test fun `restore replaces the settings and keeps the new phone's own state`() {
        val old = oldPhone()
        val new = newPhone()
        val staged = BackupReader(new).stage(ByteArrayInputStream(BackupTestKit.export(old)))
        assertTrue(staged.ownSounds)
        // Staging touches nothing live, and arms nothing.
        assertEquals(40, BackupTestKit.readStore(new, "call_audio")!![BOOST])
        assertTrue(File(new.stagingDir, AppStorage.READY).isFile)
        assertEquals(BackupApplier.Outcome.PENDING, BackupApplier(new).applyIfReady())
        assertEquals(40, BackupTestKit.readStore(new, "call_audio")!![BOOST])

        BackupTestKit.confirm(new, at = 1000L)
        assertEquals(BackupApplier.Outcome.APPLIED, BackupApplier(new, now = { 1234L }).applyIfReady())

        val sleep = BackupTestKit.readStore(new, "sleep")!!
        assertEquals("""{"durationMinutes":90}""", sleep[PLAN])
        assertEquals("""[{"id":"a1","hour":7,"minute":0}]""", sleep[ALARMS])
        assertEquals("""{"active":{"startedAtMillis":99}}""", sleep[NIGHT], "the new phone's night carries on")
        assertEquals(
            """[{"id":"custom:mine"},{"id":"custom:$soundA"}]""",
            sleep[CUSTOM],
            "own sounds came along with their list, added to the new phone's",
        )

        val automation = BackupTestKit.readStore(new, "earshot_automation")!!
        assertEquals("[{\"id\":\"r1\"}]", automation[RULES])
        assertEquals("FIELD", automation[APPEARANCE])
        assertEquals("[\"new phone log\"]", automation[LOG])

        val device = BackupTestKit.readStore(new, "bt_device")!!
        assertEquals(2, device[CHANNEL], "an Int stays an Int")
        assertEquals(setOf("AA:BB:CC:DD:EE:01=BOSE"), device[KNOWN])
        assertEquals("bose", device[FAMILY])

        // The old phone had no call settings (defaults): the new phone's go back to defaults too.
        assertFalse(new.target(BackupTestKit.store("call_audio")).exists())
        // Never part of a backup: demo mode stays as the new phone had it.
        assertEquals(false, PreferencesFiles.read(File(new.dataStoreDir, "demo_mode.preferences_pb"))!![DEMO_ENABLED])

        assertContentEquals(File(old.sharedPrefsDir, "whats_new.xml").readBytes(), File(new.sharedPrefsDir, "whats_new.xml").readBytes())
        assertFalse(File(new.sharedPrefsDir, "whats_new.xml.bak").exists(), "a stale .bak would win over the restored file")
        assertFalse(File(new.sharedPrefsDir, "bragi_trust.xml").exists())
        assertFalse(File(new.filesDir, "bt_timeline.tsv").exists())

        assertEquals(setOf("mine", soundA, soundB), new.ownSoundsDir.list()!!.toSet(), "nothing on the new phone is deleted")
        assertContentEquals(File(old.ownSoundsDir, soundA).readBytes(), File(new.ownSoundsDir, soundA).readBytes())
        assertContentEquals(ByteArray(5) { 1 }, File(new.ownSoundsDir, "mine").readBytes())

        assertFalse(new.stagingDir.exists())
        assertFalse(new.backupDir.listFiles()!!.any { it.name.startsWith("staging") })
        assertFalse(new.dataStoreDir.listFiles()!!.any { it.name.endsWith(BackupApplier.RESTORE_SUFFIX) || it.name.endsWith(BackupApplier.PRERESTORE_SUFFIX) })
        assertFalse(new.sharedPrefsDir.listFiles()!!.any { it.name.endsWith(BackupApplier.PRERESTORE_SUFFIX) })
        val notice = assertNotNull(BackupApplier.readNotice(new))
        assertEquals(1234L, notice.restoredAtMillis)
        assertEquals("2026-10-27T08:15:00Z", notice.backupCreated)
        assertEquals(BackupApplier.Outcome.NOTHING, BackupApplier(new).applyIfReady(), "applied once only")
    }

    @Test fun `without own sounds the new phone keeps its sounds and their list`() {
        val old = oldPhone()
        val new = newPhone()
        val bytes = BackupTestKit.export(old, includeOwnSounds = false)
        val zip = BackupTestKit.unzip(bytes)
        assertFalse(zip.keys.any { it.startsWith("files/") })
        assertNull(PreferencesFiles.decode(zip.getValue("datastore/sleep.preferences_pb"))[CUSTOM])
        assertFalse(BackupManifest.decode(zip.getValue("manifest.json")).ownSounds)

        BackupTestKit.stageAndConfirm(new, bytes)
        assertEquals(BackupApplier.Outcome.APPLIED, BackupApplier(new).applyIfReady())
        val sleep = BackupTestKit.readStore(new, "sleep")!!
        assertEquals("""[{"id":"custom:mine"}]""", sleep[CUSTOM])
        assertEquals("""{"durationMinutes":90}""", sleep[PLAN])
        assertEquals(setOf("mine"), new.ownSoundsDir.list()!!.toSet())
    }

    @Test fun `a fresh install gets everything and DataStore reads it`() {
        val old = oldPhone()
        val fresh = BackupTestKit.phone(folder.root, "fresh")
        BackupTestKit.stageAndConfirm(fresh, BackupTestKit.export(old))
        assertEquals(BackupApplier.Outcome.APPLIED, BackupApplier(fresh).applyIfReady())

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { fresh.target(BackupTestKit.store("sleep")) }
            val prefs = runBlocking { store.data.first() }
            assertEquals("""{"durationMinutes":90}""", prefs[PLAN])
            assertNull(prefs[NIGHT], "no night from the old phone to recover")
        } finally {
            scope.cancel()
        }
        assertEquals(setOf(soundA, soundB), fresh.ownSoundsDir.list()!!.toSet())
    }

    @Test fun `an empty install exports a valid, empty backup`() {
        val empty = BackupTestKit.phone(folder.root, "empty")
        val bytes = BackupTestKit.export(empty)
        val zip = BackupTestKit.unzip(bytes)
        assertEquals(setOf("manifest.json"), zip.keys)
        val manifest = BackupManifest.decode(zip.getValue("manifest.json"))
        assertTrue(manifest.entries.isEmpty())
        assertFalse(manifest.ownSounds, "asked for own sounds, but there were none: the flag says what is in it")
        // And this build's reader takes it.
        BackupReader(BackupTestKit.phone(folder.root, "other")).stage(ByteArrayInputStream(bytes))
    }

    @Test fun `the writer keeps to the reader's caps and says which sounds it left out`() {
        val limits = BackupReader.Limits(maxOwnSoundBytes = 4096, maxTotalBytes = 12_000)
        val old = BackupTestKit.phone(folder.root, "old")
        val atCap = "aaaaaaaa-0000-0000-0000-000000000001"
        val overCap = "aaaaaaaa-0000-0000-0000-000000000002"
        val fits = "aaaaaaaa-0000-0000-0000-000000000003"
        val pastTotal = "aaaaaaaa-0000-0000-0000-000000000004"
        BackupTestKit.writeStore(
            old, "sleep",
            preferencesOf(PLAN to "{}", CUSTOM to listOf(atCap, overCap, fits, pastTotal).joinToString(",", "[", "]") { """{"id":"custom:$it","name":"Sound $it"}""" }),
        )
        old.ownSoundsDir.mkdirs()
        File(old.ownSoundsDir, atCap).writeBytes(ByteArray(4096) { 1 })
        File(old.ownSoundsDir, overCap).writeBytes(ByteArray(4097) { 2 })
        File(old.ownSoundsDir, fits).writeBytes(ByteArray(4000) { 3 })
        File(old.ownSoundsDir, pastTotal).writeBytes(ByteArray(4000) { 4 })

        val out = java.io.ByteArrayOutputStream()
        val result = BackupWriter(old, limits = limits).write(out, includeOwnSounds = true, app = BackupTestKit.APP, now = BackupTestKit.NOW)
        assertEquals(listOf(overCap, pastTotal), result.leftOut.map { it.fileName })
        assertEquals(listOf("Sound $overCap", "Sound $pastTotal"), result.leftOut.map { it.displayName })
        assertTrue(result.manifest.ownSounds)
        val zip = BackupTestKit.unzip(out.toByteArray())
        assertEquals(setOf("files/sleep_custom/$atCap", "files/sleep_custom/$fits"), zip.keys.filter { it.startsWith("files/") }.toSet())
        // The list travels with the sounds that made it, and only those.
        val list = PreferencesFiles.decode(zip.getValue("datastore/sleep.preferences_pb"))[CUSTOM]!!
        assertEquals(listOf("custom:$atCap", "custom:$fits"), OwnSoundList.ids(list))

        // A sound exactly at the cap goes through the reader with the same caps.
        val new = BackupTestKit.phone(folder.root, "new")
        BackupReader(new, limits).stage(ByteArrayInputStream(out.toByteArray()))
        BackupTestKit.confirm(new)
        assertEquals(BackupApplier.Outcome.APPLIED, BackupApplier(new).applyIfReady())
        assertEquals(setOf(atCap, fits), new.ownSoundsDir.list()!!.toSet())
    }

    @Test fun `when no own sound fits, neither the flag nor the list travels`() {
        val limits = BackupReader.Limits(maxOwnSoundBytes = 100)
        val old = oldPhone() // sounds of 3000 and 10 bytes
        File(old.ownSoundsDir, soundB).writeBytes(ByteArray(200))
        val out = java.io.ByteArrayOutputStream()
        val result = BackupWriter(old, limits = limits).write(out, includeOwnSounds = true, app = BackupTestKit.APP, now = BackupTestKit.NOW)
        assertEquals(2, result.leftOut.size)
        assertFalse(result.manifest.ownSounds)
        val zip = BackupTestKit.unzip(out.toByteArray())
        assertFalse(zip.keys.any { it.startsWith("files/") })
        assertNull(PreferencesFiles.decode(zip.getValue("datastore/sleep.preferences_pb"))[CUSTOM])
        BackupReader(BackupTestKit.phone(folder.root, "new"), limits).stage(ByteArrayInputStream(out.toByteArray()))
    }

    @Test fun `own sounds info counts only own sound files`() {
        val old = oldPhone()
        File(old.ownSoundsDir, "not.a.sound").writeText("x")
        val info = BackupWriter(old).ownSounds()
        assertEquals(2, info.count)
        assertEquals(3010L, info.bytes)
        assertTrue(info.includeByDefault)
        assertFalse(OwnSoundsInfo(1, OwnSoundsInfo.DEFAULT_INCLUDE_LIMIT).includeByDefault)
        assertFalse(OwnSoundsInfo(0, 0).includeByDefault)
    }
}
