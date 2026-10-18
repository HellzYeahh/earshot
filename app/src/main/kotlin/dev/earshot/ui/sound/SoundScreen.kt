@file:OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package dev.earshot.ui.sound

import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.earshot.R
import dev.earshot.core.AppSoundProfile
import dev.earshot.core.HeadphoneEq
import dev.earshot.core.HeadphoneState
import dev.earshot.core.TuningState
import dev.earshot.core.fake.FakeAudioTuningRepository
import dev.earshot.core.fake.FakeHeadphoneRepository
import dev.earshot.dsp.EqPreset
import dev.earshot.dsp.Headroom
import dev.earshot.dsp.ParametricBand
import dev.earshot.dsp.TuningSettings
import dev.earshot.extras.HeadphoneLabels
import dev.earshot.ui.common.AppEntry
import dev.earshot.ui.common.AppPickerDialog
import dev.earshot.ui.common.BannerTone
import dev.earshot.ui.common.ConfirmDialog
import dev.earshot.ui.common.DropdownField
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.EmptyHint
import dev.earshot.ui.common.InfoBanner
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.common.SegmentedChoice
import dev.earshot.ui.common.SwitchRow
import dev.earshot.ui.common.ValueSlider
import dev.earshot.ui.common.earshotViewModel
import dev.earshot.ui.common.rememberInstalledApps
import dev.earshot.ui.common.rememberSettingsLoaded
import kotlin.math.roundToInt

@Composable
fun SoundScreen(contentPadding: PaddingValues) {
    val vm = earshotViewModel { SoundViewModel(it.headphones, it.tuning, it.messenger) }
    val headphoneState by vm.headphoneState.collectAsStateWithLifecycle()
    val headphoneEq by vm.headphoneEq.collectAsStateWithLifecycle()
    val tuningState by vm.tuningState.collectAsStateWithLifecycle()
    val bands by vm.bands.collectAsStateWithLifecycle()
    val presets by vm.presets.collectAsStateWithLifecycle()
    val profiles by vm.appProfiles.collectAsStateWithLifecycle()
    val installedApps by rememberInstalledApps()

    SoundContent(
        headphoneState = headphoneState,
        headphoneEq = headphoneEq,
        tuningState = tuningState,
        bands = bands,
        presets = presets,
        profiles = profiles,
        installedApps = installedApps,
        contentPadding = contentPadding,
        callbacks = SoundCallbacks(
            onHeadphoneEq = vm::setHeadphoneEq,
            onProEqEnabled = vm::setProEqEnabled,
            onUseGlobalSession = vm::setUseGlobalSession,
            onAutoPreamp = vm::autoPreamp,
            onImportAutoEq = vm::importAutoEq,
            onApplyPreset = vm::applyPreset,
            onDeletePreset = vm::deletePreset,
            onBands = vm::setBands,
            onPreamp = vm::setPreamp,
            onSavePreset = vm::savePreset,
            onBoost = vm::setBoost,
            onLimiter = vm::setLimiter,
            onSetProfile = vm::setAppProfile,
            onRemoveProfile = vm::removeAppProfile,
        ),
    )
}

/** Bundled so the stateless content and its previews stay readable. */
data class SoundCallbacks(
    val onHeadphoneEq: (HeadphoneEq) -> Unit = {},
    val onProEqEnabled: (Boolean) -> Unit = {},
    val onUseGlobalSession: (Boolean) -> Unit = {},
    val onAutoPreamp: () -> Unit = {},
    val onImportAutoEq: (text: String, name: String) -> Unit = { _, _ -> },
    val onApplyPreset: (String?) -> Unit = {},
    val onDeletePreset: (EqPreset) -> Unit = {},
    val onBands: (List<ParametricBand>) -> Unit = {},
    val onPreamp: (Double) -> Unit = {},
    val onSavePreset: (String) -> Unit = {},
    val onBoost: (Double) -> Unit = {},
    val onLimiter: (Boolean) -> Unit = {},
    val onSetProfile: (AppSoundProfile) -> Unit = {},
    val onRemoveProfile: (String) -> Unit = {},
)

private enum class EditorMode { GRAPHIC, PARAMETRIC }

@Composable
fun SoundContent(
    headphoneState: HeadphoneState,
    headphoneEq: HeadphoneEq?,
    tuningState: TuningState,
    bands: List<ParametricBand>,
    presets: List<EqPreset>,
    profiles: List<AppSoundProfile>,
    installedApps: List<AppEntry>,
    contentPadding: PaddingValues,
    callbacks: SoundCallbacks,
) {
    val settingsLoaded = rememberSettingsLoaded(headphoneState)
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(stringResource(R.string.tab_sound), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)) }
        // The headphones' own EQ only where the model has one ([1.7] answered); the Pro EQ below is
        // phone-side and works with any headphones, so it always stays.
        if (!settingsLoaded || headphoneState.eq != null) item {
            HeadphoneEqSection(headphoneState, headphoneEq, callbacks.onHeadphoneEq)
        }
        item { ProEqSection(tuningState, bands, presets, callbacks) }
        item { BoostSection(tuningState.settings, bands, callbacks.onBoost, callbacks.onLimiter) }
        item { AppProfilesSection(profiles, presets, installedApps, callbacks.onSetProfile, callbacks.onRemoveProfile) }
    }
}

