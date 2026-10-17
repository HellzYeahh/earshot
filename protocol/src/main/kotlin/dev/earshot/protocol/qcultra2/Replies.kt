package dev.earshot.protocol.qcultra2

import dev.earshot.protocol.BmapException
import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.BmapPacket
import dev.earshot.protocol.FunctionAddress

/**
 * Picks the answer to a request out of whatever frames the socket produced.
 *
 * A read can carry more than the reply: unsolicited STATUS notifications (battery, mode
 * changes made on the headphones), the PROCESSING ack some firmware sends before the real
 * answer, or the greeting burst after connect. Only frames from the requested address count.
 */
object Replies {
    /**
     * The first frame from [address] that is not PROCESSING. Throws [BmapException] if that
     * frame is an ERROR. Returns null when no frame from [address] is present (caller keeps
     * reading or times out). Frames from other addresses are ignored.
     *
     * bosectl treats "frames arrived, none from the asked address" as a desynced socket
     * (every read returning the previous request's answer). This helper stays neutral and
     * returns null; the connection layer decides whether repeated nulls mean reconnect.
     */
    fun pick(frames: List<BmapPacket>, address: FunctionAddress): BmapPacket? {
        for (frame in frames) {
            if (!frame.matches(address)) continue
            when (frame.operator) {
                BmapOperator.PROCESSING -> continue
                BmapOperator.ERROR -> throw BmapException.from(frame)
                else -> return frame
            }
        }
        return null
    }

    /** [pick] that also treats a missing reply as a failure. */
    fun require(frames: List<BmapPacket>, address: FunctionAddress): BmapPacket =
        pick(frames, address) ?: throw BmapException(address, null, null, "$address: no reply")

    /** True when the frames contain a PROCESSING ack for [address] (more STATUS frames will follow). */
    fun isProcessing(frames: List<BmapPacket>, address: FunctionAddress): Boolean =
        frames.any { it.matches(address) && it.operator == BmapOperator.PROCESSING }

    /**
     * Collect the `[31.6]` STATUS frames of a GetAll / ModeConfig START dump into slot → config,
     * sorted by slot. Later frames for the same slot win. Frames from other addresses,
     * PROCESSING/RESULT acks and undersized payloads are ignored; an ERROR on `[31.6]` throws.
     */
    fun collectModeConfigs(frames: List<BmapPacket>): Map<Int, ModeConfig> {
        val out = sortedMapOf<Int, ModeConfig>()
        for (frame in frames) {
            if (!frame.matches(QcUltra2.MODE_CONFIG)) continue
            if (frame.operator == BmapOperator.ERROR) throw BmapException.from(frame)
            if (frame.operator != BmapOperator.STATUS) continue
            val config = ModeConfigs.parse(frame.payload) ?: continue
            out[config.index] = config
        }
        return out
    }

    /** Unsolicited STATUS frames (anything not addressed to [requested]), e.g. battery or mode pushes. */
    fun unsolicited(frames: List<BmapPacket>, requested: FunctionAddress?): List<BmapPacket> =
        frames.filter { it.operator == BmapOperator.STATUS && (requested == null || !it.matches(requested)) }
}
