package dev.earshot.backup

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.earshot.backup.BackupTestKit.ALARMS
import dev.earshot.backup.BackupTestKit.APPEARANCE
import dev.earshot.backup.BackupTestKit.BOOST
import dev.earshot.backup.BackupTestKit.CUSTOM
import dev.earshot.backup.BackupTestKit.PLAN
import dev.earshot.backup.BackupTestKit.RULES
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The restore at start: only a staging the user just confirmed is applied; a bad or failed one
 * never crashes, never half-applies, and always tells the user.
 */
class BackupApplierTest {

    @get:Rule val folder = TemporaryFolder()

    private val phone by lazy {
        BackupTestKit.phone(folder.root, "phone").also {
            BackupTestKit.writeStore(it, "call_audio", preferencesOf(BOOST to 40))
        }
    }
    private val sleepBytes by lazy { PreferencesFiles.encode(preferencesOf(PLAN to "{\"restored\":true}")) }
    private val sound = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    private fun stageGood(withSound: Boolean = false, confirm: Boolean = true) {
        val files = buildList {
            add("datastore/sleep.preferences_pb" to sleepBytes)
            if (withSound) add("files/sleep_custom/$sound" to ByteArray(2048) { 3 })
        }
        BackupReader(phone).stage(ByteArrayInputStream(BackupTestKit.backupOf(files)))
        if (confirm) BackupTestKit.confirm(phone)
    }

    private fun untouched() {
        assertEquals(40, BackupTestKit.readStore(phone, "call_audio")!![BOOST], "the live settings are as they were")
        assertNull(BackupTestKit.readStore(phone, "sleep"))
        assertFalse(phone.dataStoreDir.listFiles()!!.any { it.name.endsWith(BackupApplier.RESTORE_SUFFIX) || it.name.endsWith(BackupApplier.PRERESTORE_SUFFIX) })
        assertNull(BackupApplier.readNotice(phone))
    }

    /** Untouched, the staging gone, and the user told that the restore they confirmed didn't happen. */
    private fun discardedWithNotice() {
        untouched()
        assertFalse(phone.stagingDir.exists())
        assertEquals(true, BackupApplier.readFailedNotice(phone)?.previousSettingsBack)
    }

    @Test fun `nothing staged is the quiet case`() {
        phone
        assertEquals(BackupApplier.Outcome.NOTHING, BackupApplier(phone).applyIfReady())
    }

    @Test fun `an import that was still unpacking is removed`() {
        File(phone.stagingTmpDir, "datastore").mkdirs()
        File(phone.stagingTmpDir, "datastore/sleep.preferences_pb").writeBytes(sleepBytes)
        assertEquals(BackupApplier.Outcome.NOTHING, BackupApplier(phone).applyIfReady())
        assertFalse(phone.stagingTmpDir.exists())
        untouched()
    }

    @Test fun `a checked import the user never confirmed is not applied, and waits for the screen`() {
        stageGood(confirm = false)
        // Any start: an alarm's, a Bluetooth broadcast's, the launcher's.
        assertEquals(BackupApplier.Outcome.PENDING, BackupApplier(phone).applyIfReady())
        assertEquals(BackupApplier.Outcome.PENDING, BackupApplier(phone).applyIfReady())
        untouched()
        assertNotNull(StagingControl.pending(phone, System.currentTimeMillis()), "offered again on the Backup screen")
        assertNull(BackupApplier.readFailedNotice(phone))
        // The tap arms it.
        BackupTestKit.confirm(phone)
        assertEquals(BackupApplier.Outcome.APPLIED, BackupApplier(phone).applyIfReady())
        assertEquals("{\"restored\":true}", BackupTestKit.readStore(phone, "sleep")!![PLAN])
    }

