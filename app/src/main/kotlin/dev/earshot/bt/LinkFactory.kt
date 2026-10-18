package dev.earshot.bt

import android.bluetooth.BluetoothDevice
import dev.earshot.protocol.qcultra2.BoseModel
import kotlinx.coroutines.CoroutineScope

/** What [ConnectionManager] needs from a live session, whichever protocol it speaks. */
interface ManagedLink {
    val isOpen: Boolean

    /** Suspends until the session is closed, by us or by the link. Returns the cause (null when local). */
    suspend fun awaitClosed(): Throwable?

    fun close()
}

/**
 * How one family of headphones is reached: which socket to open, how to confirm the far end really
 * answers, and what to do for the demo transport. [ConnectionManager] owns the reconnect policy and
 * is the same for every family; a factory only knows how to open one session.
 */
interface LinkFactory<C : ManagedLink> {
    /** What to tell the user when the control connection is silent: usually another app holds it. */
    val busyMessage: String get() = ConnectionManager.MESSAGE_CHANNEL_BUSY

    /** The family this stack drives; the manager stands down for a device [DeviceFamily.resolve] puts in another one. */
    val family: DeviceFamily

    /**
     * Open a session to [device] and prove it answers (so a socket that opens but stays silent is
     * reported as a busy or wrong channel here rather than after CONNECTED is published). Throws
     * [RfcommConnectException] with `looksBusy` when it opened but nothing answered, or any other
     * exception when it could not open. On failure nothing is left open.
     */
    suspend fun open(device: BluetoothDevice, name: String?, prefs: DevicePrefs, scope: CoroutineScope): C

    /** Wrap an already-open transport (demo mode: no Bluetooth, no probe). */
    fun adopt(transport: ByteTransport, scope: CoroutineScope): C
}

/**
 * The Bose BMAP family: channel 2 on the QC Ultra, 8 on the QC45 and QC Headphones, guessed from
 * the Bluetooth name, falling back to the other channel when the first stays silent, and
 * remembered per device once one answers.
 */
class BoseLinkFactory : LinkFactory<BmapConnection> {
    /** Flipped after a silent channel while none has been learned yet: the name guess may be wrong. */
    @Volatile
    private var tryOtherChannel = false

    /** The device [tryOtherChannel] was decided for: a different device starts from its own guess. */
    @Volatile
    private var lastAddress: String? = null

    override val family: DeviceFamily get() = DeviceFamily.BOSE

    override suspend fun open(device: BluetoothDevice, name: String?, prefs: DevicePrefs, scope: CoroutineScope): BmapConnection {
        val address = device.address
        if (!address.equals(lastAddress, ignoreCase = true)) {
            tryOtherChannel = false
            lastAddress = address
        }
        val learned = prefs.channelFor(address)
        var connection: BmapConnection? = null
        try {
            val guessed = learned ?: (BoseModel.fromName(name)?.rfcommChannel ?: RfcommTransport.CHANNEL)
            val channel = if (tryOtherChannel) BoseModel.otherChannel(guessed) else guessed
            val transport = RfcommTransport.connect(device, channel)
            connection = BmapConnection(transport, scope)
            // A socket that opens but never answers is another app holding the channel (or the wrong
            // channel); find out now (one 2 s GET) rather than after publishing CONNECTED with empty
            // state. Throws RfcommConnectException(looksBusy), which the manager turns into the busy message.
            LivenessProbe.check(connection)
            transport.channel?.let { if (it != learned) prefs.saveChannel(address, it) }
            tryOtherChannel = false
            return connection
        } catch (e: RfcommConnectException) {
            if (learned == null && e.looksBusy) tryOtherChannel = !tryOtherChannel
            connection?.close()
            throw e
        } catch (t: Throwable) {
            // Cancelled mid-probe (Disconnect, a new device, Bluetooth off) or any other failure: do not
            // leave the socket held, or the next connect looks "busy" because of ourselves.
            connection?.close()
            throw t
        }
    }

    override fun adopt(transport: ByteTransport, scope: CoroutineScope): BmapConnection = BmapConnection(transport, scope)
}
