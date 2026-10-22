package dev.earshot.ui.tools

import dev.earshot.ui.theme.EarshotTheme
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Earbuds
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.earshot.R
import dev.earshot.appContainer
import dev.earshot.core.ButtonAction
import dev.earshot.core.ButtonAssignment
import dev.earshot.core.HeadphoneState
import dev.earshot.core.fake.FakeHeadphoneRepository
import dev.earshot.shortcut.AssistantCandidate
import dev.earshot.shortcut.DetectHintInput
import dev.earshot.shortcut.DetectHints
import dev.earshot.shortcut.DetectionSession
import dev.earshot.shortcut.HandlerStatus
import dev.earshot.shortcut.PhysicalControl
import dev.earshot.shortcut.PressStyle
import dev.earshot.shortcut.SecondaryButtonPolicy
import dev.earshot.shortcut.ShortcutAction
import dev.earshot.shortcut.ShortcutSettings
import dev.earshot.shortcut.isEarshotAction
import dev.earshot.ui.common.AppPickerDialog
import dev.earshot.ui.common.BannerTone
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.InfoBanner
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.common.SubScreenScaffold
import dev.earshot.ui.common.SwitchRow
import dev.earshot.ui.common.earshotViewModel
import dev.earshot.ui.common.rememberInstalledApps
import dev.earshot.ui.more.SettingsViewModel
import dev.earshot.ui.onboarding.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What the screen can ask for; one object keeps the content composable's signature short. */
data class ShortcutsCallbacks(
    val onBack: () -> Unit = {},
    val onChoose: (ShortcutAction) -> Unit = {},
    val onChooseSecondary: (buttonId: Int, mode: ButtonAction) -> Unit = { _, _ -> },
    val onSpokenFeedback: (Boolean) -> Unit = {},
    val onGrantMic: () -> Unit = {},
    val onOpenDevices: () -> Unit = {},
    /** Opens the "Detect a button" sheet (starts listening). */
    val onStartDetect: () -> Unit = {},
)

/** Which part of the screen to scroll to and pulse for a moment. */
enum class ControlsHighlight { SHORTCUT, TOUCH_CONTROLS }

