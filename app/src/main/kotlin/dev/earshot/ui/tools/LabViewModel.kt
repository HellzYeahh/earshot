package dev.earshot.ui.tools

import androidx.lifecycle.viewModelScope
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.LabProfile
import dev.earshot.core.LabReading
import dev.earshot.protocol.qcultra2.FunctionNames
import dev.earshot.ui.common.EarshotViewModel
import dev.earshot.ui.common.UiMessenger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LabUiState(
    val connected: Boolean = false,
    val deviceName: String? = null,
    /** What the connected headset's stack can scan; null = nothing (not connected, or no driver). */
    val profile: LabProfile? = null,
    val selectedGroups: Set<Int> = emptySet(),
    /** The user changed the selection: a profile update then keeps it instead of resetting to the defaults. */
    val selectionTouched: Boolean = false,
    /** The stack [selectedGroups] belongs to, so a reconnect of the same headset keeps the picks. */
    val selectionStack: String? = null,
    val scanning: Boolean = false,
    val scanned: Int = 0,
    val total: Int = 0,
    val readings: List<LabReading> = emptyList(),
    /** The profile [readings] came from: the headset may have changed since. */
    val resultProfile: LabProfile? = null,
    /** Print console transcript: "> cmd" lines, reply lines, "! error" lines. */
    val consoleOutput: List<String> = emptyList(),
    val consoleBusy: Boolean = false,
) {
    /** How many reads Scan sends for the current selection. */
    val scanReadCount: Int get() = profile?.groups?.filter { it.id in selectedGroups }?.sumOf { it.readCount } ?: 0

    /**
     * The headset as the repository now reports it. The selection follows the profile: its
     * defaults when a stack first appears (or until the user picks), else the user's picks that
     * still exist. A Sennheiser or Sony headset lists its features after connecting, so the first
     * profile may be discovery only and the defaults grow with it.
     */
    fun withHeadset(connected: Boolean, deviceName: String?, profile: LabProfile?): LabUiState {
        if (profile == null) return copy(connected = connected, deviceName = deviceName, profile = null)
        val ids = profile.groups.map { it.id }.toSet()
        val keep = selectionStack == profile.stack && selectionTouched
        return copy(
            connected = connected,
            deviceName = deviceName,
            profile = profile,
            selectedGroups = if (keep) selectedGroups intersect ids else profile.defaultGroups intersect ids,
            selectionTouched = keep,
            selectionStack = profile.stack,
        )
    }
}

class LabViewModel(private val headphones: HeadphoneRepository, messenger: UiMessenger) : EarshotViewModel(messenger) {
    private val _state = MutableStateFlow(LabUiState())
    val state: StateFlow<LabUiState> = _state
    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            headphones.state
                .map { s -> Triple(s.isConnected, s.connection.deviceName, if (s.isConnected) headphones.labProfile() else null) }
                .distinctUntilChanged()
                .collect { (connected, name, profile) -> _state.update { it.withHeadset(connected, name, profile) } }
        }
    }

    fun toggleGroup(id: Int) = _state.update { s ->
        s.copy(selectedGroups = if (id in s.selectedGroups) s.selectedGroups - id else s.selectedGroups + id, selectionTouched = true)
    }

    /** The selected groups' documented reads. */
    fun startScan() {
        val s = _state.value
        val profile = s.profile ?: return
        if (s.scanning || s.selectedGroups.isEmpty()) return
        val groups = profile.groups.filter { it.id in s.selectedGroups }
        scan(profile, groups.map { it.id }, deep = false, total = groups.sumOf { it.readCount })
    }

    /** Everything the stack documents for this headset (on Bose, the GET sweep over blocks 0..63). */
    fun startDeepScan() {
        val s = _state.value
        val profile = s.profile ?: return
        if (s.scanning) return
        scan(profile, emptyList(), deep = true, total = profile.deepReadCount)
    }

    private fun scan(profile: LabProfile, groupIds: List<Int>, deep: Boolean, total: Int) {
        _state.update { it.copy(scanning = true, scanned = 0, total = total, readings = emptyList(), resultProfile = profile) }
        scanJob = viewModelScope.launch {
            try {
                headphones.scanLab(groupIds, deep).collect { reading ->
                    // The total is an estimate where the headset reports its features first: never let progress pass it.
                    _state.update { it.copy(readings = it.readings + reading, scanned = it.scanned + 1, total = maxOf(it.total, it.scanned + 1)) }
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

    /** Plain-text dump of the results, one frame per line. */
    fun exportText(): String = LabExport.text(_state.value.resultProfile, _state.value.readings)

    override fun onCleared() { scanJob?.cancel() }
}

/** The Lab's labels and its text export, kept pure so they are unit-tested. */
object LabExport {
    /** `[1.7]` on BMAP, the stack's own address (`01 05`, `0495/0603`, `2.1`, `T1 66 17`) elsewhere. */
    fun address(r: LabReading): String = r.address ?: "[${r.block}.${r.function}]"

    /** The stack's name for the read, else (BMAP) the catalogue's. */
    fun functionName(r: LabReading): String? =
        r.knownName ?: if (r.address == null) FunctionNames.functionName(r.block, r.function) else null

    /** "Block 1 · Settings", "Feature 3 · Battery and power", "Service 2 · device". */
    fun heading(profile: LabProfile?, group: Int, readings: List<LabReading>, separator: String = " · ", unknown: String = "?"): String {
        val g = profile?.group(group)
        val noun = profile?.groupNoun ?: "Block"
        val name = g?.name ?: readings.firstNotNullOfOrNull { it.groupName } ?: if (profile == null) FunctionNames.blockName(group) else null
        return "$noun ${g?.code ?: group}$separator${name ?: unknown}"
    }

    /** Results by group, in id order. */
    fun grouped(readings: List<LabReading>): Map<Int, List<LabReading>> = readings.groupBy { it.block }.toSortedMap()

    /** One frame per line under a `# group` heading, after a `# Earshot Lab · stack` line. */
    fun text(profile: LabProfile?, readings: List<LabReading>): String = buildString {
        profile?.let { appendLine("# Earshot Lab · ${it.stack}") }
        grouped(readings).forEach { (group, list) ->
            appendLine("# " + heading(profile, group, list, separator = " ", unknown = ""))
            list.forEach { r ->
                r.frames.forEach { f ->
                    appendLine("${address(r)} ${functionName(r).orEmpty()} ${f.operatorName} ${if (f.isError) "ERROR " + f.errorName.orEmpty() + " " + f.payloadHex else f.payloadHex}".trim())
                }
            }
        }
    }
}
