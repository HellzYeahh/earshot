package dev.earshot.shortcut

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.earshot.R
import dev.earshot.appContainer
import kotlinx.coroutines.launch

/**
 * Receives the headset's press-and-hold. Android's Bluetooth stack turns the HFP voice-recognition
 * request (`AT+BVRA=1`, "startVoiceRecognitionByHeadset" in the log) into a bare `VOICE_COMMAND`
 * and starts whichever app the user picked in the chooser; this activity is Earshot's entry. The
 * disabled-by-default alias `WiredHeadsetTrigger` also routes wired / USB-C headsets'
 * `VOICE_SEARCH_HANDS_FREE` here (see [WiredTrigger]).
 *
 * No UI of its own (translucent, excluded from recents, own task affinity, shown over the lock
 * screen). It works out which headset pressed, runs that headset's action, and finishes. Being in
 * the foreground for that moment is what allows the actions to open apps and start the microphone
 * service. It never forwards to itself: [ShortcutEngine] only sends explicit components that are
 * not Earshot.
 */
class ShortcutTriggerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            // Recreated (rotation, fold) while waiting for the unlock prompt: the press was handled.
            finish(); return
        }
        logTrigger()
        val engine = appContainer.shortcuts
        val trigger = intent
        val wired = trigger?.action == WiredTrigger.ACTION_VOICE_SEARCH_HANDS_FREE
        lifecycleScope.launch {
            val settings = runCatching { engine.prefs.current() }.getOrDefault(ShortcutSettings())
            // Resolving costs a profile-proxy round trip; "Open my assistant" must reach the
            // assistant inside Android's 5 s, so only look when the answer can matter.
            val needsHeadset = !wired && (settings.overrides.isNotEmpty() || settings.defaultAction == ShortcutAction.VoiceMemo ||
                HfpVoiceRecognition.ACKNOWLEDGE_VOICE_REQUEST)
            val headset = if (needsHeadset) runCatching { engine.headsets.resolve(trigger, engine.savedAddress()) }.getOrNull() else null
            Log.i(TAG, "headset ${headset?.address ?: "unknown"} via ${headset?.source ?: if (wired) "wired" else "-"}")
            val action = settings.actionFor(headset?.address)
            if (action == ShortcutAction.VoiceMemo && VoiceMemoService.state.value == VoiceMemoService.MemoState.IDLE &&
                !headsetPresent(engine, wired, headset)
            ) {
                // An exported activity anyone can start: only begin recording when a headset is
                // really there, so another app can't use Earshot to record.
                engine.feedback.toast(getString(R.string.shortcut_no_headset))
                finish(); return@launch
            }
            // The saved headphones are only a guess for overrides; the voice request belongs to a real presser.
            val presser = headset?.takeIf { it.source != HeadsetSource.SAVED }?.address
            engine.run(this@ShortcutTriggerActivity, action, settings, trigger, presser) { if (!isFinishing) finish() }
        }
    }

    private suspend fun headsetPresent(engine: ShortcutEngine, wired: Boolean, headset: ResolvedHeadset?): Boolean = when {
        wired -> engine.headsets.wiredHeadsetPresent()
        headset == null -> false
        headset.source == HeadsetSource.SAVED -> engine.headsets.anyHfpConnected() != false
        else -> true
    }

    /** Extras keys only (no values): enough to learn what the Bluetooth stack sends on each phone. */
    private fun logTrigger() {
        val keys = runCatching { intent?.extras?.keySet()?.sorted() }.getOrNull()
        Log.i(TAG, "trigger action=${intent?.action} extras=${keys ?: "none"}")
    }

    private companion object {
        const val TAG = "Earshot/Shortcut"
    }
}
