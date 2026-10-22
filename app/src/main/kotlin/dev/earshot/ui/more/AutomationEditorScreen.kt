@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.earshot.ui.more

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.earshot.R
import dev.earshot.core.AutomationAction
import dev.earshot.core.AutomationRule
import dev.earshot.core.ListeningMode
import dev.earshot.core.NoiseSettings
import dev.earshot.core.SpatialAudio
import dev.earshot.core.Trigger
import dev.earshot.dsp.EqPreset
import dev.earshot.ui.common.AppPickerDialog
import dev.earshot.ui.common.BannerTone
import dev.earshot.ui.common.ConfirmDialog
import dev.earshot.ui.common.DropdownField
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.InfoBanner
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.common.SegmentedChoice
import dev.earshot.ui.common.SubScreenScaffold
import dev.earshot.ui.common.earshotViewModel
import dev.earshot.ui.common.rememberInstalledApps
import dev.earshot.ui.home.NoiseControls
import dev.earshot.ui.onboarding.Permissions
import java.util.Locale
import java.util.UUID

private enum class TriggerKind { CONNECT, WIFI, APP, SCHEDULE }
private enum class ActionKind { MODE, EQ, NOISE }

@Composable
fun AutomationEditorScreen(ruleId: String?, onBack: () -> Unit) {
    val vm = earshotViewModel { AutomationsViewModel(it.automation, it.headphones, it.tuning, it.messenger) }
    val rules by vm.rules.collectAsStateWithLifecycle()
    val headphones by vm.headphoneState.collectAsStateWithLifecycle()
    val presets by vm.presets.collectAsStateWithLifecycle()
    val installedApps by rememberInstalledApps()
    val existing = remember(ruleId, rules) { rules.firstOrNull { it.id == ruleId } }
    AutomationEditorContent(
        existing = existing,
        modes = headphones.modes,
        presets = presets,
        installedApps = installedApps,
        hasUsageAccess = vm::hasUsageAccess,
        onOpenUsageAccess = vm::openUsageAccessSettings,
        onBack = onBack,
        onSave = { rule -> vm.save(rule, onBack) },
        onDelete = { id -> vm.delete(id, onBack) },
    )
}

@Composable
fun AutomationEditorContent(
    existing: AutomationRule?,
    modes: List<ListeningMode>,
    presets: List<EqPreset>,
    installedApps: List<Pair<String, String>>,
    hasUsageAccess: () -> Boolean,
    onOpenUsageAccess: () -> Unit,
    onBack: () -> Unit,
    onSave: (AutomationRule) -> Unit,
    onDelete: (String) -> Unit,
) {
    var name by rememberSaveable(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var trigger by remember(existing) { mutableStateOf(existing?.trigger ?: Trigger.OnConnect) }
    var actions by remember(existing) { mutableStateOf(existing?.actions ?: listOf(AutomationAction.SetMode(ListeningMode.QUIET))) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    val canSave = name.isNotBlank() && actions.isNotEmpty() && triggerIsComplete(trigger)

    SubScreenScaffold(
        title = stringResource(if (existing == null) R.string.automation_new else R.string.automation_edit),
        onBack = onBack,
        actions = {
            if (existing != null) {
                IconButton(onClick = { showDelete = true }) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete)) }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp, vertical = 8.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.automation_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())

            SectionCard(title = stringResource(R.string.automation_when)) {
                TriggerEditor(trigger, installedApps, hasUsageAccess, onOpenUsageAccess) { trigger = it }
            }

            SectionCard(title = stringResource(R.string.automation_then)) {
                actions.forEachIndexed { index, action ->
                    ActionEditor(
                        action = action,
                        modes = modes,
                        presets = presets,
                        onChange = { updated -> actions = actions.toMutableList().also { it[index] = updated } },
                        onRemove = { actions = actions.toMutableList().also { it.removeAt(index) } },
                    )
                }
                AddActionButtons(existing = actions) { actions = actions + it }
            }

            Button(shape = MaterialTheme.shapes.medium, onClick = {
                onSave(AutomationRule(id = existing?.id ?: UUID.randomUUID().toString(), name = name.trim(), enabled = existing?.enabled ?: true, trigger = trigger, actions = actions))
            }, enabled = canSave, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.action_save))
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showDelete && existing != null) {
        ConfirmDialog(
            title = stringResource(R.string.automation_delete_title),
            text = stringResource(R.string.automation_delete_text, existing.name),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = { onDelete(existing.id) },
            onDismiss = { showDelete = false },
        )
    }
}

