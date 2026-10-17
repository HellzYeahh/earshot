package dev.earshot.codec

import dev.earshot.core.ConnectionProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `BluetoothCodecStatus.toString()` as the codec user service returns it. */
class CodecStatusTextTest {

    /**
     * Verbatim `obj=` payload of a `CODEC_CONFIG_CHANGED` record in the real One UI 9 / Android 17
     * capture (`docs/reference/captures-galaxy/oneui9-android17-dumpsys-a2dp.txt`, rec[17]); the
     * state machine logs exactly `BluetoothCodecStatus.toString()` there.
     */
    private val oneUi9Sbc =
        "{mCodecConfig:{codecName:SBC,mCodecType:0,mCodecPriority:1001,mSampleRate:0x1(44100),mBitsPerSample:0x1(16),mChannelMode:0x2(STEREO),mCodecSpecific1:0,mCodecSpecific2:0,mCodecSpecific3:0,mCodecSpecific4:0}," +
            "mCodecsLocalCapabilities:[{codecName:SSC,mCodecType:8,mCodecPriority:8001,mSampleRate:0x3(44100|48000),mBitsPerSample:0x3(16|24),mChannelMode:0x2(STEREO),mCodecSpecific1:0,mCodecSpecific2:0,mCodecSpecific3:0,mCodecSpecific4:0}, " +
            "{codecName:LDAC,mCodecType:4,mCodecPriority:5001,mSampleRate:0xf(44100|48000|88200|96000),mBitsPerSample:0x7(16|24|32),mChannelMode:0x2(STEREO),mCodecSpecific1:0,mCodecSpecific2:0,mCodecSpecific3:0,mCodecSpecific4:0}, " +
            "{codecName:AptX,mCodecType:2,mCodecPriority:3001,mSampleRate:0x1(44100),mBitsPerSample:0x1(16),mChannelMode:0x2(STEREO),mCodecSpecific1:0,mCodecSpecific2:0,mCodecSpecific3:0,mCodecSpecific4:0}, " +
            "{codecName:AAC,mCodecType:1,mCodecPriority:2001,mSampleRate:0x1(44100),mBitsPerSample:0x1(16),mChannelMode:0x2(STEREO),mCodecSpecific1:0,mCodecSpecific2:0,mCodecSpecific3:0,mCodecSpecific4:0}, " +
            "{codecName:SBC,mCodecType:0,mCodecPriority:1001,mSampleRate:0x1(44100),mBitsPerSample:0x1(16),mChannelMode:0x3(MONO|STEREO),mCodecSpecific1:0,mCodecSpecific2:0,mCodecSpecific3:0,mCodecSpecific4:0}]," +
            "mCodecsSelectableCapabilities:[{codecName:SBC,mCodecType:0,mCodecPriority:1001,mSampleRate:0x1(44100),mBitsPerSample:0x1(16),mChannelMode:0x3(MONO|STEREO),mCodecSpecific1:0,mCodecSpecific2:0,mCodecSpecific3:0,mCodecSpecific4:0}]}"

    /** Same shape with the QC Ultra 2 streaming AAC and accepting AAC + SBC (what this Galaxy negotiates). */
    private val qcUltraAac =
        "{mCodecConfig:{codecName:AAC,mCodecType:1,mCodecPriority:2001,mSampleRate:0x1(44100),mBitsPerSample:0x1(16),mChannelMode:0x2(STEREO),mCodecSpecific1:0,mCodecSpecific2:0,mCodecSpecific3:0,mCodecSpecific4:0}," +
            "mCodecsLocalCapabilities:[{codecName:SSC,mCodecType:8,mCodecPriority:8001,mSampleRate:0x3(44100|48000),mBitsPerSample:0x3(16|24),mChannelMode:0x2(STEREO),mCodecSpecific1:0,mCodecSpecific2:0,mCodecSpecific3:0,mCodecSpecific4:0}, " +
            "{codecName:AAC,mCodecType:1,mCodecPriority:2001,mSampleRate:0x1(44100),mBitsPerSample:0x1(16),mChannelMode:0x2(STEREO),mCodecSpecific1:0,mCodecSpecific2:0,mCodecSpecific3:0,mCodecSpecific4:0}, " +
            "{codecName:SBC,mCodecType:0,mCodecPriority:1001,mSampleRate:0x1(44100),mBitsPerSample:0x1(16),mChannelMode:0x3(MONO|STEREO),mCodecSpecific1:0,mCodecSpecific2:0,mCodecSpecific3:0,mCodecSpecific4:0}]," +
            "mCodecsSelectableCapabilities:[{codecName:AAC,mCodecType:1,mCodecPriority:2001,mSampleRate:0x1(44100),mBitsPerSample:0x1(16),mChannelMode:0x2(STEREO),mCodecSpecific1:0,mCodecSpecific2:0,mCodecSpecific3:0,mCodecSpecific4:0}, " +
            "{codecName:SBC,mCodecType:0,mCodecPriority:1001,mSampleRate:0x1(44100),mBitsPerSample:0x1(16),mChannelMode:0x3(MONO|STEREO),mCodecSpecific1:0,mCodecSpecific2:0,mCodecSpecific3:0,mCodecSpecific4:0}]}"

