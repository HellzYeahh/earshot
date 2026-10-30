package dev.earshot.ui.more

import android.Manifest
import dev.earshot.core.AutomationAction
import dev.earshot.core.AutomationRule
import dev.earshot.core.ModeMeaning
import dev.earshot.core.ModeRef
import dev.earshot.core.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The automation editor's draft: what survives a rotation or process death, and what Save
 * stores. A rotation used to reset the trigger and actions (kept in plain remember) while the
 * name stayed, and every Save of a new rule made a fresh id, so a double tap stored two rules.
 */
class AutomationDraftTest {

    private val bose = "68:F2:1F:38:DB:0F"
    private val quiet = AutomationAction.SetMode(0, ModeRef(ModeMeaning.QUIET, "Quiet", bose, 0))

    @Test fun `every trigger and action survives the saved state exactly`() {
        val triggers = listOf(
            Trigger.OnConnect,
            Trigger.WifiNetwork("Home 5G"),
            Trigger.ForegroundApp("com.example.player", "Player"),
            Trigger.Schedule(6, 30, setOf(1, 3, 5)),
        )
        val actions = listOf(
            AutomationAction.SetMode(2, ModeRef(ModeMeaning.CUSTOM, "Gym", bose, 2, legacySlot = 4)),
            AutomationAction.SetEqPreset("bass-boost"),
            AutomationAction.SetEqPreset(null),
            AutomationAction.SetNoise(cancellingLevel = 6, ancEnabled = true, windBlock = false, spatialAudioWire = 1),
        )
        triggers.forEach { trigger ->
            val draft = AutomationRule("id-1", "Gym", enabled = false, trigger = trigger, actions = actions)
            assertEquals(draft, AutomationDraft.decode(AutomationDraft.encode(draft)))
        }
    }

    @Test fun `a 0_75 slot and a meaning from a newer build come back unchanged`() {
        val draft = AutomationRule(
            "id-2", "Old", trigger = Trigger.OnConnect,
            actions = listOf(AutomationAction.SetMode(3), AutomationAction.SetMode(1, ModeRef(ModeMeaning.CUSTOM, unknownMeaning = "future"))),
        )
        assertEquals(draft, AutomationDraft.decode(AutomationDraft.encode(draft)))
    }

    @Test fun `unreadable saved text gives null so the editor starts again`() {
        assertNull(AutomationDraft.decode("not json"))
        assertNull(AutomationDraft.decode("{}"))
    }

    @Test fun `a new rule keeps its one id however often it is saved`() {
        val draft = AutomationDraft.new("fixed-id", quiet).copy(name = "  Gym  ", trigger = Trigger.Schedule(6, 30, setOf(1)))
        val first = AutomationDraft.toSave(draft, existing = null)
        val second = AutomationDraft.toSave(draft, existing = null)
        assertEquals("fixed-id", first.id)
        assertEquals(first, second)
        assertEquals("Gym", first.name)
        assertTrue(first.enabled)
        assertEquals(listOf<AutomationAction>(quiet), first.actions)
    }

    @Test fun `an edited rule keeps the stored id and on-off switch`() {
        val stored = AutomationRule("stored-id", "Commute", enabled = false, trigger = Trigger.OnConnect, actions = listOf(quiet))
        // The draft was taken while the rule was on; it was switched off in the list since.
        val draft = stored.copy(enabled = true, name = "Commute 2", trigger = Trigger.WifiNetwork("Office"))
        val saved = AutomationDraft.toSave(draft, stored)
        assertEquals("stored-id", saved.id)
        assertFalse(saved.enabled)
        assertEquals("Commute 2", saved.name)
        assertEquals(Trigger.WifiNetwork("Office"), saved.trigger)
    }

    @Test fun `save needs a name, an action and a complete trigger`() {
        val ok = AutomationDraft.new("id", quiet).copy(name = "Gym")
        assertTrue(AutomationDraft.canSave(ok))
        assertFalse(AutomationDraft.canSave(ok.copy(name = "   ")))
        assertFalse(AutomationDraft.canSave(ok.copy(actions = emptyList())))
        assertFalse(AutomationDraft.canSave(ok.copy(trigger = Trigger.WifiNetwork(" "))))
        assertFalse(AutomationDraft.canSave(ok.copy(trigger = Trigger.ForegroundApp("", ""))))
        assertFalse(AutomationDraft.canSave(ok.copy(trigger = Trigger.Schedule(8, 0, emptySet()))))
        assertTrue(AutomationDraft.canSave(ok.copy(trigger = Trigger.Schedule(8, 0, setOf(7)))))
    }

    @Test fun `the Wi-Fi trigger asks for precise and approximate location together`() {
        // Android 12+ drops a request for fine location alone without showing a dialog.
        assertEquals(
            setOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            WifiLocationAsk.PERMISSIONS.toSet(),
        )
    }

    @Test fun `only a refused dialog that Android no longer shows sends the user to settings`() {
        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
        // Granted: nothing left to do.
        assertFalse(WifiLocationAsk.settingsOnly(mapOf(fine to true, coarse to true), precise = true, permanentlyDenied = false))
        // Approximate only, Android would still ask again: the button asks again.
        assertFalse(WifiLocationAsk.settingsOnly(mapOf(fine to false, coarse to true), precise = false, permanentlyDenied = false))
        // Refused for good: only the app's settings page can grant it.
        assertTrue(WifiLocationAsk.settingsOnly(mapOf(fine to false, coarse to false), precise = false, permanentlyDenied = true))
        // No answer at all (the request was interrupted): ask again, not settings.
        assertFalse(WifiLocationAsk.settingsOnly(emptyMap(), precise = false, permanentlyDenied = true))
    }
}
