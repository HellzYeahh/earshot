package dev.earshot.ui.sleep

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.core.AlarmOutput
import dev.earshot.core.NextAlarm
import dev.earshot.core.SleepAlarm
import dev.earshot.core.fake.FakeSleepRepository
import dev.earshot.ui.common.DashedDivider
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.EmptyHint
import dev.earshot.ui.common.FieldSwitch
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.SectionCard
import java.time.format.TextStyle

/**
 * The wake-up alarms, earliest first, each with its switch and a one-line summary; a tap opens
 * the editor. The next ring is computed by the repository (it knows snoozes and skipped days).
 */
@Composable
fun AlarmCard(
    alarms: List<SleepAlarm>,
    nextAlarm: NextAlarm?,
    nowMillis: Long,
    onOpenAlarm: (String?) -> Unit,
    onAlarmChange: (SleepAlarm) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(modifier = modifier, title = stringResource(R.string.sleep_alarms_title)) {
        if (nextAlarm != null) {
            Text(
                stringResource(
                    R.string.sleep_next_alarm,
                    clockAt(nextAlarm.atMillis),
                    ringsInText(SleepLogic.minutesUntil(nextAlarm.atMillis, nowMillis)),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        if (alarms.isEmpty()) EmptyHint(stringResource(R.string.sleep_alarms_empty))
        alarms.sortedBy { it.hour * 60 + it.minute }.forEachIndexed { index, alarm ->
            if (index > 0) DashedDivider()
            AlarmRow(alarm, onClick = { onOpenAlarm(alarm.id) }, onEnabledChange = { onAlarmChange(alarm.copy(enabled = it)) })
        }
        TextButton(onClick = { onOpenAlarm(null) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.sleep_alarm_add))
        }
    }
}

@Composable
private fun AlarmRow(alarm: SleepAlarm, onClick: () -> Unit, onEnabledChange: (Boolean) -> Unit) {
    val (time, amPm) = SleepLogic.clockParts(alarm.hour, alarm.minute, rememberIs24Hour(), currentLocale())
    val tint = if (alarm.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 72.dp).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(time, style = MaterialTheme.typography.headlineLarge, color = tint)
                if (amPm != null) Text(amPm, style = MaterialTheme.typography.labelMedium, color = tint, modifier = Modifier.padding(bottom = 6.dp))
            }
            if (!alarm.label.isNullOrBlank()) {
                Text(alarm.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                alarmSummaryText(alarm),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        val switchLabel = stringResource(R.string.sleep_alarm_switch, listOfNotNull(time, amPm).joinToString(" "))
        FieldSwitch(
            checked = alarm.enabled,
            onCheckedChange = onEnabledChange,
            modifier = Modifier.semantics { contentDescription = switchLabel },
        )
    }
}

/** "Weekdays · Earbuds, phone after 60 s · Gentle wake 20 min". */
@Composable
fun alarmSummaryText(alarm: SleepAlarm): String {
    val summary = SleepLogic.alarmSummary(alarm, firstDayOfWeek())
    val parts = buildList {
        add(repeatText(summary.repeat, summary.days))
        add(
            when (summary.output) {
                AlarmOutput.EARBUDS -> stringResource(R.string.sleep_summary_earbuds, summary.phoneAfterSeconds ?: SleepAlarm.PHONE_FALLBACK_SECONDS)
                AlarmOutput.PHONE -> stringResource(R.string.sleep_summary_phone)
                AlarmOutput.BOTH -> stringResource(R.string.sleep_summary_both)
            },
        )
        summary.wakeWindowMinutes?.let { add(stringResource(R.string.sleep_summary_wake, pluralStringResource(R.plurals.sleep_minutes_short, it, it))) }
    }
    return parts.joinToString(" · ")
}

@Composable
fun repeatText(kind: SleepLogic.RepeatKind, days: List<Int>): String = when (kind) {
    SleepLogic.RepeatKind.ONCE -> stringResource(R.string.sleep_repeat_once)
    SleepLogic.RepeatKind.EVERY_DAY -> stringResource(R.string.sleep_repeat_every_day)
    SleepLogic.RepeatKind.WEEKDAYS -> stringResource(R.string.sleep_repeat_weekdays)
    SleepLogic.RepeatKind.WEEKENDS -> stringResource(R.string.sleep_repeat_weekends)
    SleepLogic.RepeatKind.CUSTOM -> {
        val locale = currentLocale()
        days.joinToString(" ") { java.time.DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, locale) }
    }
}

// ---- Previews ---------------------------------------------------------------------------------

// Read outside composition (lint: StateFlow.value in a composable).
private val previewAlarms = FakeSleepRepository().alarms.value

@LightDarkPreview
@Composable
private fun AlarmCardPreview() = EarshotPreview {
    val now = System.currentTimeMillis()
    AlarmCard(
        alarms = previewAlarms + SleepAlarm("a2", 9, 30, days = setOf(6, 7), enabled = false, output = AlarmOutput.PHONE, label = "Weekend"),
        nextAlarm = NextAlarm("a1", now + (7 * 60 + 12) * 60_000L),
        nowMillis = now,
        onOpenAlarm = {},
        onAlarmChange = {},
        modifier = Modifier.padding(16.dp),
    )
}
