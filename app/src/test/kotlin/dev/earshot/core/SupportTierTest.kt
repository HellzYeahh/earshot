package dev.earshot.core

import dev.earshot.core.fake.FakeHeadphoneRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tier rule on hand-built states: one per tier, and the cases that must show no tier at all. */
class SupportTierTest {

    private val connected = ConnectionState(ConnectionStatus.CONNECTED, "Headset", "AA:BB:CC:DD:EE:01")
    private val info = DeviceInfo(modelName = "Some Model")

    private fun state(caps: HeadphoneCapabilities, info: DeviceInfo? = this.info, connection: ConnectionState = connected) =
        HeadphoneState(connection = connection, info = info, capabilities = caps)

    /** What GenericHeadphoneRepository publishes: none of the headset's own settings, no info. */
    private fun generic(batteryOnly: Boolean) = HeadphoneCapabilities(
        spatialAudio = false, ancToggle = false, liveNoiseControl = false, noiseCancelling = false, windBlock = false,
        editableModes = false, favoriteModes = false, listeningModes = false, ownSettings = false, batteryOnly = batteryOnly,
    )

    @Test fun `a driver whose model Earshot has run on is Tested`() {
        assertEquals(SupportTier.TESTED, SupportTier.of(state(HeadphoneCapabilities(tested = true))))
    }

    @Test fun `a driver that hasn't been tried for real is Experimental, even with a confirmed layout`() {
        assertEquals(SupportTier.EXPERIMENTAL, SupportTier.of(state(HeadphoneCapabilities(verified = false))))
        // bosectl confirmed the layout (QC45, edith): still not run with Earshot.
        assertEquals(SupportTier.EXPERIMENTAL, SupportTier.of(state(HeadphoneCapabilities(verified = true, tested = false))))
    }

    @Test fun `tested is never inherited from the defaults`() {
        assertFalse(HeadphoneCapabilities().tested)
        assertFalse(HeadphoneCapabilities().batteryOnly)
        assertEquals(SupportTier.EXPERIMENTAL, SupportTier.of(HeadphoneCapabilities()))
    }

    @Test fun `a driver that only reads the battery is Battery only, tested or not`() {
        val qc35 = HeadphoneCapabilities(listeningModes = false, noiseCancelling = false, verified = false, batteryOnly = true)
        assertEquals(SupportTier.BATTERY_ONLY, SupportTier.of(state(qc35)))
        assertEquals(SupportTier.BATTERY_ONLY, SupportTier.of(qc35.copy(tested = true)))
    }

    @Test fun `a headset without a driver is Battery only when its battery is read, else Any headset`() {
        // No info: a generic headset never gets any, and needs none for its tier.
        assertEquals(SupportTier.BATTERY_ONLY, SupportTier.of(state(generic(batteryOnly = true), info = null)))
        assertEquals(SupportTier.ANY_HEADSET, SupportTier.of(state(generic(batteryOnly = false), info = null)))
        // Whatever else the capabilities claim, no own settings means no driver.
        assertEquals(SupportTier.ANY_HEADSET, SupportTier.of(generic(batteryOnly = false).copy(tested = true)))
    }

    @Test fun `no tier while not connected`() {
        for (status in ConnectionStatus.entries - ConnectionStatus.CONNECTED) {
            val c = connected.copy(status = status)
            assertNull(SupportTier.of(state(HeadphoneCapabilities(tested = true), connection = c)), "$status")
            assertNull(SupportTier.of(state(generic(batteryOnly = true), info = null, connection = c)), "$status")
        }
        // Linked for audio only: the control link is down, so the capabilities may be stale.
        assertNull(SupportTier.of(state(HeadphoneCapabilities(tested = true), connection = connected.copy(status = ConnectionStatus.CONNECTING, audioLinked = true))))
    }

    @Test fun `no tier while a driver hasn't read the model yet`() {
        // Right after CONNECTED the capabilities are the QC Ultra 2 defaults: they say nothing about this headset.
        assertNull(SupportTier.of(state(HeadphoneCapabilities(), info = null)))
        assertNull(SupportTier.of(state(HeadphoneCapabilities(tested = true), info = null)))
        // Any info at all (even an empty one after a silent product read) means the driver has decided.
        assertEquals(SupportTier.EXPERIMENTAL, SupportTier.of(state(HeadphoneCapabilities(), info = DeviceInfo())))
    }

    @Test fun `only Experimental asks for reports`() {
        assertEquals(listOf(SupportTier.EXPERIMENTAL), SupportTier.entries.filter { it.wantsReports })
    }

    @Test fun `the fakes show the tiers the previews promise`() {
        assertEquals(SupportTier.TESTED, SupportTier.of(FakeHeadphoneRepository.connectedState()))
        // QC Ultra Earbuds (2nd Gen): bosectl's layout, not run with Earshot.
        assertEquals(SupportTier.EXPERIMENTAL, SupportTier.of(FakeHeadphoneRepository.connectedEarbudsState()))
        assertEquals(SupportTier.EXPERIMENTAL, SupportTier.of(FakeHeadphoneRepository.basicTierState()))
        assertTrue(FakeHeadphoneRepository.connectedState().capabilities.ownSettings)
    }
}
