package dev.earshot.sleep

import dev.earshot.core.SleepPlan
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Android 17's background-audio rules (docs/research/play-readiness.md A1), before and after the
 * targetSdk bump: the same build must behave right on Android 16 and 17, at target 36 and 37.
 */
class BackgroundAudioTest {

    @After
    fun noScreens() {
        repeat(BackgroundAudio.visibleScreens.value) { BackgroundAudio.screenStopped() }
    }

    private fun may(sdk: Int, target: Int, visible: Boolean = false, fgs: Boolean = false, wiu: Boolean = false) =
        BackgroundAudio.mayUseAudio(sdk, target, appVisible = visible, foregroundService = fgs, whileInUseService = wiu)

    @Test
    fun `before Android 17 nothing is restricted`() {
        assertFalse(BackgroundAudio.hardened(36))
        assertFalse(BackgroundAudio.needsWhileInUse(36, 37))
        assertTrue(may(sdk = 36, target = 36))
        assertTrue(may(sdk = 36, target = 37))
        assertTrue(may(sdk = 28, target = 36))
    }

    @Test
    fun `Android 17 at target 36 accepts any foreground service`() {
        assertTrue(BackgroundAudio.hardened(37))
        assertFalse(BackgroundAudio.needsWhileInUse(37, 36))
        assertFalse(may(sdk = 37, target = 36))
        assertTrue(may(sdk = 37, target = 36, fgs = true))
        assertTrue(may(sdk = 37, target = 36, visible = true))
    }

    @Test
    fun `Android 17 at target 37 needs while-in-use status or a visible app`() {
        assertTrue(BackgroundAudio.needsWhileInUse(37, 37))
        // The headset service started by a Bluetooth connect: a foreground service, but no WIU.
        assertFalse(may(sdk = 37, target = 37, fgs = true))
        assertTrue(may(sdk = 37, target = 37, fgs = true, wiu = true))
        assertTrue(may(sdk = 37, target = 37, visible = true))
        // Later releases keep the rule.
        assertFalse(may(sdk = 38, target = 38, fgs = true))
    }

    @Test
    fun `screens are counted, so the alarm screen over the main one still reads as visible`() {
        assertFalse(BackgroundAudio.isAppVisible)
        BackgroundAudio.screenStarted() // main screen
        BackgroundAudio.screenStarted() // alarm screen starts before the main one stops
        BackgroundAudio.screenStopped() // main screen stops under it
        assertTrue(BackgroundAudio.isAppVisible)
        BackgroundAudio.screenStopped()
        assertFalse(BackgroundAudio.isAppVisible)
    }

    @Test
    fun `an unmatched stop never goes below zero`() {
        BackgroundAudio.screenStopped()
        assertEquals(0, BackgroundAudio.visibleScreens.value)
        BackgroundAudio.screenStarted()
        assertTrue(BackgroundAudio.isAppVisible)
    }

    @Test
    fun `a night waiting for a tap survives the store, and older saves read as not waiting`() {
        val json = SleepStore.json
        val waiting = NightRecord(active = ActiveNight(SleepPlan(), 1, 2, 3, soundsWaitForTap = true))
        assertEquals(waiting, json.decodeFromString(NightRecord.serializer(), json.encodeToString(NightRecord.serializer(), waiting)))

        val older = """{"active":{"plan":{},"startedAtMillis":1,"endsAtMillis":2,"fadeOutStartsAtMillis":3}}"""
        assertFalse(json.decodeFromString(NightRecord.serializer(), older).active!!.soundsWaitForTap)
    }
}
