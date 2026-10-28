package dev.earshot.ui.sleep

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.earshot.R
import dev.earshot.core.AlarmOutput
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/*
 * Words and clocks shared by the Sleep screens. Times follow the phone's 12/24-hour setting
 * (DateFormat.is24HourFormat), not just the locale, the way the Clock app does.
 */

/** "30 min", "2 h", "1 h 30 min". */
@Composable
fun durationText(minutes: Int): String {
    val (h, m) = SleepLogic.splitMinutes(minutes)
    val hours = pluralStringResource(R.plurals.sleep_hours_short, h, h)
    val mins = pluralStringResource(R.plurals.sleep_minutes_short, m, m)
    return when {
        h == 0 -> mins
        m == 0 -> hours
        else -> stringResource(R.string.sleep_hours_minutes, hours, mins)
    }
}

/**
 * The phone's 12/24-hour choice. Read on every composition, not remembered: changing it in
 * Settings recreates nothing, and the clocks here recompose often enough to pick it up.
 */
@Composable
fun rememberIs24Hour(): Boolean = DateFormat.is24HourFormat(LocalContext.current)

/**
 * A text size that ignores the user's font scale, for the huge clock and countdown only: they're
 * already far above any scaled body text, and scaling them further would wrap "2:41:07" on a
 * 360 dp phone.
 */
@Composable
fun fixedSize(size: Dp): TextUnit = with(LocalDensity.current) { size.toSp() }

@Composable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

/** The wall-clock time of [millis] ("07:00" or "7:00 AM"). */
@Composable
fun clockAt(millis: Long): String {
    val time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime()
    return SleepLogic.clockText(time.hour, time.minute, rememberIs24Hour(), currentLocale())
}

/** The locale's first day of the week, for the day chips and the repeat summary. */
@Composable
fun firstDayOfWeek(): java.time.DayOfWeek {
    val locale = currentLocale()
    return remember(locale) { WeekFields.of(locale).firstDayOfWeek }
}

/**
 * The current time, refreshed on each [periodMillis] boundary while composed (aligned so a
 * countdown ticks on the second, not drifting between two).
 */
@Composable
fun rememberNow(periodMillis: Long = 1_000): Long {
    val now by produceState(System.currentTimeMillis(), periodMillis) {
        while (true) {
            val t = System.currentTimeMillis()
            value = t
            delay(periodMillis - t % periodMillis)
        }
    }
    return now
}

/** "Rings in 7 h 12 min". */
@Composable
fun ringsInText(minutes: Int): String = stringResource(R.string.sleep_rings_in, durationText(minutes.coerceAtLeast(1)))

@Composable
fun alarmOutputLabel(output: AlarmOutput): String = stringResource(
    when (output) {
        AlarmOutput.EARBUDS -> R.string.sleep_output_earbuds
        AlarmOutput.PHONE -> R.string.sleep_output_phone
        AlarmOutput.BOTH -> R.string.sleep_output_both
    },
)
