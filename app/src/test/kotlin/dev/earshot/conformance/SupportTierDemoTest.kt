package dev.earshot.conformance

import dev.earshot.bt.BtLog
import dev.earshot.bt.JblLog
import dev.earshot.bt.SennheiserLog
import dev.earshot.bt.SonyLog
import dev.earshot.core.ConnectionState
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneState
import dev.earshot.core.SupportTier
import dev.earshot.service.GenericHeadphoneRepository
import dev.earshot.service.ProtocolLog
import dev.earshot.virtual.DemoDevice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Every demo device, connected the way its repository connects it ([rigFor]), shows the tier its
 * model has: the support tag a reviewer sees in demo mode is the one an owner would see. Exhaustive
 * on purpose, like [rigFor]: a new demo device doesn't compile until it says its tier here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class SupportTierDemoTest(private val device: DemoDevice) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun devices(): List<DemoDevice> = DemoDevice.entries.toList()

        /** The tier each demo device's model has. Tested = Earshot has run on that model's real hardware. */
        fun expected(d: DemoDevice): SupportTier = when (d) {
            // The user's own QC Ultra 2 and Sleep A30.
            DemoDevice.QC_ULTRA, DemoDevice.SOUNDCORE_SLEEP_A30 -> SupportTier.TESTED

            // Bose Connect era: battery and product info only. AirPods: each bud and the case from the adverts.
            DemoDevice.QC35_II, DemoDevice.AIRPODS_PRO -> SupportTier.BATTERY_ONLY

            DemoDevice.QC_ULTRA_EARBUDS_2, DemoDevice.QC_EARBUDS_2, DemoDevice.QC_EARBUDS, DemoDevice.QC45,
            DemoDevice.NC700, DemoDevice.ULTRA_OPEN, DemoDevice.CRUSHER, DemoDevice.CRUSHER_SECURE,
            DemoDevice.SOUNDCORE_LIBERTY_4_NC, DemoDevice.SOUNDCORE_SPACE_ONE_PRO, DemoDevice.METHOD_360,
            DemoDevice.METHOD_540, DemoDevice.SENNHEISER_MOMENTUM_4, DemoDevice.SONY_WH1000XM5,
            DemoDevice.JBL_WAVE_BUDS_2,
            -> SupportTier.EXPERIMENTAL
        }
    }

    @Before
    fun quiet() {
        BtLog.sink = { _, _, _ -> }
        SennheiserLog.sink = { _, _, _ -> }
        SonyLog.sink = { _, _, _ -> }
        JblLog.sink = { _, _, _ -> }
        ProtocolLog.sink = { _, _, _ -> }
    }

    @Test
    fun `the connected demo device shows its model's tier`() {
        val headset = device.demoHeadset()
        val state = if (headset != null) genericDemoState(headset.name) else connectedThroughRig()
        assertEquals(expected(device), SupportTier.of(state), "$device")
    }

    /** What GenericHeadphoneRepository.showDemo publishes: the audio link up, capabilities from the name. */
    private fun genericDemoState(name: String) = HeadphoneState(
        connection = ConnectionState(ConnectionStatus.CONNECTED, name, GenericHeadphoneRepository.DEMO_ADDRESS, audioLinked = true),
        capabilities = GenericHeadphoneRepository.capabilitiesFor(name),
    )

    private fun connectedThroughRig(): HeadphoneState {
        val scope = TestScope()
        val rig = assertNotNull(rigFor(device, scope), "$device has neither a demo headset nor a rig")
        var state: HeadphoneState? = null
        scope.runTest {
            rig.connect()
            state = rig.settle()
        }
        return assertNotNull(state, "$device: no state after connect")
    }
}
