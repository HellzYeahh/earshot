package dev.earshot.sleep

import androidx.annotation.StringRes
import dev.earshot.R
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.HeadphoneState
import dev.earshot.core.ModeMeaning
import dev.earshot.core.ModeRef
import dev.earshot.core.ModeResolver
import dev.earshot.core.NightExtra
import dev.earshot.core.SleepAbility
import dev.earshot.core.SleepEndAction
import dev.earshot.core.SleepHeadset
import dev.earshot.core.SleepPlan
import dev.earshot.core.VoicePrompts
import dev.earshot.extras.HeadphoneLabels
import kotlinx.coroutines.CancellationException

/** The connect / disconnect half of the universal remote, as the Sleep tab needs it (a seam for tests). */
interface HeadsetLink {
    /** Android can connect and disconnect a linked headset (Android 17). */
    val canConnect: Boolean
    fun isLinked(address: String): Boolean
    fun connect(address: String)
    fun disconnect(address: String)
}

/** Looks up user-visible text; `Context::getString` in the app, a stub in tests. */
fun interface SleepText {
    fun get(@StringRes id: Int, vararg args: Any): String
}

/** One night step's outcome, for tonight's log. */
data class StepNote(val text: String, val isProblem: Boolean = false)

/**
 * The headset half of a night (docs/sleep-mode.md §4.5, §4.6), for any brand, through
 * [HeadphoneRepository] only: no vendor code here (`VendorBoundaryTest`). Modes are chosen by
 * meaning; a step the driver refuses becomes a [StepNote], never an exception, so one missing
 * feature can't stop the night.
 */
