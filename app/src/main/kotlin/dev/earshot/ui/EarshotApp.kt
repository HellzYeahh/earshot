package dev.earshot.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.earshot.R
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneEvent
import dev.earshot.ui.common.FieldNavigationBar
import dev.earshot.ui.common.FieldNavigationBarItem
import dev.earshot.ui.common.LocalSnackbarHostState
import dev.earshot.ui.home.PairingDialog
import dev.earshot.ui.nav.EarshotNavHost
import dev.earshot.ui.nav.NavRequest
import dev.earshot.ui.nav.Tab
import dev.earshot.ui.nav.navigateToTab
import dev.earshot.ui.onboarding.NotificationPromptStore
import dev.earshot.ui.onboarding.OnboardingScreen
import dev.earshot.ui.onboarding.Permissions
import dev.earshot.ui.theme.EarshotTheme
import dev.earshot.ui.theme.dotGrid
import dev.earshot.ui.whatsnew.WhatsNew
import dev.earshot.ui.whatsnew.WhatsNewSheet
import dev.earshot.ui.whatsnew.WhatsNewStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The root's own connect runs once per process. Every new Activity composes the root again: a
 * rotation or fold (MainActivity handles no configuration changes), or a tap on the Sleep or
 * headphones notification after the app was swiped from Recents while the service kept the
 * process alive. Connecting each time paged back a headset that the notification's Disconnect,
 * the universal remote or the Sleep end action had dropped on purpose, at night too: a connect
 * clears the user stop and the loop opens the saved headset whether or not it is linked. Later
 * Activities leave the link as it is: the connect loop has its own wake-ups (link-up, audio,
 * Bluetooth on, companion presence) and Connect is one tap away.
 */
internal class AutoConnectOnce {
    private val done = AtomicBoolean(false)

    /** True exactly once: the caller connects now. */
    fun claim(): Boolean = done.compareAndSet(false, true)

    companion object {
        /** The running app's one instance; it lives as long as the process. */
        val process = AutoConnectOnce()
    }
}

/**
 * Root of the Compose UI. Hosted by MainActivity; [tabRequests] carries the
 * `dev.earshot.EXTRA_OPEN_TAB` deep link from the notification, tile and widget.
 */
@Composable
fun EarshotApp(tabRequests: StateFlow<NavRequest?>, onTabRequestHandled: () -> Unit) {
    val context = LocalContext.current
    val dependencies = remember { UiDependencies.from(context) }
    val preferences by dependencies.automation.preferences.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalUiDependencies provides dependencies) {
        EarshotTheme(appearance = preferences.appearance) {
            EarshotRoot(tabRequests = tabRequests, onTabRequestHandled = onTabRequestHandled)
        }
    }
}

