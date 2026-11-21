package dev.earshot.ui.sound

import dev.earshot.core.fake.FakeAudioTuningRepository
import dev.earshot.dsp.Ear
import dev.earshot.dsp.GraphicEq
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Pro EQ's left/right mode: edits land on one ear and the curve follows the ear being edited. */
@OptIn(ExperimentalCoroutinesApi::class)
class PerEarEqViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private fun SoundViewModel.collectEverything(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch { bands.collect {} }
        scope.launch { curve.collect {} }
        scope.launch { editingEar.collect {} }
        scope.launch { recommendedPreampDb.collect {} }
    }

    @Test
    fun `editing one ear leaves the other alone`() = runTest {
        val tuning = FakeAudioTuningRepository()
        val vm = SoundViewModel(RecordingHeadphones(), tuning, UiMessenger(), curveDispatcher = StandardTestDispatcher(testScheduler))
        val store = storeHolding(vm)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.collectEverything(this) }
        val shared = tuning.state.value.settings.bands

        vm.selectEar(Ear.LEFT)
        advanceUntilIdle()
        assertEquals(Ear.LEFT, vm.editingEar.value)
        assertTrue(tuning.state.value.settings.perEarEnabled)
        // Switching on seeds both ears with what both ears were playing.
        assertEquals(shared, tuning.state.value.settings.leftBands)
        assertEquals(shared, tuning.state.value.settings.rightBands)

        val left = GraphicEq.bandsFromGains(listOf(6.0, 5.0, 3.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
        vm.setBands(left)
        advanceUntilIdle()
        assertEquals(left, tuning.state.value.settings.leftBands)
        assertEquals(shared, tuning.state.value.settings.rightBands, "the right ear keeps its curve")
        assertEquals(left, vm.bands.value)
        assertEquals(left, vm.curve.value.bands)

        vm.selectEar(Ear.RIGHT)
        advanceUntilIdle()
        assertEquals(Ear.RIGHT, vm.editingEar.value)
        assertEquals(shared, vm.bands.value, "the right ear still shows its own curve")
        assertEquals(shared, vm.curve.value.bands)

        store.clear()
    }

    @Test
    fun `choosing both links the ears on the curve being edited`() = runTest {
        val tuning = FakeAudioTuningRepository()
        val vm = SoundViewModel(RecordingHeadphones(), tuning, UiMessenger(), curveDispatcher = StandardTestDispatcher(testScheduler))
        val store = storeHolding(vm)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.collectEverything(this) }

        vm.selectEar(Ear.RIGHT)
        advanceUntilIdle()
        val right = GraphicEq.bandsFromGains(listOf(-2.0, -1.0, 0.0, 1.0, 2.0, 3.0, 3.0, 2.0, 0.0, -1.0))
        vm.setBands(right)
        advanceUntilIdle()
        assertEquals(right, tuning.state.value.settings.rightBands)

        vm.selectEar(null)
        advanceUntilIdle()
        val settings = tuning.state.value.settings
        assertFalse(settings.perEarEnabled)
        assertNull(vm.editingEar.value)
        assertEquals(right, settings.bands, "both ears now play the right ear's curve")
        assertEquals(right, vm.bands.value)

        store.clear()
    }

    @Test
    fun `the recommended preamp covers the louder ear`() = runTest {
        val tuning = FakeAudioTuningRepository()
        val vm = SoundViewModel(RecordingHeadphones(), tuning, UiMessenger(), curveDispatcher = StandardTestDispatcher(testScheduler))
        val store = storeHolding(vm)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.collectEverything(this) }

        vm.selectEar(Ear.LEFT)
        advanceUntilIdle()
        val quiet = GraphicEq.bandsFromGains(List(GraphicEq.BAND_COUNT) { 0.0 })
        val loud = GraphicEq.bandsFromGains(listOf(9.0, 8.0, 6.0, 3.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
        vm.setBands(quiet)
        advanceUntilIdle()
        vm.selectEar(Ear.RIGHT)
        advanceUntilIdle()
        vm.setBands(loud)
        advanceUntilIdle()

        val expected = dev.earshot.dsp.Headroom.recommendedPreampDb(loud)
        assertTrue(expected < -5.0, "the loud curve needs a real cut")
        assertEquals(expected, vm.recommendedPreampDb.value, 1e-9)

        store.clear()
    }
}
