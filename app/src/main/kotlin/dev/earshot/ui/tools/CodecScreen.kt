package dev.earshot.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.earshot.R
import dev.earshot.core.CODEC_SWITCH_UNSUPPORTED_MESSAGE
import dev.earshot.core.CodecOption
import dev.earshot.core.CodecRepository
import dev.earshot.core.CodecSelection
import dev.earshot.core.CodecState
import dev.earshot.core.ConnectionProfile
import dev.earshot.core.PrivilegeState
import dev.earshot.core.fake.FakeCodecRepository
import dev.earshot.ui.common.BannerTone
import dev.earshot.ui.common.DropdownField
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.EarshotViewModel
import dev.earshot.ui.common.InfoBanner
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.NavRow
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.common.SubScreenScaffold
import dev.earshot.ui.common.UiMessenger
import dev.earshot.ui.common.earshotViewModel
import kotlinx.coroutines.flow.StateFlow

class CodecViewModel(private val codec: CodecRepository, messenger: UiMessenger) : EarshotViewModel(messenger) {
    val state: StateFlow<CodecState> = codec.state
    fun refresh() = launchCatching { codec.refresh() }
    fun requestPrivilege() = launchCatching { codec.requestPrivilege() }
    fun select(selection: CodecSelection) = launchCatching { codec.selectCodec(selection); messenger.show(R.string.codec_switched, selection.codecName) }
    fun applyProfile(profile: ConnectionProfile) = launchCatching { codec.applyProfile(profile) }
    fun openDeveloperSettings() = codec.openDeveloperCodecSettings()
}