// ---- A. Headphone EQ ------------------------------------------------------------------------

@Composable
private fun HeadphoneEqSection(state: HeadphoneState, eq: HeadphoneEq?, onChange: (HeadphoneEq) -> Unit) {
    val enabled = state.isConnected && eq != null
    val value = eq ?: HeadphoneEq()
    val range = HeadphoneEqRange.of(state.eqRange)
    val buds = HeadphoneLabels.isEarbuds(HeadphoneLabels.formFactor(state))
    SectionCard(title = stringResource(R.string.sound_headphone_eq)) {
        Text(
            stringResource(if (buds) R.string.sound_headphone_eq_subtitle_buds else R.string.sound_headphone_eq_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        HeadphoneBandSlider(stringResource(R.string.sound_bass), value.bass, range, enabled) { onChange(value.copy(bass = it)) }
        HeadphoneBandSlider(stringResource(R.string.sound_mid), value.mid, range, enabled) { onChange(value.copy(mid = it)) }
        HeadphoneBandSlider(stringResource(R.string.sound_treble), value.treble, range, enabled) { onChange(value.copy(treble = it)) }
    }
}

@Composable
private fun HeadphoneBandSlider(label: String, value: Int, range: IntRange, enabled: Boolean, onChange: (Int) -> Unit) {
    var drag by remember(value) { mutableFloatStateOf(value.coerceIn(range).toFloat()) }
    ValueSlider(
        label = label,
        value = drag,
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = HeadphoneEqRange.steps(range),
        enabled = enabled,
        valueText = formatDb(drag.roundToInt().toDouble()),
        onValueChange = {
            drag = it
            // Live: the ViewModel debounces the actual write.
            val rounded = it.roundToInt().coerceIn(range)
            if (rounded != value) onChange(rounded)
        },
    )
}

// ---- B. Pro EQ ------------------------------------------------------------------------------

@Composable
private fun ProEqSection(state: TuningState, bands: List<ParametricBand>, presets: List<EqPreset>, callbacks: SoundCallbacks) {
    val settings = state.settings
    val enabled = settings.enabled && state.isAvailable
    var mode by rememberSaveable { mutableStateOf(EditorMode.GRAPHIC) }
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    var presetToDelete by remember { mutableStateOf<EqPreset?>(null) }

    SectionCard(title = stringResource(R.string.sound_pro_eq)) {
        SwitchRow(
            title = stringResource(R.string.sound_pro_eq_enable),
            subtitle = when {
                !state.isAvailable -> stringResource(R.string.sound_pro_eq_unavailable)
                settings.enabled && state.attachedSessions > 0 -> stringResource(R.string.sound_pro_eq_attached, state.attachedSessions)
                settings.enabled -> stringResource(R.string.sound_pro_eq_waiting)
                else -> stringResource(R.string.sound_pro_eq_subtitle)
            },
            // Always operable: when the effect is unavailable the subtitle says so, but the user
            // must still be able to switch the engine off (and the flag recovers on the next attach).
            checked = settings.enabled,
            onCheckedChange = callbacks.onProEqEnabled,
        )
        // Global audio session (0) catches apps that never broadcast their session; it is a
        // best-effort path on most phones, hence "experimental" and the engine's warning below it.
        SwitchRow(
            title = stringResource(R.string.sound_global_session),
            subtitle = stringResource(R.string.sound_global_session_subtitle),
            checked = state.useGlobalSession,
            enabled = state.isAvailable && settings.enabled,
            onCheckedChange = callbacks.onUseGlobalSession,
        )
        if (state.warning != null) {
            InfoBanner(state.warning, tone = BannerTone.WARNING, modifier = Modifier.padding(bottom = 8.dp))
        }

        // Presets: built-in first, then saved; long-press a saved one to delete it.
        PresetChips(
            presets = presets,
            activeId = settings.activePresetId,
            enabled = enabled,
            onSelect = callbacks.onApplyPreset,
            onLongPress = { if (!it.isBuiltIn) presetToDelete = it },
        )
        Spacer(Modifier.height(8.dp))
        EqCurveCanvas(bands = bands, preampDb = settings.preampDb, enabled = enabled)
        Spacer(Modifier.height(8.dp))
        SegmentedChoice(
            options = EditorMode.entries,
            selected = mode,
            onSelect = { mode = it },
            label = { stringResource(if (it == EditorMode.GRAPHIC) R.string.sound_mode_graphic else R.string.sound_mode_parametric) },
            enabled = enabled,
        )
        Spacer(Modifier.height(12.dp))
        when (mode) {
            EditorMode.GRAPHIC -> GraphicEqEditor(bands = bands, preampDb = settings.preampDb, enabled = enabled, onBandsChange = callbacks.onBands)
            EditorMode.PARAMETRIC -> ParametricEqEditor(bands = bands, enabled = enabled, onBandsChange = callbacks.onBands)
        }
        Spacer(Modifier.height(8.dp))
        var preamp by remember(settings.preampDb) { mutableFloatStateOf(settings.preampDb.toFloat()) }
        ValueSlider(
            label = stringResource(R.string.sound_preamp),
            value = preamp,
            valueRange = -20f..0f, // the repository clamps to <= 0 dB; a positive preamp is the boost's job
            steps = 39,
            enabled = enabled,
            valueText = formatDb((preamp * 2).roundToInt() / 2.0, 1) + " dB",
            onValueChange = { preamp = it },
            onValueChangeFinished = { callbacks.onPreamp((preamp * 2).roundToInt() / 2.0) },
        )
        val recommended = remember(bands) { Headroom.recommendedPreampDb(bands) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.sound_preamp_recommended, formatDb(recommended, 1)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = callbacks.onAutoPreamp, enabled = enabled && recommended != settings.preampDb, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.sound_preamp_auto))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            OutlinedButton(onClick = { showImportDialog = true }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.sound_import_autoeq))
            }
            OutlinedButton(onClick = { showSaveDialog = true }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.sound_save_preset))
            }
        }
    }

    if (showSaveDialog) {
        SavePresetDialog(onDismiss = { showSaveDialog = false }, onSave = { showSaveDialog = false; callbacks.onSavePreset(it) })
    }
    if (showImportDialog) {
        ImportAutoEqDialog(onDismiss = { showImportDialog = false }, onImport = { text, name -> showImportDialog = false; callbacks.onImportAutoEq(text, name) })
    }
    presetToDelete?.let { preset ->
        ConfirmDialog(
            title = stringResource(R.string.sound_delete_preset_title),
            text = stringResource(R.string.sound_delete_preset_text, preset.name),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = { callbacks.onDeletePreset(preset) },
            onDismiss = { presetToDelete = null },
        )
    }
}

