package dev.earshot.sleep

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import dev.earshot.core.SleepAlarm
import dev.earshot.dsp.sleep.AlarmTone
import dev.earshot.dsp.sleep.AlarmTones
import dev.earshot.dsp.sleep.RecordingLibrary
import dev.earshot.dsp.sleep.SleepSound
import dev.earshot.dsp.sleep.SleepSounds
import dev.earshot.dsp.sleep.SoundSource

/**
 * Rings an alarm (docs/sleep-mode.md §4.7) on up to two outputs at once: the headset's own output
 * and the phone speaker, each a separate stream pinned with `setPreferredDevice`. Alarm usage
 * throughout: it follows the alarm volume, gets through Do Not Disturb, and is the stream Android
 * 17 lets an exact-alarm app play from the background.
 *
 * [gain] (0..1, from the time ringing started) carries the wake-up window and the fade-in; it is
 * read about ten times a second. A tone id the build doesn't know falls back to the chime.
 */
class AlarmPlayer(private val context: Context) {
    private val audio: AudioManager? get() = context.getSystemService(AudioManager::class.java)
    private val outputs = ArrayList<Output>()
    @Volatile private var startedAt = 0L
    private var vibrating = false
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * [holdWake]: keep the CPU up while it rings (a real alarm), so the phone fallback and the
     * ring limit run on time even when no audio keeps the phone awake. Released by [stop].
     */
    @Synchronized
    fun start(toneId: String, headset: AudioDeviceInfo?, speaker: AudioDeviceInfo?, playOnSpeaker: Boolean, holdWake: Boolean = false, gain: (Long) -> Float) {
        stop()
        startedAt = SystemClock.elapsedRealtime()
        if (holdWake) acquireWake()
        if (headset != null) outputs += open(toneId, headset, gain)
        if (playOnSpeaker) outputs += open(toneId, speaker, gain)
    }

    /** The phone joins in (fallback); no-op when it already rings there. */
    @Synchronized
    fun addSpeaker(toneId: String, speaker: AudioDeviceInfo?, gain: (Long) -> Float) {
        if (outputs.any { it.onSpeaker }) return
        outputs += open(toneId, speaker, gain)
    }

    /** True when the headset stream ended by itself (couldn't open, the output closed). */
    fun headsetFailed(): Boolean = synchronized(this) { outputs.firstOrNull { !it.onSpeaker } }?.failed == true

    /**
     * False when the headset stream failed (it couldn't open, or the output closed) or Android
     * plays it somewhere else: the phone must take over at once.
     */
    fun headsetRoutedOk(headset: AudioDeviceInfo?): Boolean {
        val out = synchronized(this) { outputs.firstOrNull { !it.onSpeaker } } ?: return headset == null
        if (out.failed) return false
        val routed = out.routedDevice() ?: return true // not settled yet
        return headset == null || routed.id == headset.id
    }

