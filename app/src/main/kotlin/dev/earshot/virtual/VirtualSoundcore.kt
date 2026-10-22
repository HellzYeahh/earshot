package dev.earshot.virtual

import dev.earshot.bt.ByteTransport
import dev.earshot.bt.SoundcoreModelTransport
import dev.earshot.protocol.soundcore.SoundcoreCommands
import dev.earshot.protocol.soundcore.SoundcoreFrameBuffer
import dev.earshot.protocol.soundcore.SoundcoreHeader
import dev.earshot.protocol.soundcore.SoundcoreModel
import dev.earshot.protocol.soundcore.SoundcorePacket
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The earbud (or headphone) side of any Soundcore model that has a [dev.earshot.protocol.soundcore.SoundcoreLayout],
 * in-process: demo mode and the end-to-end tests. It lays its `01 01` state out from the model's own
 * table (header, serial, mode block, length byte, case battery, settings), answers the state request,
 * applies `06 81` and the documented settings, answers the session-init the table asks for, and, like
 * real firmware, stays silent on anything it doesn't know. Written from the offsets in the research
 * notes only; no capture bytes are used (the OpenSCQ30 captures are GPL).
 *
 * The switches exist for the tests: [ignoreWrites] acknowledges a write and discards it (the Space 2
 * before its handshake), [bareAckFirst] answers the first state request with an empty body.
 */
class VirtualSoundcore(
    val model: SoundcoreModel,
    var leftBattery: Int = 4,
    var rightBattery: Int = 5,
    var caseBattery: Int = 3,
    var ignoreWrites: Boolean = false,
    var bareAckFirst: Boolean = false,
) {
    private val layout = checkNotNull(model.layout) { "${model.code} has no layout to emulate" }

    /** The sound-mode block as stored: noise cancelling, manual level 3 / adaptive 1, manual type, wind off. */
    var modeBlock: ByteArray = ByteArray(layout.codec.size).also {
        it[1] = 0x31
        if (layout.codec.mirrorsAmbientAt2) it[2] = 0
    }

    /** Single-byte settings by id, and for auto-off `enabled` / `index`. */
    val settings = mutableMapOf<String, Int>()
    var autoOffEnabled: Boolean = false
    var autoOffIndex: Int = 1

    /** Every command received, for the tests. */
    val received = mutableListOf<SoundcorePacket>()

    private var answeredState = false

    fun stateBody(): ByteArray {
        val b = ByteArray(layout.stateSize ?: (layout.modeBlockAt + layout.codec.size))
        val serial = model.serialPrefix + "DEMO00000001"
        when (model.header) {
            SoundcoreHeader.G2H -> {
                b[0] = leftBattery.toByte()
                "02.45".toByteArray().copyInto(b, 2)
                serial.toByteArray().copyInto(b, 7)
            }
            else -> {
                b[1] = 1
                b[2] = leftBattery.toByte()
                b[3] = rightBattery.toByte()
                "01.23".toByteArray().copyInto(b, 6)
                "01.23".toByteArray().copyInto(b, 11)
                serial.toByteArray().copyInto(b, 16)
            }
        }
        layout.lengthByteAt?.let { b[it] = (layout.modeBlockAt - it).toByte() }
        modeBlock.copyInto(b, layout.modeBlockAt)
        layout.caseBatteryAt?.let { b[it] = caseBattery.toByte() }
        layout.caseFirmwareAt?.let { "03.04".toByteArray().copyInto(b, it) }
        for (s in layout.settings) {
            if (s.autoOff) {
                b[s.at] = if (autoOffEnabled) 1 else 0
                b[s.at + 1] = autoOffIndex.toByte()
            } else {
                b[s.at] = (settings[s.id] ?: 0).toByte()
            }
        }
        return b
    }

    /** The reply to [packet], or null (unknown command: no answer). */
    fun answer(packet: SoundcorePacket): SoundcorePacket? {
        received += packet
        fun ack(body: ByteArray = ByteArray(0)) = SoundcorePacket(packet.command, body, inbound = true)
        return when (packet.command) {
            SoundcoreCommands.REQUEST_STATE -> {
                if (bareAckFirst && !answeredState) {
                    answeredState = true
                    ack()
                } else {
                    answeredState = true
                    ack(stateBody())
                }
            }
            SoundcoreCommands.SESSION_INFO -> if (layout.sessionInit) ack(ByteArray(47)) else null
            SoundcoreCommands.SESSION_INIT -> if (layout.sessionInit) ack(byteArrayOf(0)) else null
            SoundcoreCommands.SET_SOUND_MODES -> {
                if (packet.body.size != layout.codec.size) null
                else {
                    if (!ignoreWrites) modeBlock = packet.body.copyOf()
                    ack()
                }
            }
            else -> {
                val setting = layout.settings.firstOrNull { it.command == packet.command }
                if (setting == null) null
                else {
                    if (!ignoreWrites) {
                        if (setting.autoOff) {
                            autoOffEnabled = packet.body.getOrNull(0)?.toInt() == 1
                            autoOffIndex = packet.body.getOrNull(1)?.toInt() ?: autoOffIndex
                        } else {
                            settings[setting.id] = packet.body.firstOrNull()?.toInt() ?: 0
                        }
                    }
                    ack()
                }
            }
        }
    }
}

/** A [ByteTransport] whose far end is a [VirtualSoundcore]; it tells the link factory which model it plays. */
class VirtualSoundcoreTransport(private val unit: VirtualSoundcore) : ByteTransport, SoundcoreModelTransport {
    private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
    private val frames = SoundcoreFrameBuffer()

    override val soundcoreModel: SoundcoreModel get() = unit.model
    override var isConnected: Boolean = true
    override val incoming: Flow<ByteArray> = inbound.receiveAsFlow()

    override suspend fun write(bytes: ByteArray) {
        for (packet in frames.feed(bytes)) unit.answer(packet)?.let { inbound.trySend(it.encode()) }
    }

    /** Push a frame as the earbuds would (battery change, mode tapped on the buds). */
    fun push(packet: SoundcorePacket) {
        inbound.trySend(packet.encode())
    }

    override fun close() {
        isConnected = false
        inbound.close()
    }
}

/** The Soundcore model a demo device plays, or null for the original Sleep A30 demo. */
fun DemoDevice.soundcoreModel(): SoundcoreModel? = when (this) {
    DemoDevice.SOUNDCORE_LIBERTY_4_NC -> SoundcoreModel.LIBERTY_4_NC
    DemoDevice.SOUNDCORE_SPACE_ONE_PRO -> SoundcoreModel.SPACE_ONE_PRO
    else -> null
}
