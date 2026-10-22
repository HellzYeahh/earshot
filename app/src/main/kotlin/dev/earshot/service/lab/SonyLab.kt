package dev.earshot.service.lab

import dev.earshot.bt.SonyConnection
import dev.earshot.core.LabGroup
import dev.earshot.core.LabProfile
import dev.earshot.core.LabReading
import dev.earshot.protocol.sony.SonyRead
import dev.earshot.protocol.sony.SonyReads
import dev.earshot.service.SonySnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The Lab on Sony (MDR v2): the connect inquiries, then the GET inquiries for each function the
 * headset announced in its support lists ([SonyReads.forSupport]), grouped by function block.
 * MDR v2 command bytes sit in blocks of ten next to their writes, and table 1 also holds firmware
 * update, the test command, usage logging and factory reset, so no id is guessed.
 */
object SonyLab {
    const val STACK = "Sony MDR v2"

    private fun reads(s: SonySnapshot): List<SonyRead> = SonyReads.forSupport(s.t1, s.t2, table2 = s.protocol?.table2 == true)

    fun profile(snapshot: SonySnapshot): LabProfile {
        val reads = reads(snapshot)
        return LabProfile(
            stack = STACK,
            groupNoun = "Block",
            groups = reads.groupBy { it.group }.map { (group, list) ->
                LabGroup(group, SonyReads.groupName(group), list.size, code = if (group >= SonyReads.TABLE2_GROUPS) "T2 %02x".format(group and 0xFF) else "%02x".format(group))
            },
            scanDescription = "The GET inquiries of the selected function blocks",
            deepReadCount = reads.size,
            deepScanDescription = if (snapshot.t1.isEmpty()) {
                "The connect inquiries only: the headset hasn't announced its functions yet."
            } else {
                "The connect inquiries, then a GET for each function the headset announced (${reads.size} reads)."
            },
            safetyNote = "Only GET inquiries on Earshot's Sony allow-list; firmware update, test mode, usage logging, " +
                "factory reset and unpair are refused on the same path.",
        )
    }

    fun scan(conn: SonyConnection, snapshot: SonySnapshot, groupIds: List<Int>, deep: Boolean): Flow<LabReading> = flow {
        for (read in reads(snapshot).filter { deep || it.group in groupIds }) {
            val id = (read.table.ordinal shl 16) or (read.command shl 8) or read.type
            val message = read.message()
            val frame = if (!SonyReads.isRead(message)) {
                LabFrames.refused(read.group, id)
            } else {
                LabFrames.attempt({ conn.isOpen }, read.group, id) {
                    LabFrames.response(read.group, id, conn.request(message).payload)
                }
            }
            emit(LabReading(read.group, id, read.name, listOf(frame), address = read.address, groupName = SonyReads.groupName(read.group)))
        }
    }
}