@Composable
private fun PresetChips(
    presets: List<EqPreset>,
    activeId: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
    onLongPress: (EqPreset) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        presets.sortedByDescending { it.isBuiltIn }.forEach { preset ->
            val selected = preset.id == activeId
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .defaultMinSize(minHeight = 40.dp)
                    .combinedClickable(
                        enabled = enabled,
                        onClick = { onSelect(preset.id) },
                        onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongPress(preset) },
                    ),
            ) {
                Text(
                    preset.name,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
    Text(
        stringResource(R.string.sound_presets_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun SavePresetDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sound_save_preset)) },
        text = {
            // Scrollable + imePadding: the keyboard shrinks the dialog instead of pushing the buttons under the finger.
            Column(Modifier.verticalScroll(rememberScrollState()).imePadding()) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.sound_preset_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Paste the contents of an AutoEq "ParametricEQ.txt"; the DSP parser does the rest. */
@Composable
private fun ImportAutoEqDialog(onDismiss: () -> Unit, onImport: (text: String, name: String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sound_import_autoeq)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).imePadding()) {
                Text(stringResource(R.string.sound_import_autoeq_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.sound_preset_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.sound_import_autoeq_paste)) },
                    minLines = 4, maxLines = 8,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onImport(text, name) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.action_import)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ---- C. Volume boost ------------------------------------------------------------------------

