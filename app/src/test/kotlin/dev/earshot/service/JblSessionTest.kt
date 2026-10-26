package dev.earshot.service

import dev.earshot.bt.ByteTransport
import dev.earshot.bt.JblConnection
import dev.earshot.bt.JblLog
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.protocol.jbl.JblBattery
import dev.earshot.protocol.jbl.JblCommands as C
import dev.earshot.protocol.jbl.JblFrame
import dev.earshot.protocol.jbl.JblModel
import dev.earshot.protocol.jbl.JblWriteGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * jbl-control's capture of a Wave Buds 2 (firmware 2.7.0, MTU 517; the same frames as `JblWireTest`
 * in `:protocol`) and builders for the frames a headset sends, so the tests can shape other units.
 */
internal object JblCaptured {
    const val DEVICE_INFO = "aa 12 25 4a 42 4c 20 57 61 76 65 20 42 75 64 73 20 32 00 00 21 00 64 ff 2d 2f 6a 1b 50 02 07 00 64 f2 96 10 ff ff 02 00"
    const val CALL_PUSH = "aa 06 05 00 02 00 1f 01 00"
    const val MODES = "aa 91 07 12 01 00 02 01 03 00"
    const val INFO = "aa 06 41 00 02 00 01 10 4a 42 4c 20 57 61 76 65 20 42 75 64 73 20 32 00 00 05 03 02 07 00 00 06 01 06 00 0b 03 00 00 00 00 0c 02 00 00 00 0d 02 00 02 00 1a 02 00 64 00 1b 02 00 ff 00 1c 02 00 64 00 20 01 00"
    val EQ = "aa a2 74 00 04" + " 00".repeat(115)
    const val VOICE_PROMPTS = "aa 93 02 05 01"

    fun bytes(hex: String): ByteArray = hex.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }

    /** `AA cmd len payload`, one-byte length. */
    fun frame(command: Int, vararg payload: Int): ByteArray =
        byteArrayOf(0xAA.toByte(), command.toByte(), payload.size.toByte()) + ByteArray(payload.size) { payload[it].toByte() }

    /** A TLV return (`AA 06 len16 02 [fid u16 BE][len][value]…`). */
    fun tlv(entries: Map<Int, List<Int>>): ByteArray {
        val body = listOf(0x02) + entries.flatMap { (fid, v) -> listOf(fid shr 8, fid and 0xFF, v.size) + v }
        return byteArrayOf(0xAA.toByte(), 0x06, (body.size and 0xFF).toByte(), (body.size shr 8).toByte()) + ByteArray(body.size) { body[it].toByte() }
    }

    fun deviceInfo(name: String, pid: Int, batteryByte: Int, firmware: List<Int> = listOf(2, 7, 0)): ByteArray {
        val nameBytes = name.toByteArray().copyOf(16).map { it.toInt() and 0xFF }
        val body = nameBytes + listOf(pid and 0xFF, pid shr 8, 0x00, batteryByte) + listOf(0xff, 0x2d, 0x2f, 0x6a, 0x1b, 0x50) +
            firmware + listOf(0x64, 0xf2, 0x96, 0x10, 0xff, 0xff, 0x02, 0x00)
        return frame(0x12, *body.toIntArray())
    }

    fun modeStatus(modes: List<Int>, active: Int): ByteArray =
        frame(0x91, 0x12, *modes.sorted().flatMap { listOf(it, if (it == active) 1 else 0) }.toIntArray())

    fun level(value: Int): ByteArray = frame(0x91, 0x22, 0x04, value)

    fun eqReturn(category: Int): ByteArray = byteArrayOf(0xAA.toByte(), 0xA2.toByte(), 0x74, 0x00, 0x04, category.toByte()) + ByteArray(114)

    fun voicePrompts(on: Boolean): ByteArray = frame(0x93, 0x05, if (on) 1 else 0)

    /** The TLV values the Wave Buds 2 answered with: name, firmware, TWS, auto-off, spatial, sidetone, batteries, SafeSound. */
    fun waveBuds2Tlv(): MutableMap<Int, List<Int>> = linkedMapOf(
        0x01 to "JBL Wave Buds 2".toByteArray().copyOf(16).map { it.toInt() and 0xFF },
        0x05 to listOf(2, 7, 0),
        0x06 to listOf(6),
        0x0B to listOf(0, 0, 0),
        0x0C to listOf(0, 0),
        0x0D to listOf(0, 2),
        0x1A to listOf(0, 100),
        0x1B to listOf(0, 0xFF),
        0x1C to listOf(0, 100),
        0x20 to listOf(0),
    )
}

