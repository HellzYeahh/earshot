package dev.earshot.sleep

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.earshot.core.CustomSound
import dev.earshot.core.ModeRef
import dev.earshot.core.SavedMix
import dev.earshot.core.SleepAlarm
import dev.earshot.core.SleepPlan
import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

// A corrupt file is replaced with an empty one: the tab starts over rather than never loading.
private val Context.sleepStore: DataStore<Preferences> by preferencesDataStore(
    name = "sleep",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** A night in progress: enough to rebuild the session after the process was killed. */
@Serializable
data class ActiveNight(
    val plan: SleepPlan,
    val startedAtMillis: Long,
    val endsAtMillis: Long,
    val fadeOutStartsAtMillis: Long,
    val noiseOffAtMillis: Long? = null,
    val noiseOffDone: Boolean = false,
    val headsetAddress: String? = null,
    val headsetName: String? = null,
    /** The sounds started on the headset's own output: never move them to the speaker later. */
    val soundOnHeadset: Boolean = false,
    /** The sounds stopped for good tonight (the headset went away); recovery must not restart them. */
    val soundsStopped: Boolean = false,
) {
    val timeline: SleepTimeline get() = SleepTimeline(startedAtMillis, endsAtMillis, fadeOutStartsAtMillis, noiseOffAtMillis)

    fun withTimeline(t: SleepTimeline) = copy(
        startedAtMillis = t.startedAtMillis, endsAtMillis = t.endsAtMillis,
        fadeOutStartsAtMillis = t.fadeOutStartsAtMillis, noiseOffAtMillis = t.noiseOffAtMillis,
    )
}

/**
 * What the night changed on the headset and the phone, to put back in the morning
 * (docs/sleep-mode.md §4.5). Null fields were not changed.
 */
@Serializable
data class RestoreRecord(
    val address: String? = null,
    val mode: ModeRef? = null,
    val ancEnabled: Boolean? = null,
    val touchControlsEnabled: Boolean? = null,
    val voicePromptsEnabled: Boolean? = null,
    val voicePromptsLanguage: Int? = null,
    /** Vendor prompt switches (ids starting with `prompt_`) and their values before the night. */
    val vendorPrompts: Map<String, Int> = emptyMap(),
    /** `NotificationManager.getCurrentInterruptionFilter()` before Do Not Disturb went on. */
    val interruptionFilter: Int? = null,
    /** The filter Earshot set (Priority, or Alarms only): put back only if it is still that one. */
    val ourInterruptionFilter: Int? = null,
    /** Media volume index before the night cap or the end-of-night fade lowered it. */
    val musicVolume: Int? = null,
    /**
     * The output [musicVolume] belongs to (Android keeps one media volume per output): it is put
     * back only while that output plays media, never onto the phone speaker at a headset's level.
     */
    val musicVolumeOutput: String? = null,
    /** When to restore if no alarm does it first. */
    val restoreAtMillis: Long = 0L,
    /**
     * Morning has come but the headset was away: restore the rest as soon as that headset's
     * controls connect again.
     */
    val due: Boolean = false,
) {
    val headsetPart: Boolean get() = mode != null || ancEnabled != null || touchControlsEnabled != null || voicePromptsEnabled != null || vendorPrompts.isNotEmpty()
    val phonePart: Boolean get() = interruptionFilter != null || musicVolume != null
    val isEmpty: Boolean get() = !headsetPart && !phonePart
}

@Serializable
data class SnoozeRecord(val alarmId: String, val ringsAtMillis: Long)

/** An alarm ringing now, so a process killed mid-ring rings again instead of going quiet. */
@Serializable
data class RingRecord(val alarmId: String, val startedAtMillis: Long, val alarmAtMillis: Long)

@Serializable
data class NoteRecord(val atMillis: Long, val text: String, val isProblem: Boolean = false)

/** Everything about tonight, written as one value so the pieces never disagree after a crash. */
@Serializable
data class NightRecord(
    val active: ActiveNight? = null,
    val restore: RestoreRecord? = null,
    val snooze: SnoozeRecord? = null,
    val log: List<NoteRecord> = emptyList(),
    val ringing: RingRecord? = null,
    /** Restores waiting for another headset than tonight's (it was away in the morning). */
    val pending: List<RestoreRecord> = emptyList(),
    /**
     * Per alarm: don't ring before this time. Set when an alarm is stopped or snoozed during its
     * wake-up window, so the alarm time itself doesn't ring again a few minutes later.
     */
    val skipRingUntil: Map<String, Long> = emptyMap(),
)

/**
 * The "sleep" DataStore: tonight's plan, the alarms, saved mixes and the night in progress, as
 * JSON. Lists are decoded element by element (like the automation rules), so one unreadable
 * alarm written by a newer build never empties the list.
 */
class SleepStore(context: Context) {
    private val store = context.applicationContext.sleepStore

    data class Snapshot(
        val plan: SleepPlan,
        val alarms: List<SleepAlarm>,
        val mixes: List<SavedMix>,
        val night: NightRecord,
        val customSounds: List<CustomSound> = emptyList(),
    )

    suspend fun load(): Snapshot {
        val prefs = store.data.first()
        return Snapshot(
            plan = prefs[PLAN]?.let { decode(SleepPlan.serializer(), it) }?.let(SleepPlanner::sanitise) ?: SleepPlan(),
            alarms = decodeList(SleepAlarm.serializer(), prefs[ALARMS]),
            mixes = decodeList(SavedMix.serializer(), prefs[MIXES]),
            night = prefs[NIGHT]?.let { decode(NightRecord.serializer(), it) } ?: NightRecord(),
            customSounds = decodeList(CustomSound.serializer(), prefs[CUSTOM]),
        )
    }

    suspend fun savePlan(plan: SleepPlan) = store.edit { it[PLAN] = json.encodeToString(SleepPlan.serializer(), plan) }
    suspend fun saveAlarms(alarms: List<SleepAlarm>) = store.edit { it[ALARMS] = json.encodeToString(ListSerializer(SleepAlarm.serializer()), alarms) }
    suspend fun saveMixes(mixes: List<SavedMix>) = store.edit { it[MIXES] = json.encodeToString(ListSerializer(SavedMix.serializer()), mixes) }
    suspend fun saveCustomSounds(sounds: List<CustomSound>) = store.edit { it[CUSTOM] = json.encodeToString(ListSerializer(CustomSound.serializer()), sounds) }
    suspend fun saveNight(night: NightRecord) = store.edit { it[NIGHT] = json.encodeToString(NightRecord.serializer(), night) }

    private fun <T> decode(serializer: KSerializer<T>, raw: String): T? = runCatching { json.decodeFromString(serializer, raw) }.getOrNull()

    private fun <T> decodeList(serializer: KSerializer<T>, raw: String?): List<T> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element -> runCatching { json.decodeFromJsonElement(serializer, element) }.getOrNull() }
    }

    companion object {
        internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val PLAN = stringPreferencesKey("plan")
        private val ALARMS = stringPreferencesKey("alarms")
        private val MIXES = stringPreferencesKey("mixes")
        private val NIGHT = stringPreferencesKey("night")
        private val CUSTOM = stringPreferencesKey("custom_sounds")
    }
}
