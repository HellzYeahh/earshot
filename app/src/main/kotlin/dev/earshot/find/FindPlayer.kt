package dev.earshot.find

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.util.Log
import dev.earshot.bt.DeviceFinder
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.HeadphoneRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What Find my earbuds is doing: ringing a side, idle, or why the last attempt couldn't ring. */
data class FindState(val ringing: FindSide? = null, val problem: String? = null)

/**
 * Find my earbuds (roadmap B6, item 4): plays [FindTone] into the shown headset, one side or both,
 * for up to [MAX_RING_MS]. Works for any headset Android has an audio output for; nothing is sent
 * over a vendor protocol.
 *
 * - The tone goes to the headset's own output only ([FindOutput]): routed with
 *   `setPreferredDevice`, checked after it starts and on every period. If Android routes it
 *   anywhere else (the phone speaker, a car), it stops at once.
 * - Media usage, like Earshot's other sounds: it follows the music to the headset. The volume is
 *   the media volume times the tone's own ramp; the stream volume is never touched. Never
 *   spatialised (Android 12L+), so a one-sided tone stays on its side.
 * - Start it only from a visible screen or a notification tap, and keep the screen visible while
 *   it rings (the Find screen keeps the display on): Android 17 silences `AudioTrack` playback from
 *   an app that is neither visible nor in a qualifying foreground service.
 * - Refused while the phone is in a call or recording: the audio mode is not normal then and media
 *   is paused or rerouted.
 *
 * One ring at a time, without races: [start] and [stop] take one lock, each new ring waits for the
 * previous one to finish before it opens its track, [stop] pauses the live track so a blocked write
 * returns at once, and only the newest ring may write [state].
 */
class FindPlayer(
    private val context: Context,
    private val headphones: HeadphoneRepository,
    private val finder: DeviceFinder,
    /** The app's scope: a ring started from the notification is not tied to a ViewModel. */
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(FindState())
    val state: StateFlow<FindState> = _state.asStateFlow()

    private val lock = Any()

    /** The newest ring request; older ones are cancelled. Guarded by [lock]. */
    private var active: Job? = null

    /** Bumped for every request; a ring writes [state] only while it is still the newest. Guarded by [lock]. */
    private var generation = 0

    /** The track playing now, so [stop] can silence it without waiting for the write loop. */
    @Volatile
    private var liveTrack: AudioTrack? = null

    private val audioManager: AudioManager? get() = context.getSystemService(AudioManager::class.java)

    /** Ring [side] until [stop], the time limit, or a routing change. A failure lands in [FindState.problem]. */
    fun start(side: FindSide) {
        synchronized(lock) {
            val previous = active
            val gen = ++generation
            active = scope.launch(Dispatchers.IO) {
                previous?.cancelAndJoin()
                try {
                    play(side, preflight(), gen)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HeadphoneCommandException) {
                    Log.i(TAG, "can't ring: ${e.message}")
                    publish(gen, FindState(problem = e.message))
                } catch (e: Exception) {
                    // AudioTrack.Builder throws (UnsupportedOperationException) instead of returning a
                    // dead track, e.g. while audioserver restarts: a message, never a crash.
                    Log.w(TAG, "ring failed: ${e.javaClass.simpleName}")
                    publish(gen, FindState(problem = "Couldn't open the audio output. Try again in a moment."))
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            active?.cancel(CancellationException("stop"))
            active = null
            generation++
            _state.value = FindState()
        }
        // A paused stream track makes the blocked write() return, so the chirp ends now.
        liveTrack?.let { runCatching { it.pause(); it.flush() } }
    }

    private fun publish(gen: Int, value: FindState) {
        synchronized(lock) { if (gen == generation) _state.value = value }
    }

    /** The headset's output, or the reason it can't ring now. */
    private fun preflight(): AudioDeviceInfo {
        val hp = headphones.state.value
        val address = hp.connection.deviceAddress ?: throw HeadphoneCommandException("No headset to ring yet: connect one first.")
        val name = hp.connection.deviceName ?: "The headset"
        if (!hp.isAudioActive) {
            throw HeadphoneCommandException("$name isn't connected to the phone. A bud in its closed case is usually switched off, so look for the case.")
        }
        val am = audioManager ?: throw HeadphoneCommandException("Audio isn't available.")
        if (am.mode != AudioManager.MODE_NORMAL) {
            throw HeadphoneCommandException("The phone is in a call or recording. Try again when it's over.")
        }
        val outputs = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }.getOrDefault(emptyList())
        val mediaRoute = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { am.getAudioDevicesForAttributes(attributes()).map { it.id }.toSet() }.getOrDefault(emptySet())
        } else {
            emptySet()
        }
        val twins = runCatching { finder.twinsOf(address).map { it.address } }.getOrDefault(emptyList())
        val pick = FindOutput.pick(outputs.map { OutputCandidate(it.id, it.type, it.address) }, address, twins, mediaRoute)
            ?: throw HeadphoneCommandException("Android isn't sending audio to $name right now (another device may have it). Play something on $name, then try again.")
        return outputs.first { it.id == pick.id }
    }

    private suspend fun play(side: FindSide, output: AudioDeviceInfo, gen: Int) {
        var track: AudioTrack? = null
        try {
            val period = FindTone.period(side, gain = 1.0)
            val minBuf = AudioTrack.getMinBufferSize(FindTone.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(attributes())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(FindTone.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf, period.size * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (!track.setPreferredDevice(output)) throw HeadphoneCommandException("Android wouldn't send the tone to the headset.")
            currentCoroutineContext().ensureActive()
            liveTrack = track
            publish(gen, FindState(ringing = side))
            Log.i(TAG, "ringing ${side.name.lowercase()} on output ${output.id} (type ${output.type})")
            track.play()
            val start = SystemClock.elapsedRealtime()
            var checked = false
            while (true) {
                val elapsed = SystemClock.elapsedRealtime() - start
                if (elapsed >= MAX_RING_MS) break
                val pcm = FindTone.period(side, FindTone.gainAt(elapsed))
                val written = track.write(pcm, 0, pcm.size)
                currentCoroutineContext().ensureActive()
                if (written < 0) throw HeadphoneCommandException("The audio output stopped.")
                // Routing settles once playback starts; after that, any other output ends the ring.
                val routed = track.routedDevice
                if (routed != null && routed.id != output.id) {
                    Log.w(TAG, "tone routed to output ${routed.id} (type ${routed.type}) instead; stopping")
                    throw HeadphoneCommandException("The headset stopped taking audio, so the tone was stopped before it reached another speaker.")
                }
                if (routed != null) checked = true
            }
            if (!checked) Log.w(TAG, "Android never reported where the tone went")
            publish(gen, FindState())
        } finally {
            liveTrack = null
            runCatching { track?.pause(); track?.flush() }
            runCatching { track?.release() }
            Log.d(TAG, "ring ended")
        }
    }

    companion object {
        private const val TAG = "Earshot/Find"

        /** Long enough to walk to the sofa; short enough not to drain a lost bud. */
        const val MAX_RING_MS = 30_000L

        // Media: it follows the music to the headset; sonification may also ring the phone speaker.
        private fun attributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) setSpatializationBehavior(AudioAttributes.SPATIALIZATION_BEHAVIOR_NEVER) }
            .build()
    }
}
