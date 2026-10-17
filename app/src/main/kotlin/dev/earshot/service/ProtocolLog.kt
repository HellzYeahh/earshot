package dev.earshot.service

import android.util.Log

/**
 * Log tag for the typed protocol layer. No PII beyond the headphones' Bluetooth address.
 *
 * The sink is replaceable (and the default one falls back to stderr) so [QcUltra2Protocol] can
 * run in plain JVM unit tests, where `android.util.Log` is a stub that throws "not mocked".
 */
internal object ProtocolLog {
    const val TAG = "Earshot/Protocol"

    @Volatile
    var sink: (priority: Int, msg: String, t: Throwable?) -> Unit = { p, m, t ->
        try {
            Log.println(p, TAG, if (t != null) "$m\n${Log.getStackTraceString(t)}" else m)
        } catch (_: RuntimeException) {
            System.err.println("$TAG: $m${t?.let { " (${it.message})" } ?: ""}")
        }
    }

    fun d(msg: String) = sink(Log.DEBUG, msg, null)
    fun i(msg: String) = sink(Log.INFO, msg, null)
    fun w(msg: String, t: Throwable? = null) = sink(Log.WARN, msg, t)
}
