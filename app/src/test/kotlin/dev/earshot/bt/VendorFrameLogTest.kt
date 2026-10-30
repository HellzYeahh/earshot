package dev.earshot.bt

import dev.earshot.protocol.airoha.RaceIds
import dev.earshot.protocol.airoha.RacePacket
import dev.earshot.protocol.airoha.RaceVendor
import dev.earshot.protocol.gaia.GaiaCommand
import dev.earshot.protocol.gaia.GaiaFrame
import dev.earshot.protocol.jbl.JblCommands
import dev.earshot.protocol.sony.TandemDataType
import dev.earshot.protocol.sony.TandemFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Audit R09 for the other stacks: the JBL, Sennheiser, RACE and Sony connections log a frame by
 * direction, command id and length only. Their device-info and paired-device frames carry names and
 * addresses, and every log line also lands in [LogRing], the connection report users paste into
 * public bug reports. Each case feeds a frame whose payload is a name and checks that neither the
 * name nor its bytes reach the log, while the line still says what arrived.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VendorFrameLogTest {
    private val lines = ArrayList<String>()

    @Before
    fun captureLogs() {
        val sink: (Int, String, Throwable?) -> Unit = { _, msg, _ -> synchronized(lines) { lines += msg } }
        BtLog.sink = sink
        JblLog.sink = sink
        SennheiserLog.sink = sink
        SonyLog.sink = sink
    }

    @After
    fun silenceLogs() {
        BtLog.sink = { _, _, _ -> }
        JblLog.sink = { _, _, _ -> }
        SennheiserLog.sink = { _, _, _ -> }
        SonyLog.sink = { _, _, _ -> }
    }

    /** A hand-fed link: the test decides what arrives; writes (ACKs, requests) are accepted and dropped. */
    private class FedTransport : ByteTransport {
        private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
        override var isConnected: Boolean = true
        override val incoming: Flow<ByteArray> = inbound.receiveAsFlow()
        override suspend fun write(bytes: ByteArray) = Unit
        fun deliver(bytes: ByteArray) { inbound.trySend(bytes) }
        override fun close() {
            isConnected = false
            inbound.close()
        }
    }

    private fun logged(): List<String> = synchronized(lines) { lines.toList() }

    /** Neither [name] as text nor its bytes as hex (compact or spaced, either case) are in any line. */
    private fun assertNameNotLogged(name: String) {
        val bytes = name.toByteArray()
        val forms = listOf(
            name,
            bytes.joinToString("") { "%02x".format(it) },
            bytes.joinToString(" ") { "%02x".format(it) },
        ).flatMap { listOf(it, it.uppercase()) }
        val all = logged()
        for (line in all) {
            for (form in forms) assertTrue(form !in line, "\"$form\" leaked into: $line")
        }
    }

    private fun assertLogged(fragment: String) {
        val all = logged()
        assertTrue(all.any { fragment in it }, "no line with \"$fragment\" in:\n${all.joinToString("\n")}")
    }

    @Test
    fun `JBL device info is logged by command and length only`() = runTest {
        val t = FedTransport()
        val conn = JblConnection(t, backgroundScope)
        // jbl-control's Wave Buds 2 device info: the name "JBL Wave Buds 2" leads its 37-byte payload.
        val deviceInfo = "aa 12 25 4a 42 4c 20 57 61 76 65 20 42 75 64 73 20 32 00 00 21 00 64 ff 2d 2f 6a 1b 50 02 07 00 64 f2 96 10 ff ff 02 00"
            .split(' ').map { it.toInt(16).toByte() }.toByteArray()
        t.deliver(deviceInfo)
        runCurrent()
        conn.send(JblCommands.deviceInfoGet())

        assertLogged("<- AA 12 (37 B)")
        assertLogged("-> AA 11")
        assertNameNotLogged("JBL Wave Buds 2")
        conn.close()
    }

    @Test
    fun `Sennheiser frames are logged by ids and length only`() = runTest {
        val t = FedTransport()
        val conn = SennheiserConnection(t, backgroundScope)
        val name = "Jane's Momentum"
        // A response nobody waits for (it goes to the pushes): feature 2, pdu 1, payload = a name.
        val command = GaiaCommand.of(feature = 2, pdu = 1, type = GaiaCommand.TYPE_RESPONSE)
        t.deliver(GaiaFrame(0x0495, command, name.toByteArray()).encode())
        runCurrent()

        assertLogged("<- ${GaiaCommand.name(0x0495, command)} (${name.length} B)")
        assertNameNotLogged(name)
        conn.close()
    }

    @Test
    fun `RACE packets are logged by type, id and length only`() = runTest {
        val t = FedTransport()
        val conn = RaceConnection(t, RaceVendor.JBL, backgroundScope)
        val name = "Jane's Tune Buds"
        t.deliver(RacePacket(RacePacket.TYPE_INDICATION, RaceIds.TWS_BATTERY, name.toByteArray()).encode())
        runCurrent()

        assertLogged("RACE <- ind ${RaceIds.name(RaceIds.TWS_BATTERY)} (${name.length} B)")
        assertNameNotLogged(name)
        conn.close()
    }

    @Test
    fun `Sony messages and ignored frames are logged by command, type and length only`() = runTest {
        val t = FedTransport()
        val conn = SonyConnection(t, backgroundScope, openDelayMs = 0)
        val name = "Jane's WH-1000XM5"
        // Command 0x23, inquired type 0x01, then the name; and the same bytes on a data type Earshot ignores.
        val payload = byteArrayOf(0x23, 0x01) + name.toByteArray()
        t.deliver(TandemFrame(TandemDataType.DATA_MDR, 0, payload).encode())
        t.deliver(TandemFrame(TandemDataType.DATA_EV, 1, payload).encode())
        runCurrent()

        assertLogged("<- T1 23 01 (${payload.size} B)")
        assertLogged("ignored frame type %02x".format(TandemDataType.DATA_EV))
        assertNameNotLogged(name)
        conn.close()
    }
}
