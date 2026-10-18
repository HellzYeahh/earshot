package dev.earshot.shortcut

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import java.util.UUID

/**
 * Says a short confirmation ("Bass boost", "Saved") in the headset, through the phone's on-device
 * TextToSpeech engine on the media stream (so it plays over A2DP like the music). Falls back to a
 * toast when there is no engine, it fails to start, or the user turned spoken feedback off.
 *
 * The engine is created on demand and shut down a few seconds after the last line, so nothing is
 * held while the shortcut isn't used. All calls hop to the main thread.
 */
class SpokenFeedback(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayList<String>()
    private val shutdown = Runnable { release() }

    fun say(text: String, speak: Boolean) {
        main.post {
            if (!speak) { toast(text); return@post }
            main.removeCallbacks(shutdown)
            val engine = tts
            when {
                engine == null -> start(text)
                !ready -> pending += text
                else -> speakNow(engine, text)
            }
        }
    }

    fun toast(text: String) {
        main.post { Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show() }
    }

    private fun start(first: String) {
        pending += first
        val engine = try {
            TextToSpeech(appContext) { status -> main.post { onInit(status) } }
        } catch (t: Throwable) {
            Log.w(TAG, "TextToSpeech unavailable: $t")
            flushAsToasts(); return
        }
        tts = engine
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                // Media usage: it follows the music to the headset; sonification may also ring the speaker.
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = scheduleShutdown()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = scheduleShutdown()
        })
        main.postDelayed(shutdown, INIT_TIMEOUT_MS) // an engine that never calls back
    }

    private fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TextToSpeech init failed ($status)")
            flushAsToasts(); release(); return
        }
        ready = true
        main.removeCallbacks(shutdown)
        pending.toList().also { pending.clear() }.forEach { speakNow(engine, it) }
    }

    private fun speakNow(engine: TextToSpeech, text: String) {
        val result = engine.speak(text, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
        if (result != TextToSpeech.SUCCESS) toast(text)
        scheduleShutdown()
    }

    private fun scheduleShutdown() {
        main.removeCallbacks(shutdown)
        main.postDelayed(shutdown, IDLE_SHUTDOWN_MS)
    }

    private fun flushAsToasts() {
        pending.toList().also { pending.clear() }.forEach(::toast)
    }

    private fun release() {
        main.removeCallbacks(shutdown)
        flushAsToasts()
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    private companion object {
        const val TAG = "Earshot/Shortcut"
        const val INIT_TIMEOUT_MS = 5_000L
        const val IDLE_SHUTDOWN_MS = 8_000L
    }
}