@Composable
fun EarshotRoot(tabRequests: StateFlow<NavRequest?>, onTabRequestHandled: () -> Unit) {
    val deps = LocalUiDependencies.current
    val context = LocalContext.current
    val headphoneState by deps.headphones.state.collectAsStateWithLifecycle()

    // ---- Permissions -----------------------------------------------------------------------
    var hasBluetooth by remember { mutableStateOf(Permissions.has(context, Permissions.bluetoothConnect)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // The user may grant/revoke in system settings; re-check whenever we come back.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasBluetooth = Permissions.has(context, Permissions.bluetoothConnect)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Once per process, not once per Activity: see AutoConnectOnce.
    LaunchedEffect(hasBluetooth) { if (hasBluetooth && AutoConnectOnce.process.claim()) deps.headphones.connect() }
    // Set after a denied request when Android will no longer show the dialog; only app settings can help then.
    var bluetoothDeniedForGood by rememberSaveable { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // A one-time pairing can start whichever tab is open (it runs while connecting), so the dialog lives here.
    headphoneState.pairing?.let { request ->
        PairingDialog(request) { accept -> scope.launch { runCatching { deps.headphones.answerPairing(accept) } } }
    }
    val openAppSettingsLabel = stringResource(R.string.action_open_app_settings)
    val notificationsDeniedText = stringResource(R.string.notifications_denied)

    // ---- Start-up permission requests --------------------------------------------------------
    // Nearby-devices scan rides on the connect grant (same permission group: no dialog), for the
    // AirPods per-bud battery; a refusal changes nothing else. Notifications are asked once per
    // install, not once per Activity: after two refusals Android denies without a dialog, and the
    // "Notifications are off" snackbar then came back on every cold start (NotificationPromptStore).
    //
    // Both go out as ONE request. An Activity runs one permission request at a time and answers a
    // second one sent while the first is open at once, with empty arrays and no dialog
    // (Activity.requestPermissions: "Can request only one set of permissions at a time"). With a
    // launcher each, the notification ask sent a few ms after the scan ask on a fresh install was
    // cancelled that way: RequestPermission read it as "denied", the snackbar showed and the
    // once-per-install flag was spent without the user ever seeing the dialog.
    // RequestMultiplePermissions keeps that cancellation visible as an empty map.
    val notificationPrompt = remember(context) { NotificationPromptStore(context) }
    // Saved across a rotation or fold, so the recreated Activity doesn't send a second request
    // while the first one's dialog is still open (that one would be cancelled the same way).
    var startupAskSent by rememberSaveable { mutableStateOf(false) }
    // True while the request in flight carries a notification ask claimed from the store.
    var notificationAskInFlight by rememberSaveable { mutableStateOf(false) }
    val startupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val claimed = notificationAskInFlight
        notificationAskInFlight = false
        val notifications = Permissions.postNotifications
        if (claimed && notifications != null) {
            if (results.isEmpty()) {
                // Cancelled before anything was shown: not an answer, so no snackbar. Re-arm the
                // ask for the next start.
                scope.launch(Dispatchers.IO) { notificationPrompt.release() }
            } else if (results[notifications] == false && Permissions.isPermanentlyDenied(context, notifications)) {
                scope.launch {
                    val result = snackbarHostState.showSnackbar(notificationsDeniedText, actionLabel = openAppSettingsLabel, duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) Permissions.openAppSettings(context)
                }
            }
        }
    }
    LaunchedEffect(hasBluetooth) {
        if (!hasBluetooth || startupAskSent) return@LaunchedEffect
        val scan = Permissions.bluetoothScan?.takeUnless { Permissions.has(context, it) }
        val notifications = Permissions.postNotifications?.takeUnless { Permissions.has(context, it) }
        var claimed = false
        if (notifications != null) {
            try {
                withContext(Dispatchers.IO) { claimed = notificationPrompt.claimFirstAsk() }
            } catch (e: CancellationException) {
                // The claim may have been stored just before this effect was cancelled (root left
                // the composition): nothing was asked, so give the ask back to the next start.
                if (claimed) withContext(NonCancellable + Dispatchers.IO) { notificationPrompt.release() }
                throw e
            }
        }
        val ask = listOfNotNull(scan, notifications?.takeIf { claimed })
        if (ask.isEmpty()) return@LaunchedEffect
        startupAskSent = true
        notificationAskInFlight = claimed
        startupLauncher.launch(ask.toTypedArray())
    }

    // ---- Messages ----------------------------------------------------------------------------
    LaunchedEffect(deps) {
        deps.messenger.messages.collect { snackbarHostState.showSnackbar(it.resolve(context)) }
    }
    LaunchedEffect(deps) {
        deps.headphones.events.collect { event ->
            when (event) {
                is HeadphoneEvent.Error -> snackbarHostState.showSnackbar(event.message)
                is HeadphoneEvent.Info -> snackbarHostState.showSnackbar(event.message)
                HeadphoneEvent.Connected, HeadphoneEvent.Disconnected -> Unit
            }
        }
    }

    // ---- Navigation --------------------------------------------------------------------------
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentTab = Tab.forRoute(backStackEntry?.destination?.route)
    val status = headphoneState.connection.status
    val gated = !hasBluetooth || status == ConnectionStatus.NO_PERMISSION || status == ConnectionStatus.NO_PAIRED_DEVICE

    // The dot grid is drawn once here, under everything; the sub-screen scaffolds stay transparent.
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .dotGrid(EarshotTheme.colors.dotGrid),
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!gated) {
                FieldNavigationBar {
                    Tab.entries.forEach { tab ->
                        val selected = tab == currentTab
                        FieldNavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTab(tab) },
                            icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                            label = stringResource(tab.label),
                        )
                    }
                }
            }
        },
    ) { padding: PaddingValues ->
        if (gated) {
            OnboardingScreen(
                contentPadding = padding,
                hasBluetoothPermission = hasBluetooth,
                connection = headphoneState.connection,
                onBluetoothPermissionResult = { granted ->
                    hasBluetooth = granted || Permissions.has(context, Permissions.bluetoothConnect)
                    bluetoothDeniedForGood = !hasBluetooth && Permissions.isPermanentlyDenied(context, Permissions.bluetoothConnect)
                },
                onConnect = deps.headphones::connect,
                bluetoothPermanentlyDenied = bluetoothDeniedForGood,
                onOpenAppSettings = { Permissions.openAppSettings(context) },
            )
        } else {
            CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
                EarshotNavHost(
                    navController = navController,
                    contentPadding = padding,
                    tabRequests = tabRequests,
                    onTabRequestHandled = onTabRequestHandled,
                )
            }
        }
    }

    // ---- What's new (once per release, after an update; onboarding covers a fresh install) ----
    val whatsNewStore = remember(context) { WhatsNewStore(context) }
    val whatsNew by produceState<WhatsNew.Decision?>(initialValue = null, whatsNewStore) {
        value = withContext(Dispatchers.IO) { whatsNewStore.decision() }
    }
    var whatsNewDismissed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(whatsNew) {
        if (whatsNew == WhatsNew.Decision.MARK_SEEN) withContext(Dispatchers.IO) { whatsNewStore.markSeen() }
    }
    if (!gated && whatsNew == WhatsNew.Decision.SHOW && !whatsNewDismissed) {
        WhatsNewSheet(onDismiss = {
            whatsNewDismissed = true
            scope.launch(Dispatchers.IO) { whatsNewStore.markSeen() }
        })
    }
}
