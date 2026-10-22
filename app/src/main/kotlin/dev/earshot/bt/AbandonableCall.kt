package dev.earshot.bt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.thread

/**
 * How long a cancelled [runAbandonable] waits for the call to react to [abort] before it lets go
 * of it. The abort normally ends the call within milliseconds.
 */
internal const val ABORT_GRACE_MS = 1_500L

/**
 * Runs [block], a blocking call no coroutine can interrupt (`BluetoothSocket.connect()`), on its
 * own daemon thread, and gives the caller its result.
 *
 * Why not `coroutineScope { async { block() } }`: that scope does not return until the call has,
 * so a cancellation (the 6 s connect timeout of [BmapChannelSearch]) only took effect once the
 * Bluetooth stack gave up on its own. A Xiaomi on Android 16 (user report, Earshot 0.3.1) kept
 * "reflection ch9" and "SDP BMAP" blocked for 78 and 82 s despite the timeout and its `close()`,
 * which spent the whole 20 s search budget several times over.
 *
 * On cancellation: [abort] runs on a separate thread (a `close()` that blocks must not hold the
 * caller either), the call gets [graceMs] to end, and then the caller is released regardless.
 * If the call did end with its own exception in that time, that exception is thrown instead of
 * the cancellation, as before: a connect aborted by `close()` fails with "read failed, socket might
 * closed", which the busy-signature check relies on. A call abandoned past the grace period
 * finishes (or fails) on its own thread and nothing waits for it.
 */
internal suspend fun <T> runAbandonable(
    name: String,
    abort: () -> Unit,
    graceMs: Long = ABORT_GRACE_MS,
    block: () -> T,
): T {
    val done = CompletableDeferred<T>()
    thread(name = name, isDaemon = true) {
        try {
            done.complete(block())
        } catch (t: Throwable) {
            done.completeExceptionally(t)
        }
    }
    try {
        return done.await()
    } catch (e: CancellationException) {
        thread(name = "$name-abort", isDaemon = true) { runCatching(abort) }
        val late = withContext(NonCancellable) { withTimeoutOrNull(graceMs) { runCatching { done.await() } } }
        late?.exceptionOrNull()?.takeIf { it !is CancellationException }?.let { throw it }
        throw e
    }
}
