package dev.earshot.ui.sleep

import dev.earshot.R
import dev.earshot.core.CustomSound
import dev.earshot.core.SavedMix
import dev.earshot.core.SleepAlarm
import dev.earshot.core.AlarmOutput
import dev.earshot.core.SleepPlan
import dev.earshot.core.SleepRepository
import dev.earshot.ui.common.EarshotViewModel
import dev.earshot.ui.common.UiMessenger

/**
 * The Sleep tab and the alarm editor. Commands go through [launchCatching] (a refusal becomes a
 * Snackbar line); the non-suspend preview calls get the same treatment by hand.
 */
class SleepViewModel(private val sleep: SleepRepository, messenger: UiMessenger) : EarshotViewModel(messenger) {
    val state = sleep.state
    val plan = sleep.plan
    val alarms = sleep.alarms
    val mixes = sleep.mixes
    val headset = sleep.headset
    val permissions = sleep.permissions
    val customSounds = sleep.customSounds

    private val _importing = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** An own sound is being copied and decoded (the "Your own sound" tile spins). */
    val importing: kotlinx.coroutines.flow.StateFlow<Boolean> = _importing

    /** Adds the picked file as an own sound, then puts it in the mix if there's room. */
    fun importCustomSound(uri: String, name: String) = launchCatching {
        _importing.value = true
        try {
            val sound = sleep.importCustomSound(uri, name)
            messenger.show(R.string.sleep_custom_added, sound.name)
            if (sound.id !in plan.value.layers.map { it.soundId }) toggleSound(sound.id)
        } finally {
            _importing.value = false
        }
    }

    fun deleteCustomSound(sound: CustomSound) = launchCatching { sleep.deleteCustomSound(sound.id) }

    /**
     * Saves tonight's settings. While a preview plays it follows the mix (sounds, level, side),
     * so what the user hears is what Start will play.
     */
    fun updatePlan(new: SleepPlan) {
        val old = sleep.plan.value
        if (new == old) return
        launchCatching {
            sleep.updatePlan(new)
            val audible = new.layers != old.layers || new.volume != old.volume || new.soundSide != old.soundSide
            if (sleep.state.value.previewing && audible) {
                if (new.layers.isEmpty()) sleep.stopPreview() else sleep.preview(new.layers)
            }
        }
    }

    /** A tile tap. False when the mix is full, so the screen can say why nothing happened. */
    fun toggleSound(soundId: String): Boolean = when (val change = SleepLogic.toggleLayer(plan.value.layers, soundId)) {
        is SleepLogic.LayerChange.Changed -> { updatePlan(plan.value.copy(layers = change.layers)); true }
        SleepLogic.LayerChange.LimitReached -> false
    }

    fun setLayerGain(soundId: String, gain: Float) = updatePlan(plan.value.copy(layers = SleepLogic.setGain(plan.value.layers, soundId, gain)))
    fun removeLayer(soundId: String) = updatePlan(plan.value.copy(layers = plan.value.layers.filterNot { it.soundId == soundId }))

    /** "No sound, timer only": for falling asleep to a podcast or music from another app. */
    fun timerOnly() = updatePlan(plan.value.copy(layers = emptyList()))
    fun loadMix(mix: SavedMix) = updatePlan(plan.value.copy(layers = mix.layers.take(SleepPlan.MAX_LAYERS)))

    fun togglePreview() = guarded {
        when {
            state.value.previewing -> sleep.stopPreview()
            plan.value.layers.isNotEmpty() -> sleep.preview(plan.value.layers)
            else -> Unit
        }
    }

    fun stopPreview() = guarded { if (state.value.previewing) sleep.stopPreview() }

    /** The repository stops the preview itself; stopping it first keeps the two from overlapping. */
    fun start() = launchCatching {
        runCatching { sleep.stopPreview() }
        sleep.start()
    }

    fun extend() = launchCatching { sleep.extend(SleepLogic.EXTEND_MINUTES) }
    fun stop() = launchCatching { sleep.stop() }
    fun snooze() = launchCatching { sleep.snooze() }
    fun dismissAlarm() = launchCatching { sleep.dismissAlarm() }

    fun saveMix(name: String) = launchCatching {
        val trimmed = name.trim()
        sleep.saveMix(trimmed, plan.value.layers)
        messenger.show(R.string.sleep_mix_saved, trimmed)
    }

    fun deleteMix(mix: SavedMix) = launchCatching { sleep.deleteMix(mix.id) }

    /** [confirmation] is the screen's "Alarm set for …" line, worded where plurals are at hand. */
    fun saveAlarm(alarm: SleepAlarm, confirmation: String? = null, onDone: () -> Unit = {}) = launchCatching {
        sleep.saveAlarm(alarm)
        confirmation?.let { messenger.show(it) }
        onDone()
    }

    fun deleteAlarm(id: String, onDone: () -> Unit = {}) = launchCatching {
        previewTone(null)
        sleep.deleteAlarm(id)
        onDone()
    }

    /** The battery check's "Ring on the phone". */
    fun ringOnPhone(alarm: SleepAlarm) = saveAlarm(alarm.copy(output = AlarmOutput.PHONE))

    fun previewTone(toneId: String?, output: AlarmOutput = AlarmOutput.EARBUDS) = guarded { sleep.previewAlarmTone(toneId, output) }

    fun refreshPermissions() = guarded { sleep.refreshPermissions() }

    private inline fun guarded(block: () -> Unit) {
        runCatching(block).onFailure { messenger.report(it) }
    }
}
