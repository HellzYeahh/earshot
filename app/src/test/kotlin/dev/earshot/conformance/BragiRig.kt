package dev.earshot.conformance

import dev.earshot.bt.BragiConnection
import dev.earshot.bt.BragiFrameReader
import dev.earshot.bt.BragiLinkFactory
import dev.earshot.bt.BragiSession
import dev.earshot.bt.DeviceFamily
import dev.earshot.bt.ManagedLink
import dev.earshot.core.HeadphoneState
import dev.earshot.protocol.bragi.BosMessageType
import dev.earshot.protocol.bragi.BosRpcPacket
import dev.earshot.protocol.bragi.BragiServices
import dev.earshot.protocol.bragi.Hearing
import dev.earshot.protocol.bragi.HearingMode
import dev.earshot.protocol.bragi.PppFraming
import dev.earshot.service.CrusherProtocol
import dev.earshot.virtual.DemoDevice
import dev.earshot.virtual.VirtualCrusher
import dev.earshot.virtual.VirtualCrusherTransport
import kotlinx.coroutines.test.TestScope

/**
 * The Bragi stack (Crusher 1080, the pairing Crusher, Method 360 / 540): the real
 * [BragiConnection] over a [VirtualCrusherTransport] and one [CrusherProtocol] kept across
 * connections, as AppContainer keeps one. The connect sequence and the command wrapper are
 * `CrusherHeadphoneRepository`'s: `onConnected` with the secure-session hook (always passed; the
 * protocol only calls it when `dev_info` asks), pushes parked during a command.
 */
class BragiRig(
    test: TestScope,
    override val demo: DemoDevice,
    private val device: VirtualCrusher,
    name: String,
) : RigBase<BosRpcPacket>(test, name) {

    override val family = DeviceFamily.BRAGI
    override val factory = BragiLinkFactory()

    private val protocol = CrusherProtocol()

    /** The pairing is kept in memory; the user accepts the passcode the headphones show. */
    private val session = BragiSession(MemoryTrust(), prompt = { _, _ -> true })

    private var transport: VirtualCrusherTransport? = null
    private var conn: BragiConnection? = null
    override val connection: ManagedLink? get() = conn
    override val parksPushesDuringCommands = true

    override val notApplicable = mapOf(
        Check.BATTERY_PUSH to "BOS-RPC has no battery push: the Bragi stack polls device.batt_info (CrusherProtocol.pollBattery)",
    )

    override suspend fun connect(): HeadphoneState {
        val t = VirtualCrusherTransport(device)
        val spy = SpyTransport(t)
        val c = BragiConnection(spy, test.backgroundScope, clock = { test.testScheduler.currentTime })
        transport = t
        conn = c
        beginSession(spy)
        collectPushes(c.unsolicited)
        runCurrent()
        return command { s -> protocol.onConnected(c, s, secure = { session.establish(c, ADDRESS) { cipher -> c.installCipher(cipher) } }) }
    }

    private fun c(): BragiConnection = checkNotNull(conn)

    override suspend fun applyPush(push: BosRpcPacket, current: HeadphoneState): HeadphoneState? = protocol.onUnsolicited(push, current)

    override suspend fun refresh() = command { s -> protocol.refresh(c(), s) }

    override suspend fun setMode(index: Int) = command { s -> protocol.setMode(c(), s, index) }

    /** Slots 0 Quiet (ANC), 1 Aware (AT), 2 Off, as the protocol numbers them. */
    override fun deviceModeIndex(): Int? = when (device.hearingMode) {
        HearingMode.ANC -> 0
        HearingMode.AT -> 1
        HearingMode.OFF -> 2
        else -> null
    }

    override suspend fun pushBattery(percent: Int): Boolean = false

    override suspend fun pollBattery(percent: Int): HeadphoneState {
        device.batteryPercent = percent
        return command { s -> protocol.pollBattery(c(), s) }
    }

    override val forbiddenWrites: List<Pair<String, suspend () -> Unit>> = listOf(
        refused("service 99 (a firmware-update style service)") { c().call(BragiServices.Address(service = 99, method = 1)) },
        refused("device.99 (an unknown method of an allowed service)") { c().call(BragiServices.Address(service = BragiServices.DEVICE, method = 99)) },
    )

    /** Cleartext frames as they are; sealed ones opened with the device's end of the session cipher (opening is stateless). */
    private fun packets(bytes: ByteArray): List<BosRpcPacket> = BragiFrameReader().feed(bytes).mapNotNull { f ->
        val inner = if (f.protocolId == PppFraming.PROTOCOL_SECURE) device.open(f.body) else f.body
        inner?.let { BosRpcPacket.decode(it) }
    }

    override fun decode(bytes: ByteArray): List<Sent> = packets(bytes).map {
        Sent("${it.type} ${it.serviceId}.${it.methodId}", it.type == BosMessageType.REQUEST && BragiServices.isRead(it.address))
    }

    override fun dropLink() = checkNotNull(transport).close()

    /** The streaming mode read asked for pushes again on this connection. */
    override fun subscribed(): Boolean = packets(spy.all()).any {
        it.address == BragiServices.MODE_READ && it.payload.contentEquals(Hearing.modeRead(subscribe = true))
    }
}
