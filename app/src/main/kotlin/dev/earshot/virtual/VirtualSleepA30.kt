package dev.earshot.virtual

import dev.earshot.bt.ByteTransport
import dev.earshot.protocol.soundcore.SoundcoreCommands
import dev.earshot.protocol.soundcore.SoundcoreFrameBuffer
import dev.earshot.protocol.soundcore.SoundcorePacket
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The earbud side of a Soundcore Sleep A30, in-process: demo mode and the end-to-end tests. Answers
 * the state request with the 150-byte layout ([dev.earshot.protocol.soundcore.D1301State]), applies
 * noise cancelling (`06 87`), tap controls (`10 94`) and listening mode (`01 A9`), and, like the real
 * earbuds, stays silent on any command it doesn't know.
 */
class VirtualSleepA30(
    var ancOn: Boolean = true,
    var tapControlsDisabled: Boolean = false,
    var leftBattery: Int = 7,
    var rightBattery: Int = 6,
) {
    /** Every command received, for the tests. */
    val received = mutableListOf<SoundcorePacket>()

    /** Single-byte settings by state offset (listening mode 65, prompts, sleep options), see D1301State. */
    val settings = mutableMapOf<Int, Int>()

    fun stateBody(): ByteArray {
        val b = ByteArray(150)
        b[0] = 1
        b[1] = 1
        b[2] = leftBattery.toByte()
        b[3] = rightBattery.toByte()
        "01.91".toByteArray().copyInto(b, 4)
        "01.91".toByteArray().copyInto(b, 9)
        "1301DEMO00000001".toByteArray().copyInto(b, 14)
        "01.68".toByteArray().copyInto(b, 36)
        b[140] = if (ancOn) 1 else 0
        b[146] = if (tapControlsDisabled) 1 else 0
        for ((offset, value) in settings) b[offset] = value.toByte()
        return b
    }

    /** The reply to [packet], or null (unknown command: no answer). */
    fun answer(packet: SoundcorePacket): SoundcorePacket? {
        received += packet
        return when (packet.command) {
            SoundcoreCommands.REQUEST_STATE -> SoundcorePacket(packet.command, stateBody(), inbound = true)
            SoundcoreCommands.SET_ANC_D1301 -> {
                ancOn = packet.body.firstOrNull()?.toInt() == 1
                SoundcorePacket(packet.command, inbound = true)
            }
            SoundcoreCommands.SET_TAP_DISABLED -> {
                tapControlsDisabled = packet.body.firstOrNull()?.toInt() == 1
                SoundcorePacket(packet.command, inbound = true)
            }
            SoundcoreCommands.SET_SLEEP_TIMER -> {
                // [FF FF FF action]: only the post-sleep action (offset 144).
                packet.body.getOrNull(3)?.let { settings[144] = it.toInt() and 0xFF }
                SoundcorePacket(packet.command, inbound = true)
            }
            in SETTING_OFFSETS -> {
                settings[SETTING_OFFSETS.getValue(packet.command)] = packet.body.firstOrNull()?.toInt() ?: 0
                SoundcorePacket(packet.command, inbound = true)
            }
            else -> null
        }
    }
}

/** Where each single-byte Sleep A30 setting sits in the state body. */
private val SETTING_OFFSETS = mapOf(
    SoundcoreCommands.SET_LISTENING_MODE to 65,
    SoundcoreCommands.SET_DEFAULT_LISTENING_MODE to 66,
    SoundcoreCommands.SET_LOW_BATTERY_PROMPT to 69,
    SoundcoreCommands.SET_AUTO_OFF_PROMPT to 137,
    SoundcoreCommands.SET_LISTENING_MODE_PROMPT to 138,
    SoundcoreCommands.SET_INCOMING_CALLS to 141,
    SoundcoreCommands.SET_ANC_PROMPT to 147,
    SoundcoreCommands.SET_AUTO_SWITCH_ASLEEP to 148,
)

/** A [ByteTransport] whose far end is a [VirtualSleepA30]. */
class VirtualSleepA30Transport(private val unit: VirtualSleepA30) : ByteTransport {
    private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
    private val frames = SoundcoreFrameBuffer()

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
