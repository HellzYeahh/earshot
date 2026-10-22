package dev.earshot.protocol.sennheiser

import dev.earshot.protocol.gaia.GaiaFeaturePage
import dev.earshot.protocol.gaia.GaiaFrame
import dev.earshot.protocol.gaia.GaiaFrameBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Payloads from momentum4-control's captures (Momentum 4 fw 3.37.3 / 3.38.3, Momentum 5 fw 6.21.5; MIT). */
class SennheiserWireTest {

    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Fold every frame of a captured stream into a status, as the session does. */
    private fun SennheiserStatus.feed(stream: String): SennheiserStatus =
        GaiaFrameBuffer().feed(hex(stream)).fold(this) { s, f -> s.apply(f.command, f.payload) }

    private val m4Features = GaiaFeaturePage.parse(hex("00 09 01 08 06 0C 01 03 02 10 02 04 02 02 01 0A 02 14 01 0B 01 0D 01 00 04"))!!.features

    @Test fun `identity and firmware from the captured replies`() {
        val s = SennheiserStatus().feed(
            "FF 03 00 06 04 95 13 01 00 03 00 25 00 03" +
                "FF 03 00 0D 04 95 13 06 4D 34 41 45 42 54 20 42 6C 61 63 6B 00",
        )
        assertEquals("3.37.3", s.firmware)
        assertEquals("M4AEBT Black", s.modelId)
        assertEquals(SennheiserModel.MOMENTUM_4, s.model)
        assertEquals("6.21.5", SennheiserWire.firmware(hex("00 06 00 15 00 05")))
    }

    @Test fun `the registration dump of a Momentum 4 fills battery, ANC and EQ`() {
        val s = SennheiserStatus(features = m4Features).feed(
            "FF 03 00 00 04 95 01 07" + // ack of the registration: changes nothing
                "FF 03 00 01 04 95 06 82 00" +
                "FF 03 00 01 04 95 06 83 64" +
                "FF 03 00 01 04 95 1A 85 01" +
                "FF 03 00 06 04 95 1A 81 01 01 02 00 03 00" +
                "FF 03 00 01 04 95 1A 83 64" +
                "FF 03 00 05 04 95 10 82 3C 3C 16 16 00" +
                "FF 03 00 01 04 95 10 89 01",
        )
        assertEquals(listOf<Int?>(100), s.battery)
        assertEquals(listOf(0), s.charging)
        assertEquals(true, s.ancOn)
        assertEquals(100, s.ancLevel)
        assertEquals(mapOf(1 to 1, 2 to 0, 3 to 0), s.ancModes)
        assertEquals(listOf(60, 60, 22, 22, 0), s.eqGains)
        assertEquals(true, s.bassBoost)
        assertTrue(s.eqGen2)
    }

    @Test fun `EQ frames are ignored on generation-1 firmware`() {
        val gen1 = SennheiserStatus(features = m4Features + (SennheiserFeature.USER_EQ to 2))
        val s = gen1.feed("FF 03 00 05 04 95 10 82 3C 3C 16 16 00" + "FF 03 00 05 04 95 11 03 3C 3C 16 16 0A" + "FF 03 00 05 04 95 11 00 05 C4 3C 00 00")
        assertFalse(s.eqGen2)
        assertNull(s.eqGains)
        assertNull(s.eqConfig)
    }

    @Test fun `EQ config, all gains and a single-band reply`() {
        val s = SennheiserStatus(features = m4Features).feed(
            "FF 03 00 05 04 95 11 00 08 C4 3C 00 00" + // Momentum 5: 8 bands, ±6 dB
                "FF 03 00 05 04 95 11 03 3C 3C 16 16 0A",
        )
        assertEquals(SennheiserEqConfig(8, -60, 60), s.eqConfig)
        assertEquals(-6..6, s.eqConfig?.rangeDb)
        assertEquals(listOf(60, 60, 22, 22, 10), s.eqGains)
        // A GET of one band answers [gain]: not the whole list, so it is not applied.
        assertEquals(s, s.feed("FF 03 00 01 04 95 11 02 0A"))
        assertNull(SennheiserEqConfig.parse(hex("00 C4 3C")))
        assertNull(SennheiserEqConfig.parse(hex("05 3C C4")))
    }

