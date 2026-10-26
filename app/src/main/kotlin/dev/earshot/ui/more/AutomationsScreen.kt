package dev.earshot.ui.more

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.earshot.R
import dev.earshot.core.AutomationAction
import dev.earshot.core.AutomationRule
import dev.earshot.core.ListeningMode
import dev.earshot.core.ModeResolver
import dev.earshot.core.Trigger
import dev.earshot.dsp.EqPreset
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.EmptyHint
import dev.earshot.ui.common.FieldSwitch
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.LocalSnackbarHostState
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.common.SubScreenScaffold
import dev.earshot.ui.common.earshotViewModel
import java.util.Locale

@Composable
fun AutomationsScreen(onBack: () -> Unit, onEdit: (String?) -> Unit) {
    val vm = earshotViewModel { AutomationsViewModel(it.automation, it.headphones, it.tuning, it.messenger) }
    val rules by vm.rules.collectAsStateWithLifecycle()
    val log by vm.recentLog.collectAsStateWithLifecycle()
    val headphones by vm.headphoneState.collectAsStateWithLifecycle()
    val presets by vm.presets.collectAsStateWithLifecycle()
    AutomationsContent(rules, log, headphones.modes, presets, onBack, onEdit, vm::setEnabled, address = headphones.connection.deviceAddress)
}

@Composable
fun AutomationsContent(
    rules: List<AutomationRule>,
    log: List<String>,
    modes: List<ListeningMode>,
    presets: List<EqPreset>,
    onBack: () -> Unit,
    onEdit: (String?) -> Unit,
    onSetEnabled: (AutomationRule, Boolean) -> Unit,
    /** The connected headset, whose names [modes] are. */
    address: String? = null,
) {
    // The Snackbar lives in the root Scaffold, so it would cover this nested one's button;
    // lift the button while a message ("Saved …") is showing.
    val snackbarShowing = LocalSnackbarHostState.current?.currentSnackbarData != null
    val fabLift by animateDpAsState(if (snackbarShowing) 72.dp else 0.dp, label = "fabLift")
    SubScreenScaffold(title = stringResource(R.string.settings_automations), onBack = onBack) { padding ->
        Scaffold(
            // Transparent, like the sub-screen scaffold: the root draws the background and dot grid.
            containerColor = Color.Transparent,
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { onEdit(null) },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.automation_new)) },
                    modifier = Modifier.padding(bottom = fabLift),
                )
            },
            modifier = Modifier.padding(padding),
        ) { inner ->
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = inner.calculateBottomPadding() + 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (rules.isEmpty()) item { EmptyHint(stringResource(R.string.automation_empty)) }
                items(rules, key = { it.id }) { rule ->
                    SectionCard(contentPadding = PaddingValues(0.dp)) {
                        Row(
                            Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).clickable { onEdit(rule.id) }.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(rule.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(describeTrigger(rule.trigger), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    rule.actions.map { describeAction(it, modes, presets, address) }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            FieldSwitch(checked = rule.enabled, onCheckedChange = { onSetEnabled(rule, it) })
                        }
                    }
                }
                item {
                    SectionCard(title = stringResource(R.string.automation_recent), modifier = Modifier.padding(top = 8.dp)) {
                        if (log.isEmpty()) EmptyHint(stringResource(R.string.automation_recent_empty))
                        log.take(20).forEach { line ->
                            Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun describeTrigger(trigger: Trigger): String = when (trigger) {
    Trigger.OnConnect -> stringResource(R.string.trigger_on_connect)
    is Trigger.WifiNetwork -> stringResource(R.string.trigger_wifi_named, trigger.ssid)
    is Trigger.ForegroundApp -> stringResource(R.string.trigger_app_named, trigger.appLabel)
    is Trigger.Schedule -> stringResource(
        R.string.trigger_schedule_named,
        String.format(Locale.getDefault(), "%02d:%02d", trigger.hour, trigger.minute),
        trigger.days.sorted().joinToString(" ") { dayShortName(it) },
    )
}

@Composable
fun describeAction(action: AutomationAction, modes: List<ListeningMode>, presets: List<EqPreset>, address: String? = null): String = when (action) {
    // The connected headset's name for the mode the rule means, else the name saved with it.
    is AutomationAction.SetMode -> stringResource(R.string.action_set_mode_named, ModeResolver.nameOf(action, modes, address) ?: "#${action.modeIndex}")
    is AutomationAction.SetEqPreset -> stringResource(R.string.action_set_eq_named, action.presetId?.let { id -> presets.firstOrNull { it.id == id }?.name ?: id } ?: stringResource(R.string.action_eq_flat))
    is AutomationAction.SetNoise -> stringResource(
        R.string.action_set_noise_named,
        if (action.ancEnabled) action.cancellingLevel.toString() else stringResource(R.string.modes_desc_anc_off),
    )
}

fun dayShortName(day: Int): String =
    java.time.DayOfWeek.of(day.coerceIn(1, 7)).getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())

@LightDarkPreview
@Composable
private fun AutomationsPreview() {
    EarshotPreview {
        AutomationsContent(
            rules = listOf(
                AutomationRule("1", "Commute", true, Trigger.Schedule(8, 15, setOf(1, 2, 3, 4, 5)), listOf(AutomationAction.SetMode(0), AutomationAction.SetEqPreset("bass"))),
                AutomationRule("2", "Office Wi-Fi", false, Trigger.WifiNetwork("Office"), listOf(AutomationAction.SetNoise(3, true, false, 0))),
            ),
            log = listOf("Connected: set Quiet (12:04)"),
            modes = dev.earshot.core.fake.FakeHeadphoneRepository.connectedState().modes,
            presets = emptyList(),
            onBack = {}, onEdit = {}, onSetEnabled = { _, _ -> },
        )
    }
}
