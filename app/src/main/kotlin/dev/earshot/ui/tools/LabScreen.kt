@file:OptIn(ExperimentalLayoutApi::class)

package dev.earshot.ui.tools

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.earshot.R
import dev.earshot.core.LabProfile
import dev.earshot.core.LabReading
import dev.earshot.core.RawFrame
import dev.earshot.core.fake.FakeHeadphoneRepository
import dev.earshot.ui.LocalUiDependencies
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.FieldProgressBar
import dev.earshot.ui.common.InfoBanner
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.common.SubScreenScaffold
import dev.earshot.ui.common.UiMessenger
import dev.earshot.ui.common.earshotViewModel
import dev.earshot.ui.theme.JetBrainsMono
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LabScreen(onBack: () -> Unit, onOpenTimeline: () -> Unit = {}) {
    val vm = earshotViewModel { LabViewModel(it.headphones, it.messenger) }
    val state by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val messenger = LocalUiDependencies.current.messenger
    // Every finished scan is also written to the app's external files folder
    // (Android/data/<package>/files/lab/: dev.earshot on Play, dev.earshot.foss on GitHub), so it can
    // be pulled with adb or shared from a file manager. The deep-scan hint names the same folder.
    // Once per scan (LabScanFile): the effect also runs when the screen comes back with the results
    // still in the ViewModel, which used to write another identical file each time.
    var scanPending by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.scanning) {
        val step = LabScanFile.step(pending = scanPending, scanning = state.scanning, hasReadings = state.readings.isNotEmpty())
        scanPending = step.pending
        if (step.write) {
            val text = vm.exportText()
            // The flag is already cleared: a rotation right now must not lose this scan's file.
            withContext(Dispatchers.IO + NonCancellable) {
                runCatching {
                    val dir = File(context.getExternalFilesDir(null), "lab").apply { mkdirs() }
                    File(dir, LabScanFile.name(Date())).writeText(text)
                }
            }
        }
    }
    LabContent(
        state = state,
        onBack = onBack,
        onToggleGroup = vm::toggleGroup,
        onStart = vm::startScan,
        onDeepScan = vm::startDeepScan,
        onStop = vm::stopScan,
        onCopy = { scope.launch { copyOrReport(messenger) { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Earshot Lab", vm.exportText()))) } } },
        onCopyReport = {
            scope.launch {
                copyOrReport(messenger) { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Earshot connection report", DiagnosticsReport.build(context)))) }
            }
        },
        onConsoleSend = vm::sendConsole,
        onConsoleClear = vm::clearConsole,
        onOpenTimeline = onOpenTimeline,
    )
}

/**
 * When a scan goes to a file: once, on the scanning → idle edge (a stopped scan included), never
 * just because the screen came back with old results (a rotation, a trip to Bluetooth history,
 * a tab switch). The pending flag is saved with the screen, so a scan that ended while the screen
 * was away is still written when it returns. Pure, so it is unit-tested.
 */
internal object LabScanFile {
    /** The new pending flag, and whether the scan the screen holds is to be written now. */
    data class Step(val pending: Boolean, val write: Boolean)

    fun step(pending: Boolean, scanning: Boolean, hasReadings: Boolean): Step = when {
        scanning -> Step(pending = true, write = false)
        pending -> Step(pending = false, write = hasReadings)
        else -> Step(pending = false, write = false)
    }

    fun name(at: Date): String = "lab-scan-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(at) + ".txt"
}

/**
 * A copy that fails (a deep scan's clip over the binder limit, a report read that threw) becomes
 * a Snackbar line instead of crashing the app from the screen's coroutine scope.
 */
private suspend fun copyOrReport(messenger: UiMessenger, copy: suspend () -> Unit) {
    try {
        copy()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        messenger.report(e)
    }
}

@Composable
fun LabContent(
    state: LabUiState,
    onBack: () -> Unit,
    onToggleGroup: (Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCopy: () -> Unit,
    onDeepScan: () -> Unit = {},
    onCopyReport: () -> Unit = {},
    onConsoleSend: (String) -> Unit = {},
    onConsoleClear: () -> Unit = {},
    onOpenTimeline: () -> Unit = {},
) {
    val grouped = LabExport.grouped(state.readings)
    SubScreenScaffold(
        title = stringResource(R.string.tools_lab),
        onBack = onBack,
        actions = {
            IconButton(onClick = onCopy, enabled = state.readings.isNotEmpty()) {
                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.action_copy))
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { InfoBanner(stringResource(R.string.lab_read_only)) }
            item {
                SectionCard(title = stringResource(R.string.lab_report_title)) {
                    Text(stringResource(R.string.lab_report_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = onCopyReport, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.lab_report_copy))
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = onOpenTimeline, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.timeline_title))
                    }
                }
            }
            item(key = "scanner") { ScannerCard(state, onToggleGroup, onStart, onStop, onDeepScan) }
            item(key = "console") {
                PrintConsoleSection(
                    output = state.consoleOutput,
                    busy = state.consoleBusy,
                    enabled = state.connected && !state.scanning,
                    onSend = onConsoleSend,
                    onClear = onConsoleClear,
                    // Bose only; said so only when the connected headset is something else.
                    note = if (state.connected && state.profile?.printConsole != true) stringResource(R.string.lab_console_bose_only) else null,
                )
            }
            val from = state.resultProfile
            if (from != null && grouped.isNotEmpty() && from.stack != state.profile?.stack) {
                item(key = "from") {
                    Text(
                        stringResource(R.string.lab_results_from, from.stack),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            grouped.forEach { (group, readings) ->
                item(key = "h$group") {
                    Text(
                        LabExport.heading(state.resultProfile, group, readings),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }
                items(readings, key = { "${it.block}.${it.function}.${it.address}" }) { reading -> ReadingRow(reading) }
            }
        }
    }
}

/** The connected headset's stack, its groups as chips, progress, Scan / Stop and Deep scan. */
@Composable
private fun ScannerCard(
    state: LabUiState,
    onToggleGroup: (Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDeepScan: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val profile = state.profile
    SectionCard(title = stringResource(R.string.lab_scanner_title)) {
        if (profile == null) {
            Text(
                stringResource(if (state.connected) R.string.lab_no_driver else R.string.lab_not_connected),
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
            // The headset went away mid-scan: keep the Stop button reachable.
            if (state.scanning) {
                Spacer(Modifier.height(8.dp))
                ScanProgress(state, onStart, onStop, startEnabled = false)
            }
        } else {
            ScannerControls(state, profile, onToggleGroup, onStart, onStop, onDeepScan)
        }
    }
}

@Composable
private fun ScannerControls(
    state: LabUiState,
    profile: LabProfile,
    onToggleGroup: (Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDeepScan: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Text(
            state.deviceName?.let { stringResource(R.string.lab_stack_line, profile.stack, it) } ?: profile.stack,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(profile.safetyNote, style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(top = 2.dp))
        profile.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(top = 4.dp)) }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.lab_blocks), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            profile.groups.forEach { g ->
                FilterChip(
                    selected = g.id in state.selectedGroups,
                    onClick = { onToggleGroup(g.id) },
                    label = { Text("${g.code} ${g.name}") },
                    enabled = !state.scanning,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        ScanProgress(state, onStart, onStop, startEnabled = state.selectedGroups.isNotEmpty())
        if (!state.scanning) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = onDeepScan, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.lab_deep_scan, profile.deepReadCount))
            }
            Text(
                stringResource(R.string.lab_deep_scan_hint, profile.deepScanDescription, LocalContext.current.packageName),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ScanProgress(state: LabUiState, onStart: () -> Unit, onStop: () -> Unit, startEnabled: Boolean) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (state.total > 0) {
                FieldProgressBar(progress = { (state.scanned / state.total.toFloat()).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.lab_progress, state.scanned, state.total), style = MaterialTheme.typography.labelMedium, color = muted, modifier = Modifier.padding(top = 4.dp))
            } else {
                state.profile?.let { Text(stringResource(R.string.lab_functions_hint, it.scanDescription, state.scanReadCount), style = MaterialTheme.typography.labelMedium, color = muted) }
            }
        }
        Spacer(Modifier.width(12.dp))
        if (state.scanning) {
            Button(shape = MaterialTheme.shapes.medium, onClick = onStop, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Default.Stop, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.action_stop))
            }
        } else {
            Button(shape = MaterialTheme.shapes.medium, onClick = onStart, enabled = startEnabled, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Default.PlayArrow, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.lab_scan))
            }
        }
    }
}

