package dev.earshot.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import dev.earshot.ui.common.LocalSnackbarHostState
import dev.earshot.ui.home.PairingDialog
import dev.earshot.ui.nav.EarshotNavHost
import dev.earshot.ui.nav.Tab
import dev.earshot.ui.nav.navigateToTab
import dev.earshot.ui.onboarding.OnboardingScreen
import dev.earshot.ui.onboarding.Permissions
import dev.earshot.ui.theme.EarshotTheme
import dev.earshot.ui.whatsnew.WhatsNew
import dev.earshot.ui.whatsnew.WhatsNewSheet
import dev.earshot.ui.whatsnew.WhatsNewStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Root of the Compose UI. Hosted by MainActivity; [tabRequests] carries the
 * `dev.earshot.EXTRA_OPEN_TAB` deep link from the notification, tile and widget.
 */
@Composable
fun EarshotApp(tabRequests: StateFlow<Tab?>, onTabRequestHandled: () -> Unit) {
    val context = LocalContext.current
    val dependencies = remember { UiDependencies.from(context) }
    CompositionLocalProvider(LocalUiDependencies provides dependencies) {
        EarshotTheme {
            EarshotRoot(tabRequests = tabRequests, onTabRequestHandled = onTabRequestHandled)
        }
    }
}

@Composable
fun EarshotRoot(tabRequests: StateFlow<Tab?>, onTabRequestHandled: () -> Unit) {
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
    LaunchedEffect(hasBluetooth) { if (hasBluetooth) deps.headphones.connect() }
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
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted && Permissions.isPermanentlyDenied(context, Permissions.postNotifications)) {
            scope.launch {
                val result = snackbarHostState.showSnackbar(notificationsDeniedText, actionLabel = openAppSettingsLabel, duration = SnackbarDuration.Long)
                if (result == SnackbarResult.ActionPerformed) Permissions.openAppSettings(context)
            }
        }
    }
    var askedForNotifications by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(hasBluetooth) {
        if (hasBluetooth && !askedForNotifications) {
            askedForNotifications = true
            Permissions.postNotifications?.takeIf { !Permissions.has(context, it) }?.let(notificationLauncher::launch)
        }
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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!gated) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        val selected = tab == currentTab
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTab(tab) },
                            icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
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
