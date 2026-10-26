package dev.earshot.bt

import android.bluetooth.BluetoothDevice
import android.content.Context
import dev.earshot.protocol.jbl.JblCommands
import dev.earshot.protocol.jbl.JblDeviceInfo
import dev.earshot.protocol.jbl.JblModel
import kotlinx.coroutines.CoroutineScope

/**
 * Reaches a JBL headset on its Harman GATT service ("excelpoint.com", [GattLinkSpec.HARMAN]): the
 * bonded device itself when it is LE or dual-mode, else the separate `<name>-LE` control address
 * the buds advertise ([GattTargets]). Only H-AA models get here ([JblModel]).
 *
 * The probe is the device info: the headset sends `AA 12` by itself when the link opens (seen in
 * jbl-control's Wave Buds 2 capture), else Earshot asks with `AA 11 00`. Its product id then
 * decides: a Protocol 4 or Airoha model ([JblModel.refusal]) is closed before anything else is
 * sent. A link that opens and stays silent is JBL's app holding the control connection.
 */
class JblLinkFactory(
    private val context: Context,
    private val targets: GattTargets = GattTargets(context),
) : LinkFactory<JblConnection> {

    override val family: DeviceFamily get() = DeviceFamily.JBL

    override val competingApp: String get() = "JBL Headphones"

    override suspend fun open(device: BluetoothDevice, name: String?, prefs: DevicePrefs, scope: CoroutineScope): JblConnection {
        val target = targets.find(device, name, GattLinkSpec.HARMAN)
            ?: throw RfcommConnectException("Earshot couldn't find the JBL control connection nearby", looksBusy = false)
        val transport = GattLink.open(context, target, GattLinkSpec.HARMAN)
        val connection = JblConnection(transport, scope)
        try {
            val info = probe(connection)
                ?: throw RfcommConnectException("The JBL control service opened but the headphones didn't answer", looksBusy = true)
            checkProduct(info)
            JblLog.i("connected to ${device.address}: product %04x, firmware %s".format(info.productId ?: 0, info.firmware))
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

        suspend fun probe(connection: JblConnection): JblDeviceInfo? {
            connection.awaitDeviceInfo(UNSOLICITED_INFO_MS)?.let { return it }
            connection.request(JblCommands.deviceInfoGet(), timeoutMs = 2_000L, attempts = 2) { it.command == JblCommands.DEVICE_INFO }
            return connection.deviceInfo
        }

        /** Throws for a product id of another JBL stack; pure, for the tests. */
        fun checkProduct(info: JblDeviceInfo) {
            JblModel.refusal(info.productId)?.let { throw RfcommConnectException(it, looksBusy = false) }
        }
    }
}
