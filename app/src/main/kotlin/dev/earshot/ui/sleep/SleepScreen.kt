package dev.earshot.ui.sleep

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.earshot.R
import dev.earshot.core.CustomSound
import dev.earshot.core.SavedMix
import dev.earshot.core.SleepAlarm
import dev.earshot.core.SleepHeadset
import dev.earshot.core.SleepPermissions
import dev.earshot.core.SleepPlan
import dev.earshot.core.SleepRepository
import dev.earshot.core.SleepSession
import dev.earshot.core.SleepState
import dev.earshot.core.fake.FakeSleepRepository
import dev.earshot.ui.LocalUiDependencies
import dev.earshot.ui.common.EarshotPreview
import dev.earshot.ui.common.LightDarkPreview
import dev.earshot.ui.common.earshotViewModel

/** Everything the Sleep tab shows, read from the repository's flows in one place. */
data class SleepUiState(
    val state: SleepState,
    val plan: SleepPlan,
    val alarms: List<SleepAlarm>,
    val mixes: List<SavedMix>,
    val headset: SleepHeadset,
    val permissions: SleepPermissions,
    val customSounds: List<CustomSound> = emptyList(),
    val importing: Boolean = false,
) {
    companion object {
        /** A snapshot of [repository], for previews and tests. */
        fun of(repository: SleepRepository) = SleepUiState(
            repository.state.value,
            repository.plan.value,
            repository.alarms.value,
            repository.mixes.value,
            repository.headset.value,
            repository.permissions.value,
            repository.customSounds.value,
        )
    }
}

/** The tab's callbacks; previews leave them as no-ops. */
class SleepActions(
    val onPlanChange: (SleepPlan) -> Unit = {},
    val onStart: () -> Unit = {},
    val onExtend: () -> Unit = {},
    val onStop: () -> Unit = {},
    val sounds: SoundLibraryActions = SoundLibraryActions(),
    val onOpenAlarm: (String?) -> Unit = {},
    val onAlarmChange: (SleepAlarm) -> Unit = {},
    val onRingOnPhone: (SleepAlarm) -> Unit = {},
    val onSnooze: () -> Unit = {},
    val onDismissAlarm: () -> Unit = {},
    val onOpenSettings: (SleepSettingsPage) -> Unit = {},
)

/**
 * The Sleep tab (docs/sleep-mode.md §5): tonight's timer, sounds and alarms while idle, the
 * night view while a session runs. Works with any headset; steps the current one can't do stay
 * visible and say why.
 */
@Composable
fun SleepScreen(contentPadding: PaddingValues, onOpenAlarm: (String?) -> Unit) {
    val vm = earshotViewModel { SleepViewModel(it.sleep, it.messenger) }
    val state by vm.state.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val alarms by vm.alarms.collectAsStateWithLifecycle()
    val mixes by vm.mixes.collectAsStateWithLifecycle()
    val headset by vm.headset.collectAsStateWithLifecycle()
    val permissions by vm.permissions.collectAsStateWithLifecycle()
    val customSounds by vm.customSounds.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Own sounds: the system picker (any audio file, no storage permission needed), then a name.
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> picked = uri?.toString() }
    val messenger = LocalUiDependencies.current.messenger
    val limitText = pluralStringResource(R.plurals.sleep_sounds_limit, SleepPlan.MAX_LAYERS, SleepPlan.MAX_LAYERS)

    // Back from Settings: the user may have just granted an access. Leaving the tab (or the app)
    // ends a preview: it's for choosing sounds here, and Android 17 mutes a backgrounded app anyway.
    LifecycleResumeEffect(vm) {
        vm.refreshPermissions()
        onPauseOrDispose { vm.stopPreview() }
    }
    // A session started elsewhere (tile, notification) or an alarm that rings replaces the setup
    // view, and the preview with it.
    val idle = state.session == SleepSession.Idle
    LaunchedEffect(idle) { if (!idle) vm.stopPreview() }

    val actions = remember(vm, context, messenger, limitText, onOpenAlarm) {
        SleepActions(
            onPlanChange = vm::updatePlan,
            onStart = vm::start,
            onExtend = vm::extend,
            onStop = vm::stop,
            sounds = SoundLibraryActions(
                onToggleSound = { id -> if (!vm.toggleSound(id)) messenger.show(limitText) },
                onLayerGain = vm::setLayerGain,
                onRemoveLayer = vm::removeLayer,
                onTimerOnly = vm::timerOnly,
                onTogglePreview = vm::togglePreview,
                onLoadMix = vm::loadMix,
                onSaveMix = vm::saveMix,
                onDeleteMix = vm::deleteMix,
                onAddCustom = { runCatching { picker.launch(arrayOf("audio/*")) } },
                onDeleteCustom = vm::deleteCustomSound,
            ),
            onOpenAlarm = onOpenAlarm,
            onAlarmChange = { vm.saveAlarm(it) },
            onRingOnPhone = vm::ringOnPhone,
            onSnooze = vm::snooze,
            onDismissAlarm = vm::dismissAlarm,
            onOpenSettings = { page -> SleepSettings.open(context, page) },
        )
    }
    SleepContent(SleepUiState(state, plan, alarms, mixes, headset, permissions, customSounds, importing), actions, contentPadding)

    picked?.let { uri ->
        val suggestion = remember(uri) { suggestedSoundName(context, uri.toUri()) }
        CustomSoundNameDialog(suggestion, onAdd = { name -> vm.importCustomSound(uri, name) }, onDismiss = { picked = null })
    }
}

