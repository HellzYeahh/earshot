package dev.earshot.protocol.sennheiser

import dev.earshot.protocol.gaia.GaiaCommand
import dev.earshot.protocol.gaia.GaiaFeaturePage
import dev.earshot.protocol.gaia.GaiaFrame
import dev.earshot.protocol.gaia.GaiaVendor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The block-list from the Sennheiser notes §4 must never leave the phone, whatever the headset reports. */
class SennheiserWriteGuardTest {

    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Everything a Momentum 4 reports, plus every blocked feature, so nothing passes merely by being absent. */
    private val m4 = GaiaFeaturePage.parse(hex("00 09 01 08 06 0C 01 03 02 10 02 04 02 02 01 0A 02 14 01 0B 01 0D 01 00 04"))!!.features
    private val everything: Map<Int, Int> = m4 + (1..30).associateWith { 6 }

    private fun sen(command: Int, vararg payload: Int) = GaiaFrame(SennheiserCommands.VENDOR, command, ByteArray(payload.size) { payload[it].toByte() })
    private fun qc(command: Int, vararg payload: Int) = GaiaFrame(GaiaVendor.QUALCOMM, command, ByteArray(payload.size) { payload[it].toByte() })

    private fun refused(frame: GaiaFrame, reported: Map<Int, Int>? = everything) =
        assertNotNull(SennheiserWriteGuard.refusal(frame, reported), "$frame must be refused")

    private fun allowed(frame: GaiaFrame, reported: Map<Int, Int>? = m4) =
        assertNull(SennheiserWriteGuard.refusal(frame, reported), "$frame must be allowed")

    @Test fun `factory reset is refused`() {
        refused(sen(0x0040))
        refused(sen(0x0040, 1))
        assertTrue(SennheiserWriteGuard.refusal(sen(0x0040), everything)!!.contains("factory reset"))
    }

    @Test fun `firmware update is refused under both vendors`() {
        refused(sen(0x0200))
        refused(sen(0x0201, 0, 0, 0x10, 0))
        // Anything else in Sennheiser feature 1 too.
        refused(sen(GaiaCommand.of(feature = 1, pdu = 5)))
        refused(sen(0x0202))
        for (c in 0x0C00..0x0C02) refused(qc(c))
        refused(qc(GaiaCommand.of(feature = 6, pdu = 9)))
    }

    @Test fun `reboot and role switch are refused`() {
        refused(sen(0x060F))
        refused(sen(0x1C00))
        refused(sen(GaiaCommand.of(feature = 14, pdu = 2)))
    }

    @Test fun `pairing-list disconnect and delete are refused`() {
        refused(sen(0x1403, 3))
        refused(sen(0x1405, 0))
        refused(sen(0x1406))
    }

    @Test fun `debug and data transfer are refused`() {
        refused(qc(0x0803))
        refused(qc(0x0804))
        refused(qc(0x0009))
        refused(qc(0x000A, 0, 0))
        refused(qc(0x000D, 1, 4))
        refused(qc(0x0601, 2)) // voice assistant select
        refused(sen(0x080E))
        refused(sen(0x080F))
    }

    @Test fun `hearing-profile reset, button reset and rename are refused`() {
        refused(sen(0x2009))
        refused(sen(0x1604))
        refused(sen(0x2801, 0x41, 0x42))
    }

    @Test fun `button functions 8 and 19 are refused, and button mapping is not on the allow-list at all`() {
        refused(sen(0x1600, 0, 1, 8))
        refused(sen(0x1600, 1, 2, 19))
        assertTrue(SennheiserWriteGuard.refusal(sen(0x1600, 0, 1, 8), everything)!!.contains("button function 8"))
        refused(sen(0x1600, 0, 1, 1)) // play/pause: harmless, but Earshot doesn't map buttons
    }

    @Test fun `a feature the headset did not report is refused`() {
        val noAnc = m4 - SennheiserFeature.ANC
        refused(sen(SennheiserCommands.ANC_SET, 1), noAnc)
        refused(sen(SennheiserCommands.ANC_GET), noAnc)
        refused(sen(SennheiserCommands.REGISTER_NOTIFICATION, SennheiserFeature.ANC), noAnc)
        // Fit test (15) and find device (22) are not reported by a Momentum 4.
        refused(sen(GaiaCommand.of(feature = 15, pdu = 0)), m4)
        refused(sen(0x2C00), m4)
    }

    @Test fun `nothing but discovery goes out before the feature list`() {
        allowed(sen(SennheiserCommands.SUPPORTED_FEATURES), reported = null)
        allowed(qc(0x0000), reported = null)
        allowed(qc(0x0001), reported = null)
        allowed(qc(0x0002), reported = null)
        refused(sen(SennheiserCommands.BATTERY_GET), reported = null)
        refused(sen(SennheiserCommands.MODEL_ID_GET), reported = null)
        refused(sen(SennheiserCommands.ANC_SET, 1), reported = null)
    }

