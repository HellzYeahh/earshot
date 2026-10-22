package dev.earshot.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.earshot.core.VendorControl
import kotlin.math.roundToInt

/**
 * The headset's own settings that only its maker has (Sleep A30: listening mode, sleep options,
 * prompts; Sony: ambient level, EQ, speak-to-chat), drawn from [VendorControl] descriptions so a
 * new driver needs no new screen: a switch for a toggle, a segmented choice for a few options, a
 * dropdown for a longer list, a slider for a level. Unknown values show unselected and stay
 * tappable (Tools' "let people try" rule applies here too).
 */
@Composable
fun VendorControlsCard(
    title: String,
    controls: List<VendorControl>,
    enabled: Boolean,
    onChange: (id: String, value: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (controls.isEmpty()) return
    SectionCard(modifier = modifier, title = title) {
        controls.forEachIndexed { i, control ->
            if (i > 0) HorizontalDivider()
            val options = control.options
            val range = control.range
            when {
                options != null && options.size > MAX_SEGMENTS -> Column(Modifier.padding(vertical = 8.dp)) {
                    DropdownField(
                        label = control.title,
                        options = options.indices.toList(),
                        selected = control.value?.takeIf { it in options.indices },
                        onSelect = { onChange(control.id, it) },
                        optionLabel = { options[it] },
                        enabled = enabled,
                    )
                    control.subtitle?.let { Subtitle(it) }
                }
                options != null -> Column(Modifier.padding(vertical = 8.dp)) {
                    Text(control.title, style = MaterialTheme.typography.bodyLarge)
                    control.subtitle?.let { Subtitle(it) }
                    SegmentedChoice(
                        options = options.indices.toList(),
                        selected = control.value ?: -1,
                        onSelect = { onChange(control.id, it) },
                        label = { options[it] },
                        enabled = enabled,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                range != null && range.last > range.first -> LevelRow(control, range, enabled, onChange)
                else -> SwitchRow(
                    title = control.title,
                    checked = control.value == 1,
                    onCheckedChange = { onChange(control.id, if (it) 1 else 0) },
                    subtitle = control.subtitle,
                    enabled = enabled,
                )
            }
        }
    }
}

/** A level: the slider follows the finger, the headset is told once on release. */
@Composable
private fun LevelRow(control: VendorControl, range: IntRange, enabled: Boolean, onChange: (id: String, value: Int) -> Unit) {
    val current = (control.value ?: range.first).coerceIn(range)
    var drag by remember(control.id, current) { mutableFloatStateOf(current.toFloat()) }
    val shown = drag.roundToInt()
    Column(Modifier.padding(vertical = 8.dp)) {
        ValueSlider(
            label = control.title,
            value = drag,
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            enabled = enabled && control.value != null,
            onValueChange = { drag = it },
            onValueChangeFinished = {
                val v = drag.roundToInt().coerceIn(range)
                if (v != current) onChange(control.id, v)
            },
            valueText = when {
                control.value == null -> "–"
                control.unit == "dB" && shown > 0 -> "+$shown dB"
                control.unit != null -> "$shown ${control.unit}"
                else -> shown.toString()
            },
        )
        control.subtitle?.let { Subtitle(it) }
    }
}

@Composable
private fun Subtitle(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** More options than this don't fit a segmented row at phone width; they get a dropdown. */
private const val MAX_SEGMENTS = 4
