package dev.earshot.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.foundation.layout.padding
import dev.earshot.R
import dev.earshot.core.CrusherBass
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.common.SegmentedChoice
import dev.earshot.ui.common.SwitchRow
import dev.earshot.ui.common.ValueSlider
import kotlin.math.roundToInt

/**
 * The Skullcandy Crusher's haptic bass: on/off, how strong, and how it feels (high / medium / low).
 * Only shown for headphones that report [CrusherBass]. Stateless: every gesture calls [onChange]
 * with just the attribute that changed (the rest stays as it is on the headphones).
 */
@Composable
fun CrusherCard(
    bass: CrusherBass,
    enabled: Boolean,
    onChange: (active: Boolean?, intensity: Int?, mode: Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = stringResource(R.string.crusher_title), modifier = modifier) {
        SwitchRow(
            title = stringResource(R.string.crusher_active),
            subtitle = stringResource(R.string.crusher_active_subtitle),
            checked = bass.active,
            enabled = enabled,
            onCheckedChange = { onChange(it, null, null) },
        )
        var drag by remember(bass.intensity) { mutableFloatStateOf(bass.intensity.toFloat()) }
        ValueSlider(
            label = stringResource(R.string.crusher_intensity),
            value = drag,
            valueRange = 0f..bass.maxIntensity.coerceAtLeast(1).toFloat(),
            steps = (bass.maxIntensity - 1).coerceAtLeast(0),
            enabled = enabled && bass.active && !bass.intensityReadOnly,
            onValueChange = { drag = it },
            onValueChangeFinished = {
                val level = drag.roundToInt().coerceIn(0, bass.maxIntensity)
                if (level != bass.intensity) onChange(null, level, null)
            },
            valueText = drag.roundToInt().toString(),
        )
        if (bass.intensityReadOnly) {
            Text(
                stringResource(R.string.crusher_dial_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        val feel = bass.mode
        if (feel != null && bass.modeCount > 0) {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(
                    stringResource(R.string.crusher_feel),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                // 0 = high, 1 = medium, 2 = low (the headphones' own order).
                val options = (0 until bass.modeCount.coerceAtMost(3)).toList()
                SegmentedChoice(
                    options = options,
                    selected = feel,
                    onSelect = { onChange(null, null, it) },
                    label = { stringResource(feelLabel(it)) },
                    enabled = enabled && bass.active,
                )
            }
        }
    }
}

private fun feelLabel(mode: Int): Int = when (mode) {
    0 -> R.string.crusher_feel_high
    1 -> R.string.crusher_feel_medium
    else -> R.string.crusher_feel_low
}
