package dev.earshot.mic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Routes microphone input to the headset's own mic for the duration of a recording.
 *
 * Headset mics only exist on the HFP/SCO link, not on A2DP, so the phone has to open SCO (which
 * pauses A2DP music) and route "communication" audio to it. Two eras of API:
 *  - Android 12+: `setCommunicationDevice(TYPE_BLUETOOTH_SCO)`; the SCO link comes up when a
 *    VOICE_COMMUNICATION capture starts. Priority among apps goes to whoever owns the audio mode,
 *    hence MODE_IN_COMMUNICATION while we record (exactly what calling apps do).
 *  - Android 9–11: `startBluetoothSco()` and wait for `SCO_AUDIO_STATE_CONNECTED`.
 * [release] always undoes everything so music returns to A2DP afterwards.
 */
internal class HeadsetMicRouter(private val context: Context) {
    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)
    private var previousMode = AudioManager.MODE_NORMAL
    private var engaged = false

    /** The SCO input device if a headset with a mic is connected, else null. */
    fun scoInputDevice(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

    /** True when the stack currently routes communication audio over SCO. */
    fun isHeadsetRouteActive(): Boolean = when {
        Build.VERSION.SDK_INT >= 31 -> audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        else -> @Suppress("DEPRECATION") audioManager.isBluetoothScoOn
    }

    /** Requests the headset route; returns whether it is in place. Falls back silently to the phone mic. */
    suspend fun engage(timeoutMs: Long = ROUTE_TIMEOUT_MS): Boolean {
        if (engaged) return isHeadsetRouteActive()
        // If the mode already reads IN_COMMUNICATION it is our own previous engagement still being
        // released; remembering it would make the matching release() leave the phone stuck there.
        previousMode = audioManager.mode.takeIf { it != AudioManager.MODE_IN_COMMUNICATION } ?: AudioManager.MODE_NORMAL
        engaged = true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) engageModern(timeoutMs) else engageLegacy(timeoutMs)
        } catch (t: Throwable) {
            Log.w(TAG, "headset routing failed, using phone mic: $t")
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun engageModern(timeoutMs: Long): Boolean {
        val sco = audioManager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        if (sco == null) {
            Log.i(TAG, "no SCO communication device available")
            return false
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (!audioManager.setCommunicationDevice(sco)) {
            Log.w(TAG, "setCommunicationDevice refused")
            return false
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return true
            delay(POLL_MS)
        }
        return false
    }

    @Suppress("DEPRECATION")
    private suspend fun engageLegacy(timeoutMs: Long): Boolean {
        if (!audioManager.isBluetoothScoAvailableOffCall) return false
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val connected = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        val s = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                        if (s == AudioManager.SCO_AUDIO_STATE_CONNECTED || s == AudioManager.SCO_AUDIO_STATE_ERROR) {
                            runCatching { context.unregisterReceiver(this) }
                            if (cont.isActive) cont.resume(s == AudioManager.SCO_AUDIO_STATE_CONNECTED)
                        }
                    }
                }
                ContextCompat.registerReceiver(
                    context, receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED), ContextCompat.RECEIVER_EXPORTED,
                )
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
                audioManager.startBluetoothSco()
            }
        } ?: false
        if (connected) audioManager.isBluetoothScoOn = true
        return connected
    }

    /** Idempotent. Puts routing and audio mode back so playback goes over A2DP again. */
    fun release() {
        if (!engaged) return
        engaged = false
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
            }
        }.onFailure { Log.w(TAG, "release routing: $it") }
        runCatching { audioManager.mode = previousMode }
    }

    companion object {
        private const val TAG = "Earshot/MicRoute"
        private const val ROUTE_TIMEOUT_MS = 3_000L
        private const val POLL_MS = 100L
    }
}
