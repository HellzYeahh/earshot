package dev.earshot.sleep

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * "Also pause other apps at the end": the sleep timer for a podcast or an audiobook
 * (docs/sleep-mode.md §4.6). During the fade the media volume steps down from the volume the
 * night saved ([RestoreRecord.musicVolume]); at the end the player is asked to pause. Putting the
 * volume back is the repository's job, from that saved value, so it survives a process restart.
 *
 * Pausing another app has no single reliable API: taking audio focus pauses players that honour
 * focus, a media PAUSE key covers the ones that listen to the active media session. Some players
 * ignore both. Needs the foreground service on Android 17 (volume and focus calls from the
 * background are ignored).
 */
class OtherAudioPauser(private val context: Context) {
    private val audio: AudioManager? get() = context.getSystemService(AudioManager::class.java)

    fun currentVolume(): Int? = audio?.getStreamVolume(AudioManager.STREAM_MUSIC)

    /** Called every few seconds during the fade with its progress 0..1 and the volume it started from. */
    fun fadeStep(progress: Float, startIndex: Int) {
        val am = audio ?: return
        val target = (startIndex * Fades.fadeOut((progress.coerceIn(0f, 1f) * 1000).toLong(), 1000)).roundToInt()
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) > target) runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
    }

    /**
     * Asks whatever plays to pause. Returns true when nothing plays a few seconds later, so the
     * volume can safely go back up now; false means a player ignored both, and turning the volume
     * up would play it at full volume at 3 am.
     */
    suspend fun pauseOthers(): Boolean {
        val am = audio ?: return true
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { }
            .build()
        runCatching { am.requestAudioFocus(focus) }
        val now = SystemClock.uptimeMillis()
        runCatching {
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
        }
        delay(SETTLE_MS)
        runCatching { am.abandonAudioFocusRequest(focus) }
        return !runCatching { am.isMusicActive }.getOrDefault(false)
    }

    private companion object { const val SETTLE_MS = 3_000L }
}
