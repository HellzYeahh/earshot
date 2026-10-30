package dev.earshot.backup

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The save picker may hand back an existing backup to overwrite: a failed export must never cost
 * the user that file, and the empty document a failed export created goes again.
 */
class DocumentExportTest {

    @get:Rule val folder = TemporaryFolder()

    private val temp by lazy { File(folder.root, "cache/backup-export") }

    /** A document as a provider shows it. [content] null = deleted; [reportsSize] false = the provider doesn't say. */
    private class FakeDocument(var content: ByteArray?, val reportsSize: Boolean = true, val failAfterBytes: Int? = null) : ExportTarget {
        var opened = 0

        override fun size(): Long? = if (reportsSize) content?.size?.toLong() else null

        override fun openForWrite(): OutputStream {
            opened++
            content = ByteArray(0) // "wt": truncated the moment it is opened
            return object : OutputStream() {
                private val buffer = ByteArrayOutputStream()
                override fun write(b: Int) {
                    if (failAfterBytes != null && buffer.size() >= failAfterBytes) throw IOException("provider gave up")
                    buffer.write(b)
                    content = buffer.toByteArray()
                }
            }
        }

        override fun delete() {
            content = null
        }
    }

    private val zipBytes = ByteArray(5000) { (it % 7).toByte() }
    private val result = ExportResult(BackupManifest.decode(BackupTestKit.manifestFor(emptyList())))

    private val building: (OutputStream) -> ExportResult = { out ->
        out.write(zipBytes)
        result
    }

    private fun failingBuild(error: Exception): (OutputStream) -> ExportResult = { out ->
        out.write(zipBytes, 0, 100)
        throw error
    }

    @Test fun `a good export lands in the document and leaves no temp file`() {
        val doc = FakeDocument(content = ByteArray(0))
        assertEquals(result, DocumentExport.export(doc, temp, build = building))
        assertContentEquals(zipBytes, doc.content)
        assertTrue(temp.listFiles().orEmpty().isEmpty())
    }

    @Test fun `overwriting an older backup replaces it whole`() {
        val doc = FakeDocument(content = "last week's backup".toByteArray())
        DocumentExport.export(doc, temp, build = building)
        assertContentEquals(zipBytes, doc.content)
    }

    @Test fun `a failed build never opens an existing backup`() {
        val old = "last week's backup".toByteArray()
        val doc = FakeDocument(content = old.copyOf())
        val e = assertFailsWith<BackupException> {
            DocumentExport.export(doc, temp, build = failingBuild(BackupException(BackupError.CHANGED_DURING_EXPORT, "a sound changed")))
        }
        assertEquals(BackupError.CHANGED_DURING_EXPORT, e.error)
        assertEquals(0, doc.opened, "never truncated")
        assertContentEquals(old, doc.content, "the older backup is intact")
        assertTrue(temp.listFiles().orEmpty().isEmpty())
    }

    @Test fun `a failed build removes the empty document the picker created`() {
        val doc = FakeDocument(content = ByteArray(0))
        val e = assertFailsWith<BackupException> { DocumentExport.export(doc, temp, build = failingBuild(IOException("No space left on device"))) }
        assertEquals(BackupError.NO_SPACE, e.error)
        assertNull(doc.content)
    }

    @Test fun `a failed build keeps a document whose size the provider doesn't report`() {
        val doc = FakeDocument(content = "something".toByteArray(), reportsSize = false)
        val e = assertFailsWith<BackupException> { DocumentExport.export(doc, temp, build = failingBuild(IllegalStateException("bug"))) }
        assertEquals(BackupError.WRITE_FAILED, e.error)
        assertContentEquals("something".toByteArray(), doc.content)
    }

    @Test fun `a failed copy into a new document removes it`() {
        val doc = FakeDocument(content = ByteArray(0), failAfterBytes = 1000)
        val e = assertFailsWith<BackupException> { DocumentExport.export(doc, temp, build = building) }
        assertEquals(BackupError.WRITE_FAILED, e.error)
        assertNull(doc.content, "half a backup in a file the user didn't have before: removed")
        assertTrue(temp.listFiles().orEmpty().isEmpty())
    }

    @Test fun `a failed copy over an existing backup is never deleted, and says so`() {
        val doc = FakeDocument(content = "last week's backup".toByteArray(), failAfterBytes = 1000)
        val e = assertFailsWith<BackupException> { DocumentExport.export(doc, temp, build = building) }
        assertEquals(BackupError.OVERWRITE_FAILED, e.error)
        assertFalse(doc.content == null, "not deleted")
        // Unknown size: treated as a file that had content.
        val unknown = FakeDocument(content = ByteArray(0), reportsSize = false, failAfterBytes = 1000)
        assertEquals(BackupError.OVERWRITE_FAILED, assertFailsWith<BackupException> { DocumentExport.export(unknown, temp, build = building) }.error)
        assertFalse(unknown.content == null)
    }

    /** A build that succeeds, then Android clears the cache (storage pressure) before the copy begins. */
    private val buildThenCacheCleared: (OutputStream) -> ExportResult = { out ->
        out.write(zipBytes)
        // Closed first, so the delete also works on Windows, where an open file can't be removed.
        out.close()
        temp.listFiles().orEmpty().forEach { check(it.delete()) { "the test couldn't remove the temp file" } }
        result
    }

    @Test fun `a built zip that vanished before the copy never opens an existing backup`() {
        val old = "last week's backup".toByteArray()
        val doc = FakeDocument(content = old.copyOf())
        val e = assertFailsWith<BackupException> { DocumentExport.export(doc, temp, build = buildThenCacheCleared) }
        assertEquals(BackupError.WRITE_FAILED, e.error)
        assertEquals(0, doc.opened, "never opened, so never truncated")
        assertContentEquals(old, doc.content, "the older backup is intact")
        // The empty document the picker just created goes again, as after a failed build.
        val fresh = FakeDocument(content = ByteArray(0))
        assertFailsWith<BackupException> { DocumentExport.export(fresh, temp, build = buildThenCacheCleared) }
        assertEquals(0, fresh.opened)
        assertNull(fresh.content)
    }

    @Test fun `leftovers of an export the process didn't survive are swept, recent ones kept`() {
        temp.mkdirs()
        val old = File(temp, "old.zip").apply { writeText("x"); setLastModified(System.currentTimeMillis() - DocumentExport.STALE_TEMP_MILLIS - 60_000) }
        val recent = File(temp, "recent.zip").apply { writeText("x") }
        DocumentExport.export(FakeDocument(ByteArray(0)), temp, build = building)
        assertFalse(old.exists())
        assertTrue(recent.exists(), "maybe another export still writing it")
    }
}
