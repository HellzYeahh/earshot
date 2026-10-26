package dev.earshot.service.session

import android.util.Log

/**
 * Where a session repository's log lines go. Injected rather than calling `android.util.Log`
 * directly, because that class is a stub in plain JVM unit tests (no `returnDefaultValues`), and
 * the repositories are now tested there over a fake link.
 *
 * Each driver keeps its own tag and its own destination: Bose, the Crusher and Soundcore log to
 * logcat only ([AndroidSessionLog]); Sony and Sennheiser go through `SonyLog` / `SennheiserLog`,
 * which also feed the Lab's connection report. Lines carry no PII beyond the headset's address.
 */
interface SessionLog {
    fun d(msg: String)
    fun i(msg: String)
    fun w(msg: String, t: Throwable? = null)
}

/**
 * `android.util.Log` under [tag] (`Earshot/Repo`, `Earshot/Crusher`, `Earshot/Soundcore`), as those
 * repositories always logged. Not copied into `LogRing`: the connection report would otherwise
 * grow repository lines it never had. Falls back to stderr where `Log` is a stub (JVM tests), like
 * `ProtocolLog`; tests can also replace [sink] to keep their output quiet.
 */
class AndroidSessionLog(private val tag: String) : SessionLog {
    override fun d(msg: String) = sink(Log.DEBUG, tag, msg, null)
    override fun i(msg: String) = sink(Log.INFO, tag, msg, null)
    override fun w(msg: String, t: Throwable?) = sink(Log.WARN, tag, msg, t)

    companion object {
        @Volatile
        var sink: (priority: Int, tag: String, msg: String, t: Throwable?) -> Unit = { p, tag, m, t ->
            try {
                Log.println(p, tag, if (t != null) "$m\n${Log.getStackTraceString(t)}" else m)
            } catch (_: RuntimeException) {
                System.err.println("$tag: $m${t?.let { " (${it.message})" } ?: ""}")
            }
        }
    }
}
