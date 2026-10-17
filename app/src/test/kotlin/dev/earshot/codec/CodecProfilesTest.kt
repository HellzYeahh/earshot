package dev.earshot.codec

import dev.earshot.core.CodecOption
import dev.earshot.core.ConnectionProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodecProfilesTest {

    private fun cfg(name: String, rates: List<Int>, bits: List<Int> = listOf(16), type: Int? = null, cs1: Long? = 0) =
        ParsedCodecConfig(name, name, type, null, rates, bits, listOf("Stereo"), cs1, 0, 0, 0)

    @Test
    fun `current config maps to CodecInfo with LDAC quality label`() {
        val info = CodecProfiles.toCodecInfo(cfg(CodecNames.LDAC, listOf(96000), listOf(24), cs1 = 1000))
        assertEquals(CodecNames.LDAC, info.codecName)
        assertEquals(96000, info.sampleRateHz)
        assertEquals(24, info.bitsPerSample)
        assertEquals("Stereo", info.channelMode)
        assertEquals(CodecNames.LDAC_HQ, info.qualityLabel)
    }

    @Test
    fun `capability-shaped current config does not pretend to know a single rate`() {
        val info = CodecProfiles.toCodecInfo(cfg(CodecNames.AAC, listOf(44100, 48000)))
        assertNull(info.sampleRateHz)
    }

    @Test
    fun `options merge selectable, local and phone-only codecs in quality order`() {
        val options = CodecProfiles.toOptions(
            selectable = listOf(cfg(CodecNames.SBC, listOf(44100)), cfg(CodecNames.AAC, listOf(44100, 48000))),
            local = listOf(cfg(CodecNames.AAC, listOf(44100, 48000)), cfg(CodecNames.LDAC, listOf(44100, 96000), listOf(16, 24, 32))),
            extraPhoneCodecNames = listOf("SSC", "AAC", "INVALID CODEC"),
        )
        assertEquals(listOf(CodecNames.LDAC, CodecNames.SAMSUNG_SCALABLE, CodecNames.AAC, CodecNames.SBC), options.map { it.codecName })
        assertTrue(options.first { it.codecName == CodecNames.AAC }.isSelectable)
        assertFalse(options.first { it.codecName == CodecNames.LDAC }.isSelectable)
        assertFalse(options.first { it.codecName == CodecNames.SAMSUNG_SCALABLE }.isSelectable)
        assertEquals(CodecNames.LDAC_QUALITY_MODES, options.first { it.codecName == CodecNames.LDAC }.qualityModes)
    }

    private val options = listOf(
        CodecOption(CodecNames.LDAC, listOf(44100, 96000), listOf(16, 24, 32), CodecNames.LDAC_QUALITY_MODES, isSelectable = false),
        CodecOption(CodecNames.APTX_HD, listOf(48000), listOf(24), isSelectable = true),
        CodecOption(CodecNames.AAC, listOf(44100, 48000), listOf(16), isSelectable = true),
        CodecOption(CodecNames.SBC, listOf(44100, 48000), listOf(16), isSelectable = true),
    )

    @Test
    fun `best quality picks the highest selectable codec at its top rate`() {
        val pick = assertNotNull(CodecProfiles.pick(ConnectionProfile.BEST_QUALITY, options))
        assertEquals(CodecNames.APTX_HD, pick.codecName) // LDAC is present but not selectable
        assertEquals(48000, pick.sampleRateHz)
        assertEquals(24, pick.bitsPerSample)
        assertNull(pick.qualityMode)
    }

    @Test
    fun `best quality asks LDAC for its high-quality mode`() {
        val withLdac = options.map { if (it.codecName == CodecNames.LDAC) it.copy(isSelectable = true) else it }
        val pick = assertNotNull(CodecProfiles.pick(ConnectionProfile.BEST_QUALITY, withLdac))
        assertEquals(CodecNames.LDAC, pick.codecName)
        assertEquals(96000, pick.sampleRateHz)
        assertEquals(CodecNames.LDAC_HQ, pick.qualityMode)
    }

    @Test
    fun `most stable prefers SBC at 44_1k, then AAC`() {
        val pick = assertNotNull(CodecProfiles.pick(ConnectionProfile.MOST_STABLE, options))
        assertEquals(CodecNames.SBC, pick.codecName)
        assertEquals(44100, pick.sampleRateHz)

        val noSbc = options.filterNot { it.codecName == CodecNames.SBC }
        assertEquals(CodecNames.AAC, CodecProfiles.pick(ConnectionProfile.MOST_STABLE, noSbc)?.codecName)
    }

    @Test
    fun `nothing selectable means no pick`() {
        assertNull(CodecProfiles.pick(ConnectionProfile.BEST_QUALITY, options.map { it.copy(isSelectable = false) }))
        assertNull(CodecProfiles.pick(ConnectionProfile.MOST_STABLE, emptyList()))
    }
}
