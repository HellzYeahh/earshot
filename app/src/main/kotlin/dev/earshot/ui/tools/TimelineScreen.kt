package dev.earshot.ui.tools

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.earshot.R
import dev.earshot.appContainer
import dev.earshot.bt.BtTimeline
import dev.earshot.bt.TimelineEvent
import dev.earshot.bt.TimelineKind
import dev.earshot.ui.common.ConfirmDialog
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.EmptyHint
import dev.earshot.ui.common.InfoBanner
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.SectionHeading
import dev.earshot.ui.common.SubScreenScaffold
import dev.earshot.ui.theme.JetBrainsMono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lab → Bluetooth history (roadmap F6, item 5): every headset's link, profile, pairing, encryption
 * and codec changes on this phone, newest first, grouped by day. Names are looked up from the
 * paired devices for display; the stored history holds addresses only.
 */
@Composable
fun TimelineScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer
    val events by remember { container.timeline.store.events }.collectAsStateWithLifecycle()
    // Looked up again whenever a new address appears (the list is capped, so its size can stay put).
    val addresses = remember(events) { events.mapTo(HashSet()) { it.address.uppercase() } }
    val names by produceState(emptyMap<String, String>(), addresses) {
        value = withContext(Dispatchers.IO) { bondedNames(context) }
    }
    TimelineContent(events = events, names = names, onBack = onBack, onClear = { container.timeline.store.clear() })
}

@Composable
fun TimelineContent(events: List<TimelineEvent>, names: Map<String, String>, onBack: () -> Unit, onClear: () -> Unit) {
    val newestFirst = remember(events) { events.asReversed() }
    val day = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val time = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    // Clearing deletes the stored history for good, and it is what a connection bug report and
    // Find's "last seen" are built from: one stray tap must not wipe it, so it is confirmed first.
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    SubScreenScaffold(
        title = stringResource(R.string.timeline_title),
        onBack = onBack,
        actions = {
            IconButton(onClick = { confirmClear = true }, enabled = events.isNotEmpty()) {
                Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.timeline_clear))
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { InfoBanner(stringResource(R.string.timeline_intro)) }
            if (newestFirst.isEmpty()) item { EmptyHint(stringResource(R.string.timeline_empty)) }
            var lastDay: String? = null
            newestFirst.forEach { e ->
                val d = day.format(Date(e.timeMs))
                if (d != lastDay) {
                    lastDay = d
                    item(key = "day-$d-${e.timeMs}") { SectionHeading(d, modifier = Modifier.padding(top = 8.dp)) }
                }
                item(key = "${e.timeMs}-${e.address}-${e.kind}-${e.detail}") { TimelineRow(e, names[e.address.uppercase()] ?: e.address, time.format(Date(e.timeMs))) }
            }
        }
    }

    if (confirmClear) {
        // The app's one confirm dialog, like every other destructive confirm; the body says the
        // deletion is for good and stays on this phone (nothing else holds a copy).
        ConfirmDialog(
            title = stringResource(R.string.timeline_clear),
            text = stringResource(R.string.timeline_clear_text),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = onClear,
            onDismiss = { confirmClear = false },
        )
    }
}

/** Every paired device's name by address (upper case), LE Audio twins included; empty without the permission. */
@SuppressLint("MissingPermission")
private fun bondedNames(context: Context): Map<String, String> = runCatching {
    context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
        .associate { it.address.uppercase() to (it.name ?: it.address) }
}.getOrDefault(emptyMap())

@Composable
private fun TimelineRow(event: TimelineEvent, name: String, time: String) {
    val problem = event.kind == TimelineKind.KEY_MISSING ||
        (event.kind == TimelineKind.PROFILE_DOWN && event.detail.contains(','))
    Row(Modifier.fillMaxWidth()) {
        Text(time, style = MaterialTheme.typography.bodySmall, fontFamily = JetBrainsMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                BtTimeline.describe(event),
                style = MaterialTheme.typography.bodyMedium,
                color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@LightDarkPreview
@Composable
private fun TimelinePreview() = EarshotPreview {
    val now = System.currentTimeMillis()
    val a = "68:F2:1F:38:DB:0F"
    TimelineContent(
        events = listOf(
            TimelineEvent(now - 3_600_000, a, TimelineKind.LINK_UP),
            TimelineEvent(now - 3_599_000, a, TimelineKind.ENCRYPTION, "AES, 16-byte key"),
            TimelineEvent(now - 3_598_000, a, TimelineKind.PROFILE_UP, "A2DP"),
            TimelineEvent(now - 3_597_000, a, TimelineKind.CODEC, "AAC, 44.1 kHz"),
            TimelineEvent(now - 60_000, a, TimelineKind.PROFILE_DOWN, "A2DP, the audio stream couldn't be set up"),
            TimelineEvent(now - 59_000, a, TimelineKind.LINK_DOWN),
        ),
        names = mapOf(a to "Bose QC Ultra 2 HP"),
        onBack = {},
        onClear = {},
    )
}
