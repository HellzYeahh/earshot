package dev.earshot.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.earshot.core.ButtonAction
import dev.earshot.core.HeadphoneState
import dev.earshot.core.Sidetone
import dev.earshot.core.VoicePrompts
import dev.earshot.core.fake.FakeHeadphoneRepository
import dev.earshot.ui.common.ConfirmDialog
import dev.earshot.ui.common.DropdownField
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
    onOpenDevices: () -> Unit,
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
            onTouchControls = vm::setTouchControls,
            onVoicePrompts = vm::setVoicePrompts,
            onShortcutButton = vm::setShortcutButton,
            onRename = vm::rename,
            onPowerOff = vm::powerOff,
        ),
        onOpenDevices = onOpenDevices,
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
    val onTouchControls: (Boolean) -> Unit = {},
    val onVoicePrompts: (VoicePrompts) -> Unit = {},
    val onShortcutButton: (ButtonAction) -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onPowerOff: () -> Unit = {},
)

@Composable
fun MoreContent(
    state: HeadphoneState,
    contentPadding: PaddingValues,
    callbacks: SettingsCallbacks,
    onOpenDevices: () -> Unit,
    onOpenAutomations: () -> Unit,
    onOpenPreferences: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val enabled = state.isConnected && !state.isBusy
    var showRename by rememberSaveable { mutableStateOf(false) }
    var showPowerOff by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.tab_more), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))

        if (!state.isConnected) InfoBanner(stringResource(R.string.settings_not_connected))

        SectionCard(title = stringResource(R.string.settings_headphones)) {
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
            HorizontalDivider()
            SwitchRow(stringResource(R.string.settings_auto_play_pause), state.autoPlayPause ?: false, callbacks.onAutoPlayPause, subtitle = stringResource(R.string.settings_auto_play_pause_subtitle), enabled = enabled && state.autoPlayPause != null)
            SwitchRow(stringResource(R.string.settings_auto_answer), state.autoAnswer ?: false, callbacks.onAutoAnswer, subtitle = stringResource(R.string.settings_auto_answer_subtitle), enabled = enabled && state.autoAnswer != null)
            SwitchRow(stringResource(R.string.settings_multipoint), state.multipointEnabled ?: false, callbacks.onMultipoint, subtitle = stringResource(R.string.settings_multipoint_subtitle), enabled = enabled && state.multipointEnabled != null)
            // [1.34] is "disable captouch" on the wire; the state already presents it the right way up.
            SwitchRow(stringResource(R.string.settings_touch_controls), state.touchControlsEnabled ?: true, callbacks.onTouchControls, subtitle = stringResource(R.string.settings_touch_controls_subtitle), enabled = enabled && state.touchControlsEnabled != null)
            NavRow(
                title = stringResource(R.string.settings_devices),
                value = state.pairedDevices.firstOrNull { it.isActiveSource }?.name,
                icon = Icons.Default.Devices,
                onClick = onOpenDevices,
            )
            HorizontalDivider()
            val prompts = state.voicePrompts
            SwitchRow(
                title = stringResource(R.string.settings_voice_prompts),
                checked = prompts?.enabled ?: false,
                enabled = enabled && prompts != null,
                onCheckedChange = { callbacks.onVoicePrompts(VoicePrompts(it, prompts?.languageId ?: 1)) },
            )
            DropdownField(
                label = stringResource(R.string.settings_voice_language),
                options = VOICE_PROMPT_LANGUAGES,
                selected = VOICE_PROMPT_LANGUAGES.firstOrNull { it.first == prompts?.languageId },
                onSelect = { callbacks.onVoicePrompts(VoicePrompts(prompts?.enabled ?: true, it.first)) },
                optionLabel = { it.second },
                enabled = enabled && prompts != null,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            val button = state.shortcutButton
            DropdownField(
                label = stringResource(R.string.settings_shortcut_button),
                // The current action and Disabled are always offered, even when the firmware's
                // supported list omits them, so the user can never get stuck on a choice.
                options = (button?.supportedActions.orEmpty() + listOfNotNull(button?.action) + ButtonAction.DISABLED).distinct(),
                selected = button?.action,
                onSelect = callbacks.onShortcutButton,
                optionLabel = { it.label },
                enabled = enabled && button != null,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            HorizontalDivider()
            NavRow(
                title = stringResource(R.string.settings_rename),
                value = state.info?.customName ?: state.connection.deviceName,
                subtitle = stringResource(R.string.settings_rename_subtitle),
                onClick = { showRename = true },
                enabled = enabled,
            )
            NavRow(title = stringResource(R.string.settings_power_off), icon = Icons.Default.PowerSettingsNew, onClick = { showPowerOff = true }, enabled = enabled)
        }

        SectionCard(title = stringResource(R.string.settings_app)) {
            NavRow(title = stringResource(R.string.settings_automations), subtitle = stringResource(R.string.settings_automations_subtitle), icon = Icons.Default.AutoAwesome, onClick = onOpenAutomations)
            NavRow(title = stringResource(R.string.settings_preferences), subtitle = stringResource(R.string.settings_preferences_subtitle), icon = Icons.Default.Tune, onClick = onOpenPreferences)
            NavRow(title = stringResource(R.string.settings_about), icon = Icons.Default.Info, onClick = onOpenAbout)
        }

        val info = state.info
        if (info != null) {
            Text(
                listOfNotNull(
                    info.modelName ?: info.productName,
                    info.firmwareVersion?.let { "FW $it" },
                    info.serialNumber?.let { "SN $it" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    if (showRename) {
        var name by rememberSaveable { mutableStateOf(state.info?.customName ?: state.connection.deviceName.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(R.string.settings_rename)) },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.settings_rename_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { showRename = false; callbacks.onRename(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (showPowerOff) {
        ConfirmDialog(
            title = stringResource(R.string.settings_power_off),
            text = stringResource(R.string.settings_power_off_confirm),
            confirmLabel = stringResource(R.string.settings_power_off),
            destructive = true,
            onConfirm = callbacks.onPowerOff,
            onDismiss = { showPowerOff = false },
        )
    }
}

@LightDarkPreview
@Composable
private fun MorePreview() {
    EarshotPreview {
        MoreContent(FakeHeadphoneRepository.connectedState(), PaddingValues(0.dp), SettingsCallbacks(), {}, {}, {}, {})
    }
}
