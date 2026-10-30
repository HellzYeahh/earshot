package dev.earshot.sleep

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import dev.earshot.bt.DeviceFinder
import dev.earshot.core.CustomSound
import dev.earshot.core.SleepLayer
import dev.earshot.core.SoundSide
import dev.earshot.dsp.sleep.ChannelMode
import dev.earshot.dsp.sleep.SleepSound
import dev.earshot.dsp.sleep.SleepSounds
import dev.earshot.dsp.sleep.SoundLayer
import dev.earshot.dsp.sleep.SoundMixer
import dev.earshot.find.FindOutput
import dev.earshot.find.OutputCandidate

/** Why the sounds stopped by themselves. */
enum class PlayerStop { HEADSET_GONE, OUTPUT_FAILED }

/**
 * Plays the sleep sounds: the `:dsp` [SoundMixer] pulled in 50 ms blocks into a float
 * `AudioTrack` on its own thread, all night.
 *
 * - Media usage, so it follows the media volume and mixes with a podcast (no audio focus is taken:
 *   "rain under an audiobook" is a normal way to fall asleep). It goes silent while a call or a
 *   recording holds the audio mode.
 * - Routed to the shown headset's own output when Android has one ([FindOutput], strict). If the
 *   night started on the headset and Android moves the audio anywhere else (the buds died or
 *   disconnected), it stops instead of playing rain out of the phone speaker at 3 am.
 * - Power-saving performance mode and a deep buffer, so the CPU can sleep between blocks.
 * - Android 17 silences an app's audio unless it is visible or runs a foreground service: the
 *   session runs `SleepService`; a preview only plays while the tab is on screen.
 *
 * [gain] is read once per block on the player thread (the night's envelope times the volume); the
 * mixer smooths the steps.
 */
class SleepSoundPlayer(private val context: Context, private val finder: DeviceFinder) {
    private val audioManager: AudioManager? get() = context.getSystemService(AudioManager::class.java)