@Composable
fun CodecScreen(onBack: () -> Unit, onOpenCallAudio: () -> Unit = {}) {
    val vm = earshotViewModel { CodecViewModel(it.codec, it.messenger) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    CodecContent(
        state = state, onBack = onBack, onRefresh = vm::refresh, onRequestPrivilege = vm::requestPrivilege,
        onSelect = vm::select, onProfile = vm::applyProfile, onOpenDeveloperSettings = vm::openDeveloperSettings,
        onOpenCallAudio = onOpenCallAudio,
    )
}

@Composable
fun CodecContent(
    state: CodecState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRequestPrivilege: () -> Unit,
    onSelect: (CodecSelection) -> Unit,
    onProfile: (ConnectionProfile) -> Unit,
    onOpenDeveloperSettings: () -> Unit,
    onOpenCallAudio: () -> Unit = {},
) {
    val privileged = state.privilege == PrivilegeState.GRANTED
    SubScreenScaffold(
        title = stringResource(R.string.tools_codec),
        onBack = onBack,
        actions = {
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh)) }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CurrentCodecCard(state)
            if (state.canSwitch) {
                PrivilegeBanner(state.privilege, onRequestPrivilege, onOpenDeveloperSettings)
            } else {
                // Switching runs in a Shizuku user service, so without Shizuku (running + granted) the
                // picker and profiles are gone. One banner says so, offers the next Shizuku step and
                // keeps the manual Developer options route.
                InfoBanner(
                    if (state.privilege == PrivilegeState.UNAVAILABLE) stringResource(R.string.codec_priv_unavailable) else CODEC_SWITCH_UNSUPPORTED_MESSAGE,
                    tone = BannerTone.WARNING,
                    action = {
                        when (state.privilege) {
                            PrivilegeState.SHIZUKU_PERMISSION_NEEDED -> TextButton(onClick = onRequestPrivilege) { Text(stringResource(R.string.codec_action_grant)) }
                            PrivilegeState.SHIZUKU_NOT_RUNNING -> TextButton(onClick = onRequestPrivilege) { Text(stringResource(R.string.codec_action_retry)) }
                            else -> Unit
                        }
                        TextButton(onClick = onOpenDeveloperSettings) { Text(stringResource(R.string.codec_open_developer_options)) }
                    },
                )
            }
            if (state.lastError != null) InfoBanner(state.lastError, tone = BannerTone.ERROR)

            if (state.canSwitch) SectionCard(title = stringResource(R.string.codec_profiles)) {
                Text(stringResource(R.string.codec_profiles_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { onProfile(ConnectionProfile.BEST_QUALITY) }, enabled = privileged && state.a2dpConnected, shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.codec_best_quality))
                    }
                    FilledTonalButton(onClick = { onProfile(ConnectionProfile.MOST_STABLE) }, enabled = privileged && state.a2dpConnected, shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.codec_most_stable))
                    }
                }
            }

            if (state.canSwitch) CodecPicker(state = state, enabled = privileged && state.a2dpConnected, onSelect = onSelect)

            SectionCard(title = stringResource(R.string.codec_supported)) {
                Text(stringResource(R.string.codec_supported_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                state.options.forEach { option ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (option.isSelectable) Icons.Default.Check else Icons.Default.Close,
                            contentDescription = stringResource(if (option.isSelectable) R.string.codec_supported_both else R.string.codec_supported_phone_only),
                            tint = if (option.isSelectable) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(option.codecName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                listOf(option.sampleRatesHz.joinToString { describeRate(it) }, option.bitDepths.joinToString { "$it bit" }).filter { it.isNotBlank() }.joinToString(" · ") +
                                    (if (option.qualityModes.isNotEmpty()) " · " + option.qualityModes.joinToString() else ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // The "Open Developer options" button lives in the privilege banner above; only the hint stays here.
            Text(stringResource(R.string.codec_developer_options_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // This panel is the A2DP (music) codec; the voice codec used on calls has its own screen.
            SectionCard {
                NavRow(
                    title = stringResource(R.string.codec_open_call_audio),
                    subtitle = stringResource(R.string.tools_call_audio_subtitle),
                    icon = Icons.Default.Call,
                    onClick = onOpenCallAudio,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CurrentCodecCard(state: CodecState) {
    SectionCard(title = stringResource(R.string.codec_current)) {
        val current = state.current
        if (!state.a2dpConnected || current == null) {
            Text(stringResource(R.string.codec_not_streaming), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(current.codecName, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                listOfNotNull(describeRate(current.sampleRateHz), current.bitsPerSample?.let { "$it bit" }, current.channelMode, current.qualityLabel).joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/** The privilege state decides the call to action: install Shizuku / start it / grant / granted. */
@Composable
private fun PrivilegeBanner(privilege: PrivilegeState, onRequest: () -> Unit, onOpenDeveloperSettings: () -> Unit) {
    when (privilege) {
        PrivilegeState.GRANTED -> InfoBanner(stringResource(R.string.codec_priv_granted), icon = Icons.Default.VerifiedUser)
        PrivilegeState.SHIZUKU_PERMISSION_NEEDED -> InfoBanner(
            stringResource(R.string.codec_priv_grant),
            tone = BannerTone.WARNING,
            action = { TextButton(onClick = onRequest) { Text(stringResource(R.string.codec_action_grant)) } },
        )
        PrivilegeState.SHIZUKU_NOT_RUNNING -> InfoBanner(
            stringResource(R.string.codec_priv_not_running),
            tone = BannerTone.WARNING,
            action = { TextButton(onClick = onRequest) { Text(stringResource(R.string.codec_action_retry)) } },
        )
        PrivilegeState.UNAVAILABLE -> InfoBanner(
            stringResource(R.string.codec_priv_unavailable),
            tone = BannerTone.WARNING,
            action = { TextButton(onClick = onOpenDeveloperSettings) { Text(stringResource(R.string.codec_open_developer_options)) } },
        )
    }
}

@Composable
private fun CodecPicker(state: CodecState, enabled: Boolean, onSelect: (CodecSelection) -> Unit) {
    val selectable = state.options.filter { it.isSelectable }
    var codec by remember(selectable) { mutableStateOf<CodecOption?>(selectable.firstOrNull { it.codecName == state.current?.codecName } ?: selectable.firstOrNull()) }
    var rate by remember(codec) { mutableStateOf(codec?.sampleRatesHz?.let { r -> r.firstOrNull { it == state.current?.sampleRateHz } ?: r.maxOrNull() }) }
    var bits by remember(codec) { mutableStateOf(codec?.bitDepths?.maxOrNull()) }
    var quality by remember(codec) { mutableStateOf(codec?.qualityModes?.firstOrNull()) }

    SectionCard(title = stringResource(R.string.codec_picker)) {
        if (selectable.isEmpty()) {
            Text(stringResource(R.string.codec_picker_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        DropdownField(stringResource(R.string.codec_label), selectable, codec, { codec = it }, { it.codecName }, enabled = enabled, modifier = Modifier.padding(vertical = 4.dp))
        val c = codec
        if (c != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                DropdownField(stringResource(R.string.codec_sample_rate), c.sampleRatesHz, rate, { rate = it }, { describeRate(it) }, enabled = enabled && c.sampleRatesHz.size > 1, modifier = Modifier.weight(1f))
                DropdownField(stringResource(R.string.codec_bit_depth), c.bitDepths, bits, { bits = it }, { "$it bit" }, enabled = enabled && c.bitDepths.size > 1, modifier = Modifier.weight(1f))
            }
            if (c.qualityModes.isNotEmpty()) {
                DropdownField(stringResource(R.string.codec_quality), c.qualityModes, quality, { quality = it }, { it }, enabled = enabled, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                shape = MaterialTheme.shapes.medium,
                onClick = { c?.let { onSelect(CodecSelection(it.codecName, rate, bits, quality)) } },
                enabled = enabled && c != null,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.codec_apply)) }
        }
    }
}

@LightDarkPreview
@Composable
private fun CodecPreview() {
    val state by remember { FakeCodecRepository() }.state.collectAsStateWithLifecycle()
    EarshotPreview { CodecContent(state, {}, {}, {}, {}, {}, {}) }
}

@LightDarkPreview
@Composable
private fun CodecReadOnlyPreview() {
    val state by remember { FakeCodecRepository(canSwitch = false) }.state.collectAsStateWithLifecycle()
    EarshotPreview { CodecContent(state, {}, {}, {}, {}, {}, {}) }
}
