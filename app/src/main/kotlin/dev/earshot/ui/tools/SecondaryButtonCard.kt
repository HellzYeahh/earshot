package dev.earshot.ui.tools

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.core.ButtonAction
import dev.earshot.core.ButtonAssignment
import dev.earshot.shortcut.SecondaryButtonPolicy
import dev.earshot.ui.common.SectionCard

/**
 * "Shortcut Button 2 (left earbud):" and so on: a Bose `[1.9]` record after the first. Its
 * options are what the record reports: sharing Shortcut Button 1's action (Voice assistant),
 * or one of the headset's own modes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SecondaryButtonCard(
    button: ButtonAssignment,
    index: Int,
    selected: ButtonAction,
    /** What Shortcut Button 1 runs, for "Same as Shortcut Button 1 (…)". */
    sharedLabel: String,
    earbuds: Boolean,
    onSelect: (ButtonAction) -> Unit,
) {
    val options = SecondaryButtonPolicy.options(button).let { if (selected in it) it else it + selected }
    var expanded by remember { mutableStateOf(false) }
    SectionCard {
        Text(buttonTitle(button, index), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = modeLabel(selected, sharedLabel, earbuds),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                enabled = options.size > 1,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(modeLabel(mode, sharedLabel, earbuds)) },
                        onClick = { expanded = false; onSelect(mode) },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }
        Text(
            stringResource(R.string.shortcut_secondary_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
        )
    }
}

@Composable
private fun modeLabel(mode: ButtonAction, sharedLabel: String, earbuds: Boolean): String = when (mode) {
    ButtonAction.VOICE_ASSISTANT -> stringResource(R.string.shortcut_secondary_same, sharedLabel)
    else -> ShortcutChoice.forNative(mode)?.let { choiceLabel(it, earbuds) } ?: mode.label
}
