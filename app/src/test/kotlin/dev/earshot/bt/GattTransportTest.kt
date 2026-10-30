package dev.earshot.bt

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Before
import java.io.IOException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The GATT link's setup sequence (JBL notes §2), MTU-sized writes that each wait for their callback,
 * and the notification stream, against a fake [GattPort] that answers like Android's GATT client.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GattTransportTest {

    @Before
    fun quiet() {
        BtLog.sink = { _, _, _ -> }
    }

    private val spec = GattLinkSpec.HARMAN
    private val notifyChar = spec.notify.single()

    private class FakePort(spec: GattLinkSpec) : GattPort {
        lateinit var transport: GattTransport
        val calls = mutableListOf<String>()
        val props = mutableMapOf(spec.write to GattTransport.PROPERTY_WRITE, spec.notify.single() to GattTransport.PROPERTY_NOTIFY)

        /** What `onMtuChanged` reports; null = Android never calls back. */
        var grantedMtu: Int? = 247
        var writeStatus = GattTransport.GATT_SUCCESS
        val written = mutableListOf<Pair<ByteArray, Boolean>>()
        var closes = 0

        override fun discoverServices(): Boolean {
            calls += "discover"
            transport.onServicesDiscovered(GattTransport.GATT_SUCCESS)
            return true
        }

        override fun requestHighPriority(): Boolean = true.also { calls += "priority" }

        /** Android throws for a client that is already gone; the transport must shrug it off. */
        var balancedFails = false

        override fun requestBalancedPriority(): Boolean {
            calls += "balanced"
            if (balancedFails) throw IllegalStateException("no GATT client")
            return true
        }

        override fun requestMtu(mtu: Int): Boolean {
            calls += "mtu $mtu"
            grantedMtu?.let { transport.onMtuChanged(it, GattTransport.GATT_SUCCESS) }
            return true
        }

        override fun properties(service: UUID, characteristic: UUID): Int? = props[characteristic]

        override fun enableNotifications(service: UUID, characteristic: UUID, indicate: Boolean): Boolean {
            calls += if (indicate) "indicate" else "notify"
            transport.onDescriptorWrite(GattTransport.GATT_SUCCESS)
            return true
        }

        override fun write(service: UUID, characteristic: UUID, value: ByteArray, withResponse: Boolean): Boolean {
            written += value to withResponse
            transport.onCharacteristicWrite(writeStatus)
            return true
        }

        override fun close() {
            closes++
        }
    }

    private fun link(port: FakePort = FakePort(spec), timeouts: GattTransport.Timeouts = GattTransport.Timeouts()): Pair<FakePort, GattTransport> {
        val transport = GattTransport(port, spec, timeouts)
        port.transport = transport
        return port to transport
    }

    @Test fun `setup runs discover, priority, MTU and notifications in that order, then hands the radio back`() = runTest {
        val (port, transport) = link()
        transport.onConnectionStateChange(GattTransport.GATT_SUCCESS, isConnected = true)
        transport.open()
        // High priority only for the setup burst: the long, sparse session runs at the balanced interval.
        assertEquals(listOf("discover", "priority", "mtu 512", "notify", "balanced"), port.calls)
        assertEquals(247, transport.mtu)
        assertTrue(transport.isConnected)
    }

    @Test fun `a failed balanced-priority request doesn't fail setup`() = runTest {
        val port = FakePort(spec).apply { balancedFails = true }
        val (_, transport) = link(port)
        transport.onConnectionStateChange(GattTransport.GATT_SUCCESS, isConnected = true)
        transport.open()
        assertTrue(transport.isConnected)
        transport.write(byteArrayOf(1))
        assertEquals(1, port.written.size)
    }

    @Test fun `open waits for the LE connection`() = runTest {
        val (port, transport) = link()
        val opening = async { transport.open() }
        testScheduler.runCurrent()
        assertTrue(port.calls.isEmpty(), "nothing is asked before the link is up")
        transport.onConnectionStateChange(GattTransport.GATT_SUCCESS, isConnected = true)
        opening.await()
        assertTrue(transport.isConnected)
    }

    @Test fun `a failed LE connection fails open and releases the client`() = runTest {
        val (port, transport) = link()
        transport.onConnectionStateChange(133, isConnected = false)
        assertFailsWith<GattSetupException> { transport.open() }
        assertEquals(1, port.closes)
        assertFalse(transport.isConnected)
    }

    @Test fun `writes are cut to the granted MTU and wait for each callback`() = runTest {
        val (port, transport) = link()
        transport.onConnectionStateChange(GattTransport.GATT_SUCCESS, isConnected = true)
        transport.open()
        val frame = ByteArray(600) { it.toByte() }
        transport.write(frame)
        assertEquals(listOf(244, 244, 112), port.written.map { it.first.size })
        assertTrue(port.written.all { it.second }, "Harman writes with response")
        assertContentEquals(frame, port.written.map { it.first }.reduce { a, b -> a + b })
    }

    @Test fun `a silent MTU request keeps 23 and the link still opens`() = runTest {
        val port = FakePort(spec).apply { grantedMtu = null }
        val (_, transport) = link(port, GattTransport.Timeouts(mtuMs = 100))
        transport.onConnectionStateChange(GattTransport.GATT_SUCCESS, isConnected = true)
        transport.open()
        assertEquals(GattLinkSpec.MIN_MTU, transport.mtu)
        transport.write(ByteArray(45))
        assertEquals(listOf(20, 20, 5), port.written.map { it.first.size })
    }

    @Test fun `a missing write characteristic fails setup`() = runTest {
        val port = FakePort(spec).apply { props.remove(spec.write) }
        val (_, transport) = link(port)
        transport.onConnectionStateChange(GattTransport.GATT_SUCCESS, isConnected = true)
        assertFailsWith<GattSetupException> { transport.open() }
        assertEquals(1, port.closes)
    }

    @Test fun `an indicate-only characteristic gets indications`() = runTest {
        val port = FakePort(spec).apply { props[notifyChar] = GattTransport.PROPERTY_INDICATE }
        val (_, transport) = link(port)
        transport.onConnectionStateChange(GattTransport.GATT_SUCCESS, isConnected = true)
        transport.open()
        assertEquals(listOf("indicate", "balanced"), port.calls.takeLast(2))
    }

    @Test fun `a characteristic without write-with-response is written without`() = runTest {
        val port = FakePort(spec).apply { props[spec.write] = GattTransport.PROPERTY_WRITE_NO_RESPONSE }
        val (_, transport) = link(port)
        transport.onConnectionStateChange(GattTransport.GATT_SUCCESS, isConnected = true)
        transport.open()
        transport.write(byteArrayOf(1, 2, 3))
        assertFalse(port.written.single().second)
    }

    @Test fun `a refused write is an IOException`() = runTest {
        val port = FakePort(spec)
        val (_, transport) = link(port)
        transport.onConnectionStateChange(GattTransport.GATT_SUCCESS, isConnected = true)
        transport.open()
        port.writeStatus = 0x0D // GATT_INVALID_ATTRIBUTE_LENGTH
        assertFailsWith<IOException> { transport.write(byteArrayOf(1)) }
    }

    @Test fun `writing before open is refused`() = runTest {
        val (port, transport) = link()
        assertFailsWith<IOException> { transport.write(byteArrayOf(1)) }
        assertTrue(port.written.isEmpty())
    }

    @Test fun `notifications arrive in order, other characteristics are ignored, a drop ends the stream`() = runTest {
        val (port, transport) = link()
        transport.onConnectionStateChange(GattTransport.GATT_SUCCESS, isConnected = true)
        transport.open()
        val got = mutableListOf<List<Byte>>()
        val received = async { runCatching { transport.incoming.collect { got += it.toList() } } }
        transport.onCharacteristicChanged(notifyChar, byteArrayOf(1, 2))
        transport.onCharacteristicChanged(UUID.randomUUID(), byteArrayOf(9))
        transport.onCharacteristicChanged(notifyChar, byteArrayOf(3))
        transport.onConnectionStateChange(8, isConnected = false) // GATT_CONN_TIMEOUT
        val result = received.await()
        assertEquals(listOf(listOf<Byte>(1, 2), listOf<Byte>(3)), got)
        assertTrue(result.exceptionOrNull() is IOException, "a dropped link ends the stream with an error, like a socket")
        assertFalse(transport.isConnected)
        assertEquals(1, port.closes)
    }

    @Test fun `a local close ends the stream normally`() = runTest {
        val (_, transport) = link()
        transport.onConnectionStateChange(GattTransport.GATT_SUCCESS, isConnected = true)
        transport.open()
        val received = async { transport.incoming.toList() }
        transport.onCharacteristicChanged(notifyChar, byteArrayOf(7))
        transport.close()
        assertEquals(listOf(listOf<Byte>(7)), received.await().map { it.toList() })
    }

    @Test fun `chunk sizes follow the ATT header`() {
        assertEquals(20, GattChunks.payloadSize(23))
        assertEquals(509, GattChunks.payloadSize(512))
        assertEquals(20, GattChunks.payloadSize(5), "never below the ATT minimum")
        assertTrue(GattChunks.split(ByteArray(0), 512).isEmpty())
        assertEquals(listOf(20, 20), GattChunks.split(ByteArray(40), 23).map { it.size })
    }

    @Test fun `firmware-update services can never be a link spec`() {
        assertTrue(GattLinkSpec.isFirmwareService(UUID.fromString("66666666-6666-6666-6666-666666666666")))
        assertTrue(GattLinkSpec.isFirmwareService(UUID.fromString("86868686-1234-5678-9abc-def012345678")))
        assertTrue(GattLinkSpec.isFirmwareService(UUID.fromString("65786365-6c70-6f69-6e74-2e636f820001")))
        assertFalse(GattLinkSpec.isFirmwareService(GattLinkSpec.HARMAN.service))
        assertFalse(GattLinkSpec.isFirmwareService(GattLinkSpec.AIROHA.service))
        assertFailsWith<IllegalArgumentException> {
            GattLinkSpec("OTA", UUID.fromString("66666666-6666-6666-6666-666666666666"), UUID.randomUUID(), listOf(UUID.randomUUID()))
        }
    }

    @Test fun `LE advert names fit the headset only exactly`() {
        assertTrue(LeAdvertMatch.fits("Live Buds 3-LE", "Live Buds 3"))
        assertTrue(LeAdvertMatch.fits("JBL Live Buds 3", "jbl live buds 3"))
        assertTrue(LeAdvertMatch.fits("LE-Tour Pro 3", "Tour Pro 3"))
        assertTrue(LeAdvertMatch.fits("Tour Pro 3 LE", "Tour Pro 3"))
        assertFalse(LeAdvertMatch.fits("Live Buds 3 Pro-LE", "Live Buds 3"))
        assertFalse(LeAdvertMatch.fits("Live 770NC", "Tune 770NC"))
        assertFalse(LeAdvertMatch.fits(null, "Tour Pro 3"))
        assertFalse(LeAdvertMatch.fits("JBL", "JBL"), "a name this short would match strangers")
        val names = LeAdvertMatch.candidateNames(" Tour Pro 3 ")
        assertEquals(listOf("Tour Pro 3", "Tour Pro 3-LE", "Tour Pro 3_LE", "Tour Pro 3 LE", "LE-Tour Pro 3", "LE_Tour Pro 3", "LE Tour Pro 3"), names)
        assertTrue(names.all { LeAdvertMatch.fits(it, "Tour Pro 3") }, "every filter name is one fits() accepts")
    }

    @Test fun `a drop during setup is a GattSetupException and the client is released`() = runTest {
        val port = object : GattPort by FakePort(spec) {
            lateinit var transport: GattTransport
            var closes = 0
            override fun discoverServices(): Boolean {
                // Connected, then the link drops before discovery answers.
                transport.onConnectionStateChange(8, isConnected = false)
                return true
            }
            override fun close() { closes++ }
        }
        val transport = GattTransport(port, spec)
        port.transport = transport
        transport.onConnectionStateChange(GattTransport.GATT_SUCCESS, isConnected = true)
        assertFailsWith<GattSetupException> { transport.open() }
        assertTrue(port.closes >= 2, "closed on the drop and again when open() gives up, so a late client is released")
    }
}
