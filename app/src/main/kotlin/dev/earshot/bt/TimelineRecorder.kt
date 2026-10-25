package dev.earshot.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dev.earshot.codec.CodecStatusText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Records the diagnostics timeline (roadmap F6, item 5) into [store], for every headset, whether
 * or not Earshot drives it. Two doors, so nothing is recorded twice:
 *
 * - **Link, A2DP and LE Audio** arrive only through [onLinkBroadcast], called by the manifest
 *   receiver (`service.BluetoothConnectionReceiver`), which Android delivers whether or not the
 *   process is alive. A receiver registered here would miss the broadcast that started the process.
 * - **HFP, pairing, key missing, encryption and the music codec** arrive through the receiver
 *   [start] registers, so they are recorded while Earshot runs (its foreground service usually does
 *   while a headset is connected). The timeline screen says events may be missing otherwise.
 *
 * The call codec comes from `BluetoothHeadset.getCodecType` (Android 17.2), read a few seconds
 * after HFP connects and when call audio starts. Cars and watches are left out ([AudioClass]).
 * Addresses only: names are looked up for display, never stored.
 */
class TimelineRecorder(
    private val context: Context,
    val store: TimelineStore,
    private val scope: CoroutineScope,
    private val prefs: DevicePrefs,
) {
    private val serial = Dispatchers.Default.limitedParallelism(1)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            handle(intent)
        }
    }

    @Volatile
    private var started = false

    /** Load the file and listen; idempotent. Called from `EarshotApp.onCreate`. */
    fun start() {
        if (started) return
        started = true
        store.load()
        val filter = IntentFilter().apply {
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(ACTION_KEY_MISSING)
            addAction(ACTION_ENCRYPTION_CHANGE)
            addAction(ACTION_CODEC_CONFIG_CHANGED)
        }
        // Protected system broadcasts, as in ConnectionManager.
        ContextCompat.registerReceiver(context.applicationContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    /**
     * ACL, A2DP and LE Audio, from the manifest receiver. The returned job finishes once the event
     * is on disk (or was dropped), so a receiver that cold-started the process can wait for it.
     */
    fun onLinkBroadcast(intent: Intent): Job? = handle(intent)

    // The Android 17 reason extras are plain strings; older versions just don't send them.
    @SuppressLint("InlinedApi")
    private fun handle(intent: Intent): Job? {
        val device = deviceFrom(intent) ?: return null
        val now = System.currentTimeMillis()
        val event: TimelineEvent = when (intent.action) {
            // LE links come and go under classic audio (Earshot's own battery read opens one), so
            // only the classic link counts as the headset connecting or leaving.
            BluetoothDevice.ACTION_ACL_CONNECTED ->
                if (isLeTransport(intent)) return null else TimelineEvent(now, device.address, TimelineKind.LINK_UP)
            BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                if (isLeTransport(intent)) return null else TimelineEvent(now, device.address, TimelineKind.LINK_DOWN)
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> profile(now, device, intent, "A2DP", BluetoothA2dp.EXTRA_DISCONNECTED_REASON) ?: return null
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                val e = profile(now, device, intent, "HFP", BluetoothHeadset.EXTRA_DISCONNECTED_REASON) ?: return null
                if (e.kind == TimelineKind.PROFILE_UP) scheduleCallCodec(device, CALL_CODEC_SETTLE_MS)
                e
            }
            DeviceFinder.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED -> profile(now, device, intent, "LE Audio", reasonExtra = null) ?: return null
            BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED -> {
                // Call audio (SCO) up: the codec for this call is settled by now.
                if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothHeadset.STATE_AUDIO_CONNECTED) scheduleCallCodec(device, 0)
                return null
            }
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                BluetoothDevice.BOND_BONDED -> TimelineEvent(now, device.address, TimelineKind.BONDED)
                BluetoothDevice.BOND_NONE ->
                    if (intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1) == BluetoothDevice.BOND_BONDED) {
                        TimelineEvent(now, device.address, TimelineKind.UNBONDED)
                    } else {
                        return null
                    }
                else -> return null
            }
            ACTION_KEY_MISSING -> {
                val reason = intent.getIntExtra(EXTRA_BOND_LOSS_REASON, -1)
                TimelineEvent(now, device.address, TimelineKind.KEY_MISSING, if (reason < 0) "" else BtTimeline.bondLossReason(reason))
            }
            ACTION_ENCRYPTION_CHANGE -> TimelineEvent(
                now, device.address, TimelineKind.ENCRYPTION,
                BtTimeline.encryption(
                    enabled = intent.getBooleanExtra(EXTRA_ENCRYPTION_ENABLED, true),
                    keySize = intent.getIntExtra(EXTRA_KEY_SIZE, -1),
                    algorithm = intent.getIntExtra(EXTRA_ENCRYPTION_ALGORITHM, -1),
                ),
            )
            ACTION_CODEC_CONFIG_CHANGED -> codec(now, device, intent) ?: return null
            else -> return null
        }
        return record(device, event)
    }

    // EXTRA_TRANSPORT is a plain string (Android 13); without it the link counts as classic.
    @SuppressLint("InlinedApi")
    private fun isLeTransport(intent: Intent): Boolean =
        intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_AUTO) == BluetoothDevice.TRANSPORT_LE

    private fun profile(now: Long, device: BluetoothDevice, intent: Intent, name: String, reasonExtra: String?): TimelineEvent? =
        when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
            BluetoothProfile.STATE_CONNECTED -> TimelineEvent(now, device.address, TimelineKind.PROFILE_UP, name)
            BluetoothProfile.STATE_DISCONNECTED -> {
                // Android 17 says why a connection attempt failed; absent on older versions.
                val reason = reasonExtra?.let { intent.getIntExtra(it, BtTimeline.NO_CODE) } ?: BtTimeline.NO_CODE
                val why = BtTimeline.disconnectReason(reason)
                TimelineEvent(now, device.address, TimelineKind.PROFILE_DOWN, if (why == null) name else "$name, $why")
            }
            else -> null
        }

    private fun codec(now: Long, device: BluetoothDevice, intent: Intent): TimelineEvent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val status = runCatching { intent.getParcelableExtra(EXTRA_CODEC_STATUS, android.bluetooth.BluetoothCodecStatus::class.java) }.getOrNull() ?: return null
        val current = CodecStatusText.parse(status.toString(), device.address)?.current ?: return null
        val rate = current.sampleRatesHz.singleOrNull()?.let { hz -> if (hz % 1000 == 0) "${hz / 1000} kHz" else "${hz / 1000.0} kHz" }
        val detail = listOfNotNull(current.codecName, rate).joinToString(", ")
        return TimelineEvent(now, device.address, TimelineKind.CODEC, detail)
    }

    /** Headsets only: a car kit's or a watch's comings and goings are noise here. */
    @SuppressLint("MissingPermission")
    private suspend fun isHeadset(device: BluetoothDevice): Boolean {
        val deviceClass = runCatching { device.bluetoothClass?.deviceClass }.getOrNull()
        val uuids = runCatching { device.uuids?.map { it.uuid.toString() } }.getOrNull().orEmpty()
        if (AudioClass.isHeadset(deviceClass, uuids)) return true
        val saved = runCatching { prefs.savedDevice()?.address }.getOrNull()
        return saved != null && saved.equals(device.address, ignoreCase = true)
    }

    /** What this recorder wrote in the last seconds; the store writes asynchronously, so repeats are checked here. Serial only. */
    private val recent = ArrayDeque<TimelineEvent>()

    // No LogRing line: the connection report has its own history section, filtered to the saved
    // headphones, and the log would carry other people's headsets into a report posted publicly.
    private fun record(device: BluetoothDevice, event: TimelineEvent): Job = scope.launch(serial) {
        if (!isHeadset(device)) return@launch
        while (recent.isNotEmpty() && event.timeMs - recent.first().timeMs > BtTimeline.REPEAT_WINDOW_MS) recent.removeFirst()
        if (BtTimeline.isRepeat(recent.toList(), event)) return@launch
        if (BtTimeline.isUnchanged(store.events.value + recent, event)) return@launch
        recent.addLast(event)
        store.record(event).await()
    }

    private fun scheduleCallCodec(device: BluetoothDevice, delayMs: Long) {
        if (Build.VERSION.SDK_INT < API_37) return
        scope.launch(serial) {
            if (delayMs > 0) delay(delayMs)
            val label = callCodecLabel(device) ?: return@launch
            record(device, TimelineEvent(System.currentTimeMillis(), device.address, TimelineKind.CALL_CODEC, label))
        }
    }

    /**
     * `BluetoothHeadset.getCodecType` (Android 17.2) by reflection: it is not in the 37.0 SDK this
     * app compiles against. The returned int is named by looking for the `CODEC_TYPE_*` constant
     * with that value, so no constant value is guessed. Null on older builds or when nothing is known.
     */
    @SuppressLint("MissingPermission")
    private suspend fun callCodecLabel(device: BluetoothDevice): String? {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        val method = runCatching { BluetoothHeadset::class.java.getMethod("getCodecType", BluetoothDevice::class.java) }.getOrNull() ?: return null
        val headset = withTimeoutOrNull(PROXY_TIMEOUT_MS) {
            suspendCancellableCoroutine<BluetoothHeadset?> { cont ->
                val listener = object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        val headset = proxy as? BluetoothHeadset
                        // Closed if the timeout cancelled the wait just now, or if it isn't a headset proxy.
                        if (headset == null || !cont.isActive) {
                            runCatching { adapter.closeProfileProxy(profile, proxy) }
                            if (cont.isActive) cont.resume(null)
                        } else {
                            cont.resume(headset) { runCatching { adapter.closeProfileProxy(profile, proxy) } }
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                if (!runCatching { adapter.getProfileProxy(context, listener, BluetoothProfile.HEADSET) }.getOrDefault(false) && cont.isActive) cont.resume(null)
            }
        } ?: return null
        return try {
            val type = method.invoke(headset, device) as? Int ?: return null
            val name = BluetoothHeadset::class.java.fields
                .firstOrNull { it.name.startsWith("CODEC_TYPE_") && runCatching { it.getInt(null) }.getOrNull() == type }?.name
                ?: return null
            BtTimeline.callCodecLabel(name)
        } catch (e: Exception) {
            Log.d(TAG, "getCodecType unavailable: ${e.javaClass.simpleName}")
            null
        } finally {
            runCatching { adapter.closeProfileProxy(BluetoothProfile.HEADSET, headset) }
        }
    }

    private fun deviceFrom(intent: Intent): BluetoothDevice? {
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    private companion object {
        const val TAG = "Earshot/Timeline"
        const val API_37 = 37
        const val CALL_CODEC_SETTLE_MS = 4_000L
        const val PROXY_TIMEOUT_MS = 1_500L

        // Android 16 / 16.1 and 13 names, as literal strings so older SDK levels resolve them.
        const val ACTION_KEY_MISSING = "android.bluetooth.device.action.KEY_MISSING"
        const val ACTION_ENCRYPTION_CHANGE = "android.bluetooth.device.action.ENCRYPTION_CHANGE"
        const val EXTRA_BOND_LOSS_REASON = "android.bluetooth.device.extra.BOND_LOSS_REASON"
        const val EXTRA_ENCRYPTION_ENABLED = "android.bluetooth.device.extra.ENCRYPTION_ENABLED"
        const val EXTRA_KEY_SIZE = "android.bluetooth.device.extra.KEY_SIZE"
        const val EXTRA_ENCRYPTION_ALGORITHM = "android.bluetooth.device.extra.EXTRA_ENCRYPTION_ALGORITHM"
        const val ACTION_CODEC_CONFIG_CHANGED = "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED"
        const val EXTRA_CODEC_STATUS = "android.bluetooth.extra.CODEC_STATUS"
    }
}
