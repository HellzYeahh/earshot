package dev.earshot.backup

import dev.earshot.backup.BackupTestKit.ALARMS
import dev.earshot.backup.BackupTestKit.CUSTOM
import dev.earshot.backup.BackupTestKit.MIXES
import dev.earshot.backup.BackupTestKit.NIGHT
import dev.earshot.backup.BackupTestKit.PLAN
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Sleep tab's night in progress (`night`: the active night, restore, ring and snooze records)
 * belongs to the phone it ran on. Exported, the new phone would "recover" a night it never had
 * and put back settings it never changed; so it is stripped on export, and dropped again on
 * import should a backup carry one anyway.
 */
class SleepTransientStateTest {

    @get:Rule val folder = TemporaryFolder()

    private val night = """{"active":{"startedAtMillis":1},"restore":{"musicVolume":3},"ringing":{"alarmId":"a1","startedAtMillis":2,"alarmAtMillis":3}}"""

    @Test fun `export strips the night and keeps the plan, alarms and mixes`() {
        val phone = BackupTestKit.phone(folder.root, "old")
        BackupTestKit.writeStore(phone, "sleep", BackupTestKit.sleepPrefs(night = night, custom = "[]"))
        val zip = BackupTestKit.unzip(BackupTestKit.export(phone))
        val sleep = PreferencesFiles.decode(zip.getValue("datastore/sleep.preferences_pb"))
        assertNull(sleep[NIGHT])
        assertNotNull(sleep[PLAN])
        assertNotNull(sleep[ALARMS])
        assertNotNull(sleep[MIXES])
        // No own sound files on this phone: the (empty) list doesn't travel without them.
        assertNull(sleep[CUSTOM])
        // Stripped at the source, not only on the way out: the old phone's own night is untouched.
        assertEquals(night, BackupTestKit.readStore(phone, "sleep")!![NIGHT])
    }

    @Test fun `a night inside a backup is never applied`() {
        // A hand-made (or future) backup that carries a night anyway.
        val withNight = PreferencesFiles.encode(BackupTestKit.sleepPrefs(night = night, custom = null))
        val target = BackupTestKit.phone(folder.root, "new")
        BackupTestKit.stageAndConfirm(target, BackupTestKit.backupOf(listOf("datastore/sleep.preferences_pb" to withNight)))
        assertEquals(BackupApplier.Outcome.APPLIED, BackupApplier(target).applyIfReady())
        val sleep = BackupTestKit.readStore(target, "sleep")!!
        assertNull(sleep[NIGHT])
        assertNotNull(sleep[PLAN])
    }

    @Test fun `a night running on the target phone carries on through a restore`() {
        val target = BackupTestKit.phone(folder.root, "new")
        val mine = """{"active":{"startedAtMillis":500}}"""
        BackupTestKit.writeStore(target, "sleep", BackupTestKit.sleepPrefs(night = mine, custom = null))
        val incoming = PreferencesFiles.encode(BackupTestKit.sleepPrefs(night = night, custom = null))
        BackupTestKit.stageAndConfirm(target, BackupTestKit.backupOf(listOf("datastore/sleep.preferences_pb" to incoming)))
        assertEquals(BackupApplier.Outcome.APPLIED, BackupApplier(target).applyIfReady())
        assertEquals(mine, BackupTestKit.readStore(target, "sleep")!![NIGHT])
    }

    @Test fun `a running night's own sounds are never deleted by a restore with sounds`() {
        val target = BackupTestKit.phone(folder.root, "new")
        val playing = "cccccccc-0000-0000-0000-000000000001"
        val mine = """{"active":{"plan":{"layers":[{"soundId":"custom:$playing"}]},"startedAtMillis":500}}"""
        BackupTestKit.writeStore(target, "sleep", BackupTestKit.sleepPrefs(night = mine, custom = """[{"id":"custom:$playing"}]"""))
        target.ownSoundsDir.mkdirs()
        File(target.ownSoundsDir, playing).writeBytes(ByteArray(64) { 9 })
        val other = "dddddddd-0000-0000-0000-000000000002"
        val incoming = PreferencesFiles.encode(BackupTestKit.sleepPrefs(night = null, custom = """[{"id":"custom:$other"}]"""))
        BackupTestKit.stageAndConfirm(
            target,
            BackupTestKit.backupOf(listOf("datastore/sleep.preferences_pb" to incoming, "files/sleep_custom/$other" to ByteArray(32) { 1 })),
        )
        assertEquals(BackupApplier.Outcome.APPLIED, BackupApplier(target).applyIfReady())
        assertTrue(File(target.ownSoundsDir, playing).isFile, "the night goes on with its sound")
        assertTrue(File(target.ownSoundsDir, other).isFile)
        val sleep = BackupTestKit.readStore(target, "sleep")!!
        assertEquals(mine, sleep[NIGHT])
        assertEquals("""[{"id":"custom:$playing"},{"id":"custom:$other"}]""", sleep[CUSTOM])
    }

    @Test fun `a backup without a sleep store still keeps the target's night`() {
        val target = BackupTestKit.phone(folder.root, "new")
        val mine = """{"active":{"startedAtMillis":500}}"""
        BackupTestKit.writeStore(target, "sleep", BackupTestKit.sleepPrefs(night = mine, custom = null))
        BackupTestKit.stageAndConfirm(target, BackupTestKit.backupOf(emptyList()))
        assertEquals(BackupApplier.Outcome.APPLIED, BackupApplier(target).applyIfReady())
        val sleep = BackupTestKit.readStore(target, "sleep")!!
        assertEquals(mine, sleep[NIGHT])
        assertNull(sleep[PLAN], "the old phone had no plan: back to defaults")
    }
}