@Composable
private fun BoostSection(settings: TuningSettings, bands: List<ParametricBand>, onBoost: (Double) -> Unit, onLimiter: (Boolean) -> Unit) {
    // Without the limiter the DSP module caps the boost lower; the slider follows that cap.
    val cap = Headroom.effectiveBoostCapDb(settings.limiterEnabled)
    var boost by remember(settings.boostDb, cap) { mutableFloatStateOf(settings.boostDb.coerceAtMost(cap).toFloat()) }
    val overshoot = remember(bands, settings.preampDb, settings.boostDb) { Headroom.worstCaseOvershootDb(bands, settings.preampDb, settings.boostDb) }
    val enabled = settings.enabled
    SectionCard(title = stringResource(R.string.sound_boost)) {
        if (!enabled) {
            Text(stringResource(R.string.sound_boost_needs_pro_eq), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        }
        if (boost > TuningSettings.BOOST_WARNING_DB) {
            InfoBanner(
                stringResource(R.string.sound_boost_warning),
                tone = BannerTone.ERROR,
                icon = Icons.Default.Warning,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        ValueSlider(
            label = stringResource(R.string.sound_boost_level),
            value = boost,
            valueRange = 0f..cap.toFloat(),
            steps = (cap * 2).toInt() - 1,
            enabled = enabled,
            valueText = formatDb((boost * 2).roundToInt() / 2.0, 1) + " dB",
            onValueChange = { boost = it },
            onValueChangeFinished = { onBoost(Headroom.clampBoost((boost * 2).roundToInt() / 2.0, settings.limiterEnabled)) },
        )
        if (!settings.limiterEnabled) {
            Text(
                stringResource(R.string.sound_boost_cap_no_limiter, formatDb(cap)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // "Relying on the limiter" only makes sense while there is one; without it the cap note above applies.
        if (overshoot > 0.0 && enabled && settings.limiterEnabled) {
            Text(
                stringResource(R.string.sound_boost_overshoot, formatDb(overshoot, 1)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        SwitchRow(
            title = stringResource(R.string.sound_limiter),
            subtitle = stringResource(R.string.sound_limiter_subtitle),
            checked = settings.limiterEnabled,
            enabled = enabled,
            onCheckedChange = onLimiter,
        )
        if ("samsung".equals(Build.MANUFACTURER, ignoreCase = true)) {
            Text(
                stringResource(R.string.sound_boost_samsung_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---- D. Per-app profiles --------------------------------------------------------------------

@Composable
private fun AppProfilesSection(
    profiles: List<AppSoundProfile>,
    presets: List<EqPreset>,
    installedApps: List<AppEntry>,
    onSet: (AppSoundProfile) -> Unit,
    onRemove: (String) -> Unit,
) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var pendingApp by remember { mutableStateOf<AppEntry?>(null) }

    SectionCard(
        title = stringResource(R.string.sound_app_profiles),
        trailing = {
            TextButton(onClick = { showPicker = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.action_add))
            }
        },
    ) {
        Text(stringResource(R.string.sound_app_profiles_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (profiles.isEmpty()) {
            EmptyHint(stringResource(R.string.sound_app_profiles_empty))
        }
        profiles.sortedBy { it.appLabel.lowercase() }.forEach { profile ->
            Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(profile.appLabel, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(profile.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownField(
                    label = stringResource(R.string.sound_preset),
                    options = presets,
                    selected = presets.firstOrNull { it.id == profile.presetId },
                    onSelect = { onSet(profile.copy(presetId = it.id)) },
                    optionLabel = { it.name },
                    modifier = Modifier.width(150.dp),
                )
                IconButton(onClick = { onRemove(profile.packageName) }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.sound_remove_profile, profile.appLabel))
                }
            }
        }
    }

    if (showPicker) {
        AppPickerDialog(
            apps = installedApps,
            onPick = { label, pkg -> showPicker = false; pendingApp = label to pkg },
            onDismiss = { showPicker = false },
        )
    }
    pendingApp?.let { (label, pkg) ->
        var chosen by remember { mutableStateOf(presets.firstOrNull()) }
        AlertDialog(
            onDismissRequest = { pendingApp = null },
            title = { Text(label) },
            text = {
                Column {
                    Text(stringResource(R.string.sound_app_profile_pick_preset), modifier = Modifier.padding(bottom = 8.dp))
                    DropdownField(label = stringResource(R.string.sound_preset), options = presets, selected = chosen, onSelect = { chosen = it }, optionLabel = { it.name })
                }
            },
            confirmButton = {
                Button(onClick = { chosen?.let { onSet(AppSoundProfile(pkg, label, it.id)) }; pendingApp = null }, enabled = chosen != null) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = { TextButton(onClick = { pendingApp = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@LightDarkPreview
@Composable
private fun SoundPreview() {
    val tuning = remember { FakeAudioTuningRepository() }
    val presets by tuning.presets.collectAsStateWithLifecycle()
    EarshotPreview {
        SoundContent(
            headphoneState = FakeHeadphoneRepository.connectedState(),
            headphoneEq = HeadphoneEq(2, 0, -1),
            tuningState = TuningState(settings = TuningSettings(enabled = true, bands = presets[1].bands, preampDb = -4.0, boostDb = 7.0), activePreset = presets[1], attachedSessions = 1),
            bands = presets[1].bands,
            presets = presets,
            profiles = listOf(AppSoundProfile("com.spotify.music", "Spotify", "bass")),
            installedApps = emptyList(),
            contentPadding = PaddingValues(0.dp),
            callbacks = SoundCallbacks(),
        )
    }
}