    @Test fun `an unconfirmed import is dropped after a day, quietly`() {
        stageGood(confirm = false)
        val later = System.currentTimeMillis() + StagingControl.UNCONFIRMED_KEEP_MILLIS + 60_000
        // A start a day later drops it.
        assertEquals(BackupApplier.Outcome.DISCARDED, BackupApplier(phone, now = { later }).applyIfReady())
        untouched()
        assertFalse(phone.stagingDir.exists())
        assertNull(BackupApplier.readFailedNotice(phone), "never confirmed: nothing to apologise for")

        // So does the Backup screen, while the process lives on (the service can keep it alive for
        // days): deleted, not just hidden, so staged sounds never sit there with no way to discard them.
        stageGood(withSound = true, confirm = false)
        assertNotNull(StagingControl.pending(phone, System.currentTimeMillis()), "still offered within the day")
        assertNull(StagingControl.pending(phone, later))
        assertFalse(phone.stagingDir.exists(), "the expired staging is gone")
        untouched()
        assertNull(BackupApplier.readFailedNotice(phone))
        assertEquals(BackupApplier.Outcome.NOTHING, BackupApplier(phone, now = { later }).applyIfReady())
    }

    @Test fun `a confirmation from long ago is dropped with a notice`() {
        stageGood(confirm = false)
        val tappedAt = System.currentTimeMillis()
        BackupTestKit.confirm(phone, tappedAt)
        // The process lived on and Earshot only started again much later.
        val later = tappedAt + StagingControl.CONFIRM_VALID_MILLIS + 1
        assertEquals(BackupApplier.Outcome.DISCARDED, BackupApplier(phone, now = { later }).applyIfReady())
        discardedWithNotice()
    }

    @Test fun `a staging without its ready marker is discarded`() {
        stageGood()
        File(phone.stagingDir, AppStorage.READY).delete()
        assertEquals(BackupApplier.Outcome.DISCARDED, BackupApplier(phone).applyIfReady())
        untouched()
        assertFalse(phone.stagingDir.exists())
    }

    @Test fun `a corrupt ready marker is discarded`() {
        stageGood()
        File(phone.stagingDir, AppStorage.READY).writeText("{ not json")
        assertEquals(BackupApplier.Outcome.DISCARDED, BackupApplier(phone).applyIfReady())
        discardedWithNotice()
    }

    @Test fun `a staged file changed after the check is discarded before anything moves`() {
        stageGood()
        File(phone.stagingDir, "datastore/sleep.preferences_pb").appendBytes(byteArrayOf(1))
        assertEquals(BackupApplier.Outcome.DISCARDED, BackupApplier(phone).applyIfReady())
        discardedWithNotice()
    }

    @Test fun `staged own sounds that went missing are discarded`() {
        stageGood(withSound = true)
        File(phone.stagingDir, "files/sleep_custom/$sound").delete()
        assertEquals(BackupApplier.Outcome.DISCARDED, BackupApplier(phone).applyIfReady())
        discardedWithNotice()
    }

    @Test fun `a staging that keeps failing is given up after a few starts`() {
        stageGood()
        File(phone.stagingDir, AppStorage.ATTEMPTS).writeText(BackupApplier.MAX_ATTEMPTS.toString())
        assertEquals(BackupApplier.Outcome.DISCARDED, BackupApplier(phone).applyIfReady())
        discardedWithNotice()
    }

    @Test fun `an attempt that can't be counted is not made`() {
        stageGood()
        // Where the counter goes, a folder: the write fails (as on a full disk), so it would never reach the limit.
        File(phone.stagingDir, AppStorage.ATTEMPTS).apply { mkdirs(); File(this, "x").writeText("x") }
        assertEquals(BackupApplier.Outcome.DISCARDED, BackupApplier(phone).applyIfReady())
        discardedWithNotice()
    }

    @Test fun `each start counts its attempt, and a failed one waits for the user again`() {
        stageGood()
        // A folder where the sleep store's restore temp must go: preparing fails with an I/O error.
        val blocker = File(phone.target(BackupTestKit.store("sleep")).path + BackupApplier.RESTORE_SUFFIX)
        blocker.mkdirs()
        File(blocker, "x").writeText("x")
        assertEquals(BackupApplier.Outcome.RETRY_LATER, BackupApplier(phone).applyIfReady())
        assertEquals("1", File(phone.stagingDir, AppStorage.ATTEMPTS).readText())
        assertEquals(40, BackupTestKit.readStore(phone, "call_audio")!![BOOST])
        assertEquals(true, BackupApplier.readFailedNotice(phone)?.previousSettingsBack, "the user is told it didn't finish")
        // Not armed any more: a later start in the background doesn't retry on its own.
        assertNull(StagingControl.confirmedAt(phone))
        blocker.deleteRecursively()
        assertEquals(BackupApplier.Outcome.PENDING, BackupApplier(phone).applyIfReady())
        BackupTestKit.confirm(phone)
        assertEquals(BackupApplier.Outcome.APPLIED, BackupApplier(phone).applyIfReady())
        assertEquals("{\"restored\":true}", BackupTestKit.readStore(phone, "sleep")!![PLAN])
        assertNull(BackupApplier.readFailedNotice(phone), "a restore that got through clears the old failure")
    }

