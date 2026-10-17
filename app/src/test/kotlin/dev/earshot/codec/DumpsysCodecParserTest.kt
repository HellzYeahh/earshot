package dev.earshot.codec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Samples are SYNTHETIC: written from the `A2dpStateMachine.dump()` / `BluetoothCodecConfig.toString()`
 * source layout, not captured from a device. Replace with a real `dumpsys bluetooth_manager` excerpt
 * from the user's Galaxy once one is available.
 */
class DumpsysCodecParserTest {

    // Android 13/14 style: multi-line per-device section, hex masks with labels.
    private val android14Dump = """
        Bluetooth Status
          enabled: true
        ...
        Profile: A2dpService
          mMaxConnectedAudioDevices: 5
          mActiveDevice: 4C:87:5D:AA:BB:CC
          A2dpStateMachine for 4C:87:5D:AA:BB:CC:
            mDevice: 4C:87:5D:AA:BB:CC
              StateMachine: A2dpStateMachine (4C:87:5D:AA:BB:CC) Connected
              mIsPlaying: true
              mCodecConfig: {codecName:AAC,mCodecType:1,mCodecPriority:0,mSampleRate:0x1(44100),mBitsPerSample:0x1(16),mChannelMode:0x2(STEREO),mCodecSpecific1:0,mCodecSpecific2:0,mCodecSpecific3:0,mCodecSpecific4:0}
              mCodecsSelectableCapabilities:
                {codecName:SBC,mCodecType:0,mCodecPriority:1001,mSampleRate:0x1(44100),mBitsPerSample:0x1(16),mChannelMode:0x3(MONO|STEREO),mCodecSpecific1:0,mCodecSpecific2:0,mCodecSpecific3:0,mCodecSpecific4:0}
                {codecName:AAC,mCodecType:1,mCodecPriority:2001,mSampleRate:0x3(44100|48000),mBitsPerSample:0x1(16),mChannelMode:0x2(STEREO),mCodecSpecific1:0,mCodecSpecific2:0,mCodecSpecific3:0,mCodecSpecific4:0}
                {codecName:aptX,mCodecType:2,mCodecPriority:3001,mSampleRate:0x3(44100|48000),mBitsPerSample:0x1(16),mChannelMode:0x2(STEREO),mCodecSpecific1:0,mCodecSpecific2:0,mCodecSpecific3:0,mCodecSpecific4:0}
              mCodecsLocalCapabilities:
                {codecName:LDAC,mCodecType:4,mCodecPriority:5001,mSampleRate:0xf(44100|48000|88200|96000),mBitsPerSample:0x7(16|24|32),mChannelMode:0x3(MONO|STEREO),mCodecSpecific1:1003,mCodecSpecific2:0,mCodecSpecific3:0,mCodecSpecific4:0}
          A2dpStateMachine for 11:22:33:44:55:66:
            mDevice: 11:22:33:44:55:66
              StateMachine: A2dpStateMachine (11:22:33:44:55:66) Disconnected
              mIsPlaying: false
        Profile: HeadsetService
          mActiveDevice: 4C:87:5D:AA:BB:CC
    """.trimIndent()

    @Test
    fun `parses current codec for the matching device`() {
        val dump = DumpsysCodecParser.parse(android14Dump)
        assertEquals("4C:87:5D:AA:BB:CC", dump.activeDeviceAddress)
        assertEquals(2, dump.devices.size)

        val bose = assertNotNull(dump.forDevice("4c:87:5d:aa:bb:cc"))
        val current = assertNotNull(bose.current)
        assertEquals(CodecNames.AAC, current.codecName)
        assertEquals(1, current.codecType)
        assertEquals(listOf(44100), current.sampleRatesHz)
        assertEquals(listOf(16), current.bitDepths)
        assertEquals(listOf("Stereo"), current.channelModes)
    }

    @Test
    fun `selectable and local lists are kept apart`() {
        val bose = assertNotNull(DumpsysCodecParser.parse(android14Dump).forDevice("4C:87:5D:AA:BB:CC"))
        assertEquals(listOf(CodecNames.SBC, CodecNames.AAC, CodecNames.APTX), bose.selectable.map { it.codecName })
        assertEquals(listOf(44100, 48000), bose.selectable[1].sampleRatesHz)
        assertEquals(listOf("Mono", "Stereo"), bose.selectable[0].channelModes)

        assertEquals(listOf(CodecNames.LDAC), bose.local.map { it.codecName })
        val ldac = bose.local.single()
        assertEquals(listOf(44100, 48000, 88200, 96000), ldac.sampleRatesHz)
        assertEquals(listOf(16, 24, 32), ldac.bitDepths)
        assertEquals(1003L, ldac.codecSpecific1)
    }

    @Test
    fun `device without codec status has null current`() {
        val other = assertNotNull(DumpsysCodecParser.parse(android14Dump).forDevice("11:22:33:44:55:66"))
        assertNull(other.current)
        assertTrue(other.selectable.isEmpty())
    }

    @Test
    fun `unknown address falls back to the active device`() {
        val picked = assertNotNull(DumpsysCodecParser.parse(android14Dump).forDevice("00:00:00:00:00:00"))
        assertEquals("4C:87:5D:AA:BB:CC", picked.address)
    }

