package dev.earshot.bt

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The timeline file: appends, survives a restart, stays within capacity, skips junk lines. */
class TimelineStoreTest {

    /** Runs each task at once, so the test sees the result without waiting. */
    private val direct = Executor { it.run() }

    private fun tempFile(): File = Files.createTempDirectory("timeline").toFile().resolve("bt_timeline.tsv")

    private fun event(n: Long) = TimelineEvent(n, "68:F2:1F:38:DB:0F", TimelineKind.LINK_UP, "#$n")

    @Test fun `events are kept across a restart`() {
        val file = tempFile()
        TimelineStore(file, io = direct).apply { record(event(1)); record(event(2)) }
        val again = TimelineStore(file, io = direct).also { it.load() }
        assertEquals(listOf("#1", "#2"), again.events.value.map { it.detail })
    }

    @Test fun `the file never grows far past capacity`() {
        val file = tempFile()
        val store = TimelineStore(file, capacity = 8, io = direct)
        (1L..40L).forEach { store.record(event(it)) }
        assertEquals((33L..40L).map { "#$it" }, store.events.value.map { it.detail })
        assertTrue(file.readLines().size <= 8 + 8 / 4, "rewritten once it grows a quarter past capacity")
        val again = TimelineStore(file, capacity = 8, io = direct).also { it.load() }
        assertEquals(store.events.value, again.events.value)
    }

    @Test fun `junk lines are skipped and clear empties the file`() {
        val file = tempFile()
        file.writeText("garbage\n" + event(5).encode() + "\n")
        val store = TimelineStore(file, io = direct).also { it.load() }
        assertEquals(listOf("#5"), store.events.value.map { it.detail })
        store.clear()
        assertTrue(store.events.value.isEmpty())
        assertTrue(!file.exists())
    }
}
