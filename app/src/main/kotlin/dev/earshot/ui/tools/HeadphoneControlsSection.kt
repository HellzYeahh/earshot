package dev.earshot.ui.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PowerSettingsNew
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
import dev.earshot.R
import dev.earshot.core.HeadphoneState
import dev.earshot.core.VoicePrompts
import dev.earshot.extras.HeadphoneLabels
import dev.earshot.ui.common.ConfirmDialog
import dev.earshot.ui.common.DropdownField
import dev.earshot.ui.common.NavRow
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.common.SwitchRow
import dev.earshot.ui.common.rememberSettingsLoaded
import dev.earshot.ui.more.VOICE_PROMPT_LANGUAGES

/** The headphone commands behind the Controls screen's "Headphone controls" (a `SettingsViewModel`). */
data class HeadphoneControlsCallbacks(
    val onTouchControls: (Boolean) -> Unit = {},
    val onVoicePrompts: (VoicePrompts) -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onPowerOff: () -> Unit = {},
)

/**
 * Touch controls, voice prompts, devices, rename and power off, on the Controls screen (they
 * lived on More, then on the Tools tab). Same rules as before: rows are disabled while
 * disconnected or busy, and once the settings have loaded a row whose value is still null is
 * hidden, since this model doesn't have it. [touchModifier] goes on the Touch controls row, so
 * the detect sheet's hint can scroll to it and highlight it.
 */
@Composable
fun HeadphoneControlsSection(
    state: HeadphoneState,
    callbacks: HeadphoneControlsCallbacks,
    onOpenDevices: () -> Unit,
    modifier: Modifier = Modifier,
    touchModifier: Modifier = Modifier,
) {
    val enabled = state.isConnected && !state.isBusy
    var showRename by rememberSaveable { mutableStateOf(false) }
    var showPowerOff by rememberSaveable { mutableStateOf(false) }
    val buds = HeadphoneLabels.isEarbuds(HeadphoneLabels.formFactor(state))
    val loaded = rememberSettingsLoaded(state)
    fun has(value: Any?) = !loaded || value != null
    val prompts = state.voicePrompts

    SectionCard(modifier = modifier, title = stringResource(if (buds) R.string.tools_earbud_controls else R.string.tools_headphone_controls)) {
        // [1.34]: wire bit 1 = touch on (the name "DisableCaptouch" is misleading); the state is already "enabled".
        if (has(state.touchControlsEnabled)) {
            SwitchRow(
                stringResource(R.string.settings_touch_controls),
                state.touchControlsEnabled ?: true,
                callbacks.onTouchControls,
                subtitle = stringResource(if (buds) R.string.settings_touch_controls_subtitle_buds else R.string.settings_touch_controls_subtitle),
                enabled = enabled && state.touchControlsEnabled != null,
                modifier = touchModifier,
            )
            HorizontalDivider()
        }
        if (has(prompts)) {
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
            HorizontalDivider()
        }
        NavRow(
            title = stringResource(R.string.settings_devices),
            value = state.pairedDevices.firstOrNull { it.isActiveSource }?.name,
            icon = Icons.Default.Devices,
            onClick = onOpenDevices,
        )
        NavRow(
            title = stringResource(if (buds) R.string.settings_rename_buds else R.string.settings_rename),
            value = HeadphoneLabels.displayName(state),
            subtitle = stringResource(R.string.settings_rename_subtitle),
            onClick = { showRename = true },
            enabled = enabled,
        )
        NavRow(title = stringResource(R.string.settings_power_off), icon = Icons.Default.PowerSettingsNew, onClick = { showPowerOff = true }, enabled = enabled)
    }

    if (showRename) {
        var name by rememberSaveable { mutableStateOf(HeadphoneLabels.displayName(state).orEmpty()) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(if (buds) R.string.settings_rename_buds else R.string.settings_rename)) },
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
            text = stringResource(if (buds) R.string.settings_power_off_confirm_buds else R.string.settings_power_off_confirm),
            confirmLabel = stringResource(R.string.settings_power_off),
            destructive = true,
            onConfirm = callbacks.onPowerOff,
            onDismiss = { showPowerOff = false },
        )
    }
}
