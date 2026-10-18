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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.core.ButtonAction
import dev.earshot.shortcut.AssistantCandidate
import dev.earshot.shortcut.ShortcutAction

/** The entries of the remapper's "Shortcut Button 1:" dropdown. */
internal enum class ShortcutChoice(val isNative: Boolean = false) {
    PHONE_ASSISTANT, CYCLE_EQ, VOICE_MEMO, LAUNCH_APP,
    /** `[1.9]` mode 19 on the headphones themselves. */
    IMMERSIVE_AUDIO(isNative = true),
    /** `[1.9]` mode 14 (Disabled). */
    OFF(isNative = true),
    ;

    companion object {
        /** Earshot's own actions, always offered, in display order. */
        val EARSHOT = listOf(PHONE_ASSISTANT, CYCLE_EQ, VOICE_MEMO, LAUNCH_APP)

        fun forNative(mode: ButtonAction): ShortcutChoice? = when (mode) {
            ButtonAction.SPATIAL_AUDIO -> IMMERSIVE_AUDIO
            ButtonAction.DISABLED -> OFF
            else -> null
        }

        /**
         * The dropdown's options: Earshot's actions, then the native modes the headphones report
         * ([nativeModes], already filtered by `ShortcutButtonPolicy.nativeOptions`). The current
         * choice is always listed, so a saved native choice shows even while that headset isn't
         * connected through Earshot.
         */
        fun options(nativeModes: List<ButtonAction>, current: ShortcutChoice): List<ShortcutChoice> {
            val list = EARSHOT + nativeModes.mapNotNull(::forNative)
            return if (current in list) list else list + current
        }
    }
}

internal fun ShortcutAction.choice(): ShortcutChoice = when (this) {
    is ShortcutAction.OpenAssistant -> ShortcutChoice.PHONE_ASSISTANT
    ShortcutAction.CycleEq -> ShortcutChoice.CYCLE_EQ
    ShortcutAction.VoiceMemo -> ShortcutChoice.VOICE_MEMO
    is ShortcutAction.LaunchApp -> ShortcutChoice.LAUNCH_APP
    // An unknown stored mode behaves like the assistant (see ShortcutEngine), so it reads as one.
    is ShortcutAction.HeadphoneButton -> ShortcutChoice.forNative(ButtonAction.fromWire(mode)) ?: ShortcutChoice.PHONE_ASSISTANT
}

@Composable
internal fun choiceLabel(choice: ShortcutChoice, earbuds: Boolean): String = stringResource(
    when (choice) {
        ShortcutChoice.PHONE_ASSISTANT -> R.string.shortcut_choice_assistant
        ShortcutChoice.CYCLE_EQ -> R.string.shortcut_action_cycle_eq
        ShortcutChoice.VOICE_MEMO -> R.string.shortcut_action_voice_memo
        ShortcutChoice.LAUNCH_APP -> R.string.shortcut_choice_launch
        ShortcutChoice.IMMERSIVE_AUDIO -> if (earbuds) R.string.shortcut_choice_immersive_buds else R.string.shortcut_choice_immersive
        ShortcutChoice.OFF -> R.string.shortcut_choice_off
    },
)

/** One line for the current choice, e.g. "Cycle EQ preset" or "Open Spotify" (the Tools row). */
@Composable
internal fun shortcutSummary(action: ShortcutAction, earbuds: Boolean): String =
    (action as? ShortcutAction.LaunchApp)?.label?.let { stringResource(R.string.shortcut_action_launch_named, it) }
        ?: choiceLabel(action.choice(), earbuds)

/** "Automatic (Google)" or the picked assistant's name. */
@Composable
internal fun assistantLabel(action: ShortcutAction.OpenAssistant, autoAssistant: AssistantCandidate?): String =
    action.label ?: autoAssistant?.let { stringResource(R.string.shortcut_assistant_auto_named, it.label) }
        ?: stringResource(R.string.shortcut_assistant_auto)

@Composable
internal fun AssistantDialog(
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