    @Test
    fun `android 15 style redacted address and nested codec type still parse`() {
        val dump = """
            mActiveDevice: XX:XX:XX:XX:BB:CC
            mDevice: XX:XX:XX:XX:BB:CC
              mCodecConfig: {codecName:aptX Adaptive,mCodecType:BluetoothCodecType{mCodecName:aptX Adaptive,mCodecId:1048600,mIsMandatoryCodec:false},mCodecPriority:0,mSampleRate:0x2(48000),mBitsPerSample:0x2(24),mChannelMode:0x2(STEREO),mCodecSpecific1:0,mCodecSpecific2:0,mCodecSpecific3:0,mCodecSpecific4:0}
              mCodecsSelectableCapabilities:
                {codecName:SBC,mCodecType:BluetoothCodecType{mCodecName:SBC,mCodecId:0,mIsMandatoryCodec:true},mCodecPriority:1001,mSampleRate:0x1(44100),mBitsPerSample:0x1(16),mChannelMode:0x2(STEREO),mCodecSpecific1:0,mCodecSpecific2:0,mCodecSpecific3:0,mCodecSpecific4:0}
        """.trimIndent()
        val device = assertNotNull(DumpsysCodecParser.parse(dump).forDevice("4C:87:5D:AA:BB:CC"))
        val current = assertNotNull(device.current)
        assertEquals(CodecNames.APTX_ADAPTIVE, current.codecName)
        assertNull(current.codecType) // printed as an object, not an int
        assertEquals(listOf(48000), current.sampleRatesHz)
        assertEquals(listOf(24), current.bitDepths)
        assertEquals(listOf(CodecNames.SBC), device.selectable.map { it.codecName })
    }

    @Test
    fun `single-line BluetoothCodecStatus toString parses with labels in order`() {
        val line = "{mCodecConfig:{codecName:LDAC,mCodecType:4,mCodecPriority:0,mSampleRate:0x8(96000),mBitsPerSample:0x4(32),mChannelMode:0x2(STEREO),mCodecSpecific1:1000,mCodecSpecific2:0,mCodecSpecific3:0,mCodecSpecific4:0}," +
            "mCodecsLocalCapabilities:[{codecName:SBC,mCodecType:0,mCodecPriority:1001,mSampleRate:0x1(44100),mBitsPerSample:0x1(16),mChannelMode:0x3(MONO|STEREO),mCodecSpecific1:0,mCodecSpecific2:0,mCodecSpecific3:0,mCodecSpecific4:0}, " +
            "{codecName:LDAC,mCodecType:4,mCodecPriority:5001,mSampleRate:0xf(44100|48000|88200|96000),mBitsPerSample:0x7(16|24|32),mChannelMode:0x3(MONO|STEREO),mCodecSpecific1:1003,mCodecSpecific2:0,mCodecSpecific3:0,mCodecSpecific4:0}]," +
            "mCodecsSelectableCapabilities:[{codecName:SBC,mCodecType:0,mCodecPriority:1001,mSampleRate:0x1(44100),mBitsPerSample:0x1(16),mChannelMode:0x2(STEREO),mCodecSpecific1:0,mCodecSpecific2:0,mCodecSpecific3:0,mCodecSpecific4:0}, " +
            "{codecName:LDAC,mCodecType:4,mCodecPriority:5001,mSampleRate:0x9(44100|96000),mBitsPerSample:0x4(32),mChannelMode:0x2(STEREO),mCodecSpecific1:0,mCodecSpecific2:0,mCodecSpecific3:0,mCodecSpecific4:0}]}"
        val device = DumpsysCodecParser.parse(line).devices.single()
        assertEquals(CodecNames.LDAC, device.current?.codecName)
        assertEquals(1000L, device.current?.codecSpecific1)
        assertEquals(listOf(CodecNames.SBC, CodecNames.LDAC), device.local.map { it.codecName })
        assertEquals(listOf(CodecNames.SBC, CodecNames.LDAC), device.selectable.map { it.codecName })
        assertEquals(listOf(44100, 96000), device.selectable[1].sampleRatesHz)
    }

    @Test
    fun `labels without masks are used when the hex is missing`() {
        val c = assertNotNull(DumpsysCodecParser.parseCodecConfig("{codecName:SSC,mCodecType:7,mSampleRate:(44100|48000|96000),mBitsPerSample:(24),mChannelMode:(STEREO)}"))
        assertEquals(CodecNames.SAMSUNG_SCALABLE, c.codecName)
        assertEquals(7, c.codecType)
        assertEquals(listOf(44100, 48000, 96000), c.sampleRatesHz)
        assertEquals(listOf(24), c.bitDepths)
        assertEquals(listOf("Stereo"), c.channelModes)
    }

    @Test
    fun `garbage yields no devices rather than throwing`() {
        val dump = DumpsysCodecParser.parse("nothing to see here\nmCodecConfig: null\n")
        assertTrue(dump.devices.isEmpty())
        assertNull(dump.forDevice("4C:87:5D:AA:BB:CC"))
    }
}