/**
 * A scripted JBL H-AA headset behind a [ByteTransport], answering what the Wave Buds 2 capture
 * answered. Everything the phone writes is recorded as the frame it decodes to ([written]); a
 * feature the real unit stayed silent on (the level read, `AA 25`) stays silent unless a test turns
 * it on. The fields are the unit's own state, so a test can shape other models and read back what a
 * write did.
 */
internal class ScriptedJbl(
    private val clock: () -> Long = { 0L },
    private val scope: CoroutineScope? = null,
) : ByteTransport {
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    private var closed = false

    val written = mutableListOf<JblFrame>()
    val writeTimes = mutableListOf<Long>()

    var name = "JBL Wave Buds 2"
    var pid = 0x2100
    var deviceBatteryByte = 0x64
    var answersDeviceInfo = true
    val tlv: MutableMap<Int, List<Int>> = JblCaptured.waveBuds2Tlv()

    /** The unit pushes the call status the instant it is asked for the TLV and sends the real return this much later; null = no push. */
    var callPushThenReturnAfterMs: Long? = null

    var modes: List<Int> = listOf(1, 2, 3)

    /** The mode that is on: 0 = off, else a wire number. */
    var active = 2
    var declineModes = false
    var replyToModeSet = true
    var answersLevel = false
    var level = 3
    var answersEq = true
    var eqCategory = 0
    var answersVoicePrompts = true
    var voicePrompts = true

    /** The `AA 25` bud pair the unit answers with (primary %, secondary %, case %); null = silent, as the Wave Buds 2 is. */
    var twsBattery: Triple<Int, Int, Int>? = null

    override val isConnected: Boolean get() = !closed
    override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()

    /** The device info the unit sends by itself when the link opens. */
    fun greet() = push(JblCaptured.deviceInfo(name, pid, deviceBatteryByte))

    fun push(bytes: ByteArray) {
        inbox.trySend(bytes)
    }

    override suspend fun write(bytes: ByteArray) {
        if (closed) throw IOException("closed")
        val frame = JblFrame.decodeWhole(bytes) ?: error("not a whole H-AA frame: ${JblCaptured.hex(bytes)}")
        written += frame
        writeTimes += clock()
        respond(frame)
    }

    override fun close() {
        closed = true
        inbox.close()
    }

    private fun respond(f: JblFrame) {
        when (f.command) {
            C.DEVICE_INFO_GET -> if (answersDeviceInfo) push(JblCaptured.deviceInfo(name, pid, deviceBatteryByte))
            C.LISTENING -> when (f.sub) {
                C.MODE_GET -> push(JblCaptured.modeStatus(modes, active))
                C.MODE_SET -> {
                    if (!declineModes) active = (1 until f.size - 1 step 2).firstOrNull { f[it + 1] == 1 }?.let { f[it] } ?: 0
                    if (replyToModeSet) push(JblCaptured.modeStatus(modes, active))
                }
                C.LEVEL_GET -> if (answersLevel) push(JblCaptured.level(level))
                C.LEVEL_SET -> level = f[2]
            }
            C.INFO -> {
                val asked = (1 until f.size step 2).map { (f[it] shl 8) or f[it + 1] }
                val answer = JblCaptured.tlv(asked.filter { it in tlv }.associateWith { tlv.getValue(it) })
                val delayMs = callPushThenReturnAfterMs
                if (delayMs == null) {
                    if (asked.any { it in tlv }) push(answer)
                } else {
                    push(JblCaptured.bytes(JblCaptured.CALL_PUSH))
                    checkNotNull(scope).launch { delay(delayMs); push(answer) }
                }
            }
            C.EQ -> if (f.longLength) eqCategory = f[1] else if (answersEq) push(JblCaptured.eqReturn(eqCategory))
            C.VOICE_PROMPTS -> when (f.sub) {
                0x04 -> if (answersVoicePrompts) push(JblCaptured.voicePrompts(voicePrompts))
                0x03 -> voicePrompts = f[1] == 1
            }
            C.BATTERY -> twsBattery?.let { (a, b, case) -> push(JblCaptured.frame(C.BATTERY, 0x01, 0x00, 0x00, a, b, 0x00, case)) }
        }
    }
}

