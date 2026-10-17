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
        Log.d(TAG, "open session=$sessionId pkg=$packageName")
        _sessions.update { list ->
            list.filterNot { it.sessionId == sessionId } +
                AudioSession(sessionId, packageName, System.currentTimeMillis())
        }
    }

    fun close(sessionId: Int) {
        Log.d(TAG, "close session=$sessionId")
        _sessions.update { list -> list.filterNot { it.sessionId == sessionId } }
    }

    fun clear() = _sessions.update { emptyList() }

    /**
     * Drop sessions announced more than [maxAgeMillis] ago. Players the OS kills never send CLOSE,
     * so without this the list only ever grows; a session that is still live gets re-announced by
     * the player on its next start anyway.
     */
    fun pruneOlderThan(maxAgeMillis: Long, nowMillis: Long = System.currentTimeMillis()) {
        _sessions.update { list -> list.filter { nowMillis - it.openedAtMillis <= maxAgeMillis } }
    }
}
