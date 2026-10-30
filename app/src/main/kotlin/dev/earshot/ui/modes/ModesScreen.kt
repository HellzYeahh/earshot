@file:OptIn(ExperimentalMaterial3Api::class)

package dev.earshot.ui.modes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.earshot.R
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.HeadphoneCapabilities
import dev.earshot.core.HeadphoneState
import dev.earshot.core.ListeningMode
import dev.earshot.core.NoiseSettings
import dev.earshot.core.SpatialAudio
import dev.earshot.core.fake.FakeHeadphoneRepository
import dev.earshot.extras.HeadphoneLabels
import dev.earshot.ui.common.ConfirmDialog
import dev.earshot.ui.common.InfoBanner
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.EarshotViewModel
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.common.SubScreenScaffold
import dev.earshot.ui.common.UiMessenger
import dev.earshot.ui.common.earshotViewModel
import dev.earshot.ui.home.NoiseControls
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

class ModesViewModel(
    private val headphones: HeadphoneRepository,
    messenger: UiMessenger,
) : EarshotViewModel(messenger) {
    val state: StateFlow<HeadphoneState> = headphones.state
    fun setMode(index: Int) = launchCatching { headphones.setMode(index) }
    fun save(index: Int, name: String, settings: NoiseSettings, onDone: () -> Unit) = launchCatching {
        headphones.saveCustomMode(index, name, settings)
        messenger.show(R.string.modes_saved, name)
        onDone()
    }

    /** `[31.9]` START: the slot goes back to the firmware's empty state. */
    fun reset(index: Int, onDone: () -> Unit) = launchCatching {
        headphones.resetCustomMode(index)
        messenger.show(R.string.modes_reset_done, index)
        onDone()
    }
}

@Composable
fun ModesScreen(onBack: () -> Unit) {
    val vm = earshotViewModel { ModesViewModel(it.headphones, it.messenger) }
    val state by vm.state.collectAsStateWithLifecycle()
    // One save or reset at a time: a double tap on Save used to write the slot twice (state.isBusy
    // flips only once the command has started), and the second command's onDone could close an
    // editor opened since. Cleared when the command ends either way: on success the sheet has
    // closed, on failure (its Snackbar said why) Save can be retried. Plain remember on purpose:
    // after a rotation mid-save the new sheet must not stay locked. Unlike the automation and alarm
    // editors no lifecycle check is needed here: onDone only closes the sheet, it pops no screen.
    var busy by remember { mutableStateOf(false) }
    fun once(onDone: () -> Unit, command: (onDone: () -> Unit) -> Job) {
        if (busy) return
        busy = true
        command(onDone).invokeOnCompletion { busy = false }
    }
    ModesContent(
        state = state,
        onBack = onBack,
        onUse = vm::setMode,
        onSave = { index, name, settings, onDone -> once(onDone) { done -> vm.save(index, name, settings, done) } },
        onReset = { index, onDone -> once(onDone) { done -> vm.reset(index, done) } },
        busy = busy,
    )
}

