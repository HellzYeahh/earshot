package dev.earshot.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.protocol.sony.SonyMessage
import dev.earshot.protocol.sony.SonyModel
import dev.earshot.protocol.sony.SonyProtocolInfo
import dev.earshot.protocol.sony.SonyTable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import java.util.UUID

/**
 * Reaches a Sony headset on its MDR v2 RFCOMM service, `956C7B26-D49A-4BA8-B03F-B17D393CB6E2`,
 * found in the SDP record Android caches (also accepted byte-reversed, as Sound Connect does).
 * Opened by UUID only, never by channel number and never through the Bose probe: many Sony models
 * are Airoha-based and also expose the RACE debug channel, which Earshot must never touch.
 *
 * The probe is `CONNECT_GET_PROTOCOL_INFO`, the first thing every client sends: an 8-byte reply
 * is MDR v2 and the session continues; a 4-byte one is v1 (WH-1000XM4 and older), which Earshot
 * doesn't drive. A socket that opens but stays silent is Sound Connect holding the connection.
 */
class SonyLinkFactory(
    /** The probe gives up sooner than a normal send (3 resends, ~3 s with the 500 ms start delay). */
    private val probeResends: Int = PROBE_RESENDS,
) : LinkFactory<SonyConnection> {

    override val family: DeviceFamily get() = DeviceFamily.SONY
    override val competingApp: String get() = "Sony Sound Connect"

    override suspend fun open(device: BluetoothDevice, name: String?, prefs: DevicePrefs, scope: CoroutineScope): SonyConnection {
        val uuids = cachedUuids(device)
        val service = uuids.firstOrNull(SonyModel::isV2Service)
        if (service == null) {
            val message = if (uuids.any(SonyModel::isV1Service)) MESSAGE_V1 else MESSAGE_NO_SERVICE
            throw RfcommConnectException(message, looksBusy = false)
        }
        // The real SDP record holds the canonical UUID; only Android's cached copy may be reversed.
        val candidates = listOf(SonyModel.SERVICE_V2, service).distinct().map(UUID::fromString)
        var lastError: Exception? = null
        for (uuid in candidates) {
            val transport = try {
                RfcommTransport.connectService(device, uuid)
            } catch (e: SecurityException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                continue
            }
            val connection = SonyConnection(transport, scope)
            try {
                probe(connection)
                SonyLog.i("sony connected to ${device.address} via $uuid (protocol ${connection.protocolInfo?.versionText})")
                return connection
            } catch (t: Throwable) {
                connection.close()
                throw t
            }
        }
        throw lastError ?: RfcommConnectException(MESSAGE_NO_SERVICE, looksBusy = false)
    }

    override fun adopt(transport: ByteTransport, scope: CoroutineScope): SonyConnection = SonyConnection(transport, scope)

    /**
     * Ask for the protocol info and keep it on [connection]. Throws [RfcommConnectException]: busy
     * when nothing answers, not busy for a v1 headset or a reply that isn't MDR.
     */
    suspend fun probe(connection: SonyConnection) {
        val reply = try {
            connection.request(SonyMessage(SonyTable.T1, SonyProtocolInfo.GET), resends = probeResends)
        } catch (e: HeadphoneCommandException) {
            throw RfcommConnectException("The Sony service opened but the headphones did not answer", looksBusy = true, cause = e)
        }
        val info = SonyProtocolInfo.parse(reply.payload)
            ?: throw RfcommConnectException("The Sony service answered with something that isn't MDR", looksBusy = false)
        if (!info.isV2) throw RfcommConnectException(MESSAGE_V1, looksBusy = false)
        connection.protocolInfo = info
    }

    @SuppressLint("MissingPermission") // the manager checked BLUETOOTH_CONNECT
    private fun cachedUuids(device: BluetoothDevice): List<String> =
        runCatching { device.uuids }.getOrNull().orEmpty().map { it.uuid.toString().lowercase() }

    companion object {
        const val PROBE_RESENDS = 3
        const val MESSAGE_V1 = "These Sony headphones use the older protocol (MDR v1), which Earshot doesn't drive yet"
        const val MESSAGE_NO_SERVICE = "No Sony control service in these headphones' Bluetooth record"
    }
}
