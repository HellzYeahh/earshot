package dev.earshot.bt

import android.util.Log

/**
 * Log tag `Earshot/Sony` for the Sony driver (connection, factory, session, repository). Lines name
 * commands, inquired types and sizes only: some Sony replies carry the phone's name, paired
 * devices' addresses or the headset's own address, so payload bytes are never logged.
 *
 * Like [BtLog], every line also goes into [LogRing] for the Lab's connection report, and the sink
 * falls back to stderr where `android.util.Log` is a stub (plain JVM unit tests).
 */
object SonyLog {
    const val TAG = "Earshot/Sony"

    @Volatile
    var sink: (priority: Int, msg: String, t: Throwable?) -> Unit = { p, m, t ->
        try {
            Log.println(p, TAG, if (t != null) "$m\n${Log.getStackTraceString(t)}" else m)
        } catch (_: RuntimeException) {
            System.err.println("$TAG: $m${t?.let { " (${it.message})" } ?: ""}")
        }
    }

    fun d(msg: String) { LogRing.add('D', "Sony", msg); sink(Log.DEBUG, msg, null) }
    fun i(msg: String) { LogRing.add('I', "Sony", msg); sink(Log.INFO, msg, null) }
    fun w(msg: String, t: Throwable? = null) { LogRing.add('W', "Sony", msg); sink(Log.WARN, msg, t) }
}
