package dev.earshot.ui.home

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.earshot.R
import dev.earshot.bt.BleScanAccess
import dev.earshot.bt.DeviceFamily
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneState
import dev.earshot.extras.HeadphoneLabels
import dev.earshot.ui.common.BannerTone
import dev.earshot.ui.common.InfoBanner
import dev.earshot.ui.onboarding.Permissions

/**
 * Android 9–11 only (audit R36). There a BLE scan "succeeds" but returns nothing unless the app
 * holds the location permission (precise on 10 and 11, any on 9) and location is on, and nothing
 * says why ([BleScanAccess]). Two things on Home rely on such a scan: the per-bud and case
 * battery of AirPods and Beats earbuds (read from their adverts) and the control connection of a
 * JBL headset that isn't bonded over LE (found by its `<name>-LE` advert). This banner asks for the
 * permission, or sends the user to the location switch, right where that information is missing.
 * Android 12+ has the Nearby devices permission instead, asked at start-up, so it never shows there.
 */
@Composable
fun BleScanBanner(state: HeadphoneState, modifier: Modifier = Modifier) {
    val connection = state.connection
    val earbuds = HeadphoneLabels.isEarbuds(HeadphoneLabels.formFactor(state))
    // DeviceFamily.forName walks the brand tables: once per name and status, not every recomposition.
    val reason = remember(connection.deviceName, connection.status, earbuds) {
        BleScanPrompt.reason(Build.VERSION.SDK_INT, connection.deviceName, connection.status, earbuds)
    } ?: return
    val context = LocalContext.current
    // Bumped whenever the answer may have changed: back from the permission dialog, the app's
    // settings page or Android's location settings, and when location is switched from the shade
    // (which doesn't pause the activity).
    var checks by remember { mutableIntStateOf(0) }
    var settingsOnly by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        checks++
        if (Permissions.has(context, FINE)) settingsOnly = false
        onPauseOrDispose { }
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                checks++
            }
        }
        // A protected system broadcast. The banner only runs on Android 9-11, where the export
        // flag doesn't exist yet; ContextCompat still wants one.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(LocationManager.MODE_CHANGED_ACTION), ContextCompat.RECEIVER_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        checks++
        // An empty result is a request that got no answer (another one was in flight): ask again.
        settingsOnly = result.isNotEmpty() && Permissions.isPermanentlyDenied(context, FINE)
    }
    val need = remember(checks, reason) {
        BleScanPrompt.need(
            Build.VERSION.SDK_INT,
            missing = BleScanAccess.missing(context),
            fineGranted = Permissions.has(context, FINE),
            coarseGranted = Permissions.has(context, COARSE),
        )
    }
    // "Not now" for the battery only (Home works without it); back on the next start of the app.
    var dismissed by rememberSaveable(connection.deviceAddress) { mutableStateOf(false) }
    if (need == null || dismissed) return

    val battery = reason == BleScanPrompt.Reason.BUD_BATTERY
    InfoBanner(
        stringResource(
            when (need) {
                BleScanPrompt.Need.PERMISSION -> if (battery) R.string.ble_scan_permission_battery else R.string.ble_scan_permission_link
                BleScanPrompt.Need.LOCATION_OFF -> if (battery) R.string.ble_scan_location_off_battery else R.string.ble_scan_location_off_link
            },
        ),
        modifier = modifier,
        tone = if (battery) BannerTone.INFO else BannerTone.WARNING,
        action = {
            if (battery) {
                TextButton(onClick = { dismissed = true }) { Text(stringResource(R.string.action_not_now)) }
            }
            when (need) {
                BleScanPrompt.Need.PERMISSION -> TextButton(
                    onClick = { if (settingsOnly) Permissions.openAppSettings(context) else launcher.launch(BleScanPrompt.PERMISSIONS) },
                ) {
                    Text(stringResource(if (settingsOnly) R.string.action_open_app_settings else R.string.action_allow_location))
                }
                BleScanPrompt.Need.LOCATION_OFF -> TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                ) {
                    Text(stringResource(R.string.action_location_settings))
                }
            }
        },
    )
}

private const val FINE = Manifest.permission.ACCESS_FINE_LOCATION
private const val COARSE = Manifest.permission.ACCESS_COARSE_LOCATION

/** When Home asks for BLE scan access, and for what. Pure so it is unit-tested. */
internal object BleScanPrompt {

    /** What the scan is for on this headset. */
    enum class Reason {
        /** AirPods / Beats earbuds: each bud's and the case's battery come only from their adverts. */
        BUD_BATTERY,

        /** A JBL headset not bonded over LE: its control connection is found by a scan. */
        CONTROL_LINK,
    }

    /** What stands in the way of a scan with results. */
    enum class Need { PERMISSION, LOCATION_OFF }

    /** Both, as on Android 9–11 they are one group and one dialog; coarse alone is enough on 9. */
    val PERMISSIONS = arrayOf(FINE, COARSE)

    /**
     * The generic driver's rule for the headsets that advertise per-bud battery (ui may not import
     * the driver; arch/VendorBoundaryTest).
     */
    private val APPLE_NAME = Regex("""airpods|beats""", RegexOption.IGNORE_CASE)

    /**
     * The over-ear Apple headphones, which report one battery figure through Android anyway. The
     * form factor from the name can't decide this: "Beats Fit Pro", "Powerbeats Pro" and
     * "Beats Flex" match none of its earbud rules and come back as headphones, yet they are buds
     * the generic driver scans for. "Beats Studio Buds" / "Beats Solo Buds" are earbuds.
     */
    private val APPLE_OVER_EAR = Regex("""airpods\s*max|beats\s*(?:solo|studio)(?!\s*buds)""", RegexOption.IGNORE_CASE)

    /** The demo headsets (DisconnectedCard's rule): canned adverts and a virtual link, no scan. */
    private const val DEMO_MARK = "(demo)"

    /**
     * Null when nothing on Home needs a scan for this headset. The battery while AirPods / Beats
     * earbuds are connected (over-ear models, [APPLE_OVER_EAR], unless the form factor says
     * earbuds); the JBL control link while it is down, the time the scan runs (JblLinkFactory:
     * bonded LE devices skip it, which the UI can't see, so the banner may show for one that
     * doesn't need it).
     */
    fun reason(sdk: Int, name: String?, status: ConnectionStatus, earbuds: Boolean): Reason? = when {
        sdk >= Build.VERSION_CODES.S || name == null || name.contains(DEMO_MARK) -> null
        APPLE_NAME.containsMatchIn(name) -> Reason.BUD_BATTERY.takeIf {
            status == ConnectionStatus.CONNECTED && (earbuds || !APPLE_OVER_EAR.containsMatchIn(name))
        }
        DeviceFamily.forName(name) == DeviceFamily.JBL ->
            Reason.CONTROL_LINK.takeIf { status == ConnectionStatus.DISCONNECTED || status == ConnectionStatus.CONNECTING }
        else -> null
    }

    /**
     * [missing] is [BleScanAccess.missing]'s answer (null = a scan sees results). Missing while
     * the permission is held means location is off; the permission rule is BleScanAccess's.
     * Android 12+ is the start-up Nearby devices ask, not this banner's.
     */
    fun need(sdk: Int, missing: String?, fineGranted: Boolean, coarseGranted: Boolean): Need? = when {
        missing == null || sdk >= Build.VERSION_CODES.S -> null
        sdk >= Build.VERSION_CODES.Q && !fineGranted -> Need.PERMISSION
        !fineGranted && !coarseGranted -> Need.PERMISSION
        else -> Need.LOCATION_OFF
    }
}
