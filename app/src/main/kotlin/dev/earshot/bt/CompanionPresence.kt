package dev.earshot.bt

import android.annotation.SuppressLint
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Which presence API this Android version has, for the headsets linked through the universal
 * remote ([CompanionRemote]). Pure, so the gating is unit-tested.
 */
data class PresenceSupport(
    /** `startObservingDevicePresence` exists (Android 12) and the phone has companion devices. */
    val canObserve: Boolean,
    /** Android 16: observing by association id, with typed events (`onDevicePresenceEvent`). */
    val byAssociationId: Boolean,
) {
    companion object {
        fun forSdk(sdk: Int, hasCompanionFeature: Boolean): PresenceSupport = PresenceSupport(
            canObserve = hasCompanionFeature && sdk >= Build.VERSION_CODES.S,
            byAssociationId = hasCompanionFeature && sdk >= Build.VERSION_CODES.BAKLAVA,
        )
    }
}

/**
 * Companion Device Manager presence for the linked headsets (roadmap 0.8, item 3): Android binds
 * `service.HeadsetPresenceService` when a linked headset connects, whether or not Earshot's process
 * is alive, and that bound service may start the foreground service from the background. This is
 * the Play-sanctioned path for what the manifest ACL receiver does; the receiver stays for headsets
 * that were never linked.
 *
 * Observing is idempotent, so [observeAll] runs at every process start and [observe] after each new
 * link. Failures (an association that disappeared, a permission an OEM withholds) are logged and
 * ignored: the ACL receiver still covers the headset.
 */
class CompanionPresence(private val context: Context) {
    private val cdm: CompanionDeviceManager? get() = context.getSystemService(CompanionDeviceManager::class.java)

    val support: PresenceSupport = PresenceSupport.forSdk(
        Build.VERSION.SDK_INT,
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP),
    )

    /** Observe every headset Earshot is linked to. */
    fun observeAll() {
        if (!support.canObserve) return
        linkedAddresses().forEach(::observe)
    }

    @SuppressLint("NewApi", "MissingPermission") // gated by support; the permission is declared
    fun observe(address: String) {
        if (!support.canObserve) return
        val manager = cdm ?: return
        runCatching {
            if (support.byAssociationId) {
                val id = associationId(address) ?: return
                manager.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(id).build())
            } else {
                @Suppress("DEPRECATION")
                manager.startObservingDevicePresence(address.uppercase())
            }
            Log.i(TAG, "observing presence of $address")
        }.onFailure { Log.w(TAG, "presence for $address not observed: ${it.javaClass.simpleName}") }
    }

    @SuppressLint("NewApi", "MissingPermission")
    fun stop(address: String) {
        if (!support.canObserve) return
        val manager = cdm ?: return
        runCatching {
            if (support.byAssociationId) {
                val id = associationId(address) ?: return
                manager.stopObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(id).build())
            } else {
                @Suppress("DEPRECATION")
                manager.stopObservingDevicePresence(address.uppercase())
            }
        }.onFailure { Log.w(TAG, "presence for $address not stopped: ${it.javaClass.simpleName}") }
    }

    /** The MAC address behind an association id (Android 13+), for the presence events that carry only the id. */
    fun addressOf(associationId: Int): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return runCatching { cdm?.myAssociations?.firstOrNull { it.id == associationId }?.deviceMacAddress?.toString()?.uppercase() }.getOrNull()
    }

    private fun associationId(address: String): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return runCatching {
            cdm?.myAssociations?.firstOrNull { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) }?.id
        }.getOrNull()
    }

    /** Upper-case MAC addresses of every headset linked to Earshot. */
    fun linkedAddresses(): List<String> {
        val manager = cdm ?: return emptyList()
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.myAssociations.mapNotNull { it.deviceMacAddress?.toString()?.uppercase() }
            } else {
                @Suppress("DEPRECATION")
                manager.associations.map { it.uppercase() }
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val TAG = "Earshot/Presence"
    }
}
