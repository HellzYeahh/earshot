package dev.earshot.ui.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.shortcut.AssistantCandidate
import dev.earshot.shortcut.ShortcutAction
import dev.earshot.ui.common.AppPickerDialog
import dev.earshot.ui.common.rememberInstalledApps

/** The action kinds offered in the picker, in display order. Adding an action = one more entry. */
internal enum class ActionKind { CYCLE_EQ, VOICE_MEMO, LAUNCH_APP, OPEN_ASSISTANT }

internal fun ShortcutAction.kind(): ActionKind = when (this) {
    ShortcutAction.CycleEq -> ActionKind.CYCLE_EQ
    ShortcutAction.VoiceMemo -> ActionKind.VOICE_MEMO
    is ShortcutAction.LaunchApp -> ActionKind.LAUNCH_APP
    is ShortcutAction.OpenAssistant -> ActionKind.OPEN_ASSISTANT
}

/** Where an edit flow starts: picking the kind, or straight at the app / assistant of the current action. */
internal enum class EditStep { KIND, APP, ASSISTANT }

/** Short label for an action, e.g. "Open Spotify" or "My assistant (Google)". */
@Composable
internal fun actionLabel(action: ShortcutAction, autoAssistant: AssistantCandidate?): String = when (action) {
    ShortcutAction.CycleEq -> stringResource(R.string.shortcut_action_cycle_eq)
    ShortcutAction.VoiceMemo -> stringResource(R.string.shortcut_action_voice_memo)
    is ShortcutAction.LaunchApp -> action.label?.let { stringResource(R.string.shortcut_action_launch_named, it) }
        ?: stringResource(R.string.shortcut_action_launch_none)
    is ShortcutAction.OpenAssistant -> stringResource(
        R.string.shortcut_action_assistant_named,
        action.label ?: autoAssistant?.label ?: stringResource(R.string.shortcut_assistant_auto_short),
    )
}

@Composable
private fun kindTitle(kind: ActionKind): String = stringResource(
    when (kind) {
        ActionKind.CYCLE_EQ -> R.string.shortcut_action_cycle_eq
        ActionKind.VOICE_MEMO -> R.string.shortcut_action_voice_memo
        ActionKind.LAUNCH_APP -> R.string.shortcut_kind_launch
        ActionKind.OPEN_ASSISTANT -> R.string.shortcut_kind_assistant
    },
)

@Composable
private fun kindSubtitle(kind: ActionKind): String = stringResource(
    when (kind) {
        ActionKind.CYCLE_EQ -> R.string.shortcut_kind_cycle_eq_sub
        ActionKind.VOICE_MEMO -> R.string.shortcut_kind_voice_memo_sub
        ActionKind.LAUNCH_APP -> R.string.shortcut_kind_launch_sub
        ActionKind.OPEN_ASSISTANT -> R.string.shortcut_kind_assistant_sub
    },
)

/**
 * The whole "choose an action" flow: kind first, then the app or assistant when the kind needs
 * one. [allowDefault] adds "Use default" (per-headset rows); choosing it saves null.
 */
@Composable
internal fun ActionEditFlow(
    current: ShortcutAction?,
    allowDefault: Boolean,
    startAt: EditStep,
    assistants: List<AssistantCandidate>,
    autoAssistant: AssistantCandidate?,
    onSave: (ShortcutAction?) -> Unit,
    onClose: () -> Unit,
) {
    var step by remember { mutableStateOf(startAt) }
    when (step) {
        EditStep.KIND -> KindDialog(
            current = current?.kind(),
            allowDefault = allowDefault,
            onPick = { kind ->
                when (kind) {
                    null -> { onSave(null); onClose() }
                    ActionKind.CYCLE_EQ -> { onSave(ShortcutAction.CycleEq); onClose() }
                    ActionKind.VOICE_MEMO -> { onSave(ShortcutAction.VoiceMemo); onClose() }
                    ActionKind.LAUNCH_APP -> step = EditStep.APP
                    ActionKind.OPEN_ASSISTANT -> step = EditStep.ASSISTANT
                }
            },
            onDismiss = onClose,
        )
        EditStep.APP -> {
            val apps by rememberInstalledApps()
            AppPickerDialog(
                apps = apps,
                onPick = { label, pkg -> onSave(ShortcutAction.LaunchApp(pkg, label)); onClose() },
                onDismiss = onClose,
            )
        }
        EditStep.ASSISTANT -> AssistantDialog(
            current = (current as? ShortcutAction.OpenAssistant)?.component,
            assistants = assistants,
            autoAssistant = autoAssistant,
            onPick = { candidate -> onSave(ShortcutAction.OpenAssistant(candidate?.component, candidate?.label)); onClose() },
            onDismiss = onClose,
        )
    }
}

@Composable
private fun KindDialog(current: ActionKind?, allowDefault: Boolean, onPick: (ActionKind?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shortcut_pick_action)) },
        text = {
            Column {
                if (allowDefault) {
                    ChoiceRow(stringResource(R.string.shortcut_use_default), null, selected = current == null) { onPick(null) }
                }
                ActionKind.entries.forEach { kind ->
                    ChoiceRow(kindTitle(kind), kindSubtitle(kind), selected = current == kind) { onPick(kind) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun AssistantDialog(
    current: String?,
    assistants: List<AssistantCandidate>,
    autoAssistant: AssistantCandidate?,
    onPick: (AssistantCandidate?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shortcut_pick_assistant)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    ChoiceRow(
                        stringResource(R.string.shortcut_assistant_auto),
                        autoAssistant?.let { stringResource(R.string.shortcut_assistant_auto_now, it.label) }
                            ?: stringResource(R.string.shortcut_assistant_none_found),
                        selected = current == null,
                    ) { onPick(null) }
                }
                items(assistants, key = { it.component }) { candidate ->
                    ChoiceRow(
                        candidate.label,
                        stringResource(
                            if (candidate.kind == AssistantCandidate.Kind.VOICE_COMMAND) R.string.shortcut_assistant_kind_command
                            else R.string.shortcut_assistant_kind_assist,
                        ),
                        selected = current == candidate.component,
                    ) { onPick(candidate) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ChoiceRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
