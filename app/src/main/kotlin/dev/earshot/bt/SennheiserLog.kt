package dev.earshot.bt

import android.util.Log

/**
 * Log tag for the Sennheiser driver (connection, session, repository). Lines carry command ids,
 * sizes and decoded settings, never raw payloads, and no address but the headset's own.
 *
 * The sink is replaceable (and falls back to stderr) so the connection and session run in plain
 * JVM unit tests, where `android.util.Log` is a stub that throws "not mocked".
 */
internal object SennheiserLog {
    const val TAG = "Earshot/Sennheiser"

    @Volatile
    var sink: (priority: Int, msg: String, t: Throwable?) -> Unit = { p, m, t ->
        try {
            Log.println(p, TAG, if (t != null) "$m\n${Log.getStackTraceString(t)}" else m)
        } catch (_: RuntimeException) {
            System.err.println("$TAG: $m${t?.let { " (${it.message})" } ?: ""}")
        }
    }

    fun d(msg: String) { LogRing.add('D', "Sennheiser", msg); sink(Log.DEBUG, msg, null) }
    fun i(msg: String) { LogRing.add('I', "Sennheiser", msg); sink(Log.INFO, msg, null) }
    fun w(msg: String, t: Throwable? = null) { LogRing.add('W', "Sennheiser", msg); sink(Log.WARN, msg, t) }
}
