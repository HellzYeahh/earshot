package dev.earshot.backup

import androidx.datastore.preferences.core.preferencesOf
import dev.earshot.backup.BackupTestKit.BOOST
import dev.earshot.backup.BackupTestKit.PLAN
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Imports that must be refused before anything is staged, let alone applied. */
class BackupReaderTest {

    @get:Rule val folder = TemporaryFolder()

    private val phone by lazy { BackupTestKit.phone(folder.root, "phone") }
    private val sleepBytes by lazy { PreferencesFiles.encode(preferencesOf(PLAN to "{}")) }
    private val callBytes by lazy { PreferencesFiles.encode(preferencesOf(BOOST to 30)) }

    private fun stage(zip: ByteArray, limits: BackupReader.Limits = BackupReader.Limits()) =
        BackupReader(phone, limits).stage(ByteArrayInputStream(zip))

    private fun refused(expected: BackupError, zip: ByteArray, limits: BackupReader.Limits = BackupReader.Limits()) {
        val e = assertFailsWith<BackupException> { stage(zip, limits) }
        assertEquals(expected, e.error, e.message)
        assertFalse(phone.stagingTmpDir.exists(), "the half-unpacked folder is removed")
        assertFalse(phone.stagingDir.exists(), "nothing staged")
    }

    @Test fun `a good backup is staged with its ready marker`() {
        val manifest = stage(BackupTestKit.backupOf(listOf("datastore/sleep.preferences_pb" to sleepBytes)))
        assertEquals(1, manifest.entries.size)
        assertTrue(File(phone.stagingDir, AppStorage.READY).isFile)
        assertTrue(File(phone.stagingDir, "datastore/sleep.preferences_pb").isFile)
        assertFalse(phone.stagingTmpDir.exists())
    }

    @Test fun `zip-slip paths are refused and nothing lands outside`() {
        val evil = "../../evil.txt"
        val files = listOf(evil to "boom".toByteArray())
        refused(BackupError.UNEXPECTED_FILE, BackupTestKit.zipOf(listOf(BackupInventory.MANIFEST to BackupTestKit.manifestFor(emptyList())) + files))
        assertFalse(folder.root.walkTopDown().any { it.name == "evil.txt" })
        refused(BackupError.UNEXPECTED_FILE, BackupTestKit.zipOf(listOf("files/sleep_custom/../../datastore/x" to ByteArray(1))))
    }

    @Test fun `absolute paths are refused`() {
        val absolute = File(folder.root, "outside.txt").absolutePath.replace('\\', '/')
        refused(BackupError.UNEXPECTED_FILE, BackupTestKit.zipOf(listOf(BackupInventory.MANIFEST to BackupTestKit.manifestFor(emptyList()), absolute to ByteArray(1))))
        refused(BackupError.UNEXPECTED_FILE, BackupTestKit.zipOf(listOf("/data/data/dev.earshot/files/datastore/sleep.preferences_pb" to sleepBytes)))
        assertFalse(File(folder.root, "outside.txt").exists())
    }

    @Test fun `files outside the inventory are refused`() {
        for (path in listOf("datastore/demo_mode.preferences_pb", "shared_prefs/bragi_trust.xml", "files/bt_timeline.tsv", "notes.txt")) {
            refused(BackupError.UNEXPECTED_FILE, BackupTestKit.backupOf(listOf(path to ByteArray(4))))
        }
    }

    @Test fun `a newer format is refused before anything is unpacked`() {
        val files = listOf("datastore/sleep.preferences_pb" to sleepBytes)
        // Unpacking past the manifest would stop at notes.txt with UNEXPECTED_FILE instead.
        val zip = BackupTestKit.zipOf(listOf(BackupInventory.MANIFEST to BackupTestKit.manifestFor(files, format = 2), "notes.txt" to ByteArray(1)) + files)
        refused(BackupError.NEWER_FORMAT, zip)
    }

    @Test fun `the manifest must come first`() {
        val files = listOf("datastore/sleep.preferences_pb" to sleepBytes)
        // A re-zipped backup in alphabetical order: datastore/ before manifest.json.
        refused(BackupError.NOT_A_BACKUP, BackupTestKit.zipOf(files + (BackupInventory.MANIFEST to BackupTestKit.manifestFor(files))))
    }

