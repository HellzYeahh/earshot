package dev.earshot.service.lab

import dev.earshot.bt.BragiConnection
import dev.earshot.core.LabGroup
import dev.earshot.core.LabProfile
import dev.earshot.core.LabReading
import dev.earshot.core.RawFrame
import dev.earshot.protocol.bragi.BragiModel
import dev.earshot.protocol.bragi.BragiRead
import dev.earshot.protocol.bragi.BragiReads
import dev.earshot.protocol.bragi.BragiServices
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The Lab on Bragi BOS-RPC (Skullcandy Crusher 1080, Method 360 / 540): the read-only methods on
 * the allow-list ([BragiReads]), grouped by service. Never a configure / set call, never the
 * pairing handshake, never a method number outside the shipped definitions. A Method earbud is
 * not asked about the Crusher bass or spatial audio, which it cannot have.
 */
object BragiLab {
    const val STACK = "Bragi BOS-RPC"
    const val TIMEOUT_MS = 2_000L

    fun profile(model: BragiModel): LabProfile {
        val reads = BragiReads.forFeatures(model.features)
        val skipped = BragiReads.SERVICE_NAMES.keys - reads.map { it.address.service }.toSet()
        return LabProfile(
            stack = STACK,
            groupNoun = "Service",
            groups = reads.groupBy { it.address.service }.map { (service, list) ->
                LabGroup(service, BragiReads.SERVICE_NAMES[service] ?: "service $service", list.size)
            },
            scanDescription = "The read-only calls of the selected services",
            deepReadCount = reads.size,
            deepScanDescription = "Every read-only call the ${model.displayName} has (${reads.size}), streaming reads without a subscription.",
            safetyNote = "Only the info, read and get methods on Earshot's Bragi allow-list; configure and set calls, the pairing handshake " +
                "and the firmware-update service are never sent.",
            note = skipped.takeIf { it.isNotEmpty() }?.let { s ->
                "Not asked: " + s.joinToString(", ") { BragiReads.SERVICE_NAMES.getValue(it) } + " (the ${model.displayName} doesn't have it)."
            },
        )
    }

    fun scan(conn: BragiConnection, model: BragiModel, groupIds: List<Int>, deep: Boolean): Flow<LabReading> = flow {
        for (read in BragiReads.forFeatures(model.features).filter { deep || it.address.service in groupIds }) {
            emit(
                LabReading(
                    block = read.address.service,
                    function = read.address.method,
                    knownName = read.name,
                    frames = listOf(readOne(conn, read)),
                    address = "${read.address.service}.${read.address.method}",
                    groupName = BragiReads.SERVICE_NAMES[read.address.service],
                ),
            )
        }
    }

    suspend fun readOne(conn: BragiConnection, read: BragiRead): RawFrame {
        val a = read.address
        if (!BragiServices.isRead(a)) return LabFrames.refused(a.service, a.method)
        return LabFrames.attempt({ conn.isOpen }, a.service, a.method) {
            // Unwatched: a method this firmware lacks must not count towards the half-dead-link watchdog.
            val reply = conn.callUnwatched(a, read.body, timeoutMs = TIMEOUT_MS, streaming = read.streaming)
            LabFrames.response(a.service, a.method, reply.payload)
        }
    }
}
