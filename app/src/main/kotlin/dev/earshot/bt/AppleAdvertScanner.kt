package dev.earshot.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.earshot.protocol.apple.AppleModels
import dev.earshot.protocol.apple.ContinuityAdvert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Listens for the proximity-pairing adverts AirPods and Beats send ([ContinuityAdvert]) while one
 * is the connected headset: per-bud and case battery and charging, which Android's own battery
 * broadcast (one figure) doesn't carry. Read-only: no connection, nothing sent.
 *
 * Short bursts ([SCAN_MS] every [PERIOD_MS], low-power mode) rather than a continuous scan, which
 * also keeps under Android's limit of five scan starts per 30 s. Adverts come from random
 * addresses, so ours is told apart by its model fitting the Bluetooth name and by being the
 * nearest ([MIN_RSSI]): a stranger's AirPods across the room don't count.
 *
 * Needs BLUETOOTH_SCAN (declared `neverForLocation`); without it nothing happens.
 */
class AppleAdvertScanner(private val context: Context, private val scope: CoroutineScope) {

    private var job: Job? = null

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    /** Scan in bursts while running, reporting the best advert of each burst that fits [bluetoothName]. */
    @Synchronized
    fun start(bluetoothName: String?, onAdvert: (ContinuityAdvert) -> Unit) {
        stop()
        if (!hasPermission()) {
            BtLog.d("Apple adverts: no BLUETOOTH_SCAN, per-bud battery unavailable")
            return
        }
        job = scope.launch {
            while (isActive) {
                burst(bluetoothName)?.let(onAdvert)
                delay(PERIOD_MS - SCAN_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    @SuppressLint("MissingPermission") // checked in hasPermission()
    private suspend fun burst(bluetoothName: String?): ContinuityAdvert? {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return null
        var best: Pair<Int, ContinuityAdvert>? = null
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord?.getManufacturerSpecificData(ContinuityAdvert.APPLE_COMPANY_ID) ?: return
                val advert = ContinuityAdvert.parse(data) ?: return
                if (result.rssi < MIN_RSSI || !AppleModels.fitsName(advert.model, bluetoothName)) return
                synchronized(this) {
                    if (best == null || result.rssi > best!!.first) {
                        if (best == null) BtLog.d("Apple advert ${ContinuityAdvert.hex(data)} rssi ${result.rssi}")
                        best = result.rssi to advert
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                BtLog.w("Apple advert scan failed: $errorCode")
            }
        }
        val filter = ScanFilter.Builder()
            // Proximity pairing only: type 0x07 in the first byte of Apple's manufacturer data.
            .setManufacturerData(ContinuityAdvert.APPLE_COMPANY_ID, byteArrayOf(0x07), byteArrayOf(0xFF.toByte()))
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        val started = runCatching { scanner.startScan(listOf(filter), settings, callback) }
            .onFailure { BtLog.w("Apple advert scan refused: ${it.message}") }
            .isSuccess
        if (!started) return null
        try {
            delay(SCAN_MS)
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
        return synchronized(callback) { best?.second }
    }

    private companion object {
        const val SCAN_MS = 6_000L
        const val PERIOD_MS = 60_000L

        /** Roughly "on you or on the table next to you". */
        const val MIN_RSSI = -70
    }
}