    @Test fun `mistyped and unknown keys never reach a live file`() {
        BackupTestKit.writeStore(phone, "earshot_automation", preferencesOf(RULES to "[{\"id\":\"phone\"}]", booleanPreferencesKey("low_battery_enabled") to true))
        val hostile = PreferencesFiles.encode(
            preferencesOf(
                intPreferencesKey("rules_json") to 7, // read as a String by AutomationRepositoryImpl
                stringPreferencesKey("low_battery_enabled") to "yes", // read as a Boolean
                intPreferencesKey("low_battery_threshold") to 20, // right type: restored
                APPEARANCE to "FIELD",
                stringPreferencesKey("from_a_newer_build") to "x",
            ),
        )
        BackupTestKit.stageAndConfirm(phone, BackupTestKit.backupOf(listOf("datastore/earshot_automation.preferences_pb" to hostile)))
        assertEquals(BackupApplier.Outcome.APPLIED, BackupApplier(phone).applyIfReady())

        val restored = BackupTestKit.readStore(phone, "earshot_automation")!!
        val types = restored.asMap().mapKeys { it.key.name }
        assertEquals("[{\"id\":\"phone\"}]", types["rules_json"], "mistyped: the phone keeps its own value")
        assertEquals(true, types["low_battery_enabled"], "mistyped: the phone keeps its own value")
        assertEquals(20, types["low_battery_threshold"])
        assertEquals("FIELD", types["appearance"])
        assertFalse("from_a_newer_build" in types, "unknown keys are dropped")
        // What the repositories do at start: typed reads that must not throw.
        assertEquals("[{\"id\":\"phone\"}]", restored[RULES])
        assertEquals(true, restored[booleanPreferencesKey("low_battery_enabled")])
        val store = BackupTestKit.store("earshot_automation")
        assertTrue(restored.asMap().all { (key, value) -> store.typeOf(key.name)?.matches(value) == true })
    }

    @Test fun `a mistyped key keeps the phone's value and the rest is restored`() {
        val hostile = PreferencesFiles.encode(preferencesOf(stringPreferencesKey("boost_percent") to "loud", booleanPreferencesKey("boost_enabled") to true))
        BackupTestKit.stageAndConfirm(phone, BackupTestKit.backupOf(listOf("datastore/call_audio.preferences_pb" to hostile)))
        assertEquals(BackupApplier.Outcome.APPLIED, BackupApplier(phone).applyIfReady())
        val call = BackupTestKit.readStore(phone, "call_audio")!!
        assertEquals(40, call[BOOST], "the phone's own value of the mistyped key")
        assertEquals(true, call[booleanPreferencesKey("boost_enabled")])
    }

