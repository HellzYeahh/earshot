package dev.earshot.codec.shizuku

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Finds the Bluetooth app's `IBluetoothA2dp` binder from inside the user service.
 *
 * Why several routes: the A2DP service is not in ServiceManager; apps reach it through the adapter.
 *  - Android 15+ (and some 14 builds): `IBluetooth.getProfile(int)` hands the profile binder out
 *    directly, reachable as the hidden `BluetoothAdapter.getProfile(int)` or through the adapter's
 *    private `mService` field;
 *  - Android 12–14: `getProfileProxy()` binds a [BluetoothA2dp] proxy (its callback needs the main
 *    looper, which Shizuku's `ServiceStarter` prepares and loops) and the private
 *    `BluetoothA2dp.getService()` returns the binder.
 * Every route is tried in the order that fits the running SDK; which one worked is logged. Plain
 * reflection is enough here: an `app_process` started by Shizuku runs without a hidden-API policy.
 */
// PrivateApi & co: reaching hidden Bluetooth internals is this class's whole job; every lookup is
// in a try and the failure is reported to the app, which falls back to dumpsys.
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi", "MissingPermission")
internal class A2dpBinderLocator(private val context: Context?) {

    @Volatile private var cached: IBinder? = null
    @Volatile private var proxy: BluetoothProfile? = null // kept so the profile binding is not dropped

    /** Route name that produced the current binder, for logs. */
    @Volatile var route: String? = null
        private set

    fun adapter(): BluetoothAdapter {
        ensureBluetoothServiceManager()
        @Suppress("DEPRECATION") // the Context-free getter is the one that works in a bare process
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: throw IllegalStateException("No Bluetooth adapter in the codec service process")
        if (!adapter.isEnabled) throw IllegalStateException("Bluetooth is off")
        return adapter
    }

    fun a2dpBinder(): IBinder {
        cached?.takeIf { it.isBinderAlive }?.let { return it }
        cached = null
        val adapter = adapter()
        val routes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            listOf(ROUTE_GET_PROFILE to ::viaAdapterGetProfile, ROUTE_SERVICE to ::viaAdapterService, ROUTE_PROXY to ::viaProfileProxy)
        } else {
            listOf(ROUTE_PROXY to ::viaProfileProxy, ROUTE_GET_PROFILE to ::viaAdapterGetProfile, ROUTE_SERVICE to ::viaAdapterService)
        }
        val errors = mutableListOf<String>()
        for ((name, find) in routes) {
            val binder = try {
                find(adapter)
            } catch (t: Throwable) {
                errors += "$name: ${rootCause(unwrap(t))}"
                null
            }
            if (binder == null) {
                if (errors.none { it.startsWith("$name:") }) errors += "$name: returned null"
                continue
            }
            val descriptor = runCatching { binder.interfaceDescriptor }.getOrNull()
            if (descriptor != IBLUETOOTH_A2DP) {
                errors += "$name: wrong interface $descriptor"
                continue
            }
            Log.i(SVC_TAG, "A2DP binder via route '$name' (sdk ${Build.VERSION.SDK_INT})")
            if (errors.isNotEmpty()) Log.i(SVC_TAG, "routes that failed first: $errors")
            cached = binder
            route = name
            return binder
        }
        Log.w(SVC_TAG, "no route to IBluetoothA2dp: $errors")
        throw IllegalStateException("Can't reach the A2DP service: ${errors.joinToString("; ")}")
    }

    /** Forget the binder (Bluetooth restarted, or a call failed); the next call looks it up again. */
    fun invalidate() {
        cached = null
    }

    // ---- routes -------------------------------------------------------------------------------

    private fun viaAdapterGetProfile(adapter: BluetoothAdapter): IBinder? {
        val m = BluetoothAdapter::class.java.getDeclaredMethod("getProfile", Int::class.javaPrimitiveType)
        m.isAccessible = true
        return m.invoke(adapter, BluetoothProfile.A2DP) as? IBinder
    }

    private fun viaAdapterService(adapter: BluetoothAdapter): IBinder? {
        val f = BluetoothAdapter::class.java.getDeclaredField("mService")
        f.isAccessible = true
        val service = f.get(adapter) ?: throw IllegalStateException("BluetoothAdapter.mService is null")
        val m = service.javaClass.methods.firstOrNull {
            it.name == "getProfile" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: throw NoSuchMethodException("IBluetooth.getProfile(int) not on ${service.javaClass.name}")
        return m.invoke(service, BluetoothProfile.A2DP) as? IBinder
    }

    private fun viaProfileProxy(adapter: BluetoothAdapter): IBinder? {
        val p = proxy ?: connectProxy(adapter)
        // Android 12–15 have a private getService(); otherwise scan the proxy (and its
        // BluetoothProfileConnector on 12–14) for a field holding the binder.
        runCatching {
            val m = BluetoothA2dp::class.java.getDeclaredMethod("getService").apply { isAccessible = true }
            m.invoke(p)?.let(::asA2dpBinder)
        }.getOrNull()?.let { return it }
        return scanFields(p, depth = 2)
    }

    private fun connectProxy(adapter: BluetoothAdapter): BluetoothProfile {
        val ctx = context ?: throw IllegalStateException("Shizuku passed no Context, so getProfileProxy is unavailable")
        val latch = CountDownLatch(1)
        var connected: BluetoothProfile? = null
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
                if (profile != BluetoothProfile.A2DP) return
                connected = p
                latch.countDown()
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.A2DP) { proxy = null; cached = null }
            }
        }
        if (!adapter.getProfileProxy(ctx, listener, BluetoothProfile.A2DP)) throw IllegalStateException("getProfileProxy returned false")
        // Binder threads may block; the callback arrives on the main looper Shizuku keeps running.
        if (!latch.await(PROXY_TIMEOUT_S, TimeUnit.SECONDS)) throw IllegalStateException("A2DP proxy didn't connect within ${PROXY_TIMEOUT_S}s")
        return connected!!.also { proxy = it }
    }

    private fun scanFields(root: Any, depth: Int): IBinder? {
        val queue = ArrayDeque<Pair<Any, Int>>().apply { add(root to depth) }
        val seen = HashSet<Int>()
        while (queue.isNotEmpty()) {
            val (obj, d) = queue.removeFirst()
            if (!seen.add(System.identityHashCode(obj))) continue
            var c: Class<*>? = obj.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    val value = runCatching { f.isAccessible = true; f.get(obj) }.getOrNull() ?: continue
                    asA2dpBinder(value)?.let { Log.i(SVC_TAG, "A2DP binder found in field ${c.simpleName}.${f.name}"); return it }
                    if (d > 1 && value.javaClass.name.startsWith("android.bluetooth.")) queue.add(value to d - 1)
                }
                c = c.superclass
            }
        }
        return null
    }

    private fun asA2dpBinder(value: Any): IBinder? {
        val binder = when (value) {
            is IInterface -> runCatching { value.asBinder() }.getOrNull()
            is IBinder -> value
            else -> null
        } ?: return null
        return binder.takeIf { runCatching { it.interfaceDescriptor }.getOrNull() == IBLUETOOTH_A2DP }
    }

    /**
     * On Android 13+ `BluetoothAdapter` gets its manager binder from `BluetoothFrameworkInitializer`,
     * which `ActivityThread.main()` sets up in app processes. A bare `app_process` may not have it,
     * so install a `BluetoothServiceManager` when it is missing (both are module-internal SystemApis).
     */
    private fun ensureBluetoothServiceManager() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || serviceManagerChecked) return
        serviceManagerChecked = true
        try {
            val init = Class.forName("android.bluetooth.BluetoothFrameworkInitializer")
            val current = init.getDeclaredMethod("getBluetoothServiceManager").apply { isAccessible = true }.invoke(null)
            if (current != null) {
                Log.i(SVC_TAG, "BluetoothServiceManager already initialised in this process")
                return
            }
            val managerClass = Class.forName("android.os.BluetoothServiceManager")
            val manager = managerClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            init.getDeclaredMethod("setBluetoothServiceManager", managerClass).apply { isAccessible = true }.invoke(null, manager)
            Log.i(SVC_TAG, "installed a BluetoothServiceManager for this process")
        } catch (t: Throwable) {
            Log.w(SVC_TAG, "BluetoothServiceManager check failed (continuing): ${rootCause(unwrap(t))}")
        }
    }

    companion object {
        const val ROUTE_GET_PROFILE = "BluetoothAdapter.getProfile(A2DP)"
        const val ROUTE_SERVICE = "BluetoothAdapter.mService.getProfile(A2DP)"
        const val ROUTE_PROXY = "getProfileProxy+BluetoothA2dp.getService()"
        private const val PROXY_TIMEOUT_S = 3L

        @Volatile private var serviceManagerChecked = false
    }
}
