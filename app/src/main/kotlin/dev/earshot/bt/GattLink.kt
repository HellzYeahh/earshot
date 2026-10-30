package dev.earshot.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * Opens a [GattTransport] on a real headset: the Android side of the GATT link. The transport holds
 * the logic; this file only turns `BluetoothGatt` calls and callbacks into [GattPort] calls and the
 * transport's `on…` methods, and finds which LE address to open ([GattTargets]).
 */
object GattLink {

    /**
     * Connect to [device] over LE and run the [spec]'s setup. Throws [GattSetupException] when the
     * link does not come up (the GATT client is released then), or [SecurityException] when
     * BLUETOOTH_CONNECT is missing.
     */
    @SuppressLint("MissingPermission") // callers hold BLUETOOTH_CONNECT
    suspend fun open(
        context: Context,
        device: BluetoothDevice,
        spec: GattLinkSpec,
        timeouts: GattTransport.Timeouts = GattTransport.Timeouts(),
    ): GattTransport {
        val port = AndroidGattPort()
        val transport = GattTransport(port, spec, timeouts)
        val gatt = device.connectGatt(context.applicationContext, false, Callback(transport), BluetoothDevice.TRANSPORT_LE)
            ?: throw GattSetupException("Android could not start a GATT connection to ${device.address}")
        port.gatt = gatt
        transport.open()
        BtLog.i("GATT ${spec.name} open on ${device.address}, MTU ${transport.mtu}")
        return transport
    }

    private class Callback(private val transport: GattTransport) : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) =
            transport.onConnectionStateChange(status, newState == BluetoothProfile.STATE_CONNECTED)

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = transport.onServicesDiscovered(status)

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = transport.onMtuChanged(mtu, status)

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            transport.onDescriptorWrite(status)

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            transport.onCharacteristicWrite(status)

        // Android 13+ delivers the value here and then calls the deprecated overload as well.
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) =
            transport.onCharacteristicChanged(characteristic.uuid, value)

        @Deprecated("Android 12 and older")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            characteristic.value?.let { transport.onCharacteristicChanged(characteristic.uuid, it) }
        }
    }

    @SuppressLint("MissingPermission")
    private class AndroidGattPort : GattPort {
        @Volatile
        var gatt: BluetoothGatt? = null

        private fun characteristic(service: UUID, characteristic: UUID): BluetoothGattCharacteristic? =
            gatt?.getService(service)?.getCharacteristic(characteristic)

        override fun discoverServices(): Boolean = gatt?.discoverServices() == true

        override fun requestHighPriority(): Boolean = gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) == true

        // Without this override the interface default (false) left the link at the setup burst's
        // high priority for the whole session (see GattPort.requestBalancedPriority).
        override fun requestBalancedPriority(): Boolean = gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED) == true

        override fun requestMtu(mtu: Int): Boolean = gatt?.requestMtu(mtu) == true

        override fun properties(service: UUID, characteristic: UUID): Int? = characteristic(service, characteristic)?.properties

        override fun enableNotifications(service: UUID, characteristic: UUID, indicate: Boolean): Boolean {
            val g = gatt ?: return false
            val c = characteristic(service, characteristic) ?: return false
            if (!g.setCharacteristicNotification(c, true)) return false
            val cccd = c.getDescriptor(GattLinkSpec.CCCD) ?: return false
            val value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        override fun write(service: UUID, characteristic: UUID, value: ByteArray, withResponse: Boolean): Boolean {
            val g = gatt ?: return false
            val c = characteristic(service, characteristic) ?: return false
            val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
            } else {
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = value
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }

        override fun close() {
            val g = gatt ?: return
            gatt = null
            // A leaked client keeps the LE link up and can nudge LE Audio routing (see GattBatteryReader).
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
    }
}

