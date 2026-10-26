package dev.earshot.virtual

import dev.earshot.protocol.BmapPacket
import dev.earshot.protocol.qcultra2.Notifications
import dev.earshot.protocol.qcultra2.QcUltra2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Demo mode's "press a control" reaches the virtual unit through the neutral [DemoPressTarget]. */
class DemoPressTargetTest {

    @Test fun `the unit whose session started last takes the presses`() {
        val first = VirtualQcUltra2()
        val second = VirtualQcUltra2()
        first.onSessionStart()
        assertSame(first, DemoPresses.current())
        second.onSessionStart()
        assertSame(second, DemoPresses.current())
    }

    @Test fun `a press pushes what the headphones would, the main button as configured`() {
        val unit = VirtualQcUltra2(clock = { 0L })
        unit.onSessionStart()
        unit.handle(BmapPacket.setGet(QcUltra2.NOTIFY_BY_FBLOCK, Notifications.byFblockPayload(setOf(2, 5))))
        val target: DemoPressTarget = unit
        target.pressPlayPause()
        target.swipeVolume(+2)
        target.pressMainButton()
        assertEquals(listOf(QcUltra2.AUDIO_STATUS, QcUltra2.VOLUME, QcUltra2.STATUS_BUTTON), unit.drainDeferred().map { it.address })
        assertEquals(24, unit.volume)
    }
}