    @Test fun `own sounds are added, never removed, and the phone's file wins a clash`() {
        val clash = "11111111-1111-1111-1111-111111111111"
        phone.ownSoundsDir.mkdirs()
        File(phone.ownSoundsDir, "mine").writeText("phone's own")
        File(phone.ownSoundsDir, clash).writeText("phone's version")
        BackupTestKit.writeStore(
            phone, "sleep",
            preferencesOf(CUSTOM to """[{"id":"custom:mine","name":"Mine"},{"id":"custom:$clash","name":"Phone name"},{"broken":true}]"""),
        )
        val sleep = PreferencesFiles.encode(
            preferencesOf(
                PLAN to "{}",
                CUSTOM to """[{"id":"custom:$sound","name":"Rain"},{"id":"custom:$clash","name":"Backup name"},{"id":"custom:nofile","name":"Gone"}]""",
            ),
        )
        val files = listOf(
            "datastore/sleep.preferences_pb" to sleep,
            "files/sleep_custom/$sound" to ByteArray(10) { 1 },
            "files/sleep_custom/$clash" to "backup's version".toByteArray(),
        )
        BackupTestKit.stageAndConfirm(phone, BackupTestKit.backupOf(files))
        assertEquals(BackupApplier.Outcome.APPLIED, BackupApplier(phone).applyIfReady())

        assertEquals(setOf("mine", clash, sound), phone.ownSoundsDir.list()!!.toSet())
        assertEquals("phone's own", File(phone.ownSoundsDir, "mine").readText())
        assertEquals("phone's version", File(phone.ownSoundsDir, clash).readText())
        assertContentEquals(ByteArray(10) { 1 }, File(phone.ownSoundsDir, sound).readBytes())
        val list = BackupTestKit.readStore(phone, "sleep")!![CUSTOM]!!
        assertEquals(
            """[{"id":"custom:mine","name":"Mine"},{"id":"custom:$clash","name":"Phone name"},{"broken":true},{"id":"custom:$sound","name":"Rain"}]""",
            list,
            "the phone's list as it was (even what it can't read), then the backup's new sounds that have a file",
        )
    }

    @Test fun `the phone's alarms are handed to the Sleep tab to cancel`() {
        BackupTestKit.writeStore(phone, "sleep", preferencesOf(ALARMS to """[{"id":"phone-1","hour":6,"minute":0},{"id":"phone-2","hour":6,"minute":30}]"""))
        val incoming = PreferencesFiles.encode(preferencesOf(ALARMS to """[{"id":"backup-1","hour":7,"minute":0}]"""))
        BackupTestKit.stageAndConfirm(phone, BackupTestKit.backupOf(listOf("datastore/sleep.preferences_pb" to incoming)))
        assertEquals(BackupApplier.Outcome.APPLIED, BackupApplier(phone).applyIfReady())
        assertEquals(listOf("phone-1", "phone-2"), StagingControl.takeAlarmIdsToCancel(phone))
        assertEquals(emptyList(), StagingControl.takeAlarmIdsToCancel(phone), "handed over once")
    }

    @Test fun `a waiting import can be cancelled, one under way can't`() {
        stageGood(confirm = false)
        assertFalse(StagingControl.cancel(phone, token = "someone else's"), "an abandoned import drops only its own staging")
        assertTrue(phone.stagingDir.exists())
        File(phone.stagingDir, AppStorage.COMMITTING).writeText("{}")
        assertFalse(StagingControl.cancel(phone))
        assertTrue(phone.stagingDir.exists())
        File(phone.stagingDir, AppStorage.COMMITTING).delete()
        assertTrue(StagingControl.cancel(phone))
        assertFalse(phone.stagingDir.exists())
    }

    @Test fun `the notice is read once and cleared`() {
        stageGood(confirm = false)
        BackupTestKit.confirm(phone, at = 40L)
        assertEquals(BackupApplier.Outcome.APPLIED, BackupApplier(phone, now = { 42L }).applyIfReady())
        assertEquals(42L, BackupApplier.readNotice(phone)!!.restoredAtMillis)
        BackupApplier.clearNotice(phone)
        assertNull(BackupApplier.readNotice(phone))
    }
}

/**
 * The commit, cut at every step: a failure is undone (byte for byte), a process death is finished
 * by the next start (byte for byte the same as an uninterrupted restore), and a finish that fails
 * in turn is undone.
 */
class BackupCommitFaultTest {

    @get:Rule val folder = TemporaryFolder()

    private val soundA = "11111111-2222-3333-4444-555555555555"
    private val soundB = "66666666-7777-8888-9999-000000000000"

    /** A process death, not an exception the applier could catch. */
    private class Crash : Error("simulated process death")

