package dev.earshot.codec

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dev.earshot.core.PrivilegeState
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * Translates Shizuku's state into [PrivilegeState] and runs the permission dialog.
 *
 * Shizuku delivers its binder to us asynchronously through the `ShizukuProvider` declared in the
 * manifest, so right after process start [Shizuku.pingBinder] is false even when Shizuku is up;
 * callers re-evaluate on [Shizuku.addBinderReceivedListenerSticky].
 */
internal object ShizukuPrivilege {
    private const val TAG = "Earshot/Shizuku"

    /** Shizuku manager app id on Play / GitHub. Sui (Magisk module) provides the binder without this package. */
    const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"

    fun current(context: Context): PrivilegeState = when {
        pingSafely() -> {
            when {
                // Shizuku < 11 used a normal runtime permission; nobody runs it any more and the
                // flow differs, so treat it as not usable rather than half-support it.
                Shizuku.isPreV11() -> PrivilegeState.UNAVAILABLE
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> PrivilegeState.GRANTED
                else -> PrivilegeState.SHIZUKU_PERMISSION_NEEDED
            }
        }
        isManagerInstalled(context) -> PrivilegeState.SHIZUKU_NOT_RUNNING
        else -> PrivilegeState.UNAVAILABLE
    }

    fun isManagerInstalled(context: Context): Boolean = try {
        // QUERY_ALL_PACKAGES is in the manifest (needed elsewhere), so this is visible on Android 11+.
        context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Shows Shizuku's permission dialog and suspends until the user answers. True when granted. */
    suspend fun request(requestCode: Int): Boolean {
        if (!pingSafely() || Shizuku.isPreV11()) return false
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true
        return suspendCancellableCoroutine { cont ->
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(code: Int, grantResult: Int) {
                    if (code != requestCode) return
                    Shizuku.removeRequestPermissionResultListener(this)
                    if (cont.isActive) cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            cont.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
            try {
                Shizuku.requestPermission(requestCode)
            } catch (t: Throwable) {
                Log.w(TAG, "requestPermission failed: $t")
                Shizuku.removeRequestPermissionResultListener(listener)
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    private fun pingSafely(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
}
