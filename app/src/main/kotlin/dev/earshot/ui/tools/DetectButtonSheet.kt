package dev.earshot.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.shortcut.ControlKind
import dev.earshot.shortcut.DetectHint
import dev.earshot.shortcut.DetectedControl
import dev.earshot.shortcut.DetectedShortcut
import dev.earshot.shortcut.DetectionSession
import dev.earshot.shortcut.PhysicalControl
import dev.earshot.shortcut.PressStyle
import dev.earshot.shortcut.SignalOrigin
import dev.earshot.ui.common.BannerTone
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.InfoBanner
import dev.earshot.ui.common.LightDarkPreview
import kotlinx.coroutines.delay

/** What the sheet can ask for. */
data class DetectCallbacks(
    val onStop: () -> Unit = {},
    val onRestart: () -> Unit = {},
    val onClose: () -> Unit = {},
    /** Close the sheet and highlight the Shortcut Button 1 card. */
    val onUseForShortcut: () -> Unit = {},
    val onTurnTouchOn: () -> Unit = {},
    /** Close the sheet, scroll to the Touch controls switch and highlight it. */
    val onShowTouch: () -> Unit = {},
    val onName: (PhysicalControl?, PressStyle?, String?) -> Unit = { _, _, _ -> },
    val onDemo: (DemoPress) -> Unit = {},
)

/** What the sheet shows beside the session itself. */
data class DetectSheetInfo(
    val earbuds: Boolean = false,
    /** The headset has no Bose connection (generic wording). */
    val anyHeadset: Boolean = false,
    /** Bose headphones with a `[1.9]` Shortcut: the tips mention the volume strip. */
    val hasStrip: Boolean = false,
    val hints: List<DetectHint> = emptyList(),
    /** Bose's name for the Shortcut's press ("Long press"), for [DetectHint.BOSE_NO_SHORTCUT]. */
    val bosePressName: String? = null,
    /** The app that answers the gesture today, for [DetectHint.OTHER_HANDLER]. */
    val otherHandlerLabel: String? = null,
    val detected: DetectedShortcut? = null,
    val demo: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectButtonSheet(session: DetectionSession, info: DetectSheetInfo, callbacks: DetectCallbacks) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = callbacks.onClose, sheetState = sheetState) {
        DetectSheetContent(session, info, callbacks)
    }
}

@Composable
fun DetectSheetContent(session: DetectionSession, info: DetectSheetInfo, callbacks: DetectCallbacks) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(
                when {
                    info.anyHeadset -> R.string.detect_title_any
                    info.earbuds -> R.string.detect_title_buds
                    else -> R.string.detect_title
                },
            ),
            style = MaterialTheme.typography.headlineSmall,
        )
        Countdown(session)
        info.hints.forEach { HintBanner(it, info, callbacks) }
        if (info.demo) DemoButtons(callbacks.onDemo)

        if (session.entries.isEmpty()) {
            Text(stringResource(R.string.detect_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                session.entries.forEachIndexed { i, entry ->
                    if (i > 0) HorizontalDivider()
                    DetectedRow(entry, callbacks.onUseForShortcut)
                }
            }
        }
        if (session.sawAssistantGesture) NameTheControl(info.detected, callbacks.onName)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (session.listening) {
                OutlinedButton(onClick = callbacks.onStop) { Text(stringResource(R.string.detect_stop)) }
            } else {
                OutlinedButton(onClick = callbacks.onRestart) { Text(stringResource(R.string.detect_again)) }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = callbacks.onClose) { Text(stringResource(R.string.detect_done)) }
        }
    }
}

