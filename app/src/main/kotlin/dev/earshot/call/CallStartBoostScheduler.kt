package dev.earshot.call

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Decides *when* to boost the call volume.
 *
 * A boost is due once "in a call AND routed to a Bluetooth headset" becomes true. It fires
 * [fireAtMs] after that edge: the first one waits ~1 s because SCO (and with it the per-device
 * `STREAM_VOICE_CALL` index) only settles after the mode change; the second one at +3 s exists
 * because One UI re-applies its stored call volume a moment after the call connects and would
 * otherwise undo the first. If the condition drops before a fire (mode flapped, SCO dropped, call
 * ended) the pending fires are cancelled; when it comes back, the schedule starts over. Repeating
 * the same condition does nothing, so callers can feed every poll result in.
 *
 * Not thread-safe: call [update] from one thread / a serial dispatcher.
 */
class CallStartBoostScheduler(
    private val scope: CoroutineScope,
    private val fireAtMs: List<Long> = DEFAULT_FIRE_AT_MS,
    private val onFire: suspend (attempt: Int) -> Unit,
) {
    init {
        require(fireAtMs.zipWithNext().all { (a, b) -> b >= a }) { "fireAtMs must be ascending" }
    }

    private var armed = false
    private var job: Job? = null

    /** True while fires are still pending for the current call. */
    val isPending: Boolean get() = job?.isActive == true

    fun update(inCall: Boolean, onHeadphones: Boolean) {
        val active = inCall && onHeadphones
        if (active == armed) return
        armed = active
        job?.cancel()
        job = if (active) {
            scope.launch {
                var elapsed = 0L
                fireAtMs.forEachIndexed { attempt, at ->
                    delay(at - elapsed)
                    elapsed = at
                    onFire(attempt)
                }
            }
        } else {
            null
        }
    }

    fun cancel() {
        armed = false
        job?.cancel()
        job = null
    }

    companion object {
        val DEFAULT_FIRE_AT_MS = listOf(1_000L, 3_000L)
    }
}
