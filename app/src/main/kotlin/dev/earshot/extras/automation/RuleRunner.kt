package dev.earshot.extras.automation

import android.util.Log
import dev.earshot.core.AudioTuningRepository
import dev.earshot.core.AutomationAction
import dev.earshot.core.AutomationRepository
import dev.earshot.core.AutomationRule
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneRepository
import dev.earshot.core.ModeResolver
import dev.earshot.core.NoiseSettings
import dev.earshot.core.SpatialAudio
import dev.earshot.core.Trigger
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Executes a rule's actions against the repositories and writes one log line per rule run. */
class RuleRunner(
    private val headphones: HeadphoneRepository,
    private val tuning: AudioTuningRepository,
    private val automation: AutomationRepository,
    private val guard: FireGuard = FireGuard(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log: AutomationLog? = automation as? AutomationLog

    /** Runs [rule] unless it fired within the guard window. Returns true when it ran (even if an action failed). */
    suspend fun run(rule: AutomationRule, reason: String = describe(rule.trigger)): Boolean {
        if (!guard.shouldFire(rule.id, clock())) return false
        val stamp = LocalTime.now().format(TIME)
        val failures = ArrayList<String>()
        for (action in rule.actions) {
            runCatching { perform(action) }
                .onFailure { e -> failures += (e.message ?: e.javaClass.simpleName) }
        }
        val summary = rule.actions.joinToString(", ") { describe(it) }.ifEmpty { "(no actions)" }
        val line = if (failures.isEmpty()) "$stamp $reason → $summary"
        else "$stamp $reason → $summary failed: ${failures.joinToString("; ")}"
        log?.append(line) ?: Log.i(TAG, line)
        return true
    }

    private suspend fun perform(action: AutomationAction) {
        when (action) {
            is AutomationAction.SetMode -> headphones.setMode(slotFor(action))
            is AutomationAction.SetNoise -> headphones.setNoise(
                NoiseSettings(
                    cancellingLevel = action.cancellingLevel.coerceIn(0, 10),
                    ancEnabled = action.ancEnabled,
                    windBlock = action.windBlock,
                    spatialAudio = SpatialAudio.fromStoredId(action.spatialAudioWire),
                ),
            )
            is AutomationAction.SetEqPreset -> tuning.applyPreset(action.presetId)
        }
    }

    /**
     * The slot to switch to on the connected headset. A rule bound to a meaning resolves it there,
     * so a Bose "Immersion" rule fails on headphones without one instead of picking whatever their
     * slot 2 is. A 0.75 rule the migration hasn't bound yet still sends its slot, as 0.75 did.
     */
    private fun slotFor(action: AutomationAction.SetMode): Int {
        val ref = action.mode ?: return action.modeIndex
        val state = headphones.state.value
        return ModeResolver.slotFor(ref, state)
            ?: throw HeadphoneCommandException(
                if (state.modes.none { it.isConfigured }) "The listening modes aren't loaded yet"
                else "${ModeResolver.label(ref)} isn't a mode of these headphones",
            )
    }

    fun describe(trigger: Trigger): String = when (trigger) {
        Trigger.OnConnect -> "Connected"
        is Trigger.WifiNetwork -> "Wi-Fi '${trigger.ssid}'"
        is Trigger.ForegroundApp -> trigger.appLabel.ifBlank { trigger.packageName }
        is Trigger.Schedule -> "Schedule %02d:%02d".format(trigger.hour, trigger.minute)
    }

    fun describe(action: AutomationAction): String = when (action) {
        is AutomationAction.SetMode -> modeName(action)
        is AutomationAction.SetNoise -> buildString {
            append(if (action.ancEnabled) "ANC ${action.cancellingLevel}" else "ANC off")
            if (action.windBlock) append(", wind block")
            SpatialAudio.fromStoredId(action.spatialAudioWire).takeIf { it != SpatialAudio.OFF }?.let { append(", ${it.label}") }
        }
        is AutomationAction.SetEqPreset -> action.presetId
            ?.let { id -> tuning.presets.value.firstOrNull { it.id == id }?.name ?: "EQ $id" }
            ?: "EQ flat"
    }

    /** The connected headset's name for it, else the saved ref's; never another model's preset names. */
    private fun modeName(action: AutomationAction.SetMode): String {
        val state = headphones.state.value
        return ModeResolver.nameOf(action, state.modes, state.connection.deviceAddress) ?: "mode ${action.modeIndex}"
    }

    private companion object {
        const val TAG = "Earshot/Automation"
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