    @Test fun `files from a newer Earshot ask for an update`() {
        val files = listOf("datastore/sleep.preferences_pb" to sleepBytes, "datastore/added_in_1_1.preferences_pb" to callBytes)
        val manifest = BackupManifest.decode(BackupTestKit.manifestFor(files)).copy(versionCode = 11)
        val zip = BackupTestKit.zipOf(listOf(BackupInventory.MANIFEST to BackupManifest.encode(manifest)) + files)
        val e = assertFailsWith<BackupException> { BackupReader(phone, currentVersionCode = 10).stage(ByteArrayInputStream(zip)) }
        assertEquals(BackupError.NEWER_FORMAT, e.error)
        // The same files from this build or an older one: not an update, a file Earshot doesn't restore.
        val same = assertFailsWith<BackupException> { BackupReader(phone, currentVersionCode = 11).stage(ByteArrayInputStream(zip)) }
        assertEquals(BackupError.UNEXPECTED_FILE, same.error)
        // A path that leaves the folder is never "newer".
        val evil = BackupManifest.decode(BackupTestKit.manifestFor(listOf("../evil" to ByteArray(1)))).copy(versionCode = 99)
        val evilZip = BackupTestKit.zipOf(listOf(BackupInventory.MANIFEST to BackupManifest.encode(evil)))
        val escaping = assertFailsWith<BackupException> { BackupReader(phone, currentVersionCode = 10).stage(ByteArrayInputStream(evilZip)) }
        assertEquals(BackupError.UNEXPECTED_FILE, escaping.error)
        assertFalse(phone.stagingTmpDir.exists())
        assertFalse(phone.stagingDir.exists())
    }

    @Test fun `a newer manifest with unknown fields still names its format`() {
        val manifest = """{"format":7,"app":"earshot","somethingNew":{"x":1},"entries":"not a list"}""".toByteArray()
        refused(BackupError.NEWER_FORMAT, BackupTestKit.zipOf(listOf(BackupInventory.MANIFEST to manifest)))
    }

    @Test fun `a checksum mismatch is refused`() {
        val files = listOf("datastore/sleep.preferences_pb" to sleepBytes)
        val manifest = BackupTestKit.manifestFor(files)
        val tampered = sleepBytes.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        refused(BackupError.DAMAGED, BackupTestKit.zipOf(listOf(BackupInventory.MANIFEST to manifest, "datastore/sleep.preferences_pb" to tampered)))
    }

    @Test fun `files and manifest must match both ways`() {
        val listedOnly = BackupTestKit.manifestFor(listOf("datastore/sleep.preferences_pb" to sleepBytes, "datastore/call_audio.preferences_pb" to callBytes))
        refused(BackupError.DAMAGED, BackupTestKit.zipOf(listOf(BackupInventory.MANIFEST to listedOnly, "datastore/sleep.preferences_pb" to sleepBytes)))
        val unlisted = BackupTestKit.manifestFor(listOf("datastore/sleep.preferences_pb" to sleepBytes))
        refused(
            BackupError.DAMAGED,
            BackupTestKit.zipOf(listOf(BackupInventory.MANIFEST to unlisted, "datastore/sleep.preferences_pb" to sleepBytes, "datastore/call_audio.preferences_pb" to callBytes)),
        )
    }

    @Test fun `a settings file that doesn't parse is refused even with a matching checksum`() {
        refused(BackupError.DAMAGED, BackupTestKit.backupOf(listOf("datastore/sleep.preferences_pb" to "not protobuf at all".toByteArray())))
        refused(BackupError.DAMAGED, BackupTestKit.backupOf(listOf("shared_prefs/whats_new.xml" to "{\"json\":true}".toByteArray())))
    }

