package dev.earshot.sleep

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dev.earshot.R
import dev.earshot.core.NightExtra
import dev.earshot.core.SleepPlan

/**
 * The phone half of the night extras: Do Not Disturb and the media volume cap (docs/sleep-mode.md
 * §6). Both remember what they changed in the [RestoreRecord] and put it back in the morning.
 *
 * Volume calls are silently ignored on Android 17 unless the app is visible or runs a foreground
 * service; the cap is applied and held while `SleepService` runs.
 */
class PhoneNightExtras(private val context: Context, private val text: SleepText) {
    private val audio: AudioManager? get() = context.getSystemService(AudioManager::class.java)
    private val notifications: NotificationManager? get() = context.getSystemService(NotificationManager::class.java)
    private var capWatcher: BroadcastReceiver? = null

    fun hasDndAccess(): Boolean = runCatching { notifications?.isNotificationPolicyAccessGranted == true }.getOrDefault(false)

    fun apply(plan: SleepPlan, record: RestoreRecord, notes: MutableList<StepNote>): RestoreRecord {
        var r = record
        if (NightExtra.DO_NOT_DISTURB in plan.nightExtras) {
            val nm = notifications
            if (nm == null || !hasDndAccess()) {
                notes += StepNote(text.get(R.string.sleep_rt_log_dnd_no_access), isProblem = true)
            } else {
                val before = nm.currentInterruptionFilter
                // Priority keeps the user's own exceptions (favourite contacts, repeat callers). But
                // the user may have taken alarms out of those exceptions, and Priority would then
                // mute Earshot's own alarm: "alarms only" lets alarms through whatever the settings.
                // Media too: without it Priority would also silence the night's own sounds.
                val needed = NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS or NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA
                val allowed = runCatching { nm.notificationPolicy.priorityCategories and needed == needed }.getOrDefault(false)
                val filter = if (allowed) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALARMS
                if (before == NotificationManager.INTERRUPTION_FILTER_ALL || before == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
                    runCatching { nm.setInterruptionFilter(filter) }
                        .onSuccess {
                            r = r.copy(interruptionFilter = before, ourInterruptionFilter = filter)
                            notes += StepNote(text.get(R.string.sleep_rt_log_dnd_on))
                        }
                        .onFailure { notes += StepNote(text.get(R.string.sleep_rt_log_dnd_failed), isProblem = true) }
                }
            }
        }
        if (NightExtra.VOLUME_CAP in plan.nightExtras) {
            val am = audio
            if (am != null) {
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val cap = capIndex(max, plan.volumeCapPercent)
                val before = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (before > cap) {
                    if (r.musicVolume == null) r = r.copy(musicVolume = before, musicVolumeOutput = mediaOutputKey())
                    runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, cap, 0) }
                }
                notes += StepNote(text.get(R.string.sleep_rt_log_volume_capped, plan.volumeCapPercent))
            }
        }
        return r
    }

    /** Holds the cap while the night runs: any volume-up past it is brought back down. */
    fun holdCap(percent: Int) {
        releaseCap()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getIntExtra(EXTRA_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
                val am = audio ?: return
                val cap = capIndex(am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), percent)
                if (am.getStreamVolume(AudioManager.STREAM_MUSIC) > cap) runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, cap, 0) }
            }
        }
        runCatching {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_VOLUME_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            capWatcher = receiver
        }.onFailure { Log.w(TAG, "volume watcher: ${it.message}") }
    }

    fun releaseCap() {
        capWatcher?.let { runCatching { context.unregisterReceiver(it) } }
        capWatcher = null
    }

    /**
     * Puts back Do Not Disturb and the volume. Returns the record without what was put back; the
     * volume stays pending when its output isn't playing or [canTouchVolume] is false.
     */
    fun restore(record: RestoreRecord, notes: MutableList<StepNote>, canTouchVolume: Boolean): RestoreRecord {
        releaseCap()
        record.interruptionFilter?.let { before ->
            // Only if it is still ours: the user may have changed Do Not Disturb themselves since.
            val nm = notifications
            val ours = record.ourInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_PRIORITY
            if (nm != null && hasDndAccess() && nm.currentInterruptionFilter == ours) {
                runCatching { nm.setInterruptionFilter(before) }
            }
        }
        val volumeBack = record.musicVolume == null || restoreMusicVolume(record, canTouchVolume)
        if (record.phonePart) notes += StepNote(text.get(R.string.sleep_rt_log_phone_restored))
        val left = record.copy(interruptionFilter = null, ourInterruptionFilter = null)
        return if (volumeBack) left.copy(musicVolume = null, musicVolumeOutput = null) else left
    }

    /**
     * Puts the media volume back, but only on the output it was taken from: Android keeps one
     * media volume per output, and the headset's level on the phone speaker could be loud. True
     * when it was put back; false keeps it pending (another output plays now, or [canTouchVolume]
     * is false: Android 17 ignores volume calls from an app with nothing in the foreground).
     */
    fun restoreMusicVolume(record: RestoreRecord, canTouchVolume: Boolean): Boolean {
        val before = record.musicVolume ?: return true
        val am = audio ?: return false
        if (!canTouchVolume) return false
        if (record.musicVolumeOutput != null && mediaOutputKey() != record.musicVolumeOutput) {
            Log.i(TAG, "media volume waits for its output")
            return false
        }
        val current = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
        if (!runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0) }.isSuccess) return false
        // Android 17 ignores a volume call it doesn't allow without a word. If the caller judged
        // the rule wrong (a service's while-in-use status can't be read, so only from target 37),
        // a volume that didn't move at all stays pending instead of being dropped. One that moved
        // part of the way (a safe-volume limit) counts as put back. Below target 37 the caller
        // knows its foreground services, so an unmoved volume there is a cap, not a refusal.
        val misjudgable = BackgroundAudio.needsWhileInUse(Build.VERSION.SDK_INT, context.applicationInfo.targetSdkVersion)
        if (misjudgable && current != null && current != before) {
            val after = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
            if (after == current) {
                Log.i(TAG, "media volume change ignored by Android; it waits for Earshot to be opened")
                return false
            }
        }
        return true
    }

    /** "type:address" of the output media plays on now; the phone speaker when nothing better is known. */
    fun mediaOutputKey(): String? {
        val am = audio ?: return null
        val device: AudioDeviceInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { am.getAudioDevicesForAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()).firstOrNull() }.getOrNull()
        } else {
            val outputs = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }.getOrDefault(emptyList())
            outputs.firstOrNull { it.type in BLUETOOTH_OUTPUTS } ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        }
        return device?.let { "${it.type}:${it.address}" }
    }

    companion object {
        private const val TAG = "Earshot/Sleep"
        // Not public SDK constants, but the broadcast every volume UI relies on.
        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"

        private val BLUETOOTH_OUTPUTS = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_HEARING_AID, 26, 27)

        fun capIndex(max: Int, percent: Int): Int = (max * percent.coerceIn(0, 100) / 100).coerceIn(1, max.coerceAtLeast(1))
    }
}
