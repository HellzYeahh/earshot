package dev.earshot.bt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build

/**
 * Whether a BLE scan by this app returns anything. Android 12+ asks for BLUETOOTH_SCAN (Earshot
 * declares it `neverForLocation`). Android 9–11 have no such permission: there `startScan` succeeds
 * but delivers no results unless the app holds the location permission (fine location on 10 and
 * 11 for an app targeting 29+, coarse is enough on 9) and location is turned on, and nothing says
 * why. Earshot uses the scan only to find a headset (AirPods/Beats battery adverts in
 * [AppleAdvertScanner], a JBL `<name>-LE` control address in [GattTargets]), never for location.
 *
 * One rule for every scanner, so a scan that would come back empty is skipped with the reason
 * logged instead of looking like "no headset nearby".
 */
internal object BleScanAccess {

    /** What is missing for a scan with results, or null when nothing is. Pure, for the tests. */
    fun missing(sdk: Int, scanGranted: Boolean, fineGranted: Boolean, coarseGranted: Boolean, locationOn: Boolean): String? = when {
        sdk >= Build.VERSION_CODES.S -> if (scanGranted) null else "the Nearby devices permission"
        sdk >= Build.VERSION_CODES.Q && !fineGranted -> "the precise location permission (Android 10 and 11)"
        !fineGranted && !coarseGranted -> "the location permission (Android 9)"
        !locationOn -> "location turned on (Android 11 and older)"
        else -> null
    }

    fun missing(context: Context): String? {
        fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return missing(Build.VERSION.SDK_INT, scanGranted = granted(Manifest.permission.BLUETOOTH_SCAN), fineGranted = false, coarseGranted = false, locationOn = true)
        }
        // Unknown (no LocationManager) counts as on: never block a scan on a guess.
        val locationOn = runCatching { context.getSystemService(LocationManager::class.java)?.isLocationEnabled }.getOrNull() ?: true
        return missing(
            Build.VERSION.SDK_INT,
            scanGranted = false,
            fineGranted = granted(Manifest.permission.ACCESS_FINE_LOCATION),
            coarseGranted = granted(Manifest.permission.ACCESS_COARSE_LOCATION),
            locationOn = locationOn,
        )
    }
}
