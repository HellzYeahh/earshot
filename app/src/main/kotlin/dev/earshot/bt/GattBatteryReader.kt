package dev.earshot.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

/**
 * One read of the standard Battery Service (`0x180F`, Battery Level `0x2A19`) over Earshot's own
 * GATT client: a Shizuku-free starting battery for headsets that speak Bluetooth LE. AOSP keeps BAS
 * off its restricted-service list (unlike VCS/AICS/HAS, Android 13 through 17), and its own battery
 * client reads it through the same public `connectGatt`. Used only when nothing else knows the
 * level; the change broadcast keeps it current afterwards.
 *
 * Classic-only headsets are skipped (no LE link to open), and every path closes the GATT client:
 * a leaked client keeps an LE connection up and can nudge LE Audio routing on Android 13–16.
 */
object GattBatteryReader {
    val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    private const val TIMEOUT_MS = 6_000L

    /** Battery Level is one byte, 0..100 percent; anything else is not a reading. */
    fun parse(value: ByteArray?): Int? = value?.firstOrNull()?.toInt()?.and(0xFF)?.takeIf { it in 0..100 }

    @SuppressLint("MissingPermission") // callers hold BLUETOOTH_CONNECT; a SecurityException is caught
    suspend fun read(context: Context, device: BluetoothDevice): Int? {
        val type = runCatching { device.type }.getOrNull()
        if (type != BluetoothDevice.DEVICE_TYPE_LE && type != BluetoothDevice.DEVICE_TYPE_DUAL) return null
        var gatt: BluetoothGatt? = null
        return try {
            withTimeoutOrNull(TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    fun finish(level: Int?) {
                        if (cont.isActive) cont.resume(level)
                    }
                    val callback = object : BluetoothGattCallback() {
                        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                            when {
                                newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS ->
                                    if (!g.discoverServices()) finish(null)
                                newState == BluetoothProfile.STATE_DISCONNECTED -> finish(null)
                            }
                        }

                        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                            val characteristic = g.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL)
                            if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null || !g.readCharacteristic(characteristic)) {
                                finish(null)
                            }
                        }

                        // Android 13+ delivers the value here; the older overload below covers 9–12.
                        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                            finish(if (status == BluetoothGatt.GATT_SUCCESS) parse(value) else null)
                        }

                        @Deprecated("Android 12 and older")
                        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                            @Suppress("DEPRECATION")
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                finish(if (status == BluetoothGatt.GATT_SUCCESS) parse(c.value) else null)
                            }
                        }
                    }
                    gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                    if (gatt == null) finish(null)
                }
            }.also { level -> BtLog.d("BAS battery for ${device.address}: ${level ?: "none"}") }
        } catch (e: SecurityException) {
            BtLog.d("BAS read refused: ${e.javaClass.simpleName}")
            null
        } finally {
            gatt?.let { g ->
                runCatching { g.disconnect() }
                runCatching { g.close() }
            }
        }
    }
}
