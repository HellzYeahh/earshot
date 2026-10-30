package dev.earshot.backup

import androidx.datastore.preferences.core.preferencesOf
import dev.earshot.backup.BackupTestKit.BOOST
import dev.earshot.backup.BackupTestKit.PLAN
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A checked import is armed only by the user's tap, for a few minutes, and never by anything else. */
class StagingControlTest {

    @get:Rule val folder = TemporaryFolder()

    private val phone by lazy {
        BackupTestKit.phone(folder.root, "phone").also { BackupTestKit.writeStore(it, "call_audio", preferencesOf(BOOST to 40)) }
    }

    private fun stage() {
        val sleep = PreferencesFiles.encode(preferencesOf(PLAN to "{\"restored\":true}"))
        BackupReader(phone).stage(ByteArrayInputStream(BackupTestKit.backupOf(listOf("datastore/sleep.preferences_pb" to sleep))))
    }

    @Test fun `nothing to confirm is refused`() {
        assertFalse(StagingControl.confirm(phone, 1L))
        assertNull(StagingControl.pending(phone, 1L))
    }

    @Test fun `a confirmation holds for a few minutes, with a little slack for the clock`() {
        val tap = 1_000_000_000L
        assertTrue(StagingControl.isFreshConfirmation(tap, tap))
        assertTrue(StagingControl.isFreshConfirmation(tap, tap + StagingControl.CONFIRM_VALID_MILLIS))
        assertFalse(StagingControl.isFreshConfirmation(tap, tap + StagingControl.CONFIRM_VALID_MILLIS + 1))
        // A network time fix may set the clock back a little between the tap and the start.
        assertTrue(StagingControl.isFreshConfirmation(tap, tap - 30_000))
        assertFalse(StagingControl.isFreshConfirmation(tap, tap - 24L * 60 * 60 * 1000), "a mark from the future is no fresh tap")
        assertFalse(StagingControl.isFreshConfirmation(Long.MIN_VALUE, tap))
    }

    @Test fun `an unreadable confirmation counts as long ago`() {
        stage()
        File(phone.stagingDir, AppStorage.CONFIRMED).writeText("not a time")
        assertEquals(Long.MIN_VALUE, StagingControl.confirmedAt(phone))
        assertEquals(BackupApplier.Outcome.DISCARDED, BackupApplier(phone).applyIfReady())
        assertEquals(40, BackupTestKit.readStore(phone, "call_audio")!![BOOST], "nothing applied")
        assertNotNull(BackupApplier.readFailedNotice(phone), "the user tapped Restart now: told it didn't happen")
    }

    @Test fun `a staging under commit is neither offered, confirmed nor cancelled`() {
        stage()
        File(phone.stagingDir, AppStorage.COMMITTING).writeText("{}")
        assertNull(StagingControl.pending(phone, System.currentTimeMillis()))
        assertFalse(StagingControl.confirm(phone, System.currentTimeMillis()))
        assertFalse(StagingControl.cancel(phone))
        assertTrue(phone.stagingDir.exists())
    }

    @Test fun `a new import isn't staged while a commit waits to be finished`() {
        stage()
        File(phone.stagingDir, AppStorage.COMMITTED).writeText("")
        val sleep = PreferencesFiles.encode(preferencesOf(PLAN to "{}"))
        val e = kotlin.test.assertFailsWith<BackupException> {
            BackupReader(phone).stage(ByteArrayInputStream(BackupTestKit.backupOf(listOf("datastore/sleep.preferences_pb" to sleep))))
        }
        assertEquals(BackupError.RESTORE_UNFINISHED, e.error, "only a restart clears it, not a moment's wait")
        assertTrue(File(phone.stagingDir, AppStorage.COMMITTED).exists(), "left for the next start to finish")
    }

    @Test fun `the last resort after a crash drops a staging that never began its commit`() {
        stage()
        BackupTestKit.confirm(phone)
        BackupApplier(phone).abandonAfterCrash()
        assertFalse(phone.stagingDir.exists())
        assertEquals(true, BackupApplier.readFailedNotice(phone)?.previousSettingsBack)
        assertEquals(40, BackupTestKit.readStore(phone, "call_audio")!![BOOST])
    }

