package dev.earshot.bt

import android.util.Log

/**
 * Log tag for the JBL driver (connection, session, repository). Lines carry command ids, sizes and
 * decoded settings, never raw payloads (the device info carries the headset's name and address).
 *
 * The sink is replaceable (and falls back to stderr) so the connection and session run in plain
 * JVM unit tests, where `android.util.Log` is a stub that throws "not mocked".
 */
internal object JblLog {
    const val TAG = "Earshot/JBL"

    @Volatile
    var sink: (priority: Int, msg: String, t: Throwable?) -> Unit = { p, m, t ->
        try {
            if (t == null) Log.println(p, TAG, m) else Log.w(TAG, m, t)
        } catch (_: RuntimeException) {
            System.err.println("$TAG: $m${t?.let { " (${it.message})" } ?: ""}")
        }
    }

    fun d(msg: String) { LogRing.add('D', "JBL", msg); sink(Log.DEBUG, msg, null) }
    fun i(msg: String) { LogRing.add('I', "JBL", msg); sink(Log.INFO, msg, null) }
    fun w(msg: String, t: Throwable? = null) { LogRing.add('W', "JBL", msg); sink(Log.WARN, msg, t) }
}
