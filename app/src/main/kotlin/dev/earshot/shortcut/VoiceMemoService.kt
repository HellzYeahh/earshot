package dev.earshot.shortcut

import android.Manifest
import android.app.Service
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.earshot.R
import dev.earshot.appContainer
import dev.earshot.bt.DeviceFinder
import dev.earshot.mic.HeadsetMicRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Voice memo: AAC/m4a from the headset mic when possible, else the phone mic.
 *
 * A `microphone` foreground service. Android 14+ only lets one start while the app is visible and
 * holds RECORD_AUDIO; the shortcut starts it from [ShortcutTriggerActivity] (in the foreground at
 * that moment).
 *
 * The headset mic needs an SCO link (see [acquireMic]), which pauses music while recording, exactly
 * like a call. Recording stops on the second press, the
 * notification's Stop, the [MAX_MINUTES] cap, a recorder error, or the headset mic disappearing
 * (the headset disconnected); every path saves what was recorded and puts audio routing back.
 */
class VoiceMemoService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var notifications: VoiceMemoNotifications
    private lateinit var router: HeadsetMicRouter
    private lateinit var audioManager: AudioManager

    private var startJob: Job? = null
    private var recorder: MediaRecorder? = null
    private var target: VoiceMemoStorage.Target? = null
    private var startedElapsed = 0L
    private var usedHeadset = false
    private var spokenFeedback = true
    private var deviceCallback: AudioDeviceCallback? = null
    private var scoReceiver: BroadcastReceiver? = null
    private var voiceRecognition: HfpVoiceRecognition? = null
    private var headsetAddress: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = VoiceMemoNotifications(this).also { it.ensureChannel() }
        router = HeadsetMicRouter(this)
        audioManager = getSystemService(AudioManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                spokenFeedback = intent.getBooleanExtra(EXTRA_SPOKEN, true)
                if (_state.value == MemoState.IDLE) headsetAddress = intent.getStringExtra(EXTRA_ADDRESS)
                if (!goForeground()) return START_NOT_STICKY
                if (_state.value == MemoState.IDLE) begin()
            }
            ACTION_STOP -> finishRecording(keep = true, note = null)
            else -> if (_state.value == MemoState.IDLE) stopSelf()
        }
        // Not sticky: a memo can't resume after the process died; restarting would just record anew.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (_state.value != MemoState.IDLE) finishRecording(keep = true, note = null, stopService = false)
        scope.cancel()
        super.onDestroy()
    }

    /** Must run right after `startForegroundService`; false when Android refused (no mic permission / not visible). */
    private fun goForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        val notification = if (_state.value == MemoState.RECORDING) {
            notifications.recording(wallClockOf(startedElapsed), usedHeadset, MAX_MINUTES)
        } else {
            notifications.starting()
        }
        ServiceCompat.startForeground(this, VoiceMemoNotifications.RECORDING_ID, notification, type)
        true
    } catch (e: Exception) {
        // SecurityException / ForegroundServiceStartNotAllowedException: say why instead of crashing.
        Log.w(TAG, "startForeground(microphone) refused: ${e.message}")
        appContainer.shortcuts.feedback.toast(getString(R.string.memo_start_refused))
        _state.value = MemoState.IDLE
        stopSelf()
        false
    }

    private fun begin() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            appContainer.shortcuts.feedback.toast(getString(R.string.memo_no_permission))
            stopNow(); return
        }
        _state.value = MemoState.STARTING
        startJob = scope.launch {
            val path = acquireMic(headsetAddress)
            // engage() swallows a cancellation (Stop pressed while SCO came up): honour it here.
            ensureActive()
            val headset = path != MicPath.PHONE
            try {
                val t = VoiceMemoStorage(this@VoiceMemoService).create()
                target = t
                recorder = openRecorder(t, path)
            } catch (e: Exception) {
                Log.w(TAG, "could not start recording", e)
                cleanUp(keep = false)
                appContainer.shortcuts.feedback.toast(getString(R.string.memo_failed))
                stopNow()
                return@launch
            }
            usedHeadset = headset
            startedElapsed = SystemClock.elapsedRealtime()
            _state.value = MemoState.RECORDING
            Log.i(TAG, "recording started via $path")
            notifications.recording(wallClockOf(startedElapsed), headset, MAX_MINUTES).let { n ->
                runCatching { ServiceCompat.startForeground(this@VoiceMemoService, VoiceMemoNotifications.RECORDING_ID, n, foregroundType()) }
            }
            if (headset) watchHeadset()
            if (path == MicPath.VOICE_RECOGNITION) watchVoiceRecognition()
            beep(headset)
        }
    }

    /**
     * The headset mic, best way first:
     *  1. HFP voice recognition on the headset that pressed: it answers the headset's pending
     *     `AT+BVRA` (no error tone) and opens SCO, wideband where supported;
     *  2. the mic check's SCO routing ([HeadsetMicRouter]), e.g. when the presser is unknown;
     *  3. the phone's own mic.
     */
    private suspend fun acquireMic(address: String?): MicPath {
        if (address != null) {
            val vr = HfpVoiceRecognition(this)
            if (vr.start(address)) {
                voiceRecognition = vr
                // SCO comes up asynchronously after the call.
                val up = withTimeoutOrNull(SCO_WAIT_MS) {
                    while (!vr.isAudioConnected()) delay(100)
                    true
                } ?: false
                if (up) return MicPath.VOICE_RECOGNITION
                Log.i(TAG, "voice recognition started but SCO never came up; trying the SCO route")
                vr.stop()
                voiceRecognition = null
            }
        }
        // Up to 3 s for the SCO link; false = no headset mic, use the phone's.
        return if (router.engage()) MicPath.SCO_ROUTE else MicPath.PHONE
    }

    private fun openRecorder(t: VoiceMemoStorage.Target, path: MicPath): MediaRecorder {
        val headset = path != MicPath.PHONE
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            // VOICE_RECOGNITION is what assistants record with over a voice-recognition SCO link;
            // VOICE_COMMUNICATION is what brings the routed SCO link up (as the mic check does);
            // the plain MIC source gives a cleaner phone-mic memo without call processing.
            r.setAudioSource(
                when (path) {
                    MicPath.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
                    MicPath.SCO_ROUTE -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    MicPath.PHONE -> MediaRecorder.AudioSource.MIC
                },
            )
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            // SCO carries 16 kHz at best (mSBC); recording it at 44.1 k only wastes space.
            r.setAudioSamplingRate(if (headset) 16_000 else 44_100)
            r.setAudioEncodingBitRate(if (headset) 48_000 else 96_000)
            r.setMaxDuration(MAX_MINUTES * 60_000)
            r.setOutputFile(t.descriptor.fileDescriptor)
            if (headset) scoInputDevice()?.let { r.setPreferredDevice(it) }
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    main.post { finishRecording(keep = true, note = getString(R.string.memo_note_limit, MAX_MINUTES)) }
                }
            }
            r.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "recorder error $what/$extra")
                main.post { finishRecording(keep = true, note = getString(R.string.memo_note_error)) }
            }
            r.prepare()
            r.start()
            return r
        } catch (e: Exception) {
            runCatching { r.release() }
            throw e
        }
    }

    /** The headset mic vanished (headset off / out of range): save what we have rather than keep recording silence. */
    private fun watchHeadset() {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                if (removedDevices.any { it.isSource && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }) {
                    Log.i(TAG, "headset mic removed, stopping")
                    finishRecording(keep = true, note = getString(R.string.memo_note_headset_lost))
                }
            }
        }
        deviceCallback = callback
        audioManager.registerAudioDeviceCallback(callback, main)
    }

    /**
     * While voice recognition runs, the headset's next press usually arrives as `AT+BVRA=0`, which
     * Android answers by closing SCO without telling any app: SCO audio going down is our stop.
     */
    private fun watchVoiceRecognition() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                // With a second HFP device (car kit, watch), its SCO going down is not our stop.
                val from = DeviceFinder.deviceAddressFrom(intent)
                val ours = headsetAddress
                if (ours != null && from != null && !from.equals(ours, ignoreCase = true)) return
                if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
                    Log.i(TAG, "SCO closed (headset press or disconnect), stopping")
                    finishRecording(keep = true, note = getString(R.string.memo_note_headset_stopped))
                }
            }
        }
        scoReceiver = receiver
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private fun scoInputDevice(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

    private fun finishRecording(keep: Boolean, note: String?, stopService: Boolean = true) {
        if (_state.value == MemoState.IDLE) { if (stopService) stopNow(); return }
        val wasRecording = _state.value == MemoState.RECORDING
        val seconds = if (wasRecording) ((SystemClock.elapsedRealtime() - startedElapsed) / 1000).toInt() else 0
        val t = target
        val saved = cleanUp(keep = keep && wasRecording)
        if (saved && t != null) {
            Log.i(TAG, "memo saved (${seconds}s)")
            appContainer.shortcuts.feedback.say(getString(R.string.memo_spoken_saved), spokenFeedback)
            notifications.notifySaved(notifications.saved(t.displayName, t.folder, t.uri, note))
        } else if (wasRecording) {
            appContainer.shortcuts.feedback.toast(getString(R.string.memo_too_short))
        }
        if (stopService) stopNow()
    }

    /** Releases recorder, routing and callback; returns whether a memo was kept. Idempotent. */
    private fun cleanUp(keep: Boolean): Boolean {
        startJob?.cancel(); startJob = null
        deviceCallback?.let { runCatching { audioManager.unregisterAudioDeviceCallback(it) } }
        deviceCallback = null
        scoReceiver?.let { runCatching { unregisterReceiver(it) } }
        scoReceiver = null
        var ok = keep
        recorder?.let { r ->
            // stop() throws when nothing was captured yet; that file is unplayable, so drop it.
            try { r.stop() } catch (e: RuntimeException) { ok = false }
            runCatching { r.release() }
        }
        recorder = null
        voiceRecognition?.stop()
        voiceRecognition = null
        router.release()
        target?.let { VoiceMemoStorage(this).finish(it, keep = ok) }
        target = null
        _state.value = MemoState.IDLE
        return ok
    }

    private fun stopNow() {
        _state.value = MemoState.IDLE
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** A short beep in the ear that says "recording". In-call stream when on SCO, else media. */
    private fun beep(headset: Boolean) {
        runCatching {
            val tone = ToneGenerator(if (headset) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC, 70)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            main.postDelayed({ runCatching { tone.release() } }, 400)
        }
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0

    private fun wallClockOf(elapsed: Long): Long = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - elapsed)

    enum class MemoState { IDLE, STARTING, RECORDING }

    private enum class MicPath { VOICE_RECOGNITION, SCO_ROUTE, PHONE }

    companion object {
        private const val TAG = "Earshot/VoiceMemo"
        const val ACTION_START = "dev.earshot.shortcut.action.MEMO_START"
        const val ACTION_STOP = "dev.earshot.shortcut.action.MEMO_STOP"
        private const val EXTRA_SPOKEN = "spoken"
        private const val EXTRA_ADDRESS = "headset_address"
        private const val SCO_WAIT_MS = 3_000L
        const val MAX_MINUTES = 30

        private val _state = MutableStateFlow(MemoState.IDLE)
        /** Process-wide: the trigger decides start vs stop from it. */
        val state: StateFlow<MemoState> = _state

        /**
         * Starts a memo, or stops the one in progress. [headsetAddress] is the headset that pressed
         * (null: none known), whose pending voice request the memo answers. Call only while an Earshot activity is in the
         * foreground (Android 14+ refuses a microphone service otherwise).
         */
        fun toggle(context: Context, spokenFeedback: Boolean, headsetAddress: String?) {
            if (_state.value != MemoState.IDLE) {
                context.startService(Intent(context, VoiceMemoService::class.java).setAction(ACTION_STOP))
            } else {
                val intent = Intent(context, VoiceMemoService::class.java).setAction(ACTION_START)
                    .putExtra(EXTRA_SPOKEN, spokenFeedback)
                    .putExtra(EXTRA_ADDRESS, headsetAddress)
                try {
                    ContextCompat.startForegroundService(context, intent)
                } catch (e: IllegalStateException) {
                    // Android 12+ ForegroundServiceStartNotAllowedException: the press came too late
                    // after the trigger went to the background (the headset lookup can take ~1.5 s).
                    Log.w(TAG, "memo service start refused: $e")
                    context.appContainer.shortcuts.feedback.toast(context.getString(R.string.memo_start_refused))
                }
            }
        }
    }
}
