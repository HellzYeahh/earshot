package dev.earshot.shortcut

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.earshot.MainActivity
import dev.earshot.R
import dev.earshot.bt.DevicePrefs
import dev.earshot.core.AudioTuningRepository
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.dsp.EqPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Runs a [ShortcutAction]. Called by [ShortcutTriggerActivity] (the headset gesture) and by the
 * Shortcuts remapper screen ("Try it now"); both pass a foreground [Activity], which is what lets
 * the actions start other activities and the microphone service without background limits.
 */
class ShortcutEngine(
    private val context: Context,
    val prefs: ShortcutPrefs,
    private val tuning: AudioTuningRepository,
    private val devicePrefs: DevicePrefs,
    private val scope: CoroutineScope,
) {
    val assistants = AssistantDirectory(context)
    val headsets = HeadsetLookup(context)
    val feedback = SpokenFeedback(context)

    /** The experimental wired / USB-C trigger (the disabled-by-default activity-alias). */
    var wiredTriggerEnabled: Boolean
        get() = WiredTrigger.isEnabled(context)
        set(value) = WiredTrigger.setEnabled(context, value)

    /** The saved headphones' address, the last resort of [HeadsetResolution]. */
    suspend fun savedAddress(): String? = runCatching { devicePrefs.savedDevice()?.address }.getOrNull()

    /**
     * Runs [action] from [activity]. [onDone] is called once the activity may finish: right away
     * for most actions, after the unlock prompt for "Launch an app" on a locked phone.
     * [headsetAddress] is the Bluetooth headset that pressed (null: unknown, wired, or "Try it now").
     */
    fun run(
        activity: Activity,
        action: ShortcutAction,
        settings: ShortcutSettings,
        trigger: Intent?,
        headsetAddress: String?,
        onDone: () -> Unit,
    ) {
        Log.i(TAG, "running ${action.javaClass.simpleName}")
        when (action) {
            ShortcutAction.CycleEq -> {
                acknowledgeIfConfigured(headsetAddress)
                scope.launch {
                    try {
                        val preset = cycleEq()
                        feedback.say(preset?.name ?: context.getString(R.string.shortcut_no_presets), settings.spokenFeedback)
                    } catch (e: Exception) {
                        Log.w(TAG, "cycle EQ failed: $e")
                        feedback.toast(e.message ?: context.getString(R.string.error_generic))
                    }
                }
                onDone()
            }
            ShortcutAction.VoiceMemo -> {
                if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED &&
                    VoiceMemoService.state.value == VoiceMemoService.MemoState.IDLE
                ) {
                    feedback.toast(context.getString(R.string.memo_no_permission))
                    openEarshot(activity)
                } else {
                    // The memo itself answers the headset's voice request (it opens the headset mic with it).
                    VoiceMemoService.toggle(activity, settings.spokenFeedback, headsetAddress)
                }
                onDone()
            }
            is ShortcutAction.LaunchApp -> {
                acknowledgeIfConfigured(headsetAddress)
                launchApp(activity, action, onDone)
            }
            is ShortcutAction.OpenAssistant -> {
                // At once: the assistant must claim the headset's request within Android's 5 s.
                forwardToAssistant(activity, action, trigger)
                onDone()
            }
        }
    }

    /**
     * Actions that don't use the mic leave the headset's voice request unanswered by default
     * (Android reports a failure after 5 s); see [HfpVoiceRecognition.ACKNOWLEDGE_VOICE_REQUEST].
     */
    private fun acknowledgeIfConfigured(headsetAddress: String?) {
        @Suppress("KotlinConstantConditions", "SimplifyBooleanWithConstants")
        if (!HfpVoiceRecognition.ACKNOWLEDGE_VOICE_REQUEST || headsetAddress == null) {
            Log.i(TAG, "voice request left to time out (acknowledge off or no headset)")
            return
        }
        scope.launch { HfpVoiceRecognition.acknowledge(context, headsetAddress) }
    }

    /**
     * Next Pro EQ preset, the same call the Sound screen's preset chips make. Turns the Pro EQ on
     * first when it is off: a preset nobody hears would be a confusing shortcut. Turning it on also
     * waits for the stored presets to load, so the user's own presets are part of the cycle.
     */
    suspend fun cycleEq(): EqPreset? {
        if (!tuning.state.value.settings.enabled) tuning.setEnabled(true)
        if (!tuning.state.value.isAvailable) throw HeadphoneCommandException(context.getString(R.string.shortcut_eq_unavailable))
        val next = EqCycle.next(tuning.presets.value, tuning.state.value.settings.activePresetId) ?: return null
        tuning.applyPreset(next.id)
        return next
    }

    private fun launchApp(activity: Activity, action: ShortcutAction.LaunchApp, onDone: () -> Unit) {
        val pkg = action.packageName
        val launch = pkg?.takeIf { it != context.packageName }?.let { context.packageManager.getLaunchIntentForPackage(it) }
        if (launch == null) {
            feedback.toast(context.getString(if (pkg == null) R.string.shortcut_no_app else R.string.shortcut_app_missing))
            onDone(); return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) {
            // Ask for the unlock first; the app opens once it succeeds. The trigger activity shows
            // over the lock screen (showWhenLocked) and stays until the user unlocked or cancelled.
            keyguard.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { start(activity, launch); onDone() }
                override fun onDismissCancelled() = onDone()
                override fun onDismissError() = onDone()
            })
        } else {
            start(activity, launch)
            onDone()
        }
    }

    private fun forwardToAssistant(activity: Activity, action: ShortcutAction.OpenAssistant, trigger: Intent?) {
        val target = assistants.forwardTarget(action.component)
        val intent = target?.let { assistants.forwardIntent(it, trigger) }
        if (target == null || intent == null) {
            feedback.toast(context.getString(R.string.shortcut_no_assistant))
            return
        }
        Log.i(TAG, "forwarding to ${target.component} (${target.kind})")
        start(activity, intent)
    }

    private fun start(activity: Activity, intent: Intent) {
        // Loop guard of last resort: an intent without a component, or one aimed at Earshot, is never sent.
        val component = intent.component
        if (component == null || component.packageName == context.packageName) {
            Log.w(TAG, "refusing to start $component")
            return
        }
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "start failed: $e")
            feedback.toast(context.getString(R.string.shortcut_start_failed))
        }
    }

    /** Opens Earshot on the Tools tab (where the Shortcuts remapper asks for the permission). */
    private fun openEarshot(activity: Activity) {
        val intent = Intent(activity, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_TAB, "tools")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { activity.startActivity(intent) }
    }

    private companion object {
        const val TAG = "Earshot/Shortcut"
    }
}
