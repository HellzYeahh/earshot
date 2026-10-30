package dev.earshot.ui.common

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
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
 * Pick an installed app, loading the list itself: the launcher query runs only once the picker is
 * open, never while the screen behind it is merely shown. Each `loadLabel` builds that app's
 * Resources, so with ~150 launcher apps the query can take 100–400 ms of CPU; run on screen entry,
 * it also recomposed the whole screen during that screen's first frames. A spinner shows until
 * the list is in; on a later open the last list loaded in this process shows at once and is
 * replaced by a fresh one, so an app installed meanwhile still appears. Nothing found (or a
 * failed query) falls back to typing a package name, as before.
 */
@Composable
fun AppPickerDialog(
    onPick: (label: String, packageName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val apps by rememberLaunchableApps()
    AppPickerDialogContent(apps, onPick, onDismiss)
}

/** [apps] null = still loading (spinner), empty = type it in, else the searchable list. */
@Composable
private fun AppPickerDialogContent(
    apps: List<AppEntry>?,
    onPick: (label: String, packageName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var manualLabel by remember { mutableStateOf("") }
    var manualPackage by remember { mutableStateOf("") }
    val filtered = remember(apps, query) { AppPickerLogic.filter(apps.orEmpty(), query) }
    val manual = apps != null && apps.isEmpty()

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
                    // The search field is there from the start, so typing can begin while the list loads.
                    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text(stringResource(R.string.app_picker_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (apps == null) {
                        Box(Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(top = 8.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
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

/** The picker's list: the last one loaded at once (null the first time), then a fresh load. */
@Composable
private fun rememberLaunchableApps(): State<List<AppEntry>?> {
    val context = LocalContext.current
    return produceState(initialValue = AppPickerLogic.lastLoaded, context) {
        value = AppPickerLogic.shownAfterLoad(loadLaunchableApps(context))
    }
}

/** The blocking package-manager query, on the IO pool; a failure counts as "nothing found". */
private suspend fun loadLaunchableApps(context: Context): List<AppEntry> {
    val apps = withContext(Dispatchers.IO) {
        runCatching { InstalledApps.launchable(context).map { it.label to it.packageName } }.getOrDefault(emptyList())
    }
    AppPickerLogic.keep(apps)
    return apps
}

/** The picker's Android-free parts: the search filter and the process-wide last list. */
internal object AppPickerLogic {
    /** The last non-empty list loaded in this process: a few kB of labels and package names. */
    @Volatile
    var lastLoaded: List<AppEntry>? = null
        private set

    /** Keeps [apps] for the next open. An empty result (a failed query) never replaces a good list. */
    fun keep(apps: List<AppEntry>) {
        if (apps.isNotEmpty()) lastLoaded = apps
    }

    /**
     * What the picker shows once its own load is done: the [fresh] list, or, if that came back
     * empty (a failed query), the last good one rather than swapping a shown list for the typing form.
     */
    fun shownAfterLoad(fresh: List<AppEntry>): List<AppEntry> = fresh.ifEmpty { lastLoaded ?: fresh }

    /** Apps whose label or package name contains [query], ignoring case; all of them for a blank one. */
    fun filter(apps: List<AppEntry>, query: String): List<AppEntry> =
        if (query.isBlank()) apps else apps.filter { it.first.contains(query, true) || it.second.contains(query, true) }

    /** Forgets the last list (tests). */
    fun clear() {
        lastLoaded = null
    }
}
