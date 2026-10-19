package dev.earshot.shortcut

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * The detector's Android-side sources. They work for any headset, Bose or not, connected to
 * Earshot or not, and need no permission: Android only lets apps see the *effects* of the
 * headset's play/pause and volume controls (AVRCP goes from the Bluetooth stack to the player).
 */
object AndroidControlFeeds {

    fun all(context: Context, clock: () -> Long = System::currentTimeMillis): List<ControlSignalFeed> {
        val app = context.applicationContext
        return listOf(playback(app, clock), musicVolume(app, clock), calls(app, clock))
    }

    /**
     * "A player started or stopped": [AudioManager.AudioPlaybackCallback] says when players
     * change, [AudioManager.isMusicActive] whether music is playing. Rechecked a moment later
     * because the music stream stays "active" briefly after a pause.
     */
    private fun playback(context: Context, clock: () -> Long) = ControlSignalFeed { emit ->
        val am = context.getSystemService(AudioManager::class.java) ?: return@ControlSignalFeed AutoCloseable { }
        val main = Handler(Looper.getMainLooper())
        var last = am.isMusicActive
        val check = Runnable {
            val now = am.isMusicActive
            if (now != last) {
                last = now
                emit(ControlSignal.PlayState(now, SignalOrigin.PHONE, clock()))
            }
        }
        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                main.removeCallbacks(check)
                check.run()
                RECHECK_MS.forEach { main.postDelayed(check, it) }
            }
        }
        am.registerAudioPlaybackCallback(callback, main)
        AutoCloseable {
            am.unregisterAudioPlaybackCallback(callback)
            main.removeCallbacks(check)
        }
    }

    /**
     * Music-stream volume: Android's `VOLUME_CHANGED_ACTION` fires for every index change,
     * including AVRCP absolute volume from the headset. The level is read back and compared, so
     * a repeated or forged broadcast adds nothing. (Earshot itself only changes volume during
     * calls, and the detector drops volume while a call is active.)
     */
    private fun musicVolume(context: Context, clock: () -> Long) = ControlSignalFeed { emit ->
        val am = context.getSystemService(AudioManager::class.java) ?: return@ControlSignalFeed AutoCloseable { }
        var last = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val stream = intent?.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1) ?: return
                if (stream != AudioManager.STREAM_MUSIC) return
                val now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (now == last) return
                emit(ControlSignal.Volume(last, now, SignalOrigin.PHONE, clock()))
                last = now
            }
        }
        // Sent by the system for every volume change: exported, since some builds deliver it
        // like any other app broadcast. The read-back above makes a forged one harmless.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_VOLUME_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        AutoCloseable { runCatching { context.unregisterReceiver(receiver) } }
    }

    /**
     * Ringing or in a call (phone or VoIP), from the audio mode: no phone permission needed,
     * and it is exactly when the headset's buttons answer, hang up and set the call volume.
     */
    private fun calls(context: Context, clock: () -> Long) = ControlSignalFeed { emit ->
        val am = context.getSystemService(AudioManager::class.java) ?: return@ControlSignalFeed AutoCloseable { }
        var last: Boolean? = null
        fun update(mode: Int) {
            val active = isCallMode(mode)
            if (active != last) {
                last = active
                // The first reading only matters when a call is already on.
                emit(ControlSignal.CallState(active, clock()))
            }
        }
        update(am.mode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val listener = AudioManager.OnModeChangedListener { update(it) }
            am.addOnModeChangedListener(context.mainExecutor, listener)
            AutoCloseable { am.removeOnModeChangedListener(listener) }
        } else {
            val main = Handler(Looper.getMainLooper())
            val poll = object : Runnable {
                override fun run() { update(am.mode); main.postDelayed(this, MODE_POLL_MS) }
            }
            main.postDelayed(poll, MODE_POLL_MS)
            AutoCloseable { main.removeCallbacks(poll) }
        }
    }

    /** Ringtone, telephony call, VoIP / communication, call screening and redirects. */
    fun isCallMode(mode: Int): Boolean = mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL ||
        mode == AudioManager.MODE_IN_COMMUNICATION || mode == MODE_CALL_SCREENING ||
        mode == MODE_CALL_REDIRECT || mode == MODE_COMMUNICATION_REDIRECT

    private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
    private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
    private val RECHECK_MS = longArrayOf(700L, 2_000L)
    private const val MODE_POLL_MS = 1_000L
    // AudioManager constants newer than minSdk 28 (values from the SDK).
    private const val MODE_CALL_SCREENING = 4
    private const val MODE_CALL_REDIRECT = 5
    private const val MODE_COMMUNICATION_REDIRECT = 6
}
