package dev.earshot.ui.sleep

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.core.NextAlarm
import dev.earshot.core.SleepEndAction
import dev.earshot.core.SleepNote
import dev.earshot.core.SleepPhase
import dev.earshot.core.SleepSession
import dev.earshot.core.fake.FakeSleepRepository
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.theme.FieldDarkColors
import dev.earshot.ui.theme.FieldDarkScheme
import dev.earshot.ui.theme.LocalEarshotColors

/**
 * The tab while a session runs: dim, big time left, what the night will still do, and the two
 * buttons that matter (+15 min, Stop). After the timer: the snoozed alarm, or the ringing one.
 * The screen is left free to turn off; nothing here keeps it awake.
 */
@Composable
fun NightView(ui: SleepUiState, actions: SleepActions, contentPadding: PaddingValues) {
    when (val session = ui.state.session) {
        // The ringing screen draws its own black background and insets.
        is SleepSession.Ringing -> AlarmRingingScreen(session, onStop = actions.onDismissAlarm, onSnooze = actions.onSnooze)
        is SleepSession.Running -> NightSurface { RunningNight(session, ui, actions, contentPadding) }
        is SleepSession.Snoozed -> NightSurface { SnoozedNight(session, actions, contentPadding) }
        SleepSession.Idle -> Unit
    }
}

/**
 * Field's night palette whatever the app theme, on its darkest surface. A light theme would
 * light up the bedroom; an already-dark theme (Field or the wallpaper's) is kept as it is.
 */
@Composable
private fun NightSurface(content: @Composable () -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val body = @Composable {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxSize(),
            content = content,
        )
    }
    if (dark) {
        body()
    } else {
        CompositionLocalProvider(LocalEarshotColors provides FieldDarkColors) {
            MaterialTheme(colorScheme = FieldDarkScheme, shapes = MaterialTheme.shapes, typography = MaterialTheme.typography, content = body)
        }
    }
}

@Composable
private fun RunningNight(session: SleepSession.Running, ui: SleepUiState, actions: SleepActions, contentPadding: PaddingValues) {
    // Minute precision for the facts; only the countdown below ticks every second.
    val now = rememberNow(60_000)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            (session.headsetName ?: stringResource(R.string.sleep_night_title)).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = muted,
            textAlign = TextAlign.Center,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Countdown(session.endsAtMillis)
            Text(
                listOf(phaseLabel(session.phase), stringResource(R.string.sleep_ends_at, clockAt(session.endsAtMillis))).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
        }

        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NightFact(Icons.Default.GraphicEq, soundsLine(session))
            NightFact(Icons.Default.Hearing, noiseLine(session, now))
            NightFact(Icons.Default.PowerSettingsNew, endLine(session.plan.endAction))
            NightFact(Icons.Default.Alarm, alarmLine(ui.state.nextAlarm, now))
        }

        Row(Modifier.widthIn(max = 480.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = actions.onExtend, shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f).heightIn(min = 64.dp)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.sleep_extend, SleepLogic.EXTEND_MINUTES))
            }
            Button(onClick = actions.onStop, shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f).heightIn(min = 64.dp)) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_stop))
            }
        }

        if (ui.state.log.isNotEmpty()) {
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.sleep_log_heading).uppercase(), style = MaterialTheme.typography.labelSmall, color = muted)
                ui.state.log.forEach { LogLine(it) }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SnoozedNight(session: SleepSession.Snoozed, actions: SleepActions, contentPadding: PaddingValues) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(48.dp))
        Text(
            (session.label?.takeIf { it.isNotBlank() } ?: stringResource(R.string.sleep_snoozed_title)).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Countdown(session.ringsAtMillis)
        Text(
            stringResource(R.string.sleep_snoozed_rings_at, clockAt(session.ringsAtMillis)),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = actions.onDismissAlarm, shape = MaterialTheme.shapes.medium, modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(min = 72.dp)) {
            Text(stringResource(R.string.sleep_alarm_stop), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** The big time left, ticking each second; kept apart so the rest of the view doesn't recompose with it. */
@Composable
private fun Countdown(untilMillis: Long) {
    val now = rememberNow(1_000)
    Text(
        SleepLogic.countdown(untilMillis - now, currentLocale()),
        style = MaterialTheme.typography.displayLarge.copy(fontSize = fixedSize(80.dp), lineHeight = fixedSize(84.dp)),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.87f),
        maxLines = 1,
        softWrap = false,
    )
}

@Composable
private fun NightFact(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
    }
}

/** One line of tonight's log; a step that couldn't run is marked, never hidden. */
@Composable
private fun LogLine(note: SleepNote) {
    val color = if (note.isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.Top) {
        Text(clockAt(note.atMillis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp).widthIn(min = 64.dp))
        if (note.isProblem) {
            Icon(Icons.Default.Warning, contentDescription = stringResource(R.string.sleep_log_problem), tint = color, modifier = Modifier.padding(end = 6.dp).size(16.dp))
        }
        Text(note.text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun phaseLabel(phase: SleepPhase): String = stringResource(
    when (phase) {
        SleepPhase.FADING_IN -> R.string.sleep_phase_fading_in
        SleepPhase.PLAYING -> R.string.sleep_phase_playing
        SleepPhase.FADING_OUT -> R.string.sleep_phase_fading_out
        SleepPhase.TIMER_ONLY -> R.string.sleep_phase_timer_only
    },
)

@Composable
private fun soundsLine(session: SleepSession.Running): String {
    val layers = session.plan.layers
    if (layers.isEmpty()) return stringResource(R.string.sleep_timer_only)
    return layers.map { soundName(it.soundId) }.joinToString(" · ")
}

@Composable
private fun noiseLine(session: SleepSession.Running, now: Long): String {
    val at = session.noiseOffAtMillis
    return when {
        session.noiseOffDone -> stringResource(R.string.sleep_night_noise_off)
        at == null -> stringResource(R.string.sleep_night_noise_kept)
        at <= now -> stringResource(R.string.sleep_night_noise_going_off)
        else -> stringResource(R.string.sleep_night_noise_off_at, clockAt(at))
    }
}

@Composable
private fun endLine(action: SleepEndAction): String = stringResource(
    when (action) {
        SleepEndAction.STOP_SOUNDS -> R.string.sleep_night_end_stop
        SleepEndAction.DISCONNECT -> R.string.sleep_night_end_disconnect
        SleepEndAction.POWER_OFF -> R.string.sleep_night_end_power_off
    },
)

@Composable
private fun alarmLine(next: NextAlarm?, now: Long): String =
    if (next == null) stringResource(R.string.sleep_night_no_alarm)
    else stringResource(R.string.sleep_next_alarm, clockAt(next.atMillis), ringsInText(SleepLogic.minutesUntil(next.atMillis, now)))

// ---- Previews ---------------------------------------------------------------------------------

@LightDarkPreview
@Composable
private fun NightRunningPreview() = EarshotPreview {
    val fake = FakeSleepRepository(running = true)
    SleepContent(SleepUiState.of(fake), SleepActions(), PaddingValues(0.dp))
}

@LightDarkPreview
@Composable
private fun NightSnoozedPreview() = EarshotPreview {
    val fake = FakeSleepRepository()
    SleepContent(
        SleepUiState.of(fake).let { it.copy(state = it.state.copy(session = SleepSession.Snoozed("a1", "Work", System.currentTimeMillis() + 9 * 60_000L))) },
        SleepActions(),
        PaddingValues(0.dp),
    )
}