    /**
     * Whether [address] has an audio output right now (its own or its LE twin's), as the night's
     * player would route to. Demo headsets have made-up addresses with no output: their sounds play
     * on the phone instead of stopping at once as "headset gone" (Play's reviewers use demo mode).
     */
    fun hasHeadsetOutput(address: String): Boolean {
        val am = audioManager ?: return false
        val outputs = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }.getOrDefault(emptyList())
        val twins = runCatching { finder.twinsOf(address).map { it.address } }.getOrDefault(emptyList())
        return FindOutput.pick(outputs.map { OutputCandidate(it.id, it.type, it.address) }, address, twins) != null
    }

    /** The bundled recordings; decoded here, off the audio thread, before the mixer may ask. */
    private val recordings = BundledRecordings.get(context)
    private val loader = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "Earshot-sleep-decode") }

    @Volatile private var worker: Worker? = null

    val isPlaying: Boolean get() = worker?.isAlive == true

    /**
     * Starts (or replaces) playback. With [requireHeadset] the sounds play only on the headset's
     * own output: no output at start, or Android moving them elsewhere, ends playback with
     * [PlayerStop.HEADSET_GONE] instead of falling back to the phone speaker.
     */
    @Synchronized
    fun start(
        layers: List<SleepLayer>,
        side: SoundSide,
        headsetAddress: String?,
        requireHeadset: Boolean,
        gain: () -> Float,
        onStopped: (PlayerStop) -> Unit = {},
    ) {
        worker?.finish(fadeMs = 300)
        worker = Worker(layers, side, headsetAddress, requireHeadset, gain, onStopped).also { it.start() }
    }

    /** Changes the mix or the side without restarting (the mixer cross-fades). */
    fun update(layers: List<SleepLayer>, side: SoundSide) {
        val w = worker ?: return
        w.pendingSide = side
        // A newly picked recording is decoded first (a few hundred ms), then handed to the mixer:
        // decoding on the audio thread would stall it.
        loader.execute {
            recordings.preload(layers.map { it.soundId })
            if (worker === w) w.pendingLayers = layers
        }
    }

    /** Fades out over [fadeMs] and releases the track; returns at once. */
    @Synchronized
    fun stop(fadeMs: Long = 1_500) {
        worker?.finish(fadeMs)
        worker = null
    }

    private inner class Worker(
        @Volatile var pendingLayers: List<SleepLayer>?,
        @Volatile var pendingSide: SoundSide?,
        private val headsetAddress: String?,
        private val requireHeadset: Boolean,
        private val gain: () -> Float,
        private val onStopped: (PlayerStop) -> Unit,
    ) : Thread("Earshot-sleep-sounds") {
        @Volatile private var stopAtMs: Long = Long.MAX_VALUE
        @Volatile private var cancelled = false
        /** Set from the main thread the moment the headset goes (noisy broadcast, routing change). */
        @Volatile private var headsetLost = false

        // Touched only on this thread: the mixer isn't thread-safe.
        private val mixer = SoundMixer(seed = SystemClock.elapsedRealtimeNanos())
        private val startedAt = SystemClock.elapsedRealtime()

        /** The loop sees [cancelled], ramps the mixer down and stops at [stopAtMs]. */
        fun finish(fadeMs: Long) {
            cancelled = true
            stopAtMs = SystemClock.elapsedRealtime() + fadeMs
        }

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            var track: AudioTrack? = null
            var noisy: BroadcastReceiver? = null
            var routing: AudioRouting.OnRoutingChangedListener? = null
            try {
                // This thread hasn't started playing yet: decoding here only delays the first sound.
                pendingLayers?.let { layers -> recordings.preload(layers.map { it.soundId }) }
                val output = headsetOutput()
                if (output == null && requireHeadset) {
                    Log.i(TAG, "no output for the headset; not playing on the phone instead")
                    if (!cancelled) onStopped(PlayerStop.HEADSET_GONE)
                    return
                }
                val t = buildTrack()
                track = t
                if (output != null && !t.setPreferredDevice(output)) Log.w(TAG, "preferred device refused")
                if (output != null) {
                    // Android says "becoming noisy" just before it moves media to the speaker, and the
                    // routing callback fires on the move itself: pausing there keeps the deep buffer
                    // from playing on the speaker while the loop catches up.
                    noisy = object : BroadcastReceiver() {
                        override fun onReceive(c: Context, i: Intent) = loseHeadset(t)
                    }.also { ContextCompat.registerReceiver(context, it, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED) }
                    routing = AudioRouting.OnRoutingChangedListener { router ->
                        val routed = router.routedDevice
                        if (routed != null && routed.id != output.id && SystemClock.elapsedRealtime() - startedAt > ROUTE_SETTLE_MS) loseHeadset(t)
                    }.also { t.addOnRoutingChangedListener(it, Handler(Looper.getMainLooper())) }
                }
                val frames = SleepSounds.SAMPLE_RATE / BLOCKS_PER_SECOND
                val buffer = FloatArray(frames * 2)
                mixer.masterGain = 0f
                t.play()
                var lastCheck = 0L
                var routedOnce = false
                while (true) {
                    val now = SystemClock.elapsedRealtime()
                    if (now >= stopAtMs) break
                    if (headsetLost) {
                        Log.i(TAG, "headset gone; stopping the sounds")
                        if (!cancelled) onStopped(PlayerStop.HEADSET_GONE)
                        break
                    }
                    pendingLayers?.let { layers -> mixer.setLayers(layers.toSoundLayers()); pendingLayers = null }
                    pendingSide?.let { side -> mixer.channelMode = side.toChannelMode(); pendingSide = null }
                    if (cancelled) mixer.masterGain = 0f
                    if (now - lastCheck >= CHECK_EVERY_MS) {
                        lastCheck = now
                        if (!cancelled) mixer.masterGain = if (audioModeIsNormal()) gain().coerceIn(0f, 1f) else 0f
                        // The routed device settles after play(); once it has, any other output ends the night's sound.
                        val routed = t.routedDevice
                        if (output != null && routed != null) {
                            if (routed.id == output.id) routedOnce = true
                            else if (routedOnce || now - startedAt > ROUTE_SETTLE_MS) {
                                Log.i(TAG, "sounds routed to output ${routed.id} (type ${routed.type}), not the headset; stopping")
                                if (!cancelled) onStopped(PlayerStop.HEADSET_GONE)
                                break
                            }
                        }
                    }
                    mixer.render(buffer, frames)
                    val written = t.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) {
                        Log.w(TAG, "write failed: $written")
                        if (!cancelled) onStopped(PlayerStop.OUTPUT_FAILED)
                        break
                    }
                }
            } catch (e: Exception) {
                // AudioTrack.Builder throws instead of returning a dead track (audioserver restarting).
                Log.w(TAG, "playback failed: ${e.javaClass.simpleName}: ${e.message}")
                if (!cancelled) onStopped(PlayerStop.OUTPUT_FAILED)
            } finally {
                noisy?.let { runCatching { context.unregisterReceiver(it) } }
                routing?.let { listener -> runCatching { track?.removeOnRoutingChangedListener(listener) } }
                runCatching { track?.pause(); track?.flush() }
                runCatching { track?.release() }
            }
        }

        /** Main thread: silence at once (a paused track also unblocks the write), the loop then stops. */
        private fun loseHeadset(t: AudioTrack) {
            if (headsetLost || cancelled) return
            headsetLost = true
            runCatching { t.pause() }
        }

        private fun headsetOutput(): AudioDeviceInfo? {
            val address = headsetAddress ?: return null
            val am = audioManager ?: return null
            val outputs = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }.getOrDefault(emptyList())
            val twins = runCatching { finder.twinsOf(address).map { it.address } }.getOrDefault(emptyList())
            val pick = FindOutput.pick(outputs.map { OutputCandidate(it.id, it.type, it.address) }, address, twins) ?: return null
            return outputs.firstOrNull { it.id == pick.id }
        }

        private fun audioModeIsNormal(): Boolean = (audioManager?.mode ?: AudioManager.MODE_NORMAL) == AudioManager.MODE_NORMAL
    }

    private fun buildTrack(): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(SleepSounds.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minBuf = AudioTrack.getMinBufferSize(SleepSounds.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val deep = SleepSounds.SAMPLE_RATE * 2 * 4 * BUFFER_MS / 1000
        return AudioTrack.Builder()
            .setAudioAttributes(attributes())
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, deep))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
            .build()
    }

    companion object {
        private const val TAG = "Earshot/Sleep"
        private const val BLOCKS_PER_SECOND = 20
        private const val BUFFER_MS = 400
        private const val CHECK_EVERY_MS = 1_000L
        private const val ROUTE_SETTLE_MS = 3_000L

        // Never spatialised: a one-ear mix for side sleepers must stay on its side.
        private fun attributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) setSpatializationBehavior(AudioAttributes.SPATIALIZATION_BEHAVIOR_NEVER) }
            .build()

        // Built-in sounds and the user's own; an id this build doesn't know (a newer build's sound) is skipped.
        internal fun List<SleepLayer>.toSoundLayers(): List<SoundLayer> =
            filter { SleepSound.fromStoredId(it.soundId) != null || CustomSound.isCustom(it.soundId) }
                .map { SoundLayer(it.soundId, it.gain.coerceIn(0f, 1f)) }

        internal fun SoundSide.toChannelMode(): ChannelMode = when (this) {
            SoundSide.BOTH -> ChannelMode.STEREO
            SoundSide.MONO -> ChannelMode.MONO
            SoundSide.LEFT_ONLY -> ChannelMode.LEFT_ONLY
            SoundSide.RIGHT_ONLY -> ChannelMode.RIGHT_ONLY
        }
    }
}
