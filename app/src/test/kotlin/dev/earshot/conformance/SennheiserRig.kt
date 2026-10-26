package dev.earshot.conformance

import dev.earshot.bt.DeviceFamily
import dev.earshot.bt.ManagedLink
import dev.earshot.bt.SennheiserConnection
import dev.earshot.bt.SennheiserLinkFactory
import dev.earshot.core.HeadphoneState
import dev.earshot.protocol.gaia.GaiaCommand
import dev.earshot.protocol.gaia.GaiaFrame
import dev.earshot.protocol.gaia.GaiaFrameBuffer
import dev.earshot.protocol.gaia.GaiaVendor
import dev.earshot.protocol.sennheiser.SennheiserCommands as C
import dev.earshot.protocol.sennheiser.SennheiserReads
import dev.earshot.service.SennheiserSession
import dev.earshot.service.SennheiserStateMapping
import dev.earshot.virtual.DemoDevice
import dev.earshot.virtual.VirtualSennheiser
import dev.earshot.virtual.VirtualSennheiserTransport
import kotlinx.coroutines.test.TestScope

/**
 * The Sennheiser stack: the link factory's adopt path ([SennheiserConnection] with the GAIA queue
 * and its write guard), a [SennheiserSession] per connection and [SennheiserStateMapping], against
 * one [VirtualSennheiser] that keeps its state across connections. `SennheiserHeadphoneRepository`'s
 * steps: pushes collected into the session before `start()`, every command re-published from the
 * session's status.
 */
class SennheiserRig(
    test: TestScope,
    override val demo: DemoDevice,
    private val unit: VirtualSennheiser = VirtualSennheiser(),
    name: String = "MOMENTUM 4",
) : RigBase<GaiaFrame>(test, name) {

    override val family = DeviceFamily.SENNHEISER
    override val factory = SennheiserLinkFactory()

    private var transport: VirtualSennheiserTransport? = null
    private var conn: SennheiserConnection? = null
    private var session: SennheiserSession? = null
    override val connection: ManagedLink? get() = conn

    override suspend fun connect(): HeadphoneState {
        val t = VirtualSennheiserTransport(unit)
        val spy = SpyTransport(t)
        val c = factory.adopt(spy, test.backgroundScope)
        val s = SennheiserSession(c, bluetoothName)
        transport = t
        conn = c
        session = s
        beginSession(spy)
        collectPushes(c.pushes)
        runCurrent()
        s.start()
        runCurrent()
        publish(mapped(s))
        return state
    }

    private fun s(): SennheiserSession = checkNotNull(session)

    private fun mapped(s: SennheiserSession): HeadphoneState = SennheiserStateMapping.apply(state, s.status.value, s.model, now = 0)

    override suspend fun applyPush(push: GaiaFrame, current: HeadphoneState): HeadphoneState {
        s().onPush(push)
        return mapped(s())
    }

    /** The repository's `command`: a live session, then the status re-published whatever happened. */
    private suspend fun sessionCommand(block: suspend (SennheiserSession) -> Unit): HeadphoneState {
        requireOpen()
        val s = s()
        try {
            block(s)
        } finally {
            publish(mapped(s))
        }
        return state
    }

    override suspend fun refresh() = sessionCommand { it.readAll() }

    override suspend fun setMode(index: Int) = sessionCommand { it.setMode(index) }

    override fun deviceModeIndex(): Int = when {
        unit.transparencyOn -> SennheiserStateMapping.MODE_TRANSPARENCY
        unit.ancOn -> SennheiserStateMapping.MODE_ANC
        else -> SennheiserStateMapping.MODE_OFF
    }

    override suspend fun pushBattery(percent: Int): Boolean {
        unit.battery = percent
        checkNotNull(transport).push(GaiaFrame(C.VENDOR, GaiaCommand.notification(C.BATTERY_GET), byteArrayOf(percent.toByte())))
        return true
    }

    override suspend fun pollBattery(percent: Int): HeadphoneState {
        unit.battery = percent
        return sessionCommand { it.readBattery() }
    }

    /** SennheiserEndToEndTest's block-list: factory reset, DFU, pairing deletes, Qualcomm upgrade and transport ids, refused button functions. */
    override val forbiddenWrites: List<Pair<String, suspend () -> Unit>> = buildList {
        val blocked = listOf(
            C.VENDOR to 0x0040, C.VENDOR to 0x0200, C.VENDOR to 0x0201, C.VENDOR to 0x060F, C.VENDOR to 0x1C00,
            C.VENDOR to 0x1403, C.VENDOR to 0x1405, C.VENDOR to 0x1406, C.VENDOR to 0x2009,
            GaiaVendor.QUALCOMM to 0x0C00, GaiaVendor.QUALCOMM to 0x0C01, GaiaVendor.QUALCOMM to 0x0C02,
            GaiaVendor.QUALCOMM to 0x0803, GaiaVendor.QUALCOMM to 0x0804, GaiaVendor.QUALCOMM to 0x0009, GaiaVendor.QUALCOMM to 0x000A,
        )
        for ((vendor, command) in blocked) {
            add(refused(GaiaCommand.name(vendor, command)) { checkNotNull(conn).request(vendor, command, byteArrayOf(1)) })
        }
        for (function in listOf(8, 19)) {
            add(refused("button function $function") { checkNotNull(conn).request(C.VENDOR, 0x1600, byteArrayOf(0, 1, function.toByte())) })
        }
    }

    /** A read is a frame on the Lab's list for the features this headset reported: discovery, then the guard's GETs with their exact payload. */
    override fun decode(bytes: ByteArray): List<Sent> {
        val reads = SennheiserReads.forFeatures(conn?.features).map { Triple(it.vendor, it.command, it.payload.toList()) }.toSet()
        return GaiaFrameBuffer().feed(bytes).map { f ->
            Sent(GaiaCommand.name(f.vendor, f.command), f.type == GaiaCommand.TYPE_COMMAND && Triple(f.vendor, f.command, f.payload.toList()) in reads)
        }
    }

    override fun dropLink() = checkNotNull(transport).close()

    /** The push registrations went out again on this connection. */
    override fun subscribed(): Boolean = GaiaFrameBuffer().feed(spy.all()).any { it.vendor == C.VENDOR && it.command == C.REGISTER_NOTIFICATION }
}
