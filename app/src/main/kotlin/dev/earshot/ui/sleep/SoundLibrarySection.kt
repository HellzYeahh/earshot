@file:OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package dev.earshot.ui.sleep

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.core.CustomSound
import dev.earshot.core.SavedMix
import dev.earshot.core.SleepLayer
import dev.earshot.core.SleepPlan
import dev.earshot.core.SoundSide
import dev.earshot.core.fake.FakeSleepRepository
import dev.earshot.dsp.sleep.LoopMaker
import dev.earshot.dsp.sleep.SleepSound
import dev.earshot.dsp.sleep.SleepSoundGroup
import dev.earshot.ui.common.ConfirmDialog
import dev.earshot.ui.common.DashedDivider
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.common.SegmentedChoice
import dev.earshot.ui.common.ValueSlider
import dev.earshot.ui.theme.EarshotTheme

/** What the sound library can ask of the screen. */
class SoundLibraryActions(
    val onToggleSound: (String) -> Unit = {},
    val onLayerGain: (String, Float) -> Unit = { _, _ -> },
    val onRemoveLayer: (String) -> Unit = {},
    val onTimerOnly: () -> Unit = {},
    val onTogglePreview: () -> Unit = {},
    val onLoadMix: (SavedMix) -> Unit = {},
    val onSaveMix: (String) -> Unit = {},
    val onDeleteMix: (SavedMix) -> Unit = {},
    /** Opens the file picker for the user's own sound. */
    val onAddCustom: () -> Unit = {},
    val onDeleteCustom: (CustomSound) -> Unit = {},
)

/**
 * The sound library: tap tiles to build a mix of up to [SleepPlan.MAX_LAYERS] sounds, each with
 * its own level, audition it with the preview, and keep mixes for one-tap reuse. "Yours" holds the
 * user's own sounds and the tile that adds one from a file. "No sound" leaves only the timer, for
 * a podcast or music from another app.
 */
