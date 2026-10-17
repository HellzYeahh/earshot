package dev.earshot.audio

import android.media.audiofx.DynamicsProcessing
import android.util.Log
import dev.earshot.dsp.DynamicsProcessingPlan
import dev.earshot.dsp.DynamicsProcessingPlan.Plan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Produces a [Plan] for whatever band count the device ended up accepting. */
fun interface Planner {
    fun plan(bandCount: Int): Plan
}

/** What the engine is doing right now, for [dev.earshot.core.TuningState]. */
data class EngineStatus(
    val attachedSessions: Set<Int> = emptySet(),
    /**
     * False once the device has refused to create *any* DynamicsProcessing effect. Not sticky: a
     * later successful attach (the failure was a transient HAL hiccup) sets it back to true.
     */
    val isAvailable: Boolean = true,
    val warning: String? = null,
)

/**
 * Owns one `android.media.audiofx.DynamicsProcessing` per attached audio session.
 *
 * Lifecycle rules that matter (learned the hard way across OEMs):
 *  - An effect must be created on the session *before* or *while* the player's AudioTrack lives;
 *    the framework attaches it to the mixer thread. Creating it is a binder call that can take
 *    tens of milliseconds and can throw a bare [RuntimeException] ("Cannot initialize effect
 *    engine") on devices whose vendor effect library lacks DynamicsProcessing. Never call from
 *    the main thread.
 *  - The config (band counts, stages in use) is fixed at construction. Changing it means
 *    release + recreate, which is what [attach] does when a device rejects our preferred layout.
 *  - Effects are refcounted per session across apps: if we leak one, the audio HAL keeps running
 *    it until reboot. Every path that drops a session calls `release()`.
 *  - Session 0 (the global output mix) is deprecated since API 31 and most Android 13+ devices
 *    refuse it with an exception or create a silent no-op. We expose it as an opt-in fallback and
 *    report a [EngineStatus.warning] when it fails rather than pretending it works.
 *
 * All methods are synchronised; callers are the repository's single coroutine scope anyway.
 */
