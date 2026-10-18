package dev.earshot.bt

import android.bluetooth.BluetoothDevice
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.protocol.bragi.BragiModel
import dev.earshot.protocol.bragi.BragiServices
import dev.earshot.protocol.bragi.DeviceInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import java.io.IOException
import java.util.UUID

/**
 * One way to reach a Bragi device: which RFCOMM service to open and how outgoing frames are
 * wrapped. The official app keeps **two** sockets per Crusher: BOS-RPC frames go raw on the *data*
 * service (`54C5BF4C-…`), while the *third-party* service (`A4602B24-…`) carries only Airoha's
 * native RACE protocol, which Earshot never speaks. So the data service comes first and the
 * third-party one is only a fallback in case a firmware differs; the one that answers is remembered
 * per device ([DevicePrefs.bragiProfileFor]). Nothing but PPP-framed BOS-RPC is ever written.
 */
class BragiLinkProfile(val id: String, val uuid: UUID, val wrapper: BragiTxWrapper) {
    override fun toString() = "$id (${wrapper})"

    companion object {
        /** The Bragi data service of the Crusher's device config: where BOS-RPC frames go, raw. */
        val SERVICE_DATA: UUID = UUID.fromString("54C5BF4C-C52F-4635-98DC-EEBF97C6726F")

        /** The third-party service: Airoha's native RACE socket in the official app. Only a fallback here. */
        val SERVICE_THIRD_PARTY: UUID = UUID.fromString("A4602B24-F9F1-4AC3-988C-228D764B97A6")

        /** In the order they are tried when nothing is remembered. */
        val DEFAULTS: List<BragiLinkProfile> = listOf(
            BragiLinkProfile("data/raw", SERVICE_DATA, RawPppWrapper),
            BragiLinkProfile("third-party/raw", SERVICE_THIRD_PARTY, RawPppWrapper),
        )
    }
}

/**
 * Opens a session to a Bragi headphone (the Skullcandy Crusher 1080 ANC): a secure RFCOMM socket by
 * service UUID, then one `device.dev_info` call to prove the far end answers BOS-RPC. Nothing is
 * sent on connect before that, matching the Skullcandy app.
 *
 * If a profile's socket opens but the probe stays silent, the next profile is tried; if every
 * profile that opened a socket stayed silent, the failure is reported as a busy channel (usually the
 * Skullcandy app holding the connection). The profile that works is remembered, so later connects
 * go straight to it.
 */
class BragiLinkFactory(
    private val profiles: List<BragiLinkProfile> = BragiLinkProfile.DEFAULTS,
    private val probeTimeoutMs: Long = PROBE_TIMEOUT_MS,
) : LinkFactory<BragiConnection> {

    /** The text for "somebody else holds the control connection". */
    override val busyMessage: String get() = MESSAGE_CHANNEL_BUSY

    override fun handles(name: String?): Boolean = BragiModel.isBragiName(name)

    override suspend fun open(device: BluetoothDevice, name: String?, prefs: DevicePrefs, scope: CoroutineScope): BragiConnection {
        val address = device.address
        val order = orderFor(prefs.bragiProfileFor(address))
        val attempts = ArrayList<String>()
        var anySocketOpened = false
        var lastIo: Exception? = null
        for (profile in order) {
            val transport = try {
                RfcommTransport.connectService(device, profile.uuid)
            } catch (e: SecurityException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                attempts += "${profile.id}: ${e.message}"
                lastIo = e
                BtLog.w("bragi ${profile.id}: could not open the socket (${e.message})")
                continue
            }
            anySocketOpened = true
            val connection = BragiConnection(transport, scope, txWrapper = profile.wrapper)
            try {
                // The first RPC the Skullcandy app sends. Unwatched: this probe decides on its own.
                connection.callUnwatched(BragiServices.DEV_INFO, DeviceInfo.devInfo(), probeTimeoutMs)
                BtLog.i("bragi connected to $address via ${profile.id}")
                prefs.saveBragiProfile(address, profile.id)
                return connection
            } catch (e: HeadphoneCommandException) {
                // A real answer that is an error still proves the wrapper and framing; only silence moves on.
                if (connection.isOpen && connection.hasFramed) {
                    BtLog.w("bragi ${profile.id}: dev_info refused (${e.message}); carrying on")
                    prefs.saveBragiProfile(address, profile.id)
                    return connection
                }
                attempts += "${profile.id}: silent"
                BtLog.w("bragi ${profile.id}: socket open but no BOS-RPC answer")
                connection.close()
            } catch (e: CancellationException) {
                connection.close()
                throw e
            }
        }
        val summary = attempts.joinToString("; ")
        if (anySocketOpened) {
            throw RfcommConnectException("The control service opened but the headphones did not answer BOS-RPC ($summary)", looksBusy = true)
        }
        throw RfcommConnectException("Could not open the headphones' control service ($summary)", looksBusy = false, cause = lastIo)
    }

    override fun adopt(transport: ByteTransport, scope: CoroutineScope): BragiConnection =
        BragiConnection(transport, scope)

    /** The remembered profile first, then the defaults in order, each once. */
    internal fun orderFor(learnedId: String?): List<BragiLinkProfile> {
        val learned = profiles.firstOrNull { it.id == learnedId } ?: return profiles
        return listOf(learned) + profiles.filter { it !== learned }
    }

    companion object {
        const val PROBE_TIMEOUT_MS = 2_000L
        const val MESSAGE_CHANNEL_BUSY = "Close the Skullcandy app, then try again"
    }
}