@Composable
fun ShortcutsRemapperScreen(onBack: () -> Unit, onOpenDevices: () -> Unit = {}) {
    val context = LocalContext.current
    val vm = earshotViewModel { deps -> ShortcutsRemapperViewModel(context.appContainer.shortcuts, deps.headphones, deps.messenger) }
    // The headphone commands that used to sit on the Tools tab ("Headphone controls").
    val settingsVm = earshotViewModel { SettingsViewModel(it.headphones, it.messenger) }
    val state by vm.state.collectAsStateWithLifecycle()
    val headphones by settingsVm.state.collectAsStateWithLifecycle()
    val detection by vm.detection.collectAsStateWithLifecycle()
    val demo by remember { context.appContainer.demoMode.isEnabled }.collectAsStateWithLifecycle(false)
    var micGranted by remember { mutableStateOf(Permissions.has(context, Permissions.RECORD_AUDIO)) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (!granted && Permissions.isPermanentlyDenied(context, Permissions.RECORD_AUDIO)) Permissions.openAppSettings(context)
    }
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        micGranted = Permissions.has(context, Permissions.RECORD_AUDIO)
        onPauseOrDispose { }
    }

    ShortcutsRemapperContent(
        state = state,
        micGranted = micGranted,
        headphones = headphones,
        detection = detection,
        demo = demo,
        callbacks = ShortcutsCallbacks(
            onBack = onBack,
            onChoose = { action ->
                vm.choose(action)
                // Asked right when it's picked: the press itself can't show a permission prompt.
                if (action == ShortcutAction.VoiceMemo && !micGranted) micLauncher.launch(Permissions.RECORD_AUDIO)
            },
            onChooseSecondary = vm::chooseSecondary,
            onSpokenFeedback = vm::setSpokenFeedback,
            onGrantMic = { micLauncher.launch(Permissions.RECORD_AUDIO) },
            onOpenDevices = onOpenDevices,
            onStartDetect = vm::startDetect,
        ),
        controls = HeadphoneControlsCallbacks(
            onTouchControls = settingsVm::setTouchControls,
            onVoicePrompts = settingsVm::setVoicePrompts,
            onRename = settingsVm::rename,
            onPowerOff = settingsVm::powerOff,
        ),
        detect = DetectCallbacks(
            onStop = vm::stopDetect,
            onRestart = vm::startDetect,
            onClose = vm::closeDetect,
            onTurnTouchOn = { settingsVm.setTouchControls(true) },
            onName = vm::nameDetected,
            onDemo = vm::simulate,
        ),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShortcutsRemapperContent(
    state: ShortcutsUiState,
    micGranted: Boolean,
    callbacks: ShortcutsCallbacks,
    headphones: HeadphoneState = HeadphoneState(),
    controls: HeadphoneControlsCallbacks = HeadphoneControlsCallbacks(),
    detection: DetectionSession = DetectionSession(),
    detect: DetectCallbacks = DetectCallbacks(),
    demo: Boolean = false,
) {
    var pickingApp by remember { mutableStateOf(false) }
    var pickingAssistant by remember { mutableStateOf(false) }
    var detecting by rememberSaveable { mutableStateOf(false) }
    var highlight by remember { mutableStateOf<ControlsHighlight?>(null) }
    val shortcutRequester = remember { BringIntoViewRequester() }
    val touchRequester = remember { BringIntoViewRequester() }
    val choice = state.choice
    val earbuds = state.headset?.earbuds == true

    LaunchedEffect(highlight) {
        when (highlight) {
            ControlsHighlight.SHORTCUT -> shortcutRequester.bringIntoView()
            ControlsHighlight.TOUCH_CONTROLS -> touchRequester.bringIntoView()
            null -> return@LaunchedEffect
        }
        delay(HIGHLIGHT_MS)
        highlight = null
    }
    // Orange in both modes: an ink outline next to the olive hairline would not read as "look here".
    val highlightColor = EarshotTheme.colors.accent
    val highlightShape = MaterialTheme.shapes.medium

    SubScreenScaffold(title = stringResource(R.string.tools_shortcuts), onBack = callbacks.onBack) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeadsetHeader(state.headset)

            SectionCard(
                modifier = Modifier
                    .bringIntoViewRequester(shortcutRequester)
                    .highlighted(highlight == ControlsHighlight.SHORTCUT, highlightColor, highlightShape),
            ) {
                ButtonTitle(state.buttons.firstOrNull(), index = 1, detected = detectedSummary(state.detected))
                ChoiceDropdown(
                    selected = choice.choice(),
                    options = ShortcutChoice.options(state.nativeModes, choice.choice()),
                    earbuds = earbuds,
                    onSelect = { picked ->
                        when (picked) {
                            ShortcutChoice.PHONE_ASSISTANT -> callbacks.onChoose(choice as? ShortcutAction.OpenAssistant ?: ShortcutAction.OpenAssistant())
                            ShortcutChoice.CYCLE_EQ -> callbacks.onChoose(ShortcutAction.CycleEq)
                            ShortcutChoice.VOICE_MEMO -> callbacks.onChoose(ShortcutAction.VoiceMemo)
                            // Nothing is saved until an app is picked: cancelling keeps the old choice.
                            ShortcutChoice.LAUNCH_APP -> if ((choice as? ShortcutAction.LaunchApp)?.packageName == null) pickingApp = true
                            ShortcutChoice.IMMERSIVE_AUDIO -> callbacks.onChoose(ShortcutAction.HeadphoneButton(ButtonAction.SPATIAL_AUDIO.wire))
                            ShortcutChoice.OFF -> callbacks.onChoose(ShortcutAction.HeadphoneButton(ButtonAction.DISABLED.wire))
                        }
                    },
                )
                if (state.showFirstPressHint) {
                    Text(
                        stringResource(R.string.shortcut_first_press_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
                    )
                }

                when (choice) {
                    is ShortcutAction.LaunchApp -> AppRow(choice, onChange = { pickingApp = true })
                    is ShortcutAction.OpenAssistant -> AssistantRow(assistantLabel(choice, state.autoAssistant), onClick = { pickingAssistant = true })
                    ShortcutAction.VoiceMemo -> if (!micGranted) {
                        InfoBanner(
                            stringResource(R.string.shortcut_mic_needed),
                            tone = BannerTone.WARNING,
                            icon = Icons.Default.Mic,
                            action = { TextButton(onClick = callbacks.onGrantMic) { Text(stringResource(R.string.shortcut_allow_mic)) } },
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    else -> Unit
                }

                if (choice == ShortcutAction.CycleEq || choice == ShortcutAction.VoiceMemo) {
                    HorizontalDivider(Modifier.padding(top = 12.dp))
                    SwitchRow(
                        title = stringResource(if (earbuds) R.string.shortcut_announce_buds else R.string.shortcut_announce),
                        checked = state.settings.spokenFeedback,
                        onCheckedChange = callbacks.onSpokenFeedback,
                    )
                }

                OutlinedButton(
                    shape = MaterialTheme.shapes.medium,
                    onClick = { detecting = true; callbacks.onStartDetect() },
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.detect_button))
                }
            }

            state.secondaryButtons.forEachIndexed { i, button ->
                SecondaryButtonCard(
                    button = button,
                    index = i + 2,
                    selected = SecondaryButtonPolicy.displayed(state.settings.buttonChoiceFor(state.headset?.address, button.buttonId), button),
                    sharedLabel = sharedActionLabel(choice, earbuds),
                    earbuds = earbuds,
                    onSelect = { mode -> callbacks.onChooseSecondary(button.buttonId, mode) },
                )
            }
            if (state.sharesAssistant) {
                InfoBanner(stringResource(R.string.shortcut_shared_assistant, sharedActionLabel(choice, earbuds)), tone = BannerTone.INFO)
            }

            HeadphoneControlsSection(
                headphones, controls, callbacks.onOpenDevices,
                touchModifier = Modifier
                    .bringIntoViewRequester(touchRequester)
                    .highlighted(highlight == ControlsHighlight.TOUCH_CONTROLS, highlightColor, highlightShape),
            )

            // Any headset, driver or not: connect, disconnect, rename and forget through a CDM link.
            if (!LocalInspectionMode.current) {
                UniversalRemoteSection(state.headset?.let { RemoteTarget(it.address, it.name) })
            }
        }
    }

    if (detecting) {
        val bose = state.headset?.viaEarshot == true
        val hints = DetectHints.hints(
            DetectHintInput(
                listening = detection.listening,
                finished = detection.finished,
                sawAssistantGesture = detection.sawAssistantGesture,
                bose = bose,
                buttons = state.buttons,
                touchControlsEnabled = headphones.touchControlsEnabled.takeIf { bose },
                handler = state.handler,
            ),
        )
        DetectButtonSheet(
            session = detection,
            info = DetectSheetInfo(
                earbuds = earbuds,
                anyHeadset = !bose,
                hasStrip = bose && !earbuds && state.buttons.isNotEmpty(),
                hints = hints,
                bosePressName = DetectHints.bosePressName(state.buttons),
                otherHandlerLabel = (state.handler as? HandlerStatus.OtherApp)?.label,
                detected = state.detected,
                demo = demo,
            ),
            callbacks = detect.copy(
                onClose = { detecting = false; detect.onClose() },
                onUseForShortcut = { detecting = false; detect.onClose(); highlight = ControlsHighlight.SHORTCUT },
                onShowTouch = { detecting = false; detect.onClose(); highlight = ControlsHighlight.TOUCH_CONTROLS },
            ),
        )
    }
    if (pickingApp) {
        val apps by rememberInstalledApps()
        AppPickerDialog(
            apps = apps,
            onPick = { label, pkg -> pickingApp = false; callbacks.onChoose(ShortcutAction.LaunchApp(pkg, label)) },
            onDismiss = { pickingApp = false },
        )
    }
    if (pickingAssistant) {
        AssistantDialog(
            current = (choice as? ShortcutAction.OpenAssistant)?.component,
            assistants = state.assistants,
            autoAssistant = state.autoAssistant,
            onPick = { c -> pickingAssistant = false; callbacks.onChoose(ShortcutAction.OpenAssistant(c?.component, c?.label)) },
            onDismiss = { pickingAssistant = false },
        )
    }
}

