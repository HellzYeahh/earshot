package dev.earshot.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.earshot.R
import dev.earshot.core.HeadphoneState
import dev.earshot.core.Sidetone
import dev.earshot.core.fake.FakeHeadphoneRepository
import dev.earshot.extras.HeadphoneLabels
import dev.earshot.ui.common.rememberSettingsLoaded
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.InfoBanner
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.NavRow
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.common.SegmentedChoice
import dev.earshot.ui.common.SwitchRow
import dev.earshot.ui.common.earshotViewModel

@Composable
fun MoreScreen(
    contentPadding: PaddingValues,
    onOpenAutomations: () -> Unit,
    onOpenPreferences: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val vm = earshotViewModel { SettingsViewModel(it.headphones, it.messenger) }
    val state by vm.state.collectAsStateWithLifecycle()
    MoreContent(
        state = state,
        contentPadding = contentPadding,
        callbacks = SettingsCallbacks(
            onSidetone = vm::setSidetone,
            onAutoPlayPause = vm::setAutoPlayPause,
            onAutoAnswer = vm::setAutoAnswer,
            onMultipoint = vm::setMultipoint,
            onAutoOff = vm::setAutoOff,
        ),
        onOpenAutomations = onOpenAutomations,
        onOpenPreferences = onOpenPreferences,
        onOpenAbout = onOpenAbout,
    )
}

data class SettingsCallbacks(
    val onSidetone: (Sidetone, Boolean) -> Unit = { _, _ -> },
    val onAutoPlayPause: (Boolean) -> Unit = {},
    val onAutoAnswer: (Boolean) -> Unit = {},
    val onMultipoint: (Boolean) -> Unit = {},
    val onAutoOff: (Int) -> Unit = {},
)

/**
 * The More tab: the headphones' everyday settings, About these headphones and the App section.
 * Touch controls, voice prompts, devices, rename and power off live on Tools → Controls
 * (Headphone controls), which also sets the Shortcut button.
 */
@Composable
fun MoreContent(
    state: HeadphoneState,
    contentPadding: PaddingValues,
    callbacks: SettingsCallbacks,
    onOpenAutomations: () -> Unit,
    onOpenPreferences: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val enabled = state.isConnected && !state.isBusy
    var showAutoOff by rememberSaveable { mutableStateOf(false) }
    val buds = HeadphoneLabels.isEarbuds(HeadphoneLabels.formFactor(state))
    // Once the refresh has landed, null means "this model doesn't have it": hide the row rather
    // than leave a switch that can never be used. While loading, rows stay (disabled) so nothing jumps.
    val loaded = rememberSettingsLoaded(state)
    fun has(value: Any?) = !loaded || value != null
    val hasSidetone = has(state.sidetone)
    val hasGeneral = has(state.autoPlayPause) || has(state.autoAnswer) || has(state.multipointEnabled) || has(state.autoOffMinutes)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.tab_more), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))

        if (!state.isConnected) InfoBanner(stringResource(R.string.settings_not_connected))

        // A model with none of these settings gets no empty card.
        if (hasSidetone || hasGeneral) SectionCard(title = stringResource(if (buds) R.string.settings_earbuds else R.string.settings_headphones)) {
            if (hasSidetone) {
                // Sidetone: wire order is 0=off 1=high 2=med 3=low; the enum handles it, we just show labels.
                Text(stringResource(R.string.settings_sidetone), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
                SegmentedChoice(
                    options = listOf(Sidetone.OFF, Sidetone.LOW, Sidetone.MEDIUM, Sidetone.HIGH),
                    selected = state.sidetone ?: Sidetone.OFF,
                    onSelect = { callbacks.onSidetone(it, state.sidetonePersistent ?: true) },
                    label = { it.label },
                    enabled = enabled && state.sidetone != null,
                )
                SwitchRow(
                    title = stringResource(R.string.settings_sidetone_persistent),
                    subtitle = stringResource(R.string.settings_sidetone_persistent_subtitle),
                    checked = state.sidetonePersistent ?: true,
                    enabled = enabled && state.sidetone != null,
                    onCheckedChange = { callbacks.onSidetone(state.sidetone ?: Sidetone.OFF, it) },
                )
                if (hasGeneral) HorizontalDivider()
            }
            if (has(state.autoPlayPause)) SwitchRow(stringResource(R.string.settings_auto_play_pause), state.autoPlayPause ?: false, callbacks.onAutoPlayPause, subtitle = stringResource(R.string.settings_auto_play_pause_subtitle), enabled = enabled && state.autoPlayPause != null)
            if (has(state.autoAnswer)) SwitchRow(stringResource(R.string.settings_auto_answer), state.autoAnswer ?: false, callbacks.onAutoAnswer, subtitle = stringResource(R.string.settings_auto_answer_subtitle), enabled = enabled && state.autoAnswer != null)
            if (has(state.multipointEnabled)) SwitchRow(stringResource(R.string.settings_multipoint), state.multipointEnabled ?: false, callbacks.onMultipoint, subtitle = stringResource(R.string.settings_multipoint_subtitle), enabled = enabled && state.multipointEnabled != null)
            if (has(state.autoOffMinutes)) NavRow(
                title = stringResource(R.string.settings_auto_off),
                subtitle = stringResource(R.string.settings_auto_off_subtitle),
                value = state.autoOffMinutes?.let { autoOffLabel(it) },
                icon = Icons.Default.Timer,
                onClick = { showAutoOff = true },
                enabled = enabled && state.autoOffMinutes != null,
            )
        }

        DeviceInfoCard(state)

        SectionCard(title = stringResource(R.string.settings_app)) {
            NavRow(title = stringResource(R.string.settings_automations), subtitle = stringResource(R.string.settings_automations_subtitle), icon = Icons.Default.AutoAwesome, onClick = onOpenAutomations)
            NavRow(title = stringResource(R.string.settings_preferences), subtitle = stringResource(R.string.settings_preferences_subtitle), icon = Icons.Default.Tune, onClick = onOpenPreferences)
            NavRow(title = stringResource(R.string.settings_about), icon = Icons.Default.Info, onClick = onOpenAbout)
        }
    }

    if (showAutoOff) {
        AutoOffDialog(
            current = state.autoOffMinutes,
            onPick = { minutes -> showAutoOff = false; callbacks.onAutoOff(minutes) },
            onDismiss = { showAutoOff = false },
        )
    }
}

@LightDarkPreview
@Composable
private fun MorePreview() {
    EarshotPreview {
        MoreContent(FakeHeadphoneRepository.connectedState(), PaddingValues(0.dp), SettingsCallbacks(), {}, {}, {})
    }
}

/** Earbuds: only the rows the model has, earbud wording, the device info card. */
@LightDarkPreview
@Composable
private fun MoreEarbudsPreview() {
    EarshotPreview {
        MoreContent(FakeHeadphoneRepository.connectedEarbudsState(), PaddingValues(0.dp), SettingsCallbacks(), {}, {}, {})
    }
}

/** A basic-tier model whose layout is a best guess: the unverified note under the device info. */
@LightDarkPreview
@Composable
private fun MoreBasicTierPreview() {
    EarshotPreview {
        MoreContent(FakeHeadphoneRepository.basicTierState(), PaddingValues(0.dp), SettingsCallbacks(), {}, {}, {})
    }
}
