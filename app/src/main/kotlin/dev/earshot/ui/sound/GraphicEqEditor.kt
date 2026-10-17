package dev.earshot.ui.sound

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.earshot.dsp.GraphicEq
import dev.earshot.dsp.ParametricBand
import kotlin.math.roundToInt

/**
 * Ten vertical ±15 dB sliders at [GraphicEq.FREQUENCIES_HZ]. The gains shown are the curve
 * sampled at each centre, so a parametric preset still reads sensibly here; moving a slider
 * converts everything to ten graphic bands (the DSP module will refine this mapping).
 */
@Composable
fun GraphicEqEditor(
    bands: List<ParametricBand>,
    preampDb: Double,
    enabled: Boolean,
    onBandsChange: (List<ParametricBand>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isGraphic = bands.size == GraphicEq.BAND_COUNT && bands.map { it.frequencyHz } == GraphicEq.FREQUENCIES_HZ
    val gains: List<Double> = remember(bands, preampDb) {
        if (isGraphic) bands.map { it.gainDb }
        else GraphicEq.FREQUENCIES_HZ.map { EqCurve.magnitudeDb(bands, 0.0, it).coerceIn(ParametricBand.MIN_GAIN_DB, ParametricBand.MAX_GAIN_DB) }
    }
    Row(
        modifier.fillMaxWidth().height(220.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        GraphicEq.FREQUENCIES_HZ.forEachIndexed { i, hz ->
            VerticalGainSlider(
                label = formatHz(hz),
                gainDb = gains[i],
                enabled = enabled,
                onGainChange = { g ->
                    val next = gains.toMutableList().also { it[i] = g }
                    onBandsChange(GraphicEq.bandsFromGains(next))
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun VerticalGainSlider(
    label: String,
    gainDb: Double,
    enabled: Boolean,
    onGainChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var drag by remember(gainDb) { mutableFloatStateOf(gainDb.toFloat()) }
    val shown = drag.roundToInt()
    val description = "$label Hz: ${formatDb(shown.toDouble())}"
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            formatDb(shown.toDouble()),
            style = MaterialTheme.typography.labelSmall,
            color = if (shown != 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Slider(
            value = drag,
            onValueChange = { drag = it },
            onValueChangeFinished = { onGainChange(drag.roundToInt().toDouble()) },
            valueRange = ParametricBand.MIN_GAIN_DB.toFloat()..ParametricBand.MAX_GAIN_DB.toFloat(),
            steps = 29,
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .width(40.dp)
                .semantics { stateDescription = description }
                .vertical(),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Rotates a horizontal Slider to run bottom-to-top while keeping touch and semantics intact. */
private fun Modifier.vertical(): Modifier = this
    .graphicsLayer { rotationZ = 270f }
    .layout { measurable, constraints ->
        val placeable = measurable.measure(
            Constraints(
                minWidth = constraints.minHeight,
                maxWidth = constraints.maxHeight,
                minHeight = constraints.minWidth,
                maxHeight = constraints.maxWidth,
            ),
        )
        layout(placeable.height, placeable.width) {
            placeable.place(-(placeable.width - placeable.height) / 2, -(placeable.height - placeable.width) / 2)
        }
    }

fun formatDb(db: Double, decimals: Int = 0): String {
    val rounded = if (decimals == 0) db.roundToInt().toDouble() else db
    val text = if (decimals == 0) rounded.roundToInt().toString() else String.format(java.util.Locale.US, "%.${decimals}f", rounded)
    return if (rounded > 0) "+$text" else text
}