class DynamicsProcessingEngine(
    private val preferredBandCount: Int = DynamicsProcessingPlan.DEFAULT_BAND_COUNT,
) {
    private class Attached(val effect: DynamicsProcessing, val bandCount: Int)

    private val lock = Any()
    private val effects = HashMap<Int, Attached>()
    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status

    /** Session ids currently holding an effect. */
    val attached: Set<Int> get() = synchronized(lock) { effects.keys.toSet() }

    /**
     * Create (or reuse) the effect on [sessionId] and apply [planner]'s plan. Returns true if the
     * session now has a working effect. On failure the session is left without an effect and
     * [EngineStatus.warning] explains why.
     */
    fun attach(sessionId: Int, planner: Planner, enabled: Boolean): Boolean = synchronized(lock) {
        val existing = effects[sessionId]
        if (existing != null) {
            return applyTo(existing, sessionId, planner, enabled)
        }
        // Try our preferred resolution first, then coarser layouts: some vendor implementations
        // cap the post-EQ at 16 or 10 bands and throw UnsupportedOperationException above that.
        var lastError: Throwable? = null
        for (bandCount in candidateBandCounts()) {
            val effect = try {
                create(sessionId, bandCount)
            } catch (e: UnsupportedOperationException) {
                lastError = e; continue
            } catch (e: IllegalStateException) {
                lastError = e; continue
            } catch (e: IllegalArgumentException) {
                // Invalid session id: the player already closed it. Not a device problem.
                Log.w(TAG, "session $sessionId rejected: ${e.message}")
                return false
            } catch (e: RuntimeException) {
                lastError = e; continue
            }
            val attached = Attached(effect, bandCount)
            if (applyTo(attached, sessionId, planner, enabled)) {
                effects[sessionId] = attached
                Log.i(TAG, "attached DynamicsProcessing to session $sessionId with $bandCount bands")
                // A working effect proves the device supports it, whatever an earlier create said.
                publish(isAvailable = true, warning = if (sessionId == 0) null else _status.value.warning)
                return true
            }
            // applyTo already released a broken effect; try a smaller layout.
            lastError = IllegalStateException("device rejected $bandCount-band config")
        }
        onCreateFailed(sessionId, lastError)
        return false
    }

    /** Re-apply the plan to every attached session (settings changed). */
    fun applyAll(plannerFor: (sessionId: Int) -> Planner, enabled: Boolean) = synchronized(lock) {
        for ((session, attached) in effects.entries.toList()) {
            if (!applyTo(attached, session, plannerFor(session), enabled)) effects.remove(session)
        }
        publish()
    }

    fun detach(sessionId: Int) = synchronized(lock) {
        effects.remove(sessionId)?.let { release(it.effect, sessionId) }
        publish()
    }

    fun detachAll() = synchronized(lock) {
        for ((session, attached) in effects) release(attached.effect, session)
        effects.clear()
        publish()
    }

    /** Clear a stale warning (for example after the user turns the global fallback off). */
    fun clearWarning() = publish(warning = null)

    // ---- internals -------------------------------------------------------------------------

    private fun candidateBandCounts(): List<Int> =
        (listOf(preferredBandCount) + FALLBACK_BAND_COUNTS).filter { it <= preferredBandCount }.distinct()

    private fun create(sessionId: Int, bandCount: Int): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            STEREO_CHANNELS,
            /* preEqInUse = */ false, /* preEqBandCount = */ 0,
            /* mbcInUse = */ false, /* mbcBandCount = */ 0,
            /* postEqInUse = */ true, /* postEqBandCount = */ bandCount,
            /* limiterInUse = */ true,
        ).build() // frame duration left at the framework default (variable / 10 ms)
        return DynamicsProcessing(/* priority = */ 0, sessionId, config)
    }

    /**
     * Push [planner]'s plan into [attached]. Returns false (and releases the effect) when the
     * device rejects the parameters, so the caller can try another layout or give up.
     */
    private fun applyTo(attached: Attached, sessionId: Int, planner: Planner, enabled: Boolean): Boolean {
        val plan = planner.plan(attached.bandCount)
        return try {
            val effect = attached.effect
            plan.bands.forEachIndexed { i, band ->
                effect.setPostEqBandAllChannelsTo(
                    i,
                    DynamicsProcessing.EqBand(/* enabled = */ true, band.cutoffHz.toFloat(), band.gainDb.toFloat()),
                )
            }
            effect.setInputGainAllChannelsTo(plan.inputGainDb.toFloat())
            val l = plan.limiter
            effect.setLimiterAllChannelsTo(
                DynamicsProcessing.Limiter(
                    /* inUse = */ true,
                    /* enabled = */ l.enabled,
                    /* linkGroup = */ 0,
                    l.attackMs.toFloat(),
                    l.releaseMs.toFloat(),
                    l.ratio.toFloat(),
                    l.thresholdDb.toFloat(),
                    l.postGainDb.toFloat(),
                ),
            )
            effect.enabled = enabled
            true
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "session $sessionId rejected ${attached.bandCount}-band plan: ${e.message}")
            release(attached.effect, sessionId); false
        } catch (e: IllegalStateException) {
            Log.w(TAG, "session $sessionId effect in bad state: ${e.message}")
            release(attached.effect, sessionId); false
        } catch (e: RuntimeException) {
            Log.w(TAG, "session $sessionId apply failed: ${e.message}")
            release(attached.effect, sessionId); false
        }
    }

    private fun release(effect: DynamicsProcessing, sessionId: Int) {
        try {
            effect.enabled = false
        } catch (_: RuntimeException) {
            // already dead; release anyway
        }
        try {
            effect.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "release on session $sessionId threw: ${e.message}")
        }
    }

    private fun onCreateFailed(sessionId: Int, cause: Throwable?) {
        val reason = cause?.message ?: cause?.javaClass?.simpleName ?: "unknown"
        if (sessionId == 0) {
            // Expected on modern devices; not a sign the engine is broken.
            Log.w(TAG, "global output mix (session 0) refused: $reason")
            publish(warning = "This phone doesn't allow EQ on the global output. Only apps that announce their audio session can be tuned.")
            return
        }
        Log.e(TAG, "cannot create DynamicsProcessing on session $sessionId: $reason", cause)
        val stillHaveOne = effects.isNotEmpty()
        // One rejected session with other sessions working is a per-app quirk (e.g. a session the
        // player already closed). Zero working sessions after a real create failure means the
        // device simply lacks the effect: remember that so the UI can say so.
        publish(
            isAvailable = stillHaveOne,
            warning = if (stillHaveOne) _status.value.warning
            else "This phone's audio stack doesn't support the DynamicsProcessing effect ($reason).",
        )
    }

    private fun publish(isAvailable: Boolean = _status.value.isAvailable, warning: String? = _status.value.warning) {
        _status.value = EngineStatus(
            attachedSessions = effects.keys.toSet(),
            isAvailable = isAvailable,
            warning = warning,
        )
    }

    private companion object {
        const val TAG = "Earshot/DpEngine"
        /** Stereo pair. The framework re-configures to the real channel count on first process. */
        const val STEREO_CHANNELS = 2
        val FALLBACK_BAND_COUNTS = listOf(16, 10, 6)
    }
}