/** An accent outline while [on]: where "Use for Shortcut Button 1" / "Show Touch controls" lead. [shape] is the card's. */
private fun Modifier.highlighted(on: Boolean, color: androidx.compose.ui.graphics.Color, shape: androidx.compose.ui.graphics.Shape): Modifier =
    if (on) border(2.dp, color, shape) else this

/** "Shortcut Button 1:" (or "… (right earbud):") with the detected control on the right. */
@Composable
private fun ButtonTitle(button: ButtonAssignment?, index: Int, detected: String?) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(buttonTitle(button, index), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (detected != null) {
            Text(detected, style = MaterialTheme.typography.labelLarge, color = successColor(), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
internal fun buttonTitle(button: ButtonAssignment?, index: Int): String = when (button?.let { SecondaryButtonPolicy.side(it.buttonId) }) {
    SecondaryButtonPolicy.Side.RIGHT -> stringResource(R.string.shortcut_button_n_right, index)
    SecondaryButtonPolicy.Side.LEFT -> stringResource(R.string.shortcut_button_n_left, index)
    null -> if (index == 1) stringResource(R.string.shortcut_button_1) else stringResource(R.string.shortcut_button_n, index)
}

/** What a second assistant button runs: Shortcut Button 1's Earshot action, else the phone's assistant. */
@Composable
private fun sharedActionLabel(choice: ShortcutAction, earbuds: Boolean): String =
    if (choice.isEarshotAction) shortcutSummary(choice, earbuds) else choiceLabel(ShortcutChoice.PHONE_ASSISTANT, earbuds)

private const val HIGHLIGHT_MS = 2_500L

/** The connected headset's name with a headphones / earbuds icon; "Any headset" with none. */
@Composable
private fun HeadsetHeader(headset: RemapperHeadset?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (headset?.earbuds == true) Icons.Default.Earbuds else Icons.Default.Headphones,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                headset?.let { it.name ?: it.address } ?: stringResource(R.string.shortcut_any_headset),
                style = MaterialTheme.typography.titleLarge,
            )
            if (headset == null) {
                Text(
                    stringResource(R.string.shortcut_any_headset_sub),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceDropdown(selected: ShortcutChoice, options: List<ShortcutChoice>, earbuds: Boolean, onSelect: (ShortcutChoice) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = choiceLabel(selected, earbuds),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { i, option ->
                // The headphones' own modes after Earshot's actions, set apart by a line.
                if (option.isNative && (i == 0 || !options[i - 1].isNative)) HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(choiceLabel(option, earbuds)) },
                    onClick = { expanded = false; onSelect(option) },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
private fun AppRow(action: ShortcutAction.LaunchApp, onChange: () -> Unit) {
    val icon = rememberAppIcon(action.packageName)
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.size(32.dp))
        } else {
            Icon(Icons.Default.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            action.label ?: action.packageName ?: stringResource(R.string.shortcut_no_app_chosen),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onChange) { Text(stringResource(R.string.shortcut_change)) }
    }
}

/** "Assistant: Automatic (Google) ▾": optional, the default works without touching it. */
@Composable
private fun AssistantRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).defaultMinSize(minHeight = 48.dp).clickable(onClick = onClick).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.shortcut_assistant_row, label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The launcher icon of [packageName], loaded off the main thread; null while loading or when missing. */
@Composable
private fun rememberAppIcon(packageName: String?): ImageBitmap? {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = packageName?.let { pkg ->
            withContext(Dispatchers.IO) {
                runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
            }
        }
    }
    return icon
}

private val previewBose = RemapperHeadset("68:F2:1F:12:34:56", "Fargo", earbuds = false, viaEarshot = true)

private val previewButton = ButtonAssignment(
    buttonId = 0x80, eventType = 9, action = ButtonAction.VOICE_ASSISTANT,
    supportedActions = listOf(ButtonAction.VOICE_ASSISTANT, ButtonAction.DISABLED, ButtonAction.SPOTIFY_TAP, ButtonAction.SPATIAL_AUDIO),
)

private val previewState = ShortcutsUiState(
    handler = HandlerStatus.Chooser,
    settings = ShortcutSettings().withChoice(previewBose.address, previewBose.name, ShortcutAction.CycleEq)
        .withDetected(previewBose.address, 0L)
        .withDetectedName(previewBose.address, PhysicalControl.POWER_BUTTON, PressStyle.SINGLE_PRESS, null),
    headset = previewBose,
    button = previewButton,
    buttons = listOf(previewButton),
    autoAssistant = AssistantCandidate("com.google.android.googlequicksearchbox", "HandsFreeActivity", "Google", AssistantCandidate.Kind.VOICE_COMMAND),
)

/** Bose connected, Cycle EQ, the detected power button, the hint line, Detect a button and the headphone controls. */
@LightDarkPreview
@Composable
private fun ShortcutsRemapperPreview() {
    EarshotPreview {
        ShortcutsRemapperContent(previewState, micGranted = true, callbacks = ShortcutsCallbacks(), headphones = FakeHeadphoneRepository.connectedState())
    }
}

/** Nothing connected: the default, Phone assistant with its optional assistant row; the controls disabled. */
@LightDarkPreview
@Composable
private fun ShortcutsRemapperDefaultPreview() {
    EarshotPreview {
        ShortcutsRemapperContent(
            previewState.copy(handler = HandlerStatus.Earshot, settings = ShortcutSettings(), headset = null, button = null, buttons = emptyList()),
            micGranted = true,
            callbacks = ShortcutsCallbacks(),
        )
    }
}

/** Other earbuds over HFP only, launching an app. */
@LightDarkPreview
@Composable
private fun ShortcutsRemapperAppPreview() {
    val buds = RemapperHeadset("AA:BB:CC:DD:EE:01", "Pixel Buds Pro", earbuds = true, viaEarshot = false)
    EarshotPreview {
        ShortcutsRemapperContent(
            previewState.copy(
                handler = HandlerStatus.Earshot,
                settings = ShortcutSettings().withChoice(buds.address, buds.name, ShortcutAction.LaunchApp("com.spotify.music", "Spotify")),
                headset = buds,
                button = null,
                buttons = emptyList(),
            ),
            micGranted = true,
            callbacks = ShortcutsCallbacks(),
        )
    }
}

/** Bose earbuds with one record per bud: "Shortcut Button 1 (right earbud)" and "Shortcut Button 2 (left earbud)". */
@LightDarkPreview
@Composable
private fun ShortcutsRemapperBudsPreview() {
    val buds = RemapperHeadset("68:F2:1F:12:34:57", "Bose QC Ultra 2 Earbuds", earbuds = true, viaEarshot = true)
    val right = previewButton.copy(buttonId = 0x03)
    val left = previewButton.copy(buttonId = 0x04, action = ButtonAction.SPATIAL_AUDIO)
    EarshotPreview {
        ShortcutsRemapperContent(
            previewState.copy(handler = HandlerStatus.Earshot, settings = ShortcutSettings(), headset = buds, button = right, buttons = listOf(right, left)),
            micGranted = true,
            callbacks = ShortcutsCallbacks(),
            headphones = FakeHeadphoneRepository.connectedEarbudsState(),
        )
    }
}
