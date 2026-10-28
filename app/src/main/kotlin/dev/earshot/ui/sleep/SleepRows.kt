package dev.earshot.ui.sleep

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.core.SleepAbility
import dev.earshot.ui.common.FieldSwitch
import dev.earshot.ui.theme.EarshotTheme

/*
 * Rows for night settings the headset may not support. "Tools never hide settings" (user
 * decision): such a row stays visible and tappable, marked "Not on this headset" with the
 * driver's note saying why and what would make it work. The session then logs the step as
 * skipped instead of failing the night.
 */

/** "NOT ON THIS HEADSET" plus the driver's reason; nothing when the step is available. */
@Composable
fun UnavailableNote(ability: SleepAbility, modifier: Modifier = Modifier) {
    if (ability.available) return
    Row(modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(top = 1.dp).size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                stringResource(R.string.sleep_not_on_this_headset).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            if (ability.note != null) {
                Text(ability.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * "What happens when" above a choice: a headline that names the time ("Your earbuds disconnect
 * at 3:57 AM") and a quieter line of detail. It changes as the choices below it change.
 */
@Composable
fun WhenLine(icon: ImageVector, headline: String, detail: String?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = EarshotTheme.colors.accent, modifier = Modifier.padding(top = 2.dp).size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(headline, style = MaterialTheme.typography.titleMedium)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A small legend above a group of choices inside a card. */
@Composable
fun ChoiceHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

/** One radio choice with an optional explanation and the headset's ability. */
@Composable
fun SleepChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    ability: SleepAbility = SleepAbility(true),
) {
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            UnavailableNote(ability)
        }
    }
}

/** [dev.earshot.ui.common.SwitchRow] plus the headset's ability and an optional extra under the text. */
@Composable
fun SleepSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    ability: SleepAbility = SleepAbility(true),
) {
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            UnavailableNote(ability)
        }
        Spacer(Modifier.width(12.dp))
        FieldSwitch(checked = checked, onCheckedChange = null)
    }
}
