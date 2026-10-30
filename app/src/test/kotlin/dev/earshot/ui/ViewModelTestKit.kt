package dev.earshot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.fake.FakeHeadphoneRepository
import dev.earshot.ui.common.UiMessage
import dev.earshot.ui.common.UiMessenger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Helpers for screen ViewModel tests on the JVM: `viewModelScope` runs on Dispatchers.Main,
 * which the rule replaces with an eager test dispatcher (its scheduler is the one `runTest` uses).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(UnconfinedTestDispatcher())
    override fun finished(description: Description) = Dispatchers.resetMain()
}

/** Records the connection calls and the commands the tests care about; everything else is the fake's. */
class RecordingHeadphones(
    private val onForget: suspend () -> Unit = {},
    private val onChoose: suspend (String, String?) -> Unit = { _, _ -> },
) : HeadphoneRepository by FakeHeadphoneRepository(scope = CoroutineScope(Dispatchers.Unconfined)) {
    val calls = mutableListOf<String>()

    override fun connect() {
        calls += "connect"
    }

    override fun disconnect() {
        calls += "disconnect"
    }

    override suspend fun forgetPairing() {
        onForget()
        calls += "forget"
    }

    override suspend fun chooseDevice(address: String, name: String?) {
        onChoose(address, name)
        calls += "choose $address"
    }
}

/** Everything [messenger] shows from now on (SharedFlow without replay: subscribe before acting). */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.collectMessages(messenger: UiMessenger): List<UiMessage> {
    val messages = mutableListOf<UiMessage>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { messenger.messages.collect { messages += it } }
    return messages
}

/**
 * A store holding [vm]; `clear()` on it does what Android does when the ViewModel's screen or
 * Activity goes away for good (onCleared, `viewModelScope` cancelled).
 */
inline fun <reified VM : ViewModel> storeHolding(vm: VM): ViewModelStore {
    val store = ViewModelStore()
    val held = ViewModelProvider(store, viewModelFactory { initializer { vm } })[VM::class.java]
    check(held === vm) { "the store built another instance" }
    return store
}