private fun List<JblFrame>.hexes(): List<String> = map { JblCaptured.hex(it.encode()) }

@OptIn(ExperimentalCoroutinesApi::class)
class JblSessionTest {

    @Before
    fun quiet() {
        JblLog.sink = { _, _, _ -> }
    }

    private class Rig(val session: JblSession, val unit: ScriptedJbl, val conn: JblConnection)

    private fun TestScope.rig(name: String? = null, greet: Boolean = true, paceMs: Long = JblConnection.PACE_MS, configure: ScriptedJbl.() -> Unit = {}): Rig {
        val unit = ScriptedJbl({ testScheduler.currentTime }, backgroundScope).apply(configure)
        if (greet) unit.greet()
        val conn = JblConnection(unit, backgroundScope, clock = { testScheduler.currentTime }, paceMs = paceMs)
        return Rig(JblSession(conn, name), unit, conn)
    }

    @Test fun `the captured builders are the captured frames`() {
        assertEquals(JblCaptured.DEVICE_INFO, JblCaptured.hex(JblCaptured.deviceInfo("JBL Wave Buds 2", 0x2100, 0x64)))
        assertEquals(JblCaptured.INFO, JblCaptured.hex(JblCaptured.tlv(JblCaptured.waveBuds2Tlv())))
        assertEquals(JblCaptured.MODES, JblCaptured.hex(JblCaptured.modeStatus(listOf(1, 2, 3), 2)))
        assertEquals(JblCaptured.EQ, JblCaptured.hex(JblCaptured.eqReturn(0)))
        assertEquals(JblCaptured.VOICE_PROMPTS, JblCaptured.hex(JblCaptured.voicePrompts(true)))
    }

    // ---- Connect --------------------------------------------------------------------------

    @Test fun `start registers for pushes, then probes each feature in order and sends nothing the guard refuses`() = runTest {
        // No write pacing here, so the gap after the registration is the session's own pause.
        val r = rig(paceMs = 0)
        r.session.start()

        assertEquals(
            listOf(
                "aa 9b 02 01 01", // pushes on
                "aa 91 01 11", // modes
                "aa 91 01 21", // level: the Wave Buds 2 never answers
                JblCaptured.hex(C.infoGet().encode()),
                "aa a2 02 01 ff", // EQ
                "aa 93 01 04", // voice prompts
            ),
            r.unit.written.hexes(),
        )
        assertTrue(r.unit.written.all { JblWriteGuard.isAllowed(it) })
        assertEquals(JblSession.NOTIFY_SETTLE_MS, r.unit.writeTimes[1] - r.unit.writeTimes[0], "the first read waits for the registration to settle")

        val s = r.session.status.value
        assertEquals(JblModel.WAVE_BUDS_2, r.session.model)
        assertEquals(listOf(1, 2, 3), s.modes)
        assertEquals(C.WIRE_AMBIENT, s.activeMode)
        assertEquals(JblBattery(100, false), s.left)
        assertEquals(0, s.eqCategory)
        assertEquals(true, s.voicePrompts)
        assertNull(s.ancLevel)
        // The silent level read stays unknown: no slider, no write.
        assertEquals(setOf(JblFeature.MODES, JblFeature.TLV, JblFeature.EQ, JblFeature.VOICE_PROMPTS), r.session.answered)
        r.conn.close()
    }

    @Test fun `start asks for the device info when the headset does not send it`() = runTest {
        val r = rig(greet = false)
        r.session.start()
        assertEquals("aa 11 00", JblCaptured.hex(r.unit.written[0].encode()))
        assertEquals("aa 9b 02 01 01", JblCaptured.hex(r.unit.written[1].encode()))
        assertEquals(JblModel.WAVE_BUDS_2, r.session.model)
        r.conn.close()
    }

    @Test fun `a headset that never says who it is is left alone`() = runTest {
        val r = rig(greet = false) { answersDeviceInfo = false }
        val e = assertFailsWith<HeadphoneCommandException> { r.session.start() }
        assertEquals("The headphones didn't answer", e.message)
        assertTrue(r.unit.written.all { it.command == C.DEVICE_INFO_GET }, "nothing but the device-info ask went out")
        r.conn.close()
    }

