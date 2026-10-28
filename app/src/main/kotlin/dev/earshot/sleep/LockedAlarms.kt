package dev.earshot.sleep

import android.app.NotificationManager
import android.content.Context
import android.os.UserManager
import android.util.Log
import androidx.core.content.edit
import dev.earshot.R
import dev.earshot.core.AlarmOutput
import dev.earshot.core.SleepSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** One upcoming ring, copied where it can be read before the phone is unlocked. */
@Serializable
data class MirroredRing(
    val alarmId: String,
    val atMillis: Long,
    val toneId: String,
    val fadeInSeconds: Int,
    val snoozeMinutes: Int?,
    val vibrate: Boolean,
    val label: String? = null,
)

/**
 * Wake alarms through an overnight restart (an OS update, One UI's auto-restart) while the phone
 * is still locked. Android forgets `AlarmManager` alarms on a reboot, and before the first unlock
 * Earshot's own storage (credential-encrypted) can't be read, so the normal path can neither
 * re-arm nor ring.
 *
 * The unlocked app copies the next rings into device-protected storage ([mirror]); the
 * `LOCKED_BOOT_COMPLETED` broadcast re-arms them; a ring while still locked plays on the phone
 * through this small ringer, which needs nothing else from the app. One-off alarms that rang this
 * way are switched off once the phone is unlocked ([consumeRang]).
 */
object LockedAlarms {
    private const val TAG = "Earshot/Sleep"
    private const val PREFS = "sleep_locked"
    private const val KEY_RINGS = "rings"
    private const val KEY_RANG = "rang"
    private const val KEY_SNOOZE = "snooze"
    private const val RING_LIMIT_MS = 15 * 60_000L
    private const val START_GAIN = 0.08f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<SleepSession>(SleepSession.Idle)
    val state: StateFlow<SleepSession> = _state.asStateFlow()

    // Holds only the application context (see [ring]), so nothing leaks an activity.
    @android.annotation.SuppressLint("StaticFieldLeak")
    private var player: AlarmPlayer? = null
    private var current: MirroredRing? = null
    private var limit: Job? = null

    /** A locked-phone ring is going on (it keeps going after an unlock until stopped). */
    val isActive: Boolean get() = current != null

    fun isLocked(context: Context): Boolean =
        runCatching { context.getSystemService(UserManager::class.java)?.isUserUnlocked == false }.getOrDefault(false)

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mirror(context: Context, rings: List<MirroredRing>) {
        runCatching { prefs(context).edit { putString(KEY_RINGS, SleepStore.json.encodeToString(ListSerializer(MirroredRing.serializer()), rings)) } }
    }

    fun read(context: Context): List<MirroredRing> = runCatching {
        prefs(context).getString(KEY_RINGS, null)?.let { SleepStore.json.decodeFromString(ListSerializer(MirroredRing.serializer()), it) }
    }.getOrNull().orEmpty()

    /** `LOCKED_BOOT_COMPLETED`: arm every mirrored ring still ahead (or just missed during the reboot). */
    fun rearmAfterLockedBoot(context: Context) {
        val now = System.currentTimeMillis()
        val scheduler = SleepScheduler(context)
        read(context).filter { it.atMillis > now - JUST_MISSED_MS }.forEach { ring ->
            scheduler.armAlarm(SleepAlarmReceiver.KIND_RING, ring.alarmId, maxOf(ring.atMillis, now + 2_000))
        }
        Log.i(TAG, "locked boot: re-armed ${read(context).size} alarm(s)")
    }

