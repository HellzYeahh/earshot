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
    /**
     * True when the name says Bose (or Skullcandy Crusher): a product Earshot has a stack for.
     * Since 0.6 by [DeviceFamily.forName] ("QCY" earbuds and "… Ultra" no longer count).
     */
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
 * Finds the headset among the phone's bonded devices. No scanning: it must be paired in system
 * settings first, same as every other headset tool.
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
            .let(::mergeLeTwins)
            .sortedWith(compareByDescending<DeviceCandidate> { it.isSaved }
                .thenByDescending { it.looksLikeBose }
                .thenByDescending { it.isAudioDevice }
                .thenBy { it.name ?: "" })
    }

    /**
     * Pick the headphones without asking, by [DevicePick.choose]: a headset connected to the
     * phone right now wins (the saved one first), so opening Earshot with AirPods on shows the
     * AirPods instead of waiting for the Bose saved last week. With nothing connected: the saved
     * device, else the one Bose-looking device, else the only audio device. Anything more
     * ambiguous is [FindResult.NoDevice] with candidates for the UI to offer.
     */
    @SuppressLint("MissingPermission")
    suspend fun find(): FindResult {
        if (!hasConnectPermission()) return FindResult.NoPermission
        val adapter = adapter ?: return FindResult.BluetoothOff
        if (!adapter.isEnabled) return FindResult.BluetoothOff
        val candidates = candidates()
        val pick = DevicePick.choose(candidates, connectedCandidates(adapter, candidates), DeviceSelection.pinned)
            ?: return FindResult.NoDevice(candidates)
        val device = deviceFor(pick.address) ?: return FindResult.NoDevice(candidates)
        return FindResult.Found(device, pick)
    }

    /**
     * Addresses (upper case) among [candidates] with an audio profile up right now, counting a
     * candidate whose LE Audio / classic twin is the connected one ([mergeLeTwins] keeps only one
     * of the two in the list).
     */
    @SuppressLint("MissingPermission")
    private suspend fun connectedCandidates(adapter: BluetoothAdapter, candidates: List<DeviceCandidate>): Set<String> {
        val raw = buildSet { for (profile in audioProfiles()) connectedVia(adapter, profile)?.let(::addAll) }
        if (raw.isEmpty()) return emptySet()
        val bonded = try { adapter.bondedDevices.orEmpty() } catch (e: SecurityException) { return emptySet() }
        val names = bonded.associate { it.address to (try { it.name } catch (e: SecurityException) { null }) }
        return candidates.map { it.address.uppercase() }.filterTo(HashSet()) { address ->
            address in raw || LeAudioTwins.twinsOf(address, names).any { it.uppercase() in raw }
        }
    }

    /**
     * One entry per pair of headphones: an "LE-Bose …" identity is dropped when its classic twin
     * is listed too (the link factory reaches the LE side through the twin), unless the LE one is
     * the saved device. The user never sees the same earbuds twice in the picker.
     */
    private fun mergeLeTwins(list: List<DeviceCandidate>): List<DeviceCandidate> {
        val names = list.associate { it.address to it.name }
        return list.filter { c ->
            if (!LeAudioTwins.isLeName(c.name) || c.isSaved) return@filter true
            // Keep the LE identity only when its classic twin is not in the list.
            LeAudioTwins.twinsOf(c.address, names).none { twin -> list.any { it.address.equals(twin, ignoreCase = true) } }
        }
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
     * Is the headset currently connected to the phone for audio (A2DP, HFP or LE Audio, on this
     * address or its LE / classic twin)? Used to decide whether a failed connect is worth
     * retrying: if the headphones are not even linked, retrying is pointless and we wait for
     * ACL_CONNECTED instead. Returns null when no profile proxy could be queried in time (treat
     * as unknown, i.e. keep trying).
     *
     * LE Audio counts since 0.4.0: earbuds on LE Audio only have no A2DP/HFP link, so they were
     * reported as "not connected" and the loop parked for good.
     */
    @SuppressLint("MissingPermission")
    suspend fun isAudioConnected(address: String): Boolean? {
        if (!hasConnectPermission()) return null
        val adapter = adapter ?: return false
        if (!adapter.isEnabled) return false
        val addresses = setOf(address.uppercase()) + twinsOf(address).map { it.address.uppercase() }
        val profiles = audioProfiles()
        var unknown = 0
        for (profile in profiles) {
            when (connectedViaProfile(adapter, profile, addresses)) {
                true -> return true
                null -> unknown++
                false -> Unit
            }
        }
        return if (unknown == profiles.size) null else false
    }

    /** True when the phone is on LE Audio with [address] or its twin right now: the LE channel is then tried first. */
    @SuppressLint("MissingPermission")
    suspend fun isLeAudioConnected(address: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !hasConnectPermission()) return false
        val adapter = adapter ?: return false
        val addresses = setOf(address.uppercase()) + twinsOf(address).map { it.address.uppercase() }
        return connectedViaProfile(adapter, LE_AUDIO_PROFILE, addresses) == true
    }

    /** The other half of an LE Audio / classic pair for [address] among the bonded devices (see [LeAudioTwins]). */
    @SuppressLint("MissingPermission")
    fun twinsOf(address: String): List<BluetoothDevice> {
        val adapter = adapter ?: return emptyList()
        if (!hasConnectPermission()) return emptyList()
        val bonded = try { adapter.bondedDevices.orEmpty() } catch (e: SecurityException) { return emptyList() }
        val names = bonded.associate { it.address to (try { it.name } catch (e: SecurityException) { null }) }
        val twins = LeAudioTwins.twinsOf(address, names)
        return bonded.filter { d -> twins.any { it.equals(d.address, ignoreCase = true) } }
    }

    /** `BluetoothDevice.getType()`, or UNKNOWN when it cannot be read. */
    @SuppressLint("MissingPermission")
    fun typeOf(device: BluetoothDevice): Int = try { device.type } catch (e: SecurityException) { BluetoothDevice.DEVICE_TYPE_UNKNOWN }

    private fun audioProfiles(): List<Int> = buildList {
        add(BluetoothProfile.A2DP)
        add(BluetoothProfile.HEADSET)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(LE_AUDIO_PROFILE)
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectedViaProfile(adapter: BluetoothAdapter, profile: Int, addresses: Set<String>): Boolean? =
        connectedVia(adapter, profile)?.any { it in addresses }

    /** Upper-case addresses connected on [profile], or null when the proxy could not be queried in time. */
    @SuppressLint("MissingPermission")
    private suspend fun connectedVia(adapter: BluetoothAdapter, profile: Int): Set<String>? =
        withTimeoutOrNull(PROFILE_PROXY_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val listener = object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        val result: Set<String>? = try {
                            proxy.connectedDevices.mapTo(HashSet()) { it.address.uppercase() }
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
        // Cached SDP record, no lookup. Only consulted for the hands-free class, see AudioClass.
        val serviceUuids = if (deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE) {
            try { uuids?.map { it.uuid.toString() }.orEmpty() } catch (e: SecurityException) { emptyList() }
        } else emptyList()
        return DeviceCandidate(
            address = address,
            name = name,
            looksLikeBose = DeviceFamily.forName(name) != DeviceFamily.GENERIC,
            isAudioDevice = AudioClass.isHeadset(deviceClass, serviceUuids),
            isSaved = savedAddress != null && address.equals(savedAddress, ignoreCase = true),
        )
    }

    companion object {
        private const val PROFILE_PROXY_TIMEOUT_MS = 1_500L

        /** `BluetoothProfile.LE_AUDIO` (API 33); a literal so the constant is not inlined from a newer SDK unguarded. */
        private const val LE_AUDIO_PROFILE = 22

        /** `BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED` (API 33). */
        const val ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED = "android.bluetooth.action.LE_AUDIO_CONNECTION_STATE_CHANGED"


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

/**
 * Process-wide: the device the user picked explicitly during this run (device switcher,
 * onboarding). It beats "whatever is connected", or picking the Bose while AirPods play would
 * snap straight back to the AirPods. A new process starts unpinned (so launching Earshot shows
 * the connected headset), and another headset linking up clears it (the user just turned that
 * one on).
 */
internal object DeviceSelection {
    @Volatile
    var pinned: String? = null
}

/** The choice behind [DeviceFinder.find], kept free of Android types so it is unit-tested. */
internal object DevicePick {
    /**
     * [candidates] come sorted (saved, Bose/Skullcandy-looking, audio device, name); [connected]
     * holds the upper-case addresses among them with audio up right now.
     *  0. The device the user [pinned] in this run, while it is still bonded.
     *  1. A connected headset: the saved one if it is connected, else the first connected one.
     *  2. Nothing connected: the saved device, else the only Bose-looking one, else the first
     *     Bose-looking audio device, else the only audio device.
     */
    fun choose(candidates: List<DeviceCandidate>, connected: Set<String>, pinned: String? = null): DeviceCandidate? {
        pinned?.let { p -> candidates.firstOrNull { it.address.equals(p, ignoreCase = true) }?.let { return it } }
        val live = candidates.filter { it.address.uppercase() in connected && (it.isAudioDevice || it.looksLikeBose || it.isSaved) }
        return live.firstOrNull { it.isSaved }
            ?: live.firstOrNull()
            ?: candidates.firstOrNull { it.isSaved }
            ?: candidates.filter { it.looksLikeBose }.takeIf { it.size == 1 }?.single()
            ?: candidates.firstOrNull { it.looksLikeBose && it.isAudioDevice }
            ?: candidates.filter { it.isAudioDevice }.takeIf { it.size == 1 }?.single()
    }
}

/**
 * Which bonded devices count as a headset (picker, "show the connected one", link-up follow).
 * Headphones and wearable headsets always do. Hands-free is shared by earbuds and car kits:
 * the user's car (Uconnect, class 0x340408) and plenty of cheap earbuds both report it. Car kits
 * also run the phonebook (PBAP client) and message (MAP notification) services to show contacts
 * and texts on the dashboard; headsets never do, so those UUIDs mark a car.
 */
internal object AudioClass {
    private val HEADSET_CLASSES = setOf(
        BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
        BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
    )

    /** MAP notification server (0x1133) and PBAP client (0x112E), base UUID, lower case. */
    private val CAR_KIT_UUIDS = setOf(
        "00001133-0000-1000-8000-00805f9b34fb",
        "0000112e-0000-1000-8000-00805f9b34fb",
    )

    fun isHeadset(deviceClass: Int?, serviceUuids: List<String>): Boolean = when (deviceClass) {
        in HEADSET_CLASSES -> true
        BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> !isCarKit(serviceUuids)
        else -> false
    }

    /**
     * Looser, for devices already connected over HFP: anything filed under audio/video except a
     * car. A watch that takes calls itself (the user's Galaxy Watch8 is an HFP hands-free unit,
     * major class Wearable) or another phone is not a headset, and showed up as one on the
     * Controls screen. An unknown class is kept, as before.
     */
    fun isAudioPeer(majorClass: Int?, deviceClass: Int?, serviceUuids: List<String>): Boolean = when {
        majorClass == null -> true
        majorClass != BluetoothClass.Device.Major.AUDIO_VIDEO -> false
        deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> false
        deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> !isCarKit(serviceUuids)
        else -> true
    }

    private fun isCarKit(serviceUuids: List<String>) = serviceUuids.any { it.lowercase() in CAR_KIT_UUIDS }
}
