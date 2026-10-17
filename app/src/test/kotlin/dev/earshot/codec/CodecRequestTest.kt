package dev.earshot.codec

import dev.earshot.core.CodecSelection
import kotlin.test.Test
import kotlin.test.assertEquals

/** Selection → the (codecType, sampleRate, bits, specific1) numbers sent to the codec user service. */
class CodecRequestTest {

    @Test
    fun `AAC at 44_1 kHz 16 bit uses the AOSP id and single-bit masks`() {
        val r = CodecRequest.from(CodecSelection("AAC", 44100, 16))
        assertEquals(CodecRequest(CodecNames.AAC, 1, 0x1, 0x1, 0L), r)
    }

    @Test
    fun `unset rate and depth mean keep what is negotiated`() {
        val r = CodecRequest.from(CodecSelection("SBC"))
        assertEquals(0, r.codecType)
        assertEquals(0, r.sampleRateMask)
        assertEquals(0, r.bitsMask)
    }

    @Test
    fun `LDAC carries its quality mode in codecSpecific1`() {
        val r = CodecRequest.from(CodecSelection("ldac", 96000, 24, CodecNames.LDAC_HQ))
        assertEquals(CodecRequest(CodecNames.LDAC, 4, 0x8, 0x2, 1000L), r)
        assertEquals(1003L, CodecRequest.from(CodecSelection("LDAC", qualityMode = CodecNames.LDAC_ABR)).codecSpecific1)
        // Quality modes mean nothing for other codecs.
        assertEquals(0L, CodecRequest.from(CodecSelection("AAC", qualityMode = CodecNames.LDAC_HQ)).codecSpecific1)
    }

    @Test
    fun `stack-reported ids win, vendor codecs without one are unknown`() {
        val known = mapOf(CodecNames.SAMSUNG_SCALABLE to 8)
        assertEquals(8, CodecRequest.from(CodecSelection("SSC", 48000, 24), known).codecType)
        assertEquals(0x2, CodecRequest.from(CodecSelection("SSC", 48000, 24), known).sampleRateMask)
        assertEquals(CodecRequest.UNKNOWN_TYPE, CodecRequest.from(CodecSelection("aptX Adaptive", 48000)).codecType)
    }

    @Test
    fun `rates and depths outside the BluetoothCodecConfig table fall back to keep`() {
        val r = CodecRequest.from(CodecSelection("AAC", 32000, 20))
        assertEquals(0, r.sampleRateMask)
        assertEquals(0, r.bitsMask)
    }
}
