package dev.earshot.ui.sleep

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.SpeakerPhone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.core.BatteryLevel
import dev.earshot.core.SleepAlarm
import dev.earshot.core.SleepHeadset
import dev.earshot.core.fake.FakeSleepRepository
import dev.earshot.ui.common.BannerTone
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.InfoBanner
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.home.BatteryRing
import dev.earshot.ui.home.formFactorIcon
import dev.earshot.ui.home.formFactorLabel

/**
 * Top of the Sleep tab: which headset the night will use, its batteries (Home's dials) and how
 * long it should last, with the bedtime battery check below. The estimate is always "about":
 * most headsets only report a percentage and the minutes are Earshot's guess.
 */
@Composable
fun SleepHeaderCard(
    headset: SleepHeadset,
    check: SleepLogic.BatteryCheck,
    onUseTimer: (Int) -> Unit,
    onRingOnPhone: (SleepAlarm) -> Unit,
    modifier: Modifier = Modifier,
) {
    val present = headset.name != null && headset.audioConnected
    val battery = headset.battery
    val budBatteries = present && battery != null && (battery.left != null || battery.right != null)
    SectionCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (budBatteries) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(64.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            formFactorIcon(headset.formFactor),
                            contentDescription = formFactorLabel(headset.formFactor),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            } else {
                BatteryRing(percent = if (present) headset.batteryPercent else null, charging = false, size = 64.dp)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    (headset.name ?: stringResource(R.string.sleep_no_headset)).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    statusLine(headset).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        if (budBatteries && battery != null) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BudBattery(
                    battery.left,
                    stringResource(R.string.battery_left_short),
                    battery.left?.percent?.let { stringResource(R.string.battery_left_percent, it) } ?: stringResource(R.string.battery_left_unknown),
                    Modifier.weight(1f),
                )
                BudBattery(
                    battery.right,
                    stringResource(R.string.battery_right_short),
                    battery.right?.percent?.let { stringResource(R.string.battery_right_percent, it) } ?: stringResource(R.string.battery_right_unknown),
                    Modifier.weight(1f),
                )
                if (battery.case != null) {
                    BudBattery(
                        battery.case,
                        stringResource(R.string.battery_case_last_known),
                        battery.case.percent?.let { stringResource(R.string.battery_case_percent, it) } ?: stringResource(R.string.battery_case_unknown),
                        Modifier.weight(1f),
                    )
                }
            }
        }
        if (!present) {
            Spacer(Modifier.height(12.dp))
            InfoBanner(stringResource(R.string.sleep_no_headset_hint), icon = Icons.Default.SpeakerPhone)
        }
        BatteryCheckBanner(check, onUseTimer, onRingOnPhone)
    }
}

@Composable
private fun statusLine(headset: SleepHeadset): String = when {
    headset.name == null -> stringResource(R.string.sleep_status_none)
    !headset.audioConnected -> stringResource(R.string.sleep_status_not_playing)
    headset.minutesLeft != null -> {
        val time = durationText(SleepLogic.roughMinutes(headset.minutesLeft))
        if (headset.minutesLeftIsGuess) stringResource(R.string.sleep_lasts_about, time) else stringResource(R.string.sleep_time_left, time)
    }
    headset.batteryPercent != null -> stringResource(R.string.sleep_status_battery_only)
    else -> stringResource(R.string.sleep_status_battery_unknown)
}

@Composable
private fun BudBattery(level: BatteryLevel?, label: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier.clearAndSetSemantics { contentDescription = description }, horizontalAlignment = Alignment.CenterHorizontally) {
        BatteryRing(percent = level?.percent, charging = false, size = 48.dp, stroke = 5.dp, textStyle = MaterialTheme.typography.labelMedium)
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** The bedtime battery check: what won't last, and the one tap that fixes it. */
@Composable
private fun BatteryCheckBanner(check: SleepLogic.BatteryCheck, onUseTimer: (Int) -> Unit, onRingOnPhone: (SleepAlarm) -> Unit) {
    when (check) {
        SleepLogic.BatteryCheck.Fine -> Unit
        is SleepLogic.BatteryCheck.TimerOutlastsBattery -> {
            Spacer(Modifier.height(12.dp))
            val left = durationText(SleepLogic.roughMinutes(check.minutesLeft))
            val suggested = check.suggestedMinutes
            InfoBanner(
                stringResource(R.string.sleep_battery_timer, left, durationText(check.timerMinutes)),
                tone = BannerTone.WARNING,
                icon = Icons.Default.BatteryAlert,
                action = if (suggested == null) null else {
                    { TextButton(onClick = { onUseTimer(suggested) }) { Text(stringResource(R.string.sleep_battery_use_timer, durationText(suggested))) } }
                },
            )
        }
        is SleepLogic.BatteryCheck.AlarmOutlastsBattery -> {
            Spacer(Modifier.height(12.dp))
            val left = durationText(SleepLogic.roughMinutes(check.minutesLeft))
            InfoBanner(
                stringResource(R.string.sleep_battery_alarm, left, durationText(check.minutesToAlarm)),
                tone = BannerTone.WARNING,
                icon = Icons.Default.BatteryAlert,
                action = { TextButton(onClick = { onRingOnPhone(check.alarm) }) { Text(stringResource(R.string.sleep_battery_ring_phone)) } },
            )
        }
    }
}

// ---- Previews ---------------------------------------------------------------------------------

@LightDarkPreview
@Composable
private fun SleepHeaderPreview() = EarshotPreview {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SleepHeaderCard(
            FakeSleepRepository.SAMPLE_HEADSET.copy(minutesLeft = 150),
            SleepLogic.BatteryCheck.TimerOutlastsBattery(150, 180, 120),
            onUseTimer = {},
            onRingOnPhone = {},
        )
        SleepHeaderCard(FakeSleepRepository.GENERIC_HEADSET, SleepLogic.BatteryCheck.Fine, onUseTimer = {}, onRingOnPhone = {})
        SleepHeaderCard(SleepHeadset(), SleepLogic.BatteryCheck.Fine, onUseTimer = {}, onRingOnPhone = {})
    }
}
