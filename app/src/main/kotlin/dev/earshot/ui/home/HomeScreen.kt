package dev.earshot.ui.home

import android.content.ClipData
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.earshot.R
import dev.earshot.core.FormFactor
import dev.earshot.core.HeadphoneCandidate
import dev.earshot.core.HeadphoneState
import dev.earshot.core.NoiseSettings
import dev.earshot.core.TransportControl
import dev.earshot.core.fake.FakeHeadphoneRepository
import dev.earshot.extras.HeadphoneLabels
import dev.earshot.ui.LocalUiDependencies
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.common.earshotViewModel
import dev.earshot.ui.tools.DiagnosticsReport
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(contentPadding: PaddingValues, onEditModes: () -> Unit) {
    val vm = earshotViewModel { HomeViewModel(it.headphones, it.messenger) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val messenger = LocalUiDependencies.current.messenger
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var showSwitcher by rememberSaveable { mutableStateOf(false) }

    HomeContent(
        state = state,
        contentPadding = contentPadding,
        onConnect = vm::connect,
        onRefresh = vm::refresh,
        onSelectMode = vm::setMode,
        onToggleFavorite = vm::toggleFavorite,
        onNoiseChange = vm::setNoise,
        onEditModes = onEditModes,
        onTransport = vm::transport,
        onVolume = vm::setVolume,
        onCrusher = vm::setCrusherBass,
        onOpenSwitcher = { showSwitcher = true },
        onCopyReport = {
            scope.launch {
                runCatching {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Earshot connection report", DiagnosticsReport.build(context))))
                }.onSuccess { messenger.show(R.string.report_copied) }.onFailure { messenger.report(it) }
            }
        },
    )

    if (showSwitcher) {
        // Re-read each time the sheet opens: the user may have just paired something.
        val candidates by produceState<List<HeadphoneCandidate>?>(initialValue = null) { value = vm.candidates() }
        DeviceSwitcherSheet(
            candidates = candidates,
            currentAddress = state.connection.deviceAddress,
            onPick = { candidate ->
                showSwitcher = false
                vm.chooseDevice(candidate.address, candidate.name)
            },
            onPairNew = {
                showSwitcher = false
                runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            },
            onDismiss = { showSwitcher = false },
        )
    }
}

/**
 * Everything that matters, visible without scrolling on a phone (scrolls on small screens).
 * The controls follow what the connected model has; while not connected only the header and
 * the [DisconnectedCard] show, so no control is left greyed out under an error.
 */
@Composable
fun HomeContent(
    state: HeadphoneState,
    contentPadding: PaddingValues,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onSelectMode: (Int) -> Unit,
    onToggleFavorite: (Int) -> Unit,
    onNoiseChange: (NoiseSettings) -> Unit,
    onEditModes: () -> Unit,
    onTransport: (TransportControl) -> Unit = {},
    onVolume: (Int) -> Unit = {},
    onCrusher: (active: Boolean?, intensity: Int?, mode: Int?) -> Unit = { _, _, _ -> },
    onOpenSwitcher: () -> Unit = {},
    onCopyReport: () -> Unit = {},
) {
    val controlsEnabled = state.isConnected && !state.isBusy
    val formFactor = HeadphoneLabels.formFactor(state)
    val buds = HeadphoneLabels.isEarbuds(formFactor)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConnectionHeader(state = state, onRefresh = onRefresh, onSwitch = onOpenSwitcher)
        StatusChips(state)
        AnimatedVisibility(visible = !state.isConnected) {
            DisconnectedCard(
                connection = state.connection,
                formFactor = formFactor,
                onConnect = onConnect,
                onChooseHeadphones = onOpenSwitcher,
                onCopyReport = onCopyReport,
            )
        }
        if (state.isConnected) ConnectedControls(
            state = state,
            buds = buds,
            controlsEnabled = controlsEnabled,
            onSelectMode = onSelectMode,
            onToggleFavorite = onToggleFavorite,
            onNoiseChange = onNoiseChange,
            onEditModes = onEditModes,
            onTransport = onTransport,
            onVolume = onVolume,
            onCrusher = onCrusher,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ConnectedControls(
    state: HeadphoneState,
    buds: Boolean,
    controlsEnabled: Boolean,
    onSelectMode: (Int) -> Unit,
    onToggleFavorite: (Int) -> Unit,
    onNoiseChange: (NoiseSettings) -> Unit,
    onEditModes: () -> Unit,
    onTransport: (TransportControl) -> Unit,
    onVolume: (Int) -> Unit,
    onCrusher: (active: Boolean?, intensity: Int?, mode: Int?) -> Unit,
) {
    val caps = state.capabilities
    caps.supportNote?.let { SupportNoteCard(it) }
    if (state.playback != null || state.volume != null) {
        NowPlayingRow(
            playback = state.playback,
            volume = state.volume,
            volumeMax = state.volumeMax,
            supportedControls = state.supportedTransportControls,
            // Not gated on isBusy: a transport tap while a refresh runs should still go through.
            enabled = state.isConnected,
            onTransport = onTransport,
            onVolume = onVolume,
        )
    }
    // Firmware that refuses the whole AudioModes block (bosectl #44) has no modes to show: a
    // carousel of placeholders whose every tap fails helped nobody. The support note, when the
    // model has one, already says so.
    if (!caps.listeningModes) {
        if (caps.supportNote == null) Text(
            stringResource(if (buds) R.string.home_modes_unavailable_buds else R.string.home_modes_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    } else Column {
        ModeCarousel(
            modes = state.modes,
            currentIndex = state.currentModeIndex,
            enabled = controlsEnabled,
            onSelect = onSelectMode,
            onToggleFavorite = onToggleFavorite,
            onEditModes = onEditModes,
            canEdit = caps.editableModes,
            canFavorite = caps.favoriteModes,
            // [31.3] = 0xFF: the live noise settings match no slot (ServiceNotification calls this "Custom" too).
            customActive = state.noise != null && state.currentMode == null,
        )
        Text(
            stringResource(if (caps.favoriteModes) R.string.home_modes_hint else R.string.home_modes_hint_tap),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
    }
    // Open earbuds have no noise cancelling at all; their immersive audio is a mode of its own.
    val showNoise = caps.noiseCancelling || (caps.spatialAudio && caps.formFactor != FormFactor.OPEN_EARBUDS)
    if (showNoise) SectionCard(title = stringResource(if (caps.noiseCancelling) R.string.home_noise_title else R.string.noise_immersive)) {
        val noise = state.noise ?: NoiseSettings()
        // Older models keep noise settings in the modes: only a custom mode can be adjusted.
        val adjustable = caps.liveNoiseControl || state.currentMode?.isEditable == true
        NoiseControls(
            settings = noise,
            enabled = controlsEnabled && state.noise != null && adjustable,
            onChange = onNoiseChange,
            compact = true,
            capabilities = caps,
        )
        if (!adjustable) {
            Text(
                stringResource(
                    when {
                        !caps.listeningModes -> R.string.noise_hint_read_only
                        buds -> R.string.noise_hint_mode_only_buds
                        else -> R.string.noise_hint_mode_only
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    state.crusher?.let { bass ->
        CrusherCard(bass = bass, enabled = controlsEnabled, onChange = onCrusher)
    }
}

// ---- Previews ---------------------------------------------------------------------------------

@Composable
private fun HomePreviewOf(state: HeadphoneState) {
    EarshotPreview {
        HomeContent(
            state = state,
            contentPadding = PaddingValues(0.dp),
            onConnect = {}, onRefresh = {}, onSelectMode = {}, onToggleFavorite = {}, onNoiseChange = {}, onEditModes = {},
        )
    }
}

@LightDarkPreview
@Composable
private fun HomePreview() = HomePreviewOf(FakeHeadphoneRepository.connectedState())

@LightDarkPreview
@Composable
private fun HomeEarbudsPreview() = HomePreviewOf(FakeHeadphoneRepository.connectedEarbudsState())

/** A basic-tier model: the support note, no carousel, the cancelling level read-only. */
@LightDarkPreview
@Composable
private fun HomeBasicTierPreview() = HomePreviewOf(FakeHeadphoneRepository.basicTierState())

@LightDarkPreview
@Composable
private fun HomeDisconnectedPreview() = HomePreviewOf(
    HeadphoneState(connection = dev.earshot.core.ConnectionState(dev.earshot.core.ConnectionStatus.DISCONNECTED, FakeHeadphoneRepository.DEVICE_NAME)),
)
