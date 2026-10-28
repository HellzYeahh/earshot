package dev.earshot.core.fake

import dev.earshot.core.AlarmOutput
import dev.earshot.core.BatteryDetail
import dev.earshot.core.BatteryLevel
import dev.earshot.core.CustomSound
import dev.earshot.core.FormFactor
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.NextAlarm
import dev.earshot.core.SavedMix
import dev.earshot.core.SleepAbility
import dev.earshot.core.SleepAlarm
import dev.earshot.core.SleepHeadset
import dev.earshot.core.SleepLayer
import dev.earshot.core.SleepNote
import dev.earshot.core.SleepPermissions
import dev.earshot.core.SleepPhase
import dev.earshot.core.SleepPlan
import dev.earshot.core.SleepRepository
import dev.earshot.core.SleepSession
import dev.earshot.core.SleepState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * In-memory Sleep tab for previews and UI tests: a Sleep A30-like headset that can do every night
 * step, one weekday alarm and one saved mix. Nothing plays and no time passes; [start] jumps
 * straight to a running session and [ring] lets a preview show the alarm.
 */
class FakeSleepRepository(
    running: Boolean = false,
    headset: SleepHeadset = SAMPLE_HEADSET,
) : SleepRepository {
    private val _plan = MutableStateFlow(SleepPlan(durationMinutes = 180, layers = listOf(SleepLayer("rain", 0.7f), SleepLayer("fire", 0.4f)), noiseOffAfterMinutes = 30))
    override val plan: StateFlow<SleepPlan> = _plan

    private val _alarms = MutableStateFlow(listOf(SleepAlarm(id = "a1", hour = 7, minute = 0, days = setOf(1, 2, 3, 4, 5), wakeWindowMinutes = 20)))
    override val alarms: StateFlow<List<SleepAlarm>> = _alarms

    private val _mixes = MutableStateFlow(listOf(SavedMix("m1", "Rainy cabin", listOf(SleepLayer("rain", 0.7f), SleepLayer("fire", 0.4f)))))
    override val mixes: StateFlow<List<SavedMix>> = _mixes

    private val _headset = MutableStateFlow(headset)
    override val headset: StateFlow<SleepHeadset> = _headset

    private val _permissions = MutableStateFlow(SleepPermissions(doNotDisturb = false))
    override val permissions: StateFlow<SleepPermissions> = _permissions

    private val _state = MutableStateFlow(SleepState(nextAlarm = NextAlarm("a1", System.currentTimeMillis() + 7 * HOUR)))
    override val state: StateFlow<SleepState> = _state

    init {
        if (running) startNow()
    }

    override suspend fun updatePlan(plan: SleepPlan) {
        _plan.value = plan
    }

    override suspend fun start() {
        if (_plan.value.layers.size > SleepPlan.MAX_LAYERS) throw HeadphoneCommandException("Pick up to ${SleepPlan.MAX_LAYERS} sounds.")
        startNow()
    }

    private fun startNow() {
        val now = System.currentTimeMillis()
        val plan = _plan.value
        _state.update {
            it.copy(
                previewing = false,
                session = SleepSession.Running(
                    plan = plan,
                    startedAtMillis = now - 19 * MINUTE,
                    endsAtMillis = now - 19 * MINUTE + plan.durationMinutes * MINUTE,
                    phase = if (plan.layers.isEmpty()) SleepPhase.TIMER_ONLY else SleepPhase.PLAYING,
                    noiseOffAtMillis = plan.noiseOffAfterMinutes?.let { now - 19 * MINUTE + it * MINUTE },
                    headsetName = _headset.value.name,
                ),
                log = listOf(SleepNote(now - 19 * MINUTE, "Started: 3 h with rain and fire")),
            )
        }
    }

    override suspend fun extend(minutes: Int) {
        _state.update { s ->
            val running = s.session as? SleepSession.Running ?: return@update s
            s.copy(session = running.copy(endsAtMillis = running.endsAtMillis + minutes * MINUTE))
        }
    }

    override suspend fun stop() {
        _state.update { it.copy(session = SleepSession.Idle) }
    }

    override fun preview(layers: List<SleepLayer>) = _state.update { it.copy(previewing = layers.isNotEmpty()) }
    override fun stopPreview() = _state.update { it.copy(previewing = false) }

    override suspend fun saveAlarm(alarm: SleepAlarm) = _alarms.update { list -> list.filterNot { it.id == alarm.id } + alarm }
    override suspend fun deleteAlarm(id: String) = _alarms.update { list -> list.filterNot { it.id == id } }

    /** Shows the ringing state (previews of the alarm screen). */
    fun ring(fallbackReason: String? = null) = _state.update {
        it.copy(session = SleepSession.Ringing("a1", null, System.currentTimeMillis(), if (fallbackReason == null) AlarmOutput.EARBUDS else AlarmOutput.BOTH, fallbackReason))
    }

    override suspend fun snooze() = _state.update { it.copy(session = SleepSession.Snoozed("a1", null, System.currentTimeMillis() + 10 * MINUTE)) }
    override suspend fun dismissAlarm() = _state.update { it.copy(session = SleepSession.Idle) }

    override fun previewAlarmTone(toneId: String?, output: AlarmOutput) = Unit

    override suspend fun saveMix(name: String, layers: List<SleepLayer>) = _mixes.update { it + SavedMix(UUID.randomUUID().toString(), name, layers) }
    override suspend fun deleteMix(id: String) = _mixes.update { list -> list.filterNot { it.id == id } }

    override fun refreshPermissions() = Unit

    private val _customSounds = MutableStateFlow(listOf(CustomSound("${CustomSound.ID_PREFIX}c1", "Grandma's clock", 42)))
    override val customSounds: StateFlow<List<CustomSound>> = _customSounds

    override suspend fun importCustomSound(uri: String, name: String): CustomSound =
        CustomSound(CustomSound.ID_PREFIX + UUID.randomUUID(), name, 60).also { sound -> _customSounds.update { it + sound } }

    override suspend fun deleteCustomSound(id: String) {
        _customSounds.update { list -> list.filterNot { it.id == id } }
        _plan.update { it.copy(layers = it.layers.filterNot { l -> l.soundId == id }) }
    }

    companion object {
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE

        val SAMPLE_HEADSET = SleepHeadset(
            name = "Soundcore Sleep A30",
            address = "00:11:22:33:44:55",
            audioConnected = true,
            battery = BatteryDetail(left = BatteryLevel(90), right = BatteryLevel(80)),
            batteryPercent = 80,
            minutesLeft = 7 * 60,
            minutesLeftIsGuess = true,
            formFactor = FormFactor.EARBUDS,
            noiseOff = SleepAbility(true),
            disconnect = SleepAbility(true),
            powerOff = SleepAbility(false, "These earbuds can't be switched off by an app. Earshot disconnects them instead."),
            touchLock = SleepAbility(true),
            promptsOff = SleepAbility(true),
        )

        /** Any headset without a driver: sounds, timer and alarm only. */
        val GENERIC_HEADSET = SleepHeadset(
            name = "Galaxy Buds",
            address = "AA:BB:CC:DD:EE:FF",
            audioConnected = true,
            batteryPercent = 60,
            noiseOff = SleepAbility(false, "Earshot can't switch noise cancelling on this headset."),
            disconnect = SleepAbility(false, "Link this headset once (Tools, Controls) to let Earshot disconnect it."),
            powerOff = SleepAbility(false, "Earshot can't switch this headset off."),
            touchLock = SleepAbility(false, "Earshot can't change this headset's touch controls."),
            promptsOff = SleepAbility(false, "Earshot can't change this headset's voice prompts."),
        )
    }
}
