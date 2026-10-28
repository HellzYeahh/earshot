package dev.earshot.ui.sleep

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.core.FormFactor
import dev.earshot.core.SleepEndAction
import dev.earshot.core.SleepHeadset
import dev.earshot.core.SleepPlan
import dev.earshot.core.fake.FakeSleepRepository
import dev.earshot.ui.common.BannerTone
import dev.earshot.ui.common.DashedDivider
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.InfoBanner
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.common.ValueSlider

/**
 * Tonight: the sleep timer and Start, the noise-cancelling timer, and what happens to the headset
 * at the end. Each timer is a carousel with a line above it saying what will happen and when
 * ("Your earbuds disconnect at 3:57 AM"), as if the night started now. The switches for the night
 * are in their own card at the bottom of the tab ([NightOptionsCard]). Every change is saved at
 * once through [onPlanChange].
 */
@Composable
fun TonightCard(
    plan: SleepPlan,
    headset: SleepHeadset,
    /** Power off at the end would leave tomorrow's earbud alarm to the phone. */
    powerOffMovesAlarm: Boolean,
    nowMillis: Long,
    onPlanChange: (SleepPlan) -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCustom by rememberSaveable { mutableStateOf(false) }
    SectionCard(modifier = modifier, title = stringResource(R.string.sleep_tonight_title)) {
        ChoiceHeading(stringResource(R.string.sleep_timer_heading))
        TimerWhenLine(plan, headset, nowMillis)
        SleepCarousel(
            options = SleepPlan.DURATION_CHOICES + CUSTOM,
            isSelected = { if (it == CUSTOM) SleepLogic.isCustomDuration(plan.durationMinutes) else plan.durationMinutes == it },
            label = { minutes ->
                when {
                    minutes != CUSTOM -> durationText(minutes)
                    SleepLogic.isCustomDuration(plan.durationMinutes) -> stringResource(R.string.sleep_timer_custom_value, durationText(plan.durationMinutes))
                    else -> stringResource(R.string.sleep_timer_custom)
                }
            },
            onSelect = { minutes -> if (minutes == CUSTOM) showCustom = true else onPlanChange(plan.copy(durationMinutes = minutes)) },
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onStart,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        ) {
            Icon(Icons.Default.Bedtime, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.sleep_start), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(16.dp))
        DashedDivider()

        ChoiceHeading(stringResource(R.string.sleep_noise_heading))
        NoiseWhenLine(plan, nowMillis)
        SleepCarousel(
            options = SleepPlan.NOISE_OFF_CHOICES,
            isSelected = { plan.noiseOffAfterMinutes == it },
            label = { noiseCarouselLabel(it) },
            onSelect = { onPlanChange(plan.copy(noiseOffAfterMinutes = it)) },
        )
        UnavailableNote(headset.noiseOff)
        Spacer(Modifier.height(8.dp))
        DashedDivider()

        ChoiceHeading(stringResource(R.string.sleep_end_heading))
        Column(Modifier.selectableGroup()) {
            SleepEndAction.entries.forEach { action ->
                SleepChoiceRow(
                    title = endActionLabel(action),
                    selected = plan.endAction == action,
                    onClick = { onPlanChange(plan.copy(endAction = action)) },
                    ability = SleepLogic.abilityFor(action, headset),
                )
            }
        }
        if (powerOffMovesAlarm) {
            InfoBanner(stringResource(R.string.sleep_end_power_off_alarm), tone = BannerTone.WARNING, modifier = Modifier.padding(vertical = 4.dp))
        }
    }

    if (showCustom) {
        CustomDurationDialog(
            initial = plan.durationMinutes,
            onPick = { onPlanChange(plan.copy(durationMinutes = it)) },
            onDismiss = { showCustom = false },
        )
    }
}

/** The carousel's "Custom" tile. */
private const val CUSTOM = -1

/** "Your earbuds disconnect at 3:57 AM" / "Sounds stop at 3:57 AM", and how the end goes. */
@Composable
private fun TimerWhenLine(plan: SleepPlan, headset: SleepHeadset, nowMillis: Long) {
    val at = clockAt(nowMillis + plan.durationMinutes * 60_000L)
    val device = stringResource(if (headset.formFactor == FormFactor.HEADPHONES) R.string.sleep_word_headphones else R.string.sleep_word_earbuds)
    val outcome = SleepLogic.endOutcome(plan, headset)
    val sounds = plan.layers.isNotEmpty()
    val headline = when (outcome) {
        SleepLogic.EndOutcome.DISCONNECTS -> stringResource(R.string.sleep_when_disconnect, device, at)
        SleepLogic.EndOutcome.SWITCHES_OFF -> stringResource(R.string.sleep_when_switch_off, device, at)
        SleepLogic.EndOutcome.STAYS_CONNECTED, SleepLogic.EndOutcome.STAYS_CONNECTED_CANT ->
            stringResource(if (sounds) R.string.sleep_when_sounds_stop else R.string.sleep_when_timer_ends, at)
    }
    val fade = if (sounds) stringResource(R.string.sleep_when_fade, durationText(SleepLogic.fadeMinutes(plan).coerceAtLeast(1))) else null
    val after = when (outcome) {
        SleepLogic.EndOutcome.DISCONNECTS -> stringResource(R.string.sleep_when_disconnect_detail)
        SleepLogic.EndOutcome.SWITCHES_OFF -> stringResource(R.string.sleep_when_switch_off_detail)
        SleepLogic.EndOutcome.STAYS_CONNECTED -> stringResource(R.string.sleep_when_stays_detail, device)
        SleepLogic.EndOutcome.STAYS_CONNECTED_CANT -> stringResource(R.string.sleep_when_stays_cant_detail, device)
    }
    WhenLine(Icons.Default.Bedtime, headline, listOfNotNull(fade, after).joinToString(" "))
}

