package dev.earshot.ui.home

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.earshot.R
import dev.earshot.core.HeadphoneState
import dev.earshot.core.NoiseSettings
import dev.earshot.core.TransportControl
import dev.earshot.core.fake.FakeHeadphoneRepository
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.common.earshotViewModel

@Composable
fun HomeScreen(contentPadding: PaddingValues, onEditModes: () -> Unit) {
    val vm = earshotViewModel { HomeViewModel(it.headphones, it.messenger) }
    val state by vm.state.collectAsStateWithLifecycle()
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
    )
}

/** Everything that matters, visible without scrolling on a phone (scrolls on small screens). */
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
) {
    val controlsEnabled = state.isConnected && !state.isBusy
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConnectionHeader(
            connection = state.connection,
            batteryPercent = state.batteryPercent,
            isCharging = state.isCharging,
            isBusy = state.isBusy,
            onRefresh = onRefresh,
        )
        StatusChips(state)
        AnimatedVisibility(visible = !state.isConnected) {
            DisconnectedCard(connection = state.connection, onConnect = onConnect)
        }
        if (state.isConnected && (state.playback != null || state.volume != null)) {
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
        Column {
            ModeCarousel(
                modes = state.modes,
                currentIndex = state.currentModeIndex,
                enabled = controlsEnabled,
                onSelect = onSelectMode,
                onToggleFavorite = onToggleFavorite,
                onEditModes = onEditModes,
                // [31.3] = 0xFF: the live noise settings match no slot (ServiceNotification calls this "Custom" too).
                customActive = state.isConnected && state.noise != null && state.currentMode == null,
            )
            Text(
                stringResource(R.string.home_modes_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
        val caps = state.capabilities
        if (caps.noiseCancelling || caps.spatialAudio) SectionCard(title = stringResource(R.string.home_noise_title)) {
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
                    stringResource(R.string.noise_hint_mode_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@LightDarkPreview
@Composable
private fun HomePreview() {
    EarshotPreview {
        HomeContent(
            state = FakeHeadphoneRepository.connectedState(),
            contentPadding = PaddingValues(0.dp),
            onConnect = {}, onRefresh = {}, onSelectMode = {}, onToggleFavorite = {}, onNoiseChange = {}, onEditModes = {},
        )
    }
}

@LightDarkPreview
@Composable
private fun HomeDisconnectedPreview() {
    EarshotPreview {
        HomeContent(
            state = HeadphoneState(),
            contentPadding = PaddingValues(0.dp),
            onConnect = {}, onRefresh = {}, onSelectMode = {}, onToggleFavorite = {}, onNoiseChange = {}, onEditModes = {},
        )
    }
}
