package dev.earshot.conformance

import android.content.ContextWrapper
import dev.earshot.bt.DeviceFamily
import dev.earshot.bt.JblConnection
import dev.earshot.bt.JblLinkFactory
import dev.earshot.bt.JblLog
import dev.earshot.bt.ManagedLink
import dev.earshot.core.HeadphoneState
import dev.earshot.protocol.jbl.JblCommands as C
import dev.earshot.protocol.jbl.JblFrame
import dev.earshot.protocol.jbl.JblStatus
import dev.earshot.protocol.jbl.JblWriteGuard
import dev.earshot.service.JblSession
import dev.earshot.service.JblStateMapping
import dev.earshot.virtual.DemoDevice
import dev.earshot.virtual.VirtualJbl
import dev.earshot.virtual.VirtualJblTransport
import kotlinx.coroutines.test.TestScope

/**
 * The JBL H-AA stack: the link factory's adopt path ([JblConnection] with its pacing, dead-link rule
 * and write guard), a [JblSession] per connection and [JblStateMapping], against one [VirtualJbl]
 * (a Wave Buds 2) that keeps its state across connections. `JblHeadphoneRepository`'s steps: the
 * session folds every frame the headset sends into its status (replies and pushes alike, so there is
 * no push channel to collect), each status change is published, every command re-publishes what the
 * session read back.
 *
 * The factory needs an Android `Context` in its constructor (`JblLinkFactory(context, targets)`),
 * unlike every other stack's. Only `family`, `competingApp` and `adopt` are used here and none of
 * them touches it, so the rig hands over an inert `ContextWrapper(null)` from the unit-test
 * android.jar rather than faking Android: `open()` (the GATT scan and link) is not exercised.
 *
 * `adopt()` gives the connection the wall clock, so its 450 ms write pace waits about 450 virtual
 * milliseconds between writes; no check depends on the exact figure.
 */