    private val backup: ByteArray by lazy {
        val old = BackupTestKit.phone(folder.root, "old")
        BackupTestKit.writeStore(old, "sleep", BackupTestKit.sleepPrefs(night = """{"active":{"startedAtMillis":1}}""", custom = """[{"id":"custom:$soundA"},{"id":"custom:$soundB"}]"""))
        BackupTestKit.writeStore(old, "earshot_automation", preferencesOf(RULES to "[{\"id\":\"r1\"}]", BackupTestKit.LOG to "[\"old\"]", APPEARANCE to "FIELD"))
        BackupTestKit.writeStore(old, "bt_device", preferencesOf(BackupTestKit.ADDRESS to "AA:BB:CC:DD:EE:01", BackupTestKit.CHANNEL to 2))
        File(old.sharedPrefsDir, "whats_new.xml").writeBytes(BackupTestKit.sharedPrefsXml("last_seen_release" to "0.9.1"))
        old.ownSoundsDir.mkdirs()
        File(old.ownSoundsDir, soundA).writeBytes(ByteArray(3000) { it.toByte() })
        File(old.ownSoundsDir, soundB).writeBytes(ByteArray(40) { 7 })
        BackupTestKit.export(old)
    }

    /** A phone with state of its own: a night, an automation log, call settings, a stale .bak, a sound and alarms. */
    private fun phone(name: String): AppStorage = BackupTestKit.phone(folder.root, name).also { s ->
        BackupTestKit.writeStore(
            s, "sleep",
            preferencesOf(
                PLAN to "{\"phone\":true}", ALARMS to """[{"id":"phone-alarm","hour":6,"minute":0}]""",
                BackupTestKit.NIGHT to """{"active":{"startedAtMillis":99}}""", CUSTOM to """[{"id":"custom:mine"}]""",
            ),
        )
        BackupTestKit.writeStore(s, "earshot_automation", preferencesOf(BackupTestKit.LOG to "[\"phone log\"]", APPEARANCE to "DYNAMIC"))
        BackupTestKit.writeStore(s, "call_audio", preferencesOf(BOOST to 40))
        File(s.sharedPrefsDir, "whats_new.xml.bak").writeBytes(BackupTestKit.sharedPrefsXml("last_seen_release" to "0.1.0"))
        s.ownSoundsDir.mkdirs()
        File(s.ownSoundsDir, "mine").writeBytes(ByteArray(5) { 1 })
        BackupTestKit.stageAndConfirm(s, backup)
    }

    /** The labels of every step of an uninterrupted commit, and the phone it leaves. */
    private val reference: Pair<List<String>, Map<String, String>> by lazy {
        val steps = ArrayList<String>()
        val s = phone("reference")
        assertEquals(BackupApplier.Outcome.APPLIED, BackupApplier(s, onCommitStep = { steps += it }).applyIfReady())
        steps to BackupTestKit.snapshot(s)
    }

    private fun failingAt(n: Int, error: () -> Throwable): (String) -> Unit {
        var i = 0
        return { if (i++ == n) throw error() }
    }

    @Test fun `the reference restore keeps the phone's night, log and sounds`() {
        val (steps, after) = reference
        assertTrue(steps.size >= 10, "every store, the preferences file, two sounds: $steps")
        assertTrue("files/sleep_custom/mine" in after && "files/sleep_custom/$soundA" in after && "files/sleep_custom/$soundB" in after)
        assertFalse(after.keys.any { it.endsWith(BackupApplier.PRERESTORE_SUFFIX) || it.endsWith(BackupApplier.RESTORE_SUFFIX) })
        assertFalse("files/datastore/call_audio.preferences_pb" in after, "the old phone had defaults")
        val s = BackupTestKit.phone(folder.root, "reference")
        val sleep = BackupTestKit.readStore(s, "sleep")!!
        assertEquals("""{"active":{"startedAtMillis":99}}""", sleep[BackupTestKit.NIGHT])
        assertEquals("[\"phone log\"]", BackupTestKit.readStore(s, "earshot_automation")!![BackupTestKit.LOG])
    }

