package dev.earshot.ui.sleep

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.core.SleepPermissions
import dev.earshot.ui.common.BannerTone
import dev.earshot.ui.common.InfoBanner

/** The special accesses the Sleep tab may send the user to grant. */
enum class SleepSettingsPage { EXACT_ALARMS, FULL_SCREEN_ALARM, NOTIFICATIONS, DO_NOT_DISTURB }

object SleepSettings {
    /**
     * The Settings page for [page]. Exact alarms became a special access in Android 12 and the
     * full-screen alarm one in Android 14; below those they're granted at install, so the
     * repository never reports them missing and the app details page is only a fallback.
     */
    fun intentFor(context: Context, page: SleepSettingsPage): Intent? {
        val pkg = Uri.fromParts("package", context.packageName, null)
        return when (page) {
            SleepSettingsPage.EXACT_ALARMS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg) else null
            SleepSettingsPage.FULL_SCREEN_ALARM ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg) else null
            SleepSettingsPage.NOTIFICATIONS ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            SleepSettingsPage.DO_NOT_DISTURB -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        }
    }

    /** Opens [page]; a phone without that screen (some OEM builds) gets the app's details page. */
    fun open(context: Context, page: SleepSettingsPage) {
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        val intent = intentFor(context, page) ?: details
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .recoverCatching { context.startActivity(details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/**
 * One banner per missing access, each saying what breaks without it and opening the page that
 * grants it. Do Not Disturb access is asked for on its own row, only when that extra is wanted.
 */
@Composable
fun SleepPermissionBanners(permissions: SleepPermissions, onOpen: (SleepSettingsPage) -> Unit, modifier: Modifier = Modifier) {
    if (permissions.exactAlarms && permissions.fullScreenAlarm && permissions.notifications) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!permissions.exactAlarms) {
            InfoBanner(
                stringResource(R.string.sleep_perm_exact),
                tone = BannerTone.WARNING,
                icon = Icons.Default.Alarm,
                action = { TextButton(onClick = { onOpen(SleepSettingsPage.EXACT_ALARMS) }) { Text(stringResource(R.string.sleep_perm_exact_action)) } },
            )
        }
        if (!permissions.fullScreenAlarm) {
            InfoBanner(
                stringResource(R.string.sleep_perm_full_screen),
                tone = BannerTone.WARNING,
                icon = Icons.Default.ScreenLockPortrait,
                action = { TextButton(onClick = { onOpen(SleepSettingsPage.FULL_SCREEN_ALARM) }) { Text(stringResource(R.string.sleep_perm_full_screen_action)) } },
            )
        }
        if (!permissions.notifications) {
            InfoBanner(
                stringResource(R.string.sleep_perm_notifications),
                tone = BannerTone.WARNING,
                icon = Icons.Default.Notifications,
                action = { TextButton(onClick = { onOpen(SleepSettingsPage.NOTIFICATIONS) }) { Text(stringResource(R.string.sleep_perm_notifications_action)) } },
            )
        }
    }
}
