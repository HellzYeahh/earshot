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

    // Every line also goes into LogRing so the Lab's connection report can show what just happened.
    fun d(msg: String) { LogRing.add('D', "Bt", msg); sink(Log.DEBUG, msg, null) }
    fun i(msg: String) { LogRing.add('I', "Bt", msg); sink(Log.INFO, msg, null) }
    fun w(msg: String, t: Throwable? = null) { LogRing.add('W', "Bt", msg); sink(Log.WARN, msg, t) }
    fun e(msg: String, t: Throwable? = null) { LogRing.add('E', "Bt", msg); sink(Log.ERROR, msg, t) }
}
