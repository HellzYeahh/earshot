package dev.earshot.sleep

import android.util.Log

/**
 * The Sleep tab's log tag, for the classes that also run in plain JVM unit tests
 * ([SleepHeadsetActions]): there `android.util.Log` is a stub that throws "not mocked", so a
 * failed write is ignored. No PII: headsets appear only by Bluetooth address.
 */
internal object SleepLog {
    const val TAG = "Earshot/Sleep"

    fun i(msg: String) { runCatching { Log.i(TAG, msg) } }
    fun w(msg: String) { runCatching { Log.w(TAG, msg) } }
}
