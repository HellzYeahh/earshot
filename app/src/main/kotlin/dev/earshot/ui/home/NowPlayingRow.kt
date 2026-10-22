package dev.earshot.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.core.Playback
import dev.earshot.core.PlaybackState
import dev.earshot.core.TransportControl
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.FieldSlider
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.theme.EarshotTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** How long the volume slider waits after the last movement before writing `[5.5]`. */
private const val VOLUME_DEBOUNCE_MS = 200L

/**
 * Compact transport + volume strip for Home. Shown only when the headphones report a play state
 * or a volume; each transport button appears only if `[5.3]` lists it as supported.
 */
@Composable
fun NowPlayingRow(
    playback: Playback?,
    volume: Int?,
    volumeMax: Int?,
    supportedControls: Set<TransportControl>,
    enabled: Boolean,
    onTransport: (TransportControl) -> Unit,
    onVolume: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playing = playback?.state == PlaybackState.PLAYING || playback?.state == PlaybackState.FAST_FORWARDING || playback?.state == PlaybackState.REWINDING
    SectionCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.now_playing_title).uppercase(), style = MaterialTheme.typography.titleMedium)
                Text(
                    playbackLabel(playback?.state).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (TransportControl.PREVIOUS in supportedControls) {
                IconButton(onClick = { onTransport(TransportControl.PREVIOUS) }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = stringResource(R.string.transport_previous))
                }
            }
            // One toggle covers play and pause; it needs whichever command the current state calls for.
            val toggle = if (playing) TransportControl.PAUSE else TransportControl.PLAY
            if (toggle in supportedControls) {
                FilledIconButton(
                    onClick = { onTransport(toggle) },
                    enabled = enabled,
                    shape = MaterialTheme.shapes.medium,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = EarshotTheme.colors.control,
                        contentColor = EarshotTheme.colors.onControl,
                    ),
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(if (playing) R.string.transport_pause else R.string.transport_play),
                    )
                }
            }
            if (TransportControl.NEXT in supportedControls) {
                IconButton(onClick = { onTransport(TransportControl.NEXT) }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipNext, contentDescription = stringResource(R.string.transport_next))
                }
            }
        }
        if (volume != null) {
            VolumeSlider(volume = volume, volumeMax = volumeMax ?: DEFAULT_VOLUME_MAX, enabled = enabled, onVolume = onVolume)
        }
    }
}

/**
 * Volume with a 200 ms debounce: the slider tracks the finger immediately, and `[5.5]` is written
 * once the finger has rested, so a long drag doesn't flood the RFCOMM channel. Pushes from the
 * headphones (`[9.2]` volume notifications) move the thumb whenever the user isn't dragging.
 */
@Composable
private fun VolumeSlider(volume: Int, volumeMax: Int, enabled: Boolean, onVolume: (Int) -> Unit) {
    val max = volumeMax.coerceAtLeast(1)
    var local by remember { mutableIntStateOf(volume) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(volume) { if (!dragging) local = volume }
    LaunchedEffect(local) {
        if (local != volume) {
            delay(VOLUME_DEBOUNCE_MS)
            onVolume(local)
        }
    }
    val valueText = stringResource(R.string.volume_value, local, max)
    val label = stringResource(R.string.volume_label)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.VolumeDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        FieldSlider(
            value = local.toFloat(),
            onValueChange = { dragging = true; local = it.roundToInt().coerceIn(0, max) },
            onValueChangeFinished = { dragging = false },
            valueRange = 0f..max.toFloat(),
            enabled = enabled,
            modifier = Modifier.weight(1f).semantics { stateDescription = "$label: $valueText" },
        )
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Default.VolumeUp, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(valueText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun playbackLabel(state: PlaybackState?): String = stringResource(
    when (state) {
        PlaybackState.PLAYING -> R.string.playback_playing
        PlaybackState.PAUSED -> R.string.playback_paused
        PlaybackState.STOPPED -> R.string.playback_stopped
        PlaybackState.FAST_FORWARDING -> R.string.playback_fast_forwarding
        PlaybackState.REWINDING -> R.string.playback_rewinding
        null -> R.string.playback_unknown
    },
)

/** `[5.5]` reports 31 steps on fw 10.12; used only if the maximum hasn't been read. */
private const val DEFAULT_VOLUME_MAX = 31

@LightDarkPreview
@Composable
private fun NowPlayingPreview() {
    EarshotPreview {
        Column {
            NowPlayingRow(
                playback = Playback(PlaybackState.PLAYING), volume = 22, volumeMax = 31,
                supportedControls = TransportControl.entries.toSet(), enabled = true, onTransport = {}, onVolume = {},
            )
            Spacer(Modifier.size(12.dp))
            NowPlayingRow(
                playback = Playback(PlaybackState.PAUSED), volume = null, volumeMax = null,
                supportedControls = setOf(TransportControl.PLAY, TransportControl.PAUSE), enabled = false, onTransport = {}, onVolume = {},
            )
        }
    }
}