@Composable
fun SoundLibrarySection(
    plan: SleepPlan,
    mixes: List<SavedMix>,
    previewing: Boolean,
    onPlanChange: (SleepPlan) -> Unit,
    actions: SoundLibraryActions,
    modifier: Modifier = Modifier,
    customSounds: List<CustomSound> = emptyList(),
    /** An own sound is being copied and decoded. */
    importing: Boolean = false,
) {
    // A SleepSoundGroup name, YOURS, or null for all.
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var showSave by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SavedMix?>(null) }
    var deletingCustom by remember { mutableStateOf<CustomSound?>(null) }
    val chosen = plan.layers.map { it.soundId }.toSet()

    SectionCard(modifier = modifier, title = stringResource(R.string.sleep_sounds_title)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = group == null, onClick = { group = null }, label = { Text(stringResource(R.string.sleep_group_all)) })
            SleepSoundGroup.entries.forEach { g ->
                FilterChip(selected = group == g.name, onClick = { group = g.name }, label = { Text(stringResource(g.labelRes())) })
            }
            FilterChip(selected = group == YOURS, onClick = { group = YOURS }, label = { Text(stringResource(R.string.sleep_group_yours)) })
        }
        Spacer(Modifier.height(8.dp))
        val tiles: List<LibraryTile> = buildList {
            if (group != YOURS) SleepSound.entries.filter { group == null || it.group.name == group }.forEach { add(LibraryTile.Builtin(it)) }
            if (group == null || group == YOURS) {
                customSounds.forEach { add(LibraryTile.Own(it)) }
                add(LibraryTile.AddOwn)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.chunked(GRID_COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { tile ->
                        val cell = Modifier.weight(1f)
                        when (tile) {
                            is LibraryTile.Builtin -> SoundTile(tile.sound, selected = tile.sound.storedId in chosen, onToggle = { actions.onToggleSound(tile.sound.storedId) }, modifier = cell)
                            is LibraryTile.Own -> CustomSoundTile(
                                tile.sound,
                                selected = tile.sound.id in chosen,
                                onToggle = { actions.onToggleSound(tile.sound.id) },
                                onDelete = { deletingCustom = tile.sound },
                                modifier = cell,
                            )
                            LibraryTile.AddOwn -> AddCustomSoundTile(importing = importing, onClick = actions.onAddCustom, modifier = cell)
                        }
                    }
                    repeat(GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (group == YOURS) {
            Text(
                pluralStringResource(R.plurals.sleep_custom_hint, LoopMaker.MAX_SECONDS, LoopMaker.MAX_SECONDS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        FilterChip(
            selected = plan.layers.isEmpty(),
            onClick = actions.onTimerOnly,
            label = { Text(stringResource(R.string.sleep_timer_only)) },
        )

        ChoiceHeading(stringResource(R.string.sleep_mix_heading, plan.layers.size, SleepPlan.MAX_LAYERS))
        if (plan.layers.isEmpty()) {
            Text(stringResource(R.string.sleep_timer_only_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        plan.layers.forEach { layer ->
            // Keyed, so removing the first layer doesn't hand its slider state to the next one.
            key(layer.soundId) {
                LayerRow(layer, onGain = { actions.onLayerGain(layer.soundId, it) }, onRemove = { actions.onRemoveLayer(layer.soundId) })
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = actions.onTogglePreview,
                enabled = previewing || plan.layers.isNotEmpty(),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Icon(if (previewing) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(if (previewing) R.string.sleep_preview_stop else R.string.sleep_preview_play))
            }
            TextButton(onClick = { showSave = true }, enabled = plan.layers.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.sleep_mix_save))
            }
        }

        ChoiceHeading(stringResource(R.string.sleep_mixes_heading))
        if (mixes.isEmpty()) {
            Text(stringResource(R.string.sleep_mixes_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                mixes.forEach { mix ->
                    MixChip(
                        mix = mix,
                        selected = SleepLogic.sameSounds(plan.layers, mix.layers),
                        onClick = { actions.onLoadMix(mix) },
                        onLongClick = { deleting = mix },
                    )
                }
            }
            Text(
                stringResource(R.string.sleep_mixes_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        DashedDivider()
        SidePicker(plan, onPlanChange)
        VolumeSlider(plan, onPlanChange)
    }

    if (showSave) {
        val suggestion = plan.layers.map { soundName(it.soundId) }.joinToString(" + ")
        SaveMixDialog(suggestion, onSave = actions.onSaveMix, onDismiss = { showSave = false })
    }
    deletingCustom?.let { sound ->
        CustomSoundDeleteDialog(sound, onDelete = { actions.onDeleteCustom(sound) }, onDismiss = { deletingCustom = null })
    }
    deleting?.let { mix ->
        ConfirmDialog(
            title = stringResource(R.string.sleep_mix_delete_title),
            text = stringResource(R.string.sleep_mix_delete_text, mix.name),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = { actions.onDeleteMix(mix) },
            onDismiss = { deleting = null },
        )
    }
}

private const val GRID_COLUMNS = 3

/** The "Yours" group chip's key (next to the SleepSoundGroup names). */
private const val YOURS = "yours"

/** One cell of the library grid. */
private sealed interface LibraryTile {
    data class Builtin(val sound: SleepSound) : LibraryTile
    data class Own(val sound: CustomSound) : LibraryTile
    data object AddOwn : LibraryTile
}

@Composable
private fun SoundTile(sound: SleepSound, selected: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val colors = EarshotTheme.colors
    Surface(
        checked = selected,
        onCheckedChange = { onToggle() },
        shape = MaterialTheme.shapes.small,
        color = if (selected) colors.control else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) colors.onControl else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, if (selected) colors.control else colors.hairline),
        modifier = modifier.aspectRatio(1.15f),
    ) {
        Column(
            Modifier.padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(sound.icon(), contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(sound.labelRes()),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LayerRow(layer: SleepLayer, onGain: (Float) -> Unit, onRemove: () -> Unit) {
    // Tracks the drag locally; the plan (and a running preview) changes when the finger lifts.
    var drag by remember(layer.gain) { mutableFloatStateOf(layer.gain) }
    val name = soundName(layer.soundId)
    Row(verticalAlignment = Alignment.CenterVertically) {
        ValueSlider(
            label = name,
            value = drag,
            valueRange = 0f..1f,
            onValueChange = { drag = it },
            onValueChangeFinished = { onGain(drag) },
            valueText = stringResource(R.string.sleep_percent, (drag * 100).toInt()),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.sleep_layer_remove, name))
        }
    }
}

@Composable
private fun MixChip(mix: SavedMix, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = EarshotTheme.colors
    val shape = MaterialTheme.shapes.small
    Surface(
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, colors.hairline),
        modifier = Modifier
            .clip(shape)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.sleep_mix_load),
                onLongClickLabel = stringResource(R.string.sleep_mix_delete),
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .semantics { this.selected = selected },
    ) {
        Text(
            mix.name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun SidePicker(plan: SleepPlan, onPlanChange: (SleepPlan) -> Unit) {
    ChoiceHeading(stringResource(R.string.sleep_side_heading))
    SegmentedChoice(
        options = SoundSide.entries,
        selected = plan.soundSide,
        onSelect = { onPlanChange(plan.copy(soundSide = it)) },
        label = {
            stringResource(
                when (it) {
                    SoundSide.BOTH -> R.string.sleep_side_both
                    SoundSide.MONO -> R.string.sleep_side_mono
                    SoundSide.LEFT_ONLY -> R.string.sleep_side_left
                    SoundSide.RIGHT_ONLY -> R.string.sleep_side_right
                },
            )
        },
    )
    Text(
        stringResource(
            when (plan.soundSide) {
                SoundSide.BOTH -> R.string.sleep_side_both_hint
                SoundSide.MONO -> R.string.sleep_side_mono_hint
                SoundSide.LEFT_ONLY, SoundSide.RIGHT_ONLY -> R.string.sleep_side_one_hint
            },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
    )
}

@Composable
private fun VolumeSlider(plan: SleepPlan, onPlanChange: (SleepPlan) -> Unit) {
    var drag by remember(plan.volume) { mutableFloatStateOf(plan.volume) }
    ValueSlider(
        label = stringResource(R.string.sleep_volume),
        value = drag,
        valueRange = 0f..1f,
        onValueChange = { drag = it },
        onValueChangeFinished = { onPlanChange(plan.copy(volume = drag)) },
        valueText = stringResource(R.string.sleep_percent, (drag * 100).toInt()),
    )
    Text(
        stringResource(R.string.sleep_volume_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SaveMixDialog(suggestion: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(suggestion) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sleep_mix_save_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_MIX_NAME) },
                label = { Text(stringResource(R.string.sleep_mix_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onSave(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.sleep_mix_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val MAX_MIX_NAME = 40

// ---- Previews ---------------------------------------------------------------------------------

// Read outside composition (lint: StateFlow.value in a composable).
private val previewFake = FakeSleepRepository()
private val previewPlan = previewFake.plan.value
private val previewMixes = previewFake.mixes.value

@LightDarkPreview
@Composable
private fun SoundLibraryPreview() = EarshotPreview {
    SoundLibrarySection(
        plan = previewPlan,
        mixes = previewMixes,
        previewing = true,
        onPlanChange = {},
        actions = SoundLibraryActions(),
        modifier = Modifier.padding(16.dp),
    )
}