@Composable
fun SleepContent(ui: SleepUiState, actions: SleepActions, contentPadding: PaddingValues) {
    CompositionLocalProvider(LocalCustomSounds provides ui.customSounds) {
        if (ui.state.session == SleepSession.Idle) SleepSetup(ui, actions, contentPadding) else NightView(ui, actions, contentPadding)
    }
}

/**
 * Idle, top to bottom (the user's order): the headset, the alarms (with the accesses that would
 * make them late), tonight's timers and Start, the sounds, and last the switches for the night.
 */
@Composable
private fun SleepSetup(ui: SleepUiState, actions: SleepActions, contentPadding: PaddingValues) {
    val now = rememberNow(30_000)
    val check = SleepLogic.batteryCheck(ui.headset, ui.plan, ui.state.nextAlarm, ui.alarms, now)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SleepHeaderCard(
            headset = ui.headset,
            check = check,
            onUseTimer = { actions.onPlanChange(ui.plan.copy(durationMinutes = it)) },
            onRingOnPhone = actions.onRingOnPhone,
        )
        AlarmCard(
            alarms = ui.alarms,
            nextAlarm = ui.state.nextAlarm,
            nowMillis = now,
            onOpenAlarm = actions.onOpenAlarm,
            onAlarmChange = actions.onAlarmChange,
        )
        SleepPermissionBanners(ui.permissions, actions.onOpenSettings)
        TonightCard(
            plan = ui.plan,
            headset = ui.headset,
            powerOffMovesAlarm = SleepLogic.powerOffMovesAlarmToPhone(ui.plan, ui.state.nextAlarm, ui.alarms),
            nowMillis = now,
            onPlanChange = actions.onPlanChange,
            onStart = actions.onStart,
        )
        SoundLibrarySection(
            plan = ui.plan,
            mixes = ui.mixes,
            previewing = ui.state.previewing,
            onPlanChange = actions.onPlanChange,
            actions = actions.sounds,
            customSounds = ui.customSounds,
            importing = ui.importing,
        )
        NightOptionsCard(
            plan = ui.plan,
            headset = ui.headset,
            permissions = ui.permissions,
            onPlanChange = actions.onPlanChange,
            onOpenSettings = actions.onOpenSettings,
        )
        Spacer(Modifier.height(8.dp))
    }
}

// ---- Previews ---------------------------------------------------------------------------------

@LightDarkPreview
@Composable
private fun SleepSetupPreview() = EarshotPreview {
    SleepContent(SleepUiState.of(remember { FakeSleepRepository() }), SleepActions(), PaddingValues(0.dp))
}

@LightDarkPreview
@Composable
private fun SleepSetupGenericPreview() = EarshotPreview {
    val fake = remember { FakeSleepRepository(headset = FakeSleepRepository.GENERIC_HEADSET) }
    SleepContent(SleepUiState.of(fake), SleepActions(), PaddingValues(0.dp))
}
