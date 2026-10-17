package dev.earshot.protocol.qcultra2

import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.hexToBytes
import dev.earshot.protocol.toHex
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Builders and parsers added from the Bose app 13.3.4 layouts (no hardware frame for some). */
class NewCodecsTest {

    @Test
    fun `bitset helper reads bit i from byte len-1-i_8`() {
        assertEquals(setOf(0), Bitset.decode("01".hexToBytes()))
        assertEquals(setOf(8), Bitset.decode("0100".hexToBytes()))
        assertEquals(setOf(15, 0), Bitset.decode("8001".hexToBytes()))
        assertEquals(setOf(1, 3), Bitset.decode("ff0a00".hexToBytes(), offset = 1, length = 1))
        assertEquals("0100", Bitset.encode(setOf(8), 2).toHex())
        assertFails { Bitset.encode(setOf(16), 2) }
        assertEquals(4, Bitset.bytesFor(setOf(31)))
        assertEquals(2, Bitset.bytesFor(emptySet(), minBytes = 2))
    }

    @Test
    fun `4_6 extended info decodes classic and LE bits`() {
        val mac = "2C:DA:46:00:00:02"
        assertEquals("04060106" + "2cda46000002", DeviceExtendedInfoQuery.request(mac).encode().toHex())
        // paired A2DP+HFP+AVRCP, connected A2DP+AVRCP, LE unicast paired, nothing LE connected
        val x = DeviceExtendedInfoQuery.parse("2cda46000002 07 05 00 00 01 00".hexToBytes())!!
        assertEquals(mac, x.mac)
        assertEquals(setOf(ClassicProfile.A2DP, ClassicProfile.HFP, ClassicProfile.AVRCP), x.pairedClassic)
        assertEquals(setOf(ClassicProfile.A2DP, ClassicProfile.AVRCP), x.connectedClassic)
        assertTrue(x.leUnicastPaired)
        assertFalse(x.leBroadcastPaired)
        assertFalse(x.leUnicastConnected)
        // a short frame still yields the classic bits
        val short = DeviceExtendedInfoQuery.parse("2cda46000002 01 01".hexToBytes())!!
        assertFalse(short.leUnicastConnected)
        assertNull(DeviceExtendedInfoQuery.parse("2cda46000002".hexToBytes()))
    }

    @Test
    fun `31_9 reset and 31_6 GET take the slot index`() {
        assertEquals("05", ModeReset.startPayload(5).toHex())
        assertEquals("04", ModeConfigs.getPayload(4).toHex())
        assertFails { ModeReset.startPayload(11) }
        assertFails { ModeConfigs.getPayload(-1) }
    }

    @Test
    fun `print console maps verbs to block 22`() {
        val bind = PrintConsole.parseCommand("bind uart")!!
        assertEquals(QcUltra2.PRINT_BIND, bind.address)
        assertEquals(BmapOperator.SET_GET, bind.operator)
        assertEquals("uart", String(bind.payload))
        assertEquals(QcUltra2.PRINT_VERBOSITY, PrintConsole.parseCommand("  VERBOSITY 3 ")!!.address)
        assertEquals("3", String(PrintConsole.parseCommand("verbosity 3")!!.payload))
        val out = PrintConsole.parseCommand("output 2")!!
        assertEquals(BmapOperator.START, out.operator)
        assertEquals("02", out.payload.toHex())
        assertEquals(0, PrintConsole.parseCommand("output")!!.payload.size)
        assertNull(PrintConsole.parseCommand("reboot"))
        assertEquals("hello", PrintConsole.render("68656c6c6f00".hexToBytes()))
        assertEquals("0102", PrintConsole.render("0102".hexToBytes()))
    }

    @Test
    fun `button event push reuses the 1_9 layout`() {
        val press = ButtonEventPush.parse("800913".hexToBytes())!!
        assertEquals(ButtonId.SHORTCUT, press.button)
        assertEquals(ButtonEvent.LONG_PRESS, press.event)
        assertEquals(ButtonAction.SPATIAL_AUDIO_MODE, press.action)
        assertEquals(ButtonAction.ALEXA, ButtonAction.fromCode(27))
    }
}