    @Test fun `settings replies from the behaviour, sidetone and auto-off captures`() {
        val s = SennheiserStatus(features = m4Features).feed(
            "FF 03 00 01 04 95 05 01 01" + // on-head detection on
                "FF 03 00 01 04 95 09 0D 00" + // smart pause off
                "FF 03 00 01 04 95 09 0B 01" + // auto-answer on
                "FF 03 00 01 04 95 09 15 01" + // comfort call on
                "FF 03 00 01 04 95 09 06 02" + // sidetone 2
                "FF 03 00 03 04 95 07 01 00 07 08" + // auto-off 1800 s
                "FF 03 00 01 04 95 19 03 64" + // earbud transparency level 100
                "FF 03 00 01 04 95 19 05 00", // transparency off
        )
        assertEquals(true, s.onHeadDetection)
        assertEquals(false, s.smartPause)
        assertEquals(true, s.autoAnswer)
        assertEquals(true, s.comfortCall)
        assertEquals(2, s.sidetone)
        assertEquals(1800, s.autoOffSeconds)
        assertEquals(100, s.transparencyLevel)
        assertEquals(false, s.transparencyOn)
    }

    @Test fun `battery layouts and unknown parts`() {
        assertEquals(listOf<Int?>(42), SennheiserWire.battery(hex("2A")))
        assertEquals(listOf(80, null, 55), SennheiserWire.battery(hex("50 FF 37")))
        // The unsolicited wear-state response after registering feature 2.
        assertEquals(listOf(3), SennheiserStatus().apply(0x0502, hex("03")).wear)
    }

    @Test fun `error frames and empty set acks change nothing`() {
        val s = SennheiserStatus(ancOn = true)
        assertEquals(s, s.apply(0x1B85, hex("05")))
        assertEquals(s, s.apply(0x1B04, ByteArray(0)))
        assertEquals(s, s.apply(0x1A04, hex("00"))) // our own request echoed: not a reply
    }

    @Test fun `noise level conversions`() {
        assertEquals(0, SennheiserWire.levelFromCancelling(10))
        assertEquals(100, SennheiserWire.levelFromCancelling(0))
        assertEquals(30, SennheiserWire.levelFromCancelling(7))
        assertEquals(10, SennheiserWire.cancellingFromLevel(0))
        assertEquals(0, SennheiserWire.cancellingFromLevel(100))
        assertEquals(7, SennheiserWire.cancellingFromLevel(30))
        assertEquals(7, SennheiserWire.cancellingFromLevel(34))
        for (n in 0..10) assertEquals(n, SennheiserWire.cancellingFromLevel(SennheiserWire.levelFromCancelling(n)))
        assertEquals(0, SennheiserWire.transparencyPreset(1))
        assertEquals(1, SennheiserWire.transparencyPreset(40))
        assertEquals(2, SennheiserWire.transparencyPreset(100))
    }

    @Test fun `auto-off and mode-table payloads match the captures`() {
        assertContentEquals(hex("00 07 08"), SennheiserWire.autoOffSet(1800))
        assertContentEquals(hex("00 0E 10"), SennheiserWire.autoOffSet(3600))
        assertContentEquals(hex("00 03 84"), SennheiserWire.autoOffSet(900))
        assertContentEquals(hex("01 02"), SennheiserWire.ancModePair(1, 2))
        assertEquals(mapOf(1 to 0, 3 to 0, 2 to 0), SennheiserWire.ancModes(hex("01 00 03 00 02 00"))) // Momentum 5 order
        assertContentEquals(
            hex("FF 03 00 02 04 95 1A 00 01 02"),
            GaiaFrame(SennheiserCommands.VENDOR, SennheiserCommands.ANC_MODE_SET, SennheiserWire.ancModePair(1, 2)).encode(),
        )
    }

