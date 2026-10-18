package dev.earshot.ui.tools

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.earshot.R
import dev.earshot.appContainer
import dev.earshot.bt.DeviceCandidate
import dev.earshot.shortcut.AssistantCandidate
import dev.earshot.shortcut.HandlerStatus
import dev.earshot.shortcut.HeadsetOverride
import dev.earshot.shortcut.ShortcutAction
import dev.earshot.shortcut.ShortcutSettings
import dev.earshot.shortcut.VoiceMemoService
import dev.earshot.ui.common.BannerTone
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.EmptyHint
import dev.earshot.ui.common.InfoBanner
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.NavRow
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.common.SubScreenScaffold
import dev.earshot.ui.common.SwitchRow
import dev.earshot.ui.common.earshotViewModel
import dev.earshot.ui.onboarding.Permissions

/** What the screen can ask for; one object keeps the content composable's signature short. */
data class ShortcutsCallbacks(
    val onBack: () -> Unit = {},
    val onSetUp: (packageName: String?) -> Unit = {},
    val onTestChooser: () -> Unit = {},
    val onSetDefault: (ShortcutAction) -> Unit = {},
    val onSetOverride: (address: String, name: String?, action: ShortcutAction?) -> Unit = { _, _, _ -> },
    val onSpokenFeedback: (Boolean) -> Unit = {},
    val onTryNow: () -> Unit = {},
    val onGrantMic: () -> Unit = {},
    val onWired: (Boolean) -> Unit = {},
)

@Composable
fun ShortcutsRemapperScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm = earshotViewModel { deps ->
        val c = context.appContainer
        ShortcutsRemapperViewModel(c.shortcuts, c.deviceFinder, deps.messenger)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    var micGranted by remember { mutableStateOf(Permissions.has(context, Permissions.RECORD_AUDIO)) }
    var runAfterGrant by remember { mutableStateOf(false) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (!granted && Permissions.isPermanentlyDenied(context, Permissions.RECORD_AUDIO)) Permissions.openAppSettings(context)
        if (granted && runAfterGrant) context.findActivity()?.let(vm::tryNow)
        runAfterGrant = false
    }
    LifecycleResumeEffect(Unit) {
        micGranted = Permissions.has(context, Permissions.RECORD_AUDIO)
        onPauseOrDispose { }
    }

    ShortcutsRemapperContent(
        state = state,
        micGranted = micGranted,
        callbacks = ShortcutsCallbacks(
            onBack = onBack,
            onSetUp = { pkg -> openSetUp(context, pkg) },
            onTestChooser = { testChooser(context) },
            onSetDefault = { action ->
                vm.setDefaultAction(action)
                if (action == ShortcutAction.VoiceMemo && !micGranted) micLauncher.launch(Permissions.RECORD_AUDIO)
            },
            onSetOverride = { address, name, action ->
                vm.setOverride(address, name, action)
                if (action == ShortcutAction.VoiceMemo && !micGranted) micLauncher.launch(Permissions.RECORD_AUDIO)
            },
            onSpokenFeedback = vm::setSpokenFeedback,
            onTryNow = {
                val needsMic = state.settings.defaultAction == ShortcutAction.VoiceMemo && state.memoState == VoiceMemoService.MemoState.IDLE
                if (needsMic && !Permissions.has(context, Permissions.RECORD_AUDIO)) {
                    runAfterGrant = true
                    micLauncher.launch(Permissions.RECORD_AUDIO)
                } else {
                    context.findActivity()?.let(vm::tryNow)
                }
            },
            onGrantMic = { micLauncher.launch(Permissions.RECORD_AUDIO) },
            onWired = vm::setWiredEnabled,
        ),
    )
}

/** What the Edit dialogs are editing: the default (null address) or one headset. */
private data class EditTarget(val address: String?, val name: String?, val step: EditStep)

