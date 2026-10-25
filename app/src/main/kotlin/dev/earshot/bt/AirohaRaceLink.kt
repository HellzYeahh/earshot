package dev.earshot.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.protocol.airoha.RaceIds
import dev.earshot.protocol.airoha.RaceVendor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import java.util.UUID

/**
 * Opens an Airoha RACE session for a vendor whose driver controls through RACE ([RaceVendor]),
 * the two ways JBL's app does (JBL notes §2): the Airoha SPP record by SDP UUID on a secure socket,
 * or Airoha's GATT service when the phone is on LE Audio ([GattLinkSpec.AIROHA]). Never a channel
 * number, never as a probe.
 *
 * Who never gets it ([refusal]): Sony, Bose and Bragi headsets, whatever their record lists. Sony's
 * Airoha units expose RACE as a debug path (ERNW, CVE-2025-20700/20701/20702) and Bose and Bragi
 * control through their own protocols; opening RACE there could only do harm.
 *
 * The probe is the MMI enum read JBL's app starts with (`0x0901`, module 0): a plain read, checked
 * by the guard like every other frame. A channel that opens but stays silent is the vendor's app
 * holding it.
 */
object AirohaRaceLink {
    /** Airoha's SPP record. */
    val SPP_SERVICE: UUID = UUID.fromString("00000000-0000-0000-0099-aabbccddeeff")

    /** Stacks that never get the RACE channel, even when the headset offers it. */
    val NEVER_FOR: Set<DeviceFamily> = setOf(DeviceFamily.SONY, DeviceFamily.BOSE, DeviceFamily.BRAGI)

    private val PROBE_TIMEOUTS_MS = listOf(1_500L, 2_500L)

    /**
     * Why Earshot won't open RACE on this headset, or null. [family] is what Earshot already knows
     * about the address (its "Headphone type" or the stack that answered there); [cachedUuids] is
     * Android's cached SDP record, empty when it has none. Pure, for the tests.
     */
    fun refusal(name: String?, family: DeviceFamily?, cachedUuids: List<UUID>, overLe: Boolean): String? {
        val byName = DeviceFamily.forName(name)
        if (family in NEVER_FOR || byName in NEVER_FOR) {
            return "RACE is never opened on a ${(family?.takeIf { it in NEVER_FOR } ?: byName).name.lowercase()} headset"
        }
        // The SPP path needs the record; an empty cache is left to SDP at connect time.
        if (!overLe && cachedUuids.isNotEmpty() && SPP_SERVICE !in cachedUuids) return "the headset's Bluetooth record has no Airoha RACE service"
        return null
    }

    /**
     * Open and probe. Throws [RfcommConnectException] (looksBusy when it opened but nothing answered,
     * or when it was refused) like the other link factories, or [SecurityException].
     */
    suspend fun open(
        context: Context,
        device: BluetoothDevice,
        name: String?,
        vendor: RaceVendor,
        scope: CoroutineScope,
        family: DeviceFamily? = null,
        overLe: Boolean = false,
    ): RaceConnection {
        refusal(name, family, cachedUuids(device), overLe)?.let { throw RfcommConnectException(it, looksBusy = false) }
        val transport: ByteTransport = if (overLe) {
            val target = GattTargets(context).find(device, name, GattLinkSpec.AIROHA)
                ?: throw RfcommConnectException("the headset's LE control side wasn't found", looksBusy = false)
            try {
                GattLink.open(context, target, GattLinkSpec.AIROHA)
            } catch (e: GattSetupException) {
                throw RfcommConnectException("Airoha GATT: ${e.message}", looksBusy = false, cause = e)
            }
        } else {
            RfcommTransport.connectService(device, SPP_SERVICE)
        }
        val connection = RaceConnection(transport, vendor, scope)
        try {
            probe(connection)
            BtLog.i("RACE (${vendor.name}) open on ${device.address} over ${if (overLe) "GATT" else "SPP"}")
            return connection
        } catch (e: HeadphoneCommandException) {
            connection.close()
            throw RfcommConnectException("The RACE channel opened but the headphones did not answer", looksBusy = true, cause = e)
        } catch (t: Throwable) {
            connection.close()
            if (t is CancellationException) throw t
            throw t
        }
    }

    /** Any reply to the MMI read means RACE is alive here; retried once with a longer wait. */
    internal suspend fun probe(connection: RaceConnection) {
        var last: HeadphoneCommandException? = null
        for (timeout in PROBE_TIMEOUTS_MS) {
            try {
                connection.request(RaceIds.MMI_GET_ENUM, byteArrayOf(0, 0), timeoutMs = timeout)
                return
            } catch (e: HeadphoneCommandException) {
                last = e
                if (!connection.isOpen) break
            }
        }
        throw last ?: HeadphoneCommandException("The headphones didn't answer")
    }

    @SuppressLint("MissingPermission") // the caller checked BLUETOOTH_CONNECT
    private fun cachedUuids(device: BluetoothDevice): List<UUID> = runCatching { device.uuids }.getOrNull().orEmpty().map { it.uuid }
}