    @Test fun `a preferences file that declares entities is refused`() {
        // whats_new.xml is restored byte for byte and parsed at every start by a parser that
        // processes a DOCTYPE: nested entities would blow up each start.
        val bomb = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <!DOCTYPE map [
              <!ENTITY a "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa">
              <!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">
              <!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;">
            ]>
            <map>
                <string name="last_seen_release">&c;</string>
            </map>
        """.trimIndent().toByteArray()
        refused(BackupError.DAMAGED, BackupTestKit.backupOf(listOf("shared_prefs/whats_new.xml" to bomb)))
        val lowerCase = bomb.decodeToString().replace("<!DOCTYPE", "<!doctype").replace("<!ENTITY", "<!entity").toByteArray()
        refused(BackupError.DAMAGED, BackupTestKit.backupOf(listOf("shared_prefs/whats_new.xml" to lowerCase)))
        // Another declared encoding could spell the declaration in bytes a text search doesn't see.
        val utf7 = "<?xml version='1.0' encoding='UTF-7' ?>\n+ADw-!DOCTYPE map+AD4-\n<map>\n</map>\n".toByteArray()
        refused(BackupError.DAMAGED, BackupTestKit.backupOf(listOf("shared_prefs/whats_new.xml" to utf7)))
        val comment = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n<!-- hi -->\n</map>\n".toByteArray()
        refused(BackupError.DAMAGED, BackupTestKit.backupOf(listOf("shared_prefs/whats_new.xml" to comment)))
        // What SharedPreferences writes still goes through, escaped markup in a value included.
        val real = BackupTestKit.sharedPrefsXml("last_seen_release" to "0.9.1", "note" to "&lt;!DOCTYPE&gt; &amp; more")
        stage(BackupTestKit.backupOf(listOf("shared_prefs/whats_new.xml" to real)))
        assertTrue(File(phone.stagingDir, "shared_prefs/whats_new.xml").isFile)
    }

    @Test fun `own sounds must be declared`() {
        val files = listOf("files/sleep_custom/abc" to ByteArray(8))
        val zip = BackupTestKit.zipOf(listOf(BackupInventory.MANIFEST to BackupTestKit.manifestFor(files, ownSounds = false)) + files)
        refused(BackupError.DAMAGED, zip)
    }

    @Test fun `own sounds declared must be there`() {
        // The writer sets the flag only with sounds in the file; the flag alone would restore the list without them.
        val files = listOf("datastore/sleep.preferences_pb" to sleepBytes)
        refused(BackupError.DAMAGED, BackupTestKit.zipOf(listOf(BackupInventory.MANIFEST to BackupTestKit.manifestFor(files, ownSounds = true)) + files))
    }

    @Test fun `size caps are checked on the declared sizes, before anything is unpacked`() {
        val big = ByteArray(5000) { 1 }
        val files = listOf("files/sleep_custom/big" to big)
        refused(BackupError.TOO_LARGE, BackupTestKit.backupOf(files), BackupReader.Limits(maxOwnSoundBytes = 4096))
        val settings = listOf("datastore/sleep.preferences_pb" to sleepBytes)
        refused(BackupError.TOO_LARGE, BackupTestKit.backupOf(settings), BackupReader.Limits(maxSettingsFileBytes = 4))
        // Refused at the manifest: unpacking would have stopped at notes.txt (UNEXPECTED_FILE) first.
        val zip = BackupTestKit.zipOf(listOf(BackupInventory.MANIFEST to BackupTestKit.manifestFor(files), "notes.txt" to ByteArray(1)) + files)
        refused(BackupError.TOO_LARGE, zip, BackupReader.Limits(maxOwnSoundBytes = 4096))
    }

    @Test fun `the total cap counts every entry`() {
        val files = listOf("files/sleep_custom/a" to ByteArray(3000), "files/sleep_custom/b" to ByteArray(3000))
        refused(BackupError.TOO_LARGE, BackupTestKit.backupOf(files), BackupReader.Limits(maxTotalBytes = 5000))
    }

    @Test fun `an entry is cut off at the size its manifest declares`() {
        // A manifest that lies about the sizes, to get past the caps and the free-space check: the
        // copy stops as soon as an entry passes its declared size, before those bytes are written.
        val files = listOf("files/sleep_custom/a" to ByteArray(300_000) { 1 })
        val lying = BackupTestKit.manifestFor(files.map { it.first to ByteArray(1) })
        refused(BackupError.DAMAGED, BackupTestKit.zipOf(listOf(BackupInventory.MANIFEST to lying) + files))
        // Smaller than declared is damaged too.
        val short = BackupTestKit.manifestFor(files.map { it.first to ByteArray(400_000) })
        refused(BackupError.DAMAGED, BackupTestKit.zipOf(listOf(BackupInventory.MANIFEST to short) + files))
    }

    @Test fun `an entry the manifest doesn't list is refused before it is written`() {
        val listed = listOf("datastore/sleep.preferences_pb" to sleepBytes)
        val zip = BackupTestKit.zipOf(listOf(BackupInventory.MANIFEST to BackupTestKit.manifestFor(listed)) + listed + ("files/sleep_custom/extra" to ByteArray(10)))
        refused(BackupError.DAMAGED, zip)
    }

    @Test fun `too many entries are refused`() {
        val files = (1..5).map { "files/sleep_custom/s$it" to ByteArray(1) }
        refused(BackupError.TOO_LARGE, BackupTestKit.backupOf(files), BackupReader.Limits(maxEntries = 3))
        // Folder entries count too.
        val one = listOf("files/sleep_custom/s1" to ByteArray(1))
        val withFolders = BackupTestKit.zipOf(listOf(BackupInventory.MANIFEST to BackupTestKit.manifestFor(one), "files/" to ByteArray(0), "files/sleep_custom/" to ByteArray(0)) + one)
        refused(BackupError.TOO_LARGE, withFolders, BackupReader.Limits(maxEntries = 3))
    }

    @Test fun `folder entries must be empty and appear once`() {
        val files = listOf("files/sleep_custom/abc" to ByteArray(8))
        val manifest = BackupInventory.MANIFEST to BackupTestKit.manifestFor(files)
        // A payload under a folder name would be inflated for nothing and counted nowhere.
        refused(BackupError.DAMAGED, BackupTestKit.zipOf(listOf(manifest, "files/" to ByteArray(100_000)) + files))
        // ZipOutputStream refuses a duplicate name, so the second one is renamed in the bytes afterwards.
        val twice = BackupTestKit.zipOf(listOf(manifest, "files/" to ByteArray(0), "fileX/" to ByteArray(0)) + files).replacing("fileX/", "files/")
        refused(BackupError.DAMAGED, twice)
    }

    /** [this] with every occurrence of [from] replaced by [to] (same length): zip headers carry names as plain bytes. */
    private fun ByteArray.replacing(from: String, to: String): ByteArray {
        val a = from.toByteArray()
        val b = to.toByteArray()
        require(a.size == b.size)
        val out = copyOf()
        var i = 0
        var found = 0
        while (i <= out.size - a.size) {
            if (a.indices.all { out[i + it] == a[it] }) {
                b.copyInto(out, i)
                i += a.size
                found++
            } else {
                i++
            }
        }
        check(found > 0) { "$from not found" }
        return out
    }

    @Test fun `any failure while reading removes what was unpacked`() {
        val files = listOf("files/sleep_custom/a" to ByteArray(200_000) { (it % 251).toByte() }, "files/sleep_custom/b" to ByteArray(10))
        val zip = BackupTestKit.backupOf(files)
        // Stops well into the zip, after the first sound was written to the staging folder.
        fun failingAfter(bytes: Int, failure: () -> Throwable) = object : java.io.InputStream() {
            private val source = ByteArrayInputStream(zip)
            private var read = 0
            override fun read(): Int = if (read++ >= bytes) throw failure() else source.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (read >= bytes) throw failure()
                val n = source.read(b, off, minOf(len, bytes - read))
                if (n > 0) read += n
                return n
            }
        }
        val runtime = assertFailsWith<BackupException> { BackupReader(phone).stage(failingAfter(zip.size - 200) { IllegalArgumentException("MALFORMED") }) }
        assertEquals(BackupError.DAMAGED, runtime.error)
        assertFalse(phone.stagingTmpDir.exists(), "removed after a runtime exception")
        assertFailsWith<StackOverflowError> { BackupReader(phone).stage(failingAfter(zip.size - 200) { StackOverflowError() }) }
        assertFalse(phone.stagingTmpDir.exists(), "removed after an Error too")
        assertFalse(phone.stagingDir.exists())
    }

    @Test fun `one import at a time`() {
        val held = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val other = Thread {
            BackupLock.lock.lock()
            try {
                held.countDown()
                release.await()
            } finally {
                BackupLock.lock.unlock()
            }
        }.apply { start() }
        try {
            held.await()
            refused(BackupError.BUSY, BackupTestKit.backupOf(listOf("datastore/sleep.preferences_pb" to sleepBytes)))
        } finally {
            release.countDown()
            other.join()
        }
        assertEquals(1, stage(BackupTestKit.backupOf(listOf("datastore/sleep.preferences_pb" to sleepBytes))).entries.size, "free again")
    }

    @Test fun `an abandoned import stops and leaves an earlier one waiting`() {
        stage(BackupTestKit.backupOf(listOf("datastore/sleep.preferences_pb" to sleepBytes)))
        val earlier = File(phone.stagingDir, AppStorage.TOKEN).readText()
        assertFailsWith<kotlin.coroutines.cancellation.CancellationException> {
            BackupReader(phone).stageImport(ByteArrayInputStream(BackupTestKit.backupOf(listOf("datastore/call_audio.preferences_pb" to callBytes))), stillWanted = { false })
        }
        assertFalse(phone.stagingTmpDir.exists())
        assertEquals(earlier, File(phone.stagingDir, AppStorage.TOKEN).readText(), "the waiting one is untouched")
        assertTrue(File(phone.stagingDir, "datastore/sleep.preferences_pb").isFile)
    }

    @Test fun `a staged import is checked, not armed`() {
        val staged = BackupReader(phone).stageImport(ByteArrayInputStream(BackupTestKit.backupOf(listOf("datastore/sleep.preferences_pb" to sleepBytes))))
        assertTrue(staged.token.isNotBlank())
        assertEquals(staged.token, File(phone.stagingDir, AppStorage.TOKEN).readText())
        assertNull(StagingControl.confirmedAt(phone), "only the user's tap arms it")
        assertEquals(staged.token, StagingControl.pending(phone, System.currentTimeMillis())?.token)
    }

    @Test fun `something that isn't an Earshot backup is refused`() {
        refused(BackupError.NOT_A_BACKUP, "PK? no, just text".toByteArray())
        refused(BackupError.NOT_A_BACKUP, BackupTestKit.zipOf(listOf("datastore/sleep.preferences_pb" to sleepBytes)))
        refused(BackupError.NOT_A_BACKUP, BackupTestKit.zipOf(listOf(BackupInventory.MANIFEST to """{"format":1,"app":"other","created":"x","entries":[]}""".toByteArray())))
        refused(BackupError.NOT_A_BACKUP, BackupTestKit.zipOf(listOf(BackupInventory.MANIFEST to "[]".toByteArray())))
    }

    @Test fun `a refused import leaves an earlier one waiting`() {
        stage(BackupTestKit.backupOf(listOf("datastore/sleep.preferences_pb" to sleepBytes)))
        val e = assertFailsWith<BackupException> { stage(BackupTestKit.backupOf(listOf("notes.txt" to ByteArray(1)))) }
        assertEquals(BackupError.UNEXPECTED_FILE, e.error)
        assertTrue(File(phone.stagingDir, AppStorage.READY).isFile)
        // A good one replaces it.
        stage(BackupTestKit.backupOf(listOf("datastore/call_audio.preferences_pb" to callBytes)))
        assertTrue(File(phone.stagingDir, "datastore/call_audio.preferences_pb").isFile)
        assertFalse(File(phone.stagingDir, "datastore/sleep.preferences_pb").exists())
    }

    @Test fun `folder entries from other zip tools are skipped`() {
        val files = listOf("files/sleep_custom/abc" to ByteArray(8))
        val zip = BackupTestKit.zipOf(listOf(BackupInventory.MANIFEST to BackupTestKit.manifestFor(files), "files/" to ByteArray(0), "files/sleep_custom/" to ByteArray(0)) + files)
        assertEquals(1, stage(zip).entries.size)
        BackupFiles.deleteRecursively(phone.stagingDir)
        refused(BackupError.UNEXPECTED_FILE, BackupTestKit.zipOf(listOf(BackupInventory.MANIFEST to BackupTestKit.manifestFor(emptyList()), "elsewhere/" to ByteArray(0))))
    }
}
