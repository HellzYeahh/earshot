package dev.earshot.codec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * REAL capture: `dumpsys bluetooth_manager` A2DP section from the user's Samsung Samsung Galaxy
 * (One UI 9 / Android 17) with the QC Ultra Headphones (2nd Gen) paired, copied from
 * `docs/reference/captures-galaxy/oneui9-android17-dumpsys-a2dp.txt`. Addresses are redacted by the
 * OS to `XX:XX:XX:XX:AA:BB`; the headphones are the `...00:11` state machine.
 */
class OneUiDumpsysCaptureTest {

    private val dump by lazy {
        val text = checkNotNull(javaClass.getResourceAsStream("/codec/oneui9-android17-dumpsys-a2dp.txt")) { "capture missing from test resources" }
            .bufferedReader().use { it.readText() }
        DumpsysCodecParser.parse(text)
    }

    /** What BluetoothDevice.getAddress() would return for the redacted ...00:11 sink. */
    private val headphones = "4C:87:5D:00:00:11"

    @Test
    fun `finds the three state machines and no active device`() {
        assertNull(dump.activeDeviceAddress)
        assertEquals(listOf("XX:XX:XX:XX:00:11", "XX:XX:XX:XX:00:01", "XX:XX:XX:XX:6F:3F"), dump.devices.map { it.address })
    }

    @Test
    fun `current codec is AAC 44100 16-bit stereo on the QC Ultra 2 section`() {
        val qc = assertNotNull(dump.forDevice(headphones))
        assertEquals("XX:XX:XX:XX:00:11", qc.address)
        val current = assertNotNull(qc.current)
        assertEquals(CodecNames.AAC, current.codecName)
        assertEquals(1, current.codecType)
        assertEquals(100_000, current.priority)
        assertEquals(listOf(44100), current.sampleRatesHz)
        assertEquals(listOf(16), current.bitDepths)
        assertEquals(listOf("Stereo"), current.channelModes)
        assertEquals(133L, current.codecSpecific1)

        val info = CodecProfiles.toCodecInfo(current)
        assertEquals(44100, info.sampleRateHz)
        assertEquals(16, info.bitsPerSample)
        assertEquals("Stereo", info.channelMode)
    }

    @Test
    fun `selectable is AAC and SBC only - the headphones do not offer aptX to this phone`() {
        val qc = assertNotNull(dump.forDevice(headphones))
        assertEquals(listOf(CodecNames.AAC, CodecNames.SBC), qc.selectable.map { it.codecName })
        assertEquals(listOf("Mono", "Stereo"), qc.selectable[1].channelModes)
    }

    @Test
    fun `offload table and rec history are not mistaken for the current config`() {
        // codecConfigOffloading lists SSC/aptX/SBC with sample rate NONE; history records carry
        // their own mCodecConfig. Neither may leak into current or selectable of any device.
        for (d in dump.devices) {
            val current = assertNotNull(d.current, "device ${d.address} lost its current config")
            assertTrue(current.sampleRatesHz.isNotEmpty(), "device ${d.address} picked up a NONE config")
            assertFalse(d.selectable.any { it.codecName == CodecNames.SAMSUNG_SCALABLE }, "SSC is never selectable here")
        }
        assertEquals(CodecNames.SBC, dump.forDevice("00:00:00:00:6F:3F")?.current?.codecName)
        assertEquals(1, dump.forDevice("00:00:00:00:6F:3F")?.selectable?.size)
    }

    @Test
    fun `phone-only codecs come from the history local capabilities with real sample rates`() {
        val qc = assertNotNull(dump.forDevice(headphones))
        assertEquals(
            listOf(CodecNames.AAC, CodecNames.SAMSUNG_SCALABLE, CodecNames.LDAC, CodecNames.APTX, CodecNames.SBC),
            qc.local.map { it.codecName },
        )
        val ssc = qc.local.first { it.codecName == CodecNames.SAMSUNG_SCALABLE }
        assertEquals(8, ssc.codecType)
        assertEquals(listOf(44100, 48000), ssc.sampleRatesHz)
        assertEquals(listOf(16, 24), ssc.bitDepths)
        val ldac = qc.local.first { it.codecName == CodecNames.LDAC }
        assertEquals(listOf(44100, 48000, 88200, 96000), ldac.sampleRatesHz)
    }

    @Test
    fun `codecConfigPriorities gives the enabled phone codecs, skipping disabled and unnamed ones`() {
        // aptX HD and Opus are -1 (disabled), "UNKNOWN CODEC(7)" and "INVALID CODEC" have no name.
        assertEquals(
            listOf(CodecNames.SBC, CodecNames.AAC, CodecNames.APTX, CodecNames.LDAC, CodecNames.SAMSUNG_SCALABLE),
            dump.phoneCodecNames,
        )
    }

    @Test
    fun `options mark only AAC and SBC selectable`() {
        val qc = assertNotNull(dump.forDevice(headphones))
        val options = CodecProfiles.toOptions(qc.selectable, qc.local, dump.phoneCodecNames)
        val byName = options.associateBy { it.codecName }
        assertTrue(byName.getValue(CodecNames.AAC).isSelectable)
        assertTrue(byName.getValue(CodecNames.SBC).isSelectable)
        for (name in listOf(CodecNames.SAMSUNG_SCALABLE, CodecNames.LDAC, CodecNames.APTX)) {
            assertFalse(byName.getValue(name).isSelectable, "$name must be phone-only")
        }
        assertFalse(CodecNames.UNKNOWN in byName)
        assertFalse(CodecNames.APTX_HD in byName) // disabled on this phone
    }

    @Test
    fun `without a matching address the first section with a config is used`() {
        assertEquals("XX:XX:XX:XX:00:11", dump.forDevice(null)?.address)
        assertEquals("XX:XX:XX:XX:00:11", dump.forDevice("11:22:33:44:55:66")?.address)
    }
}
