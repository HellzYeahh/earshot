package dev.earshot.codec

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import android.util.Log
import dev.earshot.bt.CompanionRemote
import java.lang.reflect.InvocationTargetException

/**
 * The 0.8 experiment from roadmap spike D4: codec read and switch **without Shizuku** for a
 * headset linked through the universal remote. Since Android 13, AOSP's A2DP service lets the
 * caller through `getCodecStatus` and `setCodecConfigPreference` when it holds BLUETOOTH_PRIVILEGED
 * *or* a Companion Device Manager association with that device
 * (`enforceCdmAssociationIfNotBluetoothPrivileged`). Both methods are `@SystemApi` on
 * [BluetoothA2dp]; `hiddenapi-flags.csv` lists them as "sdk,system-api", so calling them by
 * reflection is not blocked and only that runtime check applies (desk answer, D4).
 *
 * Unverified on hardware. An OEM stack may still demand the privileged permission: the call then
 * throws [SecurityException], [works] turns false for the process and Shizuku (GitHub build only)
 * stays the only path. In the Google Play build, which has no Shizuku (B8), this is the only
 * switch Earshot has; Developer options always remains.
 */
internal class AssociationCodecPath(private val context: Context) {
    private val remote by lazy { CompanionRemote(context) }

    /** Null until tried on this process; then whether the stack accepted the association. */
    @Volatile
    var works: Boolean? = null
        private set

    /** Worth trying for [address]: Android 13+, the headset is linked, and no refusal yet. */
    fun available(address: String): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && works != false && remote.isLinked(address)

    /** `BluetoothCodecStatus.toString()` for [device], or null when the call isn't allowed or answers nothing. */
    fun readStatus(a2dp: BluetoothA2dp, device: BluetoothDevice): String? = call("getCodecStatus") {
        BluetoothA2dp::class.java.getMethod("getCodecStatus", BluetoothDevice::class.java).invoke(a2dp, device)?.toString()
    }

    /** Ask the stack for [request] on [device]; true when the call went through (the caller verifies the result). */
    @SuppressLint("MissingPermission") // the privileged-only type list is optional: refused, the config uses the numeric type
    fun select(a2dp: BluetoothA2dp, device: BluetoothDevice, request: CodecRequest): Boolean = call("setCodecConfigPreference") {
        val config = CodecConfigFactory.build(request, TAG) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                runCatching { a2dp.supportedCodecTypes.toList() }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
        }
        val configClass = Class.forName("android.bluetooth.BluetoothCodecConfig")
        BluetoothA2dp::class.java.getMethod("setCodecConfigPreference", BluetoothDevice::class.java, configClass).invoke(a2dp, device, config)
        true
    } ?: false

    private fun <T> call(what: String, block: () -> T?): T? = try {
        block().also { if (works == null) Log.i(TAG, "$what through the companion link: accepted") }.also { works = true }
    } catch (e: InvocationTargetException) {
        val cause = e.targetException ?: e
        if (cause is SecurityException) {
            refused(what, cause)
        } else {
            // A stack hiccup (no service bound yet, say) says nothing about the permission check.
            Log.w(TAG, "$what through the companion link failed: ${cause.javaClass.simpleName}")
            null
        }
    } catch (e: SecurityException) {
        refused(what, e)
    } catch (e: ReflectiveOperationException) {
        // The method isn't there on this build: nothing to try again.
        refused(what, e)
    }

    private fun <T> refused(what: String, t: Throwable): T? {
        works = false
        Log.i(TAG, "$what through the companion link refused (${t.javaClass.simpleName}); off for this process")
        return null
    }

    private companion object {
        const val TAG = "Earshot/Codec"
    }
}
