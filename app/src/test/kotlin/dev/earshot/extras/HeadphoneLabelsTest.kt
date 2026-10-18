package dev.earshot.extras

import dev.earshot.core.BatteryDetail
import dev.earshot.core.BatteryLevel
import dev.earshot.core.ConnectionState
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.DeviceInfo
import dev.earshot.core.FormFactor
import dev.earshot.core.HeadphoneState
import dev.earshot.core.ListeningMode
import dev.earshot.core.fake.FakeHeadphoneRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeadphoneLabelsTest {

    @Test
    fun `LE prefix is dropped, other names are kept`() {
        assertEquals("Bose QC Ultra 2 Earbuds", HeadphoneLabels.stripLePrefix("LE-Bose QC Ultra 2 Earbuds"))
        assertEquals("Bose QC Ultra 2 Earbuds", HeadphoneLabels.stripLePrefix("le_Bose QC Ultra 2 Earbuds "))
        assertEquals("Leo's Bose", HeadphoneLabels.stripLePrefix("Leo's Bose"))
        assertEquals("Bose QC45", HeadphoneLabels.stripLePrefix("Bose QC45"))
    }

    @Test
    fun `display name prefers the custom name, then the Bluetooth name`() {
        assertEquals("Fargo", HeadphoneLabels.displayName(FakeHeadphoneRepository.connectedState()))
        assertEquals("Bose QC Ultra 2 Earbuds", HeadphoneLabels.displayName(FakeHeadphoneRepository.connectedEarbudsState()))
        val blankCustom = HeadphoneState(
            connection = ConnectionState(ConnectionStatus.CONNECTED, "LE-Bose Ultra Open Earbuds"),
            info = DeviceInfo(customName = "  "),
        )
        assertEquals("Bose Ultra Open Earbuds", HeadphoneLabels.displayName(blankCustom))
        assertNull(HeadphoneLabels.displayName(HeadphoneState()))
    }

    @Test
    fun `model line is family and generation, else the model name`() {
        assertEquals(
            "QuietComfort Ultra Earbuds · 2nd Gen",
            HeadphoneLabels.modelLine(DeviceInfo(modelName = "QuietComfort Ultra Earbuds (2nd Gen)", family = "QuietComfort Ultra Earbuds", generation = "2nd Gen")),
        )
        assertEquals("QuietComfort 45", HeadphoneLabels.modelLine(DeviceInfo(modelName = "QuietComfort 45 / QC SE", family = "QuietComfort 45")))
        assertEquals("QuietComfort 45 / QC SE", HeadphoneLabels.modelLine(DeviceInfo(modelName = "QuietComfort 45 / QC SE")))
        assertNull(HeadphoneLabels.modelLine(DeviceInfo()))
        assertNull(HeadphoneLabels.modelLine(null))
    }

    @Test
    fun `form factor comes from the capabilities while connected, the name otherwise`() {
        assertEquals(FormFactor.EARBUDS, HeadphoneLabels.formFactor(FakeHeadphoneRepository.connectedEarbudsState()))
        val offline = HeadphoneState(connection = ConnectionState(ConnectionStatus.DISCONNECTED, "LE-Bose QC Ultra Earbuds"))
        assertEquals(FormFactor.EARBUDS, HeadphoneLabels.formFactor(offline))
        assertEquals(FormFactor.OPEN_EARBUDS, HeadphoneLabels.formFactorFromName("Bose Ultra Open Earbuds"))
        assertEquals(FormFactor.HEADPHONES, HeadphoneLabels.formFactorFromName("Bose QC Ultra Headphones"))
        assertEquals(FormFactor.HEADPHONES, HeadphoneLabels.formFactorFromName(null))
    }

    @Test
    fun `alert watches the lower bud on earbuds and the headline on headphones`() {
        val buds = FakeHeadphoneRepository.connectedEarbudsState() // L 64, R 71
        assertEquals(64, HeadphoneLabels.alertPercent(buds))
        assertEquals(HeadphoneLabels.Bud.LEFT, HeadphoneLabels.lowerBud(buds))
        val rightLow = buds.copy(battery = BatteryDetail(left = BatteryLevel(50), right = BatteryLevel(12), case = BatteryLevel(90)), batteryPercent = 31)
        assertEquals(12, HeadphoneLabels.alertPercent(rightLow))
        assertEquals(HeadphoneLabels.Bud.RIGHT, HeadphoneLabels.lowerBud(rightLow))
        val tie = buds.copy(battery = BatteryDetail(left = BatteryLevel(40), right = BatteryLevel(40)))
        assertNull(HeadphoneLabels.lowerBud(tie))

        val headphones = FakeHeadphoneRepository.connectedState()
        assertEquals(72, HeadphoneLabels.alertPercent(headphones))
        assertNull(HeadphoneLabels.lowerBud(headphones))
        assertFalse(HeadphoneLabels.hasBudBatteries(headphones))
        assertTrue(HeadphoneLabels.hasBudBatteries(buds))
    }

    @Test
    fun `time left is the first bud to run out, or the headphones' estimate`() {
        assertEquals(4 * 60, HeadphoneLabels.minutesLeft(FakeHeadphoneRepository.connectedEarbudsState()))
        assertEquals(17 * 60, HeadphoneLabels.minutesLeft(FakeHeadphoneRepository.connectedState()))
        assertNull(HeadphoneLabels.minutesLeft(FakeHeadphoneRepository.basicTierState()))
    }

    @Test
    fun `short battery text`() {
        assertEquals("L 64% · R 71%", HeadphoneLabels.batteryShort(FakeHeadphoneRepository.connectedEarbudsState()))
        assertEquals("72%", HeadphoneLabels.batteryShort(FakeHeadphoneRepository.connectedState()))
        assertNull(HeadphoneLabels.batteryShort(HeadphoneState()))
        assertEquals("Case 58%", HeadphoneLabels.caseShort(BatteryLevel(58)))
        assertNull(HeadphoneLabels.caseShort(BatteryLevel(null)))
    }

    @Test
    fun `mode names never fall back to a placeholder`() {
        val state = HeadphoneState(
            modes = listOf(
                ListeningMode(0, "Stereo", isPreset = true, isEditable = false, isConfigured = true),
                ListeningMode(4, " ", isPreset = false, isEditable = true, isConfigured = true),
            ),
        )
        assertEquals("Stereo", HeadphoneLabels.modeName(state, 0))
        assertNull(HeadphoneLabels.modeName(state, 4))
        assertNull(HeadphoneLabels.modeName(state, 7))
    }
}
