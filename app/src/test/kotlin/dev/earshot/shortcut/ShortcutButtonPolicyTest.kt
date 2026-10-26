package dev.earshot.shortcut

import dev.earshot.core.ButtonAction
import dev.earshot.core.ButtonAssignment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShortcutButtonPolicyTest {
    private val immersive = ShortcutAction.HeadphoneButton(ButtonAction.SPATIAL_AUDIO.storedId)
    private val off = ShortcutAction.HeadphoneButton(ButtonAction.DISABLED.storedId)
    private val earshotChoices = listOf(
        ShortcutAction.OpenAssistant(),
        ShortcutAction.OpenAssistant("a.b/a.b.C", "Bixby"),
        ShortcutAction.CycleEq,
        ShortcutAction.VoiceMemo,
        ShortcutAction.LaunchApp("com.spotify.music", "Spotify"),
        ShortcutAction.LaunchApp(),
    )

    /** QC Ultra 2, firmware 10.12: Vpa, Disabled, Spotify Tap, Immersive Audio. */
    private val qcUltra2 = listOf(ButtonAction.VOICE_ASSISTANT, ButtonAction.DISABLED, ButtonAction.SPOTIFY_TAP, ButtonAction.SPATIAL_AUDIO)

    /** Every subset of the modes the policy cares about, plus one unrelated mode. */
    private val supportedSets: List<Set<ButtonAction>> = run {
        val modes = listOf(ButtonAction.VOICE_ASSISTANT, ButtonAction.SPATIAL_AUDIO, ButtonAction.DISABLED, ButtonAction.SPOTIFY_TAP)
        (0 until (1 shl modes.size)).map { mask -> modes.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet() }
    }

    @Test
    fun `every Earshot action needs voice assistant, only when the device offers it`() {
        for (choice in earshotChoices) for (supported in supportedSets) {
            val expected = ButtonAction.VOICE_ASSISTANT.takeIf { it in supported }
            assertEquals(expected, ShortcutButtonPolicy.desiredMode(choice, supported), "$choice with $supported")
        }
    }

    @Test
    fun `native choices need their own mode, only when the device offers it`() {
        for (supported in supportedSets) {
            assertEquals(ButtonAction.SPATIAL_AUDIO.takeIf { it in supported }, ShortcutButtonPolicy.desiredMode(immersive, supported), "immersive with $supported")
            assertEquals(ButtonAction.DISABLED.takeIf { it in supported }, ShortcutButtonPolicy.desiredMode(off, supported), "off with $supported")
        }
    }

    @Test
    fun `a stored native mode the remapper doesn't offer is never written`() {
        val spotify = ShortcutAction.HeadphoneButton(ButtonAction.SPOTIFY_TAP.storedId)
        val unknown = ShortcutAction.HeadphoneButton(99)
        for (supported in supportedSets + setOf(ButtonAction.entries.toSet())) {
            assertNull(ShortcutButtonPolicy.desiredMode(spotify, supported))
            assertNull(ShortcutButtonPolicy.desiredMode(unknown, supported))
        }
    }

    @Test
    fun `native options show only the modes the device reports, in a fixed order`() {
        assertEquals(listOf(ButtonAction.SPATIAL_AUDIO, ButtonAction.DISABLED), ShortcutButtonPolicy.nativeOptions(qcUltra2))
        assertEquals(listOf(ButtonAction.SPATIAL_AUDIO, ButtonAction.DISABLED), ShortcutButtonPolicy.nativeOptions(qcUltra2.reversed()))
        assertEquals(listOf(ButtonAction.DISABLED), ShortcutButtonPolicy.nativeOptions(listOf(ButtonAction.VOICE_ASSISTANT, ButtonAction.DISABLED)))
        assertEquals(listOf(ButtonAction.SPATIAL_AUDIO), ShortcutButtonPolicy.nativeOptions(listOf(ButtonAction.SPATIAL_AUDIO)))
        assertEquals(emptyList(), ShortcutButtonPolicy.nativeOptions(listOf(ButtonAction.VOICE_ASSISTANT, ButtonAction.SPOTIFY_TAP)))
        assertEquals(emptyList(), ShortcutButtonPolicy.nativeOptions(emptyList()))
        assertEquals(emptyList(), ShortcutButtonPolicy.nativeOptions(null))
    }

    @Test
    fun `a write is needed only when the current mode differs`() {
        fun button(current: ButtonAction) = ButtonAssignment(0x80, 9, current, qcUltra2)
        for (current in qcUltra2) {
            for (choice in earshotChoices) {
                val expected = ButtonAction.VOICE_ASSISTANT.takeIf { current != it }
                assertEquals(expected, ShortcutButtonPolicy.writeFor(choice, button(current)), "$choice on $current")
            }
            assertEquals(ButtonAction.SPATIAL_AUDIO.takeIf { current != it }, ShortcutButtonPolicy.writeFor(immersive, button(current)))
            assertEquals(ButtonAction.DISABLED.takeIf { current != it }, ShortcutButtonPolicy.writeFor(off, button(current)))
        }
        // No [1.9] (not a Bose headset, or not read yet): nothing to write.
        earshotChoices.forEach { assertNull(ShortcutButtonPolicy.writeFor(it, null)) }
        assertNull(ShortcutButtonPolicy.writeFor(immersive, null))
    }

    @Test
    fun `a frame without the supported mask keeps the last full list`() {
        val short = ButtonAssignment(0x80, 9, ButtonAction.VOICE_ASSISTANT, emptyList())
        assertEquals(qcUltra2, ShortcutButtonPolicy.withKnownSupport(short, qcUltra2).supportedActions)
        assertEquals(emptyList(), ShortcutButtonPolicy.withKnownSupport(short, null).supportedActions)
        val full = short.copy(supportedActions = listOf(ButtonAction.VOICE_ASSISTANT))
        assertEquals(full, ShortcutButtonPolicy.withKnownSupport(full, qcUltra2))
    }

    @Test
    fun `on connect only a choice the user made is applied`() {
        val address = "68:F2:1F:12:34:56"
        val spotifyTap = ButtonAssignment(0x80, 9, ButtonAction.SPOTIFY_TAP, qcUltra2)
        // Never opened the remapper, button on Spotify Tap: that mode is dead while Earshot holds the
        // connection (QC Ultra 2 test), so it gets what the default choice needs (Phone assistant → Vpa).
        assertEquals(ButtonAction.VOICE_ASSISTANT, ShortcutButtonPolicy.writeOnConnect(ShortcutSettings(), address, spotifyTap))
        // Never opened the remapper, button on Immersive Audio or Off: left as the Bose app set it.
        assertNull(ShortcutButtonPolicy.writeOnConnect(ShortcutSettings(), address, spotifyTap.copy(action = ButtonAction.SPATIAL_AUDIO)))
        assertNull(ShortcutButtonPolicy.writeOnConnect(ShortcutSettings(), address, spotifyTap.copy(action = ButtonAction.DISABLED)))
        // Picked for this headset (even Phone assistant), or a non-factory default: applied.
        assertEquals(
            ButtonAction.VOICE_ASSISTANT,
            ShortcutButtonPolicy.writeOnConnect(ShortcutSettings().withChoice(address, "QC", ShortcutAction.OpenAssistant()), address, spotifyTap),
        )
        assertEquals(
            ButtonAction.VOICE_ASSISTANT,
            ShortcutButtonPolicy.writeOnConnect(ShortcutSettings(defaultAction = ShortcutAction.CycleEq), address, spotifyTap),
        )
        assertEquals(
            ButtonAction.SPATIAL_AUDIO,
            ShortcutButtonPolicy.writeOnConnect(ShortcutSettings().withChoice(address.lowercase(), "QC", immersive), address, spotifyTap),
        )
        // Already right: nothing.
        val vpa = spotifyTap.copy(action = ButtonAction.VOICE_ASSISTANT)
        assertNull(ShortcutButtonPolicy.writeOnConnect(ShortcutSettings(defaultAction = ShortcutAction.CycleEq), address, vpa))
    }

    @Test
    fun `without a choice a native button mode is shown as it is`() {
        val address = "68:F2:1F:12:34:56"
        val onImmersive = ButtonAssignment(0x80, 9, ButtonAction.SPATIAL_AUDIO, qcUltra2)
        assertEquals(immersive, ShortcutButtonPolicy.displayedChoice(ShortcutSettings(), address, onImmersive))
        assertEquals(off, ShortcutButtonPolicy.displayedChoice(ShortcutSettings(), address, onImmersive.copy(action = ButtonAction.DISABLED)))
        // Vpa or Spotify Tap: the (default) choice.
        assertEquals(ShortcutAction.OpenAssistant(), ShortcutButtonPolicy.displayedChoice(ShortcutSettings(), address, onImmersive.copy(action = ButtonAction.SPOTIFY_TAP)))
        // A saved choice always wins.
        val saved = ShortcutSettings().withChoice(address, "QC", ShortcutAction.VoiceMemo)
        assertEquals(ShortcutAction.VoiceMemo, ShortcutButtonPolicy.displayedChoice(saved, address, onImmersive))
        assertEquals(ShortcutAction.OpenAssistant(), ShortcutButtonPolicy.displayedChoice(ShortcutSettings(), null, null))
    }
}
