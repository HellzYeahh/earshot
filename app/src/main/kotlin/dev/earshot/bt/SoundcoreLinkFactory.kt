package dev.earshot.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.protocol.soundcore.SoundcoreCommands
import dev.earshot.protocol.soundcore.SoundcoreModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import java.util.UUID

/** A demo transport that knows which Soundcore model it plays, so [SoundcoreLinkFactory.adopt] can pick the right allow-list. */
interface SoundcoreModelTransport {
    val soundcoreModel: SoundcoreModel
}

/**
 * Reaches a Soundcore headset on its vendor RFCOMM service, `0cf12d31-fac3-4553-bd80-d6832e7` +
 * five hex digits that follow the model code (`…d1301` on the Sleep A30, `…b3947` on the Liberty 4
 * NC; [SoundcoreModel.uuidSuffix]), found in the SDP record Android caches. Opened by UUID
 * only, never by channel number: the same headsets carry a firmware-update service (BES
 * `66666666-…`, RFCOMM channel 12 on the Space 2) on another channel that must never see a stray
 * byte, and JBL publishes a service on the same `0cf12d31` base that is not Soundcore at all
 * ([SoundcoreModel.identify] skips it by suffix and by name).
 *
 * The probe is the state request `01 01`, which the official app sends first; the Sleep A30 needs
 * no handshake. Some models answer a first request with a bare ack, so the probe accepts any
 * `01 01` reply and the repository asks again for the body. A socket that opens but stays silent is
 * the Soundcore app holding the connection ([competingApp]).
 */
class SoundcoreLinkFactory(
    private val probeTimeoutsMs: List<Long> = listOf(800L, 1_500L),
) : LinkFactory<SoundcoreConnection> {

    override val family: DeviceFamily get() = DeviceFamily.SOUNDCORE
    override val busyMessage: String get() = MESSAGE_CHANNEL_BUSY
    override val competingApp: String get() = "soundcore"

    override suspend fun open(device: BluetoothDevice, name: String?, prefs: DevicePrefs, scope: CoroutineScope): SoundcoreConnection {
        val (model, uuid) = identify(device, name)
            ?: throw RfcommConnectException("This Soundcore model has no Earshot driver yet (no supported service in its Bluetooth record)", looksBusy = false)
        val transport = RfcommTransport.connectService(device, UUID.fromString(uuid))
        val connection = SoundcoreConnection(transport, scope, model = model)
        try {
            connection.request(SoundcoreCommands.REQUEST_STATE, timeoutsMs = probeTimeoutsMs)
            BtLog.i("soundcore ${model.code} connected to ${device.address} via $uuid")
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

    override fun adopt(transport: ByteTransport, scope: CoroutineScope): SoundcoreConnection =
        SoundcoreConnection(transport, scope, model = (transport as? SoundcoreModelTransport)?.soundcoreModel)

    /** The first cached service UUID that belongs to a model Earshot drives, unless the name says it is another brand. */
    @SuppressLint("MissingPermission") // the manager checked BLUETOOTH_CONNECT
    private fun identify(device: BluetoothDevice, name: String?): Pair<SoundcoreModel, String>? =
        SoundcoreModel.identify(runCatching { device.uuids }.getOrNull().orEmpty().map { it.uuid.toString() }, name)

    companion object {
        const val MESSAGE_CHANNEL_BUSY = "Close the soundcore app, then try again"
    }
}
