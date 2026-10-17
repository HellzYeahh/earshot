package dev.earshot.extras.automation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/**
 * Reports the current Wi-Fi SSID (normalised, null when none/hidden) and calls [onChange]
 * whenever it changes.
 *
 * Why location: since Android 8.1 the SSID counts as location data. Reading it requires
 * `ACCESS_FINE_LOCATION` (granted at runtime by the UI) *and* location services switched on;
 * otherwise the platform hands back `<unknown ssid>`. On Android 10+ an app that is in the
 * background additionally needs `ACCESS_BACKGROUND_LOCATION` (or a `location` foreground
 * service type) to get the real name; without it the trigger still works while Earshot is
 * on screen and on the next capability change after it comes to the foreground.
 *
 * Why a NetworkCallback instead of the deprecated `NETWORK_STATE_CHANGED_ACTION` broadcast:
 * implicit broadcasts no longer reach manifest receivers, and the callback is the only path
 * that carries `WifiInfo` on Android 12+ (`FLAG_INCLUDE_LOCATION_INFO` opts in to the SSID).
 */
class WifiSsidWatcher(
    context: Context,
    private val onChange: (ssid: String?) -> Unit,
) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastSsid: String? = null
    @Volatile private var started = false

    val currentSsid: String? get() = lastSsid

    fun start() {
        if (started) return
        val cm = connectivity ?: return
        started = true
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val cb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    publish(Ssid.normalize((caps.transportInfo as? WifiInfo)?.ssid))
                }
                override fun onLost(network: Network) = publish(null)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = publish(legacySsid())
                override fun onLost(network: Network) = publish(null)
            }
        }
        callback = cb
        runCatching { cm.registerNetworkCallback(request, cb) }
            .onFailure { Log.w(TAG, "Wi-Fi callback registration failed", it); started = false; callback = null }
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        started = false
        runCatching { connectivity?.unregisterNetworkCallback(cb) }
        lastSsid = null
    }

    fun hasLocationPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun publish(ssid: String?) {
        if (ssid == lastSsid) return
        lastSsid = ssid
        onChange(ssid)
    }

    /** Pre-Android 12 path: `WifiManager.connectionInfo` still carries the SSID. */
    @Suppress("DEPRECATION")
    private fun legacySsid(): String? {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        return runCatching { Ssid.normalize(wifi.connectionInfo?.ssid) }.getOrNull()
    }

    private companion object { const val TAG = "Earshot/Wifi" }
}
