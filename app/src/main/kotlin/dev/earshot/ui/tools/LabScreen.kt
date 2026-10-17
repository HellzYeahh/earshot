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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.earshot.R
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.LabReading
import dev.earshot.core.RawFrame
import dev.earshot.protocol.qcultra2.FunctionNames
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.EarshotViewModel
import dev.earshot.ui.common.InfoBanner
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.common.SubScreenScaffold
import dev.earshot.ui.common.UiMessenger
import dev.earshot.ui.common.earshotViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Function blocks from the protocol catalogue, offered as scan targets. */
val LAB_BLOCKS: List<Pair<Int, String>> = FunctionNames.blocks.toList().sortedBy { it.first }

/** The repository's name if it has one, otherwise the catalogue's. */
fun labFunctionName(reading: LabReading): String? =
    reading.knownName ?: FunctionNames.functionName(reading.block, reading.function)

data class LabUiState(
    val selectedBlocks: Set<Int> = setOf(1, 2, 31),
    val maxFunction: Int = 40,
    val scanning: Boolean = false,
    val scanned: Int = 0,
    val total: Int = 0,
    val readings: List<LabReading> = emptyList(),
    /** Print console transcript: "> cmd" lines, reply lines, "! error" lines. */
    val consoleOutput: List<String> = emptyList(),
    val consoleBusy: Boolean = false,
)

class LabViewModel(private val headphones: HeadphoneRepository, messenger: UiMessenger) : EarshotViewModel(messenger) {
    private val _state = MutableStateFlow(LabUiState())
    val state: StateFlow<LabUiState> = _state
    private var scanJob: Job? = null

    fun toggleBlock(block: Int) = _state.update { s ->
        s.copy(selectedBlocks = if (block in s.selectedBlocks) s.selectedBlocks - block else s.selectedBlocks + block)
    }

    fun startScan() {
        val s = _state.value
        if (s.scanning || s.selectedBlocks.isEmpty()) return
        scan(s.selectedBlocks.sorted(), s.maxFunction)
    }

    /**
     * Discovery sweep: every block 0..63 and every function 0..63, GET only. Unknown blocks
     * answer FblockNotSupp at once, so most of the ~4000 reads are cheap; a few minutes in all.
     */
    fun startDeepScan() {
        if (_state.value.scanning) return
        scan((0..DEEP_SCAN_MAX).toList(), DEEP_SCAN_MAX)
    }

