package dev.earshot.protocol.soundcore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Lab's Soundcore reads pass each model's allow-list as reads, and nothing on the never-list ever does. */
class SoundcoreReadsTest {

    private val models: List<SoundcoreModel?> = SoundcoreModel.entries + listOf(null)

    @Test fun `every model's reads are reads its own allow-list lets out`() {
        for (model in models) {
            val reads = SoundcoreReads.forModel(model)
            assertTrue(reads.isNotEmpty(), "$model")
            for (read in reads) {
                val p = read.packet()
                assertFalse(p.isWrite, "${model?.code}: ${SoundcorePacket.commandName(read.command)} is a write")
                assertEquals(0, p.body.size)
                assertTrue(SoundcoreWriteGuard.isAllowed(model, p), "${model?.code}: ${SoundcorePacket.commandName(read.command)}")
                assertFalse(read.command in SoundcoreWriteGuard.NEVER)
                assertTrue(read.category in SoundcoreReads.CATEGORY_NAMES, "a named group")
            }
        }
    }

    @Test fun `no documented read is a write or on the never-list`() {
        for (read in SoundcoreReads.DOCUMENTED) {
            assertTrue(read.command and 0xFF < 0x80, SoundcorePacket.commandName(read.command))
            assertFalse(read.command in SoundcoreWriteGuard.NEVER)
        }
        for (model in models) for (never in SoundcoreWriteGuard.NEVER) assertFalse(SoundcoreReads.isRead(model, never))
    }

    @Test fun `each model gets the reads its table documents`() {
        // The tested Sleep A30 keeps its original list: state, info, session, alarms, sleep.
        assertEquals(listOf(0x0101, 0x0105, 0x0501, 0x1401, 0x1503), SoundcoreReads.forModel(null).map { it.command })
        assertEquals(listOf(0x0101, 0x0105, 0x0501, 0x1401, 0x1503), SoundcoreReads.forModel(SoundcoreModel.SLEEP_A30).map { it.command })
        // Gen-2 models: the state reads, plus the capability table where the model's session-init needs it.
        assertEquals(listOf(0x0101, 0x0105), SoundcoreReads.forModel(SoundcoreModel.LIBERTY_4_NC).map { it.command })
        assertEquals(listOf(0x0101, 0x0105), SoundcoreReads.forModel(SoundcoreModel.SPACE_ONE_PRO).map { it.command })
        assertEquals(listOf(0x0101, 0x0105, 0x0501), SoundcoreReads.forModel(SoundcoreModel.LIBERTY_5).map { it.command })
        // A model Earshot only reads: no session read either.
        assertEquals(listOf(0x0101, 0x0105), SoundcoreReads.forModel(SoundcoreModel.LIBERTY_5_PRO).map { it.command })
    }
}
