package dev.earshot.extras.automation

import dev.earshot.core.AppPreferences
import dev.earshot.core.AutomationAction
import dev.earshot.core.AutomationRepository
import dev.earshot.core.AutomationRule
import dev.earshot.core.ConnectionState
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneState
import dev.earshot.core.ListeningMode
import dev.earshot.core.ModeMeaning
import dev.earshot.core.ModeRef
import dev.earshot.core.ModeResolver
import dev.earshot.core.Trigger
import dev.earshot.core.fake.FakeAutomationRepository
import dev.earshot.core.fake.FakeHeadphoneRepository
import dev.earshot.service.SoundcoreStateMapping
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ModeRefMigrationTest {

    private val bose = "68:F2:1F:12:34:56"
    private val a30 = "E8:EE:CC:00:00:30"

    private val boseModes = listOf(
        ListeningMode(0, "Quiet", isPreset = true, isEditable = false, isConfigured = true),
        ListeningMode(1, "Aware", isPreset = true, isEditable = false, isConfigured = true),
        ListeningMode(2, "Immersion", isPreset = true, isEditable = false, isConfigured = true),
        ListeningMode(3, "Cinema", isPreset = true, isEditable = false, isConfigured = true),
        ListeningMode(4, "Home", isPreset = false, isEditable = true, isConfigured = true),
        ListeningMode(5, "Gym", isPreset = false, isEditable = true, isConfigured = true),
    ) + (6..10).map { ListeningMode(it, "", isPreset = false, isEditable = true, isConfigured = false) }

    private fun rule(id: String, vararg actions: AutomationAction) = AutomationRule(id, id, true, Trigger.OnConnect, actions.toList())

    private fun modeOf(rule: AutomationRule): AutomationAction.SetMode = rule.actions.filterIsInstance<AutomationAction.SetMode>().single()

    private suspend fun store(vararg rules: AutomationRule, tile: List<Int> = emptyList()) = FakeAutomationRepository().apply {
        rules.forEach { upsertRule(it) }
        setPreferences(AppPreferences(tileCycleModes = tile, lowBatteryThreshold = null))
    }

    @Test fun `bound on a QC Ultra, every rule hits the same slot there as in 0_75`() = runTest {
        val automation = store(
            rule("quiet", AutomationAction.SetMode(0)),
            rule("immersion", AutomationAction.SetMode(2)),
            rule("gym", AutomationAction.SetMode(5), AutomationAction.SetEqPreset("bass")),
            rule("empty", AutomationAction.SetMode(9)),
            rule("eq", AutomationAction.SetEqPreset(null)),
            tile = listOf(0, 2, 9),
        )
        ModeRefMigration(FakeHeadphoneRepository(), automation, MutableStateFlow(false)).migrate(boseModes, bose)
        val rules = automation.rules.value.associateBy { it.id }

        assertEquals(ModeRef(ModeMeaning.QUIET, "Quiet", bose, 0, legacySlot = 0), modeOf(rules.getValue("quiet")).mode)
        assertEquals(ModeRef(ModeMeaning.CUSTOM, "Immersion", bose, 2, legacySlot = 2), modeOf(rules.getValue("immersion")).mode)
        assertEquals(ModeRef(ModeMeaning.CUSTOM, "Gym", bose, 5, legacySlot = 5), modeOf(rules.getValue("gym")).mode)
        // The other actions and the 0.75 slot (for a downgrade) are untouched.
        assertEquals(AutomationAction.SetEqPreset("bass"), rules.getValue("gym").actions[1])
        listOf("quiet" to 0, "immersion" to 2, "gym" to 5).forEach { (id, slot) ->
            assertEquals(slot, modeOf(rules.getValue(id)).modeIndex)
            assertEquals(slot, ModeResolver.slotFor(modeOf(rules.getValue(id)).mode!!, boseModes, bose), id)
        }
        // A slot this headset doesn't have stays unbound and runs as 0.75 did.
        assertNull(modeOf(rules.getValue("empty")).mode)
        assertEquals(rule("eq", AutomationAction.SetEqPreset(null)), rules.getValue("eq"))
        // The rules keep their order.
        assertEquals(listOf("quiet", "immersion", "gym", "empty", "eq"), automation.rules.value.map { it.id })

        val prefs = automation.preferences.value
        assertTrue(prefs.modeRefsMigrated)
        assertEquals(
            listOf(
                ModeRef(ModeMeaning.QUIET, "Quiet", bose, 0, legacySlot = 0),
                ModeRef(ModeMeaning.CUSTOM, "Immersion", bose, 2, legacySlot = 2),
                ModeRef(ModeMeaning.CUSTOM, legacySlot = 9),
            ),
            prefs.tileCycle,
        )
        assertEquals(listOf(0, 2, 9), prefs.legacyTileSlots)
        // The rest of the preferences survive the rewrite.
        assertNull(prefs.lowBatteryThreshold)
    }

    @Test fun `bound first on a Sleep A30, a rule still does on the Bose what 0_75 did`() = runTest {
        val automation = store(rule("aware", AutomationAction.SetMode(1)), rule("immersion", AutomationAction.SetMode(2)), tile = listOf(1))
        val migration = ModeRefMigration(FakeHeadphoneRepository(), automation, MutableStateFlow(false))

        migration.migrate(SoundcoreStateMapping.MODES, a30)
        val aware = modeOf(automation.rules.value.first { it.id == "aware" }).mode!!
        assertEquals(ModeRef(ModeMeaning.OFF, "Off", a30, 1, legacySlot = 1), aware)
        // The A30 has no slot 2: that rule waits for a headset that has one.
        assertNull(modeOf(automation.rules.value.first { it.id == "immersion" }).mode)

        migration.migrate(boseModes, bose)
        // Off doesn't exist on the Bose, so the 0.75 slot is used: Aware, as before the upgrade.
        assertEquals(1, ModeResolver.slotFor(aware, boseModes, bose))
        // Already bound rules are not rebound to the second headset.
        assertEquals(aware, modeOf(automation.rules.value.first { it.id == "aware" }).mode)
        assertEquals(ModeRef(ModeMeaning.CUSTOM, "Immersion", bose, 2, legacySlot = 2), modeOf(automation.rules.value.first { it.id == "immersion" }).mode)
        // The tile was migrated once, on the A30.
        assertEquals(listOf(ModeRef(ModeMeaning.OFF, "Off", a30, 1, legacySlot = 1)), automation.preferences.value.tileCycle)
    }

    @Test fun `a tile the user already chose in 0_8 is left alone`() {
        val chosen = AppPreferences(tileCycleModes = listOf(4)).withTileCycle(listOf(ModeRef(ModeMeaning.AWARE)))
        assertEquals(chosen, ModeRefMigration.migrateTile(chosen, boseModes, bose))
        // An empty 0.75 list just marks the tile migrated.
        val empty = ModeRefMigration.migrateTile(AppPreferences(), boseModes, bose)
        assertTrue(empty.modeRefsMigrated)
        assertTrue(empty.tileCycle.isEmpty())
    }

    @Test fun `it waits for a connected headset with modes and never runs in demo mode`() = runTest {
        val automation = store(rule("quiet", AutomationAction.SetMode(0)), tile = listOf(0))
        val headset = FakeHeadphoneRepository(
            commandDelayMillis = 0,
            startConnected = true,
            connectedTemplate = { HeadphoneState(connection = ConnectionState(ConnectionStatus.CONNECTED, "QC", bose), modes = boseModes) },
        )
        val demo = MutableStateFlow(true)
        ModeRefMigration(headset, automation, demo, settleMillis = 1_000).start(backgroundScope)

        advanceTimeBy(10_000); runCurrent()
        assertNull(modeOf(automation.rules.value.single()).mode)
        assertFalse(automation.preferences.value.modeRefsMigrated)

        demo.value = false
        runCurrent()
        advanceTimeBy(500); runCurrent()
        // Still settling.
        assertNull(modeOf(automation.rules.value.single()).mode)
        advanceTimeBy(600); runCurrent()
        assertEquals(ModeMeaning.QUIET, modeOf(automation.rules.value.single()).mode?.meaning)
        assertTrue(automation.preferences.value.modeRefsMigrated)
    }

    @Test fun `a store error is logged and the next time the headset shows up it tries again`() = runTest {
        val base = store(rule("quiet", AutomationAction.SetMode(0)))
        val failing = object : AutomationRepository by base {
            var calls = 0
            override suspend fun updateRules(transform: (List<AutomationRule>) -> List<AutomationRule>) {
                calls++
                throw IOException("disk full")
            }
        }
        val connected = { HeadphoneState(connection = ConnectionState(ConnectionStatus.CONNECTED, "QC", bose), modes = boseModes) }
        val headset = FakeHeadphoneRepository(commandDelayMillis = 0, startConnected = true, connectedTemplate = connected)
        val errors = mutableListOf<Throwable>()
        // Demo mode on and off again stands in for a reconnect (the fake's connect runs on real time).
        val demo = MutableStateFlow(false)
        val job = ModeRefMigration(headset, failing, demo, settleMillis = 1_000, onError = { errors += it }).start(backgroundScope)

        advanceTimeBy(2_000); runCurrent()
        assertEquals(listOf("disk full"), errors.map { it.message })
        assertTrue(job.isActive, "still collecting after the failure")
        demo.value = true; runCurrent()
        demo.value = false; advanceTimeBy(2_000); runCurrent()
        assertEquals(2, failing.calls)
        assertTrue(job.isActive)
    }

    @Test fun `no rules, no rewrite`() = runTest {
        val empty = store()
        val automation = object : AutomationRepository by empty {
            var rewrites = 0
            override suspend fun updateRules(transform: (List<AutomationRule>) -> List<AutomationRule>) { rewrites++ }
        }
        ModeRefMigration(FakeHeadphoneRepository(), automation, MutableStateFlow(false)).migrate(boseModes, bose)
        assertEquals(0, automation.rewrites)
    }

    @Test fun `nothing happens without a connected headset that has modes`() = runTest {
        val automation = store(rule("quiet", AutomationAction.SetMode(0)), tile = listOf(0))
        val disconnected = FakeHeadphoneRepository(commandDelayMillis = 0)
        ModeRefMigration(disconnected, automation, MutableStateFlow(false), settleMillis = 1_000).start(backgroundScope)
        advanceTimeBy(10_000); runCurrent()
        assertNull(modeOf(automation.rules.value.single()).mode)
        assertFalse(automation.preferences.value.modeRefsMigrated)
    }
}
