package dev.earshot.codec

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Everything the codec panel can learn through *public* APIs: the app's own [BluetoothA2dp] proxy,
 * the connected sink, the phone-side codec list on Android 15+, and the A2DP output device as the
 * audio framework sees it. Privileged reads and writes live in the Shizuku user service instead.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is requested by the connection screen; every call is guarded.
internal class A2dpProxyHolder(private val context: Context) {
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)

    @Volatile private var proxy: BluetoothA2dp? = null
    @Volatile private var pending: CompletableDeferred<BluetoothA2dp?>? = null

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
            if (profile != BluetoothProfile.A2DP) return
            proxy = p as BluetoothA2dp
            pending?.complete(proxy)
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) proxy = null
        }
    }

    /** The A2DP proxy, binding to the Bluetooth app on first use. Null when Bluetooth is off/absent. */
    suspend fun get(): BluetoothA2dp? {
        proxy?.let { return it }
        val adapter = adapter ?: return null
        if (!adapter.isEnabled) return null
        val deferred = pending?.takeIf { it.isActive } ?: CompletableDeferred<BluetoothA2dp?>().also { d ->
            pending = d
            if (!adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP)) d.complete(null)
        }
        return withTimeoutOrNull(PROXY_TIMEOUT_MS) { deferred.await() }
    }

    fun connectedDevices(a2dp: BluetoothA2dp): List<BluetoothDevice> = try {
        a2dp.connectedDevices
    } catch (e: SecurityException) {
        Log.w(TAG, "BLUETOOTH_CONNECT missing: ${e.message}")
        emptyList()
    }

    /** Prefers the Bose headphones when several sinks are connected (e.g. a watch or car). */
    fun pickSink(devices: List<BluetoothDevice>): BluetoothDevice? {
        if (devices.isEmpty()) return null
        return devices.firstOrNull { d ->
            val name = runCatching { d.name }.getOrNull()?.lowercase() ?: return@firstOrNull false
            "bose" in name || "qc" in name || "quietcomfort" in name
        } ?: devices.first()
    }

    /**
     * `BluetoothA2dp.getCodecStatus(device)` on our own proxy, Android 9–11 only: back then the
     * Bluetooth app checked just the normal BLUETOOTH permission and the method was on the
     * hidden-API greylist, so plain reflection works. From 12 it needs BLUETOOTH_PRIVILEGED, which
     * only the Shizuku user service has, so it is not even tried.
     */
    // Deliberate reflective call on a hidden method, limited to old releases and caught right here.
    @SuppressLint("DiscouragedPrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi")
    fun codecStatusLegacy(a2dp: BluetoothA2dp, device: BluetoothDevice): Any? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return null
        return try {
            BluetoothA2dp::class.java.getMethod("getCodecStatus", BluetoothDevice::class.java).invoke(a2dp, device)
        } catch (t: Throwable) {
            Log.d(TAG, "legacy getCodecStatus unavailable: ${rootCause(t)}")
            null
        }
    }

    /** Names of every codec the phone's stack can encode (Android 15+ public API); empty elsewhere. */
    fun phoneCodecNames(a2dp: BluetoothA2dp): List<String> {
        if (Build.VERSION.SDK_INT < 35) return emptyList()
        return try {
            a2dp.supportedCodecTypes.map { it.codecName }
        } catch (t: Throwable) {
            Log.d(TAG, "getSupportedCodecTypes unavailable: ${rootCause(t)}")
            emptyList()
        }
    }

    /** The A2DP sink as the audio framework reports it; null when audio is not routed over A2DP. */
    fun a2dpOutputDevice(): AudioDeviceInfo? =
        audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }

    companion object {
        private const val TAG = "Earshot/A2dpProxy"
        private const val PROXY_TIMEOUT_MS = 4_000L

        fun rootCause(t: Throwable): String {
            var c: Throwable = t
            while (c.cause != null && c.cause !== c) c = c.cause!!
            return "${c.javaClass.simpleName}: ${c.message}"
        }
    }
}
