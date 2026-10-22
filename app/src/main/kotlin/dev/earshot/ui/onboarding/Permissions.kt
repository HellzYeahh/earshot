package dev.earshot.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** Runtime permissions the UI asks for. Everything is version-gated here, nowhere else. */
object Permissions {
    val bluetoothConnect: String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else null

    /**
     * For the AirPods / Beats per-bud battery (BLE adverts). Same "Nearby devices" group as
     * [bluetoothConnect], so once that is granted Android grants this without showing a dialog.
     */
    val bluetoothScan: String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN else null

    val postNotifications: String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null

    const val RECORD_AUDIO = Manifest.permission.RECORD_AUDIO

    fun has(context: Context, permission: String?): Boolean =
        permission == null || ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Call right after a request came back denied. Android stops showing the dialog once the user
     * has refused twice (or ticked "don't ask again"); `shouldShowRequestPermissionRationale` is
     * then false, and only the app's settings page can grant it.
     */
    fun isPermanentlyDenied(context: Context, permission: String?): Boolean {
        if (permission == null || has(context, permission)) return false
        val activity = context.findActivity() ?: return false
        return !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }

    /** Opens this app's page in system settings, where a permanently denied permission can be granted. */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
