package dev.earshot.ui.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import dev.earshot.ui.common.FieldProgressBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.earshot.R
import dev.earshot.core.MicCheckPhase
import dev.earshot.core.MicCheckRepository
import dev.earshot.core.MicCheckState
import dev.earshot.ui.common.BannerTone
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.EarshotViewModel
import dev.earshot.ui.common.InfoBanner
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.common.SubScreenScaffold
import dev.earshot.ui.common.UiMessenger
import dev.earshot.ui.common.earshotViewModel
import dev.earshot.ui.onboarding.Permissions
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

class MicCheckViewModel(private val mic: MicCheckRepository, messenger: UiMessenger) : EarshotViewModel(messenger) {
    val state: StateFlow<MicCheckState> = mic.state
    private var job: Job? = null

    fun record() { job?.cancel(); job = launchCatching { mic.record(MAX_SECONDS) } }
    fun play() { job?.cancel(); job = launchCatching { mic.play() } }
    fun stop() { job?.cancel(); mic.stop() }
    fun discard() { job?.cancel(); mic.discard() }

    override fun onCleared() { job?.cancel(); mic.stop() }

    companion object { const val MAX_SECONDS = 8 }
}

@Composable
fun MicCheckScreen(onBack: () -> Unit) {
    val vm = earshotViewModel { MicCheckViewModel(it.micCheck, it.messenger) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.record()
    }
    MicCheckContent(
        state = state,
        onBack = onBack,
        onRecord = { if (Permissions.has(context, Permissions.RECORD_AUDIO)) vm.record() else launcher.launch(Permissions.RECORD_AUDIO) },
        onPlay = vm::play,
        onStop = vm::stop,
        onDiscard = vm::discard,
    )
}

@Composable
fun MicCheckContent(
    state: MicCheckState,
    onBack: () -> Unit,
    onRecord: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onDiscard: () -> Unit,
) {
    val recording = state.phase == MicCheckPhase.RECORDING
    val playing = state.phase == MicCheckPhase.PLAYING
    val hasRecording = state.phase == MicCheckPhase.READY || playing

    SubScreenScaffold(title = stringResource(R.string.tools_mic_check), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.mic_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))

            SectionCard {
                LevelMeter(level = if (recording) state.inputLevel else 0f, active = recording)
                Spacer(Modifier.height(12.dp))
                val progress = when {
                    recording -> state.recordedSeconds / MicCheckViewModel.MAX_SECONDS
                    playing && state.recordedSeconds > 0 -> state.playbackPositionSeconds / state.recordedSeconds
                    else -> 0f
                }
                FieldProgressBar(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text(
                    when (state.phase) {
                        MicCheckPhase.IDLE -> stringResource(R.string.mic_idle, MicCheckViewModel.MAX_SECONDS)
                        MicCheckPhase.RECORDING -> stringResource(R.string.mic_recording, state.recordedSeconds)
                        MicCheckPhase.READY -> stringResource(R.string.mic_ready, state.recordedSeconds)
                        MicCheckPhase.PLAYING -> stringResource(R.string.mic_playing, state.playbackPositionSeconds, state.recordedSeconds)
                        MicCheckPhase.NO_PERMISSION -> stringResource(R.string.mic_no_permission)
                        MicCheckPhase.ERROR -> state.message ?: stringResource(R.string.error_generic)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (recording || playing) {
                        Button(shape = MaterialTheme.shapes.medium, onClick = onStop, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                            Icon(Icons.Default.Stop, contentDescription = null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.action_stop))
                        }
                    } else {
                        Button(shape = MaterialTheme.shapes.medium, onClick = onRecord, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                            Icon(Icons.Default.Mic, contentDescription = null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.action_record))
                        }
                        if (hasRecording) {
                            OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = onPlay, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.action_play))
                            }
                            OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = onDiscard, modifier = Modifier.size(52.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_discard))
                            }
                        }
                    }
                }
            }

            state.usedHeadsetMic?.let { headset ->
                InfoBanner(
                    stringResource(if (headset) R.string.mic_used_headset else R.string.mic_used_phone),
                    tone = if (headset) BannerTone.INFO else BannerTone.WARNING,
                    icon = if (headset) Icons.Default.Headset else Icons.Default.PhoneAndroid,
                )
            }
            if (state.phase == MicCheckPhase.NO_PERMISSION) {
                InfoBanner(stringResource(R.string.mic_permission_hint), tone = BannerTone.WARNING)
            }
        }
    }
}

/** Simple peak meter: 24 segments, the top ones tinted towards the error colour. */
@Composable
private fun LevelMeter(level: Float, active: Boolean) {
    val animated by animateFloatAsState(level.coerceIn(0f, 1f), label = "level")
    val low = MaterialTheme.colorScheme.secondary
    val high = MaterialTheme.colorScheme.error
    val off = MaterialTheme.colorScheme.surfaceVariant
    val description = stringResource(R.string.mic_level_description, (animated * 100).toInt())
    Canvas(Modifier.fillMaxWidth().height(28.dp).semantics { contentDescription = description }) {
        val segments = 24
        val gap = 3.dp.toPx()
        val segW = (size.width - gap * (segments - 1)) / segments
        for (i in 0 until segments) {
            val lit = active && (i + 1) / segments.toFloat() <= animated
            val color = when {
                !lit -> off
                i >= segments - 4 -> high
                else -> low
            }
            // Near-square segments, like an LED level meter.
            drawRoundRect(color, topLeft = Offset(i * (segW + gap), 0f), size = Size(segW, size.height), cornerRadius = CornerRadius(1.dp.toPx()))
        }
    }
}

@LightDarkPreview
@Composable
private fun MicCheckPreview() {
    EarshotPreview {
        MicCheckContent(MicCheckState(MicCheckPhase.RECORDING, recordedSeconds = 3.2f, inputLevel = 0.6f, usedHeadsetMic = true), {}, {}, {}, {}, {})
    }
}
