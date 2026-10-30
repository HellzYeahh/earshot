package dev.earshot.codec.shizuku

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.AttributionSource
import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import java.lang.reflect.Method
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Calls on the hidden `IBluetoothA2dp` AIDL, run inside the user service (so the transaction
 * comes from the shell uid, which holds BLUETOOTH_PRIVILEGED + BLUETOOTH_CONNECT).
 *
 * The signatures changed repeatedly: no attribution (≤ 11), `AttributionSource` (12, 12L, 14+),
 * and `AttributionSource` + `SynchronousResultReceiver` on 13, where the methods became oneway.
 * Rather than trusting `SDK_INT`, every overload the running stack exposes is tried (the shape this
 * SDK documents first) and the one that worked is logged and remembered.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
internal class A2dpAidlCalls(binder: IBinder, private val context: Context?) {

    /** The `IBluetoothA2dp.Stub.Proxy` for [binder]. */
    private val service: Any = run {
        val stub = Class.forName("$IBLUETOOTH_A2DP\$Stub")
        stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: throw IllegalStateException("IBluetoothA2dp.Stub.asInterface returned null")
    }

    private val working = ConcurrentHashMap<String, Method>()

    /** `BluetoothCodecStatus` (typed Any: hidden before API 33), or null when the stack has none. */
    fun getCodecStatus(device: BluetoothDevice): Any? = call("getCodecStatus", device)

    /** Returns the overload that accepted the request; throws when none did. */
    fun setCodecConfigPreference(device: BluetoothDevice, config: Any): String {
        call("setCodecConfigPreference", device, config)
        return working["setCodecConfigPreference"]?.let(::describe) ?: "?"
    }

    /** `List<BluetoothCodecType>` on Android 15+; empty when the method is missing or refused. */
    fun getSupportedCodecTypes(): List<Any> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return emptyList()
        return try {
            (call("getSupportedCodecTypes") as? List<*>)?.filterNotNull() ?: emptyList()
        } catch (t: Throwable) {
            Log.w(SVC_TAG, "getSupportedCodecTypes failed: ${rootCause(t)}")
            emptyList()
        }
    }

    /**
     * Invokes [name] with [lead] arguments followed by whatever trailing parameters the overload
     * takes (`AttributionSource`, then optionally a `SynchronousResultReceiver`).
     */
    private fun call(name: String, vararg lead: Any?): Any? {
        working[name]?.let { known ->
            try {
                return invoke(known, lead)
            } catch (t: Throwable) {
                val cause = unwrap(t)
                val message = "$name failed: ${describe(known)} -> ${rootCause(cause)}"
                throw if (cause is SecurityException) SecurityException(message) else IllegalStateException(message, cause)
            }
        }
        val errors = mutableListOf<String>()
        var security: SecurityException? = null
        val candidates = candidates(name, lead.size)
        for (m in candidates) {
            try {
                val result = invoke(m, lead)
                Log.i(SVC_TAG, "$name works as ${describe(m)} (${m.parameterTypes.size} args, sdk ${Build.VERSION.SDK_INT})")
                working[name] = m
                return result
            } catch (t: Throwable) {
                val cause = unwrap(t)
                if (cause is SecurityException) security = cause
                errors += "${describe(m)} -> ${rootCause(cause)}"
            }
        }
        Log.w(SVC_TAG, "$name: no overload worked: $errors")
        val message = when {
            candidates.isEmpty() -> "$name isn't on this Android build's IBluetoothA2dp"
            else -> "$name failed: ${errors.joinToString("; ")}"
        }
        if (security != null) throw SecurityException(message)
        throw IllegalStateException(message)
    }

    private fun invoke(m: Method, lead: Array<out Any?>): Any? {
        val types = m.parameterTypes
        return when {
            types.size == lead.size -> m.invoke(service, *lead)
            types.size == lead.size + 1 && isAttribution(types[lead.size]) -> m.invoke(service, *lead, attribution())
            types.size == lead.size + 2 && isAttribution(types[lead.size]) -> invokeWithReceiver(m, types[lead.size + 1], *lead, attribution())
            else -> throw IllegalArgumentException("unexpected shape ${describe(m)}")
        }
    }

    private fun candidates(name: String, leadCount: Int): List<Method> {
        val fromInterface = runCatching { Class.forName(IBLUETOOTH_A2DP).methods.toList() }.getOrDefault(emptyList())
        val fromProxy = runCatching { service.javaClass.methods.toList() }.getOrDefault(emptyList())
        val expected = expectedArity(leadCount)
        return (fromInterface + fromProxy)
            .filter { it.name == name && it.parameterTypes.size in leadCount..leadCount + 2 }
            .distinctBy { it.parameterTypes.joinToString { p -> p.name } }
            .sortedBy { if (it.parameterTypes.size == expected) 0 else 1 }
    }

    private fun expectedArity(lead: Int): Int = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> lead + 1
        Build.VERSION.SDK_INT == Build.VERSION_CODES.TIRAMISU -> lead + 2
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> lead + 1
        else -> lead
    }

    private fun isAttribution(c: Class<*>) = c.name == "android.content.AttributionSource"

    /**
     * Android 13's oneway variants return through a `SynchronousResultReceiver` (jarjar'd into the
     * Bluetooth apex, so its class comes from the method itself): `get()` makes one,
     * `awaitResultNoInterrupt(Duration)` waits, `Result.getValue(default)` rethrows what the service recorded.
     */
    private fun invokeWithReceiver(m: Method, receiverClass: Class<*>, vararg args: Any?): Any? {
        val receiver = receiverClass.getMethod("get").invoke(null)
        m.invoke(service, *args, receiver)
        val result = receiverClass.getMethod("awaitResultNoInterrupt", Duration::class.java).invoke(receiver, Duration.ofSeconds(3))
        return result?.javaClass?.getMethod("getValue", Any::class.java)?.invoke(result, null)
    }

    private fun attribution(): Any {
        // Only reached for overloads that take an AttributionSource, which exist from Android 12;
        // the explicit check is what lets lint see that.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return selfAttribution
        throw IllegalStateException("AttributionSource needs Android 12+ (running ${Build.VERSION.SDK_INT})")
    }

    private val selfAttribution: Any by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) buildAttribution() else Unit
    }

    /**
     * Who we are, as the Bluetooth app will check it: the parcelled uid must equal
     * `Binder.getCallingUid()` (2000 when Shizuku runs over adb), with the matching package. Root
     * callers are exempt from that uid check, so a root Shizuku presents itself as system. Pid -1
     * (INVALID_PID) skips the pid check Android 14 added.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun buildAttribution(): AttributionSource {
        val uid = Process.myUid()
        val (asUid, pkg) = when (uid) {
            ROOT_UID -> SYSTEM_UID to "android"
            SHELL_UID -> SHELL_UID to SHELL_PACKAGE
            else -> uid to (runCatching { context?.packageManager?.getPackagesForUid(uid)?.firstOrNull() }.getOrNull() ?: SHELL_PACKAGE)
        }
        val b = AttributionSource.Builder(asUid).setPackageName(pkg)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) b.setPid(INVALID_PID)
        Log.i(SVC_TAG, "attribution uid=$asUid package=$pkg (process uid $uid)")
        return b.build()
    }

    private companion object {
        const val ROOT_UID = 0
        const val SYSTEM_UID = 1000
        const val SHELL_UID = 2000
        const val SHELL_PACKAGE = "com.android.shell"
        const val INVALID_PID = -1
    }
}
