package dev.earshot.call

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Android 17 ignores the call-volume boost from the background without an error; the read-back
 * is the only sign (docs/research/play-readiness.md A1.1). It only counts as a refusal where
 * Android really requires more than Earshot has, so the rule is right before and after the
 * targetSdk bump.
 */
class CallBoostRefusalTest {

    private fun refused(
        before: Int,
        after: Int,
        target: Int,
        sdk: Int = 37,
        targetSdk: Int = 37,
        visible: Boolean = false,
        foregroundService: Boolean = true,
    ) = CallVolumeBoost.refusedInBackground(before, after, target, sdk, targetSdk, visible, foregroundService)

    @Test fun `a volume that did not move from the background on Android 17 at target 37 was refused`() {
        // The headset service runs, but without while-in-use status that isn't enough at target 37.
        assertTrue(refused(before = 4, after = 4, target = 7))
    }

    @Test fun `a volume that moved is not a refusal, even short of the target`() {
        assertFalse(refused(before = 4, after = 7, target = 7))
        assertFalse(refused(before = 4, after = 6, target = 7)) // One UI capping it
    }

    @Test fun `nothing to change is not a refusal`() {
        assertFalse(refused(before = 7, after = 7, target = 7))
    }

    @Test fun `a visible Earshot or an older Android is never blamed`() {
        assertFalse(refused(before = 4, after = 4, target = 7, visible = true))
        assertFalse(refused(before = 4, after = 4, target = 7, sdk = 36))
        assertFalse(refused(before = 4, after = 4, target = 7, sdk = 36, foregroundService = false))
    }

    @Test fun `at target 36 on Android 17 the headset service is enough, so an unmoved volume is a cap`() {
        assertFalse(refused(before = 4, after = 4, target = 7, targetSdk = 36, foregroundService = true))
    }

    @Test fun `at target 36 on Android 17 without any foreground service it was refused`() {
        assertTrue(refused(before = 4, after = 4, target = 7, targetSdk = 36, foregroundService = false))
    }
}
