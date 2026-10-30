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
 * One effect on one audio session, as the engine drives it. The real one wraps
 * `android.media.audiofx.DynamicsProcessing` ([AndroidEffects]); JVM tests use a fake. Both throw
 * what the framework throws: [UnsupportedOperationException] when a layout is refused or another
 * app's higher-priority effect has taken control of the session (INVALID_OPERATION),
 * [IllegalStateException] once released, a bare [RuntimeException] when audioserver died.
 */
internal interface SessionEffect {
    fun apply(plan: Plan, enabled: Boolean)

    /** Switch off and free the effect. May throw if the handle is already dead. */
    fun release()
}

/** Creates the effect on a session with a given post-EQ band count; throws as the DynamicsProcessing constructor does. */
internal fun interface SessionEffectFactory {
    fun create(sessionId: Int, bandCount: Int): SessionEffect
}

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
 *  - A working effect can stop working: another app takes control of the session with a
 *    higher-priority effect (the player's own EQ, a system sound effect), or audioserver restarts
 *    and the handle dies. [attach] then drops the dead handle and builds a new one; while that
 *    keeps failing the session is "lost" and retried on every attach, never reported as a phone
 *    without the effect.
 *  - Session 0 (the global output mix) is deprecated since API 31 and most Android 13+ devices
 *    refuse it with an exception or create a silent no-op. We expose it as an opt-in fallback and
 *    report a [EngineStatus.warning] when it fails rather than pretending it works.
 *
 * All methods are synchronised; callers are the repository's single coroutine scope anyway.
 */
class DynamicsProcessingEngine internal constructor(
    private val preferredBandCount: Int,
    private val factory: SessionEffectFactory,
) {
    constructor(preferredBandCount: Int = DynamicsProcessingPlan.DEFAULT_BAND_COUNT) : this(preferredBandCount, AndroidEffects)

    private class Attached(val effect: SessionEffect, val bandCount: Int)

    private val lock = Any()
    private val effects = HashMap<Int, Attached>()

    /**
     * Sessions whose working effect broke and could not be rebuilt yet, with the band count that
     * worked. The phone clearly supports the effect, so these show [TAKEN_OVER_WARNING], not the
     * "doesn't support" warning, and a rebuild tries only that layout: a smaller one can't help
     * while another app holds control, and each try is a binder round trip on every reconcile.
     */
    private val lost = HashMap<Int, Int>()

    /** Session 0 was refused; forgotten when it works or the user turns the fallback off. */
    private var globalRefused = false

    /** Why creating failed while no session had a working effect, or null: the phone lacks the effect. */
    private var unsupportedReason: String? = null

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
            if (applyTo(existing, sessionId, planner, enabled)) return true
            // applyTo released the broken effect. Keeping it in the map would leave the session
            // counted as attached while the music plays untuned, and every later apply would hit
            // the released handle. Forget it and build a fresh one below: that works after an
            // audioserver restart; while another app holds control it fails the same way and the
            // session stays lost until a later attach succeeds.
            effects.remove(sessionId)
            lost[sessionId] = existing.bandCount
            publish()
        }
        // Try our preferred resolution first, then coarser layouts: some vendor implementations
        // cap the post-EQ at 16 or 10 bands and refuse anything above that. A lost session tries
        // only the layout it already had working.
        var lastError: Throwable? = null
        val layouts = lost[sessionId]?.let { listOf(it) } ?: candidateBandCounts()
        for (bandCount in layouts) {
            val effect = try {
                factory.create(sessionId, bandCount)
            } catch (e: RuntimeException) {
                // Every refusal moves on to the next layout, IllegalArgumentException included: the
                // framework throws it for a missing effect type ("Effect type ... not supported")
                // and for a band count the effect HAL caps (setEngineArchitecture's BAD_VALUE). It
                // is rarely a closed session: AudioFlinger parks effects on sessions without a track.
                lastError = e
                continue
            }
            val attached = Attached(effect, bandCount)
            if (applyTo(attached, sessionId, planner, enabled)) {
                effects[sessionId] = attached
                lost.remove(sessionId)
                // A working effect proves the device supports it, whatever an earlier create said.
                unsupportedReason = null
                if (sessionId == 0) globalRefused = false
                logI("attached DynamicsProcessing to session $sessionId with $bandCount bands")
                publish()
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
            if (!applyTo(attached, session, plannerFor(session), enabled)) {
                effects.remove(session)
                lost[session] = attached.bandCount
            }
        }
        publish()
    }

    fun detach(sessionId: Int) = synchronized(lock) {
        effects.remove(sessionId)?.let { release(it.effect, sessionId) }
        lost.remove(sessionId)
        publish()
    }

    /** Release every effect on a session outside [sessionIds] and stop retrying lost ones there. */
    fun retain(sessionIds: Set<Int>) = synchronized(lock) {
        for (id in effects.keys - sessionIds) effects.remove(id)?.let { release(it.effect, id) }
        lost.keys.retainAll(sessionIds)
        publish()
    }

    fun detachAll() = synchronized(lock) {
        for ((session, attached) in effects) release(attached.effect, session)
        effects.clear()
        lost.clear()
        publish()
    }

    /** Forget that the global output mix was refused (the user turned the global fallback off). */
    fun clearWarning() = synchronized(lock) {
        globalRefused = false
        publish()
    }

    // ---- internals -------------------------------------------------------------------------

    private fun candidateBandCounts(): List<Int> =
        (listOf(preferredBandCount) + FALLBACK_BAND_COUNTS).filter { it <= preferredBandCount }.distinct()

    /**
     * Push [planner]'s plan into [attached]. Returns false (and releases the effect) when the
     * device rejects the parameters, so the caller can try another layout or give up.
     */
    private fun applyTo(attached: Attached, sessionId: Int, planner: Planner, enabled: Boolean): Boolean {
        val plan = planner.plan(attached.bandCount)
        return try {
            attached.effect.apply(plan, enabled)
            true
        } catch (e: UnsupportedOperationException) {
            logW("session $sessionId rejected ${attached.bandCount}-band plan: ${e.message}")
            release(attached.effect, sessionId); false
        } catch (e: IllegalStateException) {
            logW("session $sessionId effect in bad state: ${e.message}")
            release(attached.effect, sessionId); false
        } catch (e: RuntimeException) {
            logW("session $sessionId apply failed: ${e.message}")
            release(attached.effect, sessionId); false
        }
    }

    private fun release(effect: SessionEffect, sessionId: Int) {
        try {
            effect.release()
        } catch (e: RuntimeException) {
            logW("release on session $sessionId threw: ${e.message}")
        }
    }

    private fun onCreateFailed(sessionId: Int, cause: Throwable?) {
        val reason = cause?.message ?: cause?.javaClass?.simpleName ?: "unknown"
        when {
            // It had a working effect a moment ago, so the phone does support the effect: most
            // likely another app holds control of the session. The next attach tries again.
            sessionId in lost -> logW("session $sessionId: effect lost and not rebuilt yet: $reason")
            sessionId == 0 -> {
                // Expected on modern devices; not a sign the engine is broken.
                logW("global output mix (session 0) refused: $reason")
                globalRefused = true
            }
            else -> {
                logE("cannot create DynamicsProcessing on session $sessionId: $reason", cause)
                // One rejected session with other sessions working is a per-app quirk (e.g. a
                // session the player already closed). Zero working sessions after a real create
                // failure means the device simply lacks the effect: remember that so the UI can say so.
                unsupportedReason = if (effects.isNotEmpty()) null else reason
            }
        }
        publish()
    }

    /** Must be called with [lock] held. The most important explanation wins. */
    private fun publish() {
        val reason = unsupportedReason
        _status.value = EngineStatus(
            attachedSessions = effects.keys.toSet(),
            isAvailable = reason == null,
            warning = when {
                reason != null -> "This phone's audio stack doesn't support the DynamicsProcessing effect ($reason)."
                lost.isNotEmpty() -> TAKEN_OVER_WARNING
                globalRefused -> GLOBAL_REFUSED_WARNING
                else -> null
            },
        )
    }

    // Wrapped so the engine also runs in plain JVM unit tests, where android.util.Log is a stub that throws.
    private fun logI(msg: String) { runCatching { Log.i(TAG, msg) } }
    private fun logW(msg: String) { runCatching { Log.w(TAG, msg) } }
    private fun logE(msg: String, t: Throwable?) { runCatching { Log.e(TAG, msg, t) } }

    internal companion object {
        private const val TAG = "Earshot/DpEngine"
        private val FALLBACK_BAND_COUNTS = listOf(16, 10, 6)

        const val GLOBAL_REFUSED_WARNING =
            "This phone doesn't allow EQ on the global output. Only apps that announce their audio session can be tuned."
        const val TAKEN_OVER_WARNING =
            "Another app's sound effect (for example the player's own equaliser) has taken over a player, so the EQ is off for it until that app lets go."
    }
}

/** The real effects: one `DynamicsProcessing` per session. */
private object AndroidEffects : SessionEffectFactory {
    /** Stereo pair. The framework re-configures to the real channel count on first process. */
    private const val STEREO_CHANNELS = 2

    override fun create(sessionId: Int, bandCount: Int): SessionEffect {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            STEREO_CHANNELS,
            /* preEqInUse = */ false, /* preEqBandCount = */ 0,
            /* mbcInUse = */ false, /* mbcBandCount = */ 0,
            /* postEqInUse = */ true, /* postEqBandCount = */ bandCount,
            /* limiterInUse = */ true,
        ).build() // frame duration left at the framework default (variable / 10 ms)
        return AndroidEffect(DynamicsProcessing(/* priority = */ 0, sessionId, config))
    }
}

private class AndroidEffect(private val effect: DynamicsProcessing) : SessionEffect {
    override fun apply(plan: Plan, enabled: Boolean) {
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
    }

    override fun release() {
        try {
            effect.enabled = false
        } catch (_: RuntimeException) {
            // already dead; release anyway
        }
        effect.release()
    }
}
