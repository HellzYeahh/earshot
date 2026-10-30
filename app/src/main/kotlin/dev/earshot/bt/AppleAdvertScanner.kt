package dev.earshot.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import dev.earshot.protocol.apple.AppleModels
import dev.earshot.protocol.apple.ContinuityAdvert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
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
 * Needs BLUETOOTH_SCAN (declared `neverForLocation`) on Android 12+, and on Android 9–11 the
 * location permission with location turned on ([BleScanAccess]); without them the bursts are
 * skipped (nothing is scanned) until they are given.
 */
class AppleAdvertScanner(private val context: Context, private val scope: CoroutineScope) {

    private var job: Job? = null

    fun hasPermission(): Boolean = BleScanAccess.missing(context) == null

    /**
     * Scan in bursts while running, reporting the best advert of each burst that fits [bluetoothName].
     *
     * The loop runs even while [BleScanAccess] says a scan would come back empty: the access is
     * checked before each burst instead of once here. The caller starts the scanner only when the
     * headset connects, and the Home banner asks for the location grant (or for location to be
     * switched on) after that, so a one-off check would leave per-bud battery empty until the
     * AirPods reconnect. While access is missing it is re-checked every [RECHECK_MS], so a grant
     * shows results within about [RECHECK_MS] plus one burst.
     */
    @Synchronized
    fun start(bluetoothName: String?, onAdvert: (ContinuityAdvert) -> Unit) {
        stop()
        job = scope.launch {
            scanInBursts(
                missingAccess = { BleScanAccess.missing(context) },
                burst = { burst(bluetoothName) },
                onResult = onAdvert,
                log = BtLog::d,
                pauseMs = PERIOD_MS - SCAN_MS,
                recheckMs = RECHECK_MS,
            )
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    @SuppressLint("MissingPermission") // checked before each burst (scanInBursts)
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

        /** How often a missing permission or location switch is looked at again (no scan, no radio). */
        const val RECHECK_MS = 10_000L

        /** Roughly "on you or on the table next to you". */
        const val MIN_RSSI = -70
    }
}

/**
 * [AppleAdvertScanner]'s loop without Android, for the tests: until cancelled, run [burst] and
 * pass what it found to [onResult], then wait [pauseMs]. Before each burst [missingAccess] says
 * what a scan with results still needs; while something is missing no burst runs (no radio) and
 * the access is looked at again after [recheckMs], so a grant given while the loop runs takes
 * effect without a restart. Each missing reason is logged once, not at every check.
 */
internal suspend fun <T : Any> scanInBursts(
    missingAccess: () -> String?,
    burst: suspend () -> T?,
    onResult: (T) -> Unit,
    log: (String) -> Unit,
    pauseMs: Long,
    recheckMs: Long,
) {
    var loggedMissing: String? = null
    while (currentCoroutineContext().isActive) {
        val missing = missingAccess()
        if (missing != null) {
            if (missing != loggedMissing) log("Apple adverts: a BLE scan needs $missing; per-bud battery unavailable for now")
            loggedMissing = missing
            delay(recheckMs)
            continue
        }
        if (loggedMissing != null) log("Apple adverts: BLE scan access granted, scanning")
        loggedMissing = null
        burst()?.let(onResult)
        delay(pauseMs)
    }
}
