package dev.earshot.sleep

import android.app.NotificationManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import dev.earshot.R
import dev.earshot.bt.CompanionRemote
import dev.earshot.bt.DeviceFinder
import dev.earshot.core.AlarmOutput
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.CustomSound
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.HeadphoneState
import dev.earshot.core.NextAlarm
import dev.earshot.core.NightExtra
import dev.earshot.core.SavedMix
import dev.earshot.core.SleepAlarm
import dev.earshot.core.SleepEndAction
import dev.earshot.core.SleepHeadset
import dev.earshot.core.SleepLayer
import dev.earshot.core.SleepNote
import dev.earshot.core.SleepPermissions
import dev.earshot.core.SleepPlan
import dev.earshot.core.SleepRepository
import dev.earshot.core.SleepSession
import dev.earshot.core.SleepState
import dev.earshot.dsp.sleep.LoopMaker
import dev.earshot.extras.HeadphoneLabels
import dev.earshot.find.OutputCandidate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * The Sleep tab's runtime (docs/sleep-mode.md): one night at a time, the wake alarms and the
 * morning restore, for any headset. The brain; [SleepService] only keeps the process in the
 * foreground and [SleepAlarmReceiver] only forwards `AlarmManager` alarms here.
 *
 * Every command runs through [locked]: under [mutex], and `NonCancellable`, so a notification
 * button or the alarm screen going away mid-command can't leave a restore half done. The night is
 * written to [SleepStore] before the screen and the service hear about it, so a process killed at
 * 3 am finds it again ([recoverLocked]).
 */
