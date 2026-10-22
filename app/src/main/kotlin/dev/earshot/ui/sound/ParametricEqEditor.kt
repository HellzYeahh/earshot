package dev.earshot.ui.sound

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.dsp.FilterType
import dev.earshot.dsp.ParametricBand
import dev.earshot.ui.common.FieldSwitch
import dev.earshot.ui.common.SegmentedChoice
import dev.earshot.ui.common.ValueSlider
import dev.earshot.ui.theme.EarshotTheme
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

private const val MAX_BANDS = 12

/** Per-band frequency / gain / Q editors with shelf types, add and remove. */
@Composable
fun ParametricEqEditor(
    bands: List<ParametricBand>,
    enabled: Boolean,
    onBandsChange: (List<ParametricBand>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        bands.forEachIndexed { index, band ->
            ParametricBandCard(
                index = index,
                band = band,
                enabled = enabled,
                onChange = { updated -> onBandsChange(bands.toMutableList().also { it[index] = updated }) },
                onRemove = { onBandsChange(bands.toMutableList().also { it.removeAt(index) }) },
                removable = bands.size > 1,
            )
        }
        TextButton(
            onClick = {
                // Put the new band an octave above the highest one, or at 1 kHz.
                val hz = (bands.maxOfOrNull { it.frequencyHz }?.times(2.0) ?: 1000.0).coerceAtMost(16000.0)
                onBandsChange(bands + ParametricBand(frequencyHz = hz, gainDb = 0.0))
            },
            enabled = enabled && bands.size < MAX_BANDS,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.sound_add_band))
        }
    }
}

@Composable
private fun ParametricBandCard(
    index: Int,
    band: ParametricBand,
    enabled: Boolean,
    onChange: (ParametricBand) -> Unit,
    onRemove: () -> Unit,
    removable: Boolean,
) {
    val active = enabled && band.enabled
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, EarshotTheme.colors.hairline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.sound_band_number, index + 1),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                FieldSwitch(checked = band.enabled, onCheckedChange = { onChange(band.copy(enabled = it)) }, enabled = enabled)
                IconButton(onClick = onRemove, enabled = enabled && removable, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.sound_remove_band))
                }
            }
            SegmentedChoice(
                options = FilterType.entries,
                selected = band.type,
                onSelect = { onChange(band.copy(type = it)) },
                label = { filterTypeLabel(it) },
                enabled = active,
            )
            Spacer(Modifier.height(4.dp))
            // Frequency slides in log space so the low end is as easy to hit as the top.
            val logMin = ln(EqCurve.MIN_HZ)
            val logMax = ln(EqCurve.MAX_HZ)
            ValueSlider(
                label = stringResource(R.string.sound_frequency),
                value = ((ln(band.frequencyHz.coerceIn(EqCurve.MIN_HZ, EqCurve.MAX_HZ)) - logMin) / (logMax - logMin)).toFloat(),
                valueRange = 0f..1f,
                enabled = active,
                valueText = formatHz(band.frequencyHz) + " Hz",
                onValueChange = { f -> onChange(band.copy(frequencyHz = roundHz(exp(logMin + (logMax - logMin) * f)))) },
            )
            ValueSlider(
                label = stringResource(R.string.sound_gain),
                value = band.gainDb.toFloat(),
                valueRange = ParametricBand.MIN_GAIN_DB.toFloat()..ParametricBand.MAX_GAIN_DB.toFloat(),
                steps = 59,
                enabled = active,
                valueText = formatDb(band.gainDb, 1) + " dB",
                onValueChange = { onChange(band.copy(gainDb = (it * 2).roundToInt() / 2.0)) },
            )
            ValueSlider(
                label = stringResource(R.string.sound_q),
                value = ((ln(band.q.coerceIn(ParametricBand.MIN_Q, ParametricBand.MAX_Q)) - ln(ParametricBand.MIN_Q)) / (ln(ParametricBand.MAX_Q) - ln(ParametricBand.MIN_Q))).toFloat(),
                valueRange = 0f..1f,
                enabled = active,
                valueText = String.format(java.util.Locale.US, "%.2f", band.q),
                onValueChange = { f ->
                    val q = exp(ln(ParametricBand.MIN_Q) + (ln(ParametricBand.MAX_Q) - ln(ParametricBand.MIN_Q)) * f)
                    onChange(band.copy(q = (q * 100).roundToInt() / 100.0))
                },
            )
        }
    }
}

@Composable
private fun filterTypeLabel(type: FilterType): String = when (type) {
    FilterType.PEAK -> stringResource(R.string.sound_filter_peak)
    FilterType.LOW_SHELF -> stringResource(R.string.sound_filter_low_shelf)
    FilterType.HIGH_SHELF -> stringResource(R.string.sound_filter_high_shelf)
}

/** Three significant figures is plenty for a centre frequency. */
private fun roundHz(hz: Double): Double {
    val magnitude = 10.0.pow(kotlin.math.floor(kotlin.math.log10(hz)) - 2)
    return (hz / magnitude).roundToInt() * magnitude
}
