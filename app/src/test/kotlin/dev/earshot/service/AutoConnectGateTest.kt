package dev.earshot.service

import dev.earshot.core.AppPreferences
import dev.earshot.core.AutomationRepository
import dev.earshot.core.AutomationRule
import dev.earshot.core.fake.FakeAutomationRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Audit R04: a link-up that cold-starts the process must follow the saved "Auto-connect" choice,
 * not the default that `AutomationRepository.preferences` shows until the store has been read.
 */
class AutoConnectGateTest {

    /**
     * Shaped like AutomationRepositoryImpl right after a cold start: the StateFlow still holds the
     * defaults while the store already holds the user's choice, and [updatePreferences] reads the
     * store (after [readDelayMs]) inside one transaction that writes only a changed value.
     */
    private class ColdStartAutomation(
        var stored: AppPreferences,
        private val readDelayMs: Long = 0,
        private val unreadable: Boolean = false,
    ) : AutomationRepository {
        override val rules = MutableStateFlow(emptyList<AutomationRule>())
        override val preferences = MutableStateFlow(AppPreferences())
        override val recentLog = MutableStateFlow(emptyList<String>())
        var writes = 0

        override suspend fun upsertRule(rule: AutomationRule) = Unit
        override suspend fun deleteRule(id: String) = Unit
        override suspend fun setPreferences(preferences: AppPreferences) {
            writes++
            stored = preferences
        }

        override suspend fun updatePreferences(transform: (AppPreferences) -> AppPreferences) {
            delay(readDelayMs)
            if (unreadable) throw IOException("store unreadable")
            val next = transform(stored)
            if (next != stored) setPreferences(next)
        }

        override fun hasUsageAccess() = false
        override fun openUsageAccessSettings() = Unit
    }

    @Test
    fun `auto-connect off is honoured before the StateFlow has caught up`() = runTest {
        val automation = ColdStartAutomation(AppPreferences(autoConnectOnBluetooth = false), readDelayMs = 300)
        assertTrue(automation.preferences.value.autoConnectOnBluetooth, "precondition: the cold-start default says on")

        assertFalse(autoConnectAllowed(automation))
        assertEquals(0, automation.writes, "reading the choice must not write the store")
    }

    @Test
    fun `auto-connect on stays on`() = runTest {
        val automation = ColdStartAutomation(AppPreferences(autoConnectOnBluetooth = true), readDelayMs = 300)
        assertTrue(autoConnectAllowed(automation))
        assertEquals(0, automation.writes)
    }

    @Test
    fun `the stored preferences come back whole`() = runTest {
        val saved = AppPreferences(lowBatteryThreshold = null, persistentNotification = false, autoConnectOnBluetooth = false)
        val automation = ColdStartAutomation(saved)
        assertEquals(saved, automation.storedPreferences())
        assertEquals(saved, automation.stored)
    }

    @Test
    fun `a store that does not answer in time counts as off`() = runTest {
        val automation = ColdStartAutomation(AppPreferences(autoConnectOnBluetooth = true), readDelayMs = 10_000)
        assertFalse(autoConnectAllowed(automation, timeoutMs = AUTO_CONNECT_READ_MS))
    }

    @Test
    fun `an unreadable store falls back to what the app runs on`() = runTest {
        // The repository's flows show the defaults for a store they can't read; so does this.
        val automation = ColdStartAutomation(AppPreferences(autoConnectOnBluetooth = false), unreadable = true)
        assertTrue(autoConnectAllowed(automation))
    }

    @Test
    fun `the in-memory fake is read without being changed`() = runTest {
        val fake = FakeAutomationRepository()
        fake.setPreferences(AppPreferences(autoConnectOnBluetooth = false))
        assertFalse(autoConnectAllowed(fake))
        assertFalse(fake.preferences.value.autoConnectOnBluetooth)
    }
}
