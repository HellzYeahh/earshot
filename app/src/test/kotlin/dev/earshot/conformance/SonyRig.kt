package dev.earshot.conformance

import dev.earshot.bt.DeviceFamily
import dev.earshot.bt.ManagedLink
import dev.earshot.bt.SonyConnection
import dev.earshot.bt.SonyLinkFactory
import dev.earshot.core.HeadphoneState
import dev.earshot.protocol.sony.SonyMessage
import dev.earshot.protocol.sony.SonyReads
import dev.earshot.protocol.sony.SonyTable
import dev.earshot.protocol.sony.T1
import dev.earshot.protocol.sony.T2
import dev.earshot.protocol.sony.TandemFrameBuffer
import dev.earshot.service.SonySession
import dev.earshot.service.SonySnapshot
import dev.earshot.service.SonyStateMapping
import dev.earshot.virtual.DemoDevice
import dev.earshot.virtual.VirtualSony
import dev.earshot.virtual.VirtualSonyTransport
import kotlinx.coroutines.test.TestScope

/**
 * The Sony stack: the link factory's adopt and probe, [SonyConnection] (Tandem framing, ACKs,
 * resends, the write guard), a [SonySession] per connection and [SonyStateMapping], against one
 * [VirtualSony] that keeps its settings across connections (a new transport re-attaches it, as a
 * reconnect does). `SonyHeadphoneRepository`'s steps: pushes into the session, every command
 * published from the session's snapshot.
 */
class SonyRig(
    test: TestScope,
    override val demo: DemoDevice,
    private val unit: VirtualSony = VirtualSony(),
    name: String = "WH-1000XM5",
) : RigBase<SonyMessage>(test, name) {

    override val family = DeviceFamily.SONY
    override val factory = SonyLinkFactory()

    private var transport: VirtualSonyTransport? = null
    private var conn: SonyConnection? = null
    private var session: SonySession? = null
    override val connection: ManagedLink? get() = conn

    override suspend fun connect(): HeadphoneState {
        val t = VirtualSonyTransport(unit)
        val spy = SpyTransport(t)
        val c = factory.adopt(spy, test.backgroundScope)
        // The factory's open() probes before handing the connection over; adopt() leaves it to the caller.
        factory.probe(c)
        val s = SonySession(c, clock = { test.testScheduler.currentTime })
        transport = t
        conn = c
        session = s
        beginSession(spy)
        collectPushes(c.unsolicited)
        runCurrent()
        publish(mapped(s.initialize()))
        return state
    }

    private fun s(): SonySession = checkNotNull(session)

    private fun mapped(snapshot: SonySnapshot): HeadphoneState = SonyStateMapping.apply(state, snapshot, now = 0)

    override suspend fun applyPush(push: SonyMessage, current: HeadphoneState): HeadphoneState {
        s().onPush(push)
        return mapped(s().snapshot)
    }

    private suspend fun sessionCommand(block: suspend (SonySession) -> SonySnapshot): HeadphoneState {
        requireOpen()
        publish(mapped(block(s())))
        return state
    }

    override suspend fun refresh() = sessionCommand { it.readValues() }

    override suspend fun setMode(index: Int) = sessionCommand { it.setMode(index) }

    override fun deviceModeIndex(): Int = when {
        !unit.ncEffect -> SonyStateMapping.MODE_OFF
        unit.ncMode == 0 -> SonyStateMapping.MODE_NC
        else -> SonyStateMapping.MODE_AMBIENT
    }

    /** `POWER_NTFY_STATUS [25, 00, percent, charging]`, as the headset pushes it. */
    override suspend fun pushBattery(percent: Int): Boolean {
        unit.batteryPercent = percent
        unit.push(SonyTable.T1, byteArrayOf(T1.POWER_NTFY_STATUS.toByte(), 0x00, percent.toByte(), if (unit.charging) 1 else 0))
        return true
    }

    override suspend fun pollBattery(percent: Int): HeadphoneState {
        unit.batteryPercent = percent
        return sessionCommand { it.readBattery() }
    }

    /** Firmware update, usage logging, factory power off, reset settings and unpair (sony.md §4). */
    override val forbiddenWrites: List<Pair<String, suspend () -> Unit>> = listOf(
        "T1 UPDT 30" to byteArrayOf(0x30, 0x00),
        "T1 LOG_SET_STATUS C4" to byteArrayOf(T1.LOG_SET_STATUS.toByte(), 0x00, 0x01),
        "T1 FACTORY_POWER_OFF 24 03 FF" to byteArrayOf(T1.POWER_SET_STATUS.toByte(), 0x03, 0xFF.toByte()),
        "T1 SYSTEM 09 reset settings" to byteArrayOf(T1.SYSTEM_SET_PARAM.toByte(), 0x09, 0x00),
    ).map { (label, payload) -> refused(label) { checkNotNull(conn).send(SonyMessage(SonyTable.T1, payload)) } } +
        refused("T2 PERI 3C unpair") { checkNotNull(conn).send(SonyMessage(SonyTable.T2, byteArrayOf(T2.PERI_SET_EXTENDED_PARAM.toByte(), 0x00))) }

    /** Data frames only: the ACKs the phone owes every frame it receives are not requests. */
    private fun messages(bytes: ByteArray): List<SonyMessage> = TandemFrameBuffer().feed(bytes).filter { !it.isAck }.mapNotNull { f ->
        SonyTable.forDataType(f.dataType)?.let { SonyMessage(it, f.payload) }
    }

    override fun decode(bytes: ByteArray): List<Sent> = messages(bytes).map { Sent(it.toString(), SonyReads.isRead(it)) }

    override fun dropLink() = checkNotNull(transport).close()

    /** Sony has no push subscription; what each connection renews is the confirmation prompts (`ALERT_SET_STATUS`). */
    override fun subscribed(): Boolean = messages(spy.all()).any { it.table == SonyTable.T1 && it.command == T1.ALERT_SET_STATUS }
}
