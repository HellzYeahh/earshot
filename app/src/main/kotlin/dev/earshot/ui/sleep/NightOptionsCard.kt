package dev.earshot.ui.sleep

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.earshot.R
import dev.earshot.core.NightExtra
import dev.earshot.core.SleepHeadset
import dev.earshot.core.SleepPermissions
import dev.earshot.core.SleepPlan
import dev.earshot.core.fake.FakeSleepRepository
import dev.earshot.ui.common.BannerTone
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.InfoBanner
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.SectionCard
import dev.earshot.ui.common.SwitchRow
import dev.earshot.ui.common.ValueSlider

/**
 * The switches for the night, last on the tab: touch lock, voice prompts, Do Not Disturb, the
 * volume cap and pausing other apps. Set once and left alone, so they sit below the choices made
 * every evening. Each one is put back in the morning.
 */
@Composable
fun NightOptionsCard(
    plan: SleepPlan,
    headset: SleepHeadset,
    permissions: SleepPermissions,
    onPlanChange: (SleepPlan) -> Unit,
    onOpenSettings: (SleepSettingsPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun toggle(extra: NightExtra, on: Boolean) =
        onPlanChange(plan.copy(nightExtras = if (on) plan.nightExtras + extra else plan.nightExtras - extra))

    SectionCard(modifier = modifier, title = stringResource(R.string.sleep_extras_heading)) {
        SleepSwitchRow(
            title = stringResource(R.string.sleep_extra_touch_lock),
            subtitle = stringResource(R.string.sleep_extra_touch_lock_hint),
            checked = NightExtra.TOUCH_LOCK in plan.nightExtras,
            onCheckedChange = { toggle(NightExtra.TOUCH_LOCK, it) },
            ability = SleepLogic.abilityFor(NightExtra.TOUCH_LOCK, headset),
        )
        SleepSwitchRow(
            title = stringResource(R.string.sleep_extra_prompts_off),
            subtitle = stringResource(R.string.sleep_extra_prompts_off_hint),
            checked = NightExtra.PROMPTS_OFF in plan.nightExtras,
            onCheckedChange = { toggle(NightExtra.PROMPTS_OFF, it) },
            ability = SleepLogic.abilityFor(NightExtra.PROMPTS_OFF, headset),
        )
        SleepSwitchRow(
            title = stringResource(R.string.sleep_extra_dnd),
            subtitle = stringResource(R.string.sleep_extra_dnd_hint),
            checked = NightExtra.DO_NOT_DISTURB in plan.nightExtras,
            onCheckedChange = { toggle(NightExtra.DO_NOT_DISTURB, it) },
        )
        if (!permissions.doNotDisturb) {
            // Asked for here, not in the banners at the top: only someone who wants this extra needs it.
            InfoBanner(
                stringResource(R.string.sleep_extra_dnd_access),
                tone = if (NightExtra.DO_NOT_DISTURB in plan.nightExtras) BannerTone.WARNING else BannerTone.INFO,
                action = {
                    TextButton(onClick = { onOpenSettings(SleepSettingsPage.DO_NOT_DISTURB) }) { Text(stringResource(R.string.sleep_extra_dnd_access_action)) }
                },
            )
        }
        val capOn = NightExtra.VOLUME_CAP in plan.nightExtras
        SleepSwitchRow(
            title = stringResource(R.string.sleep_extra_volume_cap),
            subtitle = stringResource(R.string.sleep_extra_volume_cap_hint),
            checked = capOn,
            onCheckedChange = { toggle(NightExtra.VOLUME_CAP, it) },
        )
        if (capOn) {
            // Tracks the drag locally; the plan is saved when the finger lifts.
            var drag by remember(plan.volumeCapPercent) { mutableFloatStateOf(plan.volumeCapPercent.toFloat()) }
            ValueSlider(
                label = stringResource(R.string.sleep_extra_volume_cap_level),
                value = drag,
                valueRange = 10f..100f,
                steps = 17,
                onValueChange = { drag = it },
                onValueChangeFinished = { onPlanChange(plan.copy(volumeCapPercent = drag.toInt())) },
                valueText = stringResource(R.string.sleep_percent, drag.toInt()),
            )
        }
        SwitchRow(
            title = stringResource(R.string.sleep_pause_other),
            subtitle = stringResource(R.string.sleep_pause_other_hint),
            checked = plan.pauseOtherAudio,
            onCheckedChange = { onPlanChange(plan.copy(pauseOtherAudio = it)) },
        )
    }
}

// ---- Previews ---------------------------------------------------------------------------------

@LightDarkPreview
@Composable
private fun NightOptionsPreview() = EarshotPreview {
    NightOptionsCard(
        plan = SleepPlan(nightExtras = setOf(NightExtra.TOUCH_LOCK, NightExtra.VOLUME_CAP)),
        headset = FakeSleepRepository.GENERIC_HEADSET,
        permissions = SleepPermissions(doNotDisturb = false),
        onPlanChange = {},
        onOpenSettings = {},
        modifier = Modifier.padding(16.dp),
    )
}
