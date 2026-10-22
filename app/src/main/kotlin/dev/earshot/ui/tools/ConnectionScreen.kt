@file:OptIn(ExperimentalLayoutApi::class)

package dev.earshot.ui.tools

import androidx.compose.foundation.BorderStroke
import dev.earshot.ui.theme.EarshotTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.earshot.R
import dev.earshot.core.DeviceProfiles
import dev.earshot.core.HeadphoneState
import dev.earshot.core.LeAudioPreference
import dev.earshot.core.PairedDevice
import dev.earshot.core.fake.FakeHeadphoneRepository
import dev.earshot.ui.LocalUiDependencies
import dev.earshot.ui.common.BannerTone
import dev.earshot.ui.common.ConfirmDialog
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.InfoBanner
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.NavRow
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.common.SubScreenScaffold
import dev.earshot.ui.common.SwitchRow
import dev.earshot.ui.common.ValueSlider
import dev.earshot.ui.common.earshotViewModel
import kotlin.math.roundToInt

/** Latency to start from when the headset reports none: the QC Ultra 2's range and value (fw 10.12). */
private val TRY_LATENCY_RANGE = 0..520
private const val TRY_LATENCY_MS = 210

/** Tools → Connection & latency: LE Audio, codec/call shortcuts, `[5.7]` latency and tips. */
@Composable
fun ConnectionScreen(onBack: () -> Unit, onOpenCodec: () -> Unit, onOpenCallAudio: () -> Unit) {
    val vm = earshotViewModel { ConnectionViewModel(it.headphones, it.messenger) }
    val deps = LocalUiDependencies.current
    val state by vm.state.collectAsStateWithLifecycle()
    val codec by deps.codec.state.collectAsStateWithLifecycle()
    val restarting by vm.restarting.collectAsStateWithLifecycle()
    val acknowledged by vm.latencyAcknowledged.collectAsStateWithLifecycle()
    val original by vm.originalLatencyMs.collectAsStateWithLifecycle()
    ConnectionContent(
        state = state,
        codecSummary = codec.current?.let { "${it.codecName} · ${describeRate(it.sampleRateHz)}" },
        restarting = restarting,
        latencyAcknowledged = acknowledged,
        originalLatencyMs = original,
        onBack = onBack,
        onOpenCodec = onOpenCodec,
        onOpenCallAudio = onOpenCallAudio,
        onSetLeAudio = vm::setLeAudio,
        onSetLatency = vm::setLatency,
        onAcknowledgeLatency = vm::acknowledgeLatency,
    )
}

