package dev.earshot.protocol

/**
 * Reassembles BMAP frames from a byte stream.
 *
 * RFCOMM is a stream, not a datagram transport: one `read()` can return half a frame, or a
 * dozen frames glued together (the `[31.6]` GetAll dump is one STATUS per mode slot and
 * regularly arrives across several reads). Feed every chunk in here and consume the complete
 * packets it yields; whatever is left over is kept for the next chunk.
 *
 * Desync guard: the stream has no sync word and no checksum, so once we are off by a byte
 * every later frame decodes as garbage. Two things flag that state:
 *  - the pending bytes hold a full header whose operator nibble is not a BMAP operator
 *    (valid operators are 0..7), or
 *  - the pending bytes exceed [maxPending] without ever yielding a frame (the longest legal
 *    frame is 4 + 255 bytes, so anything past 1 KB that still does not decode is not BMAP).
 * In both cases the pending bytes are dropped, [desyncCount] / [droppedBytes] are bumped and
 * [onDesync] is called with the discarded bytes. The caller should treat that as "reissue the
 * request", or reconnect if it keeps happening.
 *
 * Framing itself lives in [BmapPacket.decodeStream]; this class only owns the carry-over.
 * Not thread-safe: feed it from the single reader loop.
 */
class BmapFrameBuffer(
    private val maxPending: Int = DEFAULT_MAX_PENDING,
    private val onDesync: ((dropped: ByteArray) -> Unit)? = null,
) {
    private var pending: ByteArray = BmapPacket.EMPTY

    /** Number of times the buffer gave up on its pending bytes. */
    var desyncCount: Int = 0
        private set

    /** Total bytes discarded by desync drops. */
    var droppedBytes: Long = 0
        private set

    /** Bytes waiting for the rest of a frame. */
    val pendingSize: Int get() = pending.size

    /** Copy of the bytes waiting for the rest of a frame (for logging / the Lab screen). */
    fun pendingBytes(): ByteArray = pending.copyOf()

    /**
     * Append [length] bytes of [chunk] starting at [offset] and return every frame that is now
     * complete, in arrival order. Returns an empty list when more bytes are needed.
     */
    fun feed(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size - offset): List<BmapPacket> {
        require(offset >= 0 && length >= 0 && offset + length <= chunk.size) { "bad chunk range" }
        if (length == 0) return emptyList()
        pending = if (pending.isEmpty()) {
            chunk.copyOfRange(offset, offset + length)
        } else {
            val merged = ByteArray(pending.size + length)
            pending.copyInto(merged)
            chunk.copyInto(merged, pending.size, offset, offset + length)
            merged
        }
        val result = BmapPacket.decodeStream(pending)
        pending = if (result.consumed == pending.size) BmapPacket.EMPTY
        else pending.copyOfRange(result.consumed, pending.size)
        guardDesync()
        return result.packets
    }

    /** Forget any partial frame, e.g. after a reconnect. Not counted as a desync. */
    fun reset() {
        pending = BmapPacket.EMPTY
    }

    private fun guardDesync() {
        if (pending.isEmpty()) return
        val badHeader = pending.size >= BmapPacket.HEADER_SIZE &&
            BmapOperator.fromCode(pending[2].toInt() and 0x0F) == null
        if (!badHeader && pending.size <= maxPending) return
        val dropped = pending
        pending = BmapPacket.EMPTY
        desyncCount++
        droppedBytes += dropped.size
        onDesync?.invoke(dropped)
    }

    companion object {
        /** Well above the 259-byte maximum frame, so a legitimate short read never trips it. */
        const val DEFAULT_MAX_PENDING = 1024
    }
}
