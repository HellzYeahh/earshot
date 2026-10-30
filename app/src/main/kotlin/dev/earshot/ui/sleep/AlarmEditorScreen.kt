@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.earshot.ui.sleep

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.earshot.R
import dev.earshot.core.AlarmOutput
import dev.earshot.core.SleepAlarm
import dev.earshot.core.fake.FakeSleepRepository
import dev.earshot.ui.common.ConfirmDialog
import dev.earshot.ui.common.DropdownField
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.common.SegmentedChoice
import dev.earshot.ui.common.SubScreenScaffold
import dev.earshot.ui.common.SwitchRow
import dev.earshot.ui.common.earshotViewModel
import dev.earshot.ui.theme.EarshotTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.UUID

/** Sleep → an alarm: new when [alarmId] is null. */
@Composable
fun AlarmEditorScreen(alarmId: String?, onBack: () -> Unit) {
    val vm = earshotViewModel { SleepViewModel(it.sleep, it.messenger) }
    val alarms by vm.alarms.collectAsStateWithLifecycle()
    val existing = remember(alarmId, alarms) { alarms.firstOrNull { it.id == alarmId } }
    // One save or delete at a time, and one pop for it: a double tap ran both commands and each
    // popped, which left Sleep for the screen under it. As in AutomationEditorScreen: cleared again
    // only when the command failed (its Snackbar said why), so Save can be retried; after a success
    // the editor is on its way out. Plain remember on purpose: after a rotation mid-save the new
    // screen must not stay locked.
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
            // mid-save must not pop again, or Sleep goes with it. After a rotation the new editor
            // stays; its Save replaces the same alarm (the id is saved with the draft).
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) onBack()
        }.invokeOnCompletion { if (!done) busy = false }
    }
    AlarmEditorContent(
        alarmId = alarmId,
        existing = existing,
        onBack = onBack,
        onSave = { alarm, confirmation -> leaveAfter { onDone -> vm.saveAlarm(alarm, confirmation, onDone) } },
        onDelete = { id -> leaveAfter { onDone -> vm.deleteAlarm(id, onDone) } },
        onPreviewTone = vm::previewTone,
        busy = busy,
    )
}

/**
 * The alarm, top to bottom as people set one: time and days, where it rings, how it sounds and
 * wakes, snooze. Edits stay in a draft until Save; saving switches the alarm on.
 */
