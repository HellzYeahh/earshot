package dev.earshot.shortcut

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The HFP voice-recognition handshake. After the headset's `AT+BVRA=1` Android waits 5 s
 * (`sStartVrTimeoutMs` in AOSP's HeadsetService) for an app to call
 * [BluetoothHeadset.startVoiceRecognition]; that call answers the headset with success and opens
 * SCO (wideband where supported). Without it Android reports failure to the headset, which some
 * headsets announce with an error tone.
 *
 * The voice memo uses this as its way to the headset mic and ends with [stop]. One instance per
 * use; it holds the HEADSET profile proxy until [stop].
 */
class HfpVoiceRecognition(private val context: Context) {
    private var adapter: BluetoothAdapter? = null
    private var proxy: BluetoothHeadset? = null
    private var device: BluetoothDevice? = null

    /** Starts voice recognition on [address]; false when Bluetooth, the permission or the headset refused. */
    @SuppressLint("MissingPermission") // checked first
    suspend fun start(address: String): Boolean {
        if (!hasConnectPermission()) return false
        val a = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        if (!a.isEnabled) return false
        val headset = headsetProxy(a) ?: return false
        adapter = a
        proxy = headset
        val d = runCatching { a.getRemoteDevice(address.uppercase()) }.getOrNull() ?: return false.also { stop() }
        device = d
        val ok = runCatching { headset.startVoiceRecognition(d) }
            .onFailure { Log.w(TAG, "startVoiceRecognition failed: $it") }
            .getOrDefault(false)
        Log.i(TAG, "startVoiceRecognition -> $ok")
        if (!ok) stop()
        return ok
    }

    /** True while SCO audio to the headset is up (the voice-recognition link). */
    @SuppressLint("MissingPermission")
    fun isAudioConnected(): Boolean {
        val p = proxy ?: return false
        val d = device ?: return false
        return runCatching { p.isAudioConnected(d) }.getOrDefault(false)
    }

    /** Ends voice recognition (closing SCO) and releases the proxy. Idempotent. */
    @SuppressLint("MissingPermission")
    fun stop() {
        val p = proxy
        val d = device
        if (p != null && d != null) runCatching { p.stopVoiceRecognition(d) }.onFailure { Log.w(TAG, "stopVoiceRecognition failed: $it") }
        if (p != null) runCatching { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, p) }
        proxy = null
        device = null
        adapter = null
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private suspend fun headsetProxy(a: BluetoothAdapter): BluetoothHeadset? = withTimeoutOrNull(PROXY_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
                    if (cont.isActive) cont.resume(p as? BluetoothHeadset) else runCatching { a.closeProfileProxy(profile, p) }
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            }
            val started = runCatching { a.getProfileProxy(context, listener, BluetoothProfile.HEADSET) }.getOrDefault(false)
            if (!started && cont.isActive) cont.resume(null)
        }
    }

    companion object {
        private const val TAG = "Earshot/Shortcut"
        private const val PROXY_TIMEOUT_MS = 1_500L

        /**
         * Whether actions that don't need the mic (Cycle EQ, Launch app) still answer the headset's
         * request, by starting and at once stopping voice recognition. Off: it opens SCO and
         * interrupts music for a moment. Flip it if a headset plays an error tone on the 5 s timeout.
         */
        const val ACKNOWLEDGE_VOICE_REQUEST = false

        /** Answers the pending request and closes it again (see [ACKNOWLEDGE_VOICE_REQUEST]). */
        suspend fun acknowledge(context: Context, address: String) {
            val vr = HfpVoiceRecognition(context)
            val ok = vr.start(address)
            vr.stop()
            Log.i(TAG, "acknowledged the headset's voice request: $ok")
        }
    }
}
