package dev.earshot.service.lab

import dev.earshot.bt.SennheiserConnection
import dev.earshot.core.LabGroup
import dev.earshot.core.LabProfile
import dev.earshot.core.LabReading
import dev.earshot.protocol.gaia.GaiaError
import dev.earshot.protocol.gaia.GaiaReply
import dev.earshot.protocol.sennheiser.SennheiserRead
import dev.earshot.protocol.sennheiser.SennheiserReads
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The Lab on Sennheiser (GAIA v3): discovery (the GAIA API version and both feature lists), then
 * every documented GET of each feature the headset reported ([SennheiserReads.forFeatures]). GAIA
 * numbers commands per feature and the neighbours of the reads include factory reset, firmware
 * update and pairing deletes, so no id is guessed. The feature reads follow the list the session
 * stored on the connection (the one [dev.earshot.protocol.sennheiser.SennheiserWriteGuard] checks).
 */
object SennheiserLab {
    const val STACK = "Sennheiser GAIA v3"

    fun profile(reported: Map<Int, Int>?): LabProfile {
        val reads = SennheiserReads.forFeatures(reported)
        val unread = SennheiserReads.unreadFeatures(reported)
        return LabProfile(
            stack = STACK,
            groupNoun = "Feature",
            groups = reads.groupBy { it.group }.map { (group, list) -> LabGroup(group, SennheiserReads.groupName(group), list.size) },
            scanDescription = "The documented GETs of the selected features",
            deepReadCount = reads.size,
            deepScanDescription = if (reported == null) {
                "Discovery only: the headset hasn't listed its features, so Earshot's guard lets nothing else out."
            } else {
                "Discovery (GAIA API version and both feature lists), then every documented GET of each feature the headset reported (${reads.size} reads)."
            },
            safetyNote = "Each request passes Earshot's Sennheiser write guard with the headset's own feature list; " +
                "factory reset, firmware update, reboot and pairing deletes are refused on the same path.",
            note = unread.takeIf { it.isNotEmpty() }?.let { list ->
                "Also reported, with no documented read: " + list.joinToString(", ") { "$it ${SennheiserReads.groupName(it)}" }
            },
        )
    }

    fun scan(conn: SennheiserConnection, groupIds: List<Int>, deep: Boolean): Flow<LabReading> = flow {
        // Discovery first: on a Deep scan it shows what the feature reads below are built from.
        if (deep || SennheiserReads.DISCOVERY_GROUP in groupIds) {
            for (read in SennheiserReads.DISCOVERY) emit(readOne(conn, read))
        }
        val reads = SennheiserReads.forFeatures(conn.features)
            .filter { it.group != SennheiserReads.DISCOVERY_GROUP && (deep || it.group in groupIds) }
        for (read in reads) emit(readOne(conn, read))
    }

    private suspend fun readOne(conn: SennheiserConnection, read: SennheiserRead): LabReading {
        // Qualcomm and Sennheiser both have a command 0x0001: the vendor keeps the ids apart.
        val id = (read.vendor shl 16) or read.command
        val frame = if (!SennheiserReads.isRead(read, conn.features)) {
            LabFrames.refused(read.group, id)
        } else {
            LabFrames.attempt({ conn.isOpen }, read.group, id) {
                val timeout = if (read.slow) SennheiserConnection.SLOW_TIMEOUT_MS else SennheiserConnection.DEFAULT_TIMEOUT_MS
                when (val reply = conn.request(read.vendor, read.command, read.payload, timeout)) {
                    is GaiaReply.Ok -> LabFrames.response(read.group, id, reply.payload)
                    is GaiaReply.Error -> LabFrames.error(read.group, id, GaiaError.name(reply.code), "%02x".format(reply.code))
                }
            }
        }
        return LabReading(
            block = read.group,
            function = id,
            knownName = read.name,
            frames = listOf(frame),
            address = read.address,
            groupName = SennheiserReads.groupName(read.group),
        )
    }
}
