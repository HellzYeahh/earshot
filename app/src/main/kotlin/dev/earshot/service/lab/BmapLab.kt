package dev.earshot.service.lab

import dev.earshot.bt.BmapConnection
import dev.earshot.bt.ReplyMode
import dev.earshot.bt.WriteGuard
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.LabGroup
import dev.earshot.core.LabProfile
import dev.earshot.core.LabReading
import dev.earshot.core.RawFrame
import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.BmapPacket
import dev.earshot.protocol.FunctionAddress
import dev.earshot.protocol.qcultra2.FunctionNames
import dev.earshot.protocol.toHex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The Lab on Bose (BMAP): the GET sweep it has had since 0.5, unchanged. A BMAP GET cannot change
 * anything (every write the Bose app makes is SET_GET or START, and [WriteGuard] passes GETs only
 * for that reason), so here, and only here, the Lab walks ids: Scan reads functions 0-40 of the
 * selected blocks, Deep scan every block 0-63 and function 0-63. Unknown blocks answer
 * FblockNotSupp at once, so most of the 4096 reads are cheap.
 */
object BmapLab {
    const val STACK = "Bose BMAP"
    const val SCAN_MAX_FUNCTION = 40
    const val DEEP_MAX = 63
    const val TIMEOUT_MS = 1_500L
    val DEFAULT_BLOCKS: Set<Int> = setOf(1, 2, 31)

    /** The function blocks the protocol catalogue names, as scan targets. */
    fun profile(): LabProfile = LabProfile(
        stack = STACK,
        groupNoun = "Block",
        groups = FunctionNames.blocks.toSortedMap().map { (id, name) -> LabGroup(id, name, SCAN_MAX_FUNCTION + 1) },
        defaultGroups = DEFAULT_BLOCKS,
        scanDescription = "GET on functions 0–$SCAN_MAX_FUNCTION of each selected block",
        deepReadCount = (DEEP_MAX + 1) * (DEEP_MAX + 1),
        deepScanDescription = "GET on every block 0–$DEEP_MAX and function 0–$DEEP_MAX once (4096 reads). Blocks the headphones lack answer at once; it takes a few minutes.",
        safetyNote = "Every request is a BMAP GET, which cannot change a setting. The write guard on the same path refuses the firmware-update and authentication blocks and the reset functions.",
        printConsole = true,
    )

    /** Functions 0..[SCAN_MAX_FUNCTION] of [groupIds], or with [deep] every block and function 0..[DEEP_MAX]. */
    fun scan(conn: BmapConnection, groupIds: List<Int>, deep: Boolean): Flow<LabReading> =
        if (deep) sweep(conn, (0..DEEP_MAX).toList(), DEEP_MAX) else sweep(conn, groupIds, SCAN_MAX_FUNCTION)

    fun sweep(conn: BmapConnection, blocks: List<Int>, maxFunction: Int): Flow<LabReading> = flow {
        for (block in blocks) for (function in 0..maxFunction) {
            val address = FunctionAddress(block, function)
            val packet = BmapPacket.get(address)
            // The read check, spelled out: only a GET the guard passes may leave.
            check(packet.operator == BmapOperator.GET && WriteGuard.refusalReason(packet) == null)
            // SINGLE keeps a scan fast: unsupported functions answer ERROR 4 immediately, and the
            // rare multi-frame answer can be re-read with readRaw. Unwatched: silent functions are
            // expected here and must not trip the half-dead-link watchdog.
            val frames = try {
                conn.requestUnwatched(packet, ReplyMode.SINGLE, timeoutMs = TIMEOUT_MS)
            } catch (e: HeadphoneCommandException) {
                if (!conn.isOpen) throw e
                emptyList() // no reply = silent function, still worth listing
            }
            emit(
                LabReading(
                    block, function,
                    knownName = FunctionNames.functionName(address),
                    frames = frames.map { it.toRawFrame() },
                    groupName = FunctionNames.blockName(block),
                ),
            )
        }
    }

    /** One GET with every frame that comes back (battery components, the paired list). */
    suspend fun readRaw(conn: BmapConnection, block: Int, function: Int): List<RawFrame> =
        conn.requestUnwatched(BmapPacket.get(FunctionAddress(block, function)), ReplyMode.MULTI, timeoutMs = TIMEOUT_MS).map { it.toRawFrame() }

    fun BmapPacket.toRawFrame() = RawFrame(
        block = block,
        function = function,
        operatorName = operator.name,
        payloadHex = payload.toHex(),
        isError = isError,
        errorName = if (isError) error?.name ?: errorCode?.let { "ERROR_$it" } else null,
    )
}
