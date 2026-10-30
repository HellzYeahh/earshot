package dev.earshot.backup

import androidx.datastore.preferences.core.preferencesOf
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Settings restored" is said once per restore. A staging whose clean-up keeps failing leaves
 * `COMMITTED` behind, so every later start finishes the commit again; those starts restored
 * nothing and must not bring back a notice the user already dismissed.
 */
class BackupApplierNoticeTest {

    @get:Rule val folder = TemporaryFolder()

    /** A process death, not an exception the applier could catch. */
    private class Crash : Error("simulated process death")

    /**
     * A phone whose restore has every live file swapped and `COMMITTED` holding [committed]:
     * what a commit leaves before its first finish (empty), or after a finish that wrote the
     * notice but couldn't remove the staging ([BackupApplier.NOTICE_WRITTEN]).
     */
    private fun committedPhone(name: String, committed: String): AppStorage {
        val s = BackupTestKit.phone(folder.root, name)
        val sleep = PreferencesFiles.encode(preferencesOf(BackupTestKit.PLAN to "{\"restored\":true}"))
        BackupTestKit.stageAndConfirm(s, BackupTestKit.backupOf(listOf("datastore/sleep.preferences_pb" to sleep)))
        // Dying right before COMMITTED is written leaves every live file swapped.
        runCatching { BackupApplier(s, onCommitStep = { if (it == "committed") throw Crash() }).applyIfReady() }
            .onSuccess { error("the crash didn't happen") }
        assertTrue(File(s.stagingDir, AppStorage.READY).isFile, "the manifest is still there")
        File(s.stagingDir, AppStorage.COMMITTED).writeText(committed)
        return s
    }

    @Test fun `the first finish of a commit writes the notice`() {
        val s = committedPhone("first", committed = "")
        assertEquals(BackupApplier.Outcome.APPLIED, BackupApplier(s, now = { 42L }).applyIfReady())
        val notice = assertNotNull(BackupApplier.readNotice(s))
        assertEquals(42L, notice.restoredAtMillis)
        assertEquals(BackupTestKit.NOW.toString(), notice.backupCreated, "the backup's date, from the manifest")
        assertFalse(s.stagingDir.exists())
    }

    /**
     * End to end: the mark is written by the first finish itself, not set up by hand. Its
     * clean-up dies, so `COMMITTED` survives and the next start finishes again; that start must
     * find the mark and leave the dismissed notice dismissed.
     */
    @Test fun `a first finish whose clean-up fails marks COMMITTED, so the next start stays quiet`() {
        val s = committedPhone("e2e", committed = "")
        runCatching { BackupApplier(s, now = { 42L }, onCleanUp = { throw Crash() }).applyIfReady() }
            .onSuccess { error("the clean-up didn't fail") }
        assertEquals(BackupApplier.NOTICE_WRITTEN, File(s.stagingDir, AppStorage.COMMITTED).readText(), "the first finish marked its commit")
        assertEquals(42L, assertNotNull(BackupApplier.readNotice(s), "the first finish told the user").restoredAtMillis)

        BackupApplier.clearNotice(s)
        assertEquals(BackupApplier.Outcome.APPLIED, BackupApplier(s, now = { 99L }).applyIfReady())
        assertNull(BackupApplier.readNotice(s), "the dismissed notice stays dismissed")
        assertFalse(s.stagingDir.exists(), "the clean-up is finished")
    }

    @Test fun `a later finish doesn't bring back a dismissed notice`() {
        val s = committedPhone("dismissed", committed = BackupApplier.NOTICE_WRITTEN)
        assertNull(BackupApplier.readNotice(s), "the user dismissed it")
        assertEquals(BackupApplier.Outcome.APPLIED, BackupApplier(s, now = { 99L }).applyIfReady())
        assertNull(BackupApplier.readNotice(s), "nothing new was restored at this start")
        assertFalse(s.stagingDir.exists(), "the clean-up is finished")
    }

    @Test fun `a later finish leaves a notice still showing as it was`() {
        val s = committedPhone("showing", committed = BackupApplier.NOTICE_WRITTEN)
        val shown = RestoredNotice(restoredAtMillis = 7L, backupCreated = BackupTestKit.NOW.toString(), fromVersion = "0.9.0")
        s.noticeFile.parentFile?.mkdirs()
        s.noticeFile.writeText(BackupApplier.json.encodeToString(RestoredNotice.serializer(), shown))
        assertEquals(BackupApplier.Outcome.APPLIED, BackupApplier(s, now = { 99L }).applyIfReady())
        assertEquals(shown, BackupApplier.readNotice(s))
    }

    @Test fun `a cut-short mark reads as none, so the notice is never lost`() {
        val s = committedPhone("cut-short", committed = BackupApplier.NOTICE_WRITTEN.take(3))
        assertEquals(BackupApplier.Outcome.APPLIED, BackupApplier(s, now = { 5L }).applyIfReady())
        assertEquals(5L, BackupApplier.readNotice(s)?.restoredAtMillis)
    }
}
