package dev.earshot.bt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The diagnostics timeline on disk: one tab-separated line per [TimelineEvent] in [file], newest
 * last, at most [BtTimeline.CAPACITY] lines. Appends are cheap; the file is rewritten only when it
 * has grown a quarter past capacity. Reads and writes run on one background thread ([io]), so a
 * broadcast receiver can record without touching the disk on the main thread.
 *
 * Local only, like everything else in Earshot: the timeline leaves the phone only inside a
 * connection report the user shares.
 */
class TimelineStore(
    private val file: File,
    private val capacity: Int = BtTimeline.CAPACITY,
    private val io: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "Earshot-timeline").apply { isDaemon = true } },
) {
    private val _events = MutableStateFlow<List<TimelineEvent>>(emptyList())

    /** Oldest first. Empty until [load] has run. */
    val events: StateFlow<List<TimelineEvent>> = _events.asStateFlow()

    @Volatile
    private var loaded = false

    private var linesOnDisk = 0

    /** Read the file once; later calls do nothing. Runs on [io]. */
    fun load() = io.execute { ensureLoaded() }

    /** Append [event]; the result completes once it is on disk (or the write failed). */
    fun record(event: TimelineEvent): CompletableDeferred<Unit> {
        val done = CompletableDeferred<Unit>()
        io.execute {
            ensureLoaded()
            _events.value = BtTimeline.trim(_events.value + event, capacity)
            runCatching {
                if (linesOnDisk + 1 > capacity + capacity / 4) rewrite() else {
                    file.appendText(event.encode() + "\n")
                    linesOnDisk++
                }
            }
            done.complete(Unit)
        }
        return done
    }

    fun clear() = io.execute {
        _events.value = emptyList()
        runCatching { file.delete() }
        linesOnDisk = 0
        loaded = true
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val lines = runCatching { if (file.exists()) file.readLines() else emptyList() }.getOrDefault(emptyList())
        linesOnDisk = lines.size
        _events.value = BtTimeline.trim(lines.mapNotNull(TimelineEvent::decode), capacity)
    }

    private fun rewrite() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(_events.value.joinToString("") { it.encode() + "\n" })
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        linesOnDisk = _events.value.size
    }
}