    /** AOSP Android 15+ prints the codec type as a nested object. */
    private val aosp15Nested =
        "{mCodecConfig:{codecName:LDAC,mCodecType:BluetoothCodecType{mCodecName:LDAC,mCodecId:4,mIsMandatoryCodec:false},mCodecPriority:1000000,mSampleRate:0x8(96000),mBitsPerSample:0x2(24),mChannelMode:0x2(STEREO),mCodecSpecific1:1000,mCodecSpecific2:0,mCodecSpecific3:0,mCodecSpecific4:0}," +
            "mCodecsLocalCapabilities:[{codecName:LDAC,mCodecType:BluetoothCodecType{mCodecName:LDAC,mCodecId:4,mIsMandatoryCodec:false},mCodecPriority:5001,mSampleRate:0xf(44100|48000|88200|96000),mBitsPerSample:0x7(16|24|32),mChannelMode:0x2(STEREO),mCodecSpecific1:0,mCodecSpecific2:0,mCodecSpecific3:0,mCodecSpecific4:0}]," +
            "mCodecsSelectableCapabilities:[{codecName:LDAC,mCodecType:BluetoothCodecType{mCodecName:LDAC,mCodecId:4,mIsMandatoryCodec:false},mCodecPriority:5001,mSampleRate:0xc(88200|96000),mBitsPerSample:0x2(24),mChannelMode:0x2(STEREO),mCodecSpecific1:0,mCodecSpecific2:0,mCodecSpecific3:0,mCodecSpecific4:0}]}"

    @Test
    fun `real One UI 9 status string parses current, local and selectable`() {
        val d = assertNotNull(CodecStatusText.parse(oneUi9Sbc, "AA:BB:CC:DD:00:11"))
        assertEquals("AA:BB:CC:DD:00:11", d.address)
        val current = assertNotNull(d.current)
        assertEquals(CodecNames.SBC, current.codecName)
        assertEquals(listOf(44100), current.sampleRatesHz)
        assertEquals(listOf(16), current.bitDepths)
        assertEquals(listOf(CodecNames.SBC), d.selectable.map { it.codecName })
        assertEquals(
            listOf(CodecNames.SAMSUNG_SCALABLE, CodecNames.LDAC, CodecNames.APTX, CodecNames.AAC, CodecNames.SBC),
            d.local.map { it.codecName },
        )
        // Samsung's vendor id survives, so a switch to SSC can be described to the stack.
        assertEquals(8, d.local.first().codecType)
    }

    @Test
    fun `nested BluetoothCodecType of Android 15+ still parses`() {
        val d = assertNotNull(CodecStatusText.parse(aosp15Nested, null))
        val current = assertNotNull(d.current)
        assertEquals(CodecNames.LDAC, current.codecName)
        assertEquals(listOf(96000), current.sampleRatesHz)
        assertEquals(1000L, current.codecSpecific1)
        assertNull(current.codecType) // object, not an int: the service looks the type up by name instead
        assertEquals(listOf(88200, 96000), d.selectable.single().sampleRatesHz)
    }

    @Test
    fun `empty or codec-less strings give null`() {
        assertNull(CodecStatusText.parse(null, null))
        assertNull(CodecStatusText.parse("", null))
        assertNull(CodecStatusText.parse("null", null))
        assertNull(CodecStatusText.parse("{mCodecConfig:null,mCodecsLocalCapabilities:[],mCodecsSelectableCapabilities:[]}", null))
    }

    @Test
    fun `currentMatches checks name and, when asked, the sample rate`() {
        assertTrue(CodecStatusText.currentMatches(qcUltraAac, "AAC"))
        assertTrue(CodecStatusText.currentMatches(qcUltraAac, "aac", sampleRateMask = 0x1))
        assertFalse(CodecStatusText.currentMatches(qcUltraAac, "AAC", sampleRateMask = 0x2))
        assertFalse(CodecStatusText.currentMatches(qcUltraAac, "SBC"))
        assertTrue(CodecStatusText.currentMatches(oneUi9Sbc, "SBC"))
        assertFalse(CodecStatusText.currentMatches(null, "SBC"))
    }

    @Test
    fun `QC Ultra 2 status resolves both one-tap profiles`() {
        val d = assertNotNull(CodecStatusText.parse(qcUltraAac, null))
        val options = CodecProfiles.toOptions(d.selectable, d.local)
        assertEquals(listOf(CodecNames.AAC, CodecNames.SBC), options.filter { it.isSelectable }.map { it.codecName })
        assertFalse(options.first { it.codecName == CodecNames.SAMSUNG_SCALABLE }.isSelectable)

        val best = assertNotNull(CodecProfiles.pick(ConnectionProfile.BEST_QUALITY, options))
        assertEquals(CodecNames.AAC, best.codecName)
        assertEquals(44100, best.sampleRateHz)
        val stable = assertNotNull(CodecProfiles.pick(ConnectionProfile.MOST_STABLE, options))
        assertEquals(CodecNames.SBC, stable.codecName)
        assertEquals(44100, stable.sampleRateHz)

        // And what the service is then asked for.
        val ids = (d.selectable + d.local).mapNotNull { c -> c.codecType?.let { c.codecName to it } }.toMap()
        assertEquals(CodecRequest(CodecNames.AAC, 1, 0x1, 0x1, 0L), CodecRequest.from(best, ids))
        assertEquals(CodecRequest(CodecNames.SBC, 0, 0x1, 0x1, 0L), CodecRequest.from(stable, ids))
    }
}
