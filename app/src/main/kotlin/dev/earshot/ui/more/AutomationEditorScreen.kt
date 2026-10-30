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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
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
    // One save or delete at a time, and one pop for it: a double tap used to run both and pop past
    // the Automations list to More. Cleared again only when the command failed (its Snackbar said
    // why), so Save can be retried; after a success the editor is on its way out. Plain remember on
    // purpose: after a rotation mid-save the new screen must not stay locked.
    var busy by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun leaveAfter(command: (onDone: () -> Unit) -> Job) {
        if (busy) return
        busy = true
        var done = false
        command {
            done = true
            // Only from the editor on screen. One already leaving (Back pressed during the save:
            // its entry sits at CREATED through the exit transition) or destroyed by a rotation
            // mid-save (its navController no longer drives what is shown) must not pop again, or
            // the Automations list goes with it. After a rotation the new editor stays; its Save
            // replaces the same rule.
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) onBack()
        }.invokeOnCompletion { if (!done) busy = false }
    }
    AutomationEditorContent(
        existing = existing,
        modes = headphones.modes,
        address = headphones.connection.deviceAddress,
        presets = presets,
        installedApps = installedApps,
        hasUsageAccess = vm::hasUsageAccess,
        onOpenUsageAccess = vm::openUsageAccessSettings,
        onBack = onBack,
        onSave = { rule -> leaveAfter { onDone -> vm.save(rule, onDone) } },
        onDelete = { id -> leaveAfter { onDone -> vm.delete(id, onDone) } },
        busy = busy,
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
    /** The connected headset: a mode picked from [modes] is saved as picked on it. */
    address: String? = null,
    /** A save or delete is under way: Save stays off so a second tap can't run it again. */
    busy: Boolean = false,
) {
    // The whole draft (name, trigger, actions) survives a rotation, a fold and process death: the
    // trigger and actions used to sit in plain remember, so a rotation quietly turned a schedule
    // rule back into "on connect" while the name stayed. The id is fixed once, so saving twice
    // replaces the same rule instead of storing a copy.
    val newId = rememberSaveable { UUID.randomUUID().toString() }
    var draft by rememberSaveable(saver = AutomationDraftSaver) {
        mutableStateOf(existing ?: AutomationDraft.new(newId, AutomationModeChoices.default(modes, address)))
    }
    // Seeded from the stored rule once, which may load after the screen opens; later emissions of
    // the list (an enable toggle, the mode migration writing it back) never overwrite the edits.
    var seeded by rememberSaveable { mutableStateOf(existing != null) }
    LaunchedEffect(existing) {
        if (!seeded && existing != null) {
            draft = existing
            seeded = true
        }
    }
    val name = draft.name
    val trigger = draft.trigger
    val actions = draft.actions
    var showDelete by rememberSaveable { mutableStateOf(false) }
    val canSave = AutomationDraft.canSave(draft) && !busy

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
            OutlinedTextField(value = name, onValueChange = { draft = draft.copy(name = it) }, label = { Text(stringResource(R.string.automation_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())

            SectionCard(title = stringResource(R.string.automation_when)) {
                TriggerEditor(trigger, installedApps, hasUsageAccess, onOpenUsageAccess) { draft = draft.copy(trigger = it) }
            }

            SectionCard(title = stringResource(R.string.automation_then)) {
                actions.forEachIndexed { index, action ->
                    ActionEditor(
                        action = action,
                        modes = modes,
                        address = address,
                        presets = presets,
                        onChange = { updated -> draft = draft.copy(actions = draft.actions.toMutableList().also { it[index] = updated }) },
                        onRemove = { draft = draft.copy(actions = draft.actions.toMutableList().also { it.removeAt(index) }) },
                    )
                }
                AddActionButtons(existing = actions, newMode = { AutomationModeChoices.default(modes, address) }) { draft = draft.copy(actions = draft.actions + it) }
            }

            Button(shape = MaterialTheme.shapes.medium, onClick = {
                onSave(AutomationDraft.toSave(draft, existing))
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

/**
 * The editor's draft, kept pure so it is unit-tested. It is a whole [AutomationRule] and crosses
 * a rotation or process death as the rule's own JSON (the store's serializers, so every trigger
 * and action, mode refs included, round-trips exactly).
 */
internal object AutomationDraft {
    /** The store's settings (AutomationJson): defaults written, unknown keys ignored. */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** A new rule: no name yet, on connect, one "Set mode" action. */
    fun new(id: String, defaultMode: AutomationAction.SetMode) =
        AutomationRule(id = id, name = "", enabled = true, trigger = Trigger.OnConnect, actions = listOf(defaultMode))

    fun encode(draft: AutomationRule): String = json.encodeToString(AutomationRule.serializer(), draft)

    /** Null when the saved text can't be read (the editor then starts again from the stored rule). */
    fun decode(text: String): AutomationRule? = runCatching { json.decodeFromString(AutomationRule.serializer(), text) }.getOrNull()

    fun canSave(draft: AutomationRule): Boolean =
        draft.name.isNotBlank() && draft.actions.isNotEmpty() && isComplete(draft.trigger)

    fun isComplete(trigger: Trigger): Boolean = when (trigger) {
        Trigger.OnConnect -> true
        is Trigger.WifiNetwork -> trigger.ssid.isNotBlank()
        is Trigger.ForegroundApp -> trigger.packageName.isNotBlank()
        is Trigger.Schedule -> trigger.days.isNotEmpty()
    }

    /**
     * What Save stores: the stored rule's id and on/off switch when it exists (the switch may have
     * changed in the list since the draft was taken), else the draft's fixed id, so a second Save
     * replaces the rule instead of adding a copy.
     */
    fun toSave(draft: AutomationRule, existing: AutomationRule?): AutomationRule = draft.copy(
        id = existing?.id ?: draft.id,
        name = draft.name.trim(),
        enabled = existing?.enabled ?: draft.enabled,
    )
}

/**
 * The draft's whole state, so an unreadable saved text returns null and rememberSaveable runs its
 * initializer again (a `stateSaver` would put the null into the state instead).
 */
private val AutomationDraftSaver = Saver<MutableState<AutomationRule>, String>(
    save = { AutomationDraft.encode(it.value) },
    restore = { text -> AutomationDraft.decode(text)?.let { mutableStateOf(it) } },
)

/**
 * Android reveals the Wi-Fi name only with *precise* location granted (WifiSsidWatcher checks
 * ACCESS_FINE_LOCATION) and location services on. From Android 12 (target 31+) a request for fine
 * location alone is dropped without a dialog ("must be requested with ACCESS_COARSE_LOCATION"), so
 * the Wi-Fi trigger could never be switched on: both are asked together, and the dialog offers
 * precise or approximate. Pure so the outcome is unit-tested.
 */
internal object WifiLocationAsk {
    val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    /**
     * After a request came back: true when only the app's settings page can grant precise location
     * now (Android stopped showing the dialog). An empty [result] is a request that got no answer
     * (another one was in flight, the screen went away), so asking again stays the next step.
     */
    fun settingsOnly(result: Map<String, Boolean>, precise: Boolean, permanentlyDenied: Boolean): Boolean =
        !precise && result.isNotEmpty() && permanentlyDenied

    /**
     * Approximate location granted without precise (Android 12+ offers the choice in the dialog):
     * the Wi-Fi name stays hidden, so the banner asks for precise by name instead of reading as if
     * nothing had been granted. Asking again shows Android's "change to precise" dialog.
     */
    fun approximateOnly(precise: Boolean, approximate: Boolean): Boolean = !precise && approximate
}

/**
 * The location banner of the Wi-Fi trigger. It never asks by itself: Play's prominent-disclosure
 * rule wants the explanation on screen *before* Android's dialog and an affirmative tap to start
 * it, so picking the trigger only shows this text, and the dialog comes from "Allow location"
 * alone (docs/research/play-readiness.md B4). It used to ask the moment the trigger was picked,
 * with the banner appearing only alongside the dialog; don't bring that back.
 */
@Composable
private fun WifiLocationBanner() {
    val context = LocalContext.current
    val fine = Manifest.permission.ACCESS_FINE_LOCATION
    val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
    var hasPrecise by remember { mutableStateOf(Permissions.has(context, fine)) }
    var hasApproximate by remember { mutableStateOf(Permissions.has(context, coarse)) }
    var settingsOnly by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasPrecise = Permissions.has(context, fine)
        hasApproximate = Permissions.has(context, coarse)
        settingsOnly = WifiLocationAsk.settingsOnly(result, hasPrecise, Permissions.isPermanentlyDenied(context, fine))
    }
    // Back from the app's settings page, where it may just have been granted.
    LifecycleResumeEffect(Unit) {
        hasPrecise = Permissions.has(context, fine)
        hasApproximate = Permissions.has(context, coarse)
        if (hasPrecise) settingsOnly = false
        onPauseOrDispose { }
    }
    val approximateOnly = WifiLocationAsk.approximateOnly(hasPrecise, hasApproximate)
    if (!hasPrecise) {
        Spacer(Modifier.height(8.dp))
        InfoBanner(
            stringResource(if (approximateOnly) R.string.trigger_wifi_precise_needed else R.string.trigger_wifi_location_needed),
            tone = BannerTone.WARNING,
            action = {
                TextButton(onClick = { if (settingsOnly) Permissions.openAppSettings(context) else launcher.launch(WifiLocationAsk.PERMISSIONS) }) {
                    Text(
                        stringResource(
                            when {
                                settingsOnly -> R.string.action_open_app_settings
                                approximateOnly -> R.string.action_use_precise_location
                                else -> R.string.action_allow_location
                            },
                        ),
                    )
                }
            },
        )
    }
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
            OutlinedTextField(value = trigger.ssid, onValueChange = { onChange(trigger.copy(ssid = it)) }, label = { Text(stringResource(R.string.trigger_wifi_ssid)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.trigger_wifi_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            WifiLocationBanner()
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
    address: String?,
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
                val choices = AutomationModeChoices.choices(modes, address)
                val shown = AutomationModeChoices.shown(action, choices, modes, address)
                // A saved mode this headset lacks stays visible (and selected) instead of a blank field.
                val options = if (shown in choices) choices else choices + shown
                val labels = options.associateWith { AutomationModeChoices.label(it, modes, address) ?: stringResource(R.string.mode_slot_number, it.modeIndex) }
                DropdownField(
                    label = stringResource(R.string.automation_mode),
                    options = options,
                    selected = shown,
                    // Picking writes a fresh ref for this headset (no 0.75 slot fallback any more).
                    onSelect = { onChange(it) },
                    optionLabel = { labels[it].orEmpty() },
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
                    settings = NoiseSettings(action.cancellingLevel, action.ancEnabled, action.windBlock, SpatialAudio.fromStoredId(action.spatialAudioWire)),
                    enabled = true,
                    onChange = { onChange(AutomationAction.SetNoise(it.cancellingLevel, it.ancEnabled, it.windBlock, it.spatialAudio.storedId)) },
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun AddActionButtons(existing: List<AutomationAction>, newMode: () -> AutomationAction.SetMode, onAdd: (AutomationAction) -> Unit) {
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
                                ActionKind.MODE -> newMode()
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
        val fake = dev.earshot.core.fake.FakeHeadphoneRepository.connectedState()
        AutomationEditorContent(
            existing = AutomationRule("1", "Commute", true, Trigger.Schedule(8, 15, setOf(1, 2, 3, 4, 5)), listOf(AutomationAction.SetMode(0), AutomationAction.SetNoise(6, true, false, 0))),
            modes = fake.modes,
            address = fake.connection.deviceAddress,
            presets = emptyList(),
            installedApps = emptyList(),
            hasUsageAccess = { false }, onOpenUsageAccess = {}, onBack = {}, onSave = {}, onDelete = {},
        )
    }
}
