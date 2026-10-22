package dev.earshot.virtual

import dev.earshot.bt.BragiConnection
import dev.earshot.bt.BtLog
import dev.earshot.bt.DeviceFamily
import dev.earshot.core.ConnectionState
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.FormFactor
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneState
import dev.earshot.core.Sidetone
import dev.earshot.core.SpatialAudio
import dev.earshot.extras.HeadphoneLabels
import dev.earshot.protocol.bragi.BragiModel
import dev.earshot.protocol.bragi.BragiServices
import dev.earshot.protocol.bragi.HearingMode
import dev.earshot.service.CrusherProtocol
import dev.earshot.service.ProtocolLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Skullcandy Method 360 / 540 ANC on the existing Bragi stack: the real [BragiConnection] and
 * [CrusherProtocol] against a [VirtualCrusher] playing a Method earbud (no Crusher bass, no spatial
 * audio). Quiet / Aware / Off, their gain, sidetone and battery work as on the Crusher; the Crusher
 * features are neither asked for nor shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MethodEndToEndTest {

    @Before
    fun silence() {
        BtLog.sink = { _, _, _ -> }
        ProtocolLog.sink = { _, _, _ -> }
    }

    private fun TestScope.connect(device: VirtualCrusher): Pair<BragiConnection, VirtualCrusher> =
        BragiConnection(VirtualCrusherTransport(device), backgroundScope, clock = { testScheduler.currentTime }) to device

    private val spatialAndCrusher = setOf(BragiServices.SPATIAL_AUDIO, BragiServices.CRUSHER)

    @Test
    fun `a Method 360 reads as earbuds with the shared features and none of the Crusher's`() = runTest {
        val (conn, device) = connect(VirtualCrusher.method360())
        val protocol = CrusherProtocol()
        val s = protocol.onConnected(conn, HeadphoneState())

        assertEquals("Skullcandy Method 360 ANC", s.info?.modelName)
        assertEquals("T99_PLUS", s.info?.productName)
        assertEquals(74, s.batteryPercent)
        assertEquals(listOf("Quiet", "Aware", "Off"), s.modes.map { it.name })
        assertEquals(0, s.currentModeIndex)
        assertEquals(Sidetone.MEDIUM, s.sidetone)
        assertNull(s.crusher, "no Crusher bass card")
        assertFalse(s.capabilities.spatialAudio)
        assertFalse(s.capabilities.windBlock)
        assertFalse(s.capabilities.ancToggle)
        assertFalse(s.capabilities.verified)
        assertEquals(FormFactor.EARBUDS, s.capabilities.formFactor)
        assertTrue(s.capabilities.noiseCancelling, "the level slider is the active mode's gain, as on the Crusher")
        assertTrue(s.capabilities.supportNote!!.startsWith("Experimental"))
        assertTrue("Method 360 ANC" in s.capabilities.supportNote!!)
        // Nothing for a feature it does not have ever reached the earbuds.
        assertTrue(device.received.none { it.address.service in spatialAndCrusher }, "${device.received.map { it.address }}")
        conn.close()
    }

    @Test
    fun `a Method 540 is told apart by its SKU even when the name says nothing`() = runTest {
        val (conn, _) = connect(VirtualCrusher.method540())
        val protocol = CrusherProtocol()
        val renamed = HeadphoneState(connection = ConnectionState(ConnectionStatus.CONNECTED, deviceName = "Alex's earbuds"))
        val s = protocol.onConnected(conn, renamed)
        assertEquals("Skullcandy Method 540 ANC", s.info?.modelName)
        assertEquals(81, s.batteryPercent)
        assertNull(s.crusher)
        assertEquals(FormFactor.EARBUDS, s.capabilities.formFactor)
        conn.close()
    }

    @Test
    fun `the Bluetooth name is enough when dev_info gives an unknown SKU`() = runTest {
        val (conn, device) = connect(VirtualCrusher(model = "UNKNOWN", modelNumber = "ZZZZZ", hasCrusherBass = false, hasSpatialAudio = false))
        val protocol = CrusherProtocol()
        val named = HeadphoneState(connection = ConnectionState(ConnectionStatus.CONNECTED, deviceName = "Skullcandy Method 540 ANC"))
        val s = protocol.onConnected(conn, named)
        assertEquals("Skullcandy Method 540 ANC", s.info?.modelName)
        assertTrue(device.received.none { it.address.service in spatialAndCrusher })
        conn.close()
    }

    @Test
    fun `Quiet, Aware and Off and the gain slider work on the earbuds`() = runTest {
        val (conn, device) = connect(VirtualCrusher.method360())
        val protocol = CrusherProtocol()
        val s0 = protocol.onConnected(conn, HeadphoneState())
        val aware = protocol.setMode(conn, s0, 1)
        assertEquals(HearingMode.AT, device.hearingMode)
        assertEquals(1, aware.currentModeIndex)
        val louder = protocol.setNoise(conn, aware, aware.noise!!.copy(cancellingLevel = 9))
        assertEquals(9, device.atGain)
        assertEquals(5 + 4, louder.noise?.cancellingLevel)
        val off = protocol.setMode(conn, louder, 2)
        assertEquals(HearingMode.OFF, device.hearingMode)
        assertFailsWith<HeadphoneCommandException> { protocol.setNoise(conn, off, off.noise!!.copy(cancellingLevel = 3)) }
        protocol.setSidetone(conn, off, Sidetone.HIGH)
        assertEquals(5, device.sidetoneVolume)
        conn.close()
    }

    @Test
    fun `immersive audio and the Crusher bass are refused with a sentence and never sent`() = runTest {
        val (conn, device) = connect(VirtualCrusher.method360())
        val protocol = CrusherProtocol()
        val s0 = protocol.onConnected(conn, HeadphoneState())
        val e = assertFailsWith<HeadphoneCommandException> { protocol.setNoise(conn, s0, s0.noise!!.copy(spatialAudio = SpatialAudio.ROOM)) }
        assertTrue("immersive audio" in e.message.orEmpty(), e.message)
        assertFailsWith<HeadphoneCommandException> { protocol.setCrusherBass(conn, s0, active = true, intensity = null, mode = null) }
        assertTrue(device.received.none { it.address.service in spatialAndCrusher })
        conn.close()
    }

    @Test
    fun `a firmware that lacks the services answers unknown method, as the virtual earbud does`() = runTest {
        val (conn, _) = connect(VirtualCrusher.method360())
        val e = assertFailsWith<HeadphoneCommandException> { conn.call(BragiServices.CRUSHER_STATE_GET) }
        assertTrue("rejected" in e.message.orEmpty(), e.message)
        assertFailsWith<HeadphoneCommandException> { conn.call(BragiServices.SPATIAL_INFO_GET) }
        conn.close()
    }

    @Test
    fun `one protocol object serves a Method and then a Crusher without carrying the model over`() = runTest {
        val protocol = CrusherProtocol()
        val (methodConn, _) = connect(VirtualCrusher.method360())
        val method = protocol.onConnected(methodConn, HeadphoneState())
        assertNull(method.crusher)
        methodConn.close()

        val (crusherConn, _) = connect(VirtualCrusher())
        val crusher = protocol.onConnected(crusherConn, HeadphoneState())
        assertEquals("Skullcandy Crusher 1080 ANC", crusher.info?.modelName)
        assertEquals(true, crusher.crusher?.active)
        assertTrue(crusher.capabilities.spatialAudio)
        assertEquals(FormFactor.HEADPHONES, crusher.capabilities.formFactor)
        assertNull(crusher.capabilities.supportNote)
        assertEquals(CrusherProtocol.CAPABILITIES, crusher.capabilities)
        crusherConn.close()
    }

    @Test
    fun `routing and labels recognise the Method earbuds and nothing near them`() {
        assertEquals(DeviceFamily.BRAGI, DeviceFamily.forName("Skullcandy Method 360 ANC"))
        assertEquals(DeviceFamily.BRAGI, DeviceFamily.forName("Skullcandy Method 540 ANC"))
        for (name in listOf("Skullcandy Crusher ANC 2", "Skullcandy Rail ANC", "Skullcandy Method 360", "Skullcandy Sesh ANC")) {
            assertEquals(DeviceFamily.GENERIC, DeviceFamily.forName(name), name)
        }
        assertEquals(FormFactor.EARBUDS, HeadphoneLabels.formFactorFromName("Skullcandy Method 360 ANC"))
        assertEquals(FormFactor.HEADPHONES, HeadphoneLabels.formFactorFromName("Skullcandy CRUSHER 1080 ANC"))
        assertEquals(FormFactor.EARBUDS, HeadphoneLabels.formFactorFromName("soundcore Liberty 4 NC"))
        assertEquals(FormFactor.HEADPHONES, HeadphoneLabels.formFactorFromName("soundcore Space One Pro"))
        assertEquals(FormFactor.HEADPHONES, HeadphoneLabels.formFactorFromName("Sony WH-1000XM5"))
    }

    @Test
    fun `the Method demo devices are Bragi demos with their own virtual earbuds`() {
        for ((demo, model) in listOf(DemoDevice.METHOD_360 to BragiModel.SKULLCANDY_METHOD_360, DemoDevice.METHOD_540 to BragiModel.SKULLCANDY_METHOD_540)) {
            assertTrue(demo.isCrusher, demo.name)
            assertNull(demo.virtualProfile())
            assertEquals(model, BragiModel.fromBluetoothName(demo.displayName), demo.name)
        }
        assertEquals("S2MTW", VirtualCrusher.method360().modelNumber)
        assertEquals("S2MFW", VirtualCrusher.method540().modelNumber)
        assertEquals("S6CNW", VirtualCrusher().modelNumber)
    }
}