    @Test fun `models by Bluetooth name`() {
        val expected = mapOf(
            "MOMENTUM 4" to SennheiserModel.MOMENTUM_4,
            "Momentum 4 Wireless" to SennheiserModel.MOMENTUM_4,
            "MOMENTUM 5" to SennheiserModel.MOMENTUM_5,
            "MOMENTUM TW 3" to SennheiserModel.MOMENTUM_TW3,
            "MOMENTUM TW 4" to SennheiserModel.MOMENTUM_TW4,
            "MOMENTUM TW4" to SennheiserModel.MOMENTUM_TW4,
            "ACCENTUM" to SennheiserModel.ACCENTUM,
            "ACCENTUM Plus" to SennheiserModel.ACCENTUM_PLUS,
            "ACCENTUM TW" to SennheiserModel.ACCENTUM_TW,
            "MOMENTUM SPORT" to SennheiserModel.MOMENTUM_SPORT,
            "MOMENTUM Sport" to SennheiserModel.MOMENTUM_SPORT,
            "MSPORT1-HR" to SennheiserModel.MOMENTUM_SPORT,
        )
        for ((name, model) in expected) assertEquals(model, SennheiserModel.fromName(name), name)
        // BLE-only, GAIA v1/v2, another protocol, unanalysed, codenames and look-alikes: generic.
        for (name in listOf(
            "CX Plus True Wireless", "CX 500BT", "MOMENTUM TW 2", "MOMENTUM 3", "HD 450BT", "PXC 550",
            "MOMENTUM TW 5", "ACCENTUM Clip", "Mars", "Venus", "Bose QC Ultra Headphones", "momentumctl", null,
        )) {
            assertNull(SennheiserModel.fromName(name), name.toString())
        }
    }

    @Test fun `models by the 0x1206 string`() {
        assertEquals(SennheiserModel.MOMENTUM_4, SennheiserModel.fromModelId("M4AEBT Graphite"))
        assertEquals(SennheiserModel.MOMENTUM_5, SennheiserModel.fromModelId("M5AEBT Black"))
        assertEquals(SennheiserModel.ACCENTUM_PLUS, SennheiserModel.fromModelId("ACPAEBT White"))
        assertEquals(SennheiserModel.ACCENTUM, SennheiserModel.fromModelId("ACAEBT Black"))
        assertEquals(SennheiserModel.MOMENTUM_TW4, SennheiserModel.fromModelId("MTW4 Black Copper"))
        assertEquals(SennheiserModel.ACCENTUM_TW, SennheiserModel.fromModelId("ATW1 Black"))
        assertEquals(SennheiserModel.MOMENTUM_SPORT, SennheiserModel.fromModelId("MSPORT1 Olive"))
        assertNull(SennheiserModel.fromModelId("CXPLUSTW1 Black"))
        assertNull(SennheiserModel.fromModelId(null))
    }

    @Test fun `noise families and declared settings`() {
        assertEquals(SennheiserNoiseFamily.SLIDER, SennheiserModel.MOMENTUM_4.noise)
        assertEquals(SennheiserNoiseFamily.TOGGLES, SennheiserModel.ACCENTUM.noise)
        assertEquals(SennheiserNoiseFamily.EARBUDS, SennheiserModel.MOMENTUM_TW4.noise)
        assertFalse(SennheiserSetting.SMART_PAUSE in SennheiserModel.ACCENTUM.settings) // not on the Accentum
        assertFalse(SennheiserSetting.ANTI_WIND in SennheiserModel.ACCENTUM.settings) // its mode table is ignored
        assertTrue(SennheiserSetting.TRANSPARENCY_LEVEL in SennheiserModel.ACCENTUM_TW.settings)
        assertNotNull(SennheiserModel.GAIA3_SERVICE)
    }
}