private fun triggerIsComplete(trigger: Trigger): Boolean = when (trigger) {
    Trigger.OnConnect -> true
    is Trigger.WifiNetwork -> trigger.ssid.isNotBlank()
    is Trigger.ForegroundApp -> trigger.packageName.isNotBlank()
    is Trigger.Schedule -> trigger.days.isNotEmpty()
}

@Composable
private fun TriggerEditor(
    trigger: Trigger,
    installedApps: List<Pair<String, String>>,
    hasUsageAccess: () -> Boolean,
    onOpenUsageAccess: () -> Unit,
    onChange: (Trigger) -> Unit,
) {
    val kind = when (trigger) {
        Trigger.OnConnect -> TriggerKind.CONNECT
        is Trigger.WifiNetwork -> TriggerKind.WIFI
        is Trigger.ForegroundApp -> TriggerKind.APP
        is Trigger.Schedule -> TriggerKind.SCHEDULE
    }
    SegmentedChoice(
        options = TriggerKind.entries,
        selected = kind,
        onSelect = { k ->
            onChange(
                when (k) {
                    TriggerKind.CONNECT -> Trigger.OnConnect
                    TriggerKind.WIFI -> Trigger.WifiNetwork("")
                    TriggerKind.APP -> Trigger.ForegroundApp("", "")
                    TriggerKind.SCHEDULE -> Trigger.Schedule(8, 0, setOf(1, 2, 3, 4, 5))
                },
            )
        },
        label = {
            stringResource(
                when (it) {
                    TriggerKind.CONNECT -> R.string.trigger_connect
                    TriggerKind.WIFI -> R.string.trigger_wifi
                    TriggerKind.APP -> R.string.trigger_app
                    TriggerKind.SCHEDULE -> R.string.trigger_schedule
                },
            )
        },
    )
    Spacer(Modifier.height(12.dp))
    when (trigger) {
        Trigger.OnConnect -> Text(stringResource(R.string.trigger_connect_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        is Trigger.WifiNetwork -> {
            // Android only reveals the SSID with fine location granted *and* location services on.
            val context = LocalContext.current
            var hasLocation by remember { mutableStateOf(Permissions.has(context, Manifest.permission.ACCESS_FINE_LOCATION)) }
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasLocation = it }
            LaunchedEffect(Unit) { if (!hasLocation) launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
            OutlinedTextField(value = trigger.ssid, onValueChange = { onChange(trigger.copy(ssid = it)) }, label = { Text(stringResource(R.string.trigger_wifi_ssid)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.trigger_wifi_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            if (!hasLocation) {
                Spacer(Modifier.height(8.dp))
                InfoBanner(
                    stringResource(R.string.trigger_wifi_location_needed),
                    tone = BannerTone.WARNING,
                    action = { TextButton(onClick = { launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) { Text(stringResource(R.string.action_allow_location)) } },
                )
            }
        }
        is Trigger.ForegroundApp -> {
            var showPicker by rememberSaveable { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(trigger.appLabel.ifBlank { stringResource(R.string.trigger_app_none) }, style = MaterialTheme.typography.bodyLarge)
                    if (trigger.packageName.isNotBlank()) Text(trigger.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { showPicker = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.trigger_app_pick)) }
            }
            if (!hasUsageAccess()) {
                Spacer(Modifier.height(8.dp))
                InfoBanner(
                    stringResource(R.string.trigger_app_usage_access),
                    tone = BannerTone.WARNING,
                    action = { TextButton(onClick = onOpenUsageAccess) { Text(stringResource(R.string.trigger_app_usage_access_action)) } },
                )
            }
            if (showPicker) {
                AppPickerDialog(apps = installedApps, onPick = { label, pkg -> showPicker = false; onChange(Trigger.ForegroundApp(pkg, label)) }, onDismiss = { showPicker = false })
            }
        }
        is Trigger.Schedule -> ScheduleEditor(trigger, onChange)
    }
}

@Composable
private fun ScheduleEditor(trigger: Trigger.Schedule, onChange: (Trigger) -> Unit) {
    var showTime by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.trigger_schedule_time), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        OutlinedButton(shape = MaterialTheme.shapes.medium, onClick = { showTime = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(String.format(Locale.getDefault(), "%02d:%02d", trigger.hour, trigger.minute), style = MaterialTheme.typography.titleMedium)
        }
    }
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (1..7).forEach { day ->
            FilterChip(
                selected = day in trigger.days,
                onClick = { onChange(trigger.copy(days = if (day in trigger.days) trigger.days - day else trigger.days + day)) },
                label = { Text(dayShortName(day)) },
            )
        }
    }
    if (showTime) {
        val state = rememberTimePickerState(initialHour = trigger.hour, initialMinute = trigger.minute)
        AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text(stringResource(R.string.trigger_schedule_time)) },
            text = { TimePicker(state = state) },
            confirmButton = { TextButton(onClick = { showTime = false; onChange(trigger.copy(hour = state.hour, minute = state.minute)) }) { Text(stringResource(R.string.action_ok)) } },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ActionEditor(
    action: AutomationAction,
    modes: List<ListeningMode>,
    presets: List<EqPreset>,
    onChange: (AutomationAction) -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    when (action) {
                        is AutomationAction.SetMode -> R.string.action_set_mode
                        is AutomationAction.SetEqPreset -> R.string.action_set_eq
                        is AutomationAction.SetNoise -> R.string.action_set_noise
                    },
                ),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_remove)) }
        }
        when (action) {
            is AutomationAction.SetMode -> {
                val options = modes.filter { it.isPreset || it.isConfigured }
                DropdownField(
                    label = stringResource(R.string.automation_mode),
                    options = options,
                    selected = options.firstOrNull { it.index == action.modeIndex },
                    onSelect = { onChange(AutomationAction.SetMode(it.index)) },
                    optionLabel = { it.name },
                )
            }
            is AutomationAction.SetEqPreset -> {
                val flat = stringResource(R.string.action_eq_flat)
                val options: List<EqPreset?> = listOf<EqPreset?>(null) + presets
                DropdownField(
                    label = stringResource(R.string.sound_preset),
                    options = options,
                    selected = presets.firstOrNull { it.id == action.presetId },
                    onSelect = { onChange(AutomationAction.SetEqPreset(it?.id)) },
                    optionLabel = { it?.name ?: flat },
                )
            }
            is AutomationAction.SetNoise -> {
                NoiseControls(
                    settings = NoiseSettings(action.cancellingLevel, action.ancEnabled, action.windBlock, SpatialAudio.fromWire(action.spatialAudioWire)),
                    enabled = true,
                    onChange = { onChange(AutomationAction.SetNoise(it.cancellingLevel, it.ancEnabled, it.windBlock, it.spatialAudio.wire)) },
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun AddActionButtons(existing: List<AutomationAction>, onAdd: (AutomationAction) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionKind.entries.forEach { kind ->
            val present = existing.any {
                when (kind) {
                    ActionKind.MODE -> it is AutomationAction.SetMode
                    ActionKind.EQ -> it is AutomationAction.SetEqPreset
                    ActionKind.NOISE -> it is AutomationAction.SetNoise
                }
            }
            if (!present) {
                TextButton(
                    onClick = {
                        onAdd(
                            when (kind) {
                                ActionKind.MODE -> AutomationAction.SetMode(ListeningMode.QUIET)
                                ActionKind.EQ -> AutomationAction.SetEqPreset(null)
                                ActionKind.NOISE -> AutomationAction.SetNoise(10, true, false, 0)
                            },
                        )
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(
                            when (kind) {
                                ActionKind.MODE -> R.string.action_set_mode
                                ActionKind.EQ -> R.string.action_set_eq
                                ActionKind.NOISE -> R.string.action_set_noise
                            },
                        ),
                    )
                }
            }
        }
    }
}

@LightDarkPreview
@Composable
private fun AutomationEditorPreview() {
    EarshotPreview {
        AutomationEditorContent(
            existing = AutomationRule("1", "Commute", true, Trigger.Schedule(8, 15, setOf(1, 2, 3, 4, 5)), listOf(AutomationAction.SetMode(0), AutomationAction.SetNoise(6, true, false, 0))),
            modes = dev.earshot.core.fake.FakeHeadphoneRepository.connectedState().modes,
            presets = emptyList(),
            installedApps = emptyList(),
            hasUsageAccess = { false }, onOpenUsageAccess = {}, onBack = {}, onSave = {}, onDelete = {},
        )
    }
}
