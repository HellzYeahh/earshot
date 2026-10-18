package dev.earshot.virtual

import dev.earshot.bt.BragiConnection
import dev.earshot.bt.BtLog
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneState
import dev.earshot.core.NoiseSettings
import dev.earshot.core.Sidetone
import dev.earshot.core.SpatialAudio
import dev.earshot.protocol.bragi.BosRpcPacket
import dev.earshot.protocol.bragi.BragiServices
import dev.earshot.protocol.bragi.HearingMode
import dev.earshot.protocol.bragi.SpatialMode
import dev.earshot.service.CrusherProtocol
import dev.earshot.service.ProtocolLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real [BragiConnection] and [CrusherProtocol] against a [VirtualCrusher] over a
 * [VirtualCrusherTransport]: PPP framing, CRCs, BOS-RPC headers, tags, streaming subscriptions and
 * the state mapping must all agree on every byte for these to pass.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VirtualCrusherEndToEndTest {

    @Before
    fun silence() {
        BtLog.sink = { _, _, _ -> }
        ProtocolLog.sink = { _, _, _ -> }
    }

    private class Rig(val device: VirtualCrusher, val transport: VirtualCrusherTransport, val conn: BragiConnection)

    private fun TestScope.rig(
        device: VirtualCrusher = VirtualCrusher(),
        wrapper: dev.earshot.bt.BragiTxWrapper = dev.earshot.bt.RawPppWrapper,
        replyWrapper: (ByteArray) -> ByteArray = { it },
        accepts: (ByteArray) -> Boolean = { true },
    ): Rig {
        val t = VirtualCrusherTransport(device, replyWrapper = replyWrapper, acceptsRequest = accepts)
        val c = BragiConnection(t, backgroundScope, txWrapper = wrapper, clock = { testScheduler.currentTime })
        return Rig(device, t, c)
    }

    private val protocol = CrusherProtocol()

    @Test
    fun `connect reads the whole virtual crusher into HeadphoneState`() = runTest {
        val r = rig()
        val s = protocol.onConnected(r.conn, HeadphoneState())

        assertEquals("Skullcandy Crusher 1080 ANC", s.info?.modelName)
        assertEquals("CHP300", s.info?.productName)
        assertEquals(68, s.batteryPercent)
        assertEquals(false, s.isCharging)
        // Quiet / Aware / Off (slots 0 and 1 line up with the Bose models), ANC active
        assertEquals(listOf("Quiet", "Aware", "Off"), s.modes.map { it.name })
        assertTrue(s.modes.all { it.isPreset && !it.isEditable && it.isConfigured })
        assertEquals(0, s.currentModeIndex)
        assertEquals(7, s.noise?.cancellingLevel) // gain 7 of 10
        assertEquals(true, s.noise?.ancEnabled)
        assertEquals(SpatialAudio.OFF, s.noise?.spatialAudio)
        // sidetone volume 3 of 5 = 0.6 -> medium
        assertEquals(Sidetone.MEDIUM, s.sidetone)
        // Crusher bass
        assertEquals(true, s.crusher?.active)
        assertEquals(5, s.crusher?.intensity)
        assertEquals(10, s.crusher?.maxIntensity)
        assertEquals(1, s.crusher?.mode)
        assertEquals(3, s.crusher?.modeCount)
        assertEquals(false, protocol.authenticatedAccess)
        assertFalse(s.capabilities.ancToggle)
        assertFalse(s.capabilities.windBlock)
        assertFalse(s.capabilities.verified)
    }

    @Test
    fun `refresh after connect reads without re-subscribing`() = runTest {
        val r = rig()
        val s = protocol.onConnected(r.conn, HeadphoneState())
        r.device.received.clear()
        protocol.refresh(r.conn, s)
        // The subscribe flag (08 01) is only sent on connect; a refresh asks plainly.
        val reads = r.device.received.filter { it.address == BragiServices.MODE_READ }
        assertEquals(1, reads.size)
        assertEquals(0, reads.single().payload.size)
    }

    @Test
    fun `switching to Aware sends mode_configure and updates the state`() = runTest {
        val r = rig()
        val s0 = protocol.onConnected(r.conn, HeadphoneState())
        val s1 = protocol.setMode(r.conn, s0, 1)
        assertEquals(HearingMode.AT, r.device.hearingMode)
        assertEquals(1, s1.currentModeIndex)
        val sent = r.device.received.last { it.address == BragiServices.MODE_CONFIGURE }
        assertEquals("08031001", sent.payload.joinToString("") { "%02x".format(it) }) // mode=AT(3), activate
        // The new mode's own gain is the slider value (aware gain 5 of 10 -> 5).
        assertEquals(5, s1.noise?.cancellingLevel)
    }

    @Test
    fun `moving the slider changes the active mode's gain only`() = runTest {
        val r = rig()
        val s0 = protocol.onConnected(r.conn, HeadphoneState())
        val s1 = protocol.setNoise(r.conn, s0, s0.noise!!.copy(cancellingLevel = 10))
        assertEquals(10, r.device.ancGain)
        assertEquals(5, r.device.atGain) // untouched
        assertEquals(HearingMode.ANC, r.device.hearingMode) // gain write did not switch modes
        assertEquals(10, s1.noise?.cancellingLevel)
    }

    @Test
    fun `the slider is refused while the mode is Off`() = runTest {
        val r = rig()
        val s0 = protocol.onConnected(r.conn, HeadphoneState())
        val off = protocol.setMode(r.conn, s0, 2)
        assertEquals(HearingMode.OFF, r.device.hearingMode)
        assertFailsWith<HeadphoneCommandException> { protocol.setNoise(r.conn, off, off.noise!!.copy(cancellingLevel = 3)) }
    }

    @Test
    fun `immersive audio maps Still and Motion by label`() = runTest {
        val r = rig()
        val s0 = protocol.onConnected(r.conn, HeadphoneState())
        val still = protocol.setNoise(r.conn, s0, s0.noise!!.copy(spatialAudio = SpatialAudio.ROOM)) // label "Still"
        assertEquals(SpatialMode.STILL, r.device.spatial)
        assertEquals(SpatialAudio.ROOM, still.noise?.spatialAudio)
        val motion = protocol.setNoise(r.conn, still, still.noise!!.copy(spatialAudio = SpatialAudio.HEAD)) // "Motion"
        assertEquals(SpatialMode.MOTION, r.device.spatial)
        assertEquals(SpatialAudio.HEAD, motion.noise?.spatialAudio)
    }

    @Test
    fun `sidetone folds the volume into thirds and off disables it`() = runTest {
        val r = rig()
        val s0 = protocol.onConnected(r.conn, HeadphoneState())
        protocol.setSidetone(r.conn, s0, Sidetone.HIGH)
        assertEquals(true, r.device.sidetoneEnabled)
        assertEquals(5, r.device.sidetoneVolume)
        protocol.setSidetone(r.conn, s0, Sidetone.LOW)
        assertEquals(2, r.device.sidetoneVolume) // round(5/3)
        val off = protocol.setSidetone(r.conn, s0, Sidetone.OFF)
        assertEquals(false, r.device.sidetoneEnabled)
        assertEquals(Sidetone.OFF, off.sidetone)
    }

    @Test
    fun `every sidetone level reads back as itself whatever the volume range`() = runTest {
        for (max in listOf(5, 7, 10, 31)) {
            val r = rig(VirtualCrusher(sidetoneVolumeMax = max))
            val s0 = protocol.onConnected(r.conn, HeadphoneState())
            for (level in listOf(Sidetone.LOW, Sidetone.MEDIUM, Sidetone.HIGH)) {
                protocol.setSidetone(r.conn, s0, level)
                // What the headphones now report, read back through the same mapping.
                val back = protocol.refresh(r.conn, s0)
                assertEquals(level, back.sidetone, "max $max, wrote $level, volume ${r.device.sidetoneVolume}")
            }
        }
    }

    @Test
    fun `a lost link during a refresh is reported, not swallowed`() = runTest {
        val r = rig()
        val s0 = protocol.onConnected(r.conn, HeadphoneState())
        r.conn.close()
        assertFailsWith<HeadphoneCommandException> { protocol.refresh(r.conn, s0) }
        assertFailsWith<HeadphoneCommandException> { protocol.pollBattery(r.conn, s0) }
    }

    @Test
    fun `crusher bass writes only what changed and reports the applied state`() = runTest {
        val r = rig()
        val s0 = protocol.onConnected(r.conn, HeadphoneState())
        val s1 = protocol.setCrusherBass(r.conn, s0, active = null, intensity = 8, mode = null)
        assertEquals(8, r.device.crusherIntensity)
        assertEquals(true, r.device.crusherActive)
        assertEquals(1, r.device.crusherMode)
        assertEquals(8, s1.crusher?.intensity)
        val s2 = protocol.setCrusherBass(r.conn, s1, active = false, intensity = null, mode = 2)
        assertEquals(false, r.device.crusherActive)
        assertEquals(2, r.device.crusherMode)
        assertEquals(false, s2.crusher?.active)
        assertFailsWith<HeadphoneCommandException> { protocol.setCrusherBass(r.conn, s2, null, 99, null) }
        assertFailsWith<HeadphoneCommandException> { protocol.setCrusherBass(r.conn, s2, null, null, 7) }
    }

    @Test
    fun `a change made on the headphones arrives as a push and updates the state`() = runTest {
        val r = rig()
        val s0 = protocol.onConnected(r.conn, HeadphoneState())
        val pushes = ArrayList<BosRpcPacket>()
        backgroundScope.launch { r.conn.unsolicited.collect { pushes += it } }
        // advanceUntilIdle() ignores backgroundScope work, so step the scheduler explicitly.
        testScheduler.runCurrent()

        r.transport.push(r.device.setHearingModeOnDevice(HearingMode.AT)) // the user pressed the button
        testScheduler.runCurrent()

        val push = pushes.single { it.address == BragiServices.MODE_READ }
        val s1 = assertNotNull(protocol.onUnsolicited(push, s0))
        assertEquals(1, s1.currentModeIndex)
        assertEquals(5, s1.noise?.cancellingLevel)
    }

    @Test
    fun `a firmware-update style service is refused before it is written`() = runTest {
        val r = rig()
        val e = assertFailsWith<HeadphoneCommandException> { r.conn.call(BragiServices.Address(service = 99, method = 1)) }
        assertTrue("not a supported" in e.message.orEmpty())
        assertTrue(r.device.received.isEmpty())
    }

    @Test
    fun `an unknown method is reported as a rejection`() = runTest {
        val r = rig()
        val e = assertFailsWith<HeadphoneCommandException> { r.conn.call(BragiServices.TIME_GET) } // allowed service, device does not implement it
        assertTrue("rejected" in e.message.orEmpty())
    }

    @Test
    fun `replies wrapped in a vendor header are still found`() = runTest {
        // 05 5D <len> 00 00 in front of every frame, as a RACE notification would be.
        val vendor: (ByteArray) -> ByteArray = { frame -> byteArrayOf(0x05, 0x5D, (frame.size + 2).toByte(), 0x00, 0x0D, 0x01) + frame }
        val r = rig(replyWrapper = vendor)
        val s = protocol.onConnected(r.conn, HeadphoneState())
        assertEquals(68, s.batteryPercent)
        assertTrue(r.conn.hasFramed)
    }

    @Test
    fun `a pluggable transmit wrapper reaches a device that needs it`() = runTest {
        // A made-up 2-byte header: proves the wrapper plumbing and the reader's resync, nothing about real hardware.
        val header = byteArrayOf(0x55, 0xAA.toByte())
        val needsHeader: (ByteArray) -> Boolean = { bytes -> bytes.size > 2 && bytes[0] == header[0] && bytes[1] == header[1] }
        val raw = rig(accepts = needsHeader)
        assertFailsWith<HeadphoneCommandException> { raw.conn.callUnwatched(BragiServices.DEV_INFO, timeoutMs = 500) }
        val wrapped = rig(wrapper = dev.earshot.bt.BragiTxWrapper { header + it }, accepts = needsHeader)
        val reply = wrapped.conn.callUnwatched(BragiServices.DEV_INFO, timeoutMs = 500)
        assertTrue(reply.payload.isNotEmpty())
        assertNull(wrapped.conn.closeCause)
    }
}