@Composable
fun AlarmEditorContent(
    alarmId: String?,
    existing: SleepAlarm?,
    onBack: () -> Unit,
    onSave: (SleepAlarm, String) -> Unit,
    onDelete: (String) -> Unit,
    onPreviewTone: (String?, AlarmOutput) -> Unit,
    /** A save or delete is under way: Save and Delete stay off so a second tap can't run it again. */
    busy: Boolean = false,
) {
    val newId = rememberSaveable { UUID.randomUUID().toString() }
    // Survives rotation. Seeded once from the stored alarm, which may load after the screen
    // opens; later emissions of the list never overwrite what the user is editing.
    var draft by rememberSaveable(alarmId, stateSaver = AlarmDraftSaver) {
        mutableStateOf(existing ?: SleepLogic.newAlarm(alarmId ?: newId))
    }
    var seeded by rememberSaveable(alarmId) { mutableStateOf(existing != null || alarmId == null) }
    LaunchedEffect(existing) {
        if (!seeded && existing != null) {
            draft = existing
            seeded = true
        }
    }
    var showTime by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    val is24 = rememberIs24Hour()
    val now = rememberNow(30_000)
    val minutesToRing = SleepLogic.minutesUntilAlarm(
        draft.hour,
        draft.minute,
        draft.days,
        Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()),
    )
    val (time, amPm) = SleepLogic.clockParts(draft.hour, draft.minute, is24, currentLocale())
    val confirmation = stringResource(R.string.sleep_alarm_saved, SleepLogic.clockText(draft.hour, draft.minute, is24, currentLocale()), durationText(minutesToRing))

    // The contract has no "tone playing" state. One effect owns the preview: it plays the chosen
    // tone on the chosen output, restarts (and re-times) when either changes, and falls back to
    // Play after a while because a preview is brief.
    val preview by rememberUpdatedState(onPreviewTone)
    LaunchedEffect(playing, draft.toneId, draft.output) {
        if (!playing) return@LaunchedEffect
        preview(draft.toneId, draft.output)
        delay(TONE_PREVIEW_MILLIS)
        preview(null, draft.output)
        playing = false
    }
    // Leaving the editor or the app stops the tone: nothing would be left on screen to stop it.
    LifecycleResumeEffect(Unit) {
        onPauseOrDispose {
            preview(null, draft.output)
            playing = false
        }
    }

    SubScreenScaffold(
        title = stringResource(if (alarmId == null) R.string.sleep_alarm_new else R.string.sleep_alarm_edit),
        onBack = onBack,
        actions = {
            if (existing != null) {
                IconButton(onClick = { showDelete = true }, enabled = !busy) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete)) }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp, vertical = 8.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClickLabel = stringResource(R.string.sleep_alarm_change_time)) { showTime = true }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(time, style = MaterialTheme.typography.displayLarge)
                        if (amPm != null) Text(amPm, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 6.dp, bottom = 10.dp))
                    }
                    Text(ringsInText(minutesToRing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(12.dp))
                DayToggles(draft.days) { draft = draft.copy(days = it) }
                Text(
                    if (draft.days.isEmpty()) stringResource(R.string.sleep_repeat_once_hint, time + (amPm?.let { " $it" } ?: ""))
                    else repeatText(SleepLogic.repeatKind(draft.days), SleepLogic.weekOrder(firstDayOfWeek()).filter { it in draft.days }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            SectionCard(title = stringResource(R.string.sleep_alarm_rings_in)) {
                SegmentedChoice(
                    options = AlarmOutput.entries,
                    selected = draft.output,
                    onSelect = { draft = draft.copy(output = it) },
                    label = { alarmOutputLabel(it) },
                )
                Text(
                    when (draft.output) {
                        AlarmOutput.EARBUDS -> stringResource(R.string.sleep_alarm_earbuds_hint, SleepAlarm.PHONE_FALLBACK_SECONDS)
                        AlarmOutput.PHONE -> stringResource(R.string.sleep_alarm_phone_hint)
                        AlarmOutput.BOTH -> stringResource(R.string.sleep_alarm_both_hint)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            SectionCard(title = stringResource(R.string.sleep_alarm_sound)) {
                val choices = SleepLogic.alarmToneChoices().let { if (draft.toneId in it) it else it + draft.toneId }
                val names = choices.associateWith { alarmToneName(it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DropdownField(
                        label = stringResource(R.string.sleep_alarm_sound),
                        options = choices,
                        selected = draft.toneId,
                        onSelect = { draft = draft.copy(toneId = it) },
                        optionLabel = { names[it] ?: it },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            // Starting is the effect's job; stopping is immediate.
                            if (playing) onPreviewTone(null, draft.output)
                            playing = !playing
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            if (playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = stringResource(if (playing) R.string.sleep_alarm_preview_stop else R.string.sleep_alarm_preview_play),
                        )
                    }
                }

                ChoiceHeading(stringResource(R.string.sleep_alarm_wake_window))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SleepAlarm.WAKE_WINDOW_CHOICES.forEach { minutes ->
                        FilterChip(
                            selected = draft.wakeWindowMinutes == minutes,
                            onClick = { draft = draft.copy(wakeWindowMinutes = minutes) },
                            label = { Text(if (minutes == 0) stringResource(R.string.sleep_off) else pluralStringResource(R.plurals.sleep_minutes_short, minutes, minutes)) },
                        )
                    }
                }
                Hint(stringResource(R.string.sleep_alarm_wake_window_hint))

                ChoiceHeading(stringResource(R.string.sleep_alarm_fade_in))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SleepAlarm.FADE_IN_CHOICES.forEach { seconds ->
                        FilterChip(
                            selected = draft.fadeInSeconds == seconds,
                            onClick = { draft = draft.copy(fadeInSeconds = seconds) },
                            label = { Text(secondsText(seconds)) },
                        )
                    }
                }
                Hint(stringResource(R.string.sleep_alarm_fade_in_hint))
            }

            SectionCard(title = stringResource(R.string.sleep_alarm_snooze)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SleepAlarm.SNOOZE_CHOICES.forEach { minutes ->
                        FilterChip(
                            selected = draft.snoozeMinutes == minutes,
                            onClick = { draft = draft.copy(snoozeMinutes = minutes) },
                            label = { Text(if (minutes == null) stringResource(R.string.sleep_off) else pluralStringResource(R.plurals.sleep_minutes_short, minutes, minutes)) },
                        )
                    }
                }
                SwitchRow(
                    title = stringResource(R.string.sleep_alarm_vibrate),
                    subtitle = stringResource(R.string.sleep_alarm_vibrate_hint),
                    checked = draft.vibrate,
                    onCheckedChange = { draft = draft.copy(vibrate = it) },
                )
            }

            OutlinedTextField(
                value = draft.label.orEmpty(),
                onValueChange = { draft = draft.copy(label = it.take(MAX_LABEL)) },
                label = { Text(stringResource(R.string.sleep_alarm_label)) },
                placeholder = { Text(stringResource(R.string.sleep_ringing_title)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    if (playing) onPreviewTone(null, draft.output)
                    onSave(draft.copy(enabled = true, label = draft.label?.trim()?.ifBlank { null }), confirmation)
                },
                enabled = !busy,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(stringResource(R.string.sleep_alarm_save))
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showTime) {
        val state = rememberTimePickerState(initialHour = draft.hour, initialMinute = draft.minute, is24Hour = is24)
        AlertDialog(
            onDismissRequest = { showTime = false },
            // The platform dialog width leaves ~264 dp on a 360 dp phone; the 12-hour picker needs 280.
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.padding(horizontal = 12.dp),
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = { showTime = false; draft = draft.copy(hour = state.hour, minute = state.minute) }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (showDelete && existing != null) {
        ConfirmDialog(
            title = stringResource(R.string.sleep_alarm_delete_title),
            text = stringResource(R.string.sleep_alarm_delete_text, SleepLogic.clockText(existing.hour, existing.minute, is24, currentLocale())),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = { onDelete(existing.id) },
            onDismiss = { showDelete = false },
        )
    }
}

/** Seven round day toggles in the locale's week order, initials inside, full names for TalkBack. */
@Composable
private fun DayToggles(days: Set<Int>, onChange: (Set<Int>) -> Unit) {
    val colors = EarshotTheme.colors
    val locale = currentLocale()
    // Seven 48 dp targets don't fit a 360 dp card; Compose still widens each touch area to 48 dp
    // where its neighbours leave room.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SleepLogic.weekOrder(firstDayOfWeek()).forEach { day ->
            val selected = day in days
            val dow = DayOfWeek.of(day)
            val name = dow.getDisplayName(TextStyle.FULL, locale)
            Box(
                Modifier
                    .weight(1f)
                    .widthIn(max = 48.dp)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(if (selected) colors.control else Color.Transparent)
                    .border(1.dp, if (selected) colors.control else colors.hairline, CircleShape)
                    .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onChange(SleepLogic.toggleDay(days, day)) })
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    dow.getDisplayName(TextStyle.NARROW, locale),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) colors.onControl else MaterialTheme.colorScheme.onSurface,
                    // TalkBack reads the full day name from the toggle, not "Monday, M".
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
}

/** "15 s", "1 min", "3 min". */
@Composable
private fun secondsText(seconds: Int): String =
    if (seconds < 60) pluralStringResource(R.plurals.sleep_seconds_short, seconds, seconds)
    else pluralStringResource(R.plurals.sleep_minutes_short, seconds / 60, seconds / 60)

private const val TONE_PREVIEW_MILLIS = 10_000L
private const val MAX_LABEL = 40

/** The draft as JSON across rotation and process death (SleepAlarm is already @Serializable for the store). */
private val AlarmDraftSaver = Saver<SleepAlarm, String>(
    save = { Json.encodeToString(SleepAlarm.serializer(), it) },
    restore = { runCatching { Json.decodeFromString(SleepAlarm.serializer(), it) }.getOrNull() },
)

// ---- Previews ---------------------------------------------------------------------------------

@LightDarkPreview
@Composable
private fun AlarmEditorPreview() = EarshotPreview {
    val alarm = remember { FakeSleepRepository().alarms.value.first() }
    AlarmEditorContent(alarmId = alarm.id, existing = alarm, onBack = {}, onSave = { _, _ -> }, onDelete = {}, onPreviewTone = { _, _ -> })
}

@LightDarkPreview
@Composable
private fun AlarmEditorNewPreview() = EarshotPreview {
    AlarmEditorContent(alarmId = null, existing = null, onBack = {}, onSave = { _, _ -> }, onDelete = {}, onPreviewTone = { _, _ -> })
}