    /** Alarms that rang while locked, by id, with when; cleared by reading. */
    fun consumeRang(context: Context): Map<String, Long> {
        val raw = runCatching { prefs(context).getString(KEY_RANG, null) }.getOrNull() ?: return emptyMap()
        runCatching { prefs(context).edit { remove(KEY_RANG) } }
        return runCatching { SleepStore.json.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), raw) }.getOrDefault(emptyMap())
    }

    private fun markRang(context: Context, alarmId: String, atMillis: Long) {
        val rang = runCatching {
            prefs(context).getString(KEY_RANG, null)?.let { SleepStore.json.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), it) }
        }.getOrNull().orEmpty() + (alarmId to atMillis)
        runCatching { prefs(context).edit { putString(KEY_RANG, SleepStore.json.encodeToString(MapSerializer(String.serializer(), Long.serializer()), rang)) } }
    }

    /** A wake alarm fired while the phone is still locked. */
    @Synchronized
    fun ring(context: Context, alarmId: String) {
        val app = context.applicationContext
        val ring = read(app).firstOrNull { it.alarmId == alarmId } ?: run {
            Log.w(TAG, "locked ring for an unknown alarm")
            return
        }
        if (current != null) return
        current = ring
        markRang(app, ring.alarmId, System.currentTimeMillis())
        val session = SleepSession.Ringing(
            alarmId = ring.alarmId, label = ring.label, startedAtMillis = System.currentTimeMillis(),
            output = AlarmOutput.PHONE, phoneFallbackReason = app.getString(R.string.sleep_rt_fallback_locked),
            canSnooze = ring.snoozeMinutes != null, snoozeMinutes = ring.snoozeMinutes,
        )
        _state.value = session
        if (!SleepService.start(app)) {
            // No foreground service: the full-screen alarm screen (a visible activity) is what lets it sound.
            val notifications = SleepNotifications(app).also { it.ensureChannels() }
            runCatching { app.getSystemService(NotificationManager::class.java)?.notify(SleepNotifications.ID_ALARM, notifications.alarm(session)) }
        }
        val fadeMs = ring.fadeInSeconds.coerceAtLeast(1) * 1_000L
        player = AlarmPlayer(app).also {
            it.start(ring.toneId, headset = null, speaker = null, playOnSpeaker = true, holdWake = true) { elapsed ->
                START_GAIN + (1f - START_GAIN) * Fades.fadeIn(elapsed, fadeMs)
            }
            if (ring.vibrate) it.vibrate(true)
        }
        limit = scope.launch {
            delay(RING_LIMIT_MS)
            stop(app)
        }
    }

    @Synchronized
    fun stop(context: Context) {
        limit?.cancel()
        player?.stop()
        player = null
        current = null
        _state.value = SleepSession.Idle
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(SleepNotifications.ID_ALARM) }
    }

    @Synchronized
    fun snooze(context: Context) {
        val ring = current ?: return
        val minutes = ring.snoozeMinutes ?: return stop(context)
        val at = System.currentTimeMillis() + minutes * 60_000L
        stop(context)
        // The snoozed ring comes back through the receiver: still locked → here again (from the
        // mirror); unlocked → the app, which took the snooze over at unlock ([consumeSnooze]).
        mirror(context, read(context).filterNot { it.alarmId == ring.alarmId } + ring.copy(atMillis = at))
        runCatching { prefs(context).edit { putString(KEY_SNOOZE, "${ring.alarmId}|$at") } }
        SleepScheduler(context).armAlarm(SleepAlarmReceiver.KIND_SNOOZE, ring.alarmId, at)
        _state.value = SleepSession.Snoozed(ring.alarmId, ring.label, at)
    }

    /** A snooze taken while locked, as (alarm id, ring time); cleared by reading. */
    fun consumeSnooze(context: Context): Pair<String, Long>? {
        val raw = runCatching { prefs(context).getString(KEY_SNOOZE, null) }.getOrNull() ?: return null
        runCatching { prefs(context).edit { remove(KEY_SNOOZE) } }
        val id = raw.substringBefore('|')
        val at = raw.substringAfter('|').toLongOrNull() ?: return null
        return id to at
    }

    private const val JUST_MISSED_MS = 10 * 60_000L
}