    private fun scan(blocks: List<Int>, maxFunction: Int) {
        _state.update { it.copy(scanning = true, scanned = 0, total = blocks.size * (maxFunction + 1), readings = emptyList()) }
        scanJob = viewModelScope.launch {
            try {
                headphones.scanFunctions(blocks, maxFunction).collect { reading ->
                    _state.update { it.copy(readings = it.readings + reading, scanned = it.scanned + 1) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                messenger.report(e)
            } finally {
                _state.update { it.copy(scanning = false) }
            }
        }
    }

    fun stopScan() { scanJob?.cancel(); _state.update { it.copy(scanning = false) } }

    /** One Print-console line; errors land in the transcript rather than the Snackbar so they read in context. */
    fun sendConsole(command: String) {
        val line = command.trim()
        if (line.isEmpty() || _state.value.consoleBusy) return
        _state.update { it.copy(consoleBusy = true, consoleOutput = it.consoleOutput + "> $line") }
        viewModelScope.launch {
            try {
                val reply = headphones.printConsole(line)
                _state.update { it.copy(consoleOutput = it.consoleOutput + reply.lines().filter { l -> l.isNotBlank() }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(consoleOutput = it.consoleOutput + "! ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                _state.update { it.copy(consoleBusy = false) }
            }
        }
    }

    fun clearConsole() = _state.update { it.copy(consoleOutput = emptyList()) }

    val connected: StateFlow<Boolean> get() = _connected
    private val _connected = MutableStateFlow(headphones.state.value.isConnected)

    init {
        viewModelScope.launch { headphones.state.collect { _connected.value = it.isConnected } }
    }

    /** Plain-text dump of the results, one frame per line. */
    fun exportText(): String = buildString {
        _state.value.readings.groupBy { it.block }.toSortedMap().forEach { (block, readings) ->
            appendLine("# Block $block ${FunctionNames.blockName(block).orEmpty()}")
            readings.forEach { r ->
                r.frames.forEach { f ->
                    appendLine("[${r.block}.${r.function}] ${labFunctionName(r).orEmpty()} ${f.operatorName} ${if (f.isError) "ERROR " + f.errorName.orEmpty() + " " + f.payloadHex else f.payloadHex}".trim())
                }
            }
        }
    }

    override fun onCleared() { scanJob?.cancel() }

    companion object {
        const val DEEP_SCAN_MAX = 63
    }
}

@Composable
fun LabScreen(onBack: () -> Unit) {
    val vm = earshotViewModel { LabViewModel(it.headphones, it.messenger) }
    val state by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Every finished scan is also written to the app's external files folder
    // (Android/data/dev.earshot/files/lab/), so it can be pulled with adb or shared from a file manager.
    LaunchedEffect(state.scanning) {
        if (!state.scanning && state.readings.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.getExternalFilesDir(null), "lab").apply { mkdirs() }
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    File(dir, "lab-scan-$stamp.txt").writeText(vm.exportText())
                }
            }
        }
    }
    val connected by vm.connected.collectAsStateWithLifecycle()
    LabContent(
        state = state,
        onBack = onBack,
        onToggleBlock = vm::toggleBlock,
        onStart = vm::startScan,
        onDeepScan = vm::startDeepScan,
        onStop = vm::stopScan,
        onCopy = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Earshot Lab", vm.exportText()))) } },
        connected = connected,
        onConsoleSend = vm::sendConsole,
        onConsoleClear = vm::clearConsole,
    )
}

@Composable
fun LabContent(
    state: LabUiState,
    onBack: () -> Unit,
    onToggleBlock: (Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCopy: () -> Unit,
    onDeepScan: () -> Unit = {},
    connected: Boolean = true,
    onConsoleSend: (String) -> Unit = {},
    onConsoleClear: () -> Unit = {},
) {
    val grouped = state.readings.groupBy { it.block }.toSortedMap()
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
                SectionCard(title = stringResource(R.string.lab_blocks)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LAB_BLOCKS.forEach { (id, name) ->
                            FilterChip(
                                selected = id in state.selectedBlocks,
                                onClick = { onToggleBlock(id) },
                                label = { Text("$id $name") },
                                enabled = !state.scanning,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            if (state.total > 0) {
                                LinearProgressIndicator(progress = { state.scanned / state.total.toFloat() }, modifier = Modifier.fillMaxWidth())
                                Text(stringResource(R.string.lab_progress, state.scanned, state.total), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                            } else {
                                Text(stringResource(R.string.lab_functions_hint, state.maxFunction), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        if (state.scanning) {
                            Button(onClick = onStop, modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(Icons.Default.Stop, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.action_stop))
                            }
                        } else {
                            Button(onClick = onStart, enabled = state.selectedBlocks.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.lab_scan))
                            }
                        }
                    }
                    if (!state.scanning) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onDeepScan, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.lab_deep_scan))
                        }
                        Text(stringResource(R.string.lab_deep_scan_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            item(key = "console") {
                PrintConsoleSection(
                    output = state.consoleOutput,
                    busy = state.consoleBusy,
                    enabled = connected && !state.scanning,
                    onSend = onConsoleSend,
                    onClear = onConsoleClear,
                )
            }
            grouped.forEach { (block, readings) ->
                item(key = "h$block") {
                    Text(
                        "Block $block · " + (FunctionNames.blockName(block) ?: "?"),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }
                items(readings, key = { "${it.block}.${it.function}" }) { reading -> ReadingRow(reading) }
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
                "[${reading.block}.${reading.function}]",
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                color = if (allErrors) muted else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                labFunctionName(reading) ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = if (allErrors) muted else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        reading.frames.forEach { frame -> FrameLine(frame, muted) }
    }
}

@Composable
private fun FrameLine(frame: RawFrame, muted: androidx.compose.ui.graphics.Color) {
    Text(
        if (frame.isError) "${frame.operatorName} ${frame.errorName ?: "?"} ${frame.payloadHex}".trimEnd() else "${frame.operatorName} ${frame.payloadHex.ifBlank { "(empty)" }}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = if (frame.isError) muted else MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@LightDarkPreview
@Composable
private fun LabPreview() {
    EarshotPreview {
        LabContent(
            LabUiState(
                scanned = 12, total = 82,
                readings = listOf(
                    LabReading(1, 7, "EQ", listOf(RawFrame(1, 7, "STATUS", "f60a0200f60a0001f60aff02", false))),
                    LabReading(1, 8, null, listOf(RawFrame(1, 8, "ERROR", "04", true, "FUNC_NOT_SUPPORTED"))),
                    LabReading(2, 2, "Battery", listOf(RawFrame(2, 2, "STATUS", "48ffff00", false))),
                ),
            ),
            {}, {}, {}, {}, {},
        )
    }
}
