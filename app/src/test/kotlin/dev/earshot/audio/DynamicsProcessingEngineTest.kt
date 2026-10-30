package dev.earshot.audio

import dev.earshot.dsp.DynamicsProcessingPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The engine's attach / re-apply / fallback rules against fake effects that fail the way the
 * framework's DynamicsProcessing does (no Android needed).
 */
class DynamicsProcessingEngineTest {

    private class FakeEffect(val sessionId: Int, val bandCount: Int) : SessionEffect {
        /** Thrown by the next apply: what another app's higher-priority effect or a dead audioserver causes. */
        var applyFailure: RuntimeException? = null
        var released = false
        var applied = 0

        override fun apply(plan: DynamicsProcessingPlan.Plan, enabled: Boolean) {
            if (released) throw IllegalStateException("AudioEffect: effect not initialized")
            applyFailure?.let { throw it }
            applied++
        }

        override fun release() { released = true }
    }

    private class FakeEffects : SessionEffectFactory {
        val created = ArrayList<FakeEffect>()

        /** Decides per create call whether (and how) it fails; null = it works. */
        var failCreate: (sessionId: Int, bandCount: Int) -> RuntimeException? = { _, _ -> null }

        /** Whether a freshly created effect then refuses its parameters. */
        var failApply: (bandCount: Int) -> RuntimeException? = { null }

        override fun create(sessionId: Int, bandCount: Int): SessionEffect {
            failCreate(sessionId, bandCount)?.let { throw it }
            return FakeEffect(sessionId, bandCount).also {
                it.applyFailure = failApply(bandCount)
                created += it
            }
        }
    }

    private val planner = Planner { n ->
        DynamicsProcessingPlan.Plan(
            bands = List(n) { DynamicsProcessingPlan.Band(cutoffHz = 100.0 * (it + 1), gainDb = 0.0) },
            inputGainDb = -3.0,
            limiter = DynamicsProcessingPlan.LimiterParams(enabled = true),
            sampleRate = 48_000,
        )
    }

    private val effects = FakeEffects()
    private val engine = DynamicsProcessingEngine(32, effects)

    @Test fun `an effect that stops working is rebuilt instead of staying counted as attached`() {
        assertTrue(engine.attach(7, planner, enabled = true))
        val first = effects.created.single()
        // audioserver restarted: the old handle is dead, a new one works.
        first.applyFailure = RuntimeException("Error -32 (DEAD_OBJECT)")

        assertTrue(engine.attach(7, planner, enabled = true), "a fresh effect replaces the dead one")
        assertTrue(first.released)
        assertEquals(2, effects.created.size)
        assertEquals(setOf(7), engine.status.value.attachedSessions)
        assertNull(engine.status.value.warning)
        assertEquals(1, effects.created.last().applied)
    }

    @Test fun `a session another app took over is retried and never called unsupported`() {
        assertTrue(engine.attach(7, planner, enabled = true))
        // The player's own EQ takes control: every set fails with INVALID_OPERATION, new ones too.
        effects.created.single().applyFailure = UnsupportedOperationException("AudioEffect: invalid parameter operation")
        effects.failApply = { UnsupportedOperationException("AudioEffect: invalid parameter operation") }

        assertFalse(engine.attach(7, planner, enabled = true))
        val lost = engine.status.value
        assertEquals(emptySet(), lost.attachedSessions, "the dead effect is no longer counted")
        assertTrue(lost.isAvailable, "the phone does support the effect")
        assertEquals(DynamicsProcessingEngine.TAKEN_OVER_WARNING, lost.warning)

        // Still taken over on the next reconcile: same answer, still not "unsupported".
        assertFalse(engine.attach(7, planner, enabled = true))
        assertTrue(engine.status.value.isAvailable)

        // The other app lets go: the effect comes back and the warning goes.
        effects.failApply = { null }
        assertTrue(engine.attach(7, planner, enabled = true))
        assertEquals(setOf(7), engine.status.value.attachedSessions)
        assertNull(engine.status.value.warning)
        assertTrue(effects.created.dropLast(1).all { it.released }, "every refused effect was released")
    }

    @Test fun `a lost session that goes away stops being retried`() {
        assertTrue(engine.attach(7, planner, enabled = true))
        effects.created.single().applyFailure = UnsupportedOperationException("taken over")
        effects.failApply = { UnsupportedOperationException("taken over") }
        assertFalse(engine.attach(7, planner, enabled = true))
        assertEquals(DynamicsProcessingEngine.TAKEN_OVER_WARNING, engine.status.value.warning)

        engine.retain(emptySet()) // the player closed the session
        assertNull(engine.status.value.warning)
    }

    @Test fun `IllegalArgumentException from creating still tries the smaller layouts`() {
        // An effect HAL that caps the post-EQ at 16 bands answers BAD_VALUE for 32.
        effects.failCreate = { _, bands -> if (bands > 16) IllegalArgumentException("setEngineArchitecture: BAD_VALUE") else null }

        assertTrue(engine.attach(7, planner, enabled = true))
        assertEquals(16, effects.created.single().bandCount)
        assertTrue(engine.status.value.isAvailable)
    }

    @Test fun `a phone without the effect type says so`() {
        effects.failCreate = { _, _ -> IllegalArgumentException("Effect type 7261676f-6d75-7369-6364-28e2fd3ac39e not supported") }

        assertFalse(engine.attach(7, planner, enabled = true))
        val status = engine.status.value
        assertFalse(status.isAvailable)
        assertTrue(status.warning!!.contains("doesn't support"), status.warning)

        // Not sticky: once a session works, the claim is gone with its warning.
        effects.failCreate = { _, _ -> null }
        assertTrue(engine.attach(8, planner, enabled = true))
        assertTrue(engine.status.value.isAvailable)
        assertNull(engine.status.value.warning)
    }

    @Test fun `one refused session next to a working one is a per-app quirk`() {
        assertTrue(engine.attach(7, planner, enabled = true))
        effects.failCreate = { session, _ -> if (session == 9) IllegalArgumentException("invalid session") else null }

        assertFalse(engine.attach(9, planner, enabled = true))
        assertTrue(engine.status.value.isAvailable)
        assertNull(engine.status.value.warning)
    }

    @Test fun `the global mix refusal is its own warning and clears when the fallback goes off`() {
        effects.failCreate = { session, _ -> if (session == 0) RuntimeException("Cannot initialize effect engine") else null }

        assertFalse(engine.attach(0, planner, enabled = true))
        assertTrue(engine.status.value.isAvailable)
        assertEquals(DynamicsProcessingEngine.GLOBAL_REFUSED_WARNING, engine.status.value.warning)

        engine.clearWarning()
        assertNull(engine.status.value.warning)
    }

    @Test fun `retain releases effects outside the target sessions`() {
        assertTrue(engine.attach(7, planner, enabled = true))
        assertTrue(engine.attach(8, planner, enabled = true))

        engine.retain(setOf(8))
        assertEquals(setOf(8), engine.attached)
        assertTrue(effects.created.first { it.sessionId == 7 }.released)
        assertFalse(effects.created.first { it.sessionId == 8 }.released)
    }
}
