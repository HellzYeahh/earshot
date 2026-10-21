package dev.earshot.shortcut

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import dev.earshot.bt.AudioClass
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Android side of [HeadsetResolution]: reads the intent, the active SCO output and the HFP proxy. */
class HeadsetLookup(private val context: Context) {

    suspend fun resolve(trigger: Intent?, savedAddress: String?): ResolvedHeadset? {
        val fromIntent = trigger?.extras?.let(::deviceAddressIn)
        val active = if (fromIntent == null) activeHfpAddress() else null
        val connected = if (fromIntent == null && active == null) connectedHfpAddresses() else emptyList()
        return HeadsetResolution.resolve(
            intentAddress = fromIntent,
            activeHfpAddress = active,
            connectedHfp = connected,
            savedAddress = savedAddress,
        )
    }

    /** A wired or USB headset (with or without mic) is plugged in: the source of `VOICE_SEARCH_HANDS_FREE`. */
    fun wiredHeadsetPresent(): Boolean {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        return runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in WIRED_TYPES } }.getOrDefault(false)
    }

    /** True when at least one HFP headset is connected (null: could not tell, e.g. no permission). */
    suspend fun anyHfpConnected(): Boolean? =
        if (!hasConnectPermission()) null else connectedHfpAddresses().isNotEmpty()

    /** [address] is a connected HFP headset. An address from the trigger's extras is only a claim until this says so. */
    suspend fun isHfpConnected(address: String): Boolean =
        connectedHfpAddresses().any { it.equals(address, ignoreCase = true) }

    /**
     * The headset a press would come from right now, for the remapper's header: the active HFP
     * device (the one with the SCO route), else the first connected one. Null with none or
     * without the Nearby devices permission.
     */
    suspend fun currentHeadset(): HfpHeadset? {
        val connected = connectedHfpDevices()
        val active = activeHfpAddress()
        return connected.firstOrNull { it.address.equals(active, ignoreCase = true) } ?: connected.firstOrNull()
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /**
     * The `VOICE_COMMAND` from the Bluetooth stack arrives "(has extras)". AOSP documents none, so
     * besides `BluetoothDevice.EXTRA_DEVICE` this looks at every extra for a [BluetoothDevice] or a
     * string that is a Bluetooth address. Unknown parcelables can throw while unparcelling: each
     * key is tried on its own.
     */
    @Suppress("DEPRECATION")
    private fun deviceAddressIn(extras: Bundle): String? {
        val direct = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                extras.getParcelable<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            }
        }.getOrNull()
        if (direct != null) return direct.address
        for (key in runCatching { extras.keySet().toList() }.getOrDefault(emptyList())) {
            when (val value = runCatching { extras.get(key) }.getOrNull()) {
                is BluetoothDevice -> return value.address
                is String -> if (HeadsetResolution.isAddress(value)) return value
            }
        }
        return null
    }

    private suspend fun connectedHfpAddresses(): List<String> = connectedHfpDevices().map { it.address }

    @SuppressLint("MissingPermission") // checked in hasConnectPermission()
    private suspend fun connectedHfpDevices(): List<HfpHeadset> {
        if (!hasConnectPermission()) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        return withTimeoutOrNull(PROXY_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val listener = object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        val list = try {
                            proxy.connectedDevices
                                // A watch or a car on HFP is not the headset whose button gets remapped.
                                .filter { d ->
                                    val btClass = runCatching { d.bluetoothClass }.getOrNull()
                                    val uuids = runCatching { d.uuids?.map { it.uuid.toString() } }.getOrNull().orEmpty()
                                    AudioClass.isAudioPeer(btClass?.majorDeviceClass, btClass?.deviceClass, uuids)
                                }
                                .map { HfpHeadset(it.address, runCatching { it.name }.getOrNull()) }
                        } catch (e: SecurityException) {
                            emptyList()
                        }
                        runCatching { adapter.closeProfileProxy(profile, proxy) }
                        if (cont.isActive) cont.resume(list)
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        if (cont.isActive) cont.resume(emptyList())
                    }
                }
                val started = runCatching { adapter.getProfileProxy(context, listener, BluetoothProfile.HEADSET) }.getOrDefault(false)
                if (!started && cont.isActive) cont.resume(emptyList())
            }
        } ?: emptyList<HfpHeadset>().also { Log.w(TAG, "HFP proxy did not answer in time") }
    }

    /**
     * The active HFP device: Android lists exactly one `TYPE_BLUETOOTH_SCO` output, the active
     * headset, and the Bluetooth stack makes the presser active right before it sends the intent.
     */
    private fun activeHfpAddress(): String? {
        val am = context.getSystemService(AudioManager::class.java) ?: return null
        return runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }?.address
        }.getOrNull()?.takeIf(HeadsetResolution::isAddress)
    }

    private companion object {
        const val TAG = "Earshot/Shortcut"
        const val PROXY_TIMEOUT_MS = 1_500L
        val WIRED_TYPES = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET)
    }
}

/** A connected HFP headset; [name] is its Bluetooth name (null when Android won't say). */
data class HfpHeadset(val address: String, val name: String?)
