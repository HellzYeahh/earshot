package dev.earshot.ui.sleep

import dev.earshot.core.SleepPermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The idle Sleep tab's lazy card list: the planned order, and no empty banners item. */
class SleepSetupCardsTest {

    @Test fun `cards keep the planned order with the banners after the alarms`() {
        val missing = SleepPermissions(exactAlarms = false)
        assertEquals(
            listOf(
                SleepSetupCard.HEADSET,
                SleepSetupCard.ALARMS,
                SleepSetupCard.PERMISSIONS,
                SleepSetupCard.TONIGHT,
                SleepSetupCard.SOUNDS,
                SleepSetupCard.NIGHT_OPTIONS,
            ),
            sleepSetupCards(missing),
        )
    }

    @Test fun `with every access granted the banners item is left out, not emitted empty`() {
        // An empty lazy item would still get the 12 dp gap; the old Column skipped it.
        assertEquals(
            listOf(SleepSetupCard.HEADSET, SleepSetupCard.ALARMS, SleepSetupCard.TONIGHT, SleepSetupCard.SOUNDS, SleepSetupCard.NIGHT_OPTIONS),
            sleepSetupCards(SleepPermissions()),
        )
    }

    @Test fun `each missing top access shows the banners, Do Not Disturb does not`() {
        assertTrue(SleepPermissions(exactAlarms = false).hasBanner)
        assertTrue(SleepPermissions(fullScreenAlarm = false).hasBanner)
        assertTrue(SleepPermissions(notifications = false).hasBanner)
        // DND access is asked for in the night options card, only when that extra is wanted.
        assertFalse(SleepPermissions(doNotDisturb = false).hasBanner)
        assertFalse(SleepPermissions(doNotDisturb = true).hasBanner)
    }
}
