package dev.earshot.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.earshot.core.AppSoundProfile
import dev.earshot.core.AudioTuningRepository
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.TuningState
import dev.earshot.dsp.AppProfileRecord
import dev.earshot.dsp.BuiltInPresets
import dev.earshot.dsp.DynamicsProcessingPlan
import dev.earshot.dsp.EqPreset
import dev.earshot.dsp.Headroom
import dev.earshot.dsp.ParametricBand
import dev.earshot.dsp.PresetCodec
import dev.earshot.dsp.TuningSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * One DataStore file for the whole sound engine; the delegate must be top-level (one per process).
 * A corrupt file is replaced with an empty one: without the handler [AudioTuningRepositoryImpl.restore]
 * starts fresh but every later edit rethrows the CorruptionException, so nothing the user changes
 * would ever be saved again.
 */
private val Context.tuningDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "audio_tuning",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * A tuning repository whose saved presets load asynchronously after it is built. Until
 * [awaitRestored] returns, [AudioTuningRepository.presets] holds only the built-ins, so a user
 * preset can't yet be told apart from a deleted one ([HeadsetEqMemory] waits for it).
 */
interface TuningRestore {
    suspend fun awaitRestored()
}

/**
 * When to forget the announced sessions after Bluetooth media went away ([AudioTuningRepositoryImpl]).
 * Players the OS kills never send CLOSE, so losing the headphones is the moment to forget them; but
 * a player that keeps playing through a short drop or a tap on the output switcher keeps its session
 * and never announces it again. So the sessions are forgotten only once the loss has lasted longer
 * than [graceMillis]: at the first check after that, or when Bluetooth comes back too late.
 * Not thread-safe; the repository calls it under its mutex.
 */
internal class BluetoothLossGrace(private val graceMillis: Long) {
    private var wasPresent = false
    private var lostAtMillis: Long? = null

    /** Feed the current state; true means "forget every session now". */
    fun shouldForgetSessions(present: Boolean, nowMillis: Long): Boolean {
        if (wasPresent && !present) lostAtMillis = nowMillis
        wasPresent = present
        val lostAt = lostAtMillis ?: return false
        if (nowMillis - lostAt <= graceMillis) {
            if (present) lostAtMillis = null // back in time: keep every session
            return false
        }
        lostAtMillis = null
        return true
    }
}

/**
 * Which announced sessions stay listed ([AudioTuningRepositoryImpl.reconcile]): [BluetoothLossGrace]
 * decides when a loss forgets them all, and [prune] drops the ones past their age limit.
 *
 * A session that has carried our effect keeps the longer [carriedMaxAgeMillis] even while that
 * effect is released. A sleep-sound or audiobook app announces once (say at 23:00) and plays all
 * night; a one-second drop or a tap on the output switcher at 05:30 releases every effect
 * (`engine.retain(emptySet())`), so "holds an effect right now" would prune it at [maxAgeMillis]
 * during the grace, and the EQ would stay off when the headset returns until playback restarts.
 * [carriedMaxAgeMillis] still bounds players that died without CLOSE; a loss longer than the grace
 * forgets the sessions and what they carried.
 *
 * Not thread-safe; the repository calls it under its mutex.
 */
internal class SessionAging(
    graceMillis: Long,
    private val maxAgeMillis: Long,
    private val carriedMaxAgeMillis: Long,
) {
    private val btLoss = BluetoothLossGrace(graceMillis)

    /** Listed sessions that have held our effect at some point, whether or not they hold it now. */
    private val carriedEffect = HashSet<Int>()

    /**
     * Before the attach pass. [elapsedMillis] is the monotonic clock the grace runs on;
     * [wallMillis] is the clock [SessionRegistry] stamps announcements with.
     */
    fun prune(btPresent: Boolean, elapsedMillis: Long, wallMillis: Long = System.currentTimeMillis()) {
        if (btLoss.shouldForgetSessions(btPresent, elapsedMillis)) {
            SessionRegistry.clear()
            carriedEffect.clear()
        }
        SessionRegistry.pruneOlderThan(
            maxAgeMillis,
            wallMillis,
            attached = carriedEffect.toSet(),
            attachedMaxAgeMillis = carriedMaxAgeMillis,
        )
    }

    /** After the attach pass: remember what holds an effect now, forget what the registry no longer lists. */
    fun noteAttached(attached: Set<Int>) {
        carriedEffect += attached
        // A closed or pruned session leaves the set, so a later session reusing its id starts on the normal limit.
        carriedEffect.retainAll(SessionRegistry.sessions.value.mapTo(HashSet()) { it.sessionId })
    }
}

