package dev.earshot.shortcut

import dev.earshot.core.ButtonAction
import dev.earshot.core.ButtonAssignment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The per-headset detection memory, the secondary-button choices and their stored format. */
class DetectedShortcutTest {
    private val bose = "68:F2:1F:12:34:56"

    @Test
    fun `a detection is kept per headset, with the user's name for the control`() {
        val s = ShortcutSettings().withDetected(bose.lowercase(), 1_000)
            .withDetectedName(bose, PhysicalControl.POWER_BUTTON, PressStyle.SINGLE_PRESS, "ignored unless Other")
        val d = s.detectedFor(bose)!!
        assertEquals(bose, d.address)
        assertEquals(PhysicalControl.POWER_BUTTON, d.control)
        assertEquals(PressStyle.SINGLE_PRESS, d.press)
        assertNull(d.customName)
        // Detected again later: the name stays, the time moves.
        val again = s.withDetected(bose, 9_000).detectedFor(bose)!!
        assertEquals(9_000, again.at)
        assertEquals(PhysicalControl.POWER_BUTTON, again.control)
        assertNull(s.detectedFor("AA:BB:CC:DD:EE:FF"))
        // Naming needs a detection first.
        assertEquals(ShortcutSettings(), ShortcutSettings().withDetectedName(bose, PhysicalControl.OTHER, null, "x"))
        val other = s.withDetectedName(bose, PhysicalControl.OTHER, PressStyle.LONG_PRESS, "  Left cup tap ").detectedFor(bose)!!
        assertEquals("Left cup tap", other.customName)
    }

    @Test
    fun `detections and button choices survive the round trip, and older stores read as empty`() {
        val s = ShortcutSettings().withDetected(bose, 5).withDetectedName(bose, PhysicalControl.VOLUME_STRIP, PressStyle.TOUCH_AND_HOLD, null)
            .withButtonChoice(bose, 0x04, ButtonAction.SPATIAL_AUDIO.storedId)
        val back = ShortcutCodec.decodeSettings(
            ShortcutCodec.encodeAction(s.defaultAction), ShortcutCodec.encodeOverrides(s.overrides), true, false,
            ShortcutExtrasCodec.encodeDetected(s.detected), ShortcutExtrasCodec.encodeButtonChoices(s.buttonChoices),
        )
        assertEquals(s.detected, back.detected)
        assertEquals(s.buttonChoices, back.buttonChoices)
        // Keys an older build never wrote, or corrupt ones.
        val old = ShortcutCodec.decodeSettings(null, null, null, null)
        assertTrue(old.detected.isEmpty() && old.buttonChoices.isEmpty())
        assertTrue(ShortcutCodec.decodeSettings(null, null, null, null, "{not json", "[1,2]").detected.isEmpty())
        assertEquals(
            1,
            ShortcutExtrasCodec.decodeDetected("""[{"address":"$bose","at":1},{"address":"x","at":"bad"},{"address":"$bose","at":2}]""").size,
        )
    }

    @Test
    fun `a secondary button can share the Earshot action or do the headset's own modes it reports`() {
        val left = ButtonAssignment(0x04, 9, ButtonAction.SPATIAL_AUDIO, listOf(ButtonAction.VOICE_ASSISTANT, ButtonAction.DISABLED, ButtonAction.SPATIAL_AUDIO))
        assertEquals(listOf(ButtonAction.VOICE_ASSISTANT, ButtonAction.SPATIAL_AUDIO, ButtonAction.DISABLED), SecondaryButtonPolicy.options(left))
        val choice = ButtonChoice(bose, 0x04, ButtonAction.VOICE_ASSISTANT.storedId)
        assertEquals(ButtonAction.VOICE_ASSISTANT, SecondaryButtonPolicy.writeFor(choice, left))
        assertNull(SecondaryButtonPolicy.writeFor(null, left), "nothing written for a button never set")
        assertNull(SecondaryButtonPolicy.writeFor(choice, left.copy(action = ButtonAction.VOICE_ASSISTANT)), "already there")
        assertNull(SecondaryButtonPolicy.writeFor(ButtonChoice(bose, 0x04, ButtonAction.SPOTIFY_TAP.storedId), left), "not an offered mode")
        assertNull(SecondaryButtonPolicy.writeFor(choice, left.copy(supportedActions = listOf(ButtonAction.DISABLED))), "not reported")
        assertEquals(ButtonAction.SPATIAL_AUDIO, SecondaryButtonPolicy.displayed(null, left))
        assertEquals(SecondaryButtonPolicy.Side.LEFT, SecondaryButtonPolicy.side(0x04))
        assertEquals(SecondaryButtonPolicy.Side.RIGHT, SecondaryButtonPolicy.side(0x03))
        assertNull(SecondaryButtonPolicy.side(0x80))
    }

    @Test
    fun `two buttons on the assistant run the same action`() {
        val right = ButtonAssignment(0x03, 9, ButtonAction.VOICE_ASSISTANT, emptyList())
        val left = right.copy(buttonId = 0x04)
        assertTrue(SecondaryButtonPolicy.sharesAssistant(listOf(right, left)))
        assertFalse(SecondaryButtonPolicy.sharesAssistant(listOf(right, left.copy(action = ButtonAction.DISABLED))))
        val s = ShortcutSettings().withButtonChoice(bose, 0x04, 1).withButtonChoice(bose.lowercase(), 0x04, null)
        assertTrue(s.buttonChoices.isEmpty())
    }
}
