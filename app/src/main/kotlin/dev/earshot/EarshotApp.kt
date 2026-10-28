package dev.earshot

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dev.earshot.extras.ExtrasInitializer
import dev.earshot.sleep.LockedAlarms

class EarshotApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Direct boot: the Sleep tab's alarm components can start the process after an overnight
        // restart, before the first unlock. Everything below reads credential-encrypted storage,
        // so it waits for the unlock; the locked alarm path (LockedAlarms) needs none of it.
        if (LockedAlarms.isLocked(this)) {
            val unlocked = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    runCatching { unregisterReceiver(this) }
                    startUnlocked()
                }
            }
            ContextCompat.registerReceiver(this, unlocked, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED)
            // Unlocked between the check and the registration: the broadcast is already gone.
            if (!LockedAlarms.isLocked(this)) {
                runCatching { unregisterReceiver(unlocked) }
                startUnlocked()
            }
            return
        }
        startUnlocked()
    }

    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Runs once per process: at start when unlocked, else at the first unlock. */
    private fun startUnlocked() {
        if (!started.compareAndSet(false, true)) return
        // Automations, low-battery alert and widget/tile refresh observe the repositories from
        // process start, so a rule fires even when the UI was never opened.
        ExtrasInitializer.start(this, container)
        // Call detection lives in listeners the repository registers on construction.
        container.callAudio
        // The Shortcuts remapper puts a connecting Bose headset's Shortcut button back on the mode
        // the saved choice needs, so its watcher must run from process start too.
        container.shortcuts
        // Each headset gets its own Pro EQ preset back when it connects.
        container.headsetEq.start()
        // The diagnostics timeline records from process start (HFP, pairing, encryption, codec).
        container.timeline.start()
        // Sleep tab: re-arms the wake alarms (lost on reboot) and picks up a night in progress.
        container.sleepRuntime
        dev.earshot.sleep.SleepShortcut.publish(this)
        // Headsets linked through the universal remote: Android binds HeadsetPresenceService when
        // one connects. Observing is idempotent; renewing it here covers links made before 0.8.
        // Off the main thread: a few binder calls that receivers and the presence bind shouldn't wait on.
        kotlin.concurrent.thread(name = "Earshot-presence", isDaemon = true) { dev.earshot.bt.CompanionPresence(this).observeAll() }
    }
}

/** `context.appContainer` from anywhere that has a Context (activities, services, receivers, Glance). */
val Context.appContainer: AppContainer
    get() = (applicationContext as EarshotApp).container
