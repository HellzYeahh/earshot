package dev.earshot

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.earshot.ui.EarshotApp
import dev.earshot.ui.nav.Tab
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Single activity hosting the Compose UI. `singleTask` in the manifest, so the notification,
 * tile and widget re-use it and deliver their tab request through [onNewIntent].
 */
class MainActivity : ComponentActivity() {

    /** Pending "open this tab" request from [EXTRA_OPEN_TAB]; cleared once the UI navigated. */
    private val tabRequests = MutableStateFlow<Tab?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            EarshotApp(tabRequests = tabRequests, onTabRequestHandled = { tabRequests.value = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        Tab.fromExtra(intent?.getStringExtra(EXTRA_OPEN_TAB))?.let { tabRequests.value = it }
    }

    companion object {
        /** String extra: one of "home", "sound", "tools", "more". */
        const val EXTRA_OPEN_TAB = "dev.earshot.EXTRA_OPEN_TAB"
    }
}