/** "Listening · 38 s left" with a draining bar; ticks once a second. */
@Composable
private fun Countdown(session: DetectionSession) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session.listening, session.generation) {
        while (session.listening) {
            now = System.currentTimeMillis()
            delay(TICK_MS)
        }
    }
    val start = session.startedAt
    val end = session.endsAt
    if (session.listening && start != null && end != null) {
        val leftMs = (end - now).coerceAtLeast(0)
        Text(stringResource(R.string.detect_listening, ((leftMs + 999) / 1000).toInt()), style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(progress = { leftMs.toFloat() / (end - start).coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
    } else {
        Text(stringResource(R.string.detect_stopped), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HintBanner(hint: DetectHint, info: DetectSheetInfo, callbacks: DetectCallbacks) {
    when (hint) {
        DetectHint.TOUCH_CONTROLS_OFF -> InfoBanner(
            stringResource(R.string.detect_hint_touch_off), tone = BannerTone.WARNING, icon = Icons.Default.TouchApp,
            action = { TextButton(onClick = callbacks.onTurnTouchOn) { Text(stringResource(R.string.detect_turn_touch_on)) } },
        )
        DetectHint.NO_BUTTON_ON_ASSISTANT -> InfoBanner(stringResource(R.string.detect_hint_no_assistant_button), tone = BannerTone.WARNING)
        DetectHint.TRY_EACH_CONTROL -> InfoBanner(
            stringResource(if (info.hasStrip) R.string.detect_hint_try_each else R.string.detect_hint_try_each_other),
        )
        DetectHint.BOSE_NO_SHORTCUT -> InfoBanner(
            info.bosePressName?.let { stringResource(R.string.detect_hint_bose_none, it) } ?: stringResource(R.string.detect_hint_bose_none_unnamed),
            tone = BannerTone.WARNING,
            action = { TextButton(onClick = callbacks.onShowTouch) { Text(stringResource(R.string.detect_show_touch)) } },
        )
        DetectHint.OTHER_NO_SHORTCUT -> InfoBanner(stringResource(R.string.detect_hint_other_none))
        DetectHint.OTHER_HANDLER -> info.otherHandlerLabel?.let { InfoBanner(stringResource(R.string.detect_hint_other_handler, it)) }
    }
}

@Composable
private fun DemoButtons(onDemo: (DemoPress) -> Unit) {
    Column {
        Text(stringResource(R.string.detect_demo_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DemoPress.entries.forEach { press ->
                AssistChip(
                    onClick = { onDemo(press) },
                    label = {
                        Text(
                            stringResource(
                                when (press) {
                                    DemoPress.PLAY_PAUSE -> R.string.detect_demo_play
                                    DemoPress.VOLUME -> R.string.detect_demo_volume
                                    DemoPress.BUTTON -> R.string.detect_demo_button
                                    DemoPress.ASSISTANT -> R.string.detect_demo_assistant
                                },
                            ),
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Science, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
    }
}

/** Icon, plain label, detail and the verdict; the assistant gesture in green with its action. */
@Composable
private fun DetectedRow(entry: DetectedControl, onUseForShortcut: () -> Unit) {
    val good = successColor()
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(
            kindIcon(entry.kind), contentDescription = null,
            tint = if (entry.remappable) good else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp).size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(kindLabel(entry.kind), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                if (entry.count > 1) {
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.detect_times, entry.count), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            detailText(entry)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                verdictText(entry),
                style = MaterialTheme.typography.bodyMedium,
                color = if (entry.remappable) good else MaterialTheme.colorScheme.onSurface,
            )
            if (entry.isSmallVolumeNudge) {
                Text(stringResource(R.string.detect_volume_nudge), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (entry.kind == ControlKind.ASSISTANT_GESTURE) {
                TextButton(onClick = onUseForShortcut, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp)) {
                    Text(stringResource(R.string.detect_use_for))
                }
            }
        }
    }
}

@Composable
private fun detailText(entry: DetectedControl): String? {
    val parts = buildList {
        if (entry.origin == SignalOrigin.DEMO) add(stringResource(R.string.detect_demo_label))
        when (entry.kind) {
            ControlKind.PLAY_PAUSE -> entry.playing?.let { add(stringResource(if (it) R.string.detect_playing else R.string.detect_paused)) }
            ControlKind.TRACK_CHANGE -> if (entry.detail != null) add(stringResource(R.string.detect_seeking))
            ControlKind.VOLUME -> if (entry.volumeFrom != null && entry.volumeTo != null) {
                add(stringResource(R.string.detect_volume_steps, entry.volumeFrom, entry.volumeTo))
            }
            ControlKind.ASSISTANT_GESTURE -> if (entry.detail == "Wired headset") add(stringResource(R.string.detect_wired))
            ControlKind.HEADPHONE_BUTTON_EVENT -> entry.detail?.let { add(it) }
            ControlKind.CALL_CONTROLS -> Unit
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun verdictText(entry: DetectedControl): String = when (entry.kind) {
    ControlKind.ASSISTANT_GESTURE -> stringResource(R.string.detect_verdict_assistant)
    ControlKind.PLAY_PAUSE, ControlKind.TRACK_CHANGE, ControlKind.VOLUME -> stringResource(R.string.detect_verdict_music)
    ControlKind.CALL_CONTROLS -> stringResource(R.string.detect_verdict_call)
    ControlKind.HEADPHONE_BUTTON_EVENT -> stringResource(R.string.detect_verdict_button, entry.label)
}

@Composable
private fun kindLabel(kind: ControlKind): String = stringResource(
    when (kind) {
        ControlKind.ASSISTANT_GESTURE -> R.string.detect_kind_assistant
        ControlKind.PLAY_PAUSE -> R.string.detect_kind_play_pause
        ControlKind.TRACK_CHANGE -> R.string.detect_kind_track
        ControlKind.VOLUME -> R.string.detect_kind_volume
        ControlKind.HEADPHONE_BUTTON_EVENT -> R.string.detect_kind_button
        ControlKind.CALL_CONTROLS -> R.string.detect_kind_call
    },
)

private fun kindIcon(kind: ControlKind): ImageVector = when (kind) {
    ControlKind.ASSISTANT_GESTURE -> Icons.Default.RecordVoiceOver
    ControlKind.PLAY_PAUSE -> Icons.Default.PlayArrow
    ControlKind.TRACK_CHANGE -> Icons.Default.SkipNext
    ControlKind.VOLUME -> Icons.AutoMirrored.Filled.VolumeUp
    ControlKind.HEADPHONE_BUTTON_EVENT -> Icons.Default.TouchApp
    ControlKind.CALL_CONTROLS -> Icons.Default.Call
}

/** "Which control was it?" + "How did you press it?", saved per headset on every tap. */
@Composable
private fun NameTheControl(detected: DetectedShortcut?, onName: (PhysicalControl?, PressStyle?, String?) -> Unit) {
    var control by remember(detected?.address) { mutableStateOf(detected?.control) }
    var press by remember(detected?.address) { mutableStateOf(detected?.press) }
    var custom by remember(detected?.address) { mutableStateOf(detected?.customName.orEmpty()) }
    fun save() = onName(control, press, custom)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = successColor(), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.detect_name_title), style = MaterialTheme.typography.titleSmall)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhysicalControl.entries.forEach { c ->
                FilterChip(selected = control == c, onClick = { control = c; save() }, label = { Text(controlName(c)) })
            }
        }
        if (control == PhysicalControl.OTHER) {
            OutlinedTextField(
                value = custom,
                onValueChange = { custom = it.take(MAX_NAME); save() },
                label = { Text(stringResource(R.string.detect_name_other)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(stringResource(R.string.detect_name_how), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PressStyle.entries.forEach { p ->
                FilterChip(selected = press == p, onClick = { press = p; save() }, label = { Text(pressName(p)) })
            }
        }
    }
}

@Composable
internal fun controlName(c: PhysicalControl): String = stringResource(
    when (c) {
        PhysicalControl.POWER_BUTTON -> R.string.control_power_button
        PhysicalControl.PLAY_PAUSE_BUTTON -> R.string.control_play_pause_button
        PhysicalControl.VOLUME_STRIP -> R.string.control_volume_strip
        PhysicalControl.OTHER -> R.string.control_other
    },
)

@Composable
private fun pressName(p: PressStyle): String = stringResource(
    when (p) {
        PressStyle.SINGLE_PRESS -> R.string.press_single
        PressStyle.DOUBLE_PRESS -> R.string.press_double
        PressStyle.LONG_PRESS -> R.string.press_long
        PressStyle.TOUCH_AND_HOLD -> R.string.press_touch_hold
    },
)

@Composable
private fun pressNameLower(p: PressStyle): String = stringResource(
    when (p) {
        PressStyle.SINGLE_PRESS -> R.string.press_single_lower
        PressStyle.DOUBLE_PRESS -> R.string.press_double_lower
        PressStyle.LONG_PRESS -> R.string.press_long_lower
        PressStyle.TOUCH_AND_HOLD -> R.string.press_touch_hold_lower
    },
)

/** "Power button, single press ✓", "Shortcut detected ✓", or null when never detected. */
@Composable
internal fun detectedSummary(detected: DetectedShortcut?): String? {
    detected ?: return null
    val control = when (detected.control) {
        null -> null
        PhysicalControl.OTHER -> detected.customName ?: controlName(PhysicalControl.OTHER)
        else -> controlName(detected.control)
    }
    val press = detected.press?.let { pressNameLower(it) }
    val name = when {
        control != null && press != null -> stringResource(R.string.shortcut_detected_control_press, control, press)
        control != null -> control
        else -> null
    }
    return name?.let { stringResource(R.string.shortcut_detected_named, it) } ?: stringResource(R.string.shortcut_detected)
}

/** A green readable on the current surface (the theme has no success colour of its own). */
@Composable
internal fun successColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF7DD99A) else Color(0xFF1B7F3B)

private const val TICK_MS = 1_000L
private const val MAX_NAME = 40

private val previewSession = DetectionSession(
    listening = true, generation = 1, startedAt = 0L, endsAt = 45_000L,
    log = dev.earshot.shortcut.DetectionLog()
        .add(dev.earshot.shortcut.ControlSignal.Volume(14, 16, SignalOrigin.HEADPHONES, 1_000))
        .add(dev.earshot.shortcut.ControlSignal.PlayState(false, SignalOrigin.HEADPHONES, 4_000))
        .add(dev.earshot.shortcut.ControlSignal.AssistantGesture(null, wired = false, at = 9_000)),
)

/** A detection with the gesture found, the naming chips and the touch-controls hint. */
@LightDarkPreview
@Composable
private fun DetectSheetPreview() {
    EarshotPreview {
        DetectSheetContent(
            previewSession,
            DetectSheetInfo(hints = listOf(DetectHint.TOUCH_CONTROLS_OFF), detected = DetectedShortcut("68:F2:1F:12:34:56", 9_000, PhysicalControl.POWER_BUTTON, PressStyle.SINGLE_PRESS), demo = true),
            DetectCallbacks(),
        )
    }
}

/** Ended without the gesture on Bose headphones: Bose's name for the press and the Touch controls link. */
@LightDarkPreview
@Composable
private fun DetectSheetNothingPreview() {
    EarshotPreview {
        DetectSheetContent(
            DetectionSession(generation = 1, startedAt = 0L, endsAt = 45_000L, endedAt = 45_000L),
            DetectSheetInfo(hints = listOf(DetectHint.BOSE_NO_SHORTCUT), bosePressName = "Long press"),
            DetectCallbacks(),
        )
    }
}
