package dev.earshot.ui.onboarding

import dev.earshot.core.HeadphoneRepository
import dev.earshot.ui.common.EarshotViewModel
import dev.earshot.ui.common.UiMessenger

/**
 * The onboarding's commands. They must not run in the screen's own coroutine scope: picking a
 * headset hands the connection to that headset's stack, whose idle state (DISCONNECTED) opens
 * the root's gate and disposes this screen while `chooseDevice` is still saving the pick (a
 * DataStore write). A composition scope was cancelled right there, so the connect never started
 * and the cancellation came up as a snackbar. The onboarding sits outside the NavHost, so this
 * ViewModel belongs to the Activity and outlives the screen; [launchCatching] also keeps a
 * cancellation out of the snackbar.
 */
class OnboardingViewModel(
    private val headphones: HeadphoneRepository,
    /** Saves "demo mode on" (`DemoMode.set(true)`); a lambda so the ViewModel holds no Context. */
    private val enableDemo: suspend () -> Unit,
    messenger: UiMessenger,
) : EarshotViewModel(messenger) {

    fun chooseDevice(address: String, name: String?) {
        launchCatching { headphones.chooseDevice(address, name) }
    }

    /** Demo mode is read when a connect starts, so it is saved first. */
    fun tryDemo() {
        launchCatching {
            enableDemo()
            headphones.connect()
        }
    }
}
