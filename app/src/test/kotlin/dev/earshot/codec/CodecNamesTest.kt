package dev.earshot.codec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodecNamesTest {

    @Test
    fun `normalises the spellings the stack prints`() {
        assertEquals(CodecNames.SBC, CodecNames.normalize("SBC"))
        assertEquals(CodecNames.AAC, CodecNames.normalize("aac"))
        assertEquals(CodecNames.APTX, CodecNames.normalize("aptX"))
        assertEquals(CodecNames.APTX, CodecNames.normalize("APTX"))
        assertEquals(CodecNames.APTX_HD, CodecNames.normalize("aptX HD"))
        assertEquals(CodecNames.APTX_HD, CodecNames.normalize("APTX_HD"))
        assertEquals(CodecNames.APTX_ADAPTIVE, CodecNames.normalize("aptX Adaptive"))
        assertEquals(CodecNames.APTX_ADAPTIVE, CodecNames.normalize("APTX-ADAPTIVE"))
        assertEquals(CodecNames.APTX_TWS_PLUS, CodecNames.normalize("aptX TWS+"))
        assertEquals(CodecNames.LDAC, CodecNames.normalize("LDAC"))
        assertEquals(CodecNames.LC3, CodecNames.normalize("LC3"))
        assertEquals(CodecNames.OPUS, CodecNames.normalize("OPUS"))
        assertEquals(CodecNames.SAMSUNG_SCALABLE, CodecNames.normalize("SSC"))
        assertEquals(CodecNames.SAMSUNG_SCALABLE, CodecNames.normalize("Samsung Scalable Codec"))
        assertEquals(CodecNames.SAMSUNG_SCALABLE, CodecNames.normalize("Scalable"))
        assertEquals(CodecNames.UNKNOWN, CodecNames.normalize(""))
        assertEquals(CodecNames.UNKNOWN, CodecNames.normalize("INVALID CODEC"))
    }

    @Test
    fun `unknown names pass through`() {
        assertEquals("FutureCodec9000", CodecNames.normalize(" FutureCodec9000 "))
    }

    @Test
    fun `aosp ints round-trip`() {
        for (t in 0..6) {
            val name = CodecNames.fromAospType(t)!!
            assertEquals(t, CodecNames.aospType(name))
        }
        assertEquals(null, CodecNames.fromAospType(42))
        assertEquals(null, CodecNames.aospType(CodecNames.APTX_ADAPTIVE)) // vendor: only the stack knows
    }

    @Test
    fun `quality ranking matches the profile order`() {
        val order = listOf(CodecNames.APTX_ADAPTIVE, CodecNames.LDAC, CodecNames.APTX_HD, CodecNames.AAC, CodecNames.SBC)
        assertEquals(order, order.sortedByDescending { CodecNames.qualityRank(it) })
        assertTrue(CodecNames.qualityRank("Whatever") < CodecNames.qualityRank(CodecNames.SBC))
    }

    @Test
    fun `ldac quality labels round-trip`() {
        for (label in CodecNames.LDAC_QUALITY_MODES) {
            assertEquals(label, CodecNames.ldacQualityLabel(CodecNames.ldacQualityValue(label)))
        }
        assertEquals(null, CodecNames.ldacQualityLabel(0L))
    }

    @Test
    fun `masks decode and encode`() {
        assertEquals(listOf(44100, 48000, 88200, 96000), CodecMasks.sampleRatesFromMask(0xF))
        assertEquals(listOf(44100, 48000, 96000), CodecMasks.sampleRatesFromMask(0xB))
        assertEquals(0x8, CodecMasks.sampleRateMask(96000))
        assertEquals(0, CodecMasks.sampleRateMask(null))
        assertEquals(listOf(16, 24, 32), CodecMasks.bitDepthsFromMask(0x7))
        assertEquals(0x2, CodecMasks.bitDepthMask(24))
        assertEquals(listOf("Mono", "Stereo"), CodecMasks.channelModesFromMask(0x3))
        assertEquals(0x2, CodecMasks.channelModeMask("stereo"))
    }
}
