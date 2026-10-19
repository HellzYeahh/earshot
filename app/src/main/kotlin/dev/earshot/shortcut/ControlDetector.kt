package dev.earshot.shortcut

import dev.earshot.core.HeadphoneState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One Android-side source of signals (playback, volume, calls). [open] starts it; closing stops it. */
fun interface ControlSignalFeed {
    fun open(emit: (ControlSignal) -> Unit): AutoCloseable
}

/** What the "Detect a button" sheet shows. */
data class DetectionSession(
    val listening: Boolean = false,
    /** Bumped on every [ControlDetector.start]; a late end of an older session can't touch a newer one. */
    val generation: Int = 0,
    val startedAt: Long? = null,
    val endsAt: Long? = null,
    val endedAt: Long? = null,
    /** The headset the remapper showed when the session started (stores the result when Android doesn't say who pressed). */
    val headsetAddress: String? = null,
    val log: DetectionLog = DetectionLog(),
) {
    val entries: List<DetectedControl> get() = log.entries
    /** A session ran and is over (timed out or stopped). */
    val finished: Boolean get() = !listening && startedAt != null
    val sawAssistantGesture: Boolean get() = log.sawAssistantGesture
}

/**
 * The remapper's "Detect a button" session: for up to [durationMs] it collects what each press
 * of a headset control does, from
 *  - the Bose connection ([headphoneState] diffs: `[5.4]` play state, `[5.5]` volume, `[2.12]`
 *    button events; nothing for headsets without BMAP or models that don't push),
 *  - Android ([feeds]: playback starting / stopping, music-stream volume, call state; any headset),
 *  - [ShortcutTriggerActivity] ([report] of the assistant gesture, which it then doesn't run).
 *
 * Owned by [ShortcutEngine] (one per process, so the trigger activity finds the running session).
 */
class ControlDetector(
    private val scope: CoroutineScope,
    private val headphoneState: StateFlow<HeadphoneState>,
    private val feeds: List<ControlSignalFeed>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val durationMs: Long = SESSION_MS,
    private val log: (String) -> Unit = {},
) {
    private val _session = MutableStateFlow(DetectionSession())
    val session: StateFlow<DetectionSession> = _session.asStateFlow()

    /** True while a session listens: the trigger then reports instead of running the action. */
    val isListening: Boolean get() = _session.value.listening

    private val lock = Any()
    private var job: Job? = null

    /** Starts (or restarts) a session; [headsetAddress] is the headset the screen shows. */
    fun start(headsetAddress: String? = null) {
        synchronized(lock) {
            job?.cancel()
            val now = clock()
            val generation = _session.value.generation + 1
            _session.value = DetectionSession(
                listening = true, generation = generation, startedAt = now, endsAt = now + durationMs, headsetAddress = headsetAddress,
            )
            log("detect session $generation started")
            job = scope.launch { run(generation) }
        }
    }

    /** Ends the session now (the sheet's Stop, or the sheet closing). The list stays for the sheet. */
    fun stop() {
        synchronized(lock) { job?.cancel(); job = null }
        end(_session.value.generation)
    }

    /** Clears the last session's results (the sheet closed). */
    fun clear() {
        stop()
        _session.update { DetectionSession(generation = it.generation) }
    }

    /**
     * Adds [signal] while listening. A signal stamped before the session ended still counts:
     * the trigger looks up the headset first, which can outlast the countdown by a moment.
     */
    fun report(signal: ControlSignal) {
        _session.update { s ->
            val ended = s.endedAt
            val accept = s.listening || (s.startedAt != null && ended != null && signal.at in s.startedAt..ended)
            if (accept) s.copy(log = s.log.add(signal)) else s
        }
    }

    private suspend fun run(generation: Int) {
        val handles = ArrayList<AutoCloseable>()
        try {
            for (feed in feeds) {
                try {
                    handles += feed.open(::report)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A missing service or permission only loses that one source.
                    log("a detection feed failed to start: $e")
                }
            }
            coroutineScope {
                // A child: it ends with the session (timeout or stop).
                val watcher = launch {
                    var previous: HeadphoneState? = headphoneState.value
                    headphoneState.collect { s ->
                        ControlClassifier.fromHeadphoneState(previous, s, clock()).forEach(::report)
                        previous = s
                    }
                }
                delay(durationMs)
                watcher.cancel()
            }
        } finally {
            handles.forEach { runCatching { it.close() } }
            end(generation)
        }
    }

    private fun end(generation: Int) {
        _session.update { s ->
            if (s.generation != generation || !s.listening) s else s.copy(listening = false, endedAt = clock())
        }
    }

    companion object {
        const val SESSION_MS = 45_000L
    }
}
