package dev.earshot.ui.tools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.earshot.R
import dev.earshot.core.CallAudioPreferences
import dev.earshot.core.CallAudioRepository
import dev.earshot.core.CallAudioState
import dev.earshot.core.fake.FakeCallAudioRepository
import dev.earshot.ui.common.BannerTone
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.EarshotViewModel
import dev.earshot.ui.common.InfoBanner
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.common.SubScreenScaffold
import dev.earshot.ui.common.SwitchRow
import dev.earshot.ui.common.UiMessenger
import dev.earshot.ui.common.ValueSlider
import dev.earshot.ui.common.earshotViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

class CallAudioViewModel(private val callAudio: CallAudioRepository, messenger: UiMessenger) : EarshotViewModel(messenger) {
    val state: StateFlow<CallAudioState> = callAudio.state
    val preferences: StateFlow<CallAudioPreferences> = callAudio.preferences

    fun refresh() = launchCatching { callAudio.refresh() }
    fun setPreferences(p: CallAudioPreferences) = launchCatching { callAudio.setPreferences(p) }
    fun boostNow() = launchCatching {
        callAudio.boostNow()
        messenger.show(R.string.call_boosted, callAudio.preferences.value.boostPercent)
    }
    fun setAbsoluteVolumeDisabled(disabled: Boolean) = launchCatching { callAudio.setAbsoluteVolumeDisabled(disabled) }
}

@Composable
fun CallAudioScreen(onBack: () -> Unit) {
    val vm = earshotViewModel { CallAudioViewModel(it.callAudio, it.messenger) }
    val state by vm.state.collectAsStateWithLifecycle()
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    // The route and codec are only known while a call is up; re-read on entry so a call that
    // started before the screen opened shows its codec immediately.
    LaunchedEffect(Unit) { vm.refresh() }
    CallAudioContent(
        state = state,
        prefs = prefs,
        onBack = onBack,
        onRefresh = vm::refresh,
        onBoostNow = vm::boostNow,
        onPreferences = vm::setPreferences,
        onAbsoluteVolumeDisabled = vm::setAbsoluteVolumeDisabled,
    )
}

@Composable
fun CallAudioContent(
    state: CallAudioState,
    prefs: CallAudioPreferences,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onBoostNow: () -> Unit,
    onPreferences: (CallAudioPreferences) -> Unit,
    onAbsoluteVolumeDisabled: (Boolean) -> Unit,
) {
    SubScreenScaffold(
        title = stringResource(R.string.tools_call_audio),
        onBack = onBack,
        actions = {
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh)) }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.inCall) LiveCallCard(state, onBoostNow) else IdleCard(state)
            if (state.lastError != null) {
                Text(state.lastError, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 4.dp))
            }
            BoostSettingsCard(prefs, onPreferences)
            AdvancedCard(state, onAbsoluteVolumeDisabled)
            HonestNoteCard()
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LiveCallCard(state: CallAudioState, onBoostNow: () -> Unit) {
    SectionCard(title = stringResource(R.string.call_live_title)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Icon(
                if (state.onHeadphones) Icons.Default.Call else Icons.Default.PhoneDisabled,
                contentDescription = null,
                tint = if (state.onHeadphones) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                if (state.onHeadphones) stringResource(R.string.call_on_call_via, state.routedDeviceName ?: stringResource(R.string.call_device_fallback))
                else stringResource(R.string.call_on_call_not_headphones),
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.VolumeUp, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.call_volume), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.call_volume_value, state.callVolume, state.callVolumeMax),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { if (state.callVolumeMax > 0) (state.callVolume.toFloat() / state.callVolumeMax).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))
        val codec = describeCallCodec(state)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CodecChip(codec)
                if (codec.narrowband) {
                    Text(
                        stringResource(R.string.call_narrowband_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                    )
                } else if (state.callCodec == null) {
                    Text(
                        stringResource(R.string.call_codec_unknown_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onBoostNow, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.call_boost_now))
            }
        }
    }
}

