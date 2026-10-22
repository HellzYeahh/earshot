package dev.earshot.ui

import dev.earshot.core.LabReading
import dev.earshot.core.RawFrame
import dev.earshot.protocol.bragi.BragiModel
import dev.earshot.protocol.qcultra2.FunctionNames
import dev.earshot.protocol.sennheiser.SennheiserFeature
import dev.earshot.service.lab.BmapLab
import dev.earshot.service.lab.BragiLab
import dev.earshot.service.lab.SennheiserLab
import dev.earshot.ui.tools.LabExport
import dev.earshot.ui.tools.LabUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Lab screen's pure parts: labels, the text export and how the selection follows the headset. */
class LabLogicTest {

    private val bose = BmapLab.profile()

    @Test fun `the Bose export keeps its 0_5 format under a stack line`() {
        val readings = listOf(
            LabReading(1, 7, null, listOf(RawFrame(1, 7, "STATUS", "f60a", false)), groupName = "Settings"),
            LabReading(1, 8, null, listOf(RawFrame(1, 8, "ERROR", "04", true, "FUNC_NOT_SUPPORTED")), groupName = "Settings"),
            LabReading(40, 0, null, emptyList()),
        )
        val text = LabExport.text(bose, readings).lines()
        assertEquals("# Earshot Lab · Bose BMAP", text[0])
        assertEquals("# Block 1 Settings", text[1])
        // Exactly the 0.5 line: "[b.f] name OPERATOR payload", the name from the BMAP catalogue.
        assertEquals("[1.7] ${FunctionNames.functionName(1, 7).orEmpty()} STATUS f60a", text[2])
        assertEquals("[1.8] ${FunctionNames.functionName(1, 8).orEmpty()} ERROR ERROR FUNC_NOT_SUPPORTED 04", text[3])
        assertEquals("# Block 40 ", text[4])
        assertEquals("Block 40 · ?", LabExport.heading(bose, 40, readings.drop(2)))
        assertEquals("Block 31 · AudioModes", LabExport.heading(bose, 31, emptyList()))
    }

    @Test fun `other stacks use their own address and group words`() {
        val sennheiser = SennheiserLab.profile(mapOf(SennheiserFeature.BATTERY to 2, SennheiserFeature.CORE to 4))
        val r = LabReading(3, (0x0495 shl 16) or 0x0603, "Battery", listOf(RawFrame(3, 0x0603, "RESPONSE", "50", false)), address = "0495/0603", groupName = "Battery and power")
        assertEquals("0495/0603", LabExport.address(r))
        assertEquals("Battery", LabExport.functionName(r))
        assertEquals("Feature 3 · Battery and power", LabExport.heading(sennheiser, 3, listOf(r)))
        assertTrue(LabExport.text(sennheiser, listOf(r)).contains("0495/0603 Battery RESPONSE 50"))

        val method = BragiLab.profile(BragiModel.SKULLCANDY_METHOD_360)
        assertEquals(listOf("device", "hearing"), method.groups.map { it.name })
        assertTrue(method.note!!.contains("spatial_audio") && method.note!!.contains("crusher"))
        assertEquals("Service 4 · hearing", LabExport.heading(method, 4, emptyList()))
    }

    @Test fun `the selection starts at the defaults, follows a growing profile, and keeps the user's picks`() {
        var s = LabUiState().withHeadset(connected = true, deviceName = "QC Ultra", profile = bose)
        assertEquals(setOf(1, 2, 31), s.selectedGroups)
        assertEquals(3 * 41, s.scanReadCount)

        // Sennheiser: discovery first, then the feature list; untouched, the selection grows with it.
        val early = SennheiserLab.profile(null)
        val late = SennheiserLab.profile(mapOf(SennheiserFeature.BATTERY to 2, SennheiserFeature.ANC to 1, SennheiserFeature.CORE to 4))
        s = s.withHeadset(true, "MOMENTUM 4", early)
        assertEquals(setOf(0), s.selectedGroups)
        s = s.withHeadset(true, "MOMENTUM 4", late)
        assertEquals(setOf(0, 3, 13), s.selectedGroups)

        // The user's picks survive a profile update and a reconnect of the same stack.
        s = s.copy(selectedGroups = setOf(13), selectionTouched = true)
        s = s.withHeadset(false, "MOMENTUM 4", null)
        assertEquals(null, s.profile)
        s = s.withHeadset(true, "MOMENTUM 4", late)
        assertEquals(setOf(13), s.selectedGroups)

        // Another stack starts from its own defaults.
        s = s.withHeadset(true, "QC Ultra", bose)
        assertEquals(setOf(1, 2, 31), s.selectedGroups)
        assertFalse(s.selectionTouched)
    }
}
