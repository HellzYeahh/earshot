package dev.earshot.protocol.qcultra2

import dev.earshot.protocol.FunctionAddress
import dev.earshot.protocol.hexToBytes
import dev.earshot.protocol.toHex
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Parsers run against the real bytes in bosectl's capture snapshots. */
class CaptureParsersTest {
    private val base = Captures.before(Captures.NOISE_CONTROL)

    @Test
    fun `capture loader sees every scan and the documented keys`() {
        val snaps = Captures.snapshots(Captures.NOISE_CONTROL)
        assertEquals(listOf("before", "t+3s", "t+9s", "t+15s", "after"), snaps.map { it.label })
        assertEquals(57, base.state.size) // 11 + 11 + 5 + 7 + 7 + 8 + 1 + 3 + 2 + 2 across blocks 0-9
        assertEquals("50ffff00", base.hex(QcUltra2.BATTERY))
    }

    // ── Status ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `battery 50ffff00 is 80 percent on the headphones`() {
        val payload = base.bytes(QcUltra2.BATTERY)
        assertEquals(80, Battery.parsePercent(payload))
        val status = Battery.parse(payload)
        assertEquals(80, status.percent)
        assertEquals(listOf(BatteryReading(0, 80)), status.readings)
        assertEquals(BatteryComponent.SINGLE, status.readings.single().component)
    }

    @Test
    fun `battery 0xFF means unknown`() {
        assertNull(Battery.parsePercent("ffffff00".hexToBytes()))
        assertNull(Battery.parsePercent(ByteArray(0)))
        assertNull(Battery.parse("ffffff00".hexToBytes()).percent)
    }

    @Test
    fun `earbuds fixture has four component records and the combined figure wins`() {
        val payload = Captures.text(Captures.EARBUDS_BATTERY).trim().hexToBytes()
        val status = Battery.parse(payload)
        assertEquals(4, status.readings.size)
        assertEquals(60, status.readings.first { it.component == BatteryComponent.RIGHT }.percent)
        assertEquals(60, status.readings.first { it.component == BatteryComponent.LEFT }.percent)
        assertEquals(80, status.readings.first { it.component == BatteryComponent.CASE }.percent)
        assertEquals(60, status.percent)
        // without the combined record, the lower bud stands in
        val noCombined = Battery.parseReadings("3cffff01 28ffff02 50ffff03".hexToBytes())
        assertEquals(40, Battery.aggregate(noCombined))
        // combined present but unknown → fall back as well
        val unknownCombined = Battery.parseReadings("3cffff01 3cffff02 ffffff04".hexToBytes())
        assertEquals(60, Battery.aggregate(unknownCombined))
    }

    // ── ProductInfo ──────────────────────────────────────────────────────────────────────

    @Test
    fun `product info strings and ids decode from the scan`() {
        assertEquals("1.1.0", ProductInfo.parseString(base.bytes(QcUltra2.PRODUCT_INFO_FBLOCK_INFO)))
        assertEquals("1.2.0", ProductInfo.parseString(base.bytes(QcUltra2.BMAP_VERSION)))
        assertEquals("8.2.20+g34cf029", ProductInfo.parseFirmwareVersion(base.bytes(QcUltra2.FIRMWARE_VERSION)))
        assertEquals("Bose QC Ultra 2 HP", ProductInfo.parseString(base.bytes(QcUltra2.PRODUCT_NAME)))
        val id = ProductInfo.parseProductIdVariant(base.bytes(QcUltra2.PRODUCT_ID_VARIANT))!!
        assertEquals(QcUltra2.PRODUCT_ID, id.productId)
        assertEquals(1, id.variant)
        assertTrue(id.isQcUltra2)
        assertTrue(base.isMasked(QcUltra2.MAC_ADDRESS))
        assertEquals("68:F2:1F:00:00:00", ProductInfo.parseMac(base.bytes(QcUltra2.MAC_ADDRESS)))
        assertEquals("68:F2:1F:00:00:00", ProductInfo.parseMac(base.bytes(FunctionAddress(0, 17))))
        assertEquals("1.1.0", ProductInfo.parseString(base.bytes(QcUltra2.SETTINGS_FBLOCK_INFO)))
    }

    @Test
    fun `all-function-blocks mask covers every block the notes probed`() {
        val blocks = ProductInfo.parseAllFunctionBlocks(base.bytes(QcUltra2.ALL_FUNCTION_BLOCKS))!!
        assertEquals(setOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 13, 18, 19, 22, 23, 24, 25, 26, 31), blocks)
        // every block the headphones report has a name (blocks 23-26 as "Unknown (error 24)")
        for (block in blocks) assertNotNull(FunctionNames.blockName(block), "block $block")
        assertEquals(FunctionNames.UNKNOWN_ERROR24_BLOCK, FunctionNames.blockName(24))
    }

    // ── Settings ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `custom name has a flag byte then UTF-8`() {
        assertEquals("00466172676f", base.hex(QcUltra2.CUSTOM_NAME))
        assertEquals("Fargo", CustomName.parse(base.bytes(QcUltra2.CUSTOM_NAME)))
        assertEquals("Fargo", String(CustomName.build("Fargo")))
    }

    @Test
    fun `EQ f60a0000 f60afe01 f60afa02 is bass 0 mid -2 treble -6`() {
        val bands = Eq.parse(base.bytes(QcUltra2.EQ))
        assertEquals(3, bands.size)
        assertEquals(listOf(0, -2, -6), bands.map { it.value })
        assertEquals(listOf(EqBandId.BASS, EqBandId.MID, EqBandId.TREBLE), bands.map { it.band })
        assertTrue(bands.all { it.min == -10 && it.max == 10 })
        assertEquals(-6, Eq.valueOf(bands, EqBandId.TREBLE))
    }

    @Test
    fun `EQ captures show each band moving plus then minus then back`() {
        fun values(name: String) = Captures.snapshots(name).map { s -> Eq.parse(s.bytes(QcUltra2.EQ)).map { it.value } }
        assertEquals(listOf(listOf(0, 0, 0), listOf(1, 0, 0), listOf(-4, 0, 0), listOf(0, 0, 0)), values(Captures.EQ_BASS))
        assertEquals(listOf(listOf(0, 0, 0), listOf(0, 10, 0), listOf(0, -9, 0), listOf(0, 0, 0)), values(Captures.EQ_MID))
        assertEquals(listOf(listOf(0, 0, 0), listOf(0, 0, 9), listOf(0, 0, -9), listOf(0, 0, 0)), values(Captures.EQ_TREBLE))
    }

    @Test
    fun `EQ band builder matches the SET_GET layout and the GET echo`() {
        assertEquals("fc00", Eq.buildBand(-4, EqBandId.BASS).toHex())
        assertEquals("0a01", Eq.buildBand(10, EqBandId.MID).toHex())
        assertEquals("f702", Eq.buildBand(-9, 2).toHex())
        // the "current" byte of the GET reply is exactly the value byte we send
        val after = Captures.snapshots(Captures.EQ_TREBLE)[2].bytes(QcUltra2.EQ)
        assertEquals(Eq.buildBand(-9, EqBandId.TREBLE)[0], after[10])
        assertFails { Eq.buildBand(11, EqBandId.BASS) }
    }

    @Test
    fun `CNC 1_5 mirrors the app slider across the capture`() {
        val steps = Captures.snapshots(Captures.CNC_SLIDER).map { Cnc.parse(it.bytes(QcUltra2.CNC))!! }
        assertEquals(listOf(0, 7, 5, 1, 0), steps.map { it.step })
        assertTrue(steps.all { it.steps == 11 && it.maxStep == 10 && it.flags == 3 })
        assertTrue(steps.first().enabled)
        assertFalse(steps.first().userCanToggle)
    }

    @Test
    fun `buttons 80090e00094002 is the shortcut button long press disabled`() {
        val cfg = Buttons.parse(base.bytes(QcUltra2.BUTTONS))!!
        assertEquals(ButtonId.SHORTCUT, cfg.button)
        assertEquals(ButtonEvent.LONG_PRESS, cfg.event)
        assertEquals(ButtonAction.DISABLED, cfg.action)
        // big-endian 0x00094002 → bits 1, 14, 16, 19; the current action (14) is one of them
        assertEquals(setOf(1, 14, 16, 19), cfg.supportedActionCodes)
        assertEquals(listOf(ButtonAction.VPA, ButtonAction.DISABLED, ButtonAction.SPOTIFY_GO, ButtonAction.SPATIAL_AUDIO_MODE), cfg.supportedActions)
        assertEquals(cfg.supportedActionCodes, cfg.availableActionCodes) // no unavailable mask in the 7-byte form
        assertTrue(cfg.isConsistent)
        assertTrue(cfg.unavailableActionCodes.isEmpty())
        assertEquals(0, cfg.tail.size)
        // the remap from the notes: bose raw "01 09 02 03 80 09 08"
        assertEquals("800908", Buttons.build(ButtonId.SHORTCUT, ButtonEvent.LONG_PRESS, ButtonAction.SWITCH_DEVICE).toHex())
    }

    @Test
    fun `multipoint 07 has bit 0x02 set`() {
        assertEquals(true, Multipoint.parse(base.bytes(QcUltra2.MULTIPOINT)))
        assertEquals(false, Multipoint.parse(byteArrayOf(0x05)))
        assertEquals("01", Multipoint.build(true).toHex())
        // the capture lost the device after the toggle: every later scan is empty
        val later = Captures.snapshots(Captures.MULTIPOINT).drop(1)
        assertTrue(later.all { it.state.isEmpty() }, "multipoint toggle should have dropped the link")
    }

    @Test
    fun `sidetone 01020f is persistent medium`() {
        val s = Sidetone.parse(base.bytes(QcUltra2.SIDETONE))!!
        assertTrue(s.persist)
        assertEquals(SidetoneLevel.MEDIUM, s.level)
        assertEquals(0x0f, s.supportedLevelsMask)
        assertEquals(SidetoneLevel.entries.toList(), s.supportedLevels)
        assertEquals("0102", Sidetone.build(SidetoneLevel.MEDIUM).toHex())
        assertEquals("0003", Sidetone.build(SidetoneLevel.LOW, persist = false).toHex())
        assertEquals(1, SidetoneLevel.HIGH.code)
        assertEquals(3, SidetoneLevel.LOW.code)
    }

    @Test
    fun `voice prompts 41000081020000 decodes per bosectl`() {
        val vp = VoicePrompts.parse(base.bytes(QcUltra2.VOICE_PROMPTS))!!
        assertEquals(VoicePromptLanguage.US_ENGLISH, vp.language)
        assertFalse(vp.enabled) // bit 5 clear; bit 6 set and unexplained — see the KDoc
        assertEquals(setOf(1, 8, 15), vp.installedLanguageCodes)
        assertEquals(listOf(VoicePromptLanguage.US_ENGLISH, VoicePromptLanguage.MANDARIN, VoicePromptLanguage.JAPANESE), vp.installedLanguages)
        assertEquals("21", VoicePrompts.build(true, VoicePromptLanguage.US_ENGLISH).toHex())
        assertEquals("04", VoicePrompts.build(false, VoicePromptLanguage.GERMAN).toHex())
        val round = VoicePrompts.parse(VoicePrompts.build(true, VoicePromptLanguage.JAPANESE))!!
        assertTrue(round.enabled)
        assertEquals(VoicePromptLanguage.JAPANESE, round.language)
        assertNull(round.installedLanguageCodes)
    }

    @Test
    fun `one-byte toggles read as booleans`() {
        assertEquals(true, Toggle.parse(base.bytes(QcUltra2.AUTO_PLAY_PAUSE)))
        assertEquals(true, Toggle.parse(base.bytes(QcUltra2.AUTO_ANSWER)))
        assertEquals(true, Toggle.parse(base.bytes(QcUltra2.SETUP_COMPLETE)))
        assertEquals(true, Power.parse(base.bytes(QcUltra2.POWER)))
        assertEquals(false, PairingMode.parse(base.bytes(QcUltra2.PAIRING_MODE)))
        assertEquals("00", Toggle.build(false).toHex())
    }

    // ── DeviceManagement / AudioManagement ───────────────────────────────────────────────

    @Test
    fun `paired devices 4_4 lists two addresses after a header byte`() {
        assertTrue(base.isMasked(QcUltra2.PAIRED_DEVICES))
        val list = PairedDeviceList.parse(base.bytes(QcUltra2.PAIRED_DEVICES))!!
        assertEquals(3, list.header)
        assertEquals(listOf("C4:0F:08:00:00:00", "94:45:60:85:05:A7"), list.macs)
        // zero padding is skipped
        val padded = PairedDeviceList.parse("01 c40f08112233 000000000000".hexToBytes())!!
        assertEquals(listOf("C4:0F:08:11:22:33"), padded.macs)
    }

    @Test
    fun `audio source 5_1 is bluetooth with the first paired device streaming`() {
        val src = AudioSourceQuery.parse(base.bytes(QcUltra2.AUDIO_SOURCE))!!
        assertEquals(0x000f, src.supportedMask)
        assertEquals(AudioSourceType.BLUETOOTH, src.type)
        assertEquals("C4:0F:08:00:00:00", src.mac)
        val paired = PairedDeviceList.parse(base.bytes(QcUltra2.PAIRED_DEVICES))!!
        assertTrue(src.mac in paired.macs)
        // local MAC [4.9] in the scan is the same address as the active source, not the headphones' own
        assertEquals(src.mac, Mac.format(base.bytes(QcUltra2.APP_ADDRESS)))
        assertNull(AudioSourceQuery.parse("000f02".hexToBytes())!!.mac)
        assertEquals(AudioSourceType.AUXILIARY, AudioSourceQuery.parse("000f02".hexToBytes())!!.type)
    }

    @Test
    fun `nothing in the scan is left unlabelled and everything scanned is on the readable list`() {
        for (address in base.state.keys) {
            assertNotNull(FunctionNames.functionName(address), "no name for $address")
            assertTrue(FunctionNames.isKnownReadable(address), "$address missing from knownReadableFunctions")
        }
        assertEquals("Settings.RangeControl [1.7]", FunctionNames.label(QcUltra2.EQ))
        assertEquals("HearingAssistance.Directionality [12.3]", FunctionNames.label(FunctionAddress(12, 3)))
        assertEquals("Block 40.[3] [40.3]", FunctionNames.label(FunctionAddress(40, 3)))
    }
}
