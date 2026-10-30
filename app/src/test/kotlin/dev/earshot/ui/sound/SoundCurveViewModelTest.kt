package dev.earshot.ui.sound

import dev.earshot.core.fake.FakeAudioTuningRepository
import dev.earshot.dsp.GraphicEq
import dev.earshot.dsp.Headroom
import dev.earshot.ui.MainDispatcherRule
import dev.earshot.ui.RecordingHeadphones
import dev.earshot.ui.common.UiMessenger
import dev.earshot.ui.storeHolding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** The Pro EQ curve is worked out off the main thread and follows every band and preamp change. */
@OptIn(ExperimentalCoroutinesApi::class)
class SoundCurveViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun `the curve follows the bands and the preamp`() = runTest {
        val tuning = FakeAudioTuningRepository()
        val vm = SoundViewModel(RecordingHeadphones(), tuning, UiMessenger(), curveDispatcher = StandardTestDispatcher(testScheduler))
        val store = storeHolding(vm)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.curve.collect {} }

        // The first value exists before any background work has run: the tab's first frame has a curve.
        val start = tuning.state.value.settings
        assertEquals(EqCurveData(start.bands, start.preampDb), vm.curve.value)

        val edited = GraphicEq.bandsFromGains(listOf(6.0, 5.0, 3.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
        vm.setBands(edited)
        advanceUntilIdle()
        assertEquals(edited, vm.curve.value.bands)
        assertEquals(Headroom.recommendedPreampDb(edited), vm.curve.value.recommendedPreampDb)

        vm.setPreamp(-4.0)
        advanceUntilIdle()
        assertEquals(EqCurveData(edited, -4.0), vm.curve.value)

        store.clear()
    }
}
