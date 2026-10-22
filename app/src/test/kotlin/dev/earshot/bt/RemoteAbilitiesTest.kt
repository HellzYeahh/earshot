package dev.earshot.bt

import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteAbilitiesTest {
    @Test
    fun `each action unlocks with the Android version that made it public`() {
        assertEquals(RemoteAbilities(canLink = true, canRename = false, canForget = false, canConnect = false), RemoteAbilities.forSdk(28, true))
        assertEquals(RemoteAbilities(canLink = true, canRename = false, canForget = false, canConnect = false), RemoteAbilities.forSdk(32, true))
        // setAlias returns an int from Android 13.
        assertEquals(RemoteAbilities(canLink = true, canRename = true, canForget = false, canConnect = false), RemoteAbilities.forSdk(33, true))
        // CompanionDeviceManager.removeBond: Android 16.
        assertEquals(RemoteAbilities(canLink = true, canRename = true, canForget = true, canConnect = false), RemoteAbilities.forSdk(36, true))
        // BluetoothDevice.connect()/disconnect() with a CDM association: Android 17.
        assertEquals(RemoteAbilities(canLink = true, canRename = true, canForget = true, canConnect = true), RemoteAbilities.forSdk(37, true))
    }

    @Test
    fun `nothing works on a phone without the companion-device feature`() {
        assertEquals(RemoteAbilities(canLink = false, canRename = false, canForget = false, canConnect = false), RemoteAbilities.forSdk(37, false))
    }
}