/**
 * Which LE address to open for a headset. A bonded LE or dual-mode device is opened by its own
 * address: Android resolves its rotating private address through the bond. Some TWS earbuds keep
 * their control service on a separate, unbonded LE identity with a rotating random address,
 * advertised as `<name>-LE` (jbl-control's finding on JBL buds); that one is found by a short scan.
 *
 * The scan accepts only an advert whose name fits the headset's ([LeAdvertMatch]) and that is
 * near ([MIN_RSSI]), so a stranger's identical buds across the room are not picked. Nothing is
 * sent on the link until the vendor's own probe answers, and every write passes its guard.
 * The scan needs BLUETOOTH_SCAN (declared `neverForLocation`) on Android 12+, and on Android 9–11
 * the location permission with location turned on ([BleScanAccess]), never used for location.
 */
class GattTargets(private val context: Context) {

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is held by every caller
    suspend fun find(bonded: BluetoothDevice, name: String?, spec: GattLinkSpec): BluetoothDevice? {
        if (isLeCapable(bonded)) return bonded
        if (!LeAdvertMatch.isUsable(name)) return null
        return scanAll(name!!, spec).firstOrNull()
    }

    /** A bonded LE or dual-mode device: Android resolves its LE address through the bond. */
    @SuppressLint("MissingPermission")
    fun isLeCapable(bonded: BluetoothDevice): Boolean {
        val type = runCatching { bonded.type }.getOrDefault(BluetoothDevice.DEVICE_TYPE_UNKNOWN)
        return type == BluetoothDevice.DEVICE_TYPE_LE || type == BluetoothDevice.DEVICE_TYPE_DUAL
    }

    /**
     * Every near device whose advert fits [name], strongest first. More than one means another
     * pair of the same model is near too: the caller should refuse rather than guess, because the
     * default name is the same on every pair.
     */
    suspend fun scanAll(name: String, spec: GattLinkSpec): List<BluetoothDevice> =
        if (LeAdvertMatch.isUsable(name)) scan(name, spec) else emptyList()

    @SuppressLint("MissingPermission") // checked through BleScanAccess
    private suspend fun scan(name: String, spec: GattLinkSpec): List<BluetoothDevice> {
        // The old BLUETOOTH_SCAN-only check passed every Android 9–11 phone, where a scan without
        // location starts but finds nothing; BleScanAccess is the rule the Apple scanner uses too.
        BleScanAccess.missing(context)?.let { missing ->
            BtLog.d("GATT ${spec.name}: a BLE scan needs $missing; can't look for the LE control address")
            return emptyList()
        }
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return emptyList()
        // Strongest reading per address: a rotating random address shows up as one device per scan.
        val seen = LinkedHashMap<String, ScanResult>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val advertName = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
                if (result.rssi < MIN_RSSI || !LeAdvertMatch.fits(advertName, name)) return
                synchronized(this) {
                    val address = result.device.address
                    if ((seen[address]?.rssi ?: Int.MIN_VALUE) < result.rssi) seen[address] = result
                }
            }

            override fun onScanFailed(errorCode: Int) {
                BtLog.w("GATT ${spec.name}: LE scan failed: $errorCode")
            }
        }
        // Filters are OR'ed: the service when the advert carries it, else the names it may use.
        val filters = buildList {
            add(ScanFilter.Builder().setServiceUuid(ParcelUuid(spec.service)).build())
            LeAdvertMatch.candidateNames(name).forEach { add(ScanFilter.Builder().setDeviceName(it).build()) }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val started = runCatching { scanner.startScan(filters, settings, callback) }
            .onFailure { BtLog.w("GATT ${spec.name}: LE scan refused: ${it.message}") }
            .isSuccess
        if (!started) return emptyList()
        try {
            delay(SCAN_MS)
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
        val found = synchronized(callback) { seen.values.sortedByDescending { it.rssi }.map { it.device } }
        BtLog.d("GATT ${spec.name}: ${found.size} LE control address(es) found by scan")
        return found
    }

    private companion object {
        const val SCAN_MS = 5_000L

        /** Roughly "on you or on the table next to you", as for the Apple adverts. */
        const val MIN_RSSI = -70
    }
}