    @Test fun `responses, unknown vendors and other Qualcomm commands are refused`() {
        refused(sen(GaiaCommand.response(SennheiserCommands.ANC_SET)))
        refused(sen(GaiaCommand.notification(SennheiserCommands.ANC_GET), 1))
        refused(GaiaFrame(0x0494, 0x0101)) // legacy factory reset vendor
        refused(GaiaFrame(0x000A, 0x0204)) // legacy power off
        refused(GaiaFrame(0x000A, 0x0712)) // persistent-store write
        refused(qc(0x0007, 1))
        refused(qc(0x0000, 1)) // discovery takes no payload
    }

    @Test fun `the reads and writes Earshot uses pass on a Momentum 4`() {
        for (c in listOf(
            SennheiserCommands.BATTERY_GET, SennheiserCommands.CHARGING_GET, SennheiserCommands.ANC_GET, SennheiserCommands.ANC_LEVEL_GET,
            SennheiserCommands.ANC_MODE_GET, SennheiserCommands.TH_GET, SennheiserCommands.TH_LEVEL_GET, SennheiserCommands.TH_BEHAVIOUR_GET,
            SennheiserCommands.ON_HEAD_GET, SennheiserCommands.SMART_PAUSE_GET, SennheiserCommands.AUTO_ANSWER_GET, SennheiserCommands.COMFORT_CALL_GET,
            SennheiserCommands.SIDETONE_GET, SennheiserCommands.FIRMWARE_GET, SennheiserCommands.MODEL_ID_GET, SennheiserCommands.EQ_CONFIG_GET,
            SennheiserCommands.EQ_SUBMODE_GET, SennheiserCommands.WEAR_STATE_GET,
        )) allowed(sen(c))
        allowed(sen(SennheiserCommands.AUTO_OFF_GET, 0))
        allowed(sen(SennheiserCommands.EQ_ALL_GET, 0))
        allowed(sen(SennheiserCommands.ANC_SET, 1))
        allowed(sen(SennheiserCommands.ANC_LEVEL_SET, 30))
        allowed(sen(SennheiserCommands.TH_SET, 0))
        allowed(sen(SennheiserCommands.TH_LEVEL_SET, 50))
        allowed(sen(SennheiserCommands.ANC_MODE_SET, 1, 2))
        allowed(sen(SennheiserCommands.ANC_MODE_SET, 1, 1, 2, 0, 3, 0))
        allowed(sen(SennheiserCommands.AUTO_OFF_SET, 0, 0x07, 0x08))
        allowed(sen(SennheiserCommands.SIDETONE_SET, 2))
        allowed(sen(SennheiserCommands.EQ_BAND_SET, 4, 0x0A))
        allowed(sen(SennheiserCommands.EQ_BAND_SET, 0, 0xC4)) // -6.0 dB
        allowed(sen(SennheiserCommands.EQ_SUBMODE_SET, 1))
        for (f in SennheiserFeature.NOTIFY) allowed(sen(SennheiserCommands.REGISTER_NOTIFICATION, f))
    }

    @Test fun `writes with payloads out of range are refused`() {
        refused(sen(SennheiserCommands.ANC_SET, 2), m4)
        refused(sen(SennheiserCommands.ANC_SET), m4)
        refused(sen(SennheiserCommands.ANC_LEVEL_SET, 101), m4)
        refused(sen(SennheiserCommands.ANC_MODE_SET, 1, 3), m4) // anti-wind has 0..2
        refused(sen(SennheiserCommands.ANC_MODE_SET, 4, 0), m4) // no mode 4
        refused(sen(SennheiserCommands.ANC_MODE_SET, 1, 1, 1, 0), m4) // the same mode twice
        refused(sen(SennheiserCommands.ANC_MODE_SET, 1), m4)
        refused(sen(SennheiserCommands.AUTO_OFF_SET, 1, 0x07, 0x08), m4) // the "limbo" timer
        refused(sen(SennheiserCommands.AUTO_OFF_SET, 0, 0x00, 0x05), m4) // 5 s
        refused(sen(SennheiserCommands.SIDETONE_SET, 5), m4)
        refused(sen(SennheiserCommands.EQ_BAND_SET, 4, 0x7F), m4)
        refused(sen(SennheiserCommands.EQ_SUBMODE_SET, 2), m4)
        refused(sen(SennheiserCommands.REGISTER_NOTIFICATION, SennheiserFeature.DEVICE_MANAGEMENT), m4)
        refused(sen(SennheiserCommands.BATTERY_GET, 1), m4)
    }

    @Test fun `generation-1 EQ firmware gets no EQ command`() {
        val gen1 = m4 + (SennheiserFeature.USER_EQ to 2)
        refused(sen(SennheiserCommands.EQ_BAND_SET, 0, 0), gen1)
        refused(sen(SennheiserCommands.EQ_ALL_GET, 0), gen1)
        refused(sen(SennheiserCommands.EQ_CONFIG_GET), gen1)
        refused(sen(SennheiserCommands.EQ_BAND_GET, 0), gen1) // gen-1 "set parameter group"
    }

    @Test fun `the guard object reads the reported features at send time`() {
        var reported: Map<Int, Int>? = null
        val guard = SennheiserWriteGuard { reported }
        assertNotNull(guard.refusal(sen(SennheiserCommands.BATTERY_GET)))
        reported = m4
        assertNull(guard.refusal(sen(SennheiserCommands.BATTERY_GET)))
        assertEquals(true, guard.refusal(sen(0x0040))?.startsWith("blocked"))
    }
}
