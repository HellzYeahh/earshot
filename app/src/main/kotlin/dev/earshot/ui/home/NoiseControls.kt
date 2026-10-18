package dev.earshot.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.core.NoiseSettings
import dev.earshot.core.HeadphoneCapabilities
import dev.earshot.core.SpatialAudio
import dev.earshot.ui.common.SegmentedChoice
import dev.earshot.ui.common.SwitchRow
import dev.earshot.ui.common.ValueSlider
import kotlin.math.roundToInt

/**
 * The [31.10] struct as controls: cancelling slider (10 = quietest), ANC, Wind Block, Immersive
 * Audio. Used by Home, the custom-mode editor and the automation editor. Stateless: every
 * gesture emits a whole [NoiseSettings] so the caller can send it in one SET_GET.
 *
 * [capabilities] hides what the connected model lacks: the ANC switch on the QC45 / QC
 * Headphones (level only), Immersive Audio on those too, and everything but Immersive Audio
 * on open-ear earbuds.
 */
@Composable
fun NoiseControls(
    settings: NoiseSettings,
    enabled: Boolean,
    onChange: (NoiseSettings) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    capabilities: HeadphoneCapabilities = HeadphoneCapabilities(),
) {
    // CNC is audible only while anc=1 and wind=0 (protocol notes); grey the slider otherwise.
    // Without an ANC toggle ANC is either always on (QC45 family) or part of the mode (Crusher: Off mode).
    val ancOn = settings.ancEnabled || capabilities.ancAlwaysOn
    val levelHasEffect = ancOn && !settings.windBlock
    var dragLevel by remember(settings.cancellingLevel) { mutableFloatStateOf(settings.cancellingLevel.toFloat()) }

    Column(modifier.fillMaxWidth()) {
        if (capabilities.noiseCancelling) {
        ValueSlider(
            label = stringResource(R.string.noise_cancelling),
            value = dragLevel,
            valueRange = 0f..10f,
            steps = 9,
            enabled = enabled && levelHasEffect,
            onValueChange = { dragLevel = it },
            onValueChangeFinished = {
                val level = dragLevel.roundToInt().coerceIn(0, 10)
                if (level != settings.cancellingLevel) onChange(settings.copy(cancellingLevel = level))
            },
            valueText = dragLevel.roundToInt().toString(),
            startLabel = stringResource(R.string.noise_label_aware),
            endLabel = stringResource(R.string.noise_label_quiet),
        )
        AnimatedVisibility(visible = !levelHasEffect) {
            Text(
                stringResource(if (!ancOn) R.string.noise_hint_anc_off else R.string.noise_hint_wind_on),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        if (compact) {
            // One switch per row. Side by side (weight 1f each) left the "Noise cancelling" label
            // about 60 dp at phone width, and it wrapped letter by letter next to Wind Block.
            if (capabilities.ancToggle) SwitchRow(
                title = stringResource(R.string.noise_anc),
                checked = settings.ancEnabled,
                enabled = enabled,
                onCheckedChange = { onChange(settings.copy(ancEnabled = it)) },
            )
            if (capabilities.windBlock) SwitchRow(
                title = stringResource(R.string.noise_wind_block),
                checked = settings.windBlock,
                enabled = enabled,
                onCheckedChange = { onChange(settings.copy(windBlock = it)) },
            )
        } else {
            if (capabilities.ancToggle) SwitchRow(
                title = stringResource(R.string.noise_anc),
                subtitle = stringResource(R.string.noise_anc_subtitle),
                checked = settings.ancEnabled,
                enabled = enabled,
                onCheckedChange = { onChange(settings.copy(ancEnabled = it)) },
            )
            if (capabilities.windBlock) SwitchRow(
                title = stringResource(R.string.noise_wind_block),
                subtitle = stringResource(R.string.noise_wind_block_subtitle),
                checked = settings.windBlock,
                enabled = enabled,
                onCheckedChange = { onChange(settings.copy(windBlock = it)) },
            )
        }
        }
        if (!capabilities.spatialAudio) return@Column
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.noise_immersive),
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.padding(bottom = 6.dp),
        )
        SegmentedChoice(
            options = SpatialAudio.entries,
            selected = settings.spatialAudio,
            onSelect = { onChange(settings.copy(spatialAudio = it)) },
            label = { it.label },
            enabled = enabled,
        )
    }
}
