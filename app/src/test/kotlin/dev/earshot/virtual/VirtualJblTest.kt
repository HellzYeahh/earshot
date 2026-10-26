package dev.earshot.virtual

import dev.earshot.protocol.jbl.JblBattery
import dev.earshot.protocol.jbl.JblCommands as C
import dev.earshot.protocol.jbl.JblDeviceInfo
import dev.earshot.protocol.jbl.JblFrame
import dev.earshot.protocol.jbl.JblFrameBuffer
import dev.earshot.protocol.jbl.JblStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [VirtualJbl] against jbl-control's capture of a Wave Buds 2 (firmware 2.7.0): each captured read
 * must get the captured bytes, each write must change what the next read says, and the frames the
 * real buds never answer must stay silent. Requests are passed through [JblFrame.decodeWhole] first,
 * as [VirtualJblTransport] does for the phone's GATT writes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VirtualJblTest {

    private fun hex(s: String): ByteArray = s.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.hex(): String = joinToString(" ") { "%02x".format(it) }

    /** What the real buds sent, byte for byte (the same capture JblWireTest folds). */
    private val captureAddress = hex("ff 2d 2f 6a 1b 50")
    private val rxDeviceInfo = "aa 12 25 4a 42 4c 20 57 61 76 65 20 42 75 64 73 20 32 00 00 21 00 64 ff 2d 2f 6a 1b 50 02 07 00 64 f2 96 10 ff ff 02 00"
    private val rxCallPush = "aa 06 05 00 02 00 1f 01 00"
    private val rxModes = "aa 91 07 12 01 00 02 01 03 00"
    private val rxInfo = "aa 06 41 00 02 00 01 10 4a 42 4c 20 57 61 76 65 20 42 75 64 73 20 32 00 00 05 03 02 07 00 00 06 01 06 00 0b 03 00 00 00 00 0c 02 00 00 00 0d 02 00 02 00 1a 02 00 64 00 1b 02 00 ff 00 1c 02 00 64 00 20 01 00"
    private val rxEq = "aa a2 74 00 04" + " 00".repeat(115)
    private val rxVoicePrompts = "aa 93 02 05 01"
    private val txInfoGet = "aa 06 15 00 01 00 01 00 05 00 06 00 0b 00 0c 00 0d 00 1a 00 1b 00 1c 00 20"

    private val allModes = listOf(C.WIRE_ANC, C.WIRE_AMBIENT, C.WIRE_TALKTHRU)

    /** A unit that is the captured pair, address included. */
    private fun capture() = VirtualJbl(address = captureAddress)

    private fun VirtualJbl.reply(request: JblFrame): List<JblFrame> = answer(JblFrame.decodeWhole(request.encode())!!)

    private fun VirtualJbl.ask(request: JblFrame): List<String> = reply(request).map { it.encode().hex() }

    private fun VirtualJbl.ask(request: String): List<String> = answer(JblFrame.decodeWhole(hex(request))!!).map { it.encode().hex() }

    // ---- The captured reads ------------------------------------------------------------------

    @Test fun `every captured read gets the captured bytes`() {
        val unit = capture()
        assertEquals(listOf(rxDeviceInfo), unit.hello().map { it.encode().hex() }, "the unsolicited device info")
        assertEquals(listOf(rxDeviceInfo), unit.ask("aa 11 00"))
        assertEquals(listOf(rxCallPush), unit.ask("aa 9b 02 01 01"), "pushes on: no reply, then the call-status push")
        assertEquals(listOf(rxModes), unit.ask("aa 91 01 11"))
        assertEquals(listOf(rxInfo), unit.ask(txInfoGet))
        assertEquals(listOf(rxEq), unit.ask("aa a2 02 01 ff"))
        assertEquals(listOf(rxVoicePrompts), unit.ask("aa 93 01 04"))
        assertEquals(listOf("aa 98 03 02 02 00"), unit.ask("aa 98 01 01"))
        assertEquals(listOf("aa 23 03 02 00 00"), unit.ask("aa 23 01 01"))
        assertEquals(listOf("aa 80 09 02 00 01 2e 00 18 01 ff ff"), unit.ask("aa 80 01 01"))
        assertTrue(unit.notificationsOn)
    }

    @Test fun `the made-up address is the only difference from the capture`() {
        val bytes = VirtualJbl().hello().single().encode()
        val captured = hex(rxDeviceInfo)
        assertEquals(captured.size, bytes.size)
        // aa 12 25, then name 16 + pid 2 + model 1 + battery 1: the address is the next six bytes.
        val address = 3 + 20..3 + 25
        for (i in bytes.indices) if (i !in address) assertEquals(captured[i], bytes[i], "byte $i")
        assertEquals("00 00 5e 00 53 01", bytes.copyOfRange(address.first, address.last + 1).hex())
        assertNotEquals(captureAddress.hex(), bytes.copyOfRange(address.first, address.last + 1).hex())
    }

    @Test fun `the serial is 16 ASCII bytes behind the 02 marker`() {
        val reply = VirtualJbl().reply(JblFrame.decodeWhole(hex("aa 94 01 01"))!!).single()
        assertEquals(C.SERIAL, reply.command)
        assertEquals(17, reply.size)
        assertEquals(0x02, reply[0])
        assertEquals("VIRTUALWB2000001", String(reply.payload, 1, 16, Charsets.US_ASCII))
        assertTrue(hex("aa 94 11 02").contentEquals(reply.encode().copyOfRange(0, 4)))
    }

    @Test fun `the replies reframe and fold into the status the wire test expects`() {
        val unit = capture()
        // Through the real frame buffer: the long and short length forms must be the headset's.
        val frames = listOf("aa 11 00", "aa 9b 02 01 01", "aa 91 01 11", txInfoGet, "aa a2 02 01 ff", "aa 93 01 04", "aa 91 01 21", "aa 25 01 00")
            .flatMap { unit.reply(JblFrame.decodeWhole(hex(it))!!) }
            .flatMap { JblFrameBuffer().feed(it.encode()) }
        assertEquals(6, frames.size, "the level and the older battery reads are silent")
        assertTrue(frames.none { it.truncated })
        val s = frames.fold(JblStatus()) { acc, f -> acc.fold(f) }
        assertEquals(JblDeviceInfo("JBL Wave Buds 2", 0x2100, JblBattery(100, false), "2.7.0", "50:1B:6A:2F:2D:FF"), s.info)
        assertEquals(listOf(1, 2, 3), s.modes)
        assertEquals(C.WIRE_AMBIENT, s.activeMode)
        assertEquals(JblBattery(100, false), s.left)
        assertEquals(JblBattery(null, false), s.right)
        assertEquals(JblBattery(100, false), s.case)
        assertTrue(s.reportsBuds)
        assertEquals(C.EQ_OFF, s.eqCategory)
        assertEquals(true, s.voicePrompts)
        assertNull(s.ancLevel)
    }

    // ---- Modes -------------------------------------------------------------------------------

    @Test fun `a mode write is applied and answered with the status of the new mode`() {
        val unit = VirtualJbl()
        assertEquals(listOf("aa 91 07 12 01 01 02 00 03 00"), unit.ask(C.modeSet(allModes, C.WIRE_ANC)))
        assertEquals(C.WIRE_ANC, unit.mode)
        assertEquals(listOf("aa 91 07 12 01 00 02 00 03 01"), unit.ask(C.modeSet(allModes, C.WIRE_TALKTHRU)))
        assertEquals(C.WIRE_TALKTHRU, unit.mode)
        assertEquals(listOf("aa 91 07 12 01 00 02 00 03 00"), unit.ask(C.modeSet(allModes, null)), "all pairs off is Off")
        assertNull(unit.mode)
        assertEquals(listOf(rxModes), unit.ask(C.modeSet(allModes, C.WIRE_AMBIENT)))
        assertEquals(C.WIRE_AMBIENT, unit.mode)
        assertEquals(listOf(rxModes), unit.ask(C.modeGet()), "the mode read agrees")
    }

    @Test fun `a write of a subset of the pairs only touches what it names`() {
        val unit = VirtualJbl(mode = C.WIRE_AMBIENT)
        // A headset that listed only ANC and ambient gets two pairs; the answer still lists all three.
        assertEquals(listOf("aa 91 07 12 01 01 02 00 03 00"), unit.ask(C.modeSet(listOf(2, 1), C.WIRE_ANC)))
        // Switching off a mode that is not the current one changes nothing.
        unit.ask(JblFrame(C.LISTENING, byteArrayOf(0x10, 2, 0)))
        assertEquals(C.WIRE_ANC, unit.mode)
        // Switching off the current one, with nothing on, leaves Off.
        unit.ask(JblFrame(C.LISTENING, byteArrayOf(0x10, 1, 0)))
        assertNull(unit.mode)
    }

    @Test fun `with one bud out ANC and TalkThru are declined silently and the old mode is answered`() {
        val unit = VirtualJbl(bothBudsIn = false)
        assertEquals(listOf(rxModes), unit.ask(C.modeSet(allModes, C.WIRE_ANC)))
        assertEquals(listOf(rxModes), unit.ask(C.modeSet(allModes, C.WIRE_TALKTHRU)))
        assertEquals(C.WIRE_AMBIENT, unit.mode)
        // Off and Ambient Aware still work with one bud.
        assertEquals(listOf("aa 91 07 12 01 00 02 00 03 00"), unit.ask(C.modeSet(allModes, null)))
        assertEquals(listOf(rxModes), unit.ask(C.modeSet(allModes, C.WIRE_AMBIENT)))
        assertEquals(4, unit.received.size, "the two declined writes reached the buds like the others")
        unit.bothBudsIn = true
        assertEquals(listOf("aa 91 07 12 01 01 02 00 03 00"), unit.ask(C.modeSet(allModes, C.WIRE_ANC)))
    }

    @Test fun `the level read is silent on a Wave Buds 2 and answers once a level is set`() {
        val unit = VirtualJbl()
        assertEquals(emptyList(), unit.ask(C.levelGet()))
        assertEquals(emptyList(), unit.ask(C.ancLevelSet(5)))
        assertNull(unit.ancLevel, "a headset without the level ignores the write")
        assertEquals(emptyList(), unit.ask(C.levelGet()))

        unit.ancLevel = 4
        assertEquals(listOf("aa 91 03 22 04 04"), unit.ask(C.levelGet()))
        assertEquals(emptyList(), unit.ask(C.ancLevelSet(6)), "a level write has no reply")
        assertEquals(6, unit.ancLevel)
        assertEquals(listOf("aa 91 03 22 04 06"), unit.ask(C.levelGet()))
        assertEquals(6, unit.reply(C.levelGet()).fold(JblStatus()) { s, f -> s.fold(f) }.ancLevel)
        // A level outside 1..7 never passes the guard; the unit keeps it in range anyway.
        unit.ask(JblFrame(C.LISTENING, byteArrayOf(0x20, 0x04, 9)))
        assertEquals(7, unit.ancLevel)
    }

    // ---- Device info TLV ---------------------------------------------------------------------

    @Test fun `a TLV read answers the ids it knows, in request order, and leaves the others out`() {
        val unit = VirtualJbl(left = 45)
        unit.leftCharging = true
        val name = "00 01 10 4a 42 4c 20 57 61 76 65 20 42 75 64 73 20 32 00"
        assertEquals(
            listOf("aa 06 1d 00 02 00 20 01 00 00 1a 02 01 2d $name"),
            unit.ask(C.infoGet(listOf(C.FID_SAFE_SOUND, C.FID_BATTERY_HEADBAND, C.FID_BATTERY_LEFT, C.FID_NAME))),
        )
        assertEquals(emptyList(), unit.ask(C.infoGet(listOf(C.FID_BATTERY_HEADBAND))), "nothing known: no frame")
    }

    @Test fun `the TLV values follow the state`() {
        val unit = VirtualJbl(name = "JBL Tune Buds", firmware = listOf(3, 1, 4))
        unit.rightCharging = true
        unit.right = 20
        unit.case = VirtualJbl.UNKNOWN
        val s = unit.reply(C.infoGet()).single().let { JblStatus().fold(it) }
        assertEquals("3.1.4", s.firmware)
        assertEquals(JblBattery(100, false), s.left)
        assertEquals(JblBattery(20, true), s.right)
        assertEquals(JblBattery(null, false), s.case)
        assertNull(s.headband)
        assertTrue(C.FID_BATTERY_HEADBAND !in s.infoIds)
    }

    @Test fun `the device info battery byte is the lower bud, charging in bit 7`() {
        fun batteryByte(unit: VirtualJbl) = unit.hello().single().encode()[3 + 19].toInt() and 0xFF
        assertEquals(0x64, batteryByte(VirtualJbl()), "the capture: one bud unknown")
        assertEquals(30, batteryByte(VirtualJbl(left = 70, right = 30)))
        assertEquals(0x80 or 30, batteryByte(VirtualJbl(left = 70, right = 30).also { it.rightCharging = true }))
        val unknown = batteryByte(VirtualJbl(left = VirtualJbl.UNKNOWN, right = VirtualJbl.UNKNOWN))
        assertEquals(JblBattery(null, false), JblBattery.ofByte(unknown))
    }

    // ---- EQ ----------------------------------------------------------------------------------

    @Test fun `an EQ write is stored and read back with ten bands`() {
        val unit = VirtualJbl()
        val bass = C.eqPreset(3)!!
        val write = C.eqSet(bass.category, bass.gainsDb)
        assertEquals(emptyList(), unit.ask(write), "an EQ write has no reply")
        assertEquals(3, unit.eqCategory)
        assertEquals(bass.gainsDb, unit.eqGains)

        val reply = unit.reply(C.eqGet()).single()
        assertTrue(reply.longLength)
        assertEquals(C.EQ_SET_SIZE, reply.size)
        assertEquals(0x04, reply[0], "a return, not a set")
        assertEquals(write.payload.drop(1), reply.payload.drop(1), "category, calibration, bands: the layout of the write")
        assertEquals(bass.gainsDb, JblStatus.eqGains(reply))
        assertEquals(3, JblStatus().fold(reply).eqCategory)
        // First band: peak, +4 dB, 32 Hz, Q 1.0 (JblWireTest's bytes).
        assertEquals("01 00 00 80 40 00 00 00 42 0a", reply.payload.copyOfRange(16, 26).hex())
    }

    @Test fun `EQ off goes back to the all-zero frame of the capture`() {
        val unit = VirtualJbl(eqCategory = 1, eqGains = MutableList(10) { 2f })
        assertNotEquals(listOf(rxEq), unit.ask(C.eqGet()))
        unit.ask(C.eqSet(C.EQ_OFF, List(10) { 0f }))
        assertEquals(C.EQ_OFF, unit.eqCategory)
        assertEquals(listOf(rxEq), unit.ask(C.eqGet()), "count 0 and all zeros, as the real buds sent with the EQ off")
        assertEquals(C.EQ_OFF, unit.reply(C.eqGet()).fold(JblStatus()) { s, f -> s.fold(f) }.eqCategory)
    }

    @Test fun `a cut EQ write is ignored`() {
        val unit = VirtualJbl(eqCategory = 3)
        val whole = C.eqSet(1, C.eqPreset(1)!!.gainsDb)
        unit.answer(JblFrame(C.EQ, whole.payload.copyOf(50), longLength = true))
        assertEquals(3, unit.eqCategory)
    }

    // ---- Voice prompts, heartbeat, silence ---------------------------------------------------

    @Test fun `voice prompts are read and written`() {
        val unit = VirtualJbl()
        assertEquals(emptyList(), unit.ask(C.voicePromptsSet(false)))
        assertFalse(unit.voicePrompts)
        assertEquals(listOf("aa 93 02 05 00"), unit.ask(C.voicePromptsGet()))
        assertEquals(emptyList(), unit.ask(C.voicePromptsSet(true)))
        assertEquals(listOf(rxVoicePrompts), unit.ask(C.voicePromptsGet()))
    }

    @Test fun `the unit counts heartbeat echoes and does not answer them`() {
        val unit = VirtualJbl()
        assertEquals(emptyList(), unit.ask(C.heartbeatEcho()))
        assertEquals(emptyList(), unit.ask(C.heartbeatEcho()))
        assertEquals(2, unit.heartbeatEchoes)
        assertEquals(emptyList(), unit.ask(JblFrame(C.HEARTBEAT, byteArrayOf(1, 0, 0, 0))))
        assertEquals(2, unit.heartbeatEchoes, "only the app's echo counts")
    }

    @Test fun `everything else is silence, and every frame is recorded`() {
        val unit = VirtualJbl()
        val never = listOf(
            JblFrame(0x95), JblFrame(0x97), JblFrame(0xF0, byteArrayOf(1, 2)), JblFrame(0xA4), JblFrame(0xA3), JblFrame(0x15),
            JblFrame(0x74), JblFrame(0x61), JblFrame(0xB0), JblFrame(0x42, byteArrayOf(9)),
            C.batteryGet(), // assumed silent on a Wave Buds 2 (never captured): the older-earbuds battery read
            C.notifyOn().let { JblFrame(it.command, byteArrayOf(1, 0)) }, // not the pushes-on frame
        )
        for (f in never) assertTrue(unit.reply(f).isEmpty(), "$f")
        assertEquals(never, unit.received)
        assertFalse(unit.notificationsOn)
        // Nothing moved.
        assertEquals(C.WIRE_AMBIENT, unit.mode)
        assertEquals(C.EQ_OFF, unit.eqCategory)
        assertTrue(unit.voicePrompts)
        assertEquals(listOf(rxVoicePrompts), unit.ask(C.voicePromptsGet()), "and the unit still answers")
    }

    // ---- Pushes ------------------------------------------------------------------------------

    @Test fun `a battery push is a TLV return and moves the levels`() {
        val unit = VirtualJbl()
        unit.leftCharging = true
        val frame = unit.pushBattery(80, 70, 60)
        assertEquals("aa 06 10 00 02 00 1a 02 01 50 00 1b 02 00 46 00 1c 02 00 3c", frame.encode().hex())
        assertEquals(listOf(80, 70, 60), listOf(unit.left, unit.right, unit.case))
        val s = JblStatus().fold(frame)
        assertEquals(JblBattery(80, true), s.left)
        assertEquals(JblBattery(70, false), s.right)
        assertEquals(JblBattery(60, false), s.case)
        assertEquals(emptyList(), unit.ask(C.batteryGet()), "pushes never stop the older read being silent")
    }

    @Test fun `a mode push is the 91 12 status as when a bud is tapped`() {
        val unit = VirtualJbl()
        assertEquals("aa 91 07 12 01 00 02 00 03 01", unit.pushMode(C.WIRE_TALKTHRU).encode().hex())
        assertEquals(C.WIRE_TALKTHRU, unit.mode)
        assertEquals("aa 91 07 12 01 00 02 00 03 00", unit.pushMode(null).encode().hex())
        assertNull(unit.mode)
        assertFailsWith<IllegalArgumentException> { unit.pushMode(4) }
    }

    // From the JBL app notes (`AA B1 04 01 00 01 00`); jbl-control's capture has no heartbeat.
    @Test fun `the heartbeat is the app's frame`() {
        assertEquals("aa b1 04 01 00 01 00", VirtualJbl().pushHeartbeat().encode().hex())
    }

    // ---- The transport -----------------------------------------------------------------------

    @Test fun `the device info is the first thing on the link`() = runTest {
        val unit = capture()
        val transport = VirtualJblTransport(unit)
        assertEquals(rxDeviceInfo, transport.incoming.first().hex())
        assertTrue(transport.isConnected)
    }

    @Test fun `writes are answered through the channel and pushes keep their order`() = runTest {
        val unit = capture()
        val transport = VirtualJblTransport(unit)
        val chunks = mutableListOf<String>()
        backgroundScope.launch { transport.incoming.collect { chunks += it.hex() } }

        transport.write(C.notifyOn().encode())
        transport.write(C.modeGet().encode())
        unit.pushMode(C.WIRE_ANC)
        transport.push(JblFrame(C.VOICE_PROMPTS, byteArrayOf(0x05, 0x00)))
        unit.pushBattery(50, 60, 70)
        testScheduler.runCurrent()

        assertEquals(
            listOf(
                rxDeviceInfo, rxCallPush, rxModes, "aa 91 07 12 01 01 02 00 03 00", "aa 93 02 05 00",
                "aa 06 10 00 02 00 1a 02 00 32 00 1b 02 00 3c 00 1c 02 00 46",
            ),
            chunks,
        )
        assertEquals(listOf(C.NOTIFY_ON, C.LISTENING), unit.received.map { it.command })
    }

    @Test fun `frames that do not decode are ignored`() = runTest {
        val unit = VirtualJbl()
        val transport = VirtualJblTransport(unit)
        transport.write(byteArrayOf(1, 2, 3))
        transport.write(hex("aa 91 05 10"))
        transport.write(ByteArray(0))
        assertTrue(unit.received.isEmpty())
        transport.write(C.modeGet().encode())
        assertEquals(1, unit.received.size)
    }

    @Test fun `closing ends the stream, refuses writes and lets go of the unit`() = runTest {
        val unit = VirtualJbl()
        val transport = VirtualJblTransport(unit)
        transport.close()
        assertFalse(transport.isConnected)
        assertNull(unit.link)
        assertEquals(1, transport.incoming.toList().size, "the device info that was already queued, then the end")
        assertFailsWith<IOException> { transport.write(C.modeGet().encode()) }
        assertTrue(unit.received.isEmpty())
        unit.pushMode(C.WIRE_ANC) // nobody is listening: only the state changes
        assertEquals(C.WIRE_ANC, unit.mode)
    }

    @Test fun `a newer transport keeps the unit when an older one closes`() = runTest {
        val unit = VirtualJbl()
        val old = VirtualJblTransport(unit)
        val fresh = VirtualJblTransport(unit)
        val seen = mutableListOf<String>()
        backgroundScope.launch { fresh.incoming.collect { seen += it.hex() } }
        old.close()
        unit.pushMode(C.WIRE_TALKTHRU)
        testScheduler.runCurrent()
        assertEquals(listOf(unit.hello().single().encode().hex(), "aa 91 07 12 01 00 02 00 03 01"), seen)
    }
}
