package dev.earshot.ui.common

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.earshot.ui.LocalUiDependencies
import dev.earshot.ui.UiDependencies
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Base for every screen ViewModel: one place for the `runCatching` + Snackbar convention. */
abstract class EarshotViewModel(protected val messenger: UiMessenger) : ViewModel() {

    /** Runs a repository command; failures become a Snackbar line instead of a crash. */
    protected fun launchCatching(block: suspend () -> Unit): Job = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            messenger.report(e)
        }
    }
}

/**
 * Builds a ViewModel whose constructor takes repositories, pulled from [LocalUiDependencies].
 * `earshotViewModel { deps -> HomeViewModel(deps.headphones, deps.messenger) }`
 */
@Composable
inline fun <reified VM : ViewModel> earshotViewModel(
    key: String? = null,
    crossinline create: (UiDependencies) -> VM,
): VM {
    val deps = LocalUiDependencies.current
    return viewModel(key = key, factory = viewModelFactory { initializer { create(deps) } })
}
