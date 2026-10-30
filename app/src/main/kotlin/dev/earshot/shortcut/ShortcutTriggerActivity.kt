package dev.earshot.shortcut

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.earshot.LaunchOrigin
import dev.earshot.R
import dev.earshot.appContainer
import kotlinx.coroutines.launch

/**
 * Receives the headset's press-and-hold. Android's Bluetooth stack turns the HFP voice-recognition
 * request (`AT+BVRA=1`, "startVoiceRecognitionByHeadset" in the log) into a bare `VOICE_COMMAND`
 * and starts whichever app the user picked in the chooser; this activity is Earshot's entry,
 * reached through the alias `HeadsetTrigger`, which is on only while a choice needs it (see
 * [HeadsetTrigger]). The disabled-by-default alias `WiredHeadsetTrigger` also routes wired /
 * USB-C headsets' `VOICE_SEARCH_HANDS_FREE` here (see [WiredTrigger]).
 *
 * No UI of its own (translucent, excluded from recents, own task affinity, shown over the lock
 * screen). It works out which headset pressed, runs that headset's action, and finishes. Being in
 * the foreground for that moment is what allows the actions to open apps and start the microphone
 * service. It never forwards to itself: [ShortcutEngine] only sends explicit components that are
 * not Earshot. Any app can start the exported aliases, so a voice memo starts only when Android
 * names the Bluetooth stack, the system or its app chooser as the caller
 * ([LaunchOrigin.PRESS_SOURCES]); any other app, preinstalled or not, is refused.
 */
class ShortcutTriggerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            // Recreated (rotation, fold) while waiting for the unlock prompt: the press was handled.
            finish(); return
        }
        val trigger = intent
        // Read now, in onCreate, while `trigger` is the current intent (see LaunchOrigin.of).
        val origin = LaunchOrigin.of(this, trigger)
        logTrigger(origin)
        val engine = appContainer.shortcuts
        val wired = trigger?.action == WiredTrigger.ACTION_VOICE_SEARCH_HANDS_FREE
        val headsetPress = TriggerCaller.isHeadsetPress(
            referrerPackage = runCatching { referrer?.takeIf { it.scheme == "android-app" }?.host }.getOrNull(),
            ownPackage = packageName,
            internalExtra = trigger?.getBooleanExtra(TriggerCaller.EXTRA_INTERNAL, false) == true,
        )
        when (TriggerRouting.route(engine.detector.isListening, headsetPress)) {
            TriggerRoute.REPORT_DETECTED -> {
                // "Detect a button" is listening: say what this was, run nothing, get out of the way.
                engine.markHeadsetPressSeen()
                engine.reportAssistantGesture(trigger, wired)
                finish()
                return
            }
            TriggerRoute.IGNORE -> {
                finish()
                return
            }
            TriggerRoute.RUN -> Unit
        }
        lifecycleScope.launch {
            val settings = runCatching { engine.prefs.current() }.getOrDefault(ShortcutSettings())
            // Ends the remapper's "pick Earshot, then Always" hint; off the critical path (after the read).
            if (headsetPress && !settings.seenHeadsetPress) engine.markHeadsetPressSeen()
            // Resolving costs a profile-proxy round trip; "Open my assistant" must reach the
            // assistant inside Android's 5 s, so only look when the answer can matter.
            val needsHeadset = !wired && (settings.overrides.isNotEmpty() || settings.defaultAction == ShortcutAction.VoiceMemo ||
                HfpVoiceRecognition.ACKNOWLEDGE_VOICE_REQUEST)
            val headset = if (needsHeadset) runCatching { engine.headsets.resolve(trigger, engine.savedAddress()) }.getOrNull() else null
            Log.i(TAG, "headset ${headset?.address ?: "unknown"} via ${headset?.source ?: if (wired) "wired" else "-"}")
            val action = settings.actionFor(headset?.address)
            if (action == ShortcutAction.VoiceMemo && VoiceMemoService.state.value == VoiceMemoService.MemoState.IDLE) {
                // The aliases are exported, so another app could start this one and have Earshot
                // record with its microphone grant. A press reaches us from Android's Bluetooth
                // stack (or the system, for a wired headset), so only those start a recording.
                // Stopping a running memo stays open to anyone: it records nothing.
                if (!LaunchOrigin.mayStartVoiceMemo(origin.caller)) {
                    Log.w(TAG, "voice memo refused: started by ${origin.packageName ?: "an unknown app"} (${origin.caller})")
                    engine.feedback.toast(getString(R.string.shortcut_memo_not_from_headset))
                    finish(); return@launch
                }
                // Even then, only begin recording when a headset is really there.
                if (!headsetPresent(engine, wired, headset)) {
                    engine.feedback.toast(getString(R.string.shortcut_no_headset))
                    finish(); return@launch
                }
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
        // Any app can put an address in the extras: only a headset that is really connected counts.
        headset.source == HeadsetSource.INTENT -> engine.headsets.isHfpConnected(headset.address)
        else -> true
    }

    /**
     * Extras keys only (no values): enough to learn what the Bluetooth stack sends on each phone.
     * The caller is a package name (the Bluetooth app on a real press), never a person's data.
     */
    private fun logTrigger(origin: LaunchOrigin.Origin) {
        val keys = runCatching { intent?.extras?.keySet()?.sorted() }.getOrNull()
        Log.i(TAG, "trigger action=${intent?.action} extras=${keys ?: "none"} caller=${origin.packageName ?: "unknown"} (${origin.caller})")
    }

    private companion object {
        const val TAG = "Earshot/Shortcut"
    }
}
