package dev.earshot.protocol.sony

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Lab's Sony reads: GET inquiries for announced functions, all on the allow-list, none of the dangerous blocks. */
class SonyReadsTest {

    /** Every function id the driver knows, on both tables: the widest list a headset could announce. */
    private val allT1: Set<Int> = (0x00..0xFF).toSet()
    private val allT2: Set<Int> = (0x00..0xFF).toSet()

    @Test fun `every read for every announced function is a GET on the allow-list`() {
        val reads = SonyReads.forSupport(allT1, allT2, table2 = true)
        for (read in reads) {
            val m = read.message()
            assertTrue(SonyReads.isRead(m), read.address)
            assertTrue(SonyWriteGuard.isAllowed(m), read.address)
            assertFalse(m.isNotify)
            assertTrue(read.group in SonyReads.GROUP_NAMES, read.address)
        }
        assertEquals(reads.size, reads.map { it.address }.toSet().size, "no duplicates")
    }

    @Test fun `firmware update, test, logging, reset and unpair are never reads`() {
        val forbidden1 = (T1.UPDT.toList() + listOf(T1.UPDT_TRANSFER_DATA, T1.UPDT_NTFY_MESSAGE, T1.GET_TEST, T1.LOG_SET_STATUS, T1.LOG_NTFY_PARAM))
        for (command in forbidden1) for (type in 0..0xFF) {
            assertFalse(SonyReads.isRead(SonyMessage(SonyTable.T1, byteArrayOf(command.toByte(), type.toByte()))), "%02x %02x".format(command, type))
        }
        for (type in 0..0xFF) {
            assertFalse(SonyReads.isRead(SonyMessage(SonyTable.T2, byteArrayOf(T2.PERI_SET_EXTENDED_PARAM.toByte(), type.toByte(), 0))))
            assertFalse(SonyReads.isRead(SonyMessage(SonyTable.T1, byteArrayOf(T1.SYSTEM_SET_PARAM.toByte(), type.toByte(), 0))))
        }
        // SYSTEM reset settings, NC test mode and factory power off, with any value byte.
        assertFalse(SonyReads.isRead(SonyMessage(SonyTable.T1, byteArrayOf(T1.SYSTEM_SET_STATUS.toByte(), SystemType.RESET_SETTINGS.toByte(), 0))))
        assertFalse(SonyReads.isRead(SonyMessage(SonyTable.T1, byteArrayOf(T1.NCASM_GET_PARAM.toByte(), NcAsmType.NC_TEST_MODE.toByte()))))
        assertFalse(SonyReads.isRead(SonyMessage(SonyTable.T1, byteArrayOf(T1.POWER_SET_STATUS.toByte(), PowerType.POWER_OFF.toByte(), 0xFF.toByte()))))
        // Every table-1 / table-2 command that is not a GET is refused as a read, even where the guard allows it as a write.
        for (command in 0..0xFF) if (command !in SonyReads.T1_GETS) {
            for (type in 0..0xFF) assertFalse(SonyReads.isRead(SonyMessage(SonyTable.T1, byteArrayOf(command.toByte(), type.toByte(), 0, 0))))
        }
    }

    @Test fun `only the connect inquiries before the support list, then one GET set per announced function`() {
        assertEquals(SonyReads.CONNECT.map { it.address }, SonyReads.forSupport(emptySet(), emptySet(), table2 = false).map { it.address })
        // The VirtualSony WH-1000XM5 list.
        val t1 = setOf(0x11, 0x12, 0x20, 0x23, 0x25, 0x27, 0x32, 0x50, 0x6B, 0x70, 0x90, 0xA1, 0xC1, 0xC2, 0xD1, 0xD2, 0xE1, 0xE2, 0xF1, 0xF3, 0xF9, 0xFC)
        val reads = SonyReads.forSupport(t1, setOf(0x30, 0x31, 0x41), table2 = true)
        val addresses = reads.map { it.address }
        assertTrue("T1 66 17" in addresses && "T1 60 17" in addresses, "NCASM type 0x17")
        assertTrue("T1 22 00" in addresses, "battery")
        assertTrue("T1 26 05" in addresses && "T1 20 05" in addresses, "auto-off with wearing detection")
        assertTrue("T2 46 01" in addresses, "voice guidance")
        assertTrue(addresses.none { it.startsWith("T1 22 01") }, "no left/right battery read on a headband")
        assertEquals(6 + 1 + 1 + 2 + 3 + 2 + 4 + 2 + 1 + 2 + 2, reads.size)
    }
}
