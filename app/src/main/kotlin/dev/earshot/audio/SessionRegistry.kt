package dev.earshot.audio

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** One audio session another app told us about via the effect-control-session broadcast. */
data class AudioSession(
    val sessionId: Int,
    /** May be null: the extra is optional and a few players omit it. */
    val packageName: String?,
    val openedAtMillis: Long,
)

/**
 * Process-wide list of audio sessions that media apps have announced. The manifest receiver
 * writes to it, the repository reads it. It is a plain singleton (not in [dev.earshot.AppContainer])
 * because the receiver can be the very first thing that runs in a cold process and must not
 * depend on anything else having been built yet.
 *
 * Sessions are kept in open order so "most recently opened" is just the last element.
 */
object SessionRegistry {
    private const val TAG = "Earshot/Sessions"

    private val _sessions = MutableStateFlow<List<AudioSession>>(emptyList())
    val sessions: StateFlow<List<AudioSession>> = _sessions

    fun open(sessionId: Int, packageName: String?) {
        if (sessionId <= 0) return // 0 is the global mix; apps never legitimately announce it
        // Not the package itself: a line per playback start would be a record of the user's app
        // usage in logcat and in bug reports (no PII in logs beyond the headphones' address).
        log("open session=$sessionId pkg=${if (packageName != null) "yes" else "no"}")
        _sessions.update { list ->
            list.filterNot { it.sessionId == sessionId } +
                AudioSession(sessionId, packageName, System.currentTimeMillis())
        }
    }

    fun close(sessionId: Int) {
        log("close session=$sessionId")
        _sessions.update { list -> list.filterNot { it.sessionId == sessionId } }
    }

    fun clear() = _sessions.update { emptyList() }

    /**
     * Drop sessions announced more than [maxAgeMillis] ago. Players the OS kills never send CLOSE,
     * so without this the list only ever grows; a session that is still live gets re-announced by
     * the player on its next start anyway. Sessions in [attached] (they carry a working effect, so
     * the player may simply be playing for hours since its one announcement) may reach
     * [attachedMaxAgeMillis] instead.
     */
    fun pruneOlderThan(
        maxAgeMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
        attached: Set<Int> = emptySet(),
        attachedMaxAgeMillis: Long = maxAgeMillis,
    ) {
        _sessions.update { list ->
            list.filter { s ->
                val limit = if (s.sessionId in attached) maxOf(maxAgeMillis, attachedMaxAgeMillis) else maxAgeMillis
                nowMillis - s.openedAtMillis <= limit
            }
        }
    }

    // Wrapped so the registry also runs in plain JVM unit tests, where android.util.Log is a stub that throws.
    private fun log(msg: String) { runCatching { Log.d(TAG, msg) } }
}
