package dev.earshot.bt

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * The last few hundred connection and protocol log lines, kept in memory so the Lab can offer a
 * "connection report" a user can paste into a bug report without needing adb. Lines carry no data
 * beyond what the logs already carry (the headphones' Bluetooth address, frame sizes and addresses).
 * Nothing is written to disk.
 */
object LogRing {
    private const val CAPACITY = 400
    private val lines = ArrayDeque<String>(CAPACITY)
    private val format = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    @Synchronized
    fun add(level: Char, tag: String, msg: String) {
        if (lines.size >= CAPACITY) lines.removeFirst()
        lines.addLast("${format.get()!!.format(Date())} $level $tag $msg")
    }

    @Synchronized
    fun tail(n: Int = 150): List<String> = lines.toList().takeLast(n)

    @Synchronized
    fun clear() = lines.clear()
}
