package dev.earshot.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.earshot.core.VendorControl

/**
 * The headset's own settings that only its maker has (Sleep A30: listening mode, sleep options,
 * prompts), drawn from [VendorControl] descriptions so a new driver needs no new screen: a switch
 * for a toggle, a segmented choice for a few options. Unknown values show unselected and stay
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
            if (options == null) {
                SwitchRow(
                    title = control.title,
                    checked = control.value == 1,
                    onCheckedChange = { onChange(control.id, if (it) 1 else 0) },
                    subtitle = control.subtitle,
                    enabled = enabled,
                )
            } else {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(control.title, style = MaterialTheme.typography.bodyLarge)
                    control.subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SegmentedChoice(
                        options = options.indices.toList(),
                        selected = control.value ?: -1,
                        onSelect = { onChange(control.id, it) },
                        label = { options[it] },
                        enabled = enabled,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}