    fun vibrate(on: Boolean) {
        val vibrator = vibrator() ?: return
        if (on && !vibrating) {
            runCatching {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 600, 900), 0),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
                )
                vibrating = true
            }
        } else if (!on && vibrating) {
            runCatching { vibrator.cancel() }
            vibrating = false
        }
    }

    @Synchronized
    fun stop() {
        outputs.forEach { it.close() }
        outputs.clear()
        vibrate(false)
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    private fun acquireWake() {
        val pm = context.getSystemService(PowerManager::class.java) ?: return
        wakeLock = runCatching {
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Earshot:alarm").apply {
                setReferenceCounted(false)
                acquire(WAKE_LIMIT_MS)
            }
        }.getOrNull()
    }

    val isRinging: Boolean get() = synchronized(this) { outputs.isNotEmpty() }

    /** One stream. [onSpeaker] marks the phone's. */
    private interface Output {
        val onSpeaker: Boolean
        /** The stream ended by itself (couldn't open, the output closed, the player errored). */
        val failed: Boolean
        fun routedDevice(): AudioDeviceInfo?
        fun close()
    }

    private fun open(toneId: String, device: AudioDeviceInfo?, gain: (Long) -> Float): Output {
        val onSpeaker = device == null || device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        if (toneId == SleepAlarm.TONE_SYSTEM) {
            systemTone(device, onSpeaker, gain)?.let { return it }
        }
        return SynthOutput(sourceFor(toneId), device, onSpeaker, gain).also { it.start() }
    }

    private fun sourceFor(toneId: String): SoundSource {
        val seed = SystemClock.elapsedRealtimeNanos()
        // A recording (birdsong, or a sleep sound as the alarm) is decoded first; failing that,
        // the tone plays synthesised. Never on an audio thread: this runs before the stream opens.
        val recordings = BundledRecordings.get(context)
        if (toneId.startsWith(SleepAlarm.TONE_SOUND_PREFIX)) {
            SleepSound.fromStoredId(toneId.removePrefix(SleepAlarm.TONE_SOUND_PREFIX))?.let { sound ->
                recordings.preload(listOf(sound.storedId))
                return SleepSounds.create(sound, seed)
            }
        }
        val tone = AlarmTone.fromStoredId(toneId) ?: AlarmTone.CHIME
        recordings.preload(listOf(RecordingLibrary.ALARM_PREFIX + tone.storedId))
        return AlarmTones.create(tone, seed)
    }

    private inner class SynthOutput(
        private val source: SoundSource,
        private val device: AudioDeviceInfo?,
        override val onSpeaker: Boolean,
        private val gain: (Long) -> Float,
    ) : Thread("Earshot-alarm"), Output {
        @Volatile private var running = true
        @Volatile private var track: AudioTrack? = null
        @Volatile override var failed = false

        override fun routedDevice(): AudioDeviceInfo? = runCatching { track?.routedDevice }.getOrNull()

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            try {
                val t = AudioTrack.Builder()
                    .setAudioAttributes(alarmAttributes())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(SleepSounds.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(SleepSounds.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT), SleepSounds.SAMPLE_RATE * 2 * 4 / 5))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track = t
                if (device != null && !t.setPreferredDevice(device)) Log.w(TAG, "alarm: preferred device refused")
                val frames = SleepSounds.SAMPLE_RATE / 10
                val buffer = FloatArray(frames * 2)
                t.play()
                while (running) {
                    source.render(buffer, 0, frames)
                    val g = gain(SystemClock.elapsedRealtime() - startedAt).coerceIn(0f, 1f) * PEAK
                    for (i in buffer.indices) buffer[i] *= g
                    if (t.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING) < 0) {
                        if (running) failed = true
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "alarm stream failed: ${e.javaClass.simpleName}: ${e.message}")
                if (running) failed = true
            } finally {
                runCatching { track?.pause(); track?.flush() }
                runCatching { track?.release() }
            }
        }

        override fun close() {
            running = false
            runCatching { track?.pause() } // a blocked write returns at once
        }
    }

    /** The phone's own alarm sound through MediaPlayer, looped; null when there is none. */
    private fun systemTone(device: AudioDeviceInfo?, onSpeaker: Boolean, gain: (Long) -> Float): Output? {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: return null
        var errored = false
        val player = runCatching {
            MediaPlayer().apply {
                setOnErrorListener { _, _, _ -> errored = true; true }
                setAudioAttributes(alarmAttributes())
                setDataSource(context, uri)
                isLooping = true
                if (device != null) setPreferredDevice(device)
                prepare()
                setVolume(0f, 0f)
                start()
            }
        }.getOrElse {
            Log.w(TAG, "system alarm tone failed: ${it.javaClass.simpleName}")
            return null
        }
        val ramp = object : Thread("Earshot-alarm-ramp") {
            @Volatile var running = true
            override fun run() {
                while (running) {
                    val g = gain(SystemClock.elapsedRealtime() - startedAt).coerceIn(0f, 1f)
                    runCatching { player.setVolume(g, g) }
                    try { sleep(100) } catch (_: InterruptedException) { return }
                }
            }
        }.also { it.start() }
        return object : Output {
            override val onSpeaker = onSpeaker
            override val failed: Boolean get() = errored
            override fun routedDevice(): AudioDeviceInfo? = runCatching { player.routedDevice }.getOrNull()
            override fun close() {
                ramp.running = false
                ramp.interrupt()
                runCatching { player.stop() }
                runCatching { player.release() }
            }
        }
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)

    /** Output devices as [AlarmRouting] wants them, plus the lookup back to the real objects. */
    fun outputs(): List<AudioDeviceInfo> = runCatching { audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.toList() }.getOrNull().orEmpty()

    companion object {
        private const val TAG = "Earshot/Alarm"
        /** Headroom under full scale for the synthesised tones. */
        private const val PEAK = 0.9f
        /** Longer than the ring limit plus a wake-up window: a safety cap if stop() is never reached. */
        private const val WAKE_LIMIT_MS = 50 * 60_000L

        private fun alarmAttributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