    @Test fun `a failure at any step puts the phone back exactly as it was`() {
        for (n in reference.first.indices) {
            val s = phone("fail-$n")
            val before = BackupTestKit.snapshot(s)
            val outcome = BackupApplier(s, onCommitStep = failingAt(n) { IOException("disk said no at ${reference.first[n]}") }).applyIfReady()
            assertEquals(BackupApplier.Outcome.ROLLED_BACK, outcome, "step $n ${reference.first[n]}")
            assertEquals(before, BackupTestKit.snapshot(s), "step $n ${reference.first[n]}: byte for byte as before")
            assertEquals(true, BackupApplier.readFailedNotice(s)?.previousSettingsBack, "step $n: the user is told")
            assertNull(BackupApplier.readNotice(s))
            assertFalse(File(s.stagingDir, AppStorage.COMMITTING).exists())
            // The checked backup waits for the user again; a second try gets through.
            assertNotNull(StagingControl.pending(s, System.currentTimeMillis()))
            BackupTestKit.confirm(s)
            assertEquals(BackupApplier.Outcome.APPLIED, BackupApplier(s).applyIfReady(), "step $n: retried")
            assertEquals(reference.second, BackupTestKit.snapshot(s), "step $n: retried")
        }
    }

    @Test fun `a process death at any step is finished by the next start`() {
        for (n in reference.first.indices) {
            val s = phone("crash-$n")
            runCatching { BackupApplier(s, onCommitStep = failingAt(n) { Crash() }).applyIfReady() }
                .onSuccess { error("step $n: the crash didn't happen") }
                .onFailure { assertTrue(it is Crash, "step $n: $it") }
            assertTrue(File(s.stagingDir, AppStorage.COMMITTING).exists(), "step $n: cut in the middle")
            // Even long after the tap: a commit under way is always finished.
            val later = System.currentTimeMillis() + StagingControl.CONFIRM_VALID_MILLIS * 3
            assertEquals(BackupApplier.Outcome.APPLIED, BackupApplier(s, now = { later }).applyIfReady(), "step $n ${reference.first[n]}")
            assertEquals(reference.second, BackupTestKit.snapshot(s), "step $n ${reference.first[n]}: same as an uninterrupted restore")
            assertFalse(s.stagingDir.exists())
            assertNotNull(BackupApplier.readNotice(s))
        }
    }

    @Test fun `a finish that fails in turn is undone`() {
        for (n in reference.first.indices) {
            val s = phone("crash-then-fail-$n")
            val before = BackupTestKit.snapshot(s)
            runCatching { BackupApplier(s, onCommitStep = failingAt(n) { Crash() }).applyIfReady() }
            val outcome = BackupApplier(s, onCommitStep = failingAt(0) { IOException("no") }).applyIfReady()
            assertEquals(BackupApplier.Outcome.ROLLED_BACK, outcome, "step $n")
            assertEquals(before, BackupTestKit.snapshot(s), "step $n: back as it was")
            assertEquals(true, BackupApplier.readFailedNotice(s)?.previousSettingsBack)
        }
    }

    @Test fun `a death after the last swap, before the commit is marked done, is finished too`() {
        val s = phone("after-commit")
        val steps = reference.first
        assertEquals("committed", steps.last())
        // Dying there leaves every live file swapped and COMMITTED unwritten.
        runCatching { BackupApplier(s, onCommitStep = failingAt(steps.lastIndex) { Crash() }).applyIfReady() }
        assertEquals(BackupApplier.Outcome.APPLIED, BackupApplier(s).applyIfReady())
        assertEquals(reference.second, BackupTestKit.snapshot(s))
        assertEquals(BackupApplier.Outcome.NOTHING, BackupApplier(s).applyIfReady(), "applied once only")
    }

    @Test fun `a start that finds a finished commit half cleaned up only finishes it`() {
        val s = phone("half-cleaned")
        runCatching { BackupApplier(s, onCommitStep = failingAt(reference.first.lastIndex) { Crash() }).applyIfReady() }
        // What a process death while the staging was being removed leaves: every live file swapped,
        // the notice written, the copies, the journal and READY gone, COMMITTED (always removed last) still there.
        File(s.stagingDir, AppStorage.COMMITTED).writeText("")
        val notice = RestoredNotice(restoredAtMillis = 7L, backupCreated = BackupTestKit.NOW.toString(), fromVersion = "0.9.0")
        s.noticeFile.writeText(BackupApplier.json.encodeToString(RestoredNotice.serializer(), notice))
        for (dir in listOf(s.dataStoreDir, s.sharedPrefsDir)) {
            dir.listFiles()!!.filter { it.name.endsWith(BackupApplier.PRERESTORE_SUFFIX) }.forEach { it.delete() }
        }
        File(s.stagingDir, AppStorage.COMMITTING).delete()
        File(s.stagingDir, AppStorage.READY).delete()

        assertEquals(BackupApplier.Outcome.APPLIED, BackupApplier(s).applyIfReady())
        assertEquals(reference.second, BackupTestKit.snapshot(s), "finished, never undone")
        assertFalse(s.stagingDir.exists())
        assertEquals(notice, BackupApplier.readNotice(s), "the notice keeps the backup's date")
    }

