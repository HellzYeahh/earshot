package dev.earshot.ui.sleep

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        // The file name comes from the file's provider, often another app's process (Drive, a
        // file manager) that may have to start first: asked off the main thread, never during
        // composition. The dialog seeds its name field once, so it opens when the answer is in
        // (an empty name if the provider had none).
        val suggestion by produceState<String?>(null, uri) {
            value = withContext(Dispatchers.IO) { suggestedSoundName(context, uri.toUri()) }
        }
        suggestion?.let { name ->
            CustomSoundNameDialog(name, onAdd = { chosen -> vm.importCustomSound(uri, chosen) }, onDismiss = { picked = null })
        }
    }
}

@Composable
fun SleepContent(ui: SleepUiState, actions: SleepActions, contentPadding: PaddingValues) {
    CompositionLocalProvider(LocalCustomSounds provides ui.customSounds) {
        if (ui.state.session == SleepSession.Idle) SleepSetup(ui, actions, contentPadding) else NightView(ui, actions, contentPadding)
    }
}

/**
 * Idle, top to bottom ([SleepSetupCard]): the headset, the alarms (with the
 * accesses that would make them late), tonight's timers and Start, the sounds, and last the
 * switches for the night.
 *
 * A LazyColumn with one keyed item per card, so the tab's first frame (inside the 180 ms tab fade)
 * composes only the cards on screen; on the cover screen the sound library and the night
 * switches below the fold were about half of that frame's composables (the list prefetches the
 * next card in idle time). Spacing and padding are the old scrolling Column's.
 *
 * A card that scrolls far off is disposed and composed again on the way back. Its lasting
 * choices are `rememberSaveable` (sound group, the open custom-timer and save-mix dialogs, the
 * carousels' scroll), which a lazy item keeps by its key; only short-lived `remember` state (a
 * slider mid-drag, an open delete confirmation) would reset, and neither can be scrolled away
 * while in use. A carousel scrolls its chosen tile into view again when its card comes back, as
 * on every first composition. The file picker for own sounds is registered by [SleepScreen],
 * outside the list, so its result never depends on a card being composed.
 */
@Composable
private fun SleepSetup(ui: SleepUiState, actions: SleepActions, contentPadding: PaddingValues) {
    val now = rememberNow(30_000)
    val check = SleepLogic.batteryCheck(ui.headset, ui.plan, ui.state.nextAlarm, ui.alarms, now)
    val cards = sleepSetupCards(ui.permissions)
    val layoutDirection = LocalLayoutDirection.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection) + 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            end = contentPadding.calculateEndPadding(layoutDirection) + 16.dp,
            // 8 dp edge plus the Column's end space: a 12 dp gap and an 8 dp spacer after the last card.
            bottom = contentPadding.calculateBottomPadding() + 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(cards, key = { it.name }, contentType = { it }) { card ->
            when (card) {
                SleepSetupCard.HEADSET -> SleepHeaderCard(
                    headset = ui.headset,
                    check = check,
                    onUseTimer = { actions.onPlanChange(ui.plan.copy(durationMinutes = it)) },
                    onRingOnPhone = actions.onRingOnPhone,
                )
                SleepSetupCard.ALARMS -> AlarmCard(
                    alarms = ui.alarms,
                    nextAlarm = ui.state.nextAlarm,
                    nowMillis = now,
                    onOpenAlarm = actions.onOpenAlarm,
                    onAlarmChange = actions.onAlarmChange,
                )
                SleepSetupCard.PERMISSIONS -> SleepPermissionBanners(ui.permissions, actions.onOpenSettings)
                SleepSetupCard.TONIGHT -> TonightCard(
                    plan = ui.plan,
                    headset = ui.headset,
                    powerOffMovesAlarm = SleepLogic.powerOffMovesAlarmToPhone(ui.plan, ui.state.nextAlarm, ui.alarms),
                    nowMillis = now,
                    onPlanChange = actions.onPlanChange,
                    onStart = actions.onStart,
                )
                SleepSetupCard.SOUNDS -> SoundLibrarySection(
                    plan = ui.plan,
                    mixes = ui.mixes,
                    previewing = ui.state.previewing,
                    onPlanChange = actions.onPlanChange,
                    actions = actions.sounds,
                    customSounds = ui.customSounds,
                    importing = ui.importing,
                )
                SleepSetupCard.NIGHT_OPTIONS -> NightOptionsCard(
                    plan = ui.plan,
                    headset = ui.headset,
                    permissions = ui.permissions,
                    onPlanChange = actions.onPlanChange,
                    onOpenSettings = actions.onOpenSettings,
                )
            }
        }
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
