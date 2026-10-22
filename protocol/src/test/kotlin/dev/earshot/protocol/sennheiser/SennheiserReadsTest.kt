package dev.earshot.protocol.sennheiser

import dev.earshot.protocol.gaia.GaiaCommand
import dev.earshot.protocol.gaia.GaiaFeaturePage
import dev.earshot.protocol.gaia.GaiaVendor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Lab's Sennheiser reads: discovery, then the guard's own GETs for reported features, nothing blocked. */
class SennheiserReadsTest {

    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val m4 = GaiaFeaturePage.parse(hex("00 09 01 08 06 0C 01 03 02 10 02 04 02 02 01 0A 02 14 01 0B 01 0D 01 00 04"))!!.features

    @Test fun `every guard read has a name and nothing else is a feature read`() {
        val guarded = SennheiserWriteGuard.READS.keys - SennheiserCommands.SUPPORTED_FEATURES
        assertEquals(guarded, SennheiserReads.FEATURE_READS.map { it.command }.toSet())
        assertEquals(guarded, SennheiserReads.NAMES.keys)
        for (read in SennheiserReads.FEATURE_READS) {
            assertEquals(GaiaCommand.TYPE_COMMAND, GaiaCommand.type(read.command))
            assertEquals(GaiaCommand.feature(read.command), read.group)
            assertTrue(read.payload.contentEquals(SennheiserWriteGuard.READS.getValue(read.command)))
        }
    }

    @Test fun `a Momentum 4's list passes the guard and holds no blocked id`() {
        val reads = SennheiserReads.forFeatures(m4)
        assertEquals(3 + 2 + 3 + 4 + 3 + 2 + 3 + 3, reads.size)
        for (read in reads) {
            assertNull(SennheiserWriteGuard.refusal(read.frame(), m4), read.name)
            if (read.vendor == SennheiserCommands.VENDOR) {
                assertTrue(read.command !in SennheiserWriteGuard.BLOCKED_SENNHEISER, read.name)
                assertTrue(GaiaCommand.feature(read.command) !in SennheiserWriteGuard.BLOCKED_SENNHEISER_FEATURES)
            } else {
                assertEquals(GaiaVendor.QUALCOMM, read.vendor)
                assertTrue(read.command !in SennheiserWriteGuard.BLOCKED_QUALCOMM)
                assertTrue(GaiaCommand.feature(read.command) !in SennheiserWriteGuard.BLOCKED_QUALCOMM_FEATURES)
            }
        }
        // Features with no documented GET are named, not scanned.
        assertEquals(listOf(10, 11, 16, 20), SennheiserReads.unreadFeatures(m4))
    }

    @Test fun `before the feature list only discovery goes out, and gen-1 EQ is never read`() {
        assertEquals(SennheiserReads.DISCOVERY, SennheiserReads.forFeatures(null))
        val gen1 = m4 + (SennheiserFeature.USER_EQ to 5)
        val commands = SennheiserReads.forFeatures(gen1).map { it.command }
        assertTrue(SennheiserCommands.EQ_ALL_GET !in commands && SennheiserCommands.EQ_CONFIG_GET !in commands && SennheiserCommands.EQ_SUBMODE_GET !in commands)
        // Only the features the headset reported.
        val ancOnly = mapOf(SennheiserFeature.ANC to 1, SennheiserFeature.CORE to 4)
        assertEquals(setOf(SennheiserReads.DISCOVERY_GROUP, SennheiserFeature.ANC), SennheiserReads.forFeatures(ancOnly).map { it.group }.toSet())
    }
}
