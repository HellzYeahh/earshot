package dev.earshot.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.audio.InstalledApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** `(label, packageName)` — the shape the audio module will hand the picker later. */
typealias AppEntry = Pair<String, String>

/**
 * Pick an installed app. With an empty [apps] list it falls back to typing a package name and
 * label by hand, so the flow works before the app-list provider exists.
 */
@Composable
fun AppPickerDialog(
    apps: List<AppEntry>,
    onPick: (label: String, packageName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var manualLabel by remember { mutableStateOf("") }
    var manualPackage by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter { it.first.contains(query, true) || it.second.contains(query, true) }
    }
    val manual = apps.isEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_picker_title)) },
        text = {
            Column {
                if (manual) {
                    OutlinedTextField(value = manualLabel, onValueChange = { manualLabel = it }, label = { Text(stringResource(R.string.app_picker_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = manualPackage, onValueChange = { manualPackage = it.trim() }, label = { Text(stringResource(R.string.app_picker_package)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text(stringResource(R.string.app_picker_manual_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                } else {
                    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text(stringResource(R.string.app_picker_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                        items(filtered, key = { it.second }) { (label, pkg) ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp)
                                    .clickable { onPick(label, pkg) }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                            ) {
                                Text(label, style = MaterialTheme.typography.bodyLarge)
                                Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (manual) {
                TextButton(
                    onClick = { onPick(manualLabel.ifBlank { manualPackage }, manualPackage) },
                    enabled = manualPackage.contains('.'),
                ) { Text(stringResource(R.string.action_add)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Launchable apps on this phone via the audio module's [InstalledApps], loaded off the main thread. */
@Composable
fun rememberInstalledApps(): State<List<AppEntry>> {
    val context = LocalContext.current
    return produceState(initialValue = emptyList(), context) {
        value = withContext(Dispatchers.IO) {
            runCatching { InstalledApps.launchable(context).map { it.label to it.packageName } }.getOrDefault(emptyList())
        }
    }
}
