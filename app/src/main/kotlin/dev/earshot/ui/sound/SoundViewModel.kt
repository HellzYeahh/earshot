package dev.earshot.ui.sound

import androidx.lifecycle.viewModelScope
import dev.earshot.R
import dev.earshot.core.AppSoundProfile
import dev.earshot.core.AudioTuningRepository
import dev.earshot.core.HeadphoneEq
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.HeadphoneState
import dev.earshot.core.TuningState
import dev.earshot.dsp.AutoEqParser
import dev.earshot.dsp.EqPreset
import dev.earshot.dsp.Headroom
import dev.earshot.dsp.ParametricBand
import dev.earshot.ui.common.EarshotViewModel
import dev.earshot.ui.common.UiMessenger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.earshot.audio.HeadsetEqMemory
import java.util.UUID

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SoundViewModel(
    private val headphones: HeadphoneRepository,
    private val tuning: AudioTuningRepository,
    messenger: UiMessenger,
    /** Remembers the picked preset for the headset in use; null in previews. */
    private val headsetEq: HeadsetEqMemory? = null,
    /** Where the Pro EQ curve maths runs ([curve]); tests pass their own. */
    curveDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : EarshotViewModel(messenger) {

    val headphoneState: StateFlow<HeadphoneState> = headphones.state
    val tuningState: StateFlow<TuningState> = tuning.state
    val presets: StateFlow<List<EqPreset>> = tuning.presets
    val appProfiles: StateFlow<List<AppSoundProfile>> = tuning.appProfiles

    // ---- Headphone EQ ([1.7]): drag locally, write after a 250 ms pause ----------------------
    private val eqDraft = MutableStateFlow<HeadphoneEq?>(null)

    /** What the sliders show: the in-flight draft if any, otherwise the headphones' value. */
    val headphoneEq: StateFlow<HeadphoneEq?> = combine(headphones.state, eqDraft) { s, d -> d ?: s.eq }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), headphones.state.value.eq)

    fun setHeadphoneEq(eq: HeadphoneEq) { eqDraft.value = eq }

    // ---- Pro EQ bands: same idea, shorter pause (the engine runs on the phone) ---------------
    private val bandsDraft = MutableStateFlow<List<ParametricBand>?>(null)

    val bands: StateFlow<List<ParametricBand>> = combine(tuning.state, bandsDraft) { s, d -> d ?: s.settings.bands }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), tuning.state.value.settings.bands)

    fun setBands(bands: List<ParametricBand>) { bandsDraft.value = bands }

    // ---- Pro EQ curve: the drawing, the recommended preamp and the boost overshoot ---------------
    /**
     * Worked out on [curveDispatcher], once per change of the bands or the preamp. In composition
     * it was 128 + 2 x 512 response points per band on the main thread, every frame of a slider
     * drag. mapLatest drops a result the finger has already moved past, so the curve may trail
     * the slider by a frame; the Auto preamp button still computes from [bands] itself. The first
     * value is computed right here so the tab's first frame already has its curve.
     */
    val curve: StateFlow<EqCurveData> =
        combine(bands, tuning.state.map { it.settings.preampDb }.distinctUntilChanged()) { b, preamp -> b to preamp }
            .mapLatest { (b, preamp) -> withContext(curveDispatcher) { EqCurveData(b, preamp) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EqCurveData(bands.value, tuning.state.value.settings.preampDb))

    init {
        viewModelScope.launch {
            eqDraft.filterNotNull().debounce(EQ_DEBOUNCE_MS).collect { draft ->
                runCatching { headphones.setEq(draft) }.onFailure { messenger.report(it) }
                eqDraft.compareAndSet(draft, null)
            }
        }
        viewModelScope.launch {
            bandsDraft.filterNotNull().debounce(BANDS_DEBOUNCE_MS).collect { draft ->
                runCatching { tuning.setBands(draft) }.onFailure { messenger.report(it) }
                bandsDraft.compareAndSet(draft, null)
            }
        }
    }

    fun setProEqEnabled(enabled: Boolean) = launchCatching { tuning.setEnabled(enabled) }
    fun setUseGlobalSession(enabled: Boolean) = launchCatching { tuning.setUseGlobalSession(enabled) }

    /** Sets the preamp to exactly cancel the worst-case positive band gain (DSP's [Headroom]). */
    fun autoPreamp() = launchCatching { tuning.setPreamp(Headroom.recommendedPreampDb(bands.value)) }

    /** Paste of an AutoEq "ParametricEQ.txt"; parse failures become a Snackbar line. */
    fun importAutoEq(text: String, name: String) = launchCatching {
        val preset = try {
            AutoEqParser.parse(text, id = "autoeq-" + UUID.randomUUID().toString(), name = name.trim().ifBlank { "AutoEq" })
        } catch (e: AutoEqParser.ParseException) {
            messenger.show(R.string.sound_autoeq_parse_error, e.message.orEmpty())
            return@launchCatching
        }
        tuning.savePreset(preset)
        bandsDraft.value = null
        tuning.applyPreset(preset.id)
        headsetEq?.rememberPick(preset.id)
        if (preset.bands.size == 1) messenger.show(R.string.sound_autoeq_imported_one, preset.name)
        else messenger.show(R.string.sound_autoeq_imported, preset.name, preset.bands.size)
    }
    fun applyPreset(id: String?) = launchCatching {
        bandsDraft.value = null
        tuning.applyPreset(id)
        headsetEq?.rememberPick(id)
    }
    fun setPreamp(db: Double) = launchCatching { tuning.setPreamp(db) }
    fun setBoost(db: Double) = launchCatching { tuning.setBoost(db) }
    fun setLimiter(enabled: Boolean) = launchCatching { tuning.setLimiter(enabled) }

    fun savePreset(name: String) = launchCatching {
        val s = tuning.state.value.settings
        val preset = EqPreset(id = "user-" + UUID.randomUUID().toString(), name = name.trim(), bands = bands.value, preampDb = s.preampDb)
        tuning.savePreset(preset)
        tuning.applyPreset(preset.id)
        headsetEq?.rememberPick(preset.id)
        messenger.show(R.string.sound_preset_saved, preset.name)
    }

    fun deletePreset(preset: EqPreset) = launchCatching {
        if (preset.isBuiltIn) return@launchCatching
        tuning.deletePreset(preset.id)
        messenger.show(R.string.sound_preset_deleted, preset.name)
    }

    fun setAppProfile(profile: AppSoundProfile) = launchCatching { tuning.setAppProfile(profile) }
    fun removeAppProfile(packageName: String) = launchCatching { tuning.removeAppProfile(packageName) }

    private companion object {
        const val EQ_DEBOUNCE_MS = 250L
        const val BANDS_DEBOUNCE_MS = 120L
    }
}