class SleepRepositoryImpl(
    private val context: Context,
    private val headphones: HeadphoneRepository,
    private val finder: DeviceFinder,
    remote: CompanionRemote,
    private val scope: CoroutineScope,
) : SleepRepository {
    private val text = SleepText { id, args -> context.getString(id, *args) }
    private val store = SleepStore(context)
    private val actions = SleepHeadsetActions(headphones, CompanionHeadsetLink(remote), text)
    private val phone = PhoneNightExtras(context, text)
    private val player = SleepSoundPlayer(context, finder)
    private val alarmPlayer = AlarmPlayer(context)
    private val scheduler = SleepScheduler(context)
    private val pauser = OtherAudioPauser(context)
    private val notifications = SleepNotifications(context)

    private val mutex = Mutex()
    private val loaded = CompletableDeferred<Unit>()

    private val _state = MutableStateFlow(SleepState())
    override val state: StateFlow<SleepState> = _state.asStateFlow()
    /**
     * Just the session, for the service and the alarm screen; set together with [state] (never a
     * derived flow, which would lag: the service reads it right after a start).
     */
    private val _session = MutableStateFlow<SleepSession>(SleepSession.Idle)
    val session: StateFlow<SleepSession> = _session.asStateFlow()
    private val _plan = MutableStateFlow(SleepPlan())
    override val plan: StateFlow<SleepPlan> = _plan.asStateFlow()
    private val _alarms = MutableStateFlow<List<SleepAlarm>>(emptyList())
    override val alarms: StateFlow<List<SleepAlarm>> = _alarms.asStateFlow()
    private val _mixes = MutableStateFlow<List<SavedMix>>(emptyList())
    override val mixes: StateFlow<List<SavedMix>> = _mixes.asStateFlow()
    private val _customSounds = MutableStateFlow<List<CustomSound>>(emptyList())
    override val customSounds: StateFlow<List<CustomSound>> = _customSounds.asStateFlow()
    private val _headset = MutableStateFlow(SleepHeadset())
    override val headset: StateFlow<SleepHeadset> = _headset.asStateFlow()
    private val _permissions = MutableStateFlow(SleepPermissions())
    override val permissions: StateFlow<SleepPermissions> = _permissions.asStateFlow()

    /** Written under [mutex]; volatile for the few off-lock reads (headset watcher). */
    @Volatile private var night = NightRecord()

    /** A night is running (or being started): previews must not touch the player. */
    @Volatile private var nightRunning = false
    @Volatile private var starting = false
    /** Alarms changed in memory (a one-off switched itself off) and must be saved with the night. */
    @Volatile private var alarmsDirty = false
    /** An Earshot screen is visible (counted by [setAppVisible]): audio calls work on Android 17. */
    private val appVisible: Boolean get() = BackgroundAudio.isAppVisible

    /**
     * The Sleep service runs with while-in-use status, which Android 17 asks of a foreground
     * service's audio calls at target 37 ([BackgroundAudio]). True when [start] started it (only
     * ever called from a visible screen: the Sleep tab, or MainActivity for the launcher
     * shortcut) or while it handles a notification tap ([onServiceStartedFromTap]). False once it
     * stops, and after a start that gets no such status: a night rebuilt after Android closed
     * Earshot, a ring from an alarm broadcast. When unsure it is false: the morning volume and
     * the sounds then wait for Earshot to be opened, which is better than a call that is ignored.
     */
    @Volatile private var serviceWhileInUse = false

    /** Read by the player thread for the envelope; null when no night runs. */
    @Volatile private var timeline: SleepTimeline? = null
    @Volatile private var playVolume = 0.6f

    private var ticker: Job? = null
    private var ringWatch: Job? = null
    private var tonePreview: Job? = null

    /** The alarm ringing now. Guarded by [mutex]. */
    private var ring: Ring? = null

    /** A headset that just connected and may get a pending restore, once its settings are read. */
    @Volatile private var restoreCandidate: String? = null
    @Volatile private var wasConnected = false

    /**
     * The headset the night's end action just sent away: no restore connect for it yet
     * ([RestoreConnect.Hold]). Set under [mutex]; the headset watcher releases it.
     */
    private val restoreConnectHold = AtomicReference<RestoreConnect.Hold?>(null)

    private class Ring(
        val alarm: SleepAlarm,
        val startedElapsed: Long,
        /** Length of the quiet lead-in before the alarm time (wake-up window), 0 without one. */
        val windowMs: Long,
        /** Wall-clock alarm time (the end of the window). */
        val alarmAtMillis: Long,
        val headset: AudioDeviceInfo?,
        val speaker: AudioDeviceInfo?,
        var phoneJoined: Boolean,
        var vibrating: Boolean = false,
    )

    init {
        scope.launch {
            try {
                // A store that can't be read must not hold every command and alarm forever: start
                // from defaults instead (the store replaces a corrupt file by itself).
                val snapshot = runCatching { store.load() }
                    .onFailure { Log.w(TAG, "sleep store unreadable: ${it.javaClass.simpleName}") }
                    .getOrNull()
                if (snapshot != null) {
                    _plan.value = snapshot.plan
                    _alarms.value = snapshot.alarms
                    _mixes.value = snapshot.mixes
                    _customSounds.value = snapshot.customSounds
                    mutex.withLock { night = snapshot.night }
                }
            } finally {
                loaded.complete(Unit)
            }
            refreshPermissions()
            locked { recoverLocked() }
            rearmAlarms()
        }
        scope.launch {
            // After the load: a connection seen before it would use up its one restore attempt on nothing.
            loaded.await()
            headphones.state.map { actions.abilities(it) to it }.conflate().collect { (abilities, s) ->
                _headset.value = abilities
                watchForPendingRestore(s)
                connectForPendingRestore(s)
            }
        }
    }

    /** Runs [block] under the lock to the end, whatever happens to the caller. */
    private suspend fun <T> locked(block: suspend () -> T): T = withContext(NonCancellable) {
        loaded.await()
        mutex.withLock { block() }
    }

    // ---- Tonight's plan, mixes and permissions ------------------------------------------------

    override suspend fun updatePlan(plan: SleepPlan) {
        val clean = SleepPlanner.sanitise(plan)
        _plan.value = clean
        store.savePlan(clean)
        if (_state.value.previewing) player.update(clean.layers, clean.soundSide)
    }

    override suspend fun saveMix(name: String, layers: List<SleepLayer>) {
        val trimmed = name.trim().ifEmpty { throw HeadphoneCommandException(text.get(R.string.sleep_rt_error_mix_name)) }
        if (layers.isEmpty()) throw HeadphoneCommandException(text.get(R.string.sleep_rt_error_mix_empty))
        _mixes.update { it.filterNot { m -> m.name.equals(trimmed, ignoreCase = true) } + SavedMix(UUID.randomUUID().toString(), trimmed, layers.take(SleepPlan.MAX_LAYERS)) }
        store.saveMixes(_mixes.value)
    }

    override suspend fun deleteMix(id: String) {
        _mixes.update { list -> list.filterNot { it.id == id } }
        store.saveMixes(_mixes.value)
    }

    override suspend fun importCustomSound(uri: String, name: String): CustomSound {
        loaded.await()
        val trimmed = name.trim().take(MAX_NAME).ifEmpty { throw HeadphoneCommandException(text.get(R.string.sleep_rt_error_custom_name)) }
        val id = CustomSound.ID_PREFIX + UUID.randomUUID()
        // Copying and decoding a few minutes of audio: never on the main thread.
        val seconds = withContext(Dispatchers.IO) { BundledRecordings.get(context).importCustom(uri.toUri(), id) }
            ?: throw HeadphoneCommandException(context.resources.getQuantityString(R.plurals.sleep_rt_error_custom_file, LoopMaker.MIN_SECONDS, LoopMaker.MIN_SECONDS))
        val sound = CustomSound(id, trimmed, seconds)
        _customSounds.update { it + sound }
        store.saveCustomSounds(_customSounds.value)
        return sound
    }

    override suspend fun deleteCustomSound(id: String) {
        loaded.await()
        _customSounds.update { list -> list.filterNot { it.id == id } }
        store.saveCustomSounds(_customSounds.value)
        // Out of tonight's plan and every saved mix, then the file itself.
        val plan = _plan.value
        if (plan.layers.any { it.soundId == id }) updatePlan(plan.copy(layers = plan.layers.filterNot { it.soundId == id }))
        if (_mixes.value.any { m -> m.layers.any { it.soundId == id } }) {
            _mixes.update { list -> list.map { m -> m.copy(layers = m.layers.filterNot { it.soundId == id }) }.filter { it.layers.isNotEmpty() } }
            store.saveMixes(_mixes.value)
        }
        withContext(Dispatchers.IO) { BundledRecordings.get(context).deleteCustom(id) }
    }

    override fun refreshPermissions() {
        val before = _permissions.value.exactAlarms
        val now = SleepPermissions(
            exactAlarms = scheduler.canScheduleExact(),
            fullScreenAlarm = canShowFullScreenAlarm(),
            notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            doNotDisturb = phone.hasDndAccess(),
        )
        _permissions.value = now
        // Just allowed: the alarms armed without it were inexact; arm them again, exactly.
        if (!before && now.exactAlarms && loaded.isCompleted) scope.launch { rearmAlarms() }
    }

    private fun canShowFullScreenAlarm(): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        return Build.VERSION.SDK_INT < 34 || runCatching { nm?.canUseFullScreenIntent() == true }.getOrDefault(true)
    }

    // ---- Previews -------------------------------------------------------------------------------

    override fun preview(layers: List<SleepLayer>) {
        val session = _state.value.session
        if (starting || nightRunning || session is SleepSession.Running || session is SleepSession.Ringing) return
        if (layers.isEmpty()) return stopPreview()
        val hs = headphones.state.value
        // A preview plays wherever media goes now; only a night refuses to fall back to the speaker.
        val address = hs.connection.deviceAddress?.takeIf { hs.isAudioActive }
        player.start(layers, _plan.value.soundSide, address, requireHeadset = false, gain = { _plan.value.volume }) {
            _state.update { it.copy(previewing = false) }
        }
        _state.update { it.copy(previewing = true) }
    }

    override fun stopPreview() {
        if (!_state.value.previewing) return
        // Once a night runs, the player is the night's: the screen's "stop the preview" must not end it.
        if (!nightRunning && !starting) player.stop(fadeMs = 400)
        _state.update { it.copy(previewing = false) }
    }

    /**
     * MainActivity / AlarmActivity: started or stopped, one call each (screens are counted, so the
     * alarm screen over the main one still reads as visible). A visible app may use audio on
     * Android 17: a pending morning volume goes back, waiting sounds resume.
     */
    fun setAppVisible(visible: Boolean) {
        if (visible) BackgroundAudio.screenStarted() else BackgroundAudio.screenStopped()
        if (visible) scope.launch {
            locked {
                retryVolumeRestoreLocked()
                resumeFromVisibleLocked()
            }
        }
    }

    /** [SleepService]: it is handling one of our notifications' buttons, a user's tap. */
    fun onServiceStartedFromTap() {
        serviceWhileInUse = true
    }

    /** [SleepService] was destroyed. */
    fun onServiceStopped() {
        serviceWhileInUse = false
    }

    override fun previewAlarmTone(toneId: String?, output: AlarmOutput) {
        scope.launch {
            mutex.withLock {
                tonePreview?.cancel()
                if (ring != null) return@withLock
                if (toneId == null) {
                    alarmPlayer.stop()
                    return@withLock
                }
                val route = route(output)
                alarmPlayer.start(toneId, route.headset, route.speaker, playOnSpeaker = route.onSpeaker) { PREVIEW_GAIN }
                tonePreview = scope.launch {
                    delay(TONE_PREVIEW_MS)
                    mutex.withLock { if (ring == null) alarmPlayer.stop() }
                }
            }
        }
    }

    // ---- The night ------------------------------------------------------------------------------

    override suspend fun start(): Unit = locked {
        when (_state.value.session) {
            is SleepSession.Running -> throw HeadphoneCommandException(text.get(R.string.sleep_rt_error_running))
            is SleepSession.Ringing -> throw HeadphoneCommandException(text.get(R.string.sleep_rt_error_ringing))
            else -> Unit
        }
        starting = true
        try {
            startLocked()
        } finally {
            starting = false
        }
    }

    private suspend fun startLocked() {
        player.stop(fadeMs = 200)
        _state.update { it.copy(previewing = false) }

        val plan = SleepPlanner.sanitise(_plan.value)
        val now = System.currentTimeMillis()
        val tl = SleepPlanner.timeline(plan, now)
        val hs = headphones.state.value
        val address = hs.connection.deviceAddress
        val notes = mutableListOf(StepNote(text.get(R.string.sleep_rt_log_started, durationText(plan.durationMinutes))))

        // Last night's restore for this headset is merged. One for another headset waits for that
        // headset, but only its headset part: the phone part (DND, volume) carries into tonight's.
        var pending = night.pending
        var record = RestoreRecord(address = address)
        night.restore?.let { previous ->
            if (previous.address == null || address == null || previous.address.equals(address, ignoreCase = true)) {
                record = previous.copy(address = previous.address ?: address)
            } else {
                record = record.copy(
                    interruptionFilter = previous.interruptionFilter, ourInterruptionFilter = previous.ourInterruptionFilter,
                    musicVolume = previous.musicVolume, musicVolumeOutput = previous.musicVolumeOutput,
                )
                val headsetOnly = previous.copy(interruptionFilter = null, ourInterruptionFilter = null, musicVolume = null, musicVolumeOutput = null)
                if (headsetOnly.headsetPart) pending = pending.filterNot { it.address.equals(previous.address, ignoreCase = true) } + headsetOnly
            }
        }
        record = actions.applyNightExtras(plan, hs, record, notes)
        record = phone.apply(plan, record, notes)
        // Tonight's restore gets its own morning connect (RestoreConnect), whatever last night's had.
        record = record.copy(restoreAtMillis = morningAt(now, tl), due = false, connectTried = false)

        var active = ActiveNight(
            plan = plan,
            startedAtMillis = tl.startedAtMillis, endsAtMillis = tl.endsAtMillis,
            fadeOutStartsAtMillis = tl.fadeOutStartsAtMillis, noiseOffAtMillis = tl.noiseOffAtMillis,
            headsetAddress = address, headsetName = HeadphoneLabels.displayName(hs),
            // Only a headset Android can route audio to: a demo headset plays on the phone instead.
            soundOnHeadset = address != null && hs.isAudioActive && player.hasHeadsetOutput(address),
        )
        if (tl.noiseOffAtMillis != null && tl.noiseOffAtMillis <= now) {
            record = actions.noiseOff(hs, record, notes)
            active = active.copy(noiseOffDone = true)
        }
        earbudAlarmWarning(plan, now)?.let { notes += it }

        timeline = tl
        playVolume = plan.volume
        nightRunning = true
        night = night.copy(active = active, restore = record, pending = pending, log = notes.toRecords())
        save()
        // Published before the service starts: it reads the session at once and stops itself on Idle.
        publishSession()
        // start() runs only for a visible screen, so Android gives the service while-in-use
        // status: the sounds, the fade and the morning volume may then run with Earshot closed.
        val serviceStarted = SleepService.start(context)
        serviceWhileInUse = serviceStarted
        if (!serviceStarted) {
            night = night.copy(log = night.log + note(text.get(R.string.sleep_rt_log_service_refused), problem = true))
            save()
        }
        _state.update { it.copy(previewing = false) }
        if (plan.layers.isNotEmpty()) startPlayer(active)
        if (NightExtra.VOLUME_CAP in plan.nightExtras) phone.holdCap(plan.volumeCapPercent)
        armNight(active)
        startTicker()
        publishLog()
        Log.i(TAG, "night started: ${plan.durationMinutes} min, ${plan.layers.size} sounds, end ${plan.endAction}")
    }

    override suspend fun extend(minutes: Int): Unit = locked {
        val active = night.active ?: return@locked
        val tl = SleepPlanner.extended(active.timeline, active.plan, minutes)
        val updated = active.withTimeline(tl)
        night = night.copy(active = updated, log = night.log + note(text.get(R.string.sleep_rt_log_extended, minutes)))
        timeline = tl
        // A sound that already faded out comes back for the extra time; one that waits for a tap
        // comes back now (extend is the tab or a notification button: both may play).
        if (updated.soundsWaitForTap) resumeSoundsLocked()
        else if (updated.plan.layers.isNotEmpty() && !player.isPlaying && !updated.soundsStopped) startPlayer(updated)
        armNight(updated)
        save()
        publishSession()
        publishLog()
    }

    override suspend fun stop(): Unit = locked {
        if (night.active == null) return@locked
        player.stop(fadeMs = 1_500)
        closeNightLocked(listOf(StepNote(text.get(R.string.sleep_rt_log_stopped))))
        // Stopping the night by hand means you're up: put everything back now.
        morningRestoreLocked()
        save()
        publishSession()
        publishLog()
    }

    /** Called by [SleepAlarmReceiver]. */
    suspend fun onStep(step: SleepStep): Unit = locked {
        val now = System.currentTimeMillis()
        when (step) {
            SleepStep.NOISE_OFF -> {
                val active = night.active ?: return@locked
                if (active.noiseOffDone) return@locked
                val notes = mutableListOf<StepNote>()
                val record = actions.noiseOff(headphones.state.value, night.restore ?: RestoreRecord(), notes)
                night = night.copy(active = active.copy(noiseOffDone = true), restore = record, log = night.log + notes.toRecords())
            }
            SleepStep.END -> {
                val active = night.active ?: return@locked
                // A stale alarm from before "+15 min": wait for the new one.
                if (now < active.endsAtMillis - EARLY_TOLERANCE_MS) {
                    scheduler.armStep(SleepStep.END, active.endsAtMillis)
                    return@locked
                }
                endNightLocked(active)
            }
            SleepStep.RESTORE -> {
                // During a ring: the dismissal restores; if it is snoozed and then deleted instead,
                // this comes back in half an hour.
                if (ring != null) {
                    scheduler.armStep(SleepStep.RESTORE, now + RESTORE_AFTER_ALARM_MS)
                    return@locked
                }
                // The morning time came during a night that runs longer: restore when it ends.
                val active = night.active
                if (active != null) {
                    night = night.copy(restore = night.restore?.copy(restoreAtMillis = active.endsAtMillis + RESTORE_AFTER_END_MS))
                    scheduler.armStep(SleepStep.RESTORE, active.endsAtMillis + RESTORE_AFTER_END_MS)
                } else {
                    morningRestoreLocked()
                }
            }
            SleepStep.RECONNECT -> {
                val address = night.active?.headsetAddress ?: night.restore?.address ?: return@locked
                Log.i(TAG, "reconnect before the alarm: ${actions.reconnect(address)}")
            }
        }
        save()
        publishSession()
        publishLog()
    }

    /** The timer ran out: last noise step, sounds stopped, other audio paused, the end action. */
    private suspend fun endNightLocked(active: ActiveNight) {
        val now = System.currentTimeMillis()
        val hs = headphones.state.value
        val notes = mutableListOf<StepNote>()
        var record = night.restore ?: RestoreRecord(address = active.headsetAddress)
        if (!active.noiseOffDone && active.noiseOffAtMillis != null) record = actions.noiseOff(hs, record, notes)
        player.stop(fadeMs = 2_000)
        val onTime = now - active.endsAtMillis <= LATE_END_MS
        // Hours late (Earshot was closed through the end), a pause would hit the morning's music.
        if (active.plan.pauseOtherAudio && onTime) {
            val quiet = pauser.pauseOthers()
            notes += StepNote(text.get(R.string.sleep_rt_log_paused_other))
            // Back up now only when nothing plays on and the night cap isn't holding it down.
            if (quiet && NightExtra.VOLUME_CAP !in active.plan.nightExtras && phone.restoreMusicVolume(record, canTouchVolume())) {
                record = record.copy(musicVolume = null, musicVolumeOutput = null)
            }
        }
        // Earshot was closed through the end (no exact alarm, or killed): don't switch a headset
        // off hours late while someone may be listening again.
        if (onTime) {
            val sentAway = actions.endAction(active.plan.endAction, hs, notes)
            // The restore connect must not reverse the end action that just ran. The morning
            // restore below runs at once when the morning time passed during the night, and the
            // audio link still reads as up for a moment after a disconnect: without the hold it
            // would connect the headset straight back. Its one connect waits instead (RestoreConnect).
            val sentAddress = hs.connection.deviceAddress ?: active.headsetAddress
            if (sentAway && sentAddress != null) restoreConnectHold.set(RestoreConnect.Hold(sentAddress, now + RestoreConnect.HOLD_MS))
            if (active.plan.endAction == SleepEndAction.DISCONNECT) armReconnectBeforeEarbudAlarm(now)
        } else {
            notes += StepNote(text.get(R.string.sleep_rt_log_end_skipped), isProblem = true)
        }
        night = night.copy(restore = record)
        closeNightLocked(notes + StepNote(text.get(R.string.sleep_rt_log_ended)))
        // The morning time already passed (the alarm was switched off mid-night): restore now.
        if (record.restoreAtMillis in 1..now) morningRestoreLocked()
    }

    /** Clears the night; night extras stay until the morning restore. Callers save, then publish. */
    private fun closeNightLocked(notes: List<StepNote>) {
        nightRunning = false
        ticker?.cancel()
        ticker = null
        phone.releaseCap()
        scheduler.cancelNightSteps()
        notifications.cancelResume()
        timeline = null
        night = night.copy(active = null, log = night.log + notes.toRecords())
    }

    /** Puts back what the night changed: the phone part now, the headset part once it is there. */
    private suspend fun morningRestoreLocked() {
        val record = night.restore ?: return
        val notes = mutableListOf<StepNote>()
        val hs = headphones.state.value
        // Put back on the connected headset: what a refused command leaves gets no connect of ours.
        val ranOnHeadset = record.headsetPart && actions.readyToRestore(record, hs)
        var r = actions.restore(record, hs, notes)
        r = phone.restore(r, notes, canTouchVolume())
        if (ranOnHeadset) r = r.copy(connectTried = true)
        night = night.copy(restore = if (r.isEmpty) null else r.copy(due = true), log = night.log + notes.toRecords())
        if (r.isEmpty) scheduler.cancelStep(SleepStep.RESTORE)
        connectForRestoreIfLinked()
    }

    /**
     * The morning came (the alarm stopped, the restore time, a restart) and the headset part still
     * waits for Earshot's control link: connect once if that headset is already linked for audio
     * ([RestoreConnect]). One that links later is caught by [connectForPendingRestore].
     */
    private fun connectForRestoreIfLinked() {
        val address = night.restore?.takeIf { it.due }?.address ?: return
        if (RestoreConnect.waitingFor(address, night) == null) return
        if (headphones.state.value.connection.status != ConnectionStatus.DISCONNECTED) return
        // Straight after an end action that sent the headset away: the watcher connects once it links again.
        if (RestoreConnect.isHeld(restoreConnectHold.get(), address, System.currentTimeMillis())) return
        scope.launch {
            // Off the lock: the profile proxies can take a moment to answer.
            if (runCatching { finder.isAudioConnected(address) }.getOrNull() != true) return@launch
            locked { connectForRestoreLocked(address) }
        }
    }

    /**
     * The headset of a waiting restore is linked for audio while Earshot's control link is down
     * (auto-connect off, or a process that already used its one app-start connect): connect once
     * for it ([RestoreConnect]). The restore itself then runs from [watchForPendingRestore].
     */
    private fun connectForPendingRestore(s: HeadphoneState) {
        // The headset an end action sent away is seen gone (or the hold ran out): its connect is free again.
        restoreConnectHold.get()?.let { hold ->
            if (RestoreConnect.release(hold, s, System.currentTimeMillis()) == null) restoreConnectHold.compareAndSet(hold, null)
        }
        if (!RestoreConnect.shouldConnect(s, night)) return
        val address = s.connection.deviceAddress ?: return
        if (RestoreConnect.isHeld(restoreConnectHold.get(), address, System.currentTimeMillis())) return
        scope.launch {
            locked {
                if (!RestoreConnect.shouldConnect(headphones.state.value, night)) return@locked
                connectForRestoreLocked(address)
            }
        }
    }

    /** The one connect a restore gets: marked and saved first, so neither a retry nor a restart repeats it. */
    private suspend fun connectForRestoreLocked(address: String) {
        if (RestoreConnect.waitingFor(address, night) == null) return
        if (headphones.state.value.connection.status != ConnectionStatus.DISCONNECTED) return
        // Never right after the end action sent this headset away; not marked, so it still gets its
        // connect once it links again.
        if (RestoreConnect.isHeld(restoreConnectHold.get(), address, System.currentTimeMillis())) return
        night = RestoreConnect.markTried(night, address)
        save()
        Log.i(TAG, "connecting $address for its morning restore")
        headphones.connect()
    }

    /**
     * Android 17 ignores volume, focus and playback calls from an app that is neither visible nor
     * running a foreground service (silently: the call "works"); see [BackgroundAudio].
     *  - Below target 37 any of Earshot's foreground services counts: the Sleep service while a
     *    night or an alarm runs or a button tap is handled, the headset service while a headset is
     *    connected.
     *  - From target 37 the service also needs while-in-use status. Only the Sleep service can
     *    vouch for its own ([serviceWhileInUse]); the headset service usually starts in the
     *    background (a Bluetooth connect, companion presence), so it no longer counts. The alarm's
     *    Stop button is a notification tap, so the restore after it may run.
     * False keeps the restore pending ([PhoneNightExtras.restoreMusicVolume]) until Earshot is
     * opened (below target 37 also when the headset connects again: from target 37 the retry on a
     * reconnect asks this again, and the headset service has no while-in-use status).
     */
    private fun canTouchVolume(): Boolean = BackgroundAudio.mayUseAudio(
        sdk = Build.VERSION.SDK_INT,
        targetSdk = context.applicationInfo.targetSdkVersion,
        appVisible = appVisible,
        foregroundService = nightRunning || ring != null || serviceWhileInUse || headphones.state.value.isAudioActive,
        whileInUseService = serviceWhileInUse,
    )

    /**
     * The sounds of a night rebuilt in the background ([ActiveNight.soundsWaitForTap]) play again
     * once Android allows it: the "Tap to resume sounds" notification, a notification button, or
     * an Earshot screen. Without that permission the notification stays up.
     */
    suspend fun resumeSounds(): Unit = locked { resumeSoundsLocked() }

    private suspend fun resumeSoundsLocked() {
        val active = night.active
        if (active == null || !active.soundsWaitForTap) {
            notifications.cancelResume()
            return
        }
        if (!canTouchVolume()) return
        val resumed = active.copy(soundsWaitForTap = false)
        night = night.copy(active = resumed, log = night.log + note(text.get(R.string.a17_sleep_log_resumed)))
        notifications.cancelResume()
        if (resumed.plan.layers.isNotEmpty() && !resumed.soundsStopped) startPlayer(resumed)
        save()
        publishSession()
        publishLog()
    }

    /**
     * An Earshot screen started while the sounds wait: start the service again from here, so it
     * gets while-in-use status and the sounds keep playing once Earshot is closed again.
     */
    private suspend fun resumeFromVisibleLocked() {
        if (night.active?.soundsWaitForTap != true || !appVisible) return
        if (SleepService.start(context)) serviceWhileInUse = true
        resumeSoundsLocked()
    }

    /** A morning volume that couldn't be put back yet (another output, or no foreground): try again. */
    private suspend fun retryVolumeRestoreLocked() {
        val r = night.restore ?: return
        if (night.active != null || r.musicVolume == null || !r.due) return
        if (phone.restoreMusicVolume(r, canTouchVolume())) {
            val left = r.copy(musicVolume = null, musicVolumeOutput = null)
            night = night.copy(restore = if (left.isEmpty) null else left)
            save()
        }
    }

    /**
     * A headset whose restore is still pending connects again: restore once its settings are read.
     * Once per connection, so a refused command isn't retried on every state change.
     */
    private fun watchForPendingRestore(s: HeadphoneState) {
        val address = s.connection.deviceAddress
        val connected = s.isConnected && address != null
        if (connected && !wasConnected) restoreCandidate = address
        wasConnected = connected
        val candidate = restoreCandidate ?: return
        if (!connected || !candidate.equals(address, ignoreCase = true)) return
        val waiting = listOfNotNull(night.restore?.takeIf { it.due }) + night.pending
        val match = waiting.firstOrNull { it.address.equals(candidate, ignoreCase = true) } ?: run { restoreCandidate = null; return }
        if (!actions.readyToRestore(match, s)) return
        restoreCandidate = null
        scope.launch {
            locked {
                if (night.active != null) return@locked
                val notes = mutableListOf<StepNote>()
                val current = headphones.state.value
                val tonight = night.restore
                // A restore that ran here has had its connect (RestoreConnect): a refused command
                // left over waits for the next connect, never one of the Sleep runtime's own.
                suspend fun restoreHere(record: RestoreRecord): RestoreRecord {
                    val ran = actions.readyToRestore(record, current)
                    return actions.restore(record, current, notes).let { if (ran) it.copy(connectTried = true) else it }
                }
                if (tonight != null && tonight.due && tonight.address.equals(candidate, ignoreCase = true)) {
                    val r = restoreHere(tonight)
                    night = night.copy(restore = if (r.isEmpty) null else r)
                }
                // The headset is back: its media volume can go back on its own output now.
                retryVolumeRestoreLocked()
                night = night.copy(pending = night.pending.mapNotNull { p ->
                    if (!p.address.equals(candidate, ignoreCase = true)) p
                    else restoreHere(p).takeIf { it.headsetPart }
                })
                night = night.copy(log = night.log + notes.toRecords())
                save()
                publishLog()
            }
        }
    }

    /** After a process restart: pick the night (and a ringing alarm) up where it was, or finish it. */
    private suspend fun recoverLocked() {
        val now = System.currentTimeMillis()
        night.active?.let { active ->
            if (now >= active.endsAtMillis) {
                endNightLocked(active)
            } else {
                timeline = active.timeline
                playVolume = active.plan.volume
                nightRunning = true
                armNight(active)
                publishSession()
                // Rebuilt after Android closed Earshot: the process started from an alarm
                // broadcast or a Bluetooth event, in the background, so this start gets no
                // while-in-use status unless an Earshot screen is up. serviceWhileInUse is not
                // cleared here: in this fresh process only a tap can have set it already (the
                // "Tap to resume sounds" notification of the process before started this one).
                val inForeground = SleepService.start(context)
                if (inForeground && appVisible) serviceWhileInUse = true
                // A "Tap to resume sounds" posted by the process before outlives it. Only the wait
                // branch below needs it, and shows it again; in every other case it would say the
                // sounds are paused while they play (or are gone), and nothing else takes it down:
                // resumeFromVisibleLocked returns early once nothing waits.
                notifications.cancelResume()
                if (active.plan.layers.isNotEmpty() && !active.soundsStopped) {
                    when {
                        !inForeground -> night = night.copy(
                            active = active.copy(soundsStopped = true, soundsWaitForTap = false),
                            log = night.log + note(text.get(R.string.sleep_rt_log_killed), problem = true),
                        )
                        // Android 17 at target 37 silences playback from this service: rather
                        // than play into silence, ask for a tap, which Android accepts.
                        !canTouchVolume() -> {
                            night = night.copy(
                                active = active.copy(soundsWaitForTap = true),
                                log = night.log + note(text.get(R.string.a17_sleep_log_resume_waits), problem = true),
                            )
                            notifications.showResume()
                            Log.i(TAG, "night rebuilt in the background: sounds wait for a tap")
                        }
                        else -> {
                            val playing = active.copy(soundsWaitForTap = false)
                            night = night.copy(active = playing)
                            startPlayer(playing)
                        }
                    }
                }
                startTicker()
            }
        }
        night.ringing?.let { r ->
            val alarm = _alarms.value.firstOrNull { it.id == r.alarmId }
            when {
                ring?.alarm?.id == r.alarmId -> Unit // an alarm broadcast got in first and rings it already
                alarm != null && now < r.alarmAtMillis + RING_LIMIT_MS -> beginRingingLocked(alarm, windowMs = r.alarmAtMillis - r.startedAtMillis, startedAtMillis = r.startedAtMillis)
                else -> night = night.copy(ringing = null)
            }
        }
        night.snooze?.let { snooze ->
            if (now > snooze.ringsAtMillis + STALE_SNOOZE_MS) night = night.copy(snooze = null)
            else scheduler.armAlarm(SleepAlarmReceiver.KIND_SNOOZE, snooze.alarmId, maxOf(snooze.ringsAtMillis, now + 1_000))
        }
        // A morning restore whose alarm was lost with the process (reboot, force-stop).
        night.restore?.let { r ->
            if (night.active == null && ring == null) {
                if (r.due || r.restoreAtMillis in 1..now) morningRestoreLocked()
                else if (r.restoreAtMillis > now) scheduler.armStep(SleepStep.RESTORE, r.restoreAtMillis)
            }
        }
        save()
        publishSession()
        publishLog()
    }

    private fun startPlayer(active: ActiveNight) {
        player.start(active.plan.layers, active.plan.soundSide, active.headsetAddress, requireHeadset = active.soundOnHeadset, gain = {
            val t = timeline
            if (t == null) 0f else playVolume * SleepPlanner.envelope(t, System.currentTimeMillis())
        }) { reason -> scope.launch { onPlayerStopped(reason) } }
    }

    private suspend fun onPlayerStopped(reason: PlayerStop): Unit = locked {
        val active = night.active ?: return@locked
        if (active.soundsStopped) return@locked
        val message = when (reason) {
            PlayerStop.HEADSET_GONE -> R.string.sleep_rt_log_headset_gone
            PlayerStop.OUTPUT_FAILED -> R.string.sleep_rt_log_output_failed
        }
        night = night.copy(active = active.copy(soundsStopped = true), log = night.log + note(text.get(message), problem = true))
        save()
        publishSession()
        publishLog()
    }

    private fun armNight(active: ActiveNight) {
        val now = System.currentTimeMillis()
        if (active.noiseOffAtMillis != null && !active.noiseOffDone && active.noiseOffAtMillis > now) {
            scheduler.armStep(SleepStep.NOISE_OFF, active.noiseOffAtMillis)
        }
        scheduler.armStep(SleepStep.END, active.endsAtMillis)
        night.restore?.restoreAtMillis?.takeIf { it > now }?.let { scheduler.armStep(SleepStep.RESTORE, it) }
    }

    /** Phase updates for the screen and the notification, and the other app's fade. Not a timer: the alarms are. */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            var lastFadeStep = 0L
            while (isActive) {
                val tl = timeline ?: break
                val now = System.currentTimeMillis()
                mutex.withLock {
                    val active = night.active ?: return@withLock
                    publishSession()
                    if (active.plan.pauseOtherAudio && now >= tl.fadeOutStartsAtMillis && now - lastFadeStep >= FADE_STEP_MS) {
                        // The volume the fade starts from is saved first, so the morning can put it back.
                        var record = night.restore ?: RestoreRecord(address = active.headsetAddress)
                        if (record.musicVolume == null) {
                            pauser.currentVolume()?.let { v ->
                                record = record.copy(musicVolume = v, musicVolumeOutput = phone.mediaOutputKey())
                                night = night.copy(restore = record)
                                save()
                            }
                        }
                        val length = (tl.endsAtMillis - tl.fadeOutStartsAtMillis).coerceAtLeast(1)
                        record.musicVolume?.let { start -> pauser.fadeStep((now - tl.fadeOutStartsAtMillis).toFloat() / length, start) }
                        lastFadeStep = now
                    }
                }
                delay(TICK_MS)
            }
        }
    }

    /** Publishes what the screen should show: a ringing alarm, the night, a snooze, or nothing. */
    private fun publishSession() {
        if (ring != null) return // the ring publishes itself
        val now = System.currentTimeMillis()
        val session: SleepSession = night.active?.let { active ->
            val tl = active.timeline
            SleepSession.Running(
                plan = active.plan,
                startedAtMillis = tl.startedAtMillis,
                endsAtMillis = tl.endsAtMillis,
                phase = SleepPlanner.phase(tl, active.plan.layers.isNotEmpty() && !active.soundsStopped && !active.soundsWaitForTap, now),
                noiseOffAtMillis = tl.noiseOffAtMillis,
                noiseOffDone = active.noiseOffDone,
                headsetName = active.headsetName,
            )
        } ?: night.snooze?.let { snooze ->
            SleepSession.Snoozed(snooze.alarmId, _alarms.value.firstOrNull { it.id == snooze.alarmId }?.label, snooze.ringsAtMillis)
        } ?: SleepSession.Idle
        setSession(session)
    }

    private fun setSession(session: SleepSession) {
        _state.update { if (it.session == session) it else it.copy(session = session) }
        _session.value = session
    }

    private fun publishLog() {
        val notes = night.log.takeLast(MAX_LOG).map { SleepNote(it.atMillis, it.text, it.isProblem) }
        _state.update { it.copy(log = notes) }
    }

    /** Never throws: a failed write is logged, the night carries on in memory. */
    private suspend fun save() {
        night = night.copy(log = night.log.takeLast(MAX_LOG))
        runCatching {
            if (alarmsDirty) {
                store.saveAlarms(_alarms.value)
                alarmsDirty = false
            }
            store.saveNight(night)
        }.onFailure { Log.w(TAG, "sleep store write failed: ${it.javaClass.simpleName}") }
    }

    /** When the night's settings come back if no alarm does it first. */
    private fun morningAt(now: Long, tl: SleepTimeline): Long {
        val next = SleepPlanner.nextAlarm(_alarms.value, now)?.second?.takeIf { it - now <= ALARM_HORIZON_MS }
        return maxOf(next?.plus(RESTORE_AFTER_ALARM_MS) ?: (now + DEFAULT_NIGHT_MS), tl.endsAtMillis + RESTORE_AFTER_END_MS)
    }

    /** "The alarm will ring on the phone": the headset is switched off before an earbud alarm. */
    private fun earbudAlarmWarning(plan: SleepPlan, now: Long): StepNote? {
        if (plan.endAction != SleepEndAction.POWER_OFF) return null
        val (alarm, at) = SleepPlanner.nextAlarm(ringingAlarms(), now) ?: return null
        if (alarm.output == AlarmOutput.PHONE || at - now > ALARM_HORIZON_MS) return null
        return StepNote(text.get(R.string.sleep_rt_log_alarm_on_phone_power))
    }

    /** The alarms that will ring: none while they wait for exact-alarm access ([rearmAlarms]). */
    private fun ringingAlarms(): List<SleepAlarm> = if (scheduler.canScheduleExact()) _alarms.value else emptyList()

    private fun armReconnectBeforeEarbudAlarm(now: Long) {
        val (alarm, at) = SleepPlanner.nextAlarm(ringingAlarms(), now) ?: return
        if (alarm.output == AlarmOutput.PHONE || at - now > ALARM_HORIZON_MS) return
        val lead = maxOf(at - alarm.wakeWindowMinutes * SleepPlanner.MINUTE - RECONNECT_LEAD_MS, now + 1_000)
        scheduler.armStep(SleepStep.RECONNECT, lead)
    }

    // ---- Alarms ---------------------------------------------------------------------------------

    override suspend fun saveAlarm(alarm: SleepAlarm) {
        if (alarm.hour !in 0..23 || alarm.minute !in 0..59) throw HeadphoneCommandException(text.get(R.string.sleep_rt_error_alarm_time))
        // Without "Alarms & reminders" the alarm could only be armed inexactly, and might not
        // sound at all (SleepScheduler.mayEnable): refuse to switch it on, and let the tab's
        // banner, refreshed here, offer the setting.
        if (!SleepScheduler.mayEnable(alarm, scheduler.canScheduleExact())) {
            refreshPermissions()
            throw HeadphoneCommandException(text.get(R.string.sleep_rt_error_exact_alarms))
        }
        locked {
            _alarms.update { list -> if (list.any { it.id == alarm.id }) list.map { if (it.id == alarm.id) alarm else it } else list + alarm }
            // A changed alarm starts afresh: no skip left over from an early stop.
            night = night.copy(skipRingUntil = night.skipRingUntil - alarm.id)
            store.saveAlarms(_alarms.value)
            save()
        }
        rearmAlarms()
    }

    override suspend fun deleteAlarm(id: String) {
        locked {
            _alarms.update { list -> list.filterNot { it.id == id } }
            store.saveAlarms(_alarms.value)
            scheduler.cancelAlarm(SleepAlarmReceiver.KIND_RING, id)
            scheduler.cancelAlarm(SleepAlarmReceiver.KIND_WAKE_WINDOW, id)
            scheduler.cancelAlarm(SleepAlarmReceiver.KIND_SNOOZE, id)
            if (night.snooze?.alarmId == id) night = night.copy(snooze = null)
            night = night.copy(skipRingUntil = night.skipRingUntil - id)
            save()
            publishSession()
        }
        rearmAlarms()
    }

    /**
     * Arms every enabled alarm's next ring (and its wake-up window), publishes the soonest and
     * copies them where a locked phone can read them ([LockedAlarms]). Arms none while exact
     * alarms aren't allowed: see [SleepPlanner.ringsToArm].
     */
    suspend fun rearmAlarms(): Unit = locked {
        val now = System.currentTimeMillis()
        // Alarms a settings restore replaced (Back up and restore): their AlarmManager entries
        // outlive the restart and the restored list no longer names them, so nothing else would
        // ever cancel them. Empty on every other start.
        val replacedByRestore = dev.earshot.backup.BackupStaging.takeAlarmIdsToCancel(context)
            .filter { id -> _alarms.value.none { it.id == id } }
        for (id in replacedByRestore) {
            scheduler.cancelAlarm(SleepAlarmReceiver.KIND_RING, id)
            scheduler.cancelAlarm(SleepAlarmReceiver.KIND_WAKE_WINDOW, id)
            scheduler.cancelAlarm(SleepAlarmReceiver.KIND_SNOOZE, id)
        }
        night.snooze?.let { s -> if (s.alarmId in replacedByRestore) night = night.copy(snooze = null) }
        // One-off alarms that rang while the phone was locked after a restart are done.
        val rangLocked = LockedAlarms.consumeRang(context)
        if (rangLocked.isNotEmpty()) {
            _alarms.update { list -> list.map { a -> if (a.days.isEmpty() && a.id in rangLocked) a.copy(enabled = false) else a } }
            night = night.copy(skipRingUntil = night.skipRingUntil + rangLocked.mapValues { it.value + SKIP_MARGIN_MS })
            store.saveAlarms(_alarms.value)
        }
        // A snooze taken on the locked phone becomes an ordinary snooze now that the app can run.
        LockedAlarms.consumeSnooze(context)?.let { (alarmId, at) ->
            if (at > now - STALE_SNOOZE_MS && _alarms.value.any { it.id == alarmId }) {
                night = night.copy(snooze = SnoozeRecord(alarmId, at))
                scheduler.armAlarm(SleepAlarmReceiver.KIND_SNOOZE, alarmId, maxOf(at, now + 1_000))
            }
        }
        night = night.copy(skipRingUntil = night.skipRingUntil.filterValues { it > now })
        // Without "Alarms & reminders" no wake alarm is armed (SleepPlanner.ringsToArm, audit
        // X02/X04): restored or older alarms stay switched on but wait, with no next alarm shown
        // and the tab's banner up (published here too: the permission broadcast reaches this
        // without refreshPermissions). Allowing the access re-arms them exactly: the broadcast
        // calls this, and so does refreshPermissions when the tab comes back.
        val exact = scheduler.canScheduleExact()
        _permissions.update { if (it.exactAlarms == exact) it else it.copy(exactAlarms = exact) }
        val rings = SleepPlanner.ringsToArm(_alarms.value, now, night.skipRingUntil, exactAllowed = exact)
        if (!exact && _alarms.value.any { it.enabled }) Log.w(TAG, "exact alarms not allowed: the wake alarms wait unarmed")
        // Every alarm's entries go first, also those of an alarm armed inexactly before (by an
        // older build, or before the access was taken away), which must not ring now.
        for (alarm in _alarms.value) {
            scheduler.cancelAlarm(SleepAlarmReceiver.KIND_RING, alarm.id)
            scheduler.cancelAlarm(SleepAlarmReceiver.KIND_WAKE_WINDOW, alarm.id)
        }
        val mirror = ArrayList<MirroredRing>()
        for (ring in rings) {
            val alarm = ring.alarm
            scheduler.armAlarm(SleepAlarmReceiver.KIND_RING, alarm.id, ring.atMillis)
            ring.windowStartMillis?.let { scheduler.armAlarm(SleepAlarmReceiver.KIND_WAKE_WINDOW, alarm.id, it) }
            mirror += MirroredRing(alarm.id, ring.atMillis, alarm.toneId, alarm.fadeInSeconds, alarm.snoozeMinutes, alarm.vibrate, alarm.label)
        }
        val soonest = rings.minByOrNull { it.atMillis }?.let { NextAlarm(it.alarm.id, it.atMillis, it.windowStartMillis) }
        night.snooze?.let { s ->
            _alarms.value.firstOrNull { it.id == s.alarmId }?.let { a ->
                mirror.removeAll { it.alarmId == a.id && it.atMillis > s.ringsAtMillis }
                mirror += MirroredRing(a.id, s.ringsAtMillis, a.toneId, a.fadeInSeconds, a.snoozeMinutes, a.vibrate, a.label)
            }
        }
        LockedAlarms.mirror(context, mirror)
        save()
        _state.update { it.copy(nextAlarm = soonest) }
        publishSession()
    }

    /** Called by [SleepAlarmReceiver] for a ring, a wake-up window or the end of a snooze. */
    suspend fun onAlarm(kind: String, alarmId: String) {
        locked {
            val alarm = _alarms.value.firstOrNull { it.id == alarmId }
            if (alarm == null) {
                // Deleted meanwhile: make sure nothing still says it is snoozed.
                if (night.snooze?.alarmId == alarmId) {
                    night = night.copy(snooze = null)
                    save()
                    publishSession()
                }
                return@locked
            }
            val now = System.currentTimeMillis()
            val skipped = (night.skipRingUntil[alarmId] ?: 0L) > now
            val current = ring
            when (kind) {
                SleepAlarmReceiver.KIND_WAKE_WINDOW -> if (current == null && !skipped) {
                    beginRingingLocked(alarm, windowMs = alarm.wakeWindowMinutes * SleepPlanner.MINUTE)
                }
                SleepAlarmReceiver.KIND_RING -> {
                    // Already ringing quietly from the wake-up window: the curve carries on to full volume.
                    if (current?.alarm?.id != alarmId && !skipped) beginRingingLocked(alarm, windowMs = 0)
                    if (alarm.days.isEmpty()) {
                        _alarms.update { list -> list.map { if (it.id == alarmId) it.copy(enabled = false) else it } }
                        store.saveAlarms(_alarms.value)
                    }
                }
                SleepAlarmReceiver.KIND_SNOOZE -> {
                    night = night.copy(snooze = null)
                    if (current == null) beginRingingLocked(alarm, windowMs = 0)
                }
            }
            save()
        }
        rearmAlarms()
    }

    /**
     * Starts ringing [alarm]: [windowMs] of quiet lead-in first. [startedAtMillis] resumes a ring
     * that began before a process restart, so its fade, phone fallback and time limit carry on.
     */
    private suspend fun beginRingingLocked(alarm: SleepAlarm, windowMs: Long, startedAtMillis: Long? = null) {
        tonePreview?.cancel()
        val now = System.currentTimeMillis()
        val began = startedAtMillis ?: now
        val route = route(alarm.output)
        // Assigned first: closing a night below must not publish Idle and stop the service in between.
        val r = Ring(
            alarm, SystemClock.elapsedRealtime() - (now - began), windowMs.coerceAtLeast(0), began + windowMs,
            route.headset, route.speaker, phoneJoined = route.onSpeaker && alarm.output == AlarmOutput.EARBUDS,
        )
        ring = r
        // An alarm ends a night still running (a 6 h timer past the wake time): no end action.
        if (night.active != null) {
            player.stop(fadeMs = 1_000)
            closeNightLocked(listOf(StepNote(text.get(R.string.sleep_rt_log_alarm_ended_night))))
        }
        player.stop(fadeMs = 300)
        _state.update { it.copy(previewing = false) }
        val gain = gainCurve(alarm, r.windowMs)
        // Sound first: a store that can't be written must never keep an alarm quiet.
        alarmPlayer.start(alarm.toneId, route.headset, route.speaker, playOnSpeaker = route.onSpeaker, holdWake = true, gain = gain)
        night = night.copy(
            ringing = RingRecord(alarm.id, began, r.alarmAtMillis),
            log = night.log + note(text.get(if (r.windowMs > 0 && startedAtMillis == null) R.string.sleep_rt_log_wake_window else R.string.sleep_rt_log_alarm_rang)),
        )
        save()
        publishRinging(r, route.fallback)
        publishLog()
        // A ring starts from an alarm broadcast (or a rebuilt process), and the night's
        // notification is swapped for the alarm's (SleepService.show): assume no while-in-use
        // status from here. The alarm plays USAGE_ALARM, exempt while exact alarms are allowed;
        // the morning volume after it waits for the Stop tap or the alarm screen.
        serviceWhileInUse = false
        if (!SleepService.start(context)) {
            // No foreground service from here: post the alarm notification ourselves, so its
            // full-screen screen opens; a visible screen is what lets the alarm sound.
            Log.w(TAG, "alarm: foreground service refused; posting the alarm screen directly")
            (_state.value.session as? SleepSession.Ringing)?.let { session ->
                notifications.ensureChannels()
                runCatching { context.getSystemService(NotificationManager::class.java)?.notify(SleepNotifications.ID_ALARM, notifications.alarm(session)) }
            }
            // Nor can that screen open (no full-screen access, or notifications off): Android may
            // silence or freeze this ring at once. The morning log says so instead of leaving a
            // quiet alarm unexplained (audit R05; typically an inexact alarm, see SleepScheduler).
            if (!canShowFullScreenAlarm() || !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                Log.w(TAG, "alarm: no foreground service and no full-screen alarm screen")
                night = night.copy(log = night.log + note(text.get(R.string.sleep_rt_log_alarm_background), problem = true))
                save()
                publishLog()
            }
        }
        ringWatch?.cancel()
        ringWatch = scope.launch { watchRing(r, gain) }
    }

    /** The 60 s fallback, routing and failure checks, vibration and the 15-minute limit. */
    private suspend fun watchRing(r: Ring, gain: (Long) -> Float) {
        while (true) {
            delay(1_000)
            val done = withContext(NonCancellable) {
                mutex.withLock {
                    if (ring !== r) return@withLock true
                    val elapsed = SystemClock.elapsedRealtime() - r.startedElapsed
                    val sinceAlarm = elapsed - r.windowMs
                    if (r.alarm.output == AlarmOutput.EARBUDS && !r.phoneJoined) {
                        val reason = when {
                            alarmPlayer.headsetFailed() -> AlarmFallback.STREAM_FAILED
                            !alarmPlayer.headsetRoutedOk(r.headset) -> AlarmFallback.ROUTED_ELSEWHERE
                            sinceAlarm >= SleepAlarm.PHONE_FALLBACK_SECONDS * 1_000L -> AlarmFallback.NOT_STOPPED
                            else -> null
                        }
                        if (reason != null) {
                            alarmPlayer.addSpeaker(r.alarm.toneId, r.speaker, gain)
                            r.phoneJoined = true
                            publishRinging(r, reason)
                        }
                    }
                    val phoneRings = r.alarm.output != AlarmOutput.EARBUDS || r.phoneJoined
                    if (sinceAlarm >= 0 && phoneRings && r.alarm.vibrate && !r.vibrating) {
                        alarmPlayer.vibrate(true)
                        r.vibrating = true
                    }
                    if (sinceAlarm >= RING_LIMIT_MS) {
                        stopRingingLocked(r)
                        night = night.copy(log = night.log + note(text.get(R.string.sleep_rt_log_alarm_timed_out), problem = true))
                        morningRestoreLocked()
                        save()
                        publishSession()
                        publishLog()
                        return@withLock true
                    }
                    false
                }
            }
            if (done) return
        }
    }

    override suspend fun snooze() {
        // A ring that began before the first unlock lives in LockedAlarms.
        if (LockedAlarms.isActive) return LockedAlarms.snooze(context)
        locked {
            val r = ring ?: return@locked
            val minutes = r.alarm.snoozeMinutes ?: throw HeadphoneCommandException(text.get(R.string.sleep_rt_error_no_snooze))
            stopRingingLocked(r)
            val at = System.currentTimeMillis() + minutes * SleepPlanner.MINUTE
            night = night.copy(snooze = SnoozeRecord(r.alarm.id, at), log = night.log + note(text.get(R.string.sleep_rt_log_snoozed, timeText(at))))
            scheduler.armAlarm(SleepAlarmReceiver.KIND_SNOOZE, r.alarm.id, at)
            save()
            publishSession()
            publishLog()
        }
        rearmAlarms()
    }

    override suspend fun dismissAlarm() {
        if (LockedAlarms.isActive) LockedAlarms.stop(context)
        locked {
            val r = ring
            val snooze = night.snooze
            if (r == null && snooze == null) return@locked
            r?.let { stopRingingLocked(it) }
            snooze?.let { scheduler.cancelAlarm(SleepAlarmReceiver.KIND_SNOOZE, it.alarmId) }
            night = night.copy(snooze = null, log = night.log + note(text.get(R.string.sleep_rt_log_alarm_stopped)))
            morningRestoreLocked()
            save()
            publishSession()
            publishLog()
        }
        rearmAlarms()
    }

    /** For screens and buttons that may go away mid-command: runs in the repository's own scope. */
    fun dismissAlarmAsync() { scope.launch { runCatching { dismissAlarm() } } }
    fun snoozeAsync() { scope.launch { runCatching { snooze() } } }

    /**
     * Ends a ring. Stopped (or snoozed) during its wake-up window, the alarm time itself mustn't
     * ring a few minutes later: it is skipped, and a one-off alarm counts as done.
     */
    private fun stopRingingLocked(r: Ring) {
        ringWatch?.cancel()
        ringWatch = null
        alarmPlayer.stop()
        ring = null
        val now = System.currentTimeMillis()
        if (now < r.alarmAtMillis) {
            scheduler.cancelAlarm(SleepAlarmReceiver.KIND_RING, r.alarm.id)
            night = night.copy(skipRingUntil = night.skipRingUntil + (r.alarm.id to r.alarmAtMillis + SKIP_MARGIN_MS))
            if (r.alarm.days.isEmpty()) {
                _alarms.update { list -> list.map { if (it.id == r.alarm.id) it.copy(enabled = false) else it } }
                alarmsDirty = true
            }
        }
        night = night.copy(ringing = null)
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(SleepNotifications.ID_ALARM) }
    }

    private fun publishRinging(r: Ring, fallback: AlarmFallback?) {
        val output = if (r.alarm.output == AlarmOutput.EARBUDS && r.phoneJoined) AlarmOutput.BOTH else r.alarm.output
        val reason = fallback?.let {
            text.get(
                when (it) {
                    AlarmFallback.HEADSET_NOT_CONNECTED -> R.string.sleep_rt_fallback_not_connected
                    AlarmFallback.NOT_STOPPED -> R.string.sleep_rt_fallback_not_stopped
                    AlarmFallback.ROUTED_ELSEWHERE -> R.string.sleep_rt_fallback_routed
                    AlarmFallback.STREAM_FAILED -> R.string.sleep_rt_fallback_failed
                },
            )
        } ?: (_state.value.session as? SleepSession.Ringing)?.phoneFallbackReason
        val startedAt = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - r.startedElapsed)
        setSession(SleepSession.Ringing(r.alarm.id, r.alarm.label, startedAt, output, reason, r.alarm.snoozeMinutes != null, r.alarm.snoozeMinutes))
    }

    /** Wake-up window: a slow rise to [WINDOW_PEAK]; then the fade-in to full volume. */
    private fun gainCurve(alarm: SleepAlarm, windowMs: Long): (Long) -> Float {
        val fadeMs = alarm.fadeInSeconds.coerceAtLeast(1) * 1_000L
        return { elapsed ->
            if (elapsed < windowMs) WINDOW_PEAK * Fades.fadeIn(elapsed, windowMs)
            else {
                val base = if (windowMs > 0) WINDOW_PEAK else START_GAIN
                base + (1f - base) * Fades.fadeIn(elapsed - windowMs, fadeMs)
            }
        }
    }

    private class Route(val headset: AudioDeviceInfo?, val speaker: AudioDeviceInfo?, val onSpeaker: Boolean, val fallback: AlarmFallback?)

    private fun route(output: AlarmOutput): Route {
        val devices = alarmPlayer.outputs()
        val address = headphones.state.value.connection.deviceAddress ?: night.restore?.address
        val twins = address?.let { a -> runCatching { finder.twinsOf(a).map { it.address } }.getOrDefault(emptyList()) }.orEmpty()
        val picked = AlarmRouting.route(output, devices.map { OutputCandidate(it.id, it.type, it.address) }, address, twins)
        val headset = picked.headsetOutputId?.let { id -> devices.firstOrNull { it.id == id } }
        val speaker = picked.speakerOutputId?.let { id -> devices.firstOrNull { it.id == id } }
        // Phone, both, or the earbuds' fallback: the phone rings even if Android listed no speaker.
        val onSpeaker = output != AlarmOutput.EARBUDS || picked.fallbackReason != null
        return Route(headset, speaker, onSpeaker, picked.fallbackReason)
    }

    // ---- Text -----------------------------------------------------------------------------------

    private fun note(text: String, problem: Boolean = false) = NoteRecord(System.currentTimeMillis(), text, problem)

    private fun List<StepNote>.toRecords(): List<NoteRecord> {
        val now = System.currentTimeMillis()
        return map { NoteRecord(now, it.text, it.isProblem) }
    }

    private fun durationText(minutes: Int): String =
        if (minutes % 60 == 0) context.resources.getQuantityString(R.plurals.sleep_rt_hours, minutes / 60, minutes / 60)
        else context.resources.getQuantityString(R.plurals.sleep_rt_minutes, minutes, minutes)

    private fun timeText(atMillis: Long): String = DateFormat.getTimeFormat(context).format(Date(atMillis))

    companion object {
        private const val TAG = "Earshot/Sleep"
        private const val MAX_LOG = 40
        private const val TICK_MS = 5_000L
        private const val FADE_STEP_MS = 15_000L
        private const val EARLY_TOLERANCE_MS = 60_000L
        /** An end more than this late (Earshot was closed) skips the end action. */
        private const val LATE_END_MS = 30 * 60_000L
        private const val STALE_SNOOZE_MS = 10 * 60_000L
        /** Alarms further away than this don't belong to tonight. */
        private const val ALARM_HORIZON_MS = 20 * 60 * 60_000L
        private const val RESTORE_AFTER_ALARM_MS = 30 * 60_000L
        private const val RESTORE_AFTER_END_MS = 60_000L
        private const val DEFAULT_NIGHT_MS = 10 * 60 * 60_000L
        private const val RECONNECT_LEAD_MS = 2 * 60_000L
        private const val SKIP_MARGIN_MS = 60_000L
        /** Clock apps give up after about ten minutes; a sleep alarm in earbuds gets a little longer. */
        private const val RING_LIMIT_MS = 15 * 60_000L
        private const val WINDOW_PEAK = 0.3f
        private const val START_GAIN = 0.08f
        private const val PREVIEW_GAIN = 0.6f
        private const val TONE_PREVIEW_MS = 6_000L
        private const val MAX_NAME = 40
    }
}
