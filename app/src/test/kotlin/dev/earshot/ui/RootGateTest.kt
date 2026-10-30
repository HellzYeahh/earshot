package dev.earshot.ui

import dev.earshot.core.ConnectionStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The root now reads only the connection status (through derivedStateOf), not the whole
 * headphone state; the onboarding gate must still open and close on exactly the same statuses.
 */
class RootGateTest {

    @Test
    fun `the onboarding shows without Bluetooth access or without a headset to talk to`() {
        val gated = ConnectionStatus.entries.filter { isOnboardingGate(hasBluetooth = true, status = it) }
        assertEquals(listOf(ConnectionStatus.NO_PERMISSION, ConnectionStatus.NO_PAIRED_DEVICE), gated)
    }

    @Test
    fun `without the Bluetooth permission every status shows the onboarding`() {
        assertEquals(ConnectionStatus.entries.toList(), ConnectionStatus.entries.filter { isOnboardingGate(hasBluetooth = false, status = it) })
    }
}