    @Test fun `the model comes from the product id, else from the exact name`() = runTest {
        val byPid = rig(name = "Somebody's buds") { pid = 0x2085; name = "JBL Tour One M2" }
        byPid.session.start()
        assertEquals(JblModel.TOUR_ONE_M2, byPid.session.model)
        byPid.conn.close()

        val byName = rig(name = "JBL Wave Buds 2", greet = false)
        assertEquals(JblModel.WAVE_BUDS_2, byName.session.model, "before the device info arrives the Bluetooth name decides")
        byName.conn.close()

        val neither = rig(name = "JBL Charge 5") { pid = 0x7777 }
        neither.session.start()
        assertNull(neither.session.model)
        neither.conn.close()
    }

    @Test fun `a pushed call status is not the answer to the TLV read`() = runTest {
        // The call-status push is a TLV return too. If it counted, the next read would go out at once.
        val r = rig { callPushThenReturnAfterMs = 1_000 }
        r.session.start()
        val tlv = r.unit.written.indexOfFirst { it.command == C.INFO }
        val next = r.unit.written[tlv + 1]
        assertEquals(C.EQ, next.command)
        assertTrue(r.unit.writeTimes[tlv + 1] - r.unit.writeTimes[tlv] >= 1_000, "waited for the return that carries the ids asked for")
        assertEquals(JblBattery(100, false), r.session.status.value.left)
        r.conn.close()
    }

    // ---- Battery --------------------------------------------------------------------------

    @Test fun `older earbuds without TLV batteries are asked with AA 25, and later reads use it`() = runTest {
        val r = rig {
            (0x1A..0x1C).forEach { tlv.remove(it) }
            twsBattery = Triple(80, 90, 64)
        }
        r.session.start()
        val last = r.unit.written.last()
        assertEquals("aa 25 01 00", JblCaptured.hex(last.encode()))
        assertEquals(JblBattery(80, false) to JblBattery(90, false), r.session.status.value.budPair)
        assertTrue(JblFeature.TWS_BATTERY in r.session.answered)

        r.unit.written.clear()
        r.unit.twsBattery = Triple(70, 90, 64)
        r.session.readBattery()
        assertEquals(listOf("aa 25 01 00"), r.unit.written.hexes())
        assertEquals(JblBattery(70, false) to JblBattery(90, false), r.session.status.value.budPair)
        r.conn.close()
    }

    @Test fun `known headphones are not asked for the bud battery, and read it from the device info`() = runTest {
        val r = rig {
            pid = 0x2085
            name = "JBL Tour One M2"
            deviceBatteryByte = 0x80 or 55 // charging, 55 %
            (0x1A..0x1C).forEach { tlv.remove(it) }
        }
        r.session.start()
        assertTrue(r.unit.written.none { it.command == C.BATTERY }, "headphones have no AA 25")
        assertEquals(JblBattery(55, true), r.session.status.value.info?.battery)

        r.unit.written.clear()
        r.unit.deviceBatteryByte = 54
        r.session.readBattery()
        assertEquals(listOf("aa 11 00"), r.unit.written.hexes())
        assertEquals(JblBattery(54, false), r.session.status.value.info?.battery)
        r.conn.close()
    }

    @Test fun `the battery poll reads the TLV battery ids`() = runTest {
        val r = rig()
        r.session.start()
        r.unit.written.clear()
        r.unit.tlv[C.FID_BATTERY_LEFT] = listOf(0, 80)
        r.session.readBattery()
        assertEquals(listOf(JblCaptured.hex(C.infoGet(C.BUD_BATTERY_FIDS).encode())), r.unit.written.hexes())
        assertEquals(JblBattery(80, false), r.session.status.value.left)
        r.conn.close()
    }

    // ---- Pushes ---------------------------------------------------------------------------

    @Test fun `pushes fold in as they arrive`() = runTest {
        val r = rig()
        r.session.start()
        r.unit.push(JblCaptured.modeStatus(listOf(1, 2, 3), 1))
        runCurrent()
        assertEquals(C.WIRE_ANC, r.session.status.value.activeMode)
        r.unit.push(JblCaptured.tlv(mapOf(C.FID_BATTERY_RIGHT to listOf(1, 42))))
        runCurrent()
        assertEquals(JblBattery(42, true), r.session.status.value.right)
        r.conn.close()
    }

