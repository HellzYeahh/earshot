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

/**
 * A failure as one line for logs and the connection report: "IOException: read failed" for a
 * platform exception, just the message for Earshot's own. Release builds are minified (R8), so
 * an Earshot class prints as its obfuscated name; user reports read "ch8: ea2: Could not open
 * the control channel" (issue #1) instead of a message anybody could use.
 */
internal fun Throwable.describeForReport(): String {
    val text = message ?: "no message"
    val own = this is RfcommConnectException || this is UnresponsiveLinkException || javaClass.name.startsWith("dev.earshot.")
    return if (own) text else "${javaClass.simpleName}: $text"
}