class JblRig(
    test: TestScope,
    override val demo: DemoDevice,
    private val unit: VirtualJbl = VirtualJbl(),
    name: String = "JBL Wave Buds 2",
) : RigBase<JblStatus>(test, name) {

    override val family = DeviceFamily.JBL
    override val factory = JblLinkFactory(ContextWrapper(null))

    private var transport: VirtualJblTransport? = null
    private var conn: JblConnection? = null
    private var session: JblSession? = null
    override val connection: ManagedLink? get() = conn

    init {
        // DriverConformanceTest silences the older stacks' logs and predates JBL's: without this every frame goes to stderr.
        JblLog.sink = { _, _, _ -> }
    }

    override suspend fun connect(): HeadphoneState {
        val t = VirtualJblTransport(unit)
        val spy = SpyTransport(t)
        val c = factory.adopt(spy, test.backgroundScope)
        // The factory's open() probes before handing the connection over; start() does the same for an adopted one.
        val s = JblSession(c, bluetoothName)
        transport = t
        conn = c
        session = s
        beginSession(spy)
        // The repository's `launch { s.status.collect { publish(s) } }`: a pushed frame is folded by the session, then published.
        collectPushes(s.status)
        runCurrent()
        s.start()
        runCurrent()
        publish(mapped(s))
        return state
    }

    private fun s(): JblSession = checkNotNull(session)

    private fun mapped(s: JblSession): HeadphoneState = JblStateMapping.apply(state, s.status.value, s.model, s.answered, now = 0)

    /** The repository publishes the session's current status on every change; the pushed value only says that one happened. */
    override suspend fun applyPush(push: JblStatus, current: HeadphoneState): HeadphoneState = mapped(s())

    /** The repository's `command`: a live session, then the status re-published whatever happened. */
    private suspend fun sessionCommand(block: suspend (JblSession) -> Unit): HeadphoneState {
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

    /** The unit's `[mode, on]` pairs as an Earshot slot: no pair on is Off. */
    override fun deviceModeIndex(): Int? = JblStateMapping.slotOfActive(unit.mode ?: 0)?.index

    /** A bud's level changed: the buds push the three battery TLVs. A bud that reads unknown (in its case) stays unknown. */
    override suspend fun pushBattery(percent: Int): Boolean {
        unit.pushBattery(left = percent, right = if (unit.right in 0..100) percent else unit.right, case = unit.case)
        return true
    }

    override suspend fun pollBattery(percent: Int): HeadphoneState {
        unit.left = percent
        if (unit.right in 0..100) unit.right = percent
        return sessionCommand { it.readBattery() }
    }

    /**
     * JblWriteGuard's never list and its shapes, each through [JblConnection.send]: factory reset, power off
     * and the firmware transfer (never list), the charging case's OTA launch, a TLV set (auto-off: only GETs
     * are on the allow-list), an EQ write with the custom category (230: only the app's presets are), a mode
     * set with two modes on, a noise level outside 1..7.
     */
    override val forbiddenWrites: List<Pair<String, suspend () -> Unit>> = listOf(
        "factory reset 95" to JblFrame(0x95),
        "power off 97" to JblFrame(0x97),
        "firmware transfer F0" to JblFrame(0xF0, byteArrayOf(0x01, 0x00, 0x00, 0x10, 0x00)),
        "charging case OTA A4 03" to JblFrame(0xA4, byteArrayOf(0x03)),
        "TLV set of auto-off" to JblFrame(C.INFO, byteArrayOf(0x00, 0x00, C.FID_AUTO_OFF.toByte(), 0x03, 0x01, 0x05, 0x00), longLength = true),
        "EQ write with the custom category" to C.eqSet(C.EQ_CUSTOM, List(C.EQ_BAND_HZ.size) { 1f }),
        "mode set with two modes on" to JblFrame(
            C.LISTENING,
            byteArrayOf(C.MODE_SET.toByte(), C.WIRE_ANC.toByte(), 1, C.WIRE_AMBIENT.toByte(), 1, C.WIRE_TALKTHRU.toByte(), 0),
        ),
        "noise level 8" to JblFrame(C.LISTENING, byteArrayOf(C.LEVEL_SET.toByte(), C.LEVEL_MANUAL_ANC.toByte(), 8)),
    ).map { (label, frame) -> refused(label) { checkNotNull(conn).send(frame) } }

    /** One run of the phone's bytes: the frame it frames as, or null for bytes that are no frame ([label] says so). */
    private class Wire(val frame: JblFrame?, val label: String)

    /**
     * The phone's frames from the spy's concatenated writes. Not [dev.earshot.protocol.jbl.JblFrameBuffer]: that
     * one reads the headset's lengths (the EQ is long there), and the phone's differ: the TLV and the EQ write
     * carry two length bytes, the EQ read (`AA A2 02 01 FF`) one ([JblFrame.decodeWhole]'s rule). Everything
     * the guard lets through has one of those shapes, so a byte run that is none of them is reported as such.
     */
    private fun wire(bytes: ByteArray): List<Wire> {
        val out = mutableListOf<Wire>()
        var i = 0
        while (i < bytes.size) {
            val rest = bytes.size - i
            val command = if (rest >= 2) bytes[i + 1].toInt() and 0xFF else -1
            val isEqRead = command == C.EQ && rest >= 5 && bytes[i + 2].toInt() == 2 &&
                bytes[i + 3].toInt() == C.EQ_GET && bytes[i + 4].toInt() and 0xFF == C.EQ_CURRENT
            val long = !isEqRead && (command in C.LONG_OUT || command == C.EQ)
            val header = if (long) 4 else 3
            val size = when {
                rest < header || bytes[i].toInt() and 0xFF != JblFrame.HEADER -> null
                long -> 4 + ((bytes[i + 2].toInt() and 0xFF) or ((bytes[i + 3].toInt() and 0xFF) shl 8))
                else -> 3 + (bytes[i + 2].toInt() and 0xFF)
            }
            if (size == null || size > rest) {
                out += Wire(null, "unframed bytes ($rest B)")
                break
            }
            val frame = JblFrame(command, bytes.copyOfRange(i + header, i + size), longLength = long)
            out += Wire(frame, "${C.name(command)}: $frame")
            i += size
        }
        return out
    }

    /** A frame the guard reads (a GET, the push registration, the heartbeat echo); bytes that are no frame are not reads. */
    override fun decode(bytes: ByteArray): List<Sent> = wire(bytes).map { Sent(it.label, it.frame != null && JblWriteGuard.isRead(it.frame)) }

    override fun dropLink() = checkNotNull(transport).close()

    /** The pushes-on registration (`AA 9B 02 01 01`) went out again on this connection. */
    override fun subscribed(): Boolean = wire(spy.all()).any { it.frame == C.notifyOn() }
}
