package dev.earshot.mic

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.MicCheckPhase
import dev.earshot.core.MicCheckRepository
import dev.earshot.core.MicCheckState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.abs

/**
 * "How do I sound on calls?" Records a few seconds from the headset mic over SCO, then plays it back
 * through normal media routing, i.e. over A2DP at music quality, so the user hears what the other
 * side of a call hears.
 *
 * Format: mono 16 kHz PCM16. SCO is 8 kHz (CVSD) or 16 kHz (mSBC/LC3-SWB); 16 kHz captures all of it
 * and keeps the file tiny (32 kB/s), stored raw in `cacheDir` and played by [AudioTrack], so no
 * container handling is needed.
 *
 * Only one job (recording or playback) exists at a time; [stop] and [discard] cancel it and the
 * job's `finally` releases every audio object and the SCO route, so leaving the screen can never
 * leave the phone stuck in call mode.
 */
class MicCheckRepositoryImpl(
    private val context: Context,
    @Suppress("unused") private val scope: CoroutineScope, // the contract hands us one; work runs in the callers' coroutines
) : MicCheckRepository {

    private val _state = MutableStateFlow(MicCheckState())
    override val state: StateFlow<MicCheckState> = _state

    private val router = HeadsetMicRouter(context)
    private val file: File get() = File(context.cacheDir, FILE_NAME)

    @Volatile private var activeJob: Job? = null

    override suspend fun record(maxSeconds: Int) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _state.value = MicCheckState(phase = MicCheckPhase.NO_PERMISSION, message = "Microphone permission is needed for the mic check.")
            return
        }
        cancelActive()
        _state.value = MicCheckState(phase = MicCheckPhase.RECORDING)
        runTracked { runRecording(maxSeconds.coerceIn(1, 60)) }
    }

    override suspend fun play() {
        val f = file
        if (!f.exists() || f.length() < BYTES_PER_SECOND / 10) throw HeadphoneCommandException("Nothing recorded yet.")
        cancelActive()
        _state.update { it.copy(phase = MicCheckPhase.PLAYING, playbackPositionSeconds = 0f, message = null) }
        runTracked { runPlayback(f) }
    }

    override fun stop() {
        activeJob?.cancel(CancellationException("stop"))
        _state.update { it.copy(phase = restingPhase(it), inputLevel = 0f, playbackPositionSeconds = 0f) }
    }

    override fun discard() {
        activeJob?.cancel(CancellationException("discard"))
        runCatching { file.delete() }
        _state.value = MicCheckState()
    }

    // ---- job plumbing ----------------------------------------------------------------------

    /**
     * Runs [block] as a child job of the caller so the `suspend` call returns when the work ends,
     * while [stop] can cancel just that child without failing the caller.
     */
    private suspend fun runTracked(block: suspend CoroutineScope.() -> Unit) {
        coroutineScope {
            val job = launch(Dispatchers.IO, block = block)
            activeJob = job
            job.join()
            if (activeJob === job) activeJob = null
        }
    }

    /**
     * Cancels the previous job and waits for its `finally` to run, so a new recording never races
     * the old one's release of the recorder and the SCO route. [stop] and [discard] only cancel.
     */
    private suspend fun cancelActive() {
        val previous = activeJob ?: return
        activeJob = null
        previous.cancel(CancellationException("superseded"))
        previous.join()
    }

    private fun restingPhase(s: MicCheckState) = if (s.recordedSeconds > 0f) MicCheckPhase.READY else MicCheckPhase.IDLE

    private fun fail(message: String, cause: Throwable? = null): Nothing {
        Log.w(TAG, message, cause)
        _state.update { it.copy(phase = MicCheckPhase.ERROR, inputLevel = 0f, message = message) }
        throw HeadphoneCommandException(message, cause)
    }

    // ---- recording -------------------------------------------------------------------------

    @SuppressLint("MissingPermission") // checked in record()
    private suspend fun CoroutineScope.runRecording(maxSeconds: Int) {
        val routed = router.engage()
        var recorder: AudioRecord? = null
        var out: FileOutputStream? = null
        var seconds = 0f
        try {
            recorder = openRecorder(router.scoInputDevice()) ?: fail("Couldn't open the microphone (is another app using it?).")
            out = FileOutputStream(file)
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) fail("The microphone refused to start.")

            val buffer = ShortArray(SAMPLE_RATE / 10) // 100 ms per chunk → ~10 Hz meter updates
            val bytes = ByteArray(buffer.size * 2)
            var headset: Boolean? = null
            val maxSamples = maxSeconds.toLong() * SAMPLE_RATE
            var samples = 0L
            while (isActive && samples < maxSamples) {
                val n = recorder.read(buffer, 0, buffer.size)
                if (n < 0) fail("Microphone read failed ($n).")
                if (n == 0) continue
                if (headset == null) {
                    // getRoutedDevice is authoritative once data flows; the router's view is the fallback.
                    headset = recorder.routedDevice?.let { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ?: (routed && router.isHeadsetRouteActive())
                }
                var peak = 0
                for (i in 0 until n) {
                    val s = buffer[i].toInt()
                    if (abs(s) > peak) peak = abs(s)
                    bytes[2 * i] = (s and 0xFF).toByte()
                    bytes[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
                }
                out.write(bytes, 0, n * 2)
                samples += n
                seconds = samples.toFloat() / SAMPLE_RATE
                val level = peak / 32768f
                _state.update { it.copy(recordedSeconds = seconds, inputLevel = level, usedHeadsetMic = headset) }
            }
            ensureActive()
            _state.update { it.copy(phase = MicCheckPhase.READY, inputLevel = 0f, recordedSeconds = seconds) }
        } catch (e: CancellationException) {
            _state.update { it.copy(phase = if (seconds > 0f) MicCheckPhase.READY else MicCheckPhase.IDLE, inputLevel = 0f, recordedSeconds = seconds) }
            throw e
        } catch (e: HeadphoneCommandException) {
            throw e
        } catch (t: Throwable) {
            fail("Recording failed: ${t.message ?: t.javaClass.simpleName}", t)
        } finally {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            runCatching { out?.close() }
            router.release()
        }
    }

    /** VOICE_COMMUNICATION is what calls use (and what makes the SCO link open); MIC is the fallback. */
    @SuppressLint("MissingPermission")
    private fun openRecorder(preferred: AudioDeviceInfo?): AudioRecord? {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferBytes = maxOf(minBuf, BYTES_PER_SECOND / 2)
        for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)) {
            val r = try {
                AudioRecord.Builder().setAudioSource(source).setAudioFormat(format).setBufferSizeInBytes(bufferBytes).build()
            } catch (t: Throwable) {
                Log.w(TAG, "AudioRecord source $source failed: $t"); continue
            }
            if (r.state == AudioRecord.STATE_INITIALIZED) {
                if (preferred != null) r.preferredDevice = preferred
                return r
            }
            r.release()
        }
        return null
    }

    // ---- playback --------------------------------------------------------------------------

    private suspend fun CoroutineScope.runPlayback(f: File) {
        router.release() // belt and braces: media must go over A2DP, not SCO
        val total = f.length()
        var track: AudioTrack? = null
        try {
            val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf, BYTES_PER_SECOND / 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) fail("Couldn't open audio output for playback.")
            track.play()

            val chunk = ByteArray(BYTES_PER_SECOND / 10)
            var written = 0L
            RandomAccessFile(f, "r").use { raf ->
                while (isActive) {
                    val n = raf.read(chunk)
                    if (n <= 0) break
                    var off = 0
                    while (off < n && isActive) {
                        val w = track.write(chunk, off, n - off)
                        if (w < 0) fail("Playback write failed ($w).")
                        off += w
                    }
                    written += n
                    _state.update { it.copy(playbackPositionSeconds = written.toFloat() / BYTES_PER_SECOND) }
                }
            }
            // write() returns once buffered, not once heard; wait for the head to reach the end.
            val totalFrames = (total / 2).toInt()
            val deadline = System.currentTimeMillis() + 2_000
            while (isActive && track.playbackHeadPosition < totalFrames && System.currentTimeMillis() < deadline) {
                delay(50)
                val pos = track.playbackHeadPosition.toFloat() / SAMPLE_RATE
                _state.update { it.copy(playbackPositionSeconds = pos) }
            }
            ensureActive()
            _state.update { it.copy(phase = MicCheckPhase.READY, playbackPositionSeconds = 0f) }
        } catch (e: CancellationException) {
            _state.update { it.copy(phase = MicCheckPhase.READY, playbackPositionSeconds = 0f) }
            throw e
        } catch (e: HeadphoneCommandException) {
            throw e
        } catch (t: Throwable) {
            fail("Playback failed: ${t.message ?: t.javaClass.simpleName}", t)
        } finally {
            runCatching { track?.pause(); track?.flush() }
            runCatching { track?.release() }
        }
    }

    companion object {
        private const val TAG = "Earshot/MicCheck"
        private const val FILE_NAME = "mic-check.pcm"
        private const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SECOND = SAMPLE_RATE * 2
    }
}