@Composable
private fun ReadingRow(reading: LabReading) {
    val allErrors = reading.frames.isNotEmpty() && reading.frames.all { it.isError }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    SectionCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                LabExport.address(reading),
                style = MaterialTheme.typography.labelLarge,
                fontFamily = JetBrainsMono,
                color = if (allErrors) muted else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                LabExport.functionName(reading) ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = if (allErrors) muted else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        reading.frames.forEach { frame -> FrameLine(frame, muted) }
    }
}

@Composable
private fun FrameLine(frame: RawFrame, muted: Color) {
    Text(
        if (frame.isError) "${frame.operatorName} ${frame.errorName ?: "?"} ${frame.payloadHex}".trimEnd() else "${frame.operatorName} ${frame.payloadHex.ifBlank { "(empty)" }}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = JetBrainsMono,
        color = if (frame.isError) muted else MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@LightDarkPreview
@Composable
private fun LabPreview() {
    EarshotPreview {
        val profile = FakeHeadphoneRepository.LAB_PROFILE
        LabContent(
            LabUiState(
                connected = true,
                deviceName = FakeHeadphoneRepository.DEVICE_NAME,
                profile = profile,
                selectedGroups = profile.defaultGroups,
                resultProfile = profile,
                scanned = 12, total = 33,
                readings = listOf(
                    LabReading(1, 7, "EQ", listOf(RawFrame(1, 7, "STATUS", "f60a0200f60a0001f60aff02", false)), groupName = "Settings"),
                    LabReading(1, 8, null, listOf(RawFrame(1, 8, "ERROR", "04", true, "FUNC_NOT_SUPPORTED")), groupName = "Settings"),
                    LabReading(2, 2, "Battery", listOf(RawFrame(2, 2, "STATUS", "48ffff00", false)), groupName = "Status"),
                ),
            ),
            {}, {}, {}, {}, {},
        )
    }
}

@LightDarkPreview
@Composable
private fun LabNoDriverPreview() {
    EarshotPreview {
        LabContent(LabUiState(connected = true, deviceName = "AirPods Pro"), {}, {}, {}, {}, {})
    }
}
