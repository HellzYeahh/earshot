package dev.earshot.codec.shizuku

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** Tag for everything that runs inside the Shizuku user service process (`dev.earshot:codec`). */
internal const val SVC_TAG = "Earshot/CodecSvc"

internal const val IBLUETOOTH_A2DP = "android.bluetooth.IBluetoothA2dp"

/** "SecurityException: Need BLUETOOTH_PRIVILEGED permission" for the innermost cause. */
internal fun rootCause(t: Throwable): String {
    var c: Throwable = t
    while (c.cause != null && c.cause !== c) c = c.cause!!
    return "${c.javaClass.simpleName}: ${c.message}"
}

/** The exception a reflective call really threw. */
internal fun unwrap(t: Throwable): Throwable =
    if (t is InvocationTargetException && t.cause != null) t.cause!! else t

/** `setCodecConfigPreference(BluetoothDevice, BluetoothCodecConfig, AttributionSource)`. */
internal fun describe(m: Method): String = "${m.name}(${m.parameterTypes.joinToString { it.simpleName }})"
