package dev.earshot.call

import dev.earshot.core.CallAudioPreferences
import kotlin.math.roundToInt

/** The arithmetic behind the call-volume boost, kept free of Android so it can be unit tested. */
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
}