@Composable
fun ShortcutsRemapperContent(state: ShortcutsUiState, micGranted: Boolean, callbacks: ShortcutsCallbacks) {
    var editing by remember { mutableStateOf<EditTarget?>(null) }

    SubScreenScaffold(title = stringResource(R.string.tools_shortcuts), onBack = callbacks.onBack) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.shortcut_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            SetupCard(state.handler, callbacks)

            if (state.usesVoiceMemo && !micGranted) {
                InfoBanner(
                    stringResource(R.string.shortcut_mic_needed),
                    tone = BannerTone.WARNING,
                    icon = Icons.Default.Mic,
                    action = { TextButton(onClick = callbacks.onGrantMic) { Text(stringResource(R.string.shortcut_allow_mic)) } },
                )
            }

            DefaultActionCard(state, callbacks, onEdit = { step -> editing = EditTarget(null, null, step) })

            HeadsetsCard(state.headsets, state.settings, state.autoAssistant, onEdit = { c -> editing = EditTarget(c.address, c.name, EditStep.KIND) })

            SectionCard {
                SwitchRow(
                    title = stringResource(R.string.shortcut_wired),
                    subtitle = stringResource(R.string.shortcut_wired_sub),
                    checked = state.wiredEnabled,
                    onCheckedChange = callbacks.onWired,
                )
            }

            SectionCard(title = stringResource(R.string.shortcut_good_to_know)) {
                listOf(R.string.shortcut_note_which, R.string.shortcut_note_bose, R.string.shortcut_note_buttons, R.string.shortcut_note_assistant)
                    .forEach { id ->
                        Text(
                            stringResource(id),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
            }
        }
    }

    editing?.let { target ->
        val current: ShortcutAction? =
            if (target.address == null) state.settings.defaultAction else state.settings.overrideFor(target.address)?.action
        ActionEditFlow(
            current = current,
            allowDefault = target.address != null,
            startAt = target.step,
            assistants = state.assistants,
            autoAssistant = state.autoAssistant,
            onSave = { action ->
                if (target.address == null) action?.let(callbacks.onSetDefault)
                else callbacks.onSetOverride(target.address, target.name, action)
            },
            onClose = { editing = null },
        )
    }
}

@Composable
private fun SetupCard(handler: HandlerStatus, callbacks: ShortcutsCallbacks) {
    SectionCard(title = stringResource(R.string.shortcut_setup_title)) {
        Text(
            stringResource(R.string.shortcut_setup_headset_app),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        when (handler) {
            HandlerStatus.Earshot -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.shortcut_setup_done), style = MaterialTheme.typography.bodyLarge)
            }
            is HandlerStatus.OtherApp -> {
                Text(stringResource(R.string.shortcut_setup_other, handler.label), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.padding(top = 6.dp))
                Text(
                    stringResource(R.string.shortcut_setup_other_steps, handler.label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HandlerStatus.Chooser, HandlerStatus.Unknown -> {
                Text(stringResource(R.string.shortcut_setup_chooser), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.padding(top = 6.dp))
                Text(
                    stringResource(R.string.shortcut_setup_chooser_steps),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (handler is HandlerStatus.OtherApp) {
                Button(onClick = { callbacks.onSetUp(handler.packageName) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.shortcut_set_up))
                }
            }
            OutlinedButton(onClick = callbacks.onTestChooser, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.shortcut_test_chooser))
            }
        }
    }
}

