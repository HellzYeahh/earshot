package dev.earshot.codec.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.DeadObjectException
import android.os.IBinder
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import dev.earshot.codec.ShizukuPrivilege
import dev.earshot.core.PrivilegeState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

/**
 * App-side handle on [CodecUserService]: binds it through Shizuku on first use, re-binds after
 * the service (or Shizuku) died, and unbinds in [close].
 *
 * The process is non-daemon, so Shizuku kills it together with the app process.
 */
internal class CodecServiceClient(private val context: Context) {

    private val mutex = Mutex()
    @Volatile private var service: ICodecService? = null
    @Volatile private var pending: CompletableDeferred<ICodecService>? = null
    @Volatile private var bound = false

    private val args: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, CodecUserService::class.java.name))
            .tag(SERVICE_TAG)
            .processNameSuffix(PROCESS_SUFFIX)
            .daemon(false)
            .debuggable(isDebuggable())
            .version(serviceVersion())
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                Log.w(TAG, "Shizuku handed over a dead codec service binder")
                pending?.completeExceptionally(IllegalStateException("The codec service started but isn't answering"))
                return
            }
            runCatching { binder.linkToDeath({ Log.w(TAG, "codec service process died"); service = null }, 0) }
            val s = ICodecService.Stub.asInterface(binder)
            service = s
            Log.i(TAG, "codec user service connected (process ${context.packageName}:$PROCESS_SUFFIX)")
            pending?.complete(s)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "codec user service disconnected")
            service = null
        }
    }

    /** Shizuku is running and Earshot holds its permission; nothing else is needed to switch. */
    fun isReady(): Boolean = ShizukuPrivilege.current(context) == PrivilegeState.GRANTED

    /**
     * Runs [block] against the service on the IO dispatcher, binding first if needed. A service that
     * died between calls is re-bound once. Remote failures arrive as the service's own
     * SecurityException / IllegalStateException with a readable message.
     */
    suspend fun <T> withService(block: (ICodecService) -> T): T {
        val first = obtain()
        return try {
            withContext(Dispatchers.IO) { block(first) }
        } catch (e: DeadObjectException) {
            Log.w(TAG, "codec service died mid-call; binding again once")
            service = null
            val second = obtain()
            withContext(Dispatchers.IO) { block(second) }
        }
    }

    /** Drops the cached binder, e.g. when Shizuku itself died. */
    fun reset() {
        service = null
    }

    /** Unbinds and asks Shizuku to stop the service process. */
    fun close() {
        if (!bound) return
        bound = false
        runCatching { Shizuku.unbindUserService(args, connection, true) }
            .onFailure { Log.w(TAG, "unbindUserService failed: $it") }
        service = null
    }

    private suspend fun obtain(): ICodecService = mutex.withLock {
        service?.takeIf { runCatching { it.asBinder().pingBinder() }.getOrDefault(false) }?.let { return@withLock it }
        service = null
        if (!isReady()) throw IllegalStateException("Shizuku isn't running or Earshot doesn't have its permission")
        val deferred = CompletableDeferred<ICodecService>()
        pending = deferred
        try {
            Log.i(TAG, "binding the codec user service through Shizuku ${runCatching { Shizuku.getVersion() }.getOrNull()}")
            // Shizuku delivers ServiceConnection callbacks on the main thread; bind from there too.
            withContext(Dispatchers.Main) { Shizuku.bindUserService(args, connection) }
            bound = true
            withTimeout(BIND_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "codec user service didn't connect within ${BIND_TIMEOUT_MS} ms")
            throw IllegalStateException("Shizuku didn't start the codec service within ${BIND_TIMEOUT_MS / 1000} s")
        } finally {
            pending = null
        }
    }

    private fun isDebuggable(): Boolean = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Shizuku reuses a running service while the version is unchanged. versionCode alone stays 1
     * across development installs, which would keep old service code alive after an update, so the
     * install time is mixed in.
     */
    private fun serviceVersion(): Int = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        ((PackageInfoCompat.getLongVersionCode(info) * 1_000_003L + info.lastUpdateTime / 1000L) and 0x7fffffffL).toInt()
    } catch (t: Throwable) {
        1
    }

    private companion object {
        const val TAG = "Earshot/Codec"
        const val SERVICE_TAG = "earshot-codec"
        const val PROCESS_SUFFIX = "codec"
        const val BIND_TIMEOUT_MS = 8_000L
    }
}