    // ---- Modes ----------------------------------------------------------------------------

    @Test fun `setMode writes the pairs the headset listed and reads the answer`() = runTest {
        val r = rig()
        r.session.start()
        r.unit.written.clear()

        r.session.setMode(JblStateMapping.MODE_ANC)
        assertEquals(listOf("aa 91 07 10 01 01 02 00 03 00"), r.unit.written.hexes())
        assertEquals(C.WIRE_ANC, r.session.status.value.activeMode)

        r.session.setMode(JblStateMapping.MODE_TALKTHRU)
        assertEquals("aa 91 07 10 01 00 02 00 03 01", JblCaptured.hex(r.unit.written.last().encode()))

        r.session.setMode(JblStateMapping.MODE_OFF)
        assertEquals("aa 91 07 10 01 00 02 00 03 00", JblCaptured.hex(r.unit.written.last().encode()))
        assertEquals(0, r.session.status.value.activeMode)
        assertEquals(JblStateMapping.MODE_OFF, JblStateMapping.slotOfActive(r.session.status.value.activeMode)?.index)
        assertTrue(r.unit.written.all { JblWriteGuard.isAllowed(it) })
        r.conn.close()
    }

    @Test fun `a headset that lists two modes gets only those pairs`() = runTest {
        val r = rig { modes = listOf(1, 2) }
        r.session.start()
        r.unit.written.clear()
        r.session.setMode(JblStateMapping.MODE_AMBIENT)
        assertEquals(listOf("aa 91 05 10 01 00 02 01"), r.unit.written.hexes())
        r.conn.close()
    }

    @Test fun `a mode the headset declines says why`() = runTest {
        val r = rig { declineModes = true }
        r.session.start()
        val e = assertFailsWith<HeadphoneCommandException> { r.session.setMode(JblStateMapping.MODE_ANC) }
        assertEquals("The headphones stayed in Ambient Aware (JBL earbuds may need both buds in for that)", e.message)
        assertEquals(C.WIRE_AMBIENT, r.session.status.value.activeMode)
        r.conn.close()

        // Headphones have no bud rule to blame.
        val headphones = rig {
            pid = 0x2085
            name = "JBL Tour One M2"
            declineModes = true
            (0x1A..0x1C).forEach { tlv.remove(it) } // headphones report no buds
            tlv[C.FID_BATTERY_HEADBAND] = listOf(0, 80)
        }
        headphones.session.start()
        val h = assertFailsWith<HeadphoneCommandException> { headphones.session.setMode(JblStateMapping.MODE_OFF) }
        assertEquals("The headphones stayed in Ambient Aware", h.message)
        headphones.conn.close()
    }

    @Test fun `a change reported only on request is read back`() = runTest {
        val r = rig { replyToModeSet = false }
        r.session.start()
        r.unit.written.clear()
        r.session.setMode(JblStateMapping.MODE_ANC)
        assertEquals(listOf("aa 91 07 10 01 01 02 00 03 00", "aa 91 01 11"), r.unit.written.hexes())
        assertEquals(C.WIRE_ANC, r.session.status.value.activeMode)
        r.conn.close()
    }

    @Test fun `an unlisted or unknown slot is refused before anything is written`() = runTest {
        val r = rig { modes = listOf(1, 2) }
        r.session.start()
        r.unit.written.clear()
        val unlisted = assertFailsWith<HeadphoneCommandException> { r.session.setMode(JblStateMapping.MODE_TALKTHRU) }
        assertEquals("These headphones don't have TalkThru", unlisted.message)
        for (slot in listOf(4, 99, -1)) assertFailsWith<HeadphoneCommandException>("slot $slot") { r.session.setMode(slot) }
        assertTrue(r.unit.written.isEmpty())
        assertTrue(r.conn.isOpen)
        r.conn.close()
    }

    @Test fun `setMode before the modes are known is refused`() = runTest {
        val r = rig(greet = false)
        val e = assertFailsWith<HeadphoneCommandException> { r.session.setMode(JblStateMapping.MODE_ANC) }
        assertEquals("These headphones didn't report their listening modes", e.message)
        assertTrue(r.unit.written.isEmpty())
        r.conn.close()
    }

    // ---- Vendor controls -------------------------------------------------------------------

