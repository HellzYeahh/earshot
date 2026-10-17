package dev.earshot.ui.tools

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.ui.common.BannerTone
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.InfoBanner
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.SectionCard

/**
 * Lab → Print console: one line at a time to the firmware's `[22.x]` debug console. The only
 * part of Lab that writes, hence the warning banner. The repository rejects unknown verbs before
 * anything reaches the headphones.
 */
@Composable
fun PrintConsoleSection(
    output: List<String>,
    busy: Boolean,
    enabled: Boolean,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var command by rememberSaveable { mutableStateOf("") }
    val canSend = enabled && !busy && command.isNotBlank()
    val send = { if (canSend) { onSend(command); command = "" } }

    SectionCard(
        modifier = modifier,
        title = stringResource(R.string.lab_console_title),
        trailing = {
            Icon(Icons.Default.Science, contentDescription = stringResource(R.string.conn_experimental), tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
        },
    ) {
        InfoBanner(stringResource(R.string.lab_console_experimental), tone = BannerTone.WARNING)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text(stringResource(R.string.lab_console_label)) },
                placeholder = { Text(stringResource(R.string.lab_console_hint), fontFamily = FontFamily.Monospace) },
                supportingText = { Text(stringResource(R.string.lab_console_hint), fontFamily = FontFamily.Monospace) },
                singleLine = true,
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = send, enabled = canSend, modifier = Modifier.heightIn(min = 48.dp)) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.lab_console_send))
            }
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        ) {
            Column(Modifier.padding(12.dp).horizontalScroll(rememberScrollState())) {
                if (output.isEmpty()) {
                    Text(
                        stringResource(R.string.lab_console_empty),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                output.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            line.startsWith("> ") -> MaterialTheme.colorScheme.primary
                            line.startsWith("! ") -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        softWrap = false,
                    )
                }
            }
        }
        if (output.isNotEmpty()) {
            TextButton(onClick = onClear, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.lab_console_clear)) }
        }
    }
}

@LightDarkPreview
@Composable
private fun PrintConsolePreview() {
    EarshotPreview {
        Column(Modifier.padding(16.dp)) {
            PrintConsoleSection(
                output = listOf("> output 2", "[22.3] STATUS 00 1a", "[22.3] STATUS 7b 22", "> frobnicate", "! Unknown console command. Use bind, unbind, verbosity or output."),
                busy = false,
                enabled = true,
                onSend = {},
                onClear = {},
            )
        }
    }
}