@Composable
private fun IdleCard(state: CallAudioState) {
    SectionCard(title = stringResource(R.string.call_idle_title)) {
        if (state.codecReadAt == null) {
            Text(stringResource(R.string.call_no_codec_yet), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val codec = describeCallCodec(state)
            Text(stringResource(R.string.call_last_codec, codec.label), style = MaterialTheme.typography.bodyLarge)
            if (codec.narrowband) {
                Text(
                    stringResource(R.string.call_narrowband_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun BoostSettingsCard(prefs: CallAudioPreferences, onPreferences: (CallAudioPreferences) -> Unit) {
    SectionCard(title = stringResource(R.string.call_boost_section)) {
        SwitchRow(
            title = stringResource(R.string.call_boost_enabled),
            subtitle = stringResource(R.string.call_boost_enabled_subtitle),
            checked = prefs.boostEnabled,
            onCheckedChange = { onPreferences(prefs.copy(boostEnabled = it)) },
        )
        // "Boost now" uses the same target, so the slider stays live even with auto-boost off.
        var drag by remember(prefs.boostPercent) { mutableFloatStateOf(prefs.boostPercent.toFloat()) }
        ValueSlider(
            label = stringResource(R.string.call_boost_target),
            value = drag,
            valueRange = CallAudioPreferences.MIN_BOOST_PERCENT.toFloat()..100f,
            steps = (100 - CallAudioPreferences.MIN_BOOST_PERCENT) / 10 - 1,
            valueText = "${drag.roundToInt()} %",
            onValueChange = { drag = it },
            onValueChangeFinished = { onPreferences(prefs.copy(boostPercent = drag.roundToInt())) },
        )
        SwitchRow(
            title = stringResource(R.string.call_boost_raise_headset),
            subtitle = stringResource(R.string.call_boost_raise_headset_subtitle),
            checked = prefs.alsoRaiseHeadset,
            onCheckedChange = { onPreferences(prefs.copy(alsoRaiseHeadset = it)) },
        )
    }
}

@Composable
private fun AdvancedCard(state: CallAudioState, onAbsoluteVolumeDisabled: (Boolean) -> Unit) {
    SectionCard(title = stringResource(R.string.call_advanced)) {
        // null means Shizuku can't read the switch, so it can't write it either.
        val readable = state.absoluteVolumeDisabled != null
        SwitchRow(
            title = stringResource(R.string.call_abs_volume),
            subtitle = if (readable) null else stringResource(R.string.call_abs_volume_needs_shizuku),
            checked = state.absoluteVolumeDisabled == true,
            enabled = readable,
            onCheckedChange = onAbsoluteVolumeDisabled,
        )
        if (state.absoluteVolumeNeedsReconnect) {
            InfoBanner(stringResource(R.string.call_abs_volume_reconnect), tone = BannerTone.WARNING, modifier = Modifier.padding(vertical = 4.dp))
        }
        Text(
            stringResource(R.string.call_abs_volume_caveat),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun HonestNoteCard() {
    SectionCard(title = stringResource(R.string.call_honest_title)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp).size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.call_honest_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Label for the voice codec plus whether it is the narrowband (CVSD) case worth flagging. */
private data class CallCodecLabel(val label: String, val narrowband: Boolean)

@Composable
private fun describeCallCodec(state: CallAudioState): CallCodecLabel {
    val name = state.callCodec
    val narrowband = state.wideband == false
    val label = when {
        name == null -> stringResource(R.string.call_codec_unknown)
        name.equals("CVSD", ignoreCase = true) -> stringResource(R.string.call_codec_cvsd)
        name.equals("mSBC", ignoreCase = true) -> stringResource(R.string.call_codec_msbc)
        name.equals("LC3 (LE Audio)", ignoreCase = true) -> stringResource(R.string.call_codec_lc3_le)
        name.equals("LC3", ignoreCase = true) -> stringResource(R.string.call_codec_lc3_swb)
        name.equals("aptX SWB", ignoreCase = true) -> stringResource(R.string.call_codec_aptx_swb)
        // Something the parser named but this screen doesn't know: show it with its rate if we have one.
        state.callSampleRateHz != null -> "$name · ${describeRate(state.callSampleRateHz)}"
        else -> name
    }
    return CallCodecLabel(label, narrowband)
}

@Composable
private fun CodecChip(codec: CallCodecLabel) {
    val (container, content) =
        if (codec.narrowband) MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (codec.narrowband) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            codec.label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@LightDarkPreview
@Composable
private fun CallAudioInCallPreview() {
    val repo = remember { FakeCallAudioRepository() }
    val state by repo.state.collectAsStateWithLifecycle()
    val prefs by repo.preferences.collectAsStateWithLifecycle()
    EarshotPreview { CallAudioContent(state, prefs, {}, {}, {}, {}, {}) }
}

@LightDarkPreview
@Composable
private fun CallAudioIdleNoShizukuPreview() {
    val repo = remember { FakeCallAudioRepository(inCall = false, shizukuReady = false) }
    val state by repo.state.collectAsStateWithLifecycle()
    val prefs by repo.preferences.collectAsStateWithLifecycle()
    EarshotPreview { CallAudioContent(state, prefs, {}, {}, {}, {}, {}) }
}

@LightDarkPreview
@Composable
private fun CallAudioNarrowbandPreview() {
    val state = CallAudioState(
        inCall = true, onHeadphones = true, routedDeviceName = "Bose QC Ultra Headphones",
        callVolume = 2, callVolumeMax = 7,
        callCodec = "CVSD", wideband = false, callSampleRateHz = 8_000, codecReadAt = 0L,
        absoluteVolumeDisabled = true, absoluteVolumeNeedsReconnect = true,
        lastError = "Changing absolute volume needs Shizuku: start it and grant Earshot.",
    )
    EarshotPreview { CallAudioContent(state, CallAudioPreferences(boostPercent = 80), {}, {}, {}, {}, {}) }
}
