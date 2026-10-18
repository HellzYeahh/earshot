package dev.earshot.ui.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.earshot.R

/** The `[1.4]` auto-off choices the UI offers. Pure, for `AutoOffOptionsTest`. */
object AutoOffOptions {
    /** The Bose app's "Auto-Off" list (5, 20, 40 min, 1 h, 3 h, 24 h, never), from the protocol module. */
    val STANDARD: List<Int> = dev.earshot.protocol.qcultra2.AutoOffOptions.MINUTES

    /** [STANDARD] plus [current] when the headphones are set to something else, Never first. */
    fun options(current: Int?): List<Int> =
        (STANDARD + listOfNotNull(current?.takeIf { it >= 0 })).distinct().sorted()

    /** Whole hours read as hours ("1 h", "3 h"); anything else in minutes ("90 min"). */
    sealed interface Label {
        data object Never : Label
        data class Minutes(val minutes: Int) : Label
        data class Hours(val hours: Int) : Label
    }

    fun label(minutes: Int): Label = when {
        minutes <= 0 -> Label.Never
        minutes % 60 == 0 -> Label.Hours(minutes / 60)
        else -> Label.Minutes(minutes)
    }
}

@Composable
fun autoOffLabel(minutes: Int): String = when (val l = AutoOffOptions.label(minutes)) {
    AutoOffOptions.Label.Never -> stringResource(R.string.auto_off_never)
    is AutoOffOptions.Label.Hours -> stringResource(R.string.auto_off_hours, l.hours)
    is AutoOffOptions.Label.Minutes -> stringResource(R.string.auto_off_minutes, l.minutes)
}

/** Radio list of [AutoOffOptions.options]; picking one applies it and closes the dialog. */
@Composable
fun AutoOffDialog(current: Int?, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_auto_off)) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.settings_auto_off_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AutoOffOptions.options(current).forEach { minutes ->
                    val selected = minutes == current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = selected, role = Role.RadioButton) { onPick(minutes) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(autoOffLabel(minutes), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