/**
 * Real [AudioTuningRepository]: persists settings, user presets and per-app profiles, watches the
 * sessions media apps announce, and keeps a [DynamicsProcessingEngine] in step with all of it.
 *
 * Attachment policy (the part worth understanding before touching it):
 *  - We only attach while media is routed to a Bluetooth output. The whole point is to tune the
 *    headphones; the same EQ on the phone speaker sounds wrong, and the engine would otherwise
 *    keep running when the headphones drop out mid-call. Re-evaluated on every device change and
 *    whenever a session opens (see [refreshBluetoothOutput]).
 *  - Every announced session gets the global preset, unless its package has an [AppSoundProfile],
 *    in which case that profile's preset is used instead (boost and limiter stay global: they are
 *    safety/loudness choices, not sound signature).
 *  - Session 0 (global mix) is only attempted when [useGlobalSession] is on. See the engine for
 *    why that is opt-in.
 *  - `enabled = false` releases every effect rather than bypassing them, so `attachedSessions`
 *    honestly reads 0 and no vendor DSP keeps a dormant effect alive.
 *
 * All mutations go through [mutex] so a settings change, a session broadcast and a device
 * callback cannot interleave their engine calls. Engine and DataStore work runs on IO.
 */
class AudioTuningRepositoryImpl(
    context: Context,
    private val scope: CoroutineScope,
) : AudioTuningRepository, TuningRestore {

    private val appContext = context.applicationContext
    private val dataStore = appContext.tuningDataStore
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val engine = DynamicsProcessingEngine()

    /** Output rate only shapes the response near Nyquist, but matching it keeps the UI curve honest. */
    private val sampleRate: Int =
        audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            ?: DynamicsProcessingPlan.DEFAULT_SAMPLE_RATE

    private val _state = MutableStateFlow(TuningState())
    override val state: StateFlow<TuningState> = _state

    private val userPresets = MutableStateFlow<List<EqPreset>>(emptyList())
    private val _presets = MutableStateFlow(BuiltInPresets.all)
    override val presets: StateFlow<List<EqPreset>> = _presets

    private val _appProfiles = MutableStateFlow<List<AppSoundProfile>>(emptyList())
    override val appProfiles: StateFlow<List<AppSoundProfile>> = _appProfiles

    private val _useGlobalSession = MutableStateFlow(false)
    /** Opt-in fallback for players that never broadcast their session. Not part of the contract. */
    val useGlobalSession: StateFlow<Boolean> = _useGlobalSession

    private val bluetoothOutputPresent = MutableStateFlow(false)
    /** Decides in [reconcile] which announced sessions are forgotten (a long Bluetooth loss, age). */
    private val sessionAging = SessionAging(BT_LOSS_GRACE_MS, SESSION_MAX_AGE_MS, ATTACHED_SESSION_MAX_AGE_MS)
    private val mutex = Mutex()
    private val restored = CompletableDeferred<Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshBluetoothOutput()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshBluetoothOutput()
    }

    init {
        refreshBluetoothOutput()
        audioManager?.registerAudioDeviceCallback(deviceCallback, mainHandler)

        scope.launch {
            mutex.withLock { restore() }
            restored.complete(Unit)
            // Anything that changes *which* sessions should carry an effect funnels into one reconcile.
            // A session opening is also a cheap moment to re-check the media route: the output
            // switcher doesn't fire the device callback, so this is what notices "This phone".
            combine(SessionRegistry.sessions, bluetoothOutputPresent, _useGlobalSession) { s, bt, g -> Triple(s, bt, g) }
                .collect { refreshBluetoothOutput(); mutex.withLock { reconcile() } }
        }
        scope.coroutineContext[Job]?.invokeOnCompletion {
            audioManager?.unregisterAudioDeviceCallback(deviceCallback)
            // Effects outlive us in the audio HAL if we don't release them explicitly.
            engine.detachAll()
        }
    }

    // ---- commands ---------------------------------------------------------------------------

    override suspend fun setEnabled(enabled: Boolean) = mutate { s -> s.copy(enabled = enabled) }

    override suspend fun awaitRestored() = restored.await()

    override suspend fun applyPreset(presetId: String?) {
        // Until restore() has published the user presets only the built-ins are known, so an
        // automation or HeadsetEqMemory applying a user preset at process start would be told
        // "no longer exists". edit() waits for the restore too, but only after this lookup.
        restored.await()
        val preset = presetId?.let { id ->
            presetById(id) ?: throw HeadphoneCommandException("That preset no longer exists")
        }
        mutate { s ->
            // null means "flat / off" (the automation editor's "Flat (off)"), not "keep the current bands".
            if (preset == null) s.copy(activePresetId = null, bands = BuiltInPresets.flat.bands, preampDb = 0.0)
            else s.copy(activePresetId = preset.id, bands = preset.bands, preampDb = preset.preampDb)
        }
    }

    override suspend fun setBands(bands: List<ParametricBand>) =
        mutate { s -> s.copy(activePresetId = null, bands = bands.map(::sanitise)) }

    override suspend fun setPreamp(db: Double) =
        mutate { s -> s.copy(preampDb = db.coerceIn(MIN_PREAMP_DB, 0.0)) }

    override suspend fun setBoost(db: Double) =
        mutate { s -> s.copy(boostDb = Headroom.clampBoost(db, s.limiterEnabled)) }

    override suspend fun setLimiter(enabled: Boolean) =
        // Turning the limiter off lowers the boost ceiling; re-clamp so we never run +12 dB unprotected.
        mutate { s -> s.copy(limiterEnabled = enabled, boostDb = Headroom.clampBoost(s.boostDb, enabled)) }

    override suspend fun savePreset(preset: EqPreset) {
        if (BuiltInPresets.byId(preset.id) != null || preset.isBuiltIn) {
            throw HeadphoneCommandException("Built-in presets can't be overwritten — save it under a new name")
        }
        if (preset.id.isBlank() || preset.name.isBlank()) throw HeadphoneCommandException("A preset needs a name")
        val clean = preset.copy(bands = preset.bands.map(::sanitise), preampDb = preset.preampDb.coerceIn(MIN_PREAMP_DB, 0.0), isBuiltIn = false)
        edit {
            userPresets.update { list -> list.filterNot { it.id == clean.id } + clean }
            publishPresets()
            // Saving over the preset we are currently editing re-links the live bands to it.
            val s = _state.value.settings
            if (s.activePresetId == clean.id) {
                _state.update { it.copy(settings = s.copy(bands = clean.bands, preampDb = clean.preampDb)) }
            }
        }
    }

    override suspend fun deletePreset(presetId: String) {
        if (BuiltInPresets.byId(presetId) != null) throw HeadphoneCommandException("Built-in presets can't be deleted")
        edit {
            userPresets.update { list -> list.filterNot { it.id == presetId } }
            publishPresets()
            // Profiles pointing at it fall back to the global sound; the live edit just becomes "custom".
            _appProfiles.update { list -> list.filterNot { it.presetId == presetId } }
            if (_state.value.settings.activePresetId == presetId) {
                _state.update { it.copy(settings = it.settings.copy(activePresetId = null)) }
            }
        }
    }

    override suspend fun setAppProfile(profile: AppSoundProfile) {
        restored.await() // as in applyPreset: a user preset is unknown until restore() has run
        if (presetById(profile.presetId) == null) throw HeadphoneCommandException("That preset no longer exists")
        edit { _appProfiles.update { list -> list.filterNot { it.packageName == profile.packageName } + profile } }
    }

    override suspend fun removeAppProfile(packageName: String) =
        edit { _appProfiles.update { list -> list.filterNot { it.packageName == packageName } } }

    /** Try the deprecated global output mix (session 0) for apps that don't announce a session. */
    override suspend fun setUseGlobalSession(enabled: Boolean) = edit {
        _useGlobalSession.value = enabled
        _state.update { it.copy(useGlobalSession = enabled) }
        if (!enabled) engine.clearWarning()
    }

    /** Current curve for the UI, respecting an active per-app profile is the UI's job; this is the global one. */
    fun currentPlan(bandCount: Int = DynamicsProcessingPlan.DEFAULT_BAND_COUNT): DynamicsProcessingPlan.Plan =
        plannerFor(_state.value.settings, packageName = null).plan(bandCount)

    // ---- state plumbing --------------------------------------------------------------------

    /** Apply a settings change: update state (before returning, per the contract), persist, re-plan. */
    private suspend fun mutate(transform: (TuningSettings) -> TuningSettings) = edit {
        _state.update { st ->
            val settings = transform(st.settings)
            st.copy(settings = settings, activePreset = presetById(settings.activePresetId))
        }
    }

    /** Run [block] under the lock, then persist everything and reconcile the engine. */
    private suspend fun edit(block: () -> Unit) {
        restored.await()
        mutex.withLock {
            block()
            _state.update { it.copy(activePreset = presetById(it.settings.activePresetId)) }
            persist()
            reconcile()
        }
    }

    private fun publishPresets() {
        _presets.value = BuiltInPresets.all + userPresets.value
    }

    private fun presetById(id: String?): EqPreset? = id?.let { wanted -> _presets.value.firstOrNull { it.id == wanted } }

    private fun profileFor(packageName: String?): AppSoundProfile? =
        packageName?.let { pkg -> _appProfiles.value.firstOrNull { it.packageName == pkg } }

    private fun sanitise(b: ParametricBand) = b.copy(
        frequencyHz = b.frequencyHz.coerceIn(10.0, 22000.0),
        gainDb = b.gainDb.coerceIn(ParametricBand.MIN_GAIN_DB, ParametricBand.MAX_GAIN_DB),
        q = b.q.coerceIn(ParametricBand.MIN_Q, ParametricBand.MAX_Q),
    )

    /** What a session belonging to [packageName] should play through. */
    private fun plannerFor(settings: TuningSettings, packageName: String?): Planner {
        val profilePreset = profileFor(packageName)?.let { presetById(it.presetId) }
        val bands = profilePreset?.bands ?: settings.bands
        val limiter = settings.limiterEnabled
        // With the limiter off nothing catches peaks, so the preamp may not sit above what cancels
        // the bands' combined boost; the loudness boost is capped at +3 dB by clampBoost for the same reason.
        val preamp = Headroom.effectivePreampDb(profilePreset?.preampDb ?: settings.preampDb, bands, limiter, sampleRate.toDouble())
        val boost = Headroom.clampBoost(settings.boostDb, limiter)
        return Planner { n -> DynamicsProcessingPlan.plan(bands, preamp, boost, limiter, n, sampleRate) }
    }

    /**
     * Bring the engine in line with (settings × sessions × output device × global toggle) and
     * publish the resulting runtime status. Must be called with [mutex] held.
     */
    private suspend fun reconcile() {
        val settings = _state.value.settings
        val btPresent = bluetoothOutputPresent.value
        // Players the OS kills never send CLOSE. Losing the headphones for longer than a blip is the
        // natural moment to forget every session (live players re-announce on their next start).
        // Anything older than SESSION_MAX_AGE_MS is stale regardless, except a session that has
        // carried our effect: an audiobook or sleep-sound app announces once and may play all night,
        // and dropping its effect mid-playback changes its loudness (preamp, boost, limiter). That
        // holds through a short Bluetooth drop too, when every effect is released (SessionAging).
        // ATTACHED_SESSION_MAX_AGE_MS still bounds effects parked on sessions of players that died
        // without CLOSE. Both are no-ops on the flow when nothing changes.
        sessionAging.prune(btPresent, SystemClock.elapsedRealtime())
        val sessions = SessionRegistry.sessions.value
        val shouldRun = settings.enabled && btPresent

        val targets: Map<Int, Planner> = if (!shouldRun) emptyMap() else buildMap {
            for (s in sessions) put(s.sessionId, plannerFor(settings, s.packageName))
            if (_useGlobalSession.value) put(0, plannerFor(settings, null))
        }

        withContext(Dispatchers.IO) {
            // Also stops retrying sessions whose effect another app took over once they are gone.
            engine.retain(targets.keys)
            for ((id, planner) in targets) engine.attach(id, planner, enabled = true)
        }
        sessionAging.noteAttached(engine.attached)

        val es = engine.status.value
        val activePackage = sessions.asReversed().firstNotNullOfOrNull { s ->
            s.packageName?.takeIf { pkg -> s.sessionId in es.attachedSessions && profileFor(pkg) != null }
        }
        val warning = when {
            settings.enabled && !btPresent ->
                "No Bluetooth headphones connected — the EQ is paused so it doesn't colour the phone speaker."
            !es.isAvailable -> es.warning
            shouldRun && targets.isEmpty() ->
                "Waiting for a music app to start playing. Apps that don't announce their audio session can't be tuned."
            else -> es.warning
        }
        _state.update {
            it.copy(
                attachedSessions = es.attachedSessions.size,
                isAvailable = es.isAvailable,
                activeAppPackage = activePackage,
                warning = warning,
            )
        }
    }

    /**
     * Is *media* going to Bluetooth right now? On API 33+ we ask where USAGE_MEDIA is routed, so
     * picking "This phone" in the output switcher (headphones still connected) detaches the EQ;
     * older releases can only tell us whether a Bluetooth output exists at all.
     */
    private fun refreshBluetoothOutput() {
        val am = audioManager ?: return
        val present = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
                am.getAudioDevicesForAttributes(media).any { it.type in BLUETOOTH_OUTPUT_TYPES }
            } else {
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in BLUETOOTH_OUTPUT_TYPES }
            }
        }.getOrDefault(false)
        bluetoothOutputPresent.value = present
    }

    // ---- persistence -----------------------------------------------------------------------

    private suspend fun restore() {
        val prefs = dataStore.data
            .catch { e ->
                if (e is IOException) { Log.w(TAG, "tuning store unreadable, starting fresh", e); emit(emptyPreferences()) } else throw e
            }
            .first()
        userPresets.value = PresetCodec.decodePresetsOrNull(prefs[KEY_PRESETS])
            ?.filterNot { it.isBuiltIn || BuiltInPresets.byId(it.id) != null }
            ?: emptyList()
        publishPresets()
        _appProfiles.value = PresetCodec.decodeAppProfilesOrNull(prefs[KEY_PROFILES])
            ?.map { AppSoundProfile(it.packageName, it.appLabel, it.presetId) }
            ?: emptyList()
        _useGlobalSession.value = prefs[KEY_GLOBAL_SESSION] ?: false
        _state.update { it.copy(useGlobalSession = _useGlobalSession.value) }
        val raw = PresetCodec.decodeSettingsOrNull(prefs[KEY_SETTINGS]) ?: TuningSettings()
        val settings = raw.copy(
            boostDb = Headroom.clampBoost(raw.boostDb, raw.limiterEnabled),
            activePresetId = raw.activePresetId?.takeIf { presetById(it) != null },
            bands = raw.bands.map(::sanitise),
        )
        _state.update { it.copy(settings = settings, activePreset = presetById(settings.activePresetId)) }
        reconcile()
    }

    private suspend fun persist() {
        val settings = _state.value.settings
        val presetsJson = PresetCodec.encodePresets(userPresets.value)
        val profilesJson = PresetCodec.encodeAppProfiles(
            _appProfiles.value.map { AppProfileRecord(it.packageName, it.appLabel, it.presetId) },
        )
        val global = _useGlobalSession.value
        try {
            dataStore.edit { p ->
                p[KEY_SETTINGS] = PresetCodec.encodeSettings(settings)
                p[KEY_PRESETS] = presetsJson
                p[KEY_PROFILES] = profilesJson
                p[KEY_GLOBAL_SESSION] = global
            }
        } catch (e: IOException) {
            // State is already updated and applied; losing persistence is not worth failing the command.
            Log.w(TAG, "could not persist tuning settings", e)
        }
    }

    private companion object {
        const val TAG = "Earshot/Tuning"
        const val MIN_PREAMP_DB = -30.0
        /** Sessions older than this are assumed to belong to a player the OS has long since killed. */
        const val SESSION_MAX_AGE_MS = 6L * 60 * 60 * 1000
        /** The age limit for a session that still carries a working effect: longer than any night of playback. */
        const val ATTACHED_SESSION_MAX_AGE_MS = 24L * 60 * 60 * 1000
        /** A Bluetooth loss shorter than this (a drop, the output switcher) keeps the announced sessions. */
        const val BT_LOSS_GRACE_MS = 2L * 60 * 1000
        val KEY_SETTINGS = stringPreferencesKey("settings_json")
        val KEY_PRESETS = stringPreferencesKey("user_presets_json")
        val KEY_PROFILES = stringPreferencesKey("app_profiles_json")
        val KEY_GLOBAL_SESSION = booleanPreferencesKey("use_global_session")

        /** A2DP is the classic music profile; LE Audio headsets (Android 12+) count as headphones too. */
        val BLUETOOTH_OUTPUT_TYPES: Set<Int> = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }
}