/** "Noise cancelling goes off at 11:42 PM", or that it's left alone. */
@Composable
private fun NoiseWhenLine(plan: SleepPlan, nowMillis: Long) {
    val outcome = SleepLogic.noiseOutcome(plan)
    val offAt = SleepLogic.noiseOffAt(plan, nowMillis)?.let { clockAt(it) }
    val back = stringResource(R.string.sleep_noise_when_back)
    val (headline, detail) = when (outcome) {
        SleepLogic.NoiseOutcome.Untouched -> stringResource(R.string.sleep_noise_when_untouched) to stringResource(R.string.sleep_noise_when_untouched_detail)
        SleepLogic.NoiseOutcome.AtStart -> stringResource(R.string.sleep_noise_when_start) to back
        is SleepLogic.NoiseOutcome.After ->
            stringResource(R.string.sleep_noise_when_at, offAt.orEmpty()) to stringResource(R.string.sleep_noise_when_after_detail, durationText(outcome.minutes), back)
        is SleepLogic.NoiseOutcome.AtEnd ->
            stringResource(R.string.sleep_noise_when_at, offAt.orEmpty()) to
                stringResource(if (outcome.later) R.string.sleep_noise_when_end_later_detail else R.string.sleep_noise_when_end_detail, back)
    }
    WhenLine(Icons.Outlined.Hearing, headline, detail)
}

@Composable
private fun CustomDurationDialog(initial: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable(initial) { mutableFloatStateOf(initial.toFloat()) }
    val snapped = SleepLogic.snapDuration(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sleep_timer_custom_title)) },
        text = {
            ValueSlider(
                label = stringResource(R.string.sleep_timer_heading),
                value = value,
                valueRange = SleepPlan.MIN_MINUTES.toFloat()..SleepPlan.MAX_MINUTES.toFloat(),
                steps = SleepLogic.customSliderSteps,
                onValueChange = { value = it },
                valueText = durationText(snapped),
                startLabel = durationText(SleepPlan.MIN_MINUTES),
                endLabel = durationText(SleepPlan.MAX_MINUTES),
            )
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onPick(snapped) }) { Text(stringResource(R.string.sleep_timer_custom_set)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Short labels for the noise-cancelling carousel tiles. */
@Composable
fun noiseCarouselLabel(choice: Int?): String = when (choice) {
    null -> stringResource(R.string.sleep_noise_tile_keep)
    0 -> stringResource(R.string.sleep_noise_tile_start)
    SleepPlan.MAX_MINUTES -> stringResource(R.string.sleep_noise_tile_end)
    else -> stringResource(R.string.sleep_noise_tile_after, durationText(choice))
}

@Composable
fun endActionLabel(action: SleepEndAction): String = stringResource(
    when (action) {
        SleepEndAction.STOP_SOUNDS -> R.string.sleep_end_stop
        SleepEndAction.DISCONNECT -> R.string.sleep_end_disconnect
        SleepEndAction.POWER_OFF -> R.string.sleep_end_power_off
    },
)

// ---- Previews ---------------------------------------------------------------------------------

@LightDarkPreview
@Composable
private fun TonightPreview() = EarshotPreview {
    TonightCard(
        plan = SleepPlan(durationMinutes = 180, noiseOffAfterMinutes = 30, endAction = SleepEndAction.DISCONNECT),
        headset = FakeSleepRepository.SAMPLE_HEADSET,
        powerOffMovesAlarm = false,
        nowMillis = System.currentTimeMillis(),
        onPlanChange = {},
        onStart = {},
        modifier = Modifier.padding(16.dp),
    )
}

@LightDarkPreview
@Composable
private fun TonightGenericPreview() = EarshotPreview {
    TonightCard(
        plan = SleepPlan(durationMinutes = 105, noiseOffAfterMinutes = 60, endAction = SleepEndAction.POWER_OFF),
        headset = FakeSleepRepository.GENERIC_HEADSET,
        powerOffMovesAlarm = true,
        nowMillis = System.currentTimeMillis(),
        onPlanChange = {},
        onStart = {},
        modifier = Modifier.padding(16.dp),
    )
}