@Composable
fun ConnectionContent(
    state: HeadphoneState,
    codecSummary: String?,
    restarting: Boolean,
    latencyAcknowledged: Boolean,
    originalLatencyMs: Int?,
    onBack: () -> Unit,
    onOpenCodec: () -> Unit,
    onOpenCallAudio: () -> Unit,
    onSetLeAudio: (Boolean) -> Unit,
    onSetLatency: (Int) -> Unit,
    onAcknowledgeLatency: () -> Unit,
) {
    val enabled = state.isConnected && !state.isBusy && !restarting
    SubScreenScaffold(title = stringResource(R.string.tools_connection), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AnimatedVisibility(visible = restarting) { RestartingBanner() }
            if (!state.isConnected && !restarting) InfoBanner(stringResource(R.string.settings_not_connected))
            // The LE Audio switch is a Bose BMAP function; the Crusher (state.crusher) has none.
            // Shown for every headset: Tools let anyone try a setting, a refusal becomes a message.
            LeAudioCard(state, enabled, onSetLeAudio)
            SectionCard {
                NavRow(
                    title = stringResource(R.string.conn_codec_row),
                    value = codecSummary,
                    subtitle = if (codecSummary == null) stringResource(R.string.codec_not_streaming) else null,
                    icon = Icons.Default.SettingsInputAntenna,
                    onClick = onOpenCodec,
                )
                NavRow(
                    title = stringResource(R.string.tools_call_audio),
                    subtitle = stringResource(R.string.tools_call_audio_subtitle),
                    icon = Icons.Default.Call,
                    onClick = onOpenCallAudio,
                )
            }
            LatencyCard(state, enabled, latencyAcknowledged, originalLatencyMs, onSetLatency, onAcknowledgeLatency)
            TipsCard()
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun RestartingBanner() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.conn_le_restarting), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---- LE Audio ------------------------------------------------------------------------------

@Composable
private fun LeAudioCard(state: HeadphoneState, enabled: Boolean, onSetLeAudio: (Boolean) -> Unit) {
    var pending by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val supported = state.leAudioSupported
    val context = LocalContext.current
    val phone = remember(context) { PhoneLeAudio.support(context) }
    SectionCard(title = stringResource(R.string.conn_le_audio)) {
        StatusLine(
            text = when (supported) {
                true -> stringResource(R.string.conn_le_supported)
                false -> stringResource(R.string.conn_le_not_supported)
                null -> stringResource(R.string.conn_le_support_unknown)
            },
            icon = when (supported) {
                true -> Icons.Default.CheckCircle
                false -> Icons.Outlined.Cancel
                null -> Icons.Default.HelpOutline
            },
            positive = supported == true,
        )
        StatusLine(
            text = stringResource(
                when (phone) {
                    true -> R.string.conn_le_phone_supported
                    false -> R.string.conn_le_phone_not_supported
                    null -> R.string.conn_le_phone_unknown
                },
            ),
            icon = when (phone) {
                true -> Icons.Default.CheckCircle
                false -> Icons.Outlined.Cancel
                null -> Icons.Default.HelpOutline
            },
            positive = phone == true,
        )
        StatusLine(text = preferenceLabel(state.lePreference), icon = Icons.Default.HelpOutline, positive = state.lePreference?.preferred == true)
        // Always usable while connected: on headphones that report no support (or no readable
        // switch) the write is still offered, behind a caution dialog. Firmware without LE Audio
        // refuses or ignores it and the result check after the restart says so.
        val risks = leAudioRisks(supported, state.leAudioEnabled != null, phone)
        SwitchRow(
            title = stringResource(R.string.conn_le_enabled_switch),
            subtitle = stringResource(if (risks.isEmpty()) R.string.conn_le_enabled_subtitle else R.string.conn_le_enabled_subtitle_risky),
            checked = state.leAudioEnabled ?: false,
            // The switch asks first: the headphones reboot, so an accidental flick costs ~20 s of silence.
            onCheckedChange = { pending = it },
            enabled = enabled,
        )
        if (state.pairedDevices.isNotEmpty()) {
            Text(
                stringResource(R.string.conn_le_devices),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
            state.pairedDevices.forEach { device -> DeviceProfileRow(device) }
        }
    }
    val target = pending
    if (target != null) {
        val risks = leAudioRisks(supported, state.leAudioEnabled != null, phone)
        if (risks.isEmpty()) {
            ConfirmDialog(
                title = stringResource(R.string.conn_le_confirm_title, stringResource(if (target) R.string.conn_on else R.string.conn_off)),
                text = stringResource(R.string.conn_le_confirm_body),
                confirmLabel = stringResource(R.string.conn_le_confirm_action),
                onConfirm = { onSetLeAudio(target) },
                onDismiss = { pending = null },
            )
        } else {
            // map is inline, so stringResource may be called in it (joinToString's lambda is not).
            val reasons = risks.map { "• " + stringResource(it) }.joinToString("\n")
            ConfirmDialog(
                title = stringResource(R.string.conn_le_risky_title),
                text = stringResource(R.string.conn_le_risky_body, reasons),
                confirmLabel = stringResource(R.string.conn_le_risky_action),
                onConfirm = { onSetLeAudio(target) },
                onDismiss = { pending = null },
                destructive = true,
            )
        }
    }
}

/** Why switching LE Audio may not work here, as string resources; empty when nothing speaks against it. */
private fun leAudioRisks(headphonesSupport: Boolean?, switchReadable: Boolean, phoneSupport: Boolean?): List<Int> = buildList {
    if (headphonesSupport == false) add(R.string.conn_le_reason_headphones)
    if (!switchReadable) add(R.string.conn_le_reason_no_switch)
    if (phoneSupport == false) add(R.string.conn_le_reason_phone)
}

@Composable
private fun preferenceLabel(pref: LeAudioPreference?): String = stringResource(
    when {
        pref == null -> R.string.conn_le_pref_unknown
        pref.prohibited -> R.string.conn_le_pref_prohibited
        !pref.supported -> R.string.conn_le_pref_unsupported
        pref.preferred -> R.string.conn_le_pref_preferred
        else -> R.string.conn_le_pref_not_preferred
    },
)

@Composable
private fun StatusLine(text: String, icon: ImageVector, positive: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DeviceProfileRow(device: PairedDevice) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 40.dp).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(device.name ?: device.address, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (device.isThisPhone) {
                Text(stringResource(R.string.devices_this_phone), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        ProfileChip(device.profiles, highlight = device.profiles?.usesLeAudio == true)
    }
}

/** "LE Audio" / "Classic (A2DP/HFP)" etc. from `[4.6]`; also used by the Devices screen. */
@Composable
fun profileLabel(profiles: DeviceProfiles?): String = when {
    profiles == null -> stringResource(R.string.conn_profile_unknown)
    profiles.usesLeAudio -> stringResource(R.string.conn_profile_le)
    profiles.usesClassic -> stringResource(R.string.conn_profile_classic_named, profiles.classicConnected.sortedBy { it.ordinal }.joinToString("/") { it.name })
    profiles.leAudioPaired && profiles.classicPaired.isEmpty() -> stringResource(R.string.conn_profile_le_paired)
    profiles.classicPaired.isNotEmpty() -> stringResource(R.string.conn_profile_classic_named, profiles.classicPaired.sortedBy { it.ordinal }.joinToString("/") { it.name })
    else -> stringResource(R.string.conn_profile_unknown)
}

@Composable
fun ProfileChip(profiles: DeviceProfiles?, highlight: Boolean, modifier: Modifier = Modifier) {
    // primaryContainer is Field's orange in both modes, so an LE Audio link lights up like a lamp.
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (highlight) null else BorderStroke(1.dp, EarshotTheme.colors.hairline),
        modifier = modifier,
    ) {
        Text(profileLabel(profiles), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

// ---- Latency -------------------------------------------------------------------------------

@Composable
private fun LatencyCard(
    state: HeadphoneState,
    enabled: Boolean,
    acknowledged: Boolean,
    originalMs: Int?,
    onSetLatency: (Int) -> Unit,
    onAcknowledge: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val range = state.latencyRangeMs
    val current = state.latencyMs
    SectionCard(title = stringResource(R.string.conn_latency)) {
        Text(
            if (current != null) stringResource(R.string.conn_latency_reported, current) else stringResource(R.string.conn_latency_unknown),
            style = MaterialTheme.typography.headlineSmall,
        )
        if (range != null) {
            Text(
                stringResource(R.string.conn_latency_range, range.first, range.last),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { expanded = !expanded }.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Science, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.conn_experimental), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                InfoBanner(stringResource(R.string.conn_latency_experimental_body), tone = BannerTone.WARNING, icon = Icons.Default.Warning)
                if (!acknowledged) {
                    TextButton(onClick = onAcknowledge, modifier = Modifier.padding(top = 4.dp)) {
                        Text(stringResource(R.string.conn_latency_acknowledge))
                    }
                }
                // A headset that reports no latency still gets the slider (Bose's range) to try.
                run {
                    LatencySlider(
                        current = current ?: TRY_LATENCY_MS,
                        range = range ?: TRY_LATENCY_RANGE,
                        enabled = enabled && acknowledged,
                        onCommit = onSetLatency,
                    )
                    if (originalMs != null) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.conn_latency_original, originalMs),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onSetLatency(originalMs) }, enabled = enabled && acknowledged && originalMs != current) {
                                Text(stringResource(R.string.conn_latency_reset, originalMs))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Tracks the drag locally and writes `[5.7]` once on release; the echo from the state moves the thumb. */
@Composable
private fun LatencySlider(current: Int, range: IntRange, enabled: Boolean, onCommit: (Int) -> Unit) {
    var local by remember(current) { mutableFloatStateOf(current.toFloat()) }
    ValueSlider(
        label = stringResource(R.string.conn_latency_slider),
        value = local,
        valueRange = range.first.toFloat()..range.last.toFloat(),
        onValueChange = { local = it },
        onValueChangeFinished = { onCommit(local.roundToInt().coerceIn(range)) },
        valueText = stringResource(R.string.conn_latency_ms, local.roundToInt()),
        enabled = enabled,
        startLabel = stringResource(R.string.conn_latency_ms, range.first),
        endLabel = stringResource(R.string.conn_latency_ms, range.last),
        modifier = Modifier.padding(top = 4.dp),
    )
}

// ---- Tips ----------------------------------------------------------------------------------

@Composable
private fun TipsCard() {
    SectionCard(title = stringResource(R.string.conn_tips_title)) {
        listOf(R.string.conn_tip_immersive, R.string.conn_tip_pro_eq, R.string.conn_tip_le_audio, R.string.conn_tip_codec).forEach { id ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(id), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@LightDarkPreview
@Composable
private fun ConnectionPreview() {
    EarshotPreview {
        ConnectionContent(
            state = FakeHeadphoneRepository.connectedState(),
            codecSummary = "AAC · 44.1 kHz",
            restarting = false,
            latencyAcknowledged = true,
            originalLatencyMs = 210,
            onBack = {}, onOpenCodec = {}, onOpenCallAudio = {}, onSetLeAudio = {}, onSetLatency = {}, onAcknowledgeLatency = {},
        )
    }
}

@LightDarkPreview
@Composable
private fun ConnectionRestartingPreview() {
    EarshotPreview {
        ConnectionContent(
            state = FakeHeadphoneRepository.connectedState().copy(leAudioSupported = false, latencyMs = null, latencyRangeMs = null),
            codecSummary = null,
            restarting = true,
            latencyAcknowledged = false,
            originalLatencyMs = null,
            onBack = {}, onOpenCodec = {}, onOpenCallAudio = {}, onSetLeAudio = {}, onSetLatency = {}, onAcknowledgeLatency = {},
        )
    }
}
