package dev.earshot.ui.onboarding

import dev.earshot.core.HeadphoneCommandException
import dev.earshot.ui.MainDispatcherRule
import dev.earshot.ui.RecordingHeadphones
import dev.earshot.ui.collectMessages
import dev.earshot.ui.common.UiMessage
import dev.earshot.ui.common.UiMessenger
import dev.earshot.ui.storeHolding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The onboarding's commands run outside the screen's composition (audit R03). */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun `a pick reaches the repository and its failure becomes a message`() = runTest {
        val messenger = UiMessenger()
        val messages = collectMessages(messenger)
        val picked = RecordingHeadphones()
        OnboardingViewModel(picked, enableDemo = {}, messenger = messenger).chooseDevice("68:F2:1F:12:34:56", "Bose QC Ultra Headphones")
        advanceUntilIdle()
        assertEquals(listOf("choose 68:F2:1F:12:34:56"), picked.calls)

        val refused = RecordingHeadphones(onChoose = { _, _ -> throw HeadphoneCommandException("Bluetooth is off") })
        OnboardingViewModel(refused, enableDemo = {}, messenger = messenger).chooseDevice("68:F2:1F:12:34:56", null)
        advanceUntilIdle()
        assertEquals(listOf<UiMessage>(UiMessage.Text("Bluetooth is off")), messages)
    }

    @Test
    fun `a pick cut short by the Activity going away shows no cancellation snackbar`() = runTest {
        val messenger = UiMessenger()
        val messages = collectMessages(messenger)
        // The pick suspends in its DataStore write...
        val repo = RecordingHeadphones(onChoose = { _, _ -> awaitCancellation() })
        val vm = OnboardingViewModel(repo, enableDemo = {}, messenger = messenger)
        val store = storeHolding(vm)

        vm.chooseDevice("68:F2:1F:12:34:56", null)
        store.clear()
        advanceUntilIdle()

        // ...and the old composition-scope code turned its cancellation into "Job was cancelled".
        assertTrue(messages.isEmpty(), "unexpected messages: $messages")
    }

    @Test
    fun `the demo is switched on before the connect that reads it`() = runTest {
        val repo = RecordingHeadphones()
        OnboardingViewModel(repo, enableDemo = { repo.calls += "demo on" }, messenger = UiMessenger()).tryDemo()
        advanceUntilIdle()
        assertEquals(listOf("demo on", "connect"), repo.calls)
    }
}
