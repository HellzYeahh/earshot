package dev.earshot

import android.app.Application
import android.content.Context
import dev.earshot.extras.ExtrasInitializer

class EarshotApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
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
    }
}

/** `context.appContainer` from anywhere that has a Context (activities, services, receivers, Glance). */
val Context.appContainer: AppContainer
    get() = (applicationContext as EarshotApp).container
