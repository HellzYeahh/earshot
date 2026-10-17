package dev.earshot.bt

import android.util.Log

/**
 * Shared log tag for the transport layer. No PII beyond the headphones' Bluetooth address.
 *
 * The sink is replaceable so the pure-logic classes ([BmapConnection],
 * [ReconnectBackoff]) can run in plain JVM unit tests, where `android.util.Log` is a stub
 * that throws "not mocked".
 */
internal object BtLog {
    const val TAG = "Earshot/Bt"

    @Volatile
    var sink: (priority: Int, msg: String, t: Throwable?) -> Unit = { p, m, t -> Log.println(p, TAG, if (t != null) "$m\n${Log.getStackTraceString(t)}" else m) }

    fun d(msg: String) = sink(Log.DEBUG, msg, null)
    fun i(msg: String) = sink(Log.INFO, msg, null)
    fun w(msg: String, t: Throwable? = null) = sink(Log.WARN, msg, t)
    fun e(msg: String, t: Throwable? = null) = sink(Log.ERROR, msg, t)
}
