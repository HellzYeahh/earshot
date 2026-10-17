package dev.earshot.service

import dev.earshot.bt.BmapRequester
import dev.earshot.bt.ReplyMode
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.protocol.BmapError
import dev.earshot.protocol.BmapOperator
import dev.earshot.protocol.BmapPacket
import dev.earshot.protocol.FunctionAddress
import dev.earshot.protocol.hexToBytes

/**
 * Scriptable [BmapRequester]: the test decides what the "headphones" answer per address (and
 * optionally per operator). Unscripted addresses answer ERROR 4 (FuncNotSupp), which is what
 * the real unit does for functions it lacks. Every request and send is recorded in [sent].
 */
class FakeRequester : BmapRequester {
    override var isOpen: Boolean = true

    val sent = ArrayList<BmapPacket>()
    val requestModes = ArrayList<ReplyMode>()

    private val handlers = HashMap<Pair<FunctionAddress, BmapOperator?>, (BmapPacket) -> List<BmapPacket>>()

    /** Script [address]. A null [operator] covers every operator and replaces earlier operator-specific scripts. */
    fun on(address: FunctionAddress, operator: BmapOperator? = null, handler: (BmapPacket) -> List<BmapPacket>) {
        if (operator == null) handlers.keys.removeAll { it.first == address }
        handlers[address to operator] = handler
    }

    /** GET → one STATUS with [hex]. */
    fun status(address: FunctionAddress, hex: String) = on(address, BmapOperator.GET) { listOf(statusFrame(address, hex)) }

    /** SET_GET → STATUS echoing the written payload (what Settings `[1.x]` and `[31.10]` do). */
    fun echo(address: FunctionAddress) = on(address, BmapOperator.SET_GET) { listOf(BmapPacket(address.block, address.function, BmapOperator.STATUS, it.payload)) }

    /** SET_GET → STATUS with a fixed payload regardless of what was written. */
    fun echoWith(address: FunctionAddress, hex: String) = on(address, BmapOperator.SET_GET) { listOf(statusFrame(address, hex)) }

    fun error(address: FunctionAddress, error: BmapError, operator: BmapOperator? = null) =
        on(address, operator) { listOf(errorFrame(address, error)) }

    /** No frame ever comes back for [address]: the connection's timeout fires. */
    fun silent(address: FunctionAddress, operator: BmapOperator? = null) =
        on(address, operator) { throw HeadphoneCommandException("$address: no reply from the headphones") }

    override suspend fun request(packet: BmapPacket, mode: ReplyMode, timeoutMs: Long): List<BmapPacket> {
        if (!isOpen) throw HeadphoneCommandException("Headphones not connected")
        sent += packet
        requestModes += mode
        val handler = handlers[packet.address to packet.operator] ?: handlers[packet.address to null]
            ?: return listOf(errorFrame(packet.address, BmapError.FUNC_NOT_SUPPORTED))
        return handler(packet)
    }

    override suspend fun send(packet: BmapPacket) {
        if (!isOpen) throw HeadphoneCommandException("Headphones not connected")
        sent += packet
    }

    fun requestsTo(address: FunctionAddress): List<BmapPacket> = sent.filter { it.matches(address) }

    companion object {
        fun statusFrame(address: FunctionAddress, hex: String) =
            BmapPacket(address.block, address.function, BmapOperator.STATUS, hex.replace(" ", "").hexToBytes())

        fun errorFrame(address: FunctionAddress, error: BmapError) =
            BmapPacket(address.block, address.function, BmapOperator.ERROR, byteArrayOf(error.code.toByte()))

        /**
         * A 48-byte `[31.6]` STATUS in the Bose app's layout: byte 41 changeable flags (0x1d on
         * user slots), byte 45 the "?" flag (1 on user slots), wind at byte 46.
         */
        fun modeStatus(index: Int, name: String, cncWire: Int, spatial: Int = 0, wind: Boolean = false, anc: Boolean = true, editable: Boolean = index > 3): BmapPacket {
            val p = ByteArray(48)
            p[0] = index.toByte()
            p[3] = if (editable) 1 else 0
            p[4] = if (name != "None") 1 else 0
            name.toByteArray().copyInto(p, 6)
            p[41] = if (editable) 0x1d else 0x00
            p[42] = cncWire.toByte()
            p[44] = spatial.toByte()
            p[45] = if (editable) 1 else 0
            p[46] = if (wind) 1 else 0
            p[47] = if (anc) 1 else 0
            return BmapPacket(31, 6, BmapOperator.STATUS, p)
        }
    }
}
