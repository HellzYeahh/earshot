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
 * The Bose BMAP family. The channel is probed, not assumed: within one [open] the factory tries
 * the learned channel, the model's channel guessed from the Bluetooth name, then 2, 8, 9 and the
 * two SDP lookups ([BmapCandidates.order]), and keeps the first that passes the strict
 * [LivenessProbe]. Why: two QC Headphones units put BMAP on channel 8 and 9 respectively and
 * other channels accept the socket without speaking BMAP (bosectl #22/#26/#44). The winner is
 * remembered per device; when the learned one stops working the walk falls through and the new
 * winner replaces it.
 */
class BoseLinkFactory internal constructor(
    private val search: BmapChannelSearch,
) : LinkFactory<BmapConnection> {
    constructor() : this(BmapChannelSearch())

    override val family: DeviceFamily get() = DeviceFamily.BOSE

    override suspend fun open(device: BluetoothDevice, name: String?, prefs: DevicePrefs, scope: CoroutineScope): BmapConnection {
        val address = device.address
        val learned = prefs.candidateFor(address)
        val candidates = BmapCandidates.order(learned, BoseModel.fromName(name)?.rfcommChannel)
        BtLog.d("BMAP candidates for $address: ${candidates.joinToString()} (learned: ${learned ?: "none"})")
        // A socket that opens but never answers BMAP is another app holding the channel or the wrong
        // channel; find out now rather than after publishing CONNECTED with empty state. The search
        // throws RfcommConnectException(looksBusy) when something opened but nothing answered, which
        // the manager turns into the busy message. It closes every socket it does not return.
        val found = search.find(candidates, scope) { RfcommTransport.connectCandidate(device, it) }
        if (found.candidate != learned) {
            try {
                prefs.saveCandidate(address, found.candidate)
            } catch (t: Throwable) {
                found.connection.close()
                throw t
            }
        }
        return found.connection
    }

    override fun adopt(transport: ByteTransport, scope: CoroutineScope): BmapConnection = BmapConnection(transport, scope)
}
