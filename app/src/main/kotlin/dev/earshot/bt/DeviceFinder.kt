package dev.earshot.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** A bonded device that might be the headphones, for the picker in the UI. */
data class DeviceCandidate(
    val address: String,
    val name: String?,
    /** True when the name says Bose / QC / QuietComfort / Ultra. */
    val looksLikeBose: Boolean,
    /** True when the Bluetooth class is a headphone / headset / hands-free. */
    val isAudioDevice: Boolean,
    val isSaved: Boolean,
)

sealed interface FindResult {
    data object NoPermission : FindResult
    data object BluetoothOff : FindResult
    /** Nothing bonded that could be the headphones. [candidates] lets the UI offer a picker. */
    data class NoDevice(val candidates: List<DeviceCandidate>) : FindResult
    data class Found(val device: BluetoothDevice, val candidate: DeviceCandidate) : FindResult
}

/**
 * Finds the QC Ultra among the phone's bonded devices. No scanning: the headphones must be
 * paired in system settings first, same as every other Bose tool.
 */
class DeviceFinder(private val context: Context, private val prefs: DevicePrefs) {

    val adapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    /** BLUETOOTH_CONNECT is a runtime permission on API 31+; below that the manifest entry suffices. */
    fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    /** Every bonded device that could plausibly be the headphones, best match first. */
    @SuppressLint("MissingPermission")
    suspend fun candidates(): List<DeviceCandidate> {
        if (!hasConnectPermission()) return emptyList()
        val adapter = adapter ?: return emptyList()
        val saved = prefs.savedDevice()?.address
        val bonded = try { adapter.bondedDevices.orEmpty() } catch (e: SecurityException) { return emptyList() }
        return bonded
            .map { it.toCandidate(saved) }
            .filter { it.looksLikeBose || it.isAudioDevice || it.isSaved }
            .sortedWith(compareByDescending<DeviceCandidate> { it.isSaved }
                .thenByDescending { it.looksLikeBose }
                .thenByDescending { it.isAudioDevice }
                .thenBy { it.name ?: "" })
    }

    /**
     * Pick the headphones without asking: the saved device if still bonded, else the one
     * bonded device whose name says Bose, else the only audio device. Anything more ambiguous
     * is [FindResult.NoDevice] with candidates for the UI to offer.
     */
    @SuppressLint("MissingPermission")
    suspend fun find(): FindResult {
        if (!hasConnectPermission()) return FindResult.NoPermission
        val adapter = adapter ?: return FindResult.BluetoothOff
        if (!adapter.isEnabled) return FindResult.BluetoothOff
        val candidates = candidates()
        val pick = candidates.firstOrNull { it.isSaved }
            ?: candidates.filter { it.looksLikeBose }.takeIf { it.size == 1 }?.single()
            ?: candidates.filter { it.looksLikeBose && it.isAudioDevice }.firstOrNull()
            ?: candidates.filter { it.isAudioDevice }.takeIf { it.size == 1 }?.single()
            ?: return FindResult.NoDevice(candidates)
        val device = deviceFor(pick.address) ?: return FindResult.NoDevice(candidates)
        return FindResult.Found(device, pick)
    }

    /** The bonded device with [address], or null if it is not bonded (any more). */
    @SuppressLint("MissingPermission")
    fun deviceFor(address: String): BluetoothDevice? {
        val adapter = adapter ?: return null
        if (!hasConnectPermission()) return null
        return try {
            adapter.bondedDevices.orEmpty().firstOrNull { it.address.equals(address, ignoreCase = true) }
        } catch (e: SecurityException) {
            null
        }
    }

    /**
     * Is the headset currently connected to the phone for audio (A2DP or HFP)? Used to decide
     * whether a failed RFCOMM connect is worth retrying: if the headphones are not even linked,
     * retrying is pointless and we wait for ACL_CONNECTED instead. Returns null when the profile
     * proxy could not be queried in time (treat as unknown, i.e. keep trying).
     */
    @SuppressLint("MissingPermission")
    suspend fun isAudioConnected(address: String): Boolean? {
        if (!hasConnectPermission()) return null
        val adapter = adapter ?: return false
        if (!adapter.isEnabled) return false
        val a2dp = connectedViaProfile(adapter, BluetoothProfile.A2DP, address)
        if (a2dp == true) return true
        val hfp = connectedViaProfile(adapter, BluetoothProfile.HEADSET, address)
        if (hfp == true) return true
        return if (a2dp == null && hfp == null) null else false
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectedViaProfile(adapter: BluetoothAdapter, profile: Int, address: String): Boolean? =
        withTimeoutOrNull(PROFILE_PROXY_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val listener = object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        val result = try {
                            proxy.connectedDevices.any { it.address.equals(address, ignoreCase = true) }
                        } catch (e: SecurityException) {
                            null
                        }
                        adapter.closeProfileProxy(p, proxy)
                        if (cont.isActive) cont.resume(result)
                    }

                    override fun onServiceDisconnected(p: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                val started = try {
                    adapter.getProfileProxy(context, listener, profile)
                } catch (e: Exception) {
                    false
                }
                if (!started && cont.isActive) cont.resume(null)
            }
        }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.toCandidate(savedAddress: String?): DeviceCandidate {
        val name = try { name } catch (e: SecurityException) { null }
        val deviceClass = try { bluetoothClass?.deviceClass } catch (e: SecurityException) { null }
        return DeviceCandidate(
            address = address,
            name = name,
            looksLikeBose = name != null && NAME_HINTS.any { name.contains(it, ignoreCase = true) },
            isAudioDevice = deviceClass in AUDIO_CLASSES,
            isSaved = savedAddress != null && address.equals(savedAddress, ignoreCase = true),
        )
    }

    companion object {
        private const val PROFILE_PROXY_TIMEOUT_MS = 1_500L

        /**
         * The headphones advertise as "Bose QC Ultra Headphones" unless renamed in the Bose app; the
         * Skullcandy Crusher 1080 ANC (Sound by Bose) as "Skullcandy CRUSHER 1080 ANC".
         */
        private val NAME_HINTS = listOf("Bose", "QC", "QuietComfort", "Ultra", "Skullcandy", "Crusher")

        private val AUDIO_CLASSES = setOf(
            BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
            BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
            BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
        )

        /** Device address extra of ACL / profile broadcasts, null when the intent has none. */
        fun deviceAddressFrom(intent: android.content.Intent): String? {
            @Suppress("DEPRECATION")
            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            return device?.address
        }

        /** True for a profile CONNECTION_STATE_CHANGED broadcast that landed in CONNECTED. */
        fun isProfileConnected(intent: android.content.Intent): Boolean =
            intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED

        @Suppress("unused") // keeps the profile action strings next to the code that handles them
        val PROFILE_ACTIONS = listOf(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED, BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
    }
}
