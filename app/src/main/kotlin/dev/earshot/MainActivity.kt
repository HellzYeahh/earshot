package dev.earshot

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import dev.earshot.find.FindSide
import dev.earshot.sleep.SleepShortcut
import dev.earshot.ui.EarshotApp
import dev.earshot.ui.nav.NavRequest
import dev.earshot.ui.nav.Routes
import dev.earshot.ui.nav.Tab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Single activity hosting the Compose UI. `singleTask` in the manifest, so the notification,
 * tile and widget re-use it and deliver their tab request through [onNewIntent].
 */
class MainActivity : ComponentActivity() {

    /** Pending "open this tab (and screen)" request from [EXTRA_OPEN_TAB] or [EXTRA_FIND]; cleared once the UI navigated. */
    private val tabRequests = MutableStateFlow<NavRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            EarshotApp(tabRequests = tabRequests, onTabRequestHandled = { tabRequests.value = null })
        }
    }

    // The Sleep tab's runtime needs to know: a visible app may set volumes on Android 17.
    override fun onStart() {
        super.onStart()
        appContainer.sleepRuntime.setAppVisible(true)
    }

    override fun onStop() {
        appContainer.sleepRuntime.setAppVisible(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        Tab.fromExtra(intent?.getStringExtra(EXTRA_OPEN_TAB))?.let { tabRequests.value = NavRequest(it) }
        // The notification's "Find": ring from here, while this activity is visible (Android 17
        // silences playback that starts in the background), and show the screen with Stop.
        // Never from Recents: the task's base intent still carries the extra, and replaying it would
        // ring a headset someone may be wearing again.
        val fromHistory = (intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        intent?.getStringExtra(EXTRA_FIND)?.takeUnless { fromHistory }?.let { side ->
            FindSide.entries.firstOrNull { it.name.equals(side, ignoreCase = true) }?.let { appContainer.find.start(it) }
            tabRequests.value = NavRequest(Tab.TOOLS, Routes.FIND)
            intent.removeExtra(EXTRA_FIND) // a configuration change must not ring again
        }
        // The launcher's "Start sleep" shortcut: start tonight's plan while this activity is visible
        // (Android 17 audio rules), then show the Sleep tab. Never replayed from Recents.
        if (intent?.action == SleepShortcut.ACTION_START && !fromHistory) {
            lifecycleScope.launch { runCatching { appContainer.sleep.start() } }
            Tab.fromExtra("sleep")?.let { tabRequests.value = NavRequest(it) }
            intent.action = Intent.ACTION_MAIN
        }
    }

    companion object {
        /** String extra: one of "home", "sound", "tools", "more". */
        const val EXTRA_OPEN_TAB = "dev.earshot.EXTRA_OPEN_TAB"

        /** String extra: a [FindSide] name. Opens Tools → Find and starts the tone. */
        const val EXTRA_FIND = "dev.earshot.EXTRA_FIND"
    }
}
