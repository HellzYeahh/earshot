package dev.earshot.bt

import android.bluetooth.BluetoothDevice
import android.content.Context
import dev.earshot.protocol.jbl.JblCommands
import dev.earshot.protocol.jbl.JblDeviceInfo
import dev.earshot.protocol.jbl.JblModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/**
 * Reaches a JBL headset on its Harman GATT service ("excelpoint.com", [GattLinkSpec.HARMAN]): the
 * bonded device itself when Android knows it as LE or dual-mode, else (or when the service isn't
 * there) the separate `<name>-LE` control address the buds advertise, found by a short nearby scan
 * ([GattTargets]). Only H-AA models get here ([JblModel]).
 *
 * The probe is the device info: the headset sends `AA 12` by itself when the link opens (seen in
 * jbl-control's Wave Buds 2 capture), else Earshot asks with `AA 11 00`. Its product id then
 * decides: a Protocol 4 or Airoha model ([JblModel.refusal]) is closed before anything else is
 * sent, and the user reads why. A link that opens and stays silent is JBL's app holding the control
 * connection.
 *
 * Every pair of a model advertises the same name, so a scan that finds more than one is resolved
 * only by the address in each one's device info matching the paired headset; failing that Earshot
 * refuses rather than control a stranger's buds.
 */
class JblLinkFactory(
    private val context: Context,
    targetsOverride: GattTargets? = null,
) : LinkFactory<JblConnection> {

    /** Made on the first real connect: a demo session and the tests only ever adopt. */
    private val targets: GattTargets by lazy { targetsOverride ?: GattTargets(context) }

    override val family: DeviceFamily get() = DeviceFamily.JBL

    override val competingApp: String get() = "JBL Headphones"

    override suspend fun open(device: BluetoothDevice, name: String?, prefs: DevicePrefs, scope: CoroutineScope): JblConnection {
        if (targets.isLeCapable(device)) {
            try {
                return openOn(device, device, name, scope)
            } catch (e: GattSetupException) {
                // The control service may sit on the buds' separate LE identity (jbl-control's finding).
                JblLog.d("no Harman service on the paired device (${e.message}); scanning for its LE control address")
            }
        }
        val found = name?.let { targets.scanAll(it, GattLinkSpec.HARMAN) }.orEmpty()
        return when (found.size) {
            0 -> throw ExplainedConnectException("Earshot couldn't find the JBL control connection nearby", retry = true)
            1 -> openOn(found.single(), device, name, scope)
            else -> openVerified(found, device, name, scope)
        }
    }

    /** Several pairs fit the name: keep the one whose device info names the paired headset. */
    private suspend fun openVerified(found: List<BluetoothDevice>, bonded: BluetoothDevice, name: String?, scope: CoroutineScope): JblConnection {
        for (candidate in found) {
            val connection = try {
                openOn(candidate, bonded, name, scope)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            if (connection.deviceInfo?.addressIs(bonded.address) == true) return connection
            connection.close()
        }
        throw ExplainedConnectException(
            "More than one ${name ?: "pair of JBL headphones"} is nearby and Earshot can't tell which is yours. Try again away from the other pair",
            retry = true,
        )
    }

    private suspend fun openOn(target: BluetoothDevice, bonded: BluetoothDevice, name: String?, scope: CoroutineScope): JblConnection {
        val transport = GattLink.open(context, target, GattLinkSpec.HARMAN)
        val connection = JblConnection(transport, scope)
        try {
            val info = probe(connection)
                ?: throw RfcommConnectException("The JBL control service opened but the headphones didn't answer", looksBusy = true)
            checkProduct(info, name)
            val same = if (info.addressIs(bonded.address)) "the paired address" else "another address"
            JblLog.i("connected to ${bonded.address}: product %04x, firmware %s, device info names %s".format(info.productId ?: 0, info.firmware, same))
            return connection
        } catch (t: Throwable) {
            connection.close()
            throw t
        }
    }

    override fun adopt(transport: ByteTransport, scope: CoroutineScope): JblConnection = JblConnection(transport, scope)

    companion object {
        /** How long to wait for the device info the headset sends by itself. */
        const val UNSOLICITED_INFO_MS = 1_500L

        /** The device info, asked for once more when the first one came cut (no product id). */
        suspend fun probe(connection: JblConnection): JblDeviceInfo? {
            val first = connection.awaitDeviceInfo(UNSOLICITED_INFO_MS)
            if (first != null && !first.isPartial) return first
            connection.request(JblCommands.deviceInfoGet(), timeoutMs = 2_000L, attempts = 2) { it.command == JblCommands.DEVICE_INFO && !it.truncated }
            return connection.deviceInfo
        }

        /**
         * Throws for a product id of another JBL stack, and for a headset that didn't give its
         * product id at all unless its Bluetooth name is an H-AA model exactly. Pure, for the tests.
         */
        fun checkProduct(info: JblDeviceInfo, name: String?) {
            JblModel.refusal(info.productId)?.let { throw ExplainedConnectException(it, retry = false) }
            if (info.productId == null && JblModel.fromName(name) == null) {
                throw ExplainedConnectException("These JBL headphones didn't say which model they are, so Earshot doesn't control them", retry = false)
            }
        }
    }
}
