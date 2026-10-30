package dev.earshot.call

import dev.earshot.core.CallAudioPreferences
import dev.earshot.sleep.BackgroundAudio
import kotlin.math.roundToInt

/** The arithmetic behind the call-volume boost, kept free of Android so it can be unit tested ([BackgroundAudio] is plain Kotlin too). */
object CallVolumeBoost {

    /**
     * Index to set for [percent] of [max], rounded to nearest and kept inside [min]..[max].
     * Returns null when the stream range is unknown ([max] <= 0).
     */
    fun target(max: Int, percent: Int, min: Int = 0): Int? {
        if (max <= 0) return null
        val p = percent.coerceIn(CallAudioPreferences.MIN_BOOST_PERCENT, 100)
        return (max * p / 100.0).roundToInt().coerceIn(min.coerceIn(0, max), max)
    }

    /**
     * Automatic boosts only ever raise: if the user (or Samsung's own per-device memory) already has
     * the call louder than the target, leave it alone.
     */
    fun shouldRaise(current: Int, target: Int): Boolean = current < target

    /**
     * Did Android 17 ignore the volume change? It refuses a volume call from an app that is
     * neither visible nor allowed by [BackgroundAudio.mayUseAudio], and says nothing: the index
     * simply stays.
     *  - Below target 37 (or below Android 17) any foreground service is enough. The headset
     *    service is one while a headset is linked, so a volume that didn't move there is a cap
     *    (One UI, the index already at its limit), not a refusal.
     *  - From target 37 the service also needs while-in-use status. The headset service usually
     *    starts in the background (a Bluetooth connect), and only Android knows how it was started,
     *    so its status is taken as missing ([BackgroundAudio]: the caller can't tell, so false).
     *
     * True when a change was asked for ([target] differs from [before]), the volume read back is
     * exactly [before], and the call wasn't allowed for [sdk] / [targetSdk] with [appVisible] and
     * [foregroundService]. A volume that moved only part of the way (One UI capping it) is not a
     * refusal.
     *
     * @param sdk `Build.VERSION.SDK_INT`.
     * @param targetSdk `applicationInfo.targetSdkVersion`, so the rule is right before and after the bump.
     * @param foregroundService one of Earshot's foreground services runs (the headset service while
     *   a headset is linked).
     */
    fun refusedInBackground(
        before: Int,
        after: Int,
        target: Int,
        sdk: Int,
        targetSdk: Int,
        appVisible: Boolean,
        foregroundService: Boolean,
    ): Boolean =
        target != before && after == before &&
            !BackgroundAudio.mayUseAudio(sdk, targetSdk, appVisible, foregroundService, whileInUseService = false)
}
