package dev.earshot.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import dev.earshot.core.HeadphoneCommandException
import java.util.concurrent.Executor

/**
 * What a Companion Device Manager link lets Earshot do on this Android version, for any brand
 * (research "Universal headset control accelerators"): rename the headset on the phone
 * (`setAlias`, Android 12; its `int` result from 13), unpair it (`removeBond`, Android 16) and
 * connect / disconnect all its profiles (`BluetoothDevice.connect()`, Android 17, "CDM or
 * BLUETOOTH_PRIVILEGED"). Pure, so the gating is unit-tested.
 */
data class RemoteAbilities(val canLink: Boolean, val canRename: Boolean, val canForget: Boolean, val canConnect: Boolean) {
    companion object {
        /** Android 17 (API 37) has no VERSION_CODES name in every SDK yet. */
        const val API_CONNECT = 37

        fun forSdk(sdk: Int, hasCompanionFeature: Boolean): RemoteAbilities = RemoteAbilities(
            canLink = hasCompanionFeature,
            canRename = hasCompanionFeature && sdk >= Build.VERSION_CODES.TIRAMISU,
            canForget = hasCompanionFeature && sdk >= Build.VERSION_CODES.BAKLAVA,
            canConnect = hasCompanionFeature && sdk >= API_CONNECT,
        )
    }
}

/**
 * The "universal remote": one Companion Device Manager association per headset (a single-device
 * request filtered by its MAC, so an already-paired headset costs one system dialog and no
 * re-pairing), then connect, disconnect, rename and forget work for any headset, with or without
 * a vendor driver. Every call needs the association; without it the methods throw a
 * [HeadphoneCommandException] the UI shows as a message.
 */
class CompanionRemote(private val context: Context) {
    private val cdm: CompanionDeviceManager? get() = context.getSystemService(CompanionDeviceManager::class.java)

    val abilities: RemoteAbilities = RemoteAbilities.forSdk(
        Build.VERSION.SDK_INT,
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP),
    )

    fun isLinked(address: String): Boolean = associationId(address) != null || legacyLinked(address)

    /** The system-dialog request for [address]: one device, matched by its MAC, already paired or not. */
    fun linkRequest(address: String, name: String?): AssociationRequest {
        val filter = BluetoothDeviceFilter.Builder().setAddress(address.uppercase()).build()
        return AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(true)
            .apply { if (name != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setDisplayName(name) }
            .build()
    }

    /**
     * Asks Android to link [address]; [onDialog] gets the system dialog's IntentSender to launch.
     * [onDone] runs with null on success or an error message (also when the dialog never shows).
     */
    fun link(address: String, name: String?, executor: Executor, onDialog: (IntentSender) -> Unit, onDone: (String?) -> Unit) {
        val manager = cdm ?: run {
            onDone("Companion devices aren't available on this phone.")
            return
        }
        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = onDialog(intentSender)

            @Deprecated("Android 12 and older")
            override fun onDeviceFound(intentSender: IntentSender) = onDialog(intentSender)

            override fun onAssociationCreated(associationInfo: AssociationInfo) = onDone(null)

            override fun onFailure(error: CharSequence?) = onDone(error?.toString() ?: "")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.associate(linkRequest(address, name), executor, callback)
            } else {
                @Suppress("DEPRECATION")
                manager.associate(linkRequest(address, name), callback, null)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "associate failed: ${e.javaClass.simpleName}")
            onDone(e.message ?: "")
        }
    }

    /** Removes the link (the headset stays paired). */
    fun unlink(address: String) {
        val manager = cdm ?: return
        val id = associationId(address)
        runCatching {
            // The id comes from AssociationInfo (Android 13+); older versions only know the MAC.
            if (id != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.disassociate(id)
            } else {
                @Suppress("DEPRECATION")
                manager.disassociate(address.uppercase())
            }
        }.onFailure { Log.w(TAG, "disassociate failed: ${it.javaClass.simpleName}") }
    }

    /** Pages the headset and connects all its profiles (Android 17). */
    @SuppressLint("MissingPermission", "NewApi") // BLUETOOTH_CONNECT is held; the SDK check is in requireAbility
    fun connect(address: String) {
        requireAbility(abilities.canConnect, address)
        status("connect", device(address).connect())
    }

    /** Disconnects all of the headset's profiles from this phone (Android 17). It stays paired. */
    @SuppressLint("MissingPermission", "NewApi")
    fun disconnect(address: String) {
        requireAbility(abilities.canConnect, address)
        status("disconnect", device(address).disconnect())
    }

    /** The name this phone shows for the headset (the headset's own name is unchanged). Android 13+. */
    @SuppressLint("MissingPermission", "NewApi")
    fun rename(address: String, alias: String) {
        requireAbility(abilities.canRename, address)
        status("setAlias", device(address).setAlias(alias.trim().ifEmpty { null }))
    }

    /** Unpairs the headset (Android 16). Linking it again later needs a new pairing. */
    @SuppressLint("NewApi") // the SDK check is in requireAbility
    fun forget(address: String) {
        requireAbility(abilities.canForget, address)
        val id = associationId(address) ?: throw notLinked()
        val removed = try {
            cdm?.removeBond(id) == true
        } catch (e: SecurityException) {
            throw HeadphoneCommandException("Android didn't allow Earshot to unpair the headset (Nearby devices permission).")
        }
        if (!removed) throw HeadphoneCommandException("Android didn't unpair the headset. Try again from Bluetooth settings.")
    }

    /** `getAlias` is public from Android 11; a SecurityException (no Nearby devices permission) is caught. */
    @SuppressLint("MissingPermission")
    fun aliasOf(address: String): String? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) null else runCatching { device(address).alias }.getOrNull()

    private fun associationId(address: String): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return runCatching {
            cdm?.myAssociations?.firstOrNull { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) }?.id
        }.getOrNull()
    }

    /** Android 12 and older only list the linked MAC addresses. */
    private fun legacyLinked(address: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return false
        @Suppress("DEPRECATION")
        return runCatching { cdm?.associations?.any { it.equals(address, ignoreCase = true) } == true }.getOrDefault(false)
    }

    private fun requireAbility(able: Boolean, address: String) {
        if (!able) throw HeadphoneCommandException("This needs a newer Android version.")
        if (!isLinked(address)) throw notLinked()
    }

    private fun notLinked() = HeadphoneCommandException("Link Earshot to this headset first.")

    private fun device(address: String) =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address.uppercase())
            ?: throw HeadphoneCommandException("Bluetooth is off.")

    /** `BluetoothStatusCodes.SUCCESS` is 0; anything else is shown with its code for the report. */
    private fun status(what: String, code: Int) {
        Log.i(TAG, "$what → $code")
        if (code != 0) throw HeadphoneCommandException("Android refused ($what, code $code).")
    }

    private companion object {
        const val TAG = "Earshot/Remote"
    }
}