class SleepHeadsetActions(
    private val headphones: HeadphoneRepository,
    private val link: HeadsetLink,
    private val text: SleepText,
) {
    /** How noise cancelling goes off on this headset, or null when it can't. */
    sealed interface NoiseOffPlan {
        data class Mode(val slot: Int) : NoiseOffPlan
        data object AncSwitch : NoiseOffPlan
        data object AlreadyOff : NoiseOffPlan
    }

    fun noiseOffPlan(s: HeadphoneState): NoiseOffPlan? {
        if (!s.isConnected || !s.capabilities.ownSettings) return null
        val current = s.currentMode
        if (current != null && ModeResolver.meaningOf(current) == ModeMeaning.OFF) return NoiseOffPlan.AlreadyOff
        // A mode that means Off first (Sleep A30, Crusher, Sony, Sennheiser, Soundcore Normal)…
        if (s.capabilities.listeningModes) {
            ModeResolver.slotFor(ModeRef(ModeMeaning.OFF), s)?.let { return NoiseOffPlan.Mode(it) }
        }
        // …else the ANC switch (the QC Ultra 2 has no Off mode but has the [31.10] ANC byte). Only
        // where noise changes are live: elsewhere setNoise rewrites the active custom mode.
        val noise = s.noise
        if (noise != null && s.capabilities.noiseCancelling && s.capabilities.ancToggle && s.capabilities.liveNoiseControl && !s.capabilities.ancAlwaysOn) {
            return if (noise.ancEnabled) NoiseOffPlan.AncSwitch else NoiseOffPlan.AlreadyOff
        }
        return null
    }

    fun abilities(s: HeadphoneState): SleepHeadset {
        val address = s.connection.deviceAddress
        val own = s.capabilities.ownSettings
        val driven = s.isConnected
        val minutes = s.battery?.overall?.minutesLeft
            ?: listOfNotNull(s.battery?.left?.minutesLeft, s.battery?.right?.minutesLeft).minOrNull()
        val (left, guess) = SleepPlanner.minutesLeft(s.batteryPercent, minutes, s.capabilities.formFactor)

        fun ownStep(available: Boolean, @StringRes none: Int): SleepAbility = when {
            !own -> SleepAbility(false, text.get(R.string.sleep_rt_note_no_driver))
            !driven -> SleepAbility(false, text.get(R.string.sleep_rt_note_controls_offline))
            available -> SleepAbility(true)
            else -> SleepAbility(false, text.get(none))
        }

        val disconnect = when {
            address == null -> SleepAbility(false, text.get(R.string.sleep_rt_note_no_headset))
            !link.canConnect -> SleepAbility(false, text.get(R.string.sleep_rt_note_disconnect_android17))
            !link.isLinked(address) -> SleepAbility(false, text.get(R.string.sleep_rt_note_disconnect_link))
            else -> SleepAbility(true)
        }
        val powerOff = when {
            !own -> SleepAbility(false, text.get(R.string.sleep_rt_note_power_off_none))
            !driven -> SleepAbility(false, text.get(R.string.sleep_rt_note_controls_offline))
            // Only some drivers can (Bose, Sony); the others refuse and the night falls back to disconnect.
            else -> SleepAbility(true, text.get(R.string.sleep_rt_note_power_off_fallback))
        }
        return SleepHeadset(
            name = HeadphoneLabels.displayName(s),
            address = address,
            audioConnected = s.isAudioActive,
            battery = s.battery,
            batteryPercent = s.batteryPercent,
            minutesLeft = left,
            minutesLeftIsGuess = guess,
            formFactor = s.capabilities.formFactor,
            noiseOff = ownStep(noiseOffPlan(s) != null, R.string.sleep_rt_note_noise_none),
            disconnect = disconnect,
            powerOff = powerOff,
            touchLock = ownStep(s.touchControlsEnabled != null, R.string.sleep_rt_note_touch_none),
            promptsOff = ownStep(s.voicePrompts != null || promptControls(s).isNotEmpty(), R.string.sleep_rt_note_prompts_none),
        )
    }

    /** Vendor switches for spoken prompts (the Sleep A30's `prompt_*` controls), currently on. */
    private fun promptControls(s: HeadphoneState) = s.vendorControls.filter { it.id.startsWith(PROMPT_PREFIX) && it.options == null && it.range == null && it.value != null }

    /** Touch lock and prompts off at the start of the night; returns what to restore. */
    suspend fun applyNightExtras(plan: SleepPlan, s: HeadphoneState, record: RestoreRecord, notes: MutableList<StepNote>): RestoreRecord {
        var r = record.copy(address = record.address ?: s.connection.deviceAddress)
        if (NightExtra.TOUCH_LOCK in plan.nightExtras) {
            when {
                s.touchControlsEnabled == true -> step(notes, R.string.sleep_rt_log_touch_locked, R.string.sleep_rt_log_touch_failed) {
                    headphones.setTouchControls(false)
                    r = r.copy(touchControlsEnabled = true)
                }
                s.touchControlsEnabled == false -> Unit // already off: nothing to restore
                else -> notes += StepNote(abilities(s).touchLock.note ?: text.get(R.string.sleep_rt_note_touch_none), isProblem = true)
            }
        }
        if (NightExtra.PROMPTS_OFF in plan.nightExtras) {
            val prompts = s.voicePrompts
            val vendor = promptControls(s).filter { it.value != 0 }
            if (prompts == null && vendor.isEmpty() && promptControls(s).isEmpty()) {
                notes += StepNote(abilities(s).promptsOff.note ?: text.get(R.string.sleep_rt_note_prompts_none), isProblem = true)
            }
            if (prompts?.enabled == true) step(notes, R.string.sleep_rt_log_prompts_off, R.string.sleep_rt_log_prompts_failed) {
                headphones.setVoicePrompts(VoicePrompts(enabled = false, languageId = prompts.languageId))
                r = r.copy(voicePromptsEnabled = true, voicePromptsLanguage = prompts.languageId)
            }
            if (vendor.isNotEmpty()) {
                val turnedOff = HashMap<String, Int>()
                for (control in vendor) {
                    if (attempt { headphones.setVendorControl(control.id, 0) }) turnedOff[control.id] = control.value ?: 1
                }
                if (turnedOff.isNotEmpty()) {
                    r = r.copy(vendorPrompts = r.vendorPrompts + turnedOff)
                    if (prompts?.enabled != true) notes += StepNote(text.get(R.string.sleep_rt_log_prompts_off))
                }
                if (turnedOff.size < vendor.size) notes += StepNote(text.get(R.string.sleep_rt_log_prompts_failed), isProblem = true)
            }
        }
        return r
    }

    /** Noise cancelling off now; returns the record with what to put back. */
    suspend fun noiseOff(s: HeadphoneState, record: RestoreRecord, notes: MutableList<StepNote>): RestoreRecord {
        val plan = noiseOffPlan(s)
        if (plan == null) {
            notes += StepNote(abilities(s).noiseOff.note ?: text.get(R.string.sleep_rt_note_noise_none), isProblem = true)
            return record
        }
        var r = record.copy(address = record.address ?: s.connection.deviceAddress)
        when (plan) {
            NoiseOffPlan.AlreadyOff -> notes += StepNote(text.get(R.string.sleep_rt_log_noise_already_off))
            is NoiseOffPlan.Mode -> step(notes, R.string.sleep_rt_log_noise_off, R.string.sleep_rt_log_noise_failed) {
                // Remember the mode by meaning, never a bare slot (A7), so the morning picks it again.
                val before = s.currentMode?.let { ModeResolver.refFor(it, s.connection.deviceAddress) }
                headphones.setMode(plan.slot)
                if (r.mode == null) r = r.copy(mode = before)
            }
            NoiseOffPlan.AncSwitch -> step(notes, R.string.sleep_rt_log_noise_off, R.string.sleep_rt_log_noise_failed) {
                headphones.setAncEnabled(false)
                if (r.ancEnabled == null) r = r.copy(ancEnabled = true)
            }
        }
        return r
    }

    /**
     * The end of the timer: disconnect or switch off; STOP_SOUNDS does nothing here. True when the
     * headset was sent away (switched off, or Earshot's link dropped and Android asked to
     * disconnect), so the caller keeps the morning restore from connecting it straight back
     * ([RestoreConnect.Hold]).
     */
    suspend fun endAction(action: SleepEndAction, s: HeadphoneState, notes: MutableList<StepNote>): Boolean {
        val address = s.connection.deviceAddress
        return when (action) {
            SleepEndAction.STOP_SOUNDS -> false
            SleepEndAction.DISCONNECT -> disconnect(address, notes)
            SleepEndAction.POWER_OFF -> {
                val off = s.isConnected && s.capabilities.ownSettings && attempt { headphones.powerOff() }
                if (off) {
                    notes += StepNote(text.get(R.string.sleep_rt_log_powered_off))
                    true
                } else {
                    notes += StepNote(text.get(R.string.sleep_rt_log_power_off_refused))
                    disconnect(address, notes)
                }
            }
        }
    }

    /** False when nothing was disconnected (no link to do it with); true once Earshot's own link dropped. */
    private fun disconnect(address: String?, notes: MutableList<StepNote>): Boolean {
        if (address == null || !link.canConnect || !link.isLinked(address)) {
            notes += StepNote(text.get(if (address != null && !link.canConnect) R.string.sleep_rt_note_disconnect_android17 else R.string.sleep_rt_note_disconnect_link), isProblem = true)
            return false
        }
        // Earshot's own control link first, as a user disconnect: otherwise its retry loop could
        // see the audio link still up for a moment and page the headset straight back.
        headphones.disconnect()
        runCatching { link.disconnect(address) }
            .onSuccess { notes += StepNote(text.get(R.string.sleep_rt_log_disconnected)) }
            .onFailure { notes += StepNote(text.get(R.string.sleep_rt_log_disconnect_failed, it.message.orEmpty()), isProblem = true) }
        // Even if Android refused, Earshot's link went down as a user disconnect: a restore connect would undo it.
        return true
    }

    /** Pages a headset that was disconnected for the night (before an earbud alarm), then lets Earshot reconnect. Best effort. */
    fun reconnect(address: String): Boolean {
        val paged = link.canConnect && link.isLinked(address) && runCatching { link.connect(address) }.isSuccess
        headphones.connect()
        return paged
    }

    /**
     * True once [s] is [record]'s headset with its settings read: a driver publishes "connected"
     * before it has read the modes and noise settings, and restoring into that empty state would
     * find no mode to go back to.
     */
    fun readyToRestore(record: RestoreRecord, s: HeadphoneState): Boolean {
        val address = s.connection.deviceAddress ?: return false
        if (!s.isConnected || (record.address != null && !record.address.equals(address, ignoreCase = true))) return false
        if (record.mode != null && s.modes.none { it.isConfigured }) return false
        if (record.ancEnabled != null && s.noise == null) return false
        if (record.touchControlsEnabled != null && s.touchControlsEnabled == null) return false
        if (record.voicePromptsEnabled != null && s.voicePrompts == null) return false
        return true
    }

    /**
     * Puts back the headset settings in [record] when [s] is that headset with its settings read
     * ([readyToRestore]). A field is cleared only when its command worked; what's left stays
     * pending for the next time the headset connects.
     */
    suspend fun restore(record: RestoreRecord, s: HeadphoneState, notes: MutableList<StepNote>): RestoreRecord {
        if (!record.headsetPart || !readyToRestore(record, s)) return record
        var r = record
        r.mode?.let { ref ->
            val slot = ModeResolver.slotFor(ref, s)
            // A headset that no longer has that mode can't get it back: drop it rather than retry forever.
            if (slot == null || attempt { headphones.setMode(slot) }) r = r.copy(mode = null)
        }
        if (r.ancEnabled == true && attempt { headphones.setAncEnabled(true) }) r = r.copy(ancEnabled = null)
        if (r.touchControlsEnabled == true && attempt { headphones.setTouchControls(true) }) r = r.copy(touchControlsEnabled = null)
        if (r.voicePromptsEnabled == true) {
            val language = r.voicePromptsLanguage ?: s.voicePrompts?.languageId
            if (language != null && attempt { headphones.setVoicePrompts(VoicePrompts(enabled = true, languageId = language)) }) {
                r = r.copy(voicePromptsEnabled = null, voicePromptsLanguage = null)
            }
        }
        val prompts = r.vendorPrompts.filterNot { (id, value) -> attempt { headphones.setVendorControl(id, value) } }
        r = r.copy(vendorPrompts = prompts)
        notes += StepNote(text.get(R.string.sleep_rt_log_restored), isProblem = r.headsetPart)
        return r
    }

    /** Runs a headset command; false when the driver refused. Cancellation is never swallowed. */
    private suspend fun attempt(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SleepLog.w("headset step failed: ${e.message}")
        false
    }

    private suspend fun step(notes: MutableList<StepNote>, @StringRes ok: Int, @StringRes failed: Int, block: suspend () -> Unit) {
        // The driver's own message goes to logcat; the night's log stays short and calm.
        if (attempt(block)) notes += StepNote(text.get(ok))
        else notes += StepNote(text.get(failed), isProblem = true)
    }

    companion object {
        /** Vendor controls with this id prefix are spoken-prompt switches (a naming convention, not a vendor reference). */
        const val PROMPT_PREFIX = "prompt_"
    }
}
