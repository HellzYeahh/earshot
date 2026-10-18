package dev.earshot.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID

/**
 * Finds the L2CAP channel (PSM) that carries BMAP over Bluetooth LE.
 *
 * Why: with LE Audio on, the phone may be connected to the headphones over LE only (Android lists
 * that identity as "LE-Bose …"), and there is no classic link for RFCOMM. The Bose app 13.3.4
 * (`LecocConnectionManager`) then speaks the same BMAP frames over an LE connection-oriented
 * channel: it reads the PSM (a dynamic LE PSM, 0x80..0xFF) from GATT characteristic
 * [PSM_CHARACTERISTIC] in Bose's service [SERVICE] and opens `createInsecureL2capChannel(psm)`.
 * See docs/bmap-from-bose-app-13.3.4.md, "Other transports".
 *
 * The GATT client is handed back open ([Result.gatt]) and closed with the socket: dropping it
 * right after the read can take the LE link down on phones where nothing else holds it.
 */
internal object BmapPsmReader {
    val SERVICE: UUID = UUID.fromString("0000febe-0000-1000-8000-00805f9b34fb")
    val PSM_CHARACTERISTIC: UUID = UUID.fromString("089ca084-6721-4419-9003-5e14d5ab587d")

    /** Connect, discover and read; a slow LE link takes a few seconds for all three. */
    const val TIMEOUT_MS = 10_000L

    class Result(val psm: Int, val gatt: BluetoothGatt)

    /**
     * The PSM in a characteristic value. The byte order is not documented: a single byte is the
     * PSM; with two, whichever order lands in the dynamic LE range wins (little-endian first, as
     * GATT values usually are). Null when nothing plausible is there.
     */
    fun parsePsm(value: ByteArray?): Int? {
        if (value == null || value.isEmpty()) return null
        val b0 = value[0].toInt() and 0xFF
        if (value.size == 1) return b0.takeIf { it in LE_DYNAMIC_PSM }
        val b1 = value[1].toInt() and 0xFF
        val little = b0 or (b1 shl 8)
        val big = (b0 shl 8) or b1
        return when {
            little in LE_DYNAMIC_PSM -> little
            big in LE_DYNAMIC_PSM -> big
            else -> null
        }
    }

    /** LE credit-based channels use dynamic PSMs 0x80..0xFF (Core spec, Vol 3 Part A 4.22). */
    private val LE_DYNAMIC_PSM = 0x80..0xFF

    @SuppressLint("MissingPermission")
    suspend fun read(context: Context, device: BluetoothDevice): Result {
        val connected = CompletableDeferred<Unit>()
        val discovered = CompletableDeferred<Unit>()
        val value = CompletableDeferred<ByteArray?>()
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when {
                    newState == BluetoothProfile.STATE_CONNECTED -> connected.complete(Unit)
                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        val e = IOException("GATT disconnected (status $status)")
                        connected.completeExceptionally(e)
                        discovered.completeExceptionally(e)
                        value.completeExceptionally(e)
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) discovered.complete(Unit)
                else discovered.completeExceptionally(IOException("GATT service discovery failed (status $status)"))
            }

            // API 33+ delivers the value here.
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, data: ByteArray, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) value.complete(data)
                else value.completeExceptionally(IOException("PSM read failed (status $status)"))
            }

            // Before API 33 the value sits in the characteristic.
            @Deprecated("Deprecated in API 33")
            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) value.complete(characteristic.value)
                else value.completeExceptionally(IOException("PSM read failed (status $status)"))
            }
        }

        val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("GATT client could not be created")
        try {
            return withTimeout(TIMEOUT_MS) {
                connected.await()
                if (!gatt.discoverServices()) throw IOException("GATT service discovery did not start")
                discovered.await()
                // Bose's service first; any service if a firmware moved the characteristic.
                val characteristic = gatt.getService(SERVICE)?.getCharacteristic(PSM_CHARACTERISTIC)
                    ?: gatt.services.orEmpty().firstNotNullOfOrNull { it.getCharacteristic(PSM_CHARACTERISTIC) }
                    ?: throw IOException("the headphones publish no BMAP LE channel (no PSM characteristic)")
                if (!gatt.readCharacteristic(characteristic)) throw IOException("PSM read did not start")
                val raw = value.await()
                val psm = parsePsm(raw) ?: throw IOException("PSM characteristic holds no usable channel (${raw?.joinToString(" ") { "%02x".format(it) }})")
                BtLog.i("BMAP LE channel of ${device.address}: PSM 0x%02x".format(psm))
                Result(psm, gatt)
            }
        } catch (t: Throwable) {
            runCatching { gatt.close() }
            if (t is CancellationException && t !is kotlinx.coroutines.TimeoutCancellationException) throw t
            if (t is kotlinx.coroutines.TimeoutCancellationException) throw IOException("BMAP LE channel lookup timed out after ${TIMEOUT_MS}ms")
            throw t
        }
    }
}
