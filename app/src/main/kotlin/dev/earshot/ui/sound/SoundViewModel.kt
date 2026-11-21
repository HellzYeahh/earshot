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
import dev.earshot.dsp.Ear
import dev.earshot.dsp.EqPreset
import dev.earshot.dsp.EqResponse
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
import kotlin.math.ceil

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
    /** A band edit tagged with the ear it belongs to; null means the shared curve (both ears). */
    private data class BandDraft(val ear: Ear?, val bands: List<ParametricBand>)

    private val bandsDraft = MutableStateFlow<BandDraft?>(null)

    /** Last ear picked in the editor; meaningful only while per-ear mode is on. */
    private val earChoice = MutableStateFlow<Ear?>(null)

    /** Which ear the editor shows; null means one shared curve for both ears. */
    val editingEar: StateFlow<Ear?> = combine(earChoice, tuning.state) { choice, s ->
        if (!s.settings.perEarEnabled) null else choice ?: Ear.LEFT
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val bands: StateFlow<List<ParametricBand>> =
        combine(tuning.state, bandsDraft, editingEar) { s, draft, ear ->
            // A draft from the other ear is stale the moment the user switches; show that ear's own curve.
            draft?.takeIf { it.ear == ear }?.bands ?: s.settings.bandsFor(ear)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), tuning.state.value.settings.bands)

    fun setBands(bands: List<ParametricBand>) { bandsDraft.value = BandDraft(editingEar.value, bands) }

    /**
     * Switch the editor between one curve for both ears (null) and a single ear. Choosing "both"
     * links the ears on the curve being edited, which is what "both" means to the user.
     */
    fun selectEar(ear: Ear?) = launchCatching {
        if (ear == null && editingEar.value != null) tuning.setBands(bands.value)
        tuning.setPerEarEnabled(ear != null)
        earChoice.value = ear
        bandsDraft.value = null
    }

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

    /** Peak of the loudest ear at construction, so the first frame's safety numbers are right too. */
    private val initialPeakDb: Double = run {
        val settings = tuning.state.value.settings
        maxOf(peakDb(settings.bandsFor(Ear.LEFT)), peakDb(settings.bandsFor(Ear.RIGHT)))
    }

    /**
     * Peak of the loudest ear, on [curveDispatcher]. Preamp and boost are shared by both output
     * channels, so the safety numbers have to cover the louder curve, not only the one on screen.
     */
    val worstBandPeakDb: StateFlow<Double> =
        combine(bands, tuning.state, editingEar) { selected, s, ear ->
            val left = if (ear == Ear.RIGHT) s.settings.bandsFor(Ear.LEFT) else selected
            val right = if (ear == Ear.LEFT) s.settings.bandsFor(Ear.RIGHT) else selected
            left to right
        }
            .mapLatest { (l, r) ->
                withContext(curveDispatcher) {
                    maxOf(peakDb(l), peakDb(r))
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialPeakDb)

    /** [Headroom.recommendedPreampDb] for the loudest ear. */
    val recommendedPreampDb: StateFlow<Double> = worstBandPeakDb
        .map { peak -> recommendedFor(peak) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), recommendedFor(initialPeakDb))

    init {
        viewModelScope.launch {
            eqDraft.filterNotNull().debounce(EQ_DEBOUNCE_MS).collect { draft ->
                runCatching { headphones.setEq(draft) }.onFailure { messenger.report(it) }
                eqDraft.compareAndSet(draft, null)
            }
        }
        viewModelScope.launch {
            bandsDraft.filterNotNull().debounce(BANDS_DEBOUNCE_MS).collect { draft ->
                runCatching {
                    if (draft.ear == null) tuning.setBands(draft.bands) else tuning.setEarBands(draft.ear, draft.bands)
                }.onFailure { messenger.report(it) }
                bandsDraft.compareAndSet(draft, null)
            }
        }
    }

    fun setProEqEnabled(enabled: Boolean) = launchCatching { tuning.setEnabled(enabled) }
    fun setUseGlobalSession(enabled: Boolean) = launchCatching { tuning.setUseGlobalSession(enabled) }

    /**
     * Sets the preamp to exactly cancel the worst-case positive band gain of the louder ear
     * (DSP's [Headroom]). A draft still in the debounce window counts, so the button does what
     * the curve shows.
     */
    fun autoPreamp() = launchCatching {
        val s = tuning.state.value.settings
        val ear = editingEar.value
        val left = if (ear == Ear.RIGHT) s.bandsFor(Ear.LEFT) else bands.value
        val right = if (ear == Ear.LEFT) s.bandsFor(Ear.RIGHT) else bands.value
        tuning.setPreamp(minOf(Headroom.recommendedPreampDb(left), Headroom.recommendedPreampDb(right)))
    }

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
        val preset = EqPreset(
            id = "user-" + UUID.randomUUID().toString(),
            name = name.trim(),
            // The linked curve (or the edit still in the debounce window), plus each ear's own
            // when the fitting is per-ear.
            bands = if (s.perEarEnabled) s.bands else bands.value,
            preampDb = s.preampDb,
            perEarEnabled = s.perEarEnabled,
            leftBands = if (s.perEarEnabled) s.bandsFor(Ear.LEFT) else null,
            rightBands = if (s.perEarEnabled) s.bandsFor(Ear.RIGHT) else null,
        )
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

    /** Highest point of [bands] alone; [EqCurveData.bandPeakDb] is the same number for a curve. */
    private fun peakDb(bands: List<ParametricBand>): Double =
        EqResponse(bands, preampDb = 0.0).peakBandsOnlyDb()

    private fun recommendedFor(peakDb: Double): Double =
        if (peakDb <= 0.0) 0.0 else -(ceil(peakDb * 10.0) / 10.0)

    private companion object {
        const val EQ_DEBOUNCE_MS = 250L
        const val BANDS_DEBOUNCE_MS = 120L
    }
}
