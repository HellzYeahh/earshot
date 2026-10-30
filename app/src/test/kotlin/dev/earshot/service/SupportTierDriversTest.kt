package dev.earshot.service

import dev.earshot.core.ConnectionState
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneState
import dev.earshot.core.SupportTier
import dev.earshot.protocol.qcultra2.BoseModel
import dev.earshot.protocol.soundcore.D1301State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where each driver's tier comes from (1.0 plan item 4): the facts the drivers set, read through
 * the same [SupportTier] rule the UI uses. The demo devices end to end: `SupportTierDemoTest`.
 */
class SupportTierDriversTest {

    private val connected = HeadphoneState(connection = ConnectionState(ConnectionStatus.CONNECTED, "Headset", "AA:BB:CC:DD:EE:01"))

    @Test fun `the Sleep A30 is Tested, without an Experimental note`() {
        val s = SoundcoreStateMapping.apply(connected, assertNotNull(D1301State.parse(ByteArray(D1301State.BODY_SIZE))), now = 0)
        assertTrue(s.capabilities.tested)
        assertTrue(s.capabilities.verified)
        assertNull(s.capabilities.supportNote)
        assertEquals(SupportTier.TESTED, SupportTier.of(s))
    }

    @Test fun `of the Bose catalogue only the QC Ultra Headphones 2nd Gen is Tested`() {
        assertEquals(listOf(BoseModel.QC_ULTRA_2), BoseModel.entries.filter { it.tested })
        assertEquals(SupportTier.TESTED, SupportTier.of(BoseModel.QC_ULTRA_2.toCapabilities()))
    }

    @Test fun `a Bose layout bosectl confirmed is still Experimental until Earshot runs on it`() {
        for (model in listOf(BoseModel.QC45, BoseModel.QC_HEADPHONES, BoseModel.QC_ULTRA_EARBUDS_2, BoseModel.QC_EARBUDS)) {
            val caps = model.toCapabilities()
            assertTrue(caps.verified, "$model: bosectl-verified")
            assertEquals(SupportTier.EXPERIMENTAL, SupportTier.of(caps), "$model")
        }
        assertEquals(SupportTier.EXPERIMENTAL, SupportTier.of(BoseModel.NC700.toCapabilities()), "basic tier")
    }

    @Test fun `an unknown Bose product on the QC Ultra 2 defaults is not Tested`() {
        val caps = ConnectionFacts(catalogued = false, unknownProductName = "Bose Foo Earbuds").capabilities(NoisePath.LIVE, modesAvailable = true)
        assertFalse(caps.tested)
        assertEquals(SupportTier.EXPERIMENTAL, SupportTier.of(caps))
    }

    @Test fun `the recognised-but-unsupported Bose models are Battery only`() {
        for (model in BoseModel.entries.filter { it.isUnsupported }) {
            assertEquals(SupportTier.BATTERY_ONLY, SupportTier.of(model.toCapabilities()), "$model")
        }
    }

    @Test fun `AirPods and Beats are Battery only, any other headset without a driver is Any headset`() {
        for (name in listOf("AirPods Pro", "AirPods Max", "Beats Studio Buds +", "Powerbeats Pro")) {
            assertEquals(SupportTier.BATTERY_ONLY, SupportTier.of(GenericHeadphoneRepository.capabilitiesFor(name)), name)
        }
        for (name in listOf("TWS-X15", "Galaxy Buds3 Pro", "Car stereo", null)) {
            assertEquals(SupportTier.ANY_HEADSET, SupportTier.of(GenericHeadphoneRepository.capabilitiesFor(name)), "$name")
        }
        // The generic state carries no info; its tier shows anyway.
        val airPods = connected.copy(capabilities = GenericHeadphoneRepository.capabilitiesFor("AirPods Pro"))
        assertEquals(SupportTier.BATTERY_ONLY, SupportTier.of(airPods))
    }
}
