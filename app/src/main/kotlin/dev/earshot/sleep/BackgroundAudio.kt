package dev.earshot.sleep

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Android 17's background-audio hardening, shared by the Sleep runtime and the call boost
 * (docs/research/play-readiness.md A1; https://developer.android.com/about/versions/17/changes/bg-audio).
 *
 * - On API 37+ every app's playback, audio-focus and volume calls need a visible activity or a
 *   foreground service that isn't `shortService`.
 * - An app that *targets* 37 or higher also needs that service to have while-in-use status: it
 *   was started while the app was visible, or from a notification tap, a widget or a media key.
 *   A service started from a broadcast or a Bluetooth event has none; one started from the
 *   companion binding probably has none either (unverified, research A6).
 * - A refused call fails without a word: a volume stays where it was, playback is silenced, a
 *   focus request returns `AUDIOFOCUS_REQUEST_FAILED`.
 * - `USAGE_ALARM` streams are exempt from the while-in-use part while the app holds exact-alarm
 *   access (not in `set-enable-hardening enable` test mode). The wake alarm relies on that.
 *
 * The rules take the API level and the target as plain numbers, so they hold before and after the
 * targetSdk bump and are unit tested. Callers pass `Build.VERSION.SDK_INT` and
 * `applicationInfo.targetSdkVersion`, never a constant.
 */
object BackgroundAudio {
    /** Android 17. No VERSION_CODES name for it in every SDK yet. */
    const val API_17 = 37

    /** Android 17+: audio calls need a visible app or a foreground service, whatever the target. */
    fun hardened(sdk: Int): Boolean = sdk >= API_17

    /** Android 17+ and a target of 37+: the foreground service must also have while-in-use status. */
    fun needsWhileInUse(sdk: Int, targetSdk: Int): Boolean = sdk >= API_17 && targetSdk >= API_17

    /**
     * May a playback, focus or volume call from this process work now?
     *
     * @param appVisible an Earshot screen is visible.
     * @param foregroundService one of Earshot's foreground services runs (any start).
     * @param whileInUseService one of them has while-in-use status (started from a visible
     *   screen or a notification tap). Only Earshot knows how its services were started; when it
     *   can't tell, the caller passes false and the action waits for the app to be opened.
     */
    fun mayUseAudio(sdk: Int, targetSdk: Int, appVisible: Boolean, foregroundService: Boolean, whileInUseService: Boolean): Boolean = when {
        !hardened(sdk) -> true
        appVisible -> true
        needsWhileInUse(sdk, targetSdk) -> whileInUseService
        else -> foregroundService || whileInUseService
    }

    /**
     * How many Earshot screens are started (between onStart and onStop). A count, not a flag: the
     * alarm screen starts before the main screen under it stops, so a flag would read "hidden"
     * while the alarm screen is up.
     */
    private val _visibleScreens = MutableStateFlow(0)
    val visibleScreens: StateFlow<Int> = _visibleScreens.asStateFlow()

    val isAppVisible: Boolean get() = _visibleScreens.value > 0

    fun screenStarted() = _visibleScreens.update { it + 1 }

    /** Never below zero: a screen that started before the first unlock never counted itself. */
    fun screenStopped() = _visibleScreens.update { (it - 1).coerceAtLeast(0) }
}
