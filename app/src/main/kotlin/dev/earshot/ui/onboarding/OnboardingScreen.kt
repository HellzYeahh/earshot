package dev.earshot.ui.onboarding

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.outlined.DeviceUnknown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
import dev.earshot.appContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.core.ConnectionState
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneCandidate
import dev.earshot.ui.LocalUiDependencies
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.NavRow
import dev.earshot.ui.common.SectionCard
import kotlinx.coroutines.launch

/**
 * Shown instead of the tabs until Bluetooth permission is granted, and whenever the repository
 * reports there is no paired QC Ultra. Also hosts the notification permission request.
 */
@Composable
fun OnboardingScreen(
    contentPadding: PaddingValues,
    hasBluetoothPermission: Boolean,
    connection: ConnectionState,
    onBluetoothPermissionResult: (Boolean) -> Unit,
    onConnect: () -> Unit,
    bluetoothPermanentlyDenied: Boolean = false,
    onOpenAppSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val deps = LocalUiDependencies.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onBluetoothPermissionResult(granted)
    }
    // Re-query whenever the status changes (e.g. after pairing in system settings and coming back).
    val candidates by produceState<List<HeadphoneCandidate>?>(initialValue = null, hasBluetoothPermission, connection.status) {
        value = if (hasBluetoothPermission) runCatching { deps.headphones.deviceCandidates() }.getOrDefault(emptyList()) else emptyList()
    }
    OnboardingContent(
        contentPadding = contentPadding,
        hasBluetoothPermission = hasBluetoothPermission,
        connection = connection,
        candidates = candidates,
        onRequestPermission = { Permissions.bluetoothConnect?.let(launcher::launch) ?: onBluetoothPermissionResult(true) },
        onOpenBluetoothSettings = {
            runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        },
        onChoose = { candidate ->
            scope.launch {
                runCatching { deps.headphones.chooseDevice(candidate.address, candidate.name) }
                    .onFailure { deps.messenger.report(it) }
            }
        },
        onConnect = onConnect,
        bluetoothPermanentlyDenied = bluetoothPermanentlyDenied,
        onOpenAppSettings = onOpenAppSettings,
        onTryDemo = {
            scope.launch {
                context.appContainer.demoMode.set(true)
                deps.headphones.connect()
            }
        },
    )
}

@Composable
fun OnboardingContent(
    contentPadding: PaddingValues,
    hasBluetoothPermission: Boolean,
    connection: ConnectionState,
    candidates: List<HeadphoneCandidate>?,
    onRequestPermission: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onChoose: (HeadphoneCandidate) -> Unit,
    onConnect: () -> Unit,
    /** Android no longer shows the permission dialog; offer the app's settings page instead. */
    bluetoothPermanentlyDenied: Boolean = false,
    onOpenAppSettings: () -> Unit = {},
    /** Switches on the built-in virtual headphones; null hides the option. */
    onTryDemo: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Icon(Icons.Default.Headphones, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Text(stringResource(R.string.onboarding_title).uppercase(), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.onboarding_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (!hasBluetoothPermission || connection.status == ConnectionStatus.NO_PERMISSION) {
            SectionCard(title = stringResource(R.string.onboarding_permission_title)) {
                Text(stringResource(R.string.onboarding_permission_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (bluetoothPermanentlyDenied) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.onboarding_permission_denied), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(12.dp))
                Button(shape = MaterialTheme.shapes.medium, onClick = onRequestPermission, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Default.Bluetooth, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_allow_bluetooth))
                }
                if (bluetoothPermanentlyDenied) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = onOpenAppSettings, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(stringResource(R.string.action_open_app_settings))
                    }
                }
            }
        } else {
            DevicePicker(connection, candidates, onOpenBluetoothSettings, onChoose, onConnect)
        }
        if (onTryDemo != null) {
            TextButton(onClick = onTryDemo, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onboarding_try_demo))
            }
        }
    }
}

/** Paired devices from the repository: Bose-looking first, then other audio devices, then the rest. */
@Composable
private fun DevicePicker(
    connection: ConnectionState,
    candidates: List<HeadphoneCandidate>?,
    onOpenBluetoothSettings: () -> Unit,
    onChoose: (HeadphoneCandidate) -> Unit,
    onConnect: () -> Unit,
) {
    val sorted = candidates.orEmpty().sortedWith(
        compareByDescending<HeadphoneCandidate> { it.looksLikeBose }.thenByDescending { it.isAudioDevice }.thenBy { it.name ?: it.address },
    )
    SectionCard(title = stringResource(R.string.onboarding_pick_title)) {
        Text(
            stringResource(if (connection.status == ConnectionStatus.NO_PAIRED_DEVICE) R.string.onboarding_pick_body_unpaired else R.string.onboarding_pick_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        when {
            candidates == null -> Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.onboarding_scanning), style = MaterialTheme.typography.bodyMedium)
            }
            sorted.isEmpty() -> Text(stringResource(R.string.onboarding_no_devices), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
            else -> sorted.forEach { candidate ->
                NavRow(
                    title = candidate.name ?: candidate.address,
                    subtitle = when {
                        candidate.looksLikeBose -> stringResource(R.string.onboarding_candidate_bose)
                        candidate.isAudioDevice -> stringResource(R.string.onboarding_candidate_audio)
                        else -> candidate.address
                    },
                    icon = when {
                        candidate.looksLikeBose -> Icons.Default.Headphones
                        candidate.isAudioDevice -> Icons.Default.Speaker
                        else -> Icons.Outlined.DeviceUnknown
                    },
                    onClick = { onChoose(candidate) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                shape = MaterialTheme.shapes.medium,
                onClick = onOpenBluetoothSettings,
                modifier = Modifier.weight(1f).height(48.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text(stringResource(R.string.action_bluetooth_settings), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(shape = MaterialTheme.shapes.medium, onClick = onConnect, modifier = Modifier.weight(1f).height(48.dp), enabled = connection.status != ConnectionStatus.CONNECTING) {
                Text(stringResource(R.string.action_connect))
            }
        }
    }
}

@LightDarkPreview
@Composable
private fun OnboardingPermissionPreview() {
    EarshotPreview {
        OnboardingContent(PaddingValues(0.dp), hasBluetoothPermission = false, connection = ConnectionState(), candidates = null, {}, {}, {}, {})
    }
}

@LightDarkPreview
@Composable
private fun OnboardingPermissionDeniedPreview() {
    EarshotPreview {
        OnboardingContent(
            PaddingValues(0.dp), hasBluetoothPermission = false, connection = ConnectionState(), candidates = null,
            onRequestPermission = {}, onOpenBluetoothSettings = {}, onChoose = {}, onConnect = {},
            bluetoothPermanentlyDenied = true,
        )
    }
}

@LightDarkPreview
@Composable
private fun OnboardingPickerPreview() {
    EarshotPreview {
        OnboardingContent(
            PaddingValues(0.dp), hasBluetoothPermission = true,
            connection = ConnectionState(ConnectionStatus.NO_PAIRED_DEVICE),
            candidates = listOf(
                HeadphoneCandidate("00:11:22:33:44:55", "Car stereo", looksLikeBose = false, isAudioDevice = true),
                HeadphoneCandidate("68:F2:1F:12:34:56", "Bose QC Ultra Headphones", looksLikeBose = true, isAudioDevice = true),
                HeadphoneCandidate("AA:BB:CC:DD:EE:FF", "Keyboard", looksLikeBose = false, isAudioDevice = false),
            ),
            onRequestPermission = {}, onOpenBluetoothSettings = {}, onChoose = {}, onConnect = {},
        )
    }
}