    @Test fun `the level slider writes the level and reads it back`() = runTest {
        val r = rig { answersLevel = true }
        r.session.start()
        assertTrue(JblFeature.LEVEL in r.session.answered)
        assertEquals(3, r.session.status.value.ancLevel)
        r.unit.written.clear()

        r.session.setVendorControl(JblStateMapping.ANC_LEVEL, 5)
        assertEquals(listOf("aa 91 03 20 04 05", "aa 91 01 21"), r.unit.written.hexes())
        assertEquals(5, r.session.status.value.ancLevel)

        r.unit.written.clear()
        for (bad in listOf(0, 8, -1)) assertFailsWith<HeadphoneCommandException>("level $bad") { r.session.setVendorControl(JblStateMapping.ANC_LEVEL, bad) }
        assertTrue(r.unit.written.isEmpty())
        r.conn.close()
    }

    @Test fun `a control whose read never answered is not written`() = runTest {
        val r = rig() // the Wave Buds 2 stays silent on the level read
        r.session.start()
        r.unit.written.clear()
        val e = assertFailsWith<HeadphoneCommandException> { r.session.setVendorControl(JblStateMapping.ANC_LEVEL, 3) }
        assertTrue(e.message!!.contains("anc_level"), e.message)
        assertTrue(r.unit.written.isEmpty())
        r.conn.close()
    }

    @Test fun `the EQ preset writes the app's curve and reads the EQ back`() = runTest {
        val r = rig()
        r.session.start()
        r.unit.written.clear()

        val bass = C.EQ_PRESETS.indexOfFirst { it.label == "Bass" }
        r.session.setVendorControl(JblStateMapping.EQ_PRESET, bass)
        val preset = C.eqPreset(3)!!
        assertEquals(JblCaptured.hex(C.eqSet(preset.category, preset.gainsDb).encode()), JblCaptured.hex(r.unit.written[0].encode()))
        assertEquals("aa a2 02 01 ff", JblCaptured.hex(r.unit.written[1].encode()))
        assertEquals(3, r.unit.eqCategory)
        assertEquals(3, r.session.status.value.eqCategory)

        r.unit.written.clear()
        for (bad in listOf(-1, C.EQ_PRESETS.size, 99)) assertFailsWith<HeadphoneCommandException>("preset $bad") { r.session.setVendorControl(JblStateMapping.EQ_PRESET, bad) }
        assertTrue(r.unit.written.isEmpty())
        assertTrue(r.unit.written.all { JblWriteGuard.isAllowed(it) })
        r.conn.close()
    }

    @Test fun `voice prompts write the switch and read it back`() = runTest {
        val r = rig()
        r.session.start()
        r.unit.written.clear()
        r.session.setVendorControl(JblStateMapping.VOICE_PROMPTS, 0)
        assertEquals(listOf("aa 93 02 03 00", "aa 93 01 04"), r.unit.written.hexes())
        assertEquals(false, r.session.status.value.voicePrompts)
        r.session.setVendorControl(JblStateMapping.VOICE_PROMPTS, 1)
        assertEquals("aa 93 02 03 01", JblCaptured.hex(r.unit.written[2].encode()))
        assertEquals(true, r.session.status.value.voicePrompts)

        r.unit.written.clear()
        assertFailsWith<HeadphoneCommandException> { r.session.setVendorControl(JblStateMapping.VOICE_PROMPTS, 2) }
        assertTrue(r.unit.written.isEmpty())
        r.conn.close()
    }

    @Test fun `an unknown control id is refused and nothing is written`() = runTest {
        val r = rig()
        r.session.start()
        r.unit.written.clear()
        for (id in listOf("bass_boost", "", "ANC_LEVEL")) {
            assertFailsWith<HeadphoneCommandException>("id '$id'") { r.session.setVendorControl(id, 1) }
        }
        assertTrue(r.unit.written.isEmpty())
        assertTrue(r.conn.isOpen)
        r.conn.close()
    }

    @Test fun `a dead link throws and the session says so`() = runTest {
        val r = rig()
        r.session.start()
        r.conn.close()
        assertFailsWith<HeadphoneCommandException> { r.session.readAll() }
        assertFailsWith<HeadphoneCommandException> { r.session.setMode(JblStateMapping.MODE_ANC) }
        assertFailsWith<HeadphoneCommandException> { r.session.setVendorControl(JblStateMapping.VOICE_PROMPTS, 0) }
    }
}