    @Test fun `a failure whose undo fails too is told, and the restart it asks for finishes the restore`() {
        for (n in reference.first.indices) {
            val s = phone("undo-fails-$n")
            // A disk error during the commit, then again while every file is being put back.
            val outcome = BackupApplier(
                s,
                onCommitStep = failingAt(n) { IOException("disk said no at ${reference.first[n]}") },
                onUndoStep = { throw IOException("and no again") },
            ).applyIfReady()
            assertEquals(BackupApplier.Outcome.RETRY_LATER, outcome, "step $n ${reference.first[n]}")
            val notice = assertNotNull(BackupApplier.readFailedNotice(s), "step $n: the app runs on a half commit, the user is told")
            assertFalse(notice.previousSettingsBack, "step $n")
            assertTrue(notice.finishAtNextStart, "step $n: a restart finishes it")
            assertEquals(BackupTestKit.NOW.toString(), notice.backupCreated)
            assertTrue(StagingControl.commitBegun(s), "step $n: the commit stays under way")
            assertNull(StagingControl.pending(s, System.currentTimeMillis()), "step $n: nothing to confirm")
            val busy = kotlin.test.assertFailsWith<BackupException> { BackupReader(s).stage(ByteArrayInputStream(backup)) }
            assertEquals(BackupError.RESTORE_UNFINISHED, busy.error, "step $n: a new import waits for the restart")

            // The restart the banner offers: no confirmation, even long after the tap.
            val later = System.currentTimeMillis() + StagingControl.CONFIRM_VALID_MILLIS * 3
            assertEquals(BackupApplier.Outcome.APPLIED, BackupApplier(s, now = { later }).applyIfReady(), "step $n ${reference.first[n]}")
            assertEquals(reference.second, BackupTestKit.snapshot(s), "step $n: same as an uninterrupted restore")
            assertNull(BackupApplier.readFailedNotice(s), "step $n: a restore that got through clears the failure")
            assertNotNull(BackupApplier.readNotice(s))
            assertFalse(s.stagingDir.exists())
        }
    }

    @Test fun `a failure whose undo fails at the last attempt is given up, and says some settings may be mixed`() {
        val s = phone("undo-fails-last")
        File(s.stagingDir, AppStorage.ATTEMPTS).writeText((BackupApplier.MAX_ATTEMPTS - 1).toString())
        val outcome = BackupApplier(s, onCommitStep = failingAt(2) { IOException("no") }, onUndoStep = { throw IOException("no") }).applyIfReady()
        assertEquals(BackupApplier.Outcome.DISCARDED, outcome)
        val notice = assertNotNull(BackupApplier.readFailedNotice(s))
        assertFalse(notice.previousSettingsBack)
        assertFalse(notice.finishAtNextStart, "nothing left under way: restoring from the file again is the way")
        assertFalse(s.stagingDir.exists())
        // And that way is open again.
        BackupTestKit.stageAndConfirm(s, backup)
        assertEquals(BackupApplier.Outcome.APPLIED, BackupApplier(s).applyIfReady())
    }

    @Test fun `a commit that keeps dying is undone after the last attempt`() {
        val s = phone("keeps-dying")
        val before = BackupTestKit.snapshot(s)
        repeat(BackupApplier.MAX_ATTEMPTS) {
            runCatching { BackupApplier(s, onCommitStep = failingAt(2) { Crash() }).applyIfReady() }
        }
        assertEquals(BackupApplier.Outcome.DISCARDED, BackupApplier(s).applyIfReady())
        assertEquals(before, BackupTestKit.snapshot(s))
        assertFalse(s.stagingDir.exists())
        assertEquals(true, BackupApplier.readFailedNotice(s)?.previousSettingsBack)
    }
}
