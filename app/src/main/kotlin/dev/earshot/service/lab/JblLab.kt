package dev.earshot.service.lab

import dev.earshot.bt.JblConnection
import dev.earshot.core.LabGroup
import dev.earshot.core.LabProfile
import dev.earshot.core.LabReading
import dev.earshot.protocol.jbl.JblRead
import dev.earshot.protocol.jbl.JblReads
import dev.earshot.protocol.jbl.JblWriteGuard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The Lab on JBL's H-AA: the documented reads in [JblReads] (jbl-control's probes of a Wave Buds 2
 * plus the app's device-info and battery reads). Command ids around them include factory reset
 * (`95`), power off (`97`) and firmware transfer (`F0`), and H-AA has no feature list to narrow
 * the choice, so the Lab never walks ids: each read is checked against [JblWriteGuard.isRead]
 * before it is sent, and the connection checks the guard again on its single write path.
 *
 * H-AA numbers nothing, so a read's id is its command byte and first payload byte (`91 21` = 0x9121)
 * and "answered" means the next frame of the right kind ([JblRead.answers]). A headset without the
 * feature stays silent, which shows as "no answer".
 */
object JblLab {
    const val STACK = "JBL H-AA"

    fun profile(): LabProfile {
        val reads = JblReads.ALL
        return LabProfile(
            stack = STACK,
            groupNoun = "Group",
            groups = reads.groupBy { it.group }.map { (group, list) -> LabGroup(group, JblReads.groupName(group), list.size) },
            scanDescription = "The documented reads of the selected groups",
            deepReadCount = reads.size,
            deepScanDescription = "Every documented JBL read (${reads.size} reads): device info and battery, listening mode and level, EQ, " +
                "Smart Audio & Video, voice prompts, VoiceAware and Find my buds. A feature the headset lacks stays silent.",
            safetyNote = "Each request passes Earshot's JBL write guard as a read; factory reset, power off and firmware transfer " +
                "are on its never list and refused on the same path.",
        )
    }

    fun scan(conn: JblConnection, groupIds: List<Int>, deep: Boolean): Flow<LabReading> = flow {
        for (read in JblReads.ALL.filter { deep || it.group in groupIds }) emit(readOne(conn, read))
    }

    private suspend fun readOne(conn: JblConnection, read: JblRead): LabReading {
        val id = (read.frame.command shl 8) or (read.frame.sub ?: 0)
        val frame = if (!JblWriteGuard.isRead(read.frame)) {
            LabFrames.refused(read.group, id)
        } else {
            LabFrames.attempt({ conn.isOpen }, read.group, id) {
                val reply = conn.request(read.frame, attempts = 1, answers = read.answers)
                if (reply == null) LabFrames.error(read.group, id, "no answer") else LabFrames.response(read.group, id, reply.payload)
            }
        }
        return LabReading(
            block = read.group,
            function = id,
            knownName = read.name,
            frames = listOf(frame),
            address = "%02X".format(read.frame.command) + (read.frame.sub?.let { " %02X".format(it) } ?: ""),
            groupName = JblReads.groupName(read.group),
        )
    }
}
