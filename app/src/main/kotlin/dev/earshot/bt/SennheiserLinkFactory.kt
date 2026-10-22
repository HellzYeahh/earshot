package dev.earshot.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.protocol.gaia.GaiaApiVersion
import dev.earshot.protocol.gaia.GaiaDiscovery
import dev.earshot.protocol.gaia.GaiaReply
import dev.earshot.protocol.gaia.GaiaVendor
import dev.earshot.protocol.sennheiser.SennheiserModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import java.util.UUID

/**
 * Reaches a Sennheiser headset (Momentum 4, Accentum, Momentum True Wireless 3/4, …) on its GAIA v3
 * RFCOMM service, `a2129ff3-081b-4c45-8afe-469d9c4842ec`, the way Smart Control does: by SDP
 * UUID, never by channel number, never Bose's channel walk and never the `deca-fade` (iAP2) record.
 * Why so strict: the channel differs per model (Accentum 15, Momentum 4 on 1 and 2) and an
 * Accentum is reported to power off when GAIA frames reach its iAP2 channel 14.
 *
 * The probe is Qualcomm Core's API version read (`0x001D/0x0000`, `03 01` on a Momentum 4): no
 * handshake or authentication exists on this link. A socket that opens but stays silent is Smart
 * Control holding the connection ([competingApp]).
 *
 * Models Smart Control reaches over BLE only (CX Plus TW, CX 500BT) and the 2020 GAIA v1/v2 models
 * never get here: their names keep them generic, and a cached record without the GAIA v3 service
 * is refused before any socket opens.
 */
class SennheiserLinkFactory(
    private val probeTimeoutsMs: List<Long> = listOf(1_500L, 2_500L),
) : LinkFactory<SennheiserConnection> {

    override val family: DeviceFamily get() = DeviceFamily.SENNHEISER

    /** "Close the Sennheiser Smart Control app, then try again" through [LinkFactory.busyMessage]. */
    override val competingApp: String get() = "Sennheiser Smart Control"

    override suspend fun open(device: BluetoothDevice, name: String?, prefs: DevicePrefs, scope: CoroutineScope): SennheiserConnection {
        checkServiceRecord(cachedUuids(device))
        val transport = RfcommTransport.connectService(device, SennheiserModel.GAIA3_SERVICE)
        val connection = SennheiserConnection(transport, scope)
        try {
            val version = probe(connection)
            SennheiserLog.i("connected to ${device.address}, GAIA API $version")
            return connection
        } catch (e: HeadphoneCommandException) {
            connection.close()
            throw RfcommConnectException("The Sennheiser service opened but the headphones did not answer", looksBusy = true)
        } catch (t: Throwable) {
            connection.close()
            if (t is CancellationException) throw t
            throw t
        }
    }

    /** The API version, retried once with a longer wait; throws when nothing answers. */
    private suspend fun probe(connection: SennheiserConnection): GaiaApiVersion? {
        var last: HeadphoneCommandException? = null
        for (timeout in probeTimeoutsMs) {
            try {
                return when (val r = connection.request(GaiaVendor.QUALCOMM, GaiaDiscovery.CORE_API_VERSION, timeoutMs = timeout)) {
                    is GaiaReply.Ok -> GaiaApiVersion.parse(r.payload)
                    // An error frame is still GAIA answering: the link is alive.
                    is GaiaReply.Error -> null
                }
            } catch (e: HeadphoneCommandException) {
                last = e
                if (!connection.isOpen) break
            }
        }
        throw last ?: HeadphoneCommandException("The headphones didn't answer")
    }

    override fun adopt(transport: ByteTransport, scope: CoroutineScope): SennheiserConnection = SennheiserConnection(transport, scope)

    @SuppressLint("MissingPermission") // the manager checked BLUETOOTH_CONNECT
    private fun cachedUuids(device: BluetoothDevice): List<UUID> = runCatching { device.uuids }.getOrNull().orEmpty().map { it.uuid }

    companion object {
        /**
         * Android's cached SDP record, when it has one, must list the GAIA v3 service; an empty cache
         * is left to SDP at connect time (as Smart Control does). Pure, for the tests.
         */
        fun checkServiceRecord(uuids: List<UUID>) {
            if (uuids.isEmpty() || SennheiserModel.GAIA3_SERVICE in uuids) return
            val message = if (SennheiserModel.GAIA_LEGACY_SERVICE in uuids) {
                "This Sennheiser model uses an older protocol Earshot doesn't speak yet"
            } else {
                "This Sennheiser model can't be controlled over classic Bluetooth (no GAIA service in its Bluetooth record)"
            }
            throw RfcommConnectException(message, looksBusy = false)
        }
    }
}