@Composable
fun ModesContent(
    state: HeadphoneState,
    onBack: () -> Unit,
    onUse: (Int) -> Unit,
    onSave: (index: Int, name: String, settings: NoiseSettings, onDone: () -> Unit) -> Unit,
    onReset: (index: Int, onDone: () -> Unit) -> Unit = { _, _ -> },
    /** A save or reset is under way: the editor's buttons stay off so a second tap can't run it again. */
    busy: Boolean = false,
) {
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var confirmReset by rememberSaveable { mutableStateOf<Int?>(null) }
    val enabled = state.isConnected && !state.isBusy
    // A command's onDone closes the editor it was started from, never one opened since (the sheet
    // can be dismissed while the write is still on its way).
    fun closeEditor(index: Int) {
        if (editing == index) editing = null
    }

    SubScreenScaffold(title = stringResource(R.string.modes_title), onBack = onBack) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val caps = state.capabilities
            val hasModes = caps.listeningModes
            val buds = HeadphoneLabels.isEarbuds(HeadphoneLabels.formFactor(state))
            // Counted from what the model reported, not the QC Ultra's 4 + 7.
            val presetCount = state.modes.count { it.isPreset }
            val customCount = state.modes.count { !it.isPreset }
            item {
                Text(
                    when {
                        !hasModes -> stringResource(if (buds) R.string.home_modes_unavailable_buds else R.string.home_modes_unavailable)
                        else -> stringResource(
                            if (buds) R.string.modes_intro_counts_buds else R.string.modes_intro_counts,
                            pluralStringResource(R.plurals.modes_builtin_count, presetCount, presetCount),
                            pluralStringResource(R.plurals.modes_custom_count, customCount, customCount),
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
            // A model without custom slots gets a friendly note instead of an editor with nothing in it.
            if (hasModes && state.modes.isNotEmpty() && (customCount == 0 || !caps.editableModes)) item {
                InfoBanner(
                    stringResource(
                        when {
                            customCount > 0 -> R.string.modes_not_editable
                            buds -> R.string.modes_no_custom_buds
                            else -> R.string.modes_no_custom
                        },
                    ),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            // No mode list and no editor on firmware without listening modes.
            items(if (hasModes) state.modes else emptyList(), key = { it.index }) { mode ->
                ModeRow(
                    mode = mode,
                    isCurrent = mode.index == state.currentModeIndex,
                    enabled = enabled,
                    editable = caps.editableModes && mode.isEditable && !mode.isPreset,
                    onUse = { onUse(mode.index) },
                    onEdit = { editing = mode.index },
                )
            }
        }
    }

    val editIndex = editing
    if (editIndex != null && state.capabilities.listeningModes && state.capabilities.editableModes) {
        val mode = state.modes.firstOrNull { it.index == editIndex }
        ModeEditorSheet(
            slotIndex = editIndex,
            initialName = mode?.name.orEmpty(),
            initialSettings = mode?.settings ?: state.noise ?: NoiseSettings(),
            busy = state.isBusy || busy,
            capabilities = state.capabilities,
            onDismiss = { editing = null },
            onSave = { name, settings -> onSave(editIndex, name, settings) { closeEditor(editIndex) } },
            onReset = if (mode?.isConfigured == true) ({ confirmReset = editIndex }) else null,
        )
    }

    val resetIndex = confirmReset
    if (resetIndex != null) {
        val mode = state.modes.firstOrNull { it.index == resetIndex }
        ConfirmDialog(
            title = stringResource(R.string.modes_reset_slot_title, resetIndex),
            text = stringResource(R.string.modes_reset_slot_text, mode?.name.orEmpty()),
            confirmLabel = stringResource(R.string.modes_reset_slot),
            destructive = true,
            onConfirm = { onReset(resetIndex) { closeEditor(resetIndex) } },
            onDismiss = { confirmReset = null },
        )
    }
}

@Composable
private fun ModeRow(mode: ListeningMode, isCurrent: Boolean, enabled: Boolean, editable: Boolean, onUse: () -> Unit, onEdit: () -> Unit) {
    SectionCard(contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 64.dp)
                .clickable(enabled = editable) { onEdit() }
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.mode_slot_number, mode.index),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (mode.isFavorite) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.Star, contentDescription = stringResource(R.string.mode_favourite), modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    if (isCurrent) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.Check, contentDescription = stringResource(R.string.mode_current), modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(
                    if (mode.isConfigured) mode.name.ifBlank { stringResource(R.string.mode_slot_number, mode.index) } else stringResource(R.string.modes_empty_slot),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (mode.isConfigured) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val subtitle = when {
                    mode.isPreset -> stringResource(R.string.modes_builtin)
                    mode.settings != null -> describe(mode.settings)
                    editable -> stringResource(R.string.modes_tap_to_set_up)
                    else -> null
                }
                if (subtitle != null) Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (mode.isConfigured && !isCurrent) {
                TextButton(onClick = onUse, enabled = enabled) { Text(stringResource(R.string.action_use)) }
            }
            Icon(
                if (editable) Icons.Default.Edit else Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp).size(20.dp),
            )
        }
    }
}

@Composable
private fun describe(s: NoiseSettings): String {
    val parts = mutableListOf<String>()
    parts += if (s.ancEnabled) stringResource(R.string.modes_desc_cancelling, s.cancellingLevel) else stringResource(R.string.modes_desc_anc_off)
    if (s.windBlock) parts += stringResource(R.string.noise_wind_block)
    if (s.spatialAudio != dev.earshot.core.SpatialAudio.OFF) parts += stringResource(R.string.modes_desc_immersive, s.spatialAudio.label)
    return parts.joinToString(" · ")
}

/** Custom-slot editor. Names are at most 31 bytes of UTF-8 on the wire (40-byte slot payload). */
@Composable
fun ModeEditorSheet(
    slotIndex: Int,
    initialName: String,
    initialSettings: NoiseSettings,
    busy: Boolean,
    capabilities: HeadphoneCapabilities = HeadphoneCapabilities(),
    onDismiss: () -> Unit,
    onSave: (String, NoiseSettings) -> Unit,
    /** Shown as a destructive "Reset slot" button when non-null (configured custom slots only). */
    onReset: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by rememberSaveable(slotIndex) { mutableStateOf(initialName) }
    // Saved like the name: in plain remember a rotation or fold put the slot's stored noise
    // settings back while the typed name stayed, so Save wrote a half-edited mode.
    var settings by rememberSaveable(slotIndex, saver = NoiseSettingsSaver) { mutableStateOf(initialSettings) }
    val bytes = name.toByteArray(Charsets.UTF_8).size
    val nameValid = name.isNotBlank() && bytes <= MAX_NAME_BYTES

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, contentWindowInsets = { WindowInsets(0) }) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Text(stringResource(R.string.modes_edit_slot, slotIndex), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.modes_name_label)) },
                singleLine = true,
                isError = bytes > MAX_NAME_BYTES,
                supportingText = { Text(stringResource(R.string.modes_name_counter, bytes, MAX_NAME_BYTES)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            NoiseControls(settings = settings, enabled = !busy, onChange = { settings = it }, capabilities = capabilities)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (onReset != null) {
                    TextButton(onClick = onReset, enabled = !busy) {
                        Text(stringResource(R.string.modes_reset_slot), color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.weight(1f))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(shape = MaterialTheme.shapes.medium, onClick = { onSave(name.trim(), settings) }, enabled = nameValid && !busy) {
                    Text(stringResource(R.string.action_save))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private const val MAX_NAME_BYTES = 31

/**
 * The editor's noise settings across a rotation or process death, as four numbers: level,
 * cancelling on, wind block, spatial audio by its frozen [SpatialAudio.storedId] (never the Bose
 * wire code). Pure so it is unit-tested.
 */
internal object NoiseSettingsSaving {
    fun encode(s: NoiseSettings): IntArray =
        intArrayOf(s.cancellingLevel, if (s.ancEnabled) 1 else 0, if (s.windBlock) 1 else 0, s.spatialAudio.storedId)

    /** Null for anything this build didn't write, so the editor starts again from the slot. */
    fun decode(saved: IntArray): NoiseSettings? {
        if (saved.size != 4) return null
        return NoiseSettings(
            // NoiseSettings refuses a level outside 0..10; a restore must never crash the sheet.
            cancellingLevel = saved[0].coerceIn(0, 10),
            ancEnabled = saved[1] != 0,
            windBlock = saved[2] != 0,
            spatialAudio = SpatialAudio.fromStoredId(saved[3]),
        )
    }
}

/**
 * The whole state, so an unreadable saved value returns null and rememberSaveable runs its
 * initializer again (a `stateSaver` would put the null into the state instead).
 */
private val NoiseSettingsSaver = Saver<MutableState<NoiseSettings>, IntArray>(
    save = { NoiseSettingsSaving.encode(it.value) },
    restore = { saved -> NoiseSettingsSaving.decode(saved)?.let { mutableStateOf(it) } },
)

@LightDarkPreview
@Composable
private fun ModesPreview() {
    EarshotPreview {
        ModesContent(state = FakeHeadphoneRepository.connectedState(), onBack = {}, onUse = {}, onSave = { _, _, _, _ -> })
    }
}

/** A QC45-style model: two presets and no custom slots, so the note replaces the editor. */
@LightDarkPreview
@Composable
private fun ModesNoCustomPreview() {
    EarshotPreview {
        val buds = FakeHeadphoneRepository.connectedEarbudsState()
        ModesContent(
            state = buds.copy(modes = buds.modes.filter { it.index < 2 }),
            onBack = {}, onUse = {}, onSave = { _, _, _, _ -> },
        )
    }
}