    @Test fun `the last resort leaves a commit under way for the next start, and asks for it`() {
        stage()
        File(phone.stagingDir, AppStorage.COMMITTING).writeText("{}")
        BackupApplier(phone).abandonAfterCrash()
        assertTrue(File(phone.stagingDir, AppStorage.COMMITTING).exists())
        // This process carries on with whatever the commit had swapped: the user is told a restart finishes it.
        val notice = assertNotNull(BackupApplier.readFailedNotice(phone))
        assertFalse(notice.previousSettingsBack)
        assertTrue(notice.finishAtNextStart)
    }

    @Test fun `the last resort is quiet about a commit that went through`() {
        stage()
        File(phone.stagingDir, AppStorage.COMMITTED).writeText("")
        BackupApplier(phone).abandonAfterCrash()
        assertTrue(File(phone.stagingDir, AppStorage.COMMITTED).exists(), "the next start cleans up")
        assertNull(BackupApplier.readFailedNotice(phone), "every live file is the restored one")
    }

    @Test fun `a commit cut short is told even without a notice, and a resolved one stops asking for a restart`() {
        stage()
        assertNull(StagingControl.failedNotice(phone, null, 5L))
        // A process that died mid-commit and came back locked skipped the restore at start.
        File(phone.stagingDir, AppStorage.COMMITTING).writeText("{}")
        val cut = assertNotNull(StagingControl.failedNotice(phone, null, 5L))
        assertFalse(cut.previousSettingsBack)
        assertTrue(cut.finishAtNextStart)
        File(phone.stagingDir, AppStorage.COMMITTED).writeText("")
        assertNull(StagingControl.failedNotice(phone, null, 5L), "every live file is the restored one")
        // A start finished or undid it: the restart offer goes, the notice stays.
        BackupFiles.deleteRecursively(phone.stagingDir)
        val stale = RestoreFailedNotice(1L, previousSettingsBack = false, finishAtNextStart = true)
        assertEquals(stale.copy(finishAtNextStart = false), StagingControl.failedNotice(phone, stale, 5L))
        val plain = RestoreFailedNotice(1L)
        assertEquals(plain, StagingControl.failedNotice(phone, plain, 5L))
    }

    @Test fun `an expired import is left alone while another import holds the lock`() {
        stage()
        val later = System.currentTimeMillis() + StagingControl.UNCONFIRMED_KEEP_MILLIS + 60_000
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread {
            BackupLock.lock.lock()
            try {
                held.countDown()
                release.await()
            } finally {
                BackupLock.lock.unlock()
            }
        }
        try {
            held.await()
            assertNull(StagingControl.pending(phone, later))
            assertTrue(phone.stagingDir.exists(), "an import under way may be about to replace it: left for a later call")
        } finally {
            release.countDown()
            holder.join()
        }
        assertNull(StagingControl.pending(phone, later))
        assertFalse(phone.stagingDir.exists(), "deleted once the lock is free")
    }

    @Test fun `the last resort is quiet about an import nobody confirmed`() {
        stage()
        BackupApplier(phone).abandonAfterCrash()
        assertFalse(phone.stagingDir.exists())
        assertNull(BackupApplier.readFailedNotice(phone))
    }

    @Test fun `alarm ids to cancel add up until the Sleep tab takes them`() {
        StagingControl.recordAlarmIdsToCancel(phone, listOf("a", "b"))
        StagingControl.recordAlarmIdsToCancel(phone, listOf("b", "c", "", "bad\nid"))
        assertEquals(listOf("a", "b", "c"), StagingControl.takeAlarmIdsToCancel(phone))
        assertEquals(emptyList<String>(), StagingControl.takeAlarmIdsToCancel(phone))
        StagingControl.recordAlarmIdsToCancel(phone, emptyList())
        assertFalse(phone.cancelAlarmIdsFile.exists())
    }

    @Test fun `the failed notice is read once and cleared`() {
        stage()
        BackupTestKit.confirm(phone, at = 0L)
        assertEquals(BackupApplier.Outcome.DISCARDED, BackupApplier(phone, now = { StagingControl.CONFIRM_VALID_MILLIS * 2 }).applyIfReady())
        val notice = assertNotNull(BackupApplier.readFailedNotice(phone))
        assertEquals(BackupTestKit.NOW.toString(), notice.backupCreated)
        BackupApplier.clearFailedNotice(phone)
        assertNull(BackupApplier.readFailedNotice(phone))
    }
}
