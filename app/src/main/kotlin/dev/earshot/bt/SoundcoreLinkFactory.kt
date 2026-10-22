package dev.earshot.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.protocol.soundcore.SoundcoreCommands
import dev.earshot.protocol.soundcore.SoundcoreModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import java.util.UUID

/**
 * Reaches a Soundcore headset on its vendor RFCOMM service, `0cf12d31-fac3-4553-bd80-d6832e7` +
 * the model id (`…d1301` on the Sleep A30), found in the SDP record Android caches. Opened by UUID
 * only, never by channel number: the same headsets carry a firmware-update service (BES
 * `66666666-…`) on another channel that must never see a stray byte.
 *
 * The probe is the state request `01 01`, which the official app sends first; no handshake is
 * needed on the Sleep A30. A socket that opens but stays silent is the Soundcore app holding the
 * connection ([competingApp]).
 */
class SoundcoreLinkFactory(
    private val probeTimeoutsMs: List<Long> = listOf(800L, 1_500L),
) : LinkFactory<SoundcoreConnection> {

    override val family: DeviceFamily get() = DeviceFamily.SOUNDCORE
    override val busyMessage: String get() = MESSAGE_CHANNEL_BUSY
    override val competingApp: String get() = "soundcore"

    override suspend fun open(device: BluetoothDevice, name: String?, prefs: DevicePrefs, scope: CoroutineScope): SoundcoreConnection {
        val uuid = serviceUuid(device)
            ?: throw RfcommConnectException("This Soundcore model has no Earshot driver yet (no supported service in its Bluetooth record)", looksBusy = false)
        val transport = RfcommTransport.connectService(device, UUID.fromString(uuid))
        val connection = SoundcoreConnection(transport, scope)
        try {
            connection.request(SoundcoreCommands.REQUEST_STATE, timeoutsMs = probeTimeoutsMs)
            BtLog.i("soundcore connected to ${device.address} via $uuid")
            return connection
        } catch (e: HeadphoneCommandException) {
            connection.close()
            throw RfcommConnectException("The Soundcore service opened but the earbuds did not answer", looksBusy = true)
        } catch (t: Throwable) {
            connection.close()
            if (t is CancellationException) throw t
            throw t
        }
    }

    override fun adopt(transport: ByteTransport, scope: CoroutineScope): SoundcoreConnection = SoundcoreConnection(transport, scope)

    /** The first cached service UUID that belongs to a model Earshot drives. */
    @SuppressLint("MissingPermission") // the manager checked BLUETOOTH_CONNECT
    private fun serviceUuid(device: BluetoothDevice): String? =
        runCatching { device.uuids }.getOrNull().orEmpty()
            .map { it.uuid.toString().lowercase() }
            .firstOrNull { SoundcoreModel.fromServiceUuid(it) != null }

    companion object {
        const val MESSAGE_CHANNEL_BUSY = "Close the soundcore app, then try again"
    }
}