@Composable
private fun DefaultActionCard(state: ShortcutsUiState, callbacks: ShortcutsCallbacks, onEdit: (EditStep) -> Unit) {
    val action = state.settings.defaultAction
    SectionCard(title = stringResource(R.string.shortcut_default_title)) {
        NavRow(
            title = stringResource(R.string.shortcut_default_action),
            subtitle = actionLabel(action, state.autoAssistant),
            icon = Icons.Default.TouchApp,
            onClick = { onEdit(EditStep.KIND) },
        )
        when (action) {
            is ShortcutAction.LaunchApp -> NavRow(
                title = stringResource(R.string.shortcut_app),
                value = action.label ?: stringResource(R.string.shortcut_choose),
                onClick = { onEdit(EditStep.APP) },
            )
            is ShortcutAction.OpenAssistant -> NavRow(
                title = stringResource(R.string.shortcut_assistant),
                subtitle = action.label ?: state.autoAssistant?.let { stringResource(R.string.shortcut_assistant_auto_now, it.label) }
                    ?: stringResource(R.string.shortcut_assistant_none_found),
                onClick = { onEdit(EditStep.ASSISTANT) },
            )
            else -> Unit
        }
        SwitchRow(
            title = stringResource(R.string.shortcut_spoken),
            subtitle = stringResource(R.string.shortcut_spoken_sub),
            checked = state.settings.spokenFeedback,
            onCheckedChange = callbacks.onSpokenFeedback,
        )
        val recording = state.memoState != VoiceMemoService.MemoState.IDLE
        Button(onClick = callbacks.onTryNow, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (recording && action == ShortcutAction.VoiceMemo) R.string.shortcut_try_stop else R.string.shortcut_try))
        }
    }
}

@Composable
private fun HeadsetsCard(headsets: List<DeviceCandidate>, settings: ShortcutSettings, autoAssistant: AssistantCandidate?, onEdit: (DeviceCandidate) -> Unit) {
    SectionCard(title = stringResource(R.string.shortcut_per_headset)) {
        if (headsets.isEmpty()) {
            EmptyHint(stringResource(R.string.shortcut_no_headsets))
        } else {
            headsets.forEach { candidate ->
                val override = settings.overrideFor(candidate.address)
                NavRow(
                    title = candidate.name ?: candidate.address,
                    subtitle = override?.let { actionLabel(it.action, autoAssistant) } ?: stringResource(R.string.shortcut_uses_default),
                    icon = Icons.Default.Headset,
                    onClick = { onEdit(candidate) },
                )
            }
        }
    }
}

/** The other app's settings page, where "Open by default" / "Set as default" → Clear defaults lives. */
private fun openSetUp(context: Context, packageName: String?) {
    val pkg = packageName ?: return
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** Sends the same implicit VOICE_COMMAND the Bluetooth stack does, so Android shows its chooser (or the default). */
private fun testChooser(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Can't happen while Earshot declares the filter.
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private val previewState = ShortcutsUiState(
    handler = HandlerStatus.OtherApp("com.samsung.android.bixby.agent", "Bixby Voice"),
    settings = ShortcutSettings(
        defaultAction = ShortcutAction.CycleEq,
        overrides = listOf(HeadsetOverride("AA:BB:CC:DD:EE:01", "Pixel Buds Pro", ShortcutAction.VoiceMemo)),
    ),
    headsets = listOf(
        DeviceCandidate("AA:BB:CC:DD:EE:00", "Bose QC Ultra Headphones", looksLikeBose = true, isAudioDevice = true, isSaved = true),
        DeviceCandidate("AA:BB:CC:DD:EE:01", "Pixel Buds Pro", looksLikeBose = false, isAudioDevice = true, isSaved = false),
    ),
    autoAssistant = AssistantCandidate("com.google.android.googlequicksearchbox", "HandsFreeActivity", "Google", AssistantCandidate.Kind.VOICE_COMMAND),
)

@LightDarkPreview
@Composable
private fun ShortcutsRemapperPreview() {
    EarshotPreview { ShortcutsRemapperContent(previewState, micGranted = false, callbacks = ShortcutsCallbacks()) }
}

@LightDarkPreview
@Composable
private fun ShortcutsRemapperReadyPreview() {
    EarshotPreview {
        ShortcutsRemapperContent(
            previewState.copy(handler = HandlerStatus.Earshot, settings = ShortcutSettings()),
            micGranted = true,
            callbacks = ShortcutsCallbacks(),
        )
    }
}
